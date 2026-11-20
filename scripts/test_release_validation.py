#!/usr/bin/env python3
"""Offline API-fixture regressions; no network, build, signing or credentials."""
import copy
import datetime
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


spec = importlib.util.spec_from_file_location(
    "release_validation", Path(__file__).with_name("check-release-validation.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

REPOSITORY = "release-fixture/magpie-mobile"
SHA = "a" * 40
BASE = "/repos/" + REPOSITORY
RUNS_PATH = BASE + "/actions/workflows/validate.yml/runs"


def run_record(run_id=10, attempt=1, status="completed", conclusion="success"):
    created = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    created += datetime.timedelta(seconds=run_id)
    return {
        "id": run_id, "workflow_id": 41, "path": gate.WORKFLOW_PATH,
        "name": "Validation", "head_sha": SHA, "event": "push",
        "status": status, "conclusion": conclusion, "run_attempt": attempt,
        "created_at": created.isoformat(),
        "repository": {"id": 7, "full_name": REPOSITORY},
        "head_repository": {"id": 7, "full_name": REPOSITORY},
    }


def job_record(name, job_id=1, run_id=10, attempt=1):
    return {"id": job_id, "name": name, "run_id": run_id, "run_attempt": attempt,
            "head_sha": SHA, "status": "completed", "conclusion": "success"}


class FakeApi:
    def __init__(self, runs=None, detail=None, jobs=None, final_runs=None):
        self.runs = copy.deepcopy(runs if runs is not None else [run_record()])
        selected = max(self.runs, key=lambda run: (run["created_at"], run["id"]))
        self.detail = copy.deepcopy(detail if detail is not None else selected)
        self.jobs = copy.deepcopy(jobs if jobs is not None else [
            job_record("regression", run_id=self.detail["id"], attempt=self.detail["run_attempt"]),
            job_record("runtime-emulator", 2, self.detail["id"], self.detail["run_attempt"]),
        ])
        self.final_runs = copy.deepcopy(final_runs)
        self.workflow = {"id": 41, "path": gate.WORKFLOW_PATH}
        self.calls = []
        self.listings = 0

    def get(self, path, params=None):
        self.calls.append((path, copy.deepcopy(params)))
        if path == BASE + "/actions/workflows/validate.yml":
            return copy.deepcopy(self.workflow)
        if path == RUNS_PATH:
            if params["head_sha"] != SHA:
                raise AssertionError("Run search must be restricted to the exact release SHA")
            if params["page"] == 1:
                self.listings += 1
            records = self.final_runs if self.listings > 1 and self.final_runs is not None else self.runs
            return self.page(records, "workflow_runs", params)
        if path == BASE + f"/actions/runs/{self.detail['id']}":
            return copy.deepcopy(self.detail)
        if path == BASE + f"/actions/runs/{self.detail['id']}/attempts/{self.detail['run_attempt']}/jobs":
            return self.page(self.jobs, "jobs", params)
        raise AssertionError("Unexpected API request: " + path)

    @staticmethod
    def page(records, key, params):
        start = (params["page"] - 1) * params["per_page"]
        return {"total_count": len(records), key: copy.deepcopy(records[start:start + params["per_page"]])}


class ReleaseValidationTest(unittest.TestCase):
    def verify(self, api):
        return gate.verify_validation(api, REPOSITORY, SHA)

    def blocked(self, api):
        with self.assertRaises(gate.ValidationError):
            self.verify(api)

    def test_exact_commit_and_both_required_jobs_succeed(self):
        optional = job_record("runtime-arm64-capabilities", 3)
        optional["conclusion"] = "skipped"  # Capability probe is not an ARM64 execution claim.
        result = self.verify(FakeApi(jobs=[job_record("regression"),
                                          job_record("runtime-emulator", 2), optional]))
        self.assertEqual({"run_id": 10, "attempt": 1,
                          "url": f"https://github.com/{REPOSITORY}/actions/runs/10/attempts/1"}, result)

    def test_manual_validation_is_also_trusted(self):
        record = run_record()
        record["event"] = "workflow_dispatch"
        self.verify(FakeApi(runs=[record]))

    def test_wrong_commit_workflow_id_or_path_cannot_publish(self):
        for key, wrong in (("head_sha", "b" * 40), ("workflow_id", 999),
                           ("path", ".github/workflows/same-name.yml")):
            with self.subTest(key=key):
                record = run_record()
                record[key] = wrong
                self.blocked(FakeApi(runs=[record]))
                api = FakeApi()
                api.detail[key] = wrong
                self.blocked(api)

    def test_workflow_metadata_path_must_be_exact(self):
        api = FakeApi()
        api.workflow["path"] = ".github/workflows/other.yml"
        self.blocked(api)

    def test_pr_same_name_or_fork_run_cannot_supply_verification(self):
        for event in ("pull_request", "pull_request_target", "workflow_run"):
            with self.subTest(event=event):
                record = run_record()
                record["event"] = event
                self.blocked(FakeApi(runs=[record]))
        for field in ("repository", "head_repository"):
            with self.subTest(field=field):
                record = run_record()
                record[field] = {"id": 99, "full_name": "foreign/magpie-mobile"}
                self.blocked(FakeApi(runs=[record]))

    def test_successful_pr_does_not_hide_failed_trusted_run(self):
        failure = run_record(conclusion="failure")
        pr = run_record(11)
        pr["event"] = "pull_request"
        self.blocked(FakeApi(runs=[failure, pr], detail=failure))

    def test_latest_failed_or_unfinished_run_never_falls_back_to_old_success(self):
        for status, conclusion in (("completed", "failure"), ("completed", "cancelled"),
                                   ("queued", None), ("in_progress", None)):
            with self.subTest(status=status, conclusion=conclusion):
                latest = run_record(11, status=status, conclusion=conclusion)
                # Deliberately put the older success last to catch order assumptions.
                self.blocked(FakeApi(runs=[latest, run_record()]))

    def test_non_successful_completed_run_is_rejected(self):
        for conclusion in (None, "skipped", "neutral", "timed_out", "action_required"):
            with self.subTest(conclusion=conclusion):
                self.blocked(FakeApi(runs=[run_record(conclusion=conclusion)]))

    def test_latest_attempt_status_overrides_stale_successful_run_list(self):
        for status, conclusion in (("completed", "failure"), ("queued", None), ("in_progress", None)):
            with self.subTest(status=status):
                self.blocked(FakeApi(detail=run_record(attempt=2, status=status, conclusion=conclusion)))

    def test_successful_new_attempt_uses_only_its_own_jobs(self):
        api = FakeApi(runs=[run_record(attempt=2)])
        self.assertEqual(2, self.verify(api)["attempt"])
        self.assertTrue(any(path.endswith("/attempts/2/jobs") for path, _ in api.calls))
        # Successes from attempt 1 may not fill a missing job from attempt 2.
        api.jobs[0]["run_attempt"] = 1
        self.blocked(api)
        self.blocked(FakeApi(runs=[run_record(attempt=2)],
                             jobs=[job_record("runtime-emulator", attempt=2)]))

    def test_required_jobs_cannot_be_missing_duplicated_skipped_or_unfinished(self):
        for name in gate.REQUIRED_JOBS:
            with self.subTest(name=name, issue="missing"):
                self.blocked(FakeApi(jobs=[job_record(other, index + 1)
                                          for index, other in enumerate(gate.REQUIRED_JOBS) if other != name]))
            with self.subTest(name=name, issue="duplicate"):
                self.blocked(FakeApi(jobs=[job_record("regression"), job_record("runtime-emulator", 2),
                                          job_record(name, 3)]))
            for status, conclusion in (("completed", "skipped"), ("completed", "failure"),
                                       ("completed", "cancelled"), ("queued", None), ("in_progress", None)):
                with self.subTest(name=name, status=status, conclusion=conclusion):
                    api = FakeApi()
                    job = next(job for job in api.jobs if job["name"] == name)
                    job.update(status=status, conclusion=conclusion)
                    self.blocked(api)

    def test_job_commit_run_and_attempt_must_match(self):
        for field, wrong in (("head_sha", "b" * 40), ("run_id", 11), ("run_attempt", 2)):
            with self.subTest(field=field):
                api = FakeApi()
                api.jobs[0][field] = wrong
                self.blocked(api)

    def test_all_run_pages_are_read_before_selecting_latest(self):
        records = [run_record(index) for index in range(1, 102)]
        records[-1]["conclusion"] = "failure"
        api = FakeApi(runs=records)
        self.blocked(api)
        self.assertIn((RUNS_PATH, {"head_sha": SHA, "per_page": 100, "page": 2}), api.calls)

    def test_required_jobs_can_appear_on_later_pages(self):
        jobs = [job_record("optional-" + str(index), index + 10) for index in range(99)]
        jobs += [job_record("regression"), job_record("runtime-emulator", 2)]
        api = FakeApi(jobs=jobs)
        self.verify(api)
        self.assertTrue(any(path.endswith("/attempts/1/jobs") and params["page"] == 2
                            for path, params in api.calls))

    def test_new_run_or_rerun_during_job_read_blocks_publication(self):
        for new in (run_record(11), run_record(attempt=2)):
            with self.subTest(run_id=new["id"], attempt=new["run_attempt"]):
                self.blocked(FakeApi(final_runs=[new]))
        self.blocked(FakeApi(final_runs=[run_record(status="in_progress", conclusion=None)]))

    def test_pagination_incomplete_duplicate_or_excessive_results_fail_closed(self):
        payloads = [
            {"total_count": 1001, "workflow_runs": []},
            {"total_count": 1, "workflow_runs": []},
            {"total_count": 2, "workflow_runs": [run_record(), run_record()]},
        ]
        for payload in payloads:
            with self.subTest(payload=payload):
                api = FakeApi()
                original = api.get
                api.get = lambda path, params=None: copy.deepcopy(payload) if path == RUNS_PATH else original(path, params)
                self.blocked(api)

    def test_pagination_total_changes_are_not_silently_accepted(self):
        api = FakeApi(runs=[run_record(index) for index in range(1, 102)])
        original = api.get
        def changing_total(path, params=None):
            payload = original(path, params)
            if path == RUNS_PATH and params["page"] == 2:
                payload["total_count"] += 1
            return payload
        api.get = changing_total
        self.blocked(api)

    def test_missing_run_or_invalid_environment_is_rejected(self):
        api = FakeApi()
        api.runs = []
        self.blocked(api)
        for repository, sha in (("", SHA), ("owner/repo/extra", SHA), (REPOSITORY, "main")):
            with self.subTest(repository=repository, sha=sha), self.assertRaises(gate.ValidationError):
                gate.verify_validation(FakeApi(), repository, sha)

    def test_api_errors_never_echo_token_urls_or_response_bodies(self):
        secret = "synthetic-secret-not-for-output"
        for error in (URLError("https://invalid/" + secret),
                      HTTPError("https://invalid/" + secret, 403, secret, {}, None)):
            with self.subTest(error=type(error).__name__):
                api = gate.GitHubApi(secret)
                with patch.object(api.opener, "open", side_effect=error), \
                     self.assertRaises(gate.ValidationError) as caught:
                    api.get(BASE + "/actions/workflows/validate.yml")
                self.assertNotIn(secret, str(caught.exception))
        self.assertIsNone(gate.NoRedirect().redirect_request(None, None, 302, "", {}, "https://foreign/"))

    def test_main_records_attempt_permalink_only_after_success(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "output"
            env = {"GH_TOKEN": "synthetic-secret", "GITHUB_REPOSITORY": REPOSITORY,
                   "GITHUB_SHA": SHA, "GITHUB_OUTPUT": str(output)}
            with patch.dict(os.environ, env, clear=True), patch.object(gate, "GitHubApi", return_value=FakeApi()), \
                 patch("sys.stdout", new_callable=io.StringIO) as stdout:
                self.assertEqual(0, gate.main())
            self.assertIn("attempt=1\n", output.read_text())
            self.assertIn("/actions/runs/10/attempts/1", output.read_text())
            self.assertNotIn(env["GH_TOKEN"], stdout.getvalue() + output.read_text())
            output.unlink()
            api = FakeApi(runs=[run_record(conclusion="failure")])
            with patch.dict(os.environ, env, clear=True), patch.object(gate, "GitHubApi", return_value=api), \
                 patch("sys.stderr", new_callable=io.StringIO) as stderr:
                self.assertEqual(1, gate.main())
            self.assertFalse(output.exists())
            self.assertNotIn(env["GH_TOKEN"], stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
