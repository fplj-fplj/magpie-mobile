#!/usr/bin/env python3
"""Offline fixtures for guarded Release cleanup; no network or Git tag mutation."""
import copy
import importlib.util
from pathlib import Path
import unittest
from urllib.parse import parse_qs, urlsplit


spec = importlib.util.spec_from_file_location(
    "release_cleanup", Path(__file__).with_name("cleanup-releases.py"))
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)

REPOSITORY = "release-fixture/magpie-mobile"
BASE = "/repos/" + REPOSITORY


def release(release_id, tag, draft=False, prerelease=False):
    return {
        "id": release_id,
        "tag_name": tag,
        "draft": draft,
        "prerelease": prerelease,
    }


class FakeApi:
    def __init__(self, releases, latest_id=None):
        self.releases = copy.deepcopy(releases)
        self.latest_id = latest_id
        self.calls = []

    def request(self, method, path, missing=False):
        self.calls.append((method, path))
        parsed = urlsplit(path)
        route = parsed.path
        if method == "GET" and route == BASE + "/releases/latest":
            return copy.deepcopy(next(
                (item for item in self.releases if item["id"] == self.latest_id), None))
        if method == "GET" and route == BASE + "/releases":
            page = int(parse_qs(parsed.query)["page"][0])
            per_page = int(parse_qs(parsed.query)["per_page"][0])
            start = (page - 1) * per_page
            return copy.deepcopy(self.releases[start:start + per_page])
        if method == "GET" and route.startswith(BASE + "/releases/"):
            release_id = int(route.rsplit("/", 1)[1])
            return copy.deepcopy(next(
                (item for item in self.releases if item["id"] == release_id), None))
        if method == "DELETE" and route.startswith(BASE + "/releases/"):
            release_id = int(route.rsplit("/", 1)[1])
            self.releases = [item for item in self.releases if item["id"] != release_id]
            return None
        raise AssertionError("Unexpected release cleanup API call: " + method + " " + path)


class ReleaseCleanupTest(unittest.TestCase):
    def setUp(self):
        self.releases = [
            release(1, "v0.1.1-older"),
            release(2, "v0.1.2-retained"),
            release(3, "v0.1.3-latest"),
        ]
        self.keep = ["v0.1.2-retained", "v0.1.3-latest"]

    def run_cleanup(self, api, *, keep=None, apply=False, confirmation=None):
        lines = []
        result = cleanup.cleanup_releases(
            api, REPOSITORY, keep or self.keep, apply=apply,
            confirmation=confirmation, output=lines.append)
        return result, lines

    def test_default_is_dry_run_and_never_mutates(self):
        api = FakeApi(self.releases, latest_id=3)
        (protected, planned), lines = self.run_cleanup(api)
        self.assertEqual([2, 3], [item["id"] for item in protected])
        self.assertEqual([1], [item["id"] for item in planned])
        self.assertFalse(any(method == "DELETE" for method, _ in api.calls))
        self.assertTrue(any(line.startswith("DRY RUN:") for line in lines))

    def test_apply_requires_exact_confirmation_before_api_calls(self):
        for confirmation in (None, "delete", "DELETE RELEASE OBJECTS "):
            with self.subTest(confirmation=confirmation):
                api = FakeApi(self.releases, latest_id=3)
                with self.assertRaises(cleanup.CleanupError):
                    self.run_cleanup(api, apply=True, confirmation=confirmation)
                self.assertEqual([], api.calls)

    def test_latest_and_every_explicit_keep_tag_are_mandatory(self):
        api = FakeApi(self.releases, latest_id=3)
        with self.assertRaises(cleanup.CleanupError):
            self.run_cleanup(api, keep=["v0.1.2-retained"])
        self.assertFalse(any(method == "DELETE" for method, _ in api.calls))

        api = FakeApi(self.releases, latest_id=3)
        with self.assertRaises(cleanup.CleanupError):
            self.run_cleanup(api, keep=self.keep + ["v9.9.9-missing"])
        self.assertFalse(any(method == "DELETE" for method, _ in api.calls))

    def test_unprotected_draft_blocks_the_entire_plan(self):
        releases = self.releases + [release(4, "v0.1.4-draft", draft=True)]
        api = FakeApi(releases, latest_id=3)
        with self.assertRaises(cleanup.CleanupError):
            self.run_cleanup(api)
        self.assertFalse(any(method == "DELETE" for method, _ in api.calls))

    def test_confirmed_apply_deletes_only_unprotected_release_objects(self):
        api = FakeApi(self.releases, latest_id=3)
        _, lines = self.run_cleanup(
            api, apply=True, confirmation=cleanup.CONFIRMATION)
        self.assertEqual([2, 3], [item["id"] for item in api.releases])
        self.assertTrue(any(line == "DELETED v0.1.1-older (1); Git tag retained" for line in lines))
        self.assertFalse(any("/git/" in path for _, path in api.calls))

    def test_current_latest_change_during_apply_aborts_further_deletion(self):
        api = FakeApi(self.releases, latest_id=3)
        original_request = api.request

        def changing_request(method, path, missing=False):
            result = original_request(method, path, missing=missing)
            if method == "GET" and urlsplit(path).path == BASE + "/releases/1":
                api.latest_id = 1
            return result

        api.request = changing_request
        with self.assertRaises(cleanup.CleanupError):
            self.run_cleanup(api, apply=True, confirmation=cleanup.CONFIRMATION)
        self.assertFalse(any(method == "DELETE" for method, _ in api.calls))

    def test_only_release_delete_routes_are_exposed(self):
        api = FakeApi(self.releases, latest_id=3)
        with self.assertRaises(AssertionError):
            api.request("DELETE", BASE + "/git/refs/tags/v0.1.1-older")


if __name__ == "__main__":
    unittest.main()
