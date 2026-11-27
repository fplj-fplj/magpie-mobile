#!/usr/bin/env python3
"""Offline tests for lock parsing, stable-release detection and three-way sync."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("sync-upstream.py")
spec = importlib.util.spec_from_file_location("sync_upstream", SCRIPT)
sync = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = sync
spec.loader.exec_module(sync)


class FakeApi:
    def __init__(self, responses):
        self.responses = responses

    def get(self, path):
        value = self.responses[path]
        if isinstance(value, Exception):
            raise value
        return value


class UpstreamSyncTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "source"
        self.patch_root = self.root / "patch"
        self.lock_path = self.root / "upstream.lock.properties"
        self.report = self.root / "evidence" / "report.json"
        self.patch_root.mkdir()
        self.repo.mkdir()
        self.environment = {
            **os.environ,
            "GIT_AUTHOR_NAME": "Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
            "GIT_COMMITTER_NAME": "Fixture",
            "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        }

    def command(self, *args, check=True):
        return subprocess.run(
            args,
            cwd=self.repo,
            env=self.environment,
            check=check,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def git(self, *args, check=True):
        return self.command("git", *args, check=check)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-m", message)
        return self.git("rev-parse", "HEAD").stdout.strip()

    def make_fixture(self, conflict=False, overlay_collision=False):
        self.git("init")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.repo / "go.mod").write_text(
            "module github.com/yetone/magpie\n\ngo 1.26.8\n",
            encoding="utf-8",
        )
        source_dir = self.repo / "internal"
        source_dir.mkdir()
        padding = "".join(f"const Padding{index} = {index}\n" for index in range(12))
        base = f'package sample\n\nconst Value = "old"\n{padding}const Keep = true\n'.encode()
        (source_dir / "sample.go").write_bytes(base)
        base_revision = self.commit("base")

        overlay = self.patch_root / "overlay/internal/mobilepolicy"
        overlay.mkdir(parents=True)
        overlay_data = b"package mobilepolicy\nconst Enabled = true\n"
        (overlay / "enabled.go").write_bytes(overlay_data)

        manifest = {
            "schemaVersion": 1,
            "sourceRevision": base_revision,
            "goVersion": "1.26.8",
            "checks": [{
                "path": "go.mod",
                "sha256": hashlib.sha256((self.repo / "go.mod").read_bytes()).hexdigest(),
            }],
            "patches": [{
                "path": "internal/sample.go",
                "sha256": hashlib.sha256(base).hexdigest(),
                "resultSha256": hashlib.sha256(
                    base.replace(b'"old"', b'"android"')
                ).hexdigest(),
                "replacements": [{"old": '"old"', "new": '"android"'}],
            }],
            "overlays": [{
                "path": "internal/mobilepolicy/enabled.go",
                "sha256": hashlib.sha256(overlay_data).hexdigest(),
            }],
        }
        (self.patch_root / "manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )
        self.lock_path.write_text(
            "\n".join((
                "schemaVersion=1",
                "repository=yetone/magpie",
                "tag=v0.1.3781",
                "revision=" + base_revision,
                "version=0.1.3781",
                "goVersion=1.26.8",
                "coreVersion=0.1.3781-androidhost.4",
                "coreSuffix=-androidhost.4",
                "",
            )),
            encoding="utf-8",
        )

        if conflict:
            target = base.replace(b'"old"', b'"upstream"')
        else:
            target = base.replace(b"Keep = true", b"Keep = false")
        (self.repo / "internal/sample.go").write_bytes(target)
        if overlay_collision:
            collision = self.repo / "internal/mobilepolicy/enabled.go"
            collision.parent.mkdir()
            collision.write_text("upstream-owned\n", encoding="utf-8")
        target_revision = self.commit("target")
        self.git("tag", "v0.1.4759")
        return base_revision, target_revision, target

    def adapt(self, target_revision):
        return sync.adapt_upstream(
            lock_path=self.lock_path,
            patch_root=self.patch_root,
            upstream_root=self.repo,
            target_tag="v0.1.4759",
            target_revision=target_revision,
            report_path=self.report,
            enforce_android_security=False,
        )

    def test_detect_accepts_only_stable_release_and_compares_revision(self):
        lock = sync.LOCK_READER.load_lock(self._write_minimal_lock())
        api = FakeApi({
            "/repos/yetone/magpie/releases/latest": {
                "tag_name": "v0.1.4759",
                "draft": False,
                "prerelease": False,
                "html_url": "https://example.invalid/release",
            },
        })
        with mock.patch.object(sync, "resolve_tag_commit", return_value="b" * 40):
            result = sync.detect_release(api, lock)
        self.assertTrue(result["updateAvailable"])
        self.assertEqual("v0.1.4759", result["tag"])
        self.assertEqual("0.1.4759", result["version"])
        self.assertEqual("0.1.4759-androidhost.4", result["coreVersion"])

        api.responses["/repos/yetone/magpie/releases/latest"]["tag_name"] = "v0.1.3781"
        with mock.patch.object(sync, "resolve_tag_commit", return_value="a" * 40):
            current = sync.detect_release(api, lock)
        self.assertFalse(current["updateAvailable"])

        api.responses["/repos/yetone/magpie/releases/latest"]["prerelease"] = True
        with self.assertRaisesRegex(sync.SyncError, "non-prerelease"):
            sync.detect_release(api, lock)

    def test_annotated_release_tag_is_peeled_to_commit(self):
        tag_object = "b" * 40
        commit = "c" * 40
        api = FakeApi({
            "/repos/yetone/magpie/git/ref/tags/v0.1.4759": {
                "ref": "refs/tags/v0.1.4759",
                "object": {"type": "tag", "sha": tag_object},
            },
            f"/repos/yetone/magpie/git/tags/{tag_object}": {
                "sha": tag_object,
                "object": {"type": "commit", "sha": commit},
            },
        })
        self.assertEqual(commit, sync.resolve_tag_commit(api, "yetone/magpie", "v0.1.4759"))

    def _write_minimal_lock(self):
        self.lock_path.write_text(
            "\n".join((
                "schemaVersion=1",
                "repository=yetone/magpie",
                "tag=v0.1.3781",
                "revision=" + "a" * 40,
                "version=0.1.3781",
                "goVersion=1.26.8",
                "coreVersion=0.1.3781-androidhost.4",
                "coreSuffix=-androidhost.4",
                "",
            )),
            encoding="utf-8",
        )
        return self.lock_path

    def test_three_way_success_regenerates_whole_file_patch_and_lock(self):
        _, target_revision, target = self.make_fixture()
        code, report = self.adapt(target_revision)
        self.assertEqual(0, code)
        self.assertEqual("adapted", report["status"])

        manifest = json.loads((self.patch_root / "manifest.json").read_text(encoding="utf-8"))
        lock = sync.LOCK_READER.load_lock(self.lock_path)
        self.assertEqual(target_revision, manifest["sourceRevision"])
        self.assertEqual(target_revision, lock["revision"])
        self.assertEqual("v0.1.4759", lock["tag"])
        self.assertEqual("0.1.4759-androidhost.4", lock["coreVersion"])
        self.assertEqual(1, len(manifest["patches"]))
        self.assertEqual(1, len(manifest["patches"][0]["replacements"]))
        replacement = manifest["patches"][0]["replacements"][0]
        self.assertEqual(target.decode(), replacement["old"])
        self.assertIn('const Keep = false', replacement["new"])
        self.assertIn('const Value = "android"', replacement["new"])
        self.assertTrue((self.report.parent / "adaptation.patch").is_file())

    def test_conflict_retains_lock_and_reports_markers(self):
        _, target_revision, _ = self.make_fixture(conflict=True)
        lock_before = self.lock_path.read_bytes()
        manifest_before = (self.patch_root / "manifest.json").read_bytes()
        code, report = self.adapt(target_revision)
        self.assertEqual(2, code)
        self.assertEqual("conflict", report["status"])
        self.assertIn("internal/sample.go", report["conflictFiles"])
        self.assertIn("internal/sample.go", report["conflictMarkers"])
        self.assertEqual(lock_before, self.lock_path.read_bytes())
        self.assertEqual(manifest_before, (self.patch_root / "manifest.json").read_bytes())
        self.assertTrue(self.report.with_suffix(".md").is_file())

    def test_known_import_and_updater_conflicts_are_resolved_narrowly(self):
        (self.repo / "internal").mkdir(parents=True)
        imported = self.repo / "internal/imports.go"
        imported.write_text(
            "package sample\n\nimport (\n"
            "<<<<<<< ours\n"
            '\t"github.com/yetone/magpie/internal/library"\n'
            "=======\n"
            '\t"github.com/yetone/magpie/internal/mobilepolicy"\n'
            ">>>>>>> theirs\n"
            ")\n",
            encoding="utf-8",
        )
        self.assertEqual(
            "internal/imports.go",
            sync.auto_resolve_conflict_file(self.repo, "internal/imports.go"),
        )
        merged_imports = imported.read_text(encoding="utf-8")
        self.assertNotIn("<<<<<<<", merged_imports)
        self.assertLess(
            merged_imports.index('internal/library"'),
            merged_imports.index('internal/mobilepolicy"'),
        )

        updater = self.repo / "internal/gui/update.go"
        updater.parent.mkdir(parents=True)
        updater.write_text(
            "package gui\n\nfunc (u *updater) start() {\n"
            "<<<<<<< ours\n"
            "\tu.behind = update.ReadBehind(Version)\n"
            "=======\n"
            "\tif mobilepolicy.Enabled {\n"
            "\t\treturn // APK owns native updates; do not place, spawn or register restarter\n"
            "\t}\n"
            ">>>>>>> theirs\n"
            "}\n",
            encoding="utf-8",
        )
        self.assertEqual(
            "internal/gui/update.go",
            sync.auto_resolve_conflict_file(self.repo, "internal/gui/update.go"),
        )
        merged_updater = updater.read_text(encoding="utf-8")
        self.assertLess(merged_updater.index("if mobilepolicy.Enabled"), merged_updater.index("u.behind"))

    def test_android_web_handler_keeps_artifact_routes_inside_session_proof(self):
        web = self.repo / "internal/gui/web.go"
        web.parent.mkdir(parents=True)
        web.write_text(
            "package gui\n\nfunc webHandler(port string) {\n"
            "<<<<<<< ours\n"
            "\treturn webCompressed(androidHostEditor(port, key, where, box, page))\n"
            "=======\n"
            '\treturn webCompressed(webArtifactPages(webGuard("magpie_web_"+port, key, where, box, '
            "webArtifactLinks(webOwnPage(page)))))\n"
            ">>>>>>> theirs\n}\n",
            encoding="utf-8",
        )
        self.assertEqual(
            "internal/gui/web.go",
            sync.auto_resolve_conflict_file(self.repo, "internal/gui/web.go"),
        )
        merged = web.read_text(encoding="utf-8")
        self.assertNotIn("<<<<<<<", merged)
        self.assertIn("androidHostEditor(port, key, where, box, webArtifactPages(", merged)
        self.assertIn("webArtifactLinks(webOwnPage(page))", merged)
        self.assertNotIn('webGuard("magpie_web_', merged)

        self.assertIsNone(sync.merge_android_web_artifact_handler(
            "\treturn webCompressed(androidHostEditor(port, key, where, box, page))\n",
            "\treturn webCompressed(webArtifactPages(page))\n",
        ))

    def test_finalize_regenerates_from_reviewed_worktree(self):
        _, target_revision, target = self.make_fixture(conflict=True)
        code, report = self.adapt(target_revision)
        self.assertEqual(2, code)
        self.assertEqual("conflict", report["status"])
        applied = self.git(
            "apply",
            "--3way",
            "--index",
            "--whitespace=nowarn",
            str(self.report.parent / "adaptation.patch"),
            check=False,
        )
        self.assertNotEqual(0, applied.returncode)
        (self.repo / "internal/sample.go").write_bytes(target.replace(b'"upstream"', b'"android"'))
        self.git("add", "internal/sample.go")

        finalized_report = self.root / "evidence" / "finalized.json"
        code, result = sync.finalize_upstream(
            lock_path=self.lock_path,
            patch_root=self.patch_root,
            resolved_root=self.repo,
            target_tag="v0.1.4759",
            target_revision=target_revision,
            report_path=finalized_report,
            enforce_android_security=False,
        )
        self.assertEqual(0, code)
        self.assertEqual("finalize", result["mode"])
        manifest = json.loads((self.patch_root / "manifest.json").read_text(encoding="utf-8"))
        lock = sync.LOCK_READER.load_lock(self.lock_path)
        self.assertEqual(target_revision, manifest["sourceRevision"])
        self.assertEqual(target_revision, lock["revision"])
        self.assertEqual("v0.1.4759", lock["tag"])
        self.assertIn('const Value = "android"', manifest["patches"][0]["replacements"][0]["new"])
        self.assertTrue(finalized_report.is_file())
        self.assertTrue((finalized_report.parent / "adaptation.patch").is_file())

    def test_existing_overlay_path_is_a_reported_conflict(self):
        _, target_revision, _ = self.make_fixture(overlay_collision=True)
        code, report = self.adapt(target_revision)
        self.assertEqual(2, code)
        self.assertEqual(["internal/mobilepolicy/enabled.go"], report["conflictFiles"])
        self.assertFalse(report["conflictMarkers"])

    def test_lock_rejects_duplicate_unknown_and_core_mismatch(self):
        valid = self._write_minimal_lock().read_text(encoding="utf-8")
        cases = (
            valid + "extra=value\n",
            valid.replace("tag=v0.1.3781\n", "tag=v0.1.3782\n"),
            valid.replace("revision=", "revision=", 1) + "revision=" + "b" * 40 + "\n",
            valid.replace("coreVersion=0.1.3781-androidhost.4", "coreVersion=0.1.9999-androidhost.4"),
        )
        for index, text in enumerate(cases):
            with self.subTest(index=index):
                self.lock_path.write_text(text, encoding="utf-8")
                with self.assertRaises(sync.LOCK_READER.LockError):
                    sync.LOCK_READER.load_lock(self.lock_path)


class BuildLockContractTests(unittest.TestCase):
    def test_checked_in_package_retains_android_security_surface(self):
        root = sync.ROOT
        manifest = json.loads((root / "patches/mobile/manifest.json").read_text(encoding="utf-8"))
        sources = {
            patch["path"]: patch["replacements"][-1]["new"]
            for patch in manifest["patches"]
        }
        sync.validate_android_security_surface(sources)

    def test_checked_in_lock_and_consumers_are_coherent(self):
        root = sync.ROOT
        lock = sync.LOCK_READER.load_lock(root / "upstream.lock.properties")
        self.assertEqual("v" + lock["version"], lock["tag"])
        self.assertEqual(lock["version"] + lock["coreSuffix"], lock["coreVersion"])
        gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('upstreamLockValue("coreVersion")', gradle)
        self.assertIn('upstreamLockValue("tag")', gradle)
        self.assertIn('upstreamLockValue("revision")', gradle)
        for workflow_name in ("build.yml", "validate.yml"):
            workflow = (root / ".github/workflows" / workflow_name).read_text(encoding="utf-8")
            self.assertIn("scripts/read-upstream-lock.py --format github-output", workflow)
            self.assertIn("steps.upstream.outputs.revision", workflow)
        build = (root / ".github/workflows/build.yml").read_text(encoding="utf-8")
        for machine_field in (
            "Package:",
            "VersionName:",
            "VersionCode:",
            "CoreVersion:",
            "UpstreamTag:",
            "UpstreamRevision:",
            "Commit:",
        ):
            self.assertIn(machine_field, build)
        self.assertEqual(1, build.count("Commit:"))
        self.assertIn('tag="v${version_name}-${GITHUB_SHA:0:8}"', build)
        self.assertIn("upstream_tag=$(python3 scripts/read-upstream-lock.py --get tag)", build)
        self.assertIn("upstream_revision=$(python3 scripts/read-upstream-lock.py --get revision)", build)
        self.assertIn("Exactly the four", (root / "scripts/publish-verified-release.py").read_text(encoding="utf-8"))
        sync_workflow = (root / ".github/workflows/sync-upstream.yml").read_text(encoding="utf-8")
        self.assertIn("MAGPIE_UPSTREAM_SYNC_ENABLED", sync_workflow)
        self.assertIn("default: rikkahub-provider-layout", sync_workflow)
        self.assertNotIn("branches: [main]", sync_workflow)
        self.assertIn("github.event.client_payload.target_tag", sync_workflow)
        self.assertIn("gh workflow run validate.yml", sync_workflow)
        self.assertIn("publish_release=false", sync_workflow)
        self.assertNotIn("publish_release=true", sync_workflow)


if __name__ == "__main__":
    unittest.main()
