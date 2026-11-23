#!/usr/bin/env python3
"""Offline fixtures for assets, final Validation, and guarded Latest publication."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit


spec = importlib.util.spec_from_file_location(
    "release_publication", Path(__file__).with_name("publish-verified-release.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
REPOSITORY, SHA, VERSION = "release-fixture/magpie-mobile", "a" * 40, "0.1.0-fixture"
BASE, TAG = "/repos/" + REPOSITORY, f"v{VERSION}-{SHA[:8]}"
_AUTO_LATEST = object()


class FakeApi:
    def __init__(self, expected, draft=None, existing=None, latest=_AUTO_LATEST):
        self.expected = expected
        self.release = None if draft is None else {
            "id": 77, "tag_name": TAG, "target_commitish": SHA, "draft": draft,
            "prerelease": False, "name": "Keep this title", "body": "Keep these notes",
        }
        self.latest = (copy.deepcopy(self.release) if latest is _AUTO_LATEST and draft is False
                       else None if latest is _AUTO_LATEST else copy.deepcopy(latest))
        self.tag = {"type": "commit", "sha": SHA} if draft is not None else None
        self.assets = copy.deepcopy(existing or [])
        self.annotations, self.calls = {}, []
        self.fail_upload = self.corrupt_digest = None
        self.after_upload = None

    def request(self, method, path, payload=None, upload=None, missing=False):
        self.calls.append((method, path, copy.deepcopy(payload)))
        parsed = urlsplit(path)
        route = parsed.path
        if method == "GET":
            if route == BASE + "/releases/latest":
                return None if self.latest is None else copy.deepcopy(self.latest)
            if route == BASE + "/releases/tags/" + TAG or route == BASE + "/releases/77":
                return copy.deepcopy(self.release)
            if route == BASE + "/git/ref/tags/" + TAG:
                return None if self.tag is None else {"ref": "refs/tags/" + TAG, "object": copy.deepcopy(self.tag)}
            if route.startswith(BASE + "/git/tags/"):
                return {"object": copy.deepcopy(self.annotations[route.rsplit("/", 1)[1]])}
            if route == BASE + "/releases/77/assets":
                page = int(parse_qs(parsed.query)["page"][0])
                return copy.deepcopy(self.assets[(page - 1) * 100:page * 100])
        if method == "POST" and route == BASE + "/git/refs":
            if self.tag is not None:
                raise publisher.PublicationError("Reference already exists", 422)
            self.tag = {"type": "commit", "sha": payload["sha"]}
            return {"ref": payload["ref"], "object": copy.deepcopy(self.tag)}
        if method == "POST" and route == BASE + "/releases":
            if self.release is not None:
                raise AssertionError("A release must not be recreated on retry")
            if payload["draft"] is not True or payload["make_latest"] != "false":
                raise AssertionError("A new release must begin as a draft, never Latest")
            self.release = {"id": 77, **payload}
            return copy.deepcopy(self.release)
        if method == "POST" and route == BASE + "/releases/77/assets":
            if self.release["draft"] is not True:
                raise AssertionError("Must not upload to a public release")
            name = parse_qs(parsed.query)["name"][0]
            if self.fail_upload == name:
                raise publisher.PublicationError("Synthetic upload failure")
            content = Path(upload).read_bytes()
            item = {"id": max([asset["id"] for asset in self.assets] + [100]) + 1,
                    "name": name, "size": len(content), "state": "uploaded",
                    "digest": "sha256:" + hashlib.sha256(content).hexdigest()}
            if self.corrupt_digest == name:
                item["digest"] = "sha256:" + "0" * 64
            self.assets.append(item)
            if self.after_upload:
                self.after_upload(self)
            return copy.deepcopy(item)
        if method == "DELETE" and route.startswith(BASE + "/releases/assets/"):
            if self.release["draft"] is not True:
                raise AssertionError("Must not delete a public release asset")
            self.assets = [asset for asset in self.assets if asset["id"] != int(route.rsplit("/", 1)[1])]
            return None
        if method == "PATCH" and route == BASE + "/releases/77":
            if self.release["draft"] is not True:
                raise AssertionError("Must not change an existing public release")
            publisher.verify_assets(self.assets, self.expected)
            self.release.update(payload)
            if payload.get("make_latest") == "true":
                self.latest = copy.deepcopy(self.release)
            elif self.latest is not None and self.latest.get("id") == self.release["id"]:
                self.latest = None
            return copy.deepcopy(self.release)
        raise AssertionError("Unexpected release API call: " + method + " " + path)


class ReleasePublicationTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        apk = self.root / f"magpie-mobile-{VERSION}.apk"
        apk.write_bytes(b"synthetic signed APK")
        apk.with_name(apk.name + ".sha256").write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "  " + apk.name + "\n")
        self.root.joinpath("build-info.txt").write_text("verified build evidence")
        core = self.root / "release-artifacts/build-tmp/mobile-core-build-info.txt"
        core.parent.mkdir(parents=True)
        core.write_text("fixed core evidence")
        self.expected = publisher.collect_assets(VERSION, self.root)
        self.validation_calls = 0
        self.validation_result = {"run_id": 10, "attempt": 1}
        self.validation_error = None

    def assets(self):
        return [{"id": index + 1, "name": name, "size": asset["size"],
                 "digest": asset["digest"], "state": "uploaded"}
                for index, (name, asset) in enumerate(self.expected.items())]

    def validation(self):
        self.validation_calls += 1
        if self.validation_error is not None:
            raise self.validation_error
        return copy.deepcopy(self.validation_result)

    def publish(self, api, make_latest=None):
        return publisher.publish_release(
            api, REPOSITORY, SHA, VERSION, "verified notes", self.expected,
            expected_validation=(10, 1), validation_check=self.validation,
            make_latest=make_latest)

    def assert_no_mutation(self, api):
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))

    def assert_not_published(self, api):
        self.assertFalse(any(method == "PATCH" for method, _, _ in api.calls))

    def test_new_release_stays_draft_until_four_assets_are_verified(self):
        api = FakeApi(self.expected)
        self.assertEqual(TAG, self.publish(api)[0])
        self.assertFalse(api.release["draft"])
        self.assertFalse(api.release["prerelease"])
        self.assertEqual("true", api.release["make_latest"])
        self.assertEqual(1, self.validation_calls)
        last_upload = max(index for index, (method, path, _) in enumerate(api.calls)
                          if method == "POST" and "/assets?" in path)
        publication = next(index for index, (method, _, _) in enumerate(api.calls) if method == "PATCH")
        self.assertTrue(any(method == "GET" and "/assets?" in path
                            for method, path, _ in api.calls[last_upload + 1:publication]))

    def test_final_validation_runs_after_uploads_and_immediately_before_publication(self):
        api = FakeApi(self.expected)
        validation_ids = []
        api_calls_before_validation = []

        def validation():
            self.assertTrue(any(method == "POST" and "/assets?" in path
                                for method, path, _ in api.calls))
            self.assertFalse(any(method == "PATCH" for method, _, _ in api.calls))
            validation_ids.append((10, 1))
            api_calls_before_validation.append(len(api.calls))
            return {"run_id": 10, "attempt": 1}

        publisher.publish_release(
            api, REPOSITORY, SHA, VERSION, "verified notes", self.expected,
            expected_validation=(10, 1), validation_check=validation)
        self.assertEqual([(10, 1)], validation_ids)
        publication = next(index for index, (method, _, _) in enumerate(api.calls)
                           if method == "PATCH")
        self.assertEqual(api_calls_before_validation, [publication])

    def test_changed_or_failed_final_validation_leaves_release_as_draft(self):
        cases = (
            ({"run_id": 11, "attempt": 1}, None),
            (None, publisher.validation_gate.ValidationError("new Validation failed")),
        )
        for result, error in cases:
            with self.subTest(result=result, error=type(error).__name__ if error else None):
                self.validation_calls = 0
                self.validation_result = result
                self.validation_error = error
                api = FakeApi(self.expected)
                with self.assertRaises(publisher.PublicationError):
                    self.publish(api)
                self.assertTrue(api.release["draft"])
                self.assert_not_published(api)
                self.assertEqual(1, self.validation_calls)

    def test_older_recovery_never_replaces_current_latest(self):
        newer_latest = {
            "id": 88, "tag_name": "v0.1.1-newer-release", "draft": False,
            "prerelease": False,
        }
        api = FakeApi(self.expected, latest=newer_latest)
        self.publish(api)
        self.assertEqual("false", api.release["make_latest"])
        self.assertEqual(newer_latest, api.latest)
        self.assertEqual(1, self.validation_calls)

    def test_concurrent_latest_change_during_upload_keeps_candidate_draft(self):
        api = FakeApi(self.expected)
        api.after_upload = lambda peer: setattr(peer, "latest", {
            "id": 88, "tag_name": "v0.1.1-newer-release", "draft": False,
            "prerelease": False,
        })
        with self.assertRaisesRegex(publisher.PublicationError, "Latest changed"):
            self.publish(api)
        self.assertTrue(api.release["draft"])
        self.assert_not_published(api)

    def test_explicit_latest_override_is_passed_through(self):
        newer_latest = {
            "id": 88, "tag_name": "v9.9.9-newer-release", "draft": False,
            "prerelease": False,
        }
        api = FakeApi(self.expected, latest=newer_latest)
        self.publish(api, make_latest=True)
        self.assertEqual("true", api.release["make_latest"])
        self.assertEqual(api.release, api.latest)

    def test_keep_current_requires_an_existing_latest(self):
        api = FakeApi(self.expected, latest=None)
        with self.assertRaises(publisher.PublicationError):
            self.publish(api, make_latest=False)
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))

    def test_equal_version_at_different_commit_does_not_replace_latest(self):
        equal_latest = {
            "id": 88, "tag_name": "v0.1.0-existing", "draft": False,
            "prerelease": False,
        }
        api = FakeApi(self.expected, latest=equal_latest)
        self.publish(api)
        self.assertEqual("false", api.release["make_latest"])
        self.assertEqual(equal_latest, api.latest)

    def test_validation_url_identity_is_exact(self):
        url = f"https://github.com/{REPOSITORY}/actions/runs/123/attempts/2"
        self.assertEqual((123, 2), publisher.validation_url_identity(url, REPOSITORY))
        for invalid in (
                url + "/extra",
                url.replace("github.com", "example.com"),
                f"https://github.com/another/repo/actions/runs/123/attempts/2"):
            with self.subTest(url=invalid), self.assertRaises(publisher.PublicationError):
                publisher.validation_url_identity(invalid, REPOSITORY)

    def test_partial_upload_leaves_draft_and_retry_reuses_matching_assets(self):
        api = FakeApi(self.expected)
        api.fail_upload = list(self.expected)[1]
        with self.assertRaises(publisher.PublicationError):
            self.publish(api)
        self.assertTrue(api.release["draft"])
        self.assertEqual(1, len(api.assets))
        self.assert_not_published(api)
        api.fail_upload, api.calls = None, []
        self.publish(api)
        uploads = [path for method, path, _ in api.calls if method == "POST" and "/assets?" in path]
        self.assertEqual(3, len(uploads))
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.calls))

    def test_digest_mismatch_blocks_publication_then_only_draft_asset_is_replaced(self):
        api = FakeApi(self.expected, draft=True, existing=self.assets())
        api.assets[0]["digest"] = None
        self.publish(api)
        self.assertEqual(1, sum(method == "DELETE" for method, _, _ in api.calls))
        api = FakeApi(self.expected)
        api.corrupt_digest = list(self.expected)[0]
        with self.assertRaises(publisher.PublicationError):
            self.publish(api)
        self.assertTrue(api.release["draft"])
        self.assert_not_published(api)

    def test_existing_public_identical_release_is_read_only_idempotent_success(self):
        api = FakeApi(self.expected, draft=False, existing=self.assets())
        before = copy.deepcopy(api.release)
        self.assertIn("left unchanged", self.publish(api)[1])
        self.assertEqual(before, api.release)
        self.assert_no_mutation(api)
        self.assertEqual(1, self.validation_calls)

    def test_public_idempotent_path_requires_non_prerelease_and_expected_latest_state(self):
        cases = []

        prerelease = FakeApi(self.expected, draft=False, existing=self.assets())
        prerelease.release["prerelease"] = True
        cases.append(prerelease)

        not_latest = FakeApi(self.expected, draft=False, existing=self.assets(), latest=None)
        cases.append(not_latest)

        for api in cases:
            with self.subTest(pre=api.release["prerelease"], latest=api.latest):
                with self.assertRaises(publisher.PublicationError):
                    self.publish(api)
                self.assert_no_mutation(api)

    def test_public_asset_missing_extra_wrong_name_size_digest_or_state_refuses_changes(self):
        for problem in ("missing", "extra", "name", "size", "digest", "state"):
            with self.subTest(problem=problem):
                assets = self.assets()
                if problem == "missing":
                    assets.pop()
                elif problem == "extra":
                    assets.append({**assets[0], "id": 99, "name": "unexpected.txt"})
                else:
                    assets[0][problem] = 0 if problem == "size" else "different"
                api = FakeApi(self.expected, draft=False, existing=assets)
                with self.assertRaises(publisher.PublicationError):
                    self.publish(api)
                self.assert_no_mutation(api)

    def test_wrong_commit_tag_is_never_moved_for_new_draft_or_public_release(self):
        for draft in (None, True, False):
            with self.subTest(draft=draft):
                api = FakeApi(self.expected, draft=draft, existing=self.assets())
                api.tag = {"type": "commit", "sha": "b" * 40}
                with self.assertRaises(publisher.PublicationError):
                    self.publish(api)
                self.assert_no_mutation(api)

    def test_annotated_tag_resolves_to_expected_commit(self):
        api = FakeApi(self.expected, draft=True, existing=self.assets())
        api.tag = {"type": "tag", "sha": "b" * 40}
        api.annotations["b" * 40] = {"type": "commit", "sha": SHA}
        self.publish(api)

    def test_missing_public_tag_or_foreign_pending_draft_target_is_not_repaired(self):
        for draft in (False, True):
            with self.subTest(draft=draft):
                api = FakeApi(self.expected, draft=draft, existing=self.assets())
                api.tag = None
                api.release["target_commitish"] = "b" * 40
                with self.assertRaises(publisher.PublicationError):
                    self.publish(api)
                self.assert_no_mutation(api)

    def test_external_publication_or_retag_during_upload_blocks_later_mutations(self):
        for change in ("public", "tag"):
            with self.subTest(change=change):
                api = FakeApi(self.expected)
                def external_change(current):
                    if change == "public":
                        current.release["draft"] = False
                    else:
                        current.tag = {"type": "commit", "sha": "b" * 40}
                api.after_upload = external_change
                with self.assertRaises(publisher.PublicationError):
                    self.publish(api)
                self.assert_not_published(api)
                if change == "public":
                    self.assertEqual(1, len(api.assets))

    def test_unexpected_draft_assets_are_not_automatically_deleted(self):
        api = FakeApi(self.expected, draft=True, existing=[{**self.assets()[0], "name": "keep.txt"}])
        with self.assertRaises(publisher.PublicationError):
            self.publish(api)
        self.assert_no_mutation(api)

    def test_invalid_local_checksum_prevents_publication(self):
        self.root.joinpath(f"magpie-mobile-{VERSION}.apk.sha256").write_text("not the APK hash")
        with self.assertRaises(publisher.PublicationError):
            publisher.collect_assets(VERSION, self.root)

    def test_only_four_fixed_artifact_names_are_accepted(self):
        api = FakeApi(self.expected)
        for original in (list(self.expected)[0], "build-info.txt"):
            with self.subTest(original=original):
                changed = dict(self.expected)
                changed["unexpected.txt"] = changed.pop(original)
                with self.assertRaises(publisher.PublicationError):
                    publisher.publish_release(
                        api, REPOSITORY, SHA, VERSION, "notes", changed,
                        expected_validation=(10, 1), validation_check=self.validation)
        self.assertEqual([], api.calls)

    def test_network_errors_do_not_echo_token_or_response_content(self):
        secret = "synthetic-secret-must-not-appear"
        for error in (URLError("https://invalid/" + secret), HTTPError("https://invalid/" + secret, 403, secret, {}, None)):
            api = publisher.GitHubApi(secret)
            with patch.object(api.opener, "open", side_effect=error), self.assertRaises(publisher.PublicationError) as caught:
                api.request("GET", BASE + "/releases/tags/" + TAG)
            self.assertNotIn(secret, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
