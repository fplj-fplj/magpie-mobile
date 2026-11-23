#!/usr/bin/env python3
"""Stage verified assets, recheck Validation, then publish the exact draft."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


class PublicationError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_validation_gate():
    path = Path(__file__).with_name("check-release-validation.py")
    spec = importlib.util.spec_from_file_location("release_validation_gate", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Validation gate could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


validation_gate = load_validation_gate()


class GitHubApi:
    def __init__(self, token):
        self.token = token
        self.opener = build_opener(NoRedirect())

    def request(self, method, path, payload=None, upload=None, missing=False):
        host = "uploads.github.com" if upload is not None else "api.github.com"
        data = Path(upload).read_bytes() if upload is not None else (
            json.dumps(payload).encode() if payload is not None else None)
        request = Request("https://" + host + path, data=data, method=method, headers={
            "Accept": "application/vnd.github+json", "Authorization": "Bearer " + self.token,
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "magpie-mobile-release-publication",
            "Content-Type": "application/octet-stream" if upload is not None else "application/json",
        })
        try:
            with self.opener.open(request, timeout=120) as response:
                content = response.read(8 * 1024 * 1024 + 1)
            if len(content) > 8 * 1024 * 1024:
                raise PublicationError("GitHub API response exceeded the size limit")
            return json.loads(content) if content else None
        except HTTPError as error:
            if missing and method == "GET" and error.code == 404:
                return None
            raise PublicationError(f"GitHub API request failed (HTTP {error.code})", error.code) from None
        except (URLError, OSError, ValueError):
            raise PublicationError("GitHub API request or JSON decoding failed") from None


class ValidationApi:
    def __init__(self, api):
        self.api = api

    def get(self, path, params=None):
        if params:
            path += "?" + urlencode(params)
        return self.api.request("GET", path)


def object_id(record):
    value = record.get("id") if isinstance(record, dict) else None
    if type(value) is not int or value <= 0:
        raise PublicationError("GitHub API returned an invalid object id")
    return value


def positive_validation_identity(value):
    if (not isinstance(value, tuple) or len(value) != 2 or
            any(type(item) is not int or item <= 0 for item in value)):
        raise PublicationError("Expected Validation run id and attempt are invalid")
    return value


def validation_url_identity(url, repository):
    pattern = (r"^https://github\.com/" + re.escape(repository) +
               r"/actions/runs/([1-9][0-9]*)/attempts/([1-9][0-9]*)$")
    match = re.fullmatch(pattern, url or "")
    if match is None:
        raise PublicationError("VALIDATION_URL is missing or does not identify this repository")
    return int(match.group(1)), int(match.group(2))


def version_key(value):
    match = re.fullmatch(r"v?([0-9]+)\.([0-9]+)\.([0-9]+)(?:[-+][0-9A-Za-z._-]*)?", value or "")
    return tuple(int(part) for part in match.groups()) if match else None


def current_latest(api, base):
    latest = api.request("GET", base + "/releases/latest", missing=True)
    if latest is None:
        return None
    if (not isinstance(latest, dict) or latest.get("draft") is not False or
            latest.get("prerelease") is not False or not isinstance(latest.get("tag_name"), str)):
        raise PublicationError("GitHub API returned an invalid Latest release")
    object_id(latest)
    return latest


def should_promote_latest(latest, version, tag, make_latest):
    if make_latest is not None:
        if type(make_latest) is not bool:
            raise PublicationError("make_latest must be true or false")
        if make_latest is False and latest is None:
            raise PublicationError("Cannot preserve Latest because no current Latest exists")
        return make_latest
    if latest is None:
        return True
    if latest["tag_name"] == tag:
        return True
    requested = version_key(version)
    current = version_key(latest["tag_name"])
    if requested is None or current is None:
        raise PublicationError(
            "Cannot compare release versions with Latest; pass --make-latest or --no-make-latest")
    # Equal versions at a different commit are not promoted implicitly.
    return requested > current


def verify_latest_state(api, base, release_id, promote_latest):
    latest = current_latest(api, base)
    is_latest = latest is not None and object_id(latest) == release_id
    if promote_latest and not is_latest:
        raise PublicationError("GitHub did not confirm the published release as Latest")
    if not promote_latest and is_latest:
        raise PublicationError("Release unexpectedly replaced Latest")


def recheck_validation(api, repository, sha, expected):
    expected = positive_validation_identity(expected)
    try:
        result = validation_gate.verify_validation(ValidationApi(api), repository, sha)
    except validation_gate.ValidationError as error:
        raise PublicationError(f"Final Validation recheck failed: {error}") from None
    actual = positive_validation_identity((result.get("run_id"), result.get("attempt")))
    if actual != expected:
        raise PublicationError("Validation run/attempt changed after the build evidence was recorded")
    return actual


def tag_matches(api, base, tag, sha):
    reference = api.request("GET", base + "/git/ref/tags/" + quote(tag, safe=""), missing=True)
    if reference is None:
        return False
    target, seen = reference.get("object", {}), set()
    for _ in range(8):
        digest = target.get("sha", "")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{40}", digest) or digest in seen:
            raise PublicationError("Release tag has an invalid or cyclic target")
        seen.add(digest)
        if target.get("type") == "commit":
            if digest != sha:
                raise PublicationError("Existing release tag points to a different commit; refusing to move it")
            return True
        if target.get("type") != "tag":
            break
        target = api.request("GET", base + "/git/tags/" + digest).get("object", {})
    raise PublicationError("Release tag does not resolve to the expected commit")


def release_assets(api, base, release_id):
    records, ids = [], set()
    for page in range(1, 12):
        batch = api.request("GET", base + f"/releases/{release_id}/assets?per_page=100&page={page}")
        if not isinstance(batch, list) or len(batch) > 100:
            raise PublicationError("GitHub API returned an invalid release asset page")
        for item in batch:
            item_id = object_id(item)
            if item_id in ids:
                raise PublicationError("GitHub API repeated a release asset across pages")
            ids.add(item_id)
        records.extend(batch)
        if len(batch) < 100:
            return records
    raise PublicationError("Release asset pagination exceeded the limit")


def asset_matches(asset, expected):
    return (asset.get("name") == expected["name"] and type(asset.get("size")) is int and
            asset["size"] == expected["size"] and asset.get("digest") == expected["digest"] and
            asset.get("state") == "uploaded")


def verify_assets(actual, expected):
    if len(actual) != len(expected) or {item.get("name") for item in actual} != set(expected):
        raise PublicationError("Release must contain exactly the four expected assets")
    if any(not asset_matches(item, expected[item["name"]]) for item in actual):
        raise PublicationError("Release asset name, size or SHA-256 digest differs from the local artifact")


def collect_assets(version, root=Path(".")):
    apk = f"magpie-mobile-{version}.apk"
    paths = [root / apk, root / (apk + ".sha256"), root / "build-info.txt",
             root / "release-artifacts/build-tmp/mobile-core-build-info.txt"]
    result = {}
    for path in paths:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        size = path.stat().st_size
        if size <= 0:
            raise PublicationError("A required local release artifact is empty")
        result[path.name] = {"name": path.name, "path": path, "size": size,
                             "digest": "sha256:" + digest.hexdigest()}
    expected_checksum = result[apk]["digest"][7:] + "  " + apk + "\n"
    if paths[1].read_text() != expected_checksum:
        raise PublicationError("Local APK checksum does not match the APK")
    return result


def publish_release(api, repository, sha, version, notes, expected, *,
                    expected_validation, validation_check=None, make_latest=None):
    if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or
            not re.fullmatch(r"[0-9a-f]{40}", sha) or
            not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version)):
        raise PublicationError("Release repository, commit or version is invalid")
    expected_validation = positive_validation_identity(expected_validation)
    apk = f"magpie-mobile-{version}.apk"
    if set(expected) != {apk, apk + ".sha256", "build-info.txt", "mobile-core-build-info.txt"}:
        raise PublicationError("Exactly the four fixed release artifact names are required")
    base, tag = "/repos/" + repository, f"v{version}-{sha[:8]}"
    title = f"Magpie-Mobile {version} ({sha[:8]})"
    latest_before = current_latest(api, base)
    promote_latest = should_promote_latest(latest_before, version, tag, make_latest)

    def final_validation_check():
        if validation_check is None:
            recheck_validation(api, repository, sha, expected_validation)
            return
        try:
            result = validation_check()
        except validation_gate.ValidationError as error:
            raise PublicationError(f"Final Validation recheck failed: {error}") from None
        actual = positive_validation_identity((result.get("run_id"), result.get("attempt")))
        if actual != expected_validation:
            raise PublicationError("Validation run/attempt changed after the build evidence was recorded")

    release = api.request("GET", base + "/releases/tags/" + quote(tag, safe=""), missing=True)
    has_tag = tag_matches(api, base, tag, sha)
    if release is not None:
        if release.get("tag_name") != tag or type(release.get("draft")) is not bool:
            raise PublicationError("GitHub API returned a different or invalid release")
        release_id = object_id(release)
        if not release["draft"]:
            if not has_tag:
                raise PublicationError("Published release tag is missing; refusing to alter the release")
            if release.get("prerelease") is not False:
                raise PublicationError("Published release is still marked as a prerelease")
            verify_assets(release_assets(api, base, release_id), expected)
            final_validation_check()
            verify_latest_state(api, base, release_id, promote_latest)
            latest_outcome = "Latest" if promote_latest else "current Latest preserved"
            return tag, f"already public; assets and {latest_outcome} verified, release left unchanged"
        if not has_tag and release.get("target_commitish") != sha:
            raise PublicationError("Existing draft targets a different commit")
    if not has_tag:
        try:
            api.request("POST", base + "/git/refs", {"ref": "refs/tags/" + tag, "sha": sha})
        except PublicationError as error:
            if error.status != 422 or not tag_matches(api, base, tag, sha):
                raise
    if release is None:
        release = api.request("POST", base + "/releases", {
            "tag_name": tag, "target_commitish": sha, "name": title, "body": notes,
            "draft": True, "prerelease": False, "make_latest": "false",
        })
    release_id = object_id(release)

    def require_draft():
        current = api.request("GET", base + f"/releases/{release_id}")
        if (current.get("draft") is not True or current.get("tag_name") != tag or
                object_id(current) != release_id):
            raise PublicationError("Release is no longer the expected draft; refusing to modify it")

    require_draft()
    existing = release_assets(api, base, release_id)
    names = [item.get("name") for item in existing]
    if len(set(names)) != len(names) or any(name not in expected for name in names):
        raise PublicationError("Draft contains unexpected or duplicate assets; refusing automatic cleanup")
    for name, asset in expected.items():
        previous = next((item for item in existing if item.get("name") == name), None)
        if previous is not None and asset_matches(previous, asset):
            continue
        if previous is not None:
            require_draft()  # Only an unpublished draft may have an asset replaced on retry.
            api.request("DELETE", base + f"/releases/assets/{object_id(previous)}")
        require_draft()
        api.request("POST", base + f"/releases/{release_id}/assets?name=" + quote(name, safe=""),
                    upload=asset["path"])
    if not tag_matches(api, base, tag, sha):
        raise PublicationError("Release tag disappeared before publication")
    require_draft()
    verify_assets(release_assets(api, base, release_id), expected)
    latest_now = current_latest(api, base)
    if (None if latest_now is None else (object_id(latest_now), latest_now["tag_name"])) != (
            None if latest_before is None else (object_id(latest_before), latest_before["tag_name"])):
        raise PublicationError("Latest changed while staging the release; retry publication")
    # This is deliberately the last check after the potentially long attachment
    # upload and before the public PATCH. A newer or changed Validation blocks.
    final_validation_check()
    published = api.request("PATCH", base + f"/releases/{release_id}", {
        "name": title, "body": notes, "draft": False, "prerelease": False,
        "make_latest": "true" if promote_latest else "false",
    })
    if (object_id(published) != release_id or published.get("tag_name") != tag or
            published.get("draft") is not False or published.get("prerelease") is not False):
        raise PublicationError("GitHub API did not confirm the requested public release state")
    verify_latest_state(api, base, release_id, promote_latest)
    outcome = "published Latest" if promote_latest else "published without replacing Latest"
    return tag, outcome + " after verifying all four assets"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes-file", required=True, type=Path)
    parser.add_argument("--validation-run-id", default=os.environ.get("VALIDATION_RUN_ID"))
    parser.add_argument("--validation-attempt", default=os.environ.get("VALIDATION_ATTEMPT"))
    parser.add_argument("--validation-url", default=os.environ.get("VALIDATION_URL"))
    latest = parser.add_mutually_exclusive_group()
    latest.add_argument("--make-latest", dest="make_latest", action="store_const", const=True)
    latest.add_argument("--no-make-latest", dest="make_latest", action="store_const", const=False)
    args = parser.parse_args()
    try:
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if not token:
            raise PublicationError("A GitHub token with contents:write is required")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", args.version):
            raise PublicationError("Release version is invalid")
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        if args.validation_run_id is not None or args.validation_attempt is not None:
            try:
                expected_validation = (int(args.validation_run_id), int(args.validation_attempt))
            except (TypeError, ValueError):
                raise PublicationError("Validation run id and attempt must both be positive integers") from None
        else:
            expected_validation = validation_url_identity(args.validation_url, repository)
        tag, outcome = publish_release(
            GitHubApi(token), repository, os.environ.get("GITHUB_SHA", ""), args.version,
            args.notes_file.read_text(), collect_assets(args.version),
            expected_validation=expected_validation, make_latest=args.make_latest)
        print(f"PASS: {outcome}: {tag}")
        return 0
    except PublicationError as error:
        print(f"Release publication stopped: {error}. Build artifacts remain in Actions.", file=sys.stderr)
    except (OSError, ValueError, TypeError, AttributeError):
        print("Release publication stopped: invalid metadata or unreadable local artifact.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
