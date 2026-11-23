#!/usr/bin/env python3
"""Delete explicitly unprotected GitHub Release objects without deleting Git tags."""
import argparse
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


CONFIRMATION = "DELETE RELEASE OBJECTS"
PAGE_SIZE = 100
MAX_PAGES = 10


class CleanupError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHubApi:
    def __init__(self, token):
        self.token = token
        self.opener = build_opener(NoRedirect())

    def request(self, method, path, missing=False):
        request = Request("https://api.github.com" + path, method=method, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + self.token,
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "magpie-mobile-release-cleanup",
            "Content-Type": "application/json",
        })
        try:
            with self.opener.open(request, timeout=60) as response:
                content = response.read(8 * 1024 * 1024 + 1)
            if len(content) > 8 * 1024 * 1024:
                raise CleanupError("GitHub API response exceeded the size limit")
            return json.loads(content) if content else None
        except HTTPError as error:
            if missing and method == "GET" and error.code == 404:
                return None
            raise CleanupError(f"GitHub API request failed (HTTP {error.code})", error.code) from None
        except (URLError, OSError, ValueError):
            raise CleanupError("GitHub API request or JSON decoding failed") from None


def object_id(record):
    value = record.get("id") if isinstance(record, dict) else None
    if type(value) is not int or value <= 0:
        raise CleanupError("GitHub API returned an invalid Release id")
    return value


def valid_tag(record):
    tag = record.get("tag_name") if isinstance(record, dict) else None
    if not isinstance(tag, str) or not re.fullmatch(r"v[0-9][0-9A-Za-z._-]*", tag):
        raise CleanupError("GitHub API returned an invalid Release tag")
    return tag


def current_latest(api, base):
    latest = api.request("GET", base + "/releases/latest", missing=True)
    if latest is None:
        raise CleanupError("No current Latest release exists; refusing cleanup")
    object_id(latest)
    valid_tag(latest)
    if latest.get("draft") is not False or latest.get("prerelease") is not False:
        raise CleanupError("GitHub API returned an invalid current Latest release")
    return latest


def all_releases(api, base):
    records, ids = [], set()
    for page in range(1, MAX_PAGES + 1):
        batch = api.request("GET", base + f"/releases?per_page={PAGE_SIZE}&page={page}")
        if not isinstance(batch, list) or len(batch) > PAGE_SIZE:
            raise CleanupError("GitHub API returned an invalid Release page")
        for release in batch:
            release_id = object_id(release)
            if release_id in ids:
                raise CleanupError("GitHub API repeated a Release across pages")
            valid_tag(release)
            if type(release.get("draft")) is not bool:
                raise CleanupError("GitHub API returned an invalid Release draft state")
            ids.add(release_id)
        records.extend(batch)
        if len(batch) < PAGE_SIZE:
            return records
    raise CleanupError("Release pagination exceeded the limit")


def cleanup_plan(releases, latest, keep_tags):
    keep = set(keep_tags)
    if not keep:
        raise CleanupError("At least one explicit --keep tag is required")
    invalid = sorted(tag for tag in keep if not re.fullmatch(r"v[0-9][0-9A-Za-z._-]*", tag))
    if invalid:
        raise CleanupError("Invalid protected Release tag: " + ", ".join(invalid))

    by_tag = {}
    for release in releases:
        tag = valid_tag(release)
        if tag in by_tag:
            raise CleanupError("Duplicate Release tag returned by GitHub: " + tag)
        by_tag[tag] = release

    missing = sorted(keep - set(by_tag))
    if missing:
        raise CleanupError("Protected Release tag does not exist: " + ", ".join(missing))
    latest_tag = valid_tag(latest)
    if latest_tag not in keep:
        raise CleanupError("The current Latest tag must be included in --keep: " + latest_tag)
    if latest_tag not in by_tag or object_id(by_tag[latest_tag]) != object_id(latest):
        raise CleanupError("Current Latest is not present in the Release listing")

    unprotected_drafts = sorted(tag for tag, release in by_tag.items()
                                if release["draft"] and tag not in keep)
    if unprotected_drafts:
        raise CleanupError(
            "Unprotected draft Releases exist; resolve them manually: " + ", ".join(unprotected_drafts))

    protected = [by_tag[tag] for tag in sorted(keep)]
    to_delete = [release for tag, release in by_tag.items() if tag not in keep]
    to_delete.sort(key=lambda release: object_id(release))
    return protected, to_delete


def cleanup_releases(api, repository, keep_tags, *, apply=False, confirmation=None, output=print):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository or ""):
        raise CleanupError("Repository must be OWNER/NAME")
    if apply and confirmation != CONFIRMATION:
        raise CleanupError(f'--apply requires --confirm "{CONFIRMATION}"')
    if not apply and confirmation is not None:
        raise CleanupError("--confirm is only valid with --apply")

    base = "/repos/" + repository
    latest = current_latest(api, base)
    protected, to_delete = cleanup_plan(all_releases(api, base), latest, keep_tags)
    for release in protected:
        output(f"KEEP   {valid_tag(release)} ({object_id(release)})")
    for release in to_delete:
        output(f"DELETE {valid_tag(release)} ({object_id(release)})")
    if not apply:
        output(f"DRY RUN: no Release objects changed; {len(to_delete)} would be deleted")
        return protected, to_delete

    for release in to_delete:
        release_id = object_id(release)
        current = api.request("GET", base + f"/releases/{release_id}")
        if object_id(current) != release_id or valid_tag(current) != valid_tag(release):
            raise CleanupError("Release changed while cleanup was running")
        if current.get("draft") is True:
            raise CleanupError("Refusing to delete a Release that changed into a draft")
        latest_now = current_latest(api, base)
        if valid_tag(latest_now) not in set(keep_tags):
            raise CleanupError("Current Latest changed to a tag absent from --keep; no further deletions")
        if object_id(latest_now) == release_id:
            raise CleanupError("A planned deletion became Latest; no further deletions")
        # Deleting a Release object does not delete its Git tag.
        api.request("DELETE", base + f"/releases/{release_id}")
        output(f"DELETED {valid_tag(release)} ({release_id}); Git tag retained")
    output(f"APPLIED: deleted {len(to_delete)} Release objects; no Git tag was deleted")
    return protected, to_delete


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--keep", action="append", required=True,
                        help="Release tag to protect; repeat for every retained Release")
    parser.add_argument("--apply", action="store_true", help="Perform deletions; default is dry-run")
    parser.add_argument("--confirm", help=f'Use exactly "{CONFIRMATION}" with --apply')
    args = parser.parse_args()
    try:
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if not token:
            raise CleanupError(
                "A GitHub token is required; dry-run needs contents:read and --apply needs contents:write")
        cleanup_releases(GitHubApi(token), args.repository, args.keep,
                         apply=args.apply, confirmation=args.confirm)
        return 0
    except CleanupError as error:
        print(f"Release cleanup stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
