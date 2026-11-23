#!/usr/bin/env python3
"""Allow manual publication only after the exact commit's latest Validation attempt.

Build/artifact retention stays independent. Run this only in the publish job.
GitHub's run list reports the current attempt; use the attempt-specific jobs API
so a successful job from an earlier attempt cannot satisfy this release gate.
"""
import datetime
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


WORKFLOW_PATH = ".github/workflows/validate.yml"
REQUIRED_JOBS = ("regression", "runtime-emulator")
TRUSTED_EVENTS = {"push", "workflow_dispatch"}
PAGE_SIZE = 100
MAX_RESULTS = 1000  # GitHub caps filtered workflow-run searches at 1,000.


class ValidationError(Exception):
    """A fixed, credential-free reason publication must stop."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the Authorization header to a redirect.


class GitHubApi:
    def __init__(self, token):
        self.token = token
        self.opener = build_opener(NoRedirect())

    def get(self, path, params=None):
        url = "https://api.github.com" + path
        if params:
            url += "?" + urlencode(params)
        request = Request(url, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + self.token,
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "magpie-mobile-release-validation",
        })
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(8 * 1024 * 1024 + 1)
            if len(data) > 8 * 1024 * 1024:
                raise ValidationError("GitHub API response exceeded the size limit")
            result = json.loads(data)
        except HTTPError as error:
            raise ValidationError(f"GitHub API request failed (HTTP {error.code})") from None
        except (URLError, OSError, ValueError):
            # Do not echo server bodies, request headers, exception URLs or tokens.
            raise ValidationError("GitHub API request or JSON decoding failed") from None
        if not isinstance(result, dict):
            raise ValidationError("GitHub API returned an invalid response")
        return result


def positive_integer(value, label):
    if type(value) is not int or value <= 0:
        raise ValidationError(f"Invalid {label} in GitHub API response")
    return value


def all_pages(api, path, key, params=None):
    results, ids = [], set()
    expected_total = None
    for page in range(1, MAX_RESULTS // PAGE_SIZE + 1):
        payload = api.get(path, {**(params or {}), "per_page": PAGE_SIZE, "page": page})
        total = payload.get("total_count")
        records = payload.get(key)
        if type(total) is not int or not 0 <= total <= MAX_RESULTS:
            raise ValidationError("GitHub API pagination total is missing or exceeds the limit")
        if expected_total is not None and total != expected_total:
            raise ValidationError("GitHub API results changed while reading pages; retry publication")
        expected_total = total
        if not isinstance(records, list) or len(records) > PAGE_SIZE:
            raise ValidationError("GitHub API returned an invalid page")
        for record in records:
            if not isinstance(record, dict):
                raise ValidationError("GitHub API returned an invalid record")
            record_id = positive_integer(record.get("id"), "record id")
            if record_id in ids:
                raise ValidationError("GitHub API repeated a record across pages")
            ids.add(record_id)
        results.extend(records)
        if len(results) == expected_total:
            return results
        if not records or len(results) > expected_total:
            raise ValidationError("GitHub API returned incomplete pagination")
    raise ValidationError("GitHub API pagination limit reached")


def same_repository(record, repository):
    name = record.get("full_name") if isinstance(record, dict) else None
    return isinstance(name, str) and name.lower() == repository.lower()


def check_run_identity(run, repository, sha, workflow_id):
    if (run.get("head_sha") != sha or
            positive_integer(run.get("workflow_id"), "run workflow id") != workflow_id or
            run.get("path") != WORKFLOW_PATH or run.get("event") not in TRUSTED_EVENTS):
        raise ValidationError("Validation run commit, workflow or event does not match")
    origin, head = run.get("repository"), run.get("head_repository")
    if not same_repository(origin, repository) or not same_repository(head, repository):
        raise ValidationError("Validation run does not belong to the release repository")
    if (positive_integer(origin.get("id"), "repository id") !=
            positive_integer(head.get("id"), "head repository id")):
        raise ValidationError("Validation run used a foreign head repository")
    positive_integer(run.get("id"), "run id")
    positive_integer(run.get("run_attempt"), "run attempt")


def latest_run(api, base, repository, sha, workflow_id):
    candidates = []
    for run in all_pages(api, base + "/actions/workflows/validate.yml/runs", "workflow_runs",
                         {"head_sha": sha}):
        # A PR run (including one with an identical workflow name/SHA) is not a
        # trusted push/manual verification of the release repository.
        if (run.get("event") not in TRUSTED_EVENTS or
                not same_repository(run.get("repository"), repository) or
                not same_repository(run.get("head_repository"), repository)):
            continue
        check_run_identity(run, repository, sha, workflow_id)
        try:
            created = datetime.datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
            if created.tzinfo is None:
                raise ValueError()
            # GitHub preserves created_at when an old run is rerun. The new
            # attempt's run_started_at is the relevant ordering signal; a run
            # which has not started yet still orders by its creation time.
            started_text = run.get("run_started_at")
            started = (datetime.datetime.fromisoformat(started_text.replace("Z", "+00:00"))
                       if started_text else created)
            if started.tzinfo is None:
                raise ValueError()
        except (KeyError, AttributeError, TypeError, ValueError):
            raise ValidationError("Validation run timestamp is missing or invalid") from None
        candidates.append((started, run["run_attempt"], run["id"], run))
    if not candidates:
        raise ValidationError("No trusted Validation run exists for this exact commit")
    # Never search for a successful run: a newer failed/pending run must win.
    # The most recently started attempt wins, even when it belongs to an older
    # run record. Attempt and run id make equal timestamps deterministic.
    return max(candidates, key=lambda entry: entry[:3])[3]


def require_success(run):
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ValidationError("The latest Validation run attempt has not completed successfully")


def verify_validation(api, repository, sha):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValidationError("GITHUB_REPOSITORY is missing or invalid")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValidationError("GITHUB_SHA is missing or invalid")
    base = "/repos/" + repository
    workflow = api.get(base + "/actions/workflows/validate.yml")
    workflow_id = positive_integer(workflow.get("id"), "workflow id")
    if workflow.get("path") != WORKFLOW_PATH:
        raise ValidationError("Expected the exact validate.yml workflow")
    selected = latest_run(api, base, repository, sha, workflow_id)
    # Fetch the live run, not an earlier attempt embedded in a cached run list.
    run = api.get(base + f"/actions/runs/{selected['id']}")
    check_run_identity(run, repository, sha, workflow_id)
    if run["id"] != selected["id"]:
        raise ValidationError("GitHub API returned a different Validation run")
    require_success(run)
    attempt = run["run_attempt"]
    jobs = all_pages(api, base + f"/actions/runs/{run['id']}/attempts/{attempt}/jobs", "jobs")
    for job in jobs:
        if (positive_integer(job.get("run_id"), "job run id") != run["id"] or
                positive_integer(job.get("run_attempt"), "job run attempt") != attempt or
                job.get("head_sha") != sha):
            raise ValidationError("Validation jobs do not match the selected commit/run attempt")
    for name in REQUIRED_JOBS:
        matches = [job for job in jobs if job.get("name") == name]
        if len(matches) != 1:
            raise ValidationError(f"Required Validation job {name} is missing or duplicated")
        if matches[0].get("status") != "completed" or matches[0].get("conclusion") != "success":
            raise ValidationError(f"Required Validation job {name} did not complete successfully")
    # Re-list after job pagination so a new run/rerun cannot silently replace
    # the verification we just inspected. A changing state fails closed.
    latest = latest_run(api, base, repository, sha, workflow_id)
    if (latest["id"], latest["run_attempt"]) != (run["id"], attempt):
        raise ValidationError("Validation run/attempt changed during the publication check")
    require_success(latest)
    return {"run_id": run["id"], "attempt": attempt,
            "url": f"https://github.com/{repository}/actions/runs/{run['id']}/attempts/{attempt}"}


def main():
    try:
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if not token:
            raise ValidationError("A GitHub token with actions:read is required")
        result = verify_validation(GitHubApi(token), os.environ.get("GITHUB_REPOSITORY", ""),
                                   os.environ.get("GITHUB_SHA", ""))
        output = os.environ.get("GITHUB_OUTPUT")
        if output:
            with Path(output).open("a", encoding="utf-8") as stream:
                for key, value in result.items():
                    stream.write(f"{key}={value}\n")
        print(f"PASS: exact-commit Validation attempt verified: {result['url']}")
        return 0
    except ValidationError as error:
        print(f"Release blocked: {error}. Signed build artifacts remain in Actions.", file=sys.stderr)
        return 1
    except OSError:
        print("Release blocked: could not record Validation evidence.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
