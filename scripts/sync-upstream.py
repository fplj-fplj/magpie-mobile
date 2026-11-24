#!/usr/bin/env python3
"""Detect stable Magpie releases and three-way merge the Android host policy."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCK = ROOT / "upstream.lock.properties"
DEFAULT_PATCH_ROOT = ROOT / "patches" / "mobile"
DEFAULT_REPORT = ROOT / "build-tmp" / "upstream-sync" / "report.json"
CONFLICT_BLOCK = re.compile(
    r"(?ms)^<<<<<<< [^\r\n]*\r?\n(.*?)^=======\r?\n(.*?)^>>>>>>> [^\r\n]*\r?\n"
)
GO_IMPORT_LINE = re.compile(r'^\t+(?:(?:[A-Za-z_][A-Za-z0-9_]*|[._])\s+)?"[^"]+"$')


class SyncError(RuntimeError):
    """A source, release, Git, or generated package failed a safety check."""


def load_script(name: str, module_name: str):
    path = Path(__file__).with_name(name)
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise SyncError(f"cannot load {name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


LOCK_READER = load_script("read-upstream-lock.py", "sync_upstream_lock_reader")
APPLIER = load_script("apply-mobile-core.py", "sync_upstream_applier")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def atomic_write(path: Path, data: bytes, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".upstream-sync-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def run(command: list[str], *, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(command, cwd=cwd, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise SyncError(f"command failed ({result.returncode}): {' '.join(command)}: {detail}")
    return result


def git(root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    return run(["git", "-C", str(root), *args], check=check)


def git_text(root: Path, *args: str) -> str:
    return git(root, *args).stdout.decode("utf-8", errors="strict").strip()


def git_bytes(root: Path, *args: str) -> bytes:
    return git(root, *args).stdout


def git_has_path(root: Path, revision: str, path: str) -> bool:
    return git(root, "cat-file", "-e", f"{revision}:{path}", check=False).returncode == 0


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


class GitHubApi:
    def __init__(self, token: str | None):
        self.token = token
        self.opener = build_opener(NoRedirect())

    def get(self, path: str) -> dict:
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "magpie-mobile-upstream-sync",
        }
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = Request("https://api.github.com" + path, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
        except HTTPError as error:
            raise SyncError(f"GitHub API request failed with HTTP {error.code}") from None
        except (OSError, URLError):
            raise SyncError("GitHub API request failed") from None
        if len(raw) > 2 * 1024 * 1024:
            raise SyncError("GitHub API response exceeded 2 MiB")
        try:
            value = json.loads(raw)
        except (UnicodeDecodeError, ValueError):
            raise SyncError("GitHub API returned invalid JSON") from None
        if not isinstance(value, dict):
            raise SyncError("GitHub API returned a non-object response")
        return value


def resolve_tag_commit(api: GitHubApi, repository: str, tag: str) -> str:
    encoded = quote(tag, safe="")
    reference = api.get(f"/repos/{repository}/git/ref/tags/{encoded}")
    if reference.get("ref") != "refs/tags/" + tag:
        raise SyncError("GitHub returned a different tag reference")
    target = reference.get("object")
    for _ in range(5):
        if not isinstance(target, dict):
            raise SyncError("GitHub tag reference has no object")
        object_type, sha = target.get("type"), target.get("sha")
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise SyncError("GitHub tag object has an invalid SHA")
        if object_type == "commit":
            return sha
        if object_type != "tag":
            raise SyncError("GitHub tag does not resolve to a commit")
        tag_object = api.get(f"/repos/{repository}/git/tags/{sha}")
        if tag_object.get("sha") != sha:
            raise SyncError("GitHub returned a different annotated tag object")
        target = tag_object.get("object")
    raise SyncError("GitHub annotated tag nesting exceeds the safety limit")


def detect_release(
    api: GitHubApi,
    lock: dict[str, str],
    requested_tag: str | None = None,
) -> dict[str, object]:
    repository = lock["repository"]
    if requested_tag:
        release = api.get(f"/repos/{repository}/releases/tags/{quote(requested_tag, safe='')}")
    else:
        release = api.get(f"/repos/{repository}/releases/latest")
    tag = release.get("tag_name")
    if not isinstance(tag, str) or not re.fullmatch(r"v[0-9]+(?:\.[0-9]+){2}", tag):
        raise SyncError("Stable release has an invalid version tag")
    if release.get("draft") is not False or release.get("prerelease") is not False:
        raise SyncError("Only released, non-prerelease upstream versions are accepted")
    revision = resolve_tag_commit(api, repository, tag)
    version = tag.removeprefix("v")
    version_parts = tuple(int(part) for part in version.split("."))
    current_parts = tuple(int(part) for part in lock["version"].split("."))
    if revision != lock["revision"] and version_parts <= current_parts:
        raise SyncError("upstream release does not advance the locked version")
    return {
        "schemaVersion": 1,
        "repository": repository,
        "status": "up_to_date" if revision == lock["revision"] else "update_available",
        "updateAvailable": revision != lock["revision"],
        "currentTag": lock["tag"],
        "currentRevision": lock["revision"],
        "tag": tag,
        "revision": revision,
        "version": version,
        "coreSuffix": lock["coreSuffix"],
        "coreVersion": version + lock["coreSuffix"],
        "releaseUrl": release.get("html_url") if isinstance(release.get("html_url"), str) else None,
    }


def report_markdown(report: dict[str, object]) -> str:
    status = report.get("status")
    lines = [
        f"# 上游同步：{status}",
        "",
        f"- 当前版本：`{report.get('currentTag', '')}` (`{report.get('currentRevision', '')}`)",
        f"- 目标版本：`{report.get('targetTag', '')}` (`{report.get('targetRevision', '')}`)",
        f"- 生成补丁：`{report.get('adaptationPatch', '')}`",
    ]
    conflicts = report.get("conflictFiles")
    if isinstance(conflicts, list) and conflicts:
        lines.extend(("", "## 冲突文件", ""))
        lines.extend(f"- `{path}`" for path in conflicts)
    markers = report.get("conflictMarkers")
    if isinstance(markers, dict) and markers:
        lines.extend(("", "## 冲突标记", ""))
        for path, line_numbers in markers.items():
            lines.append(f"- `{path}`: line {', '.join(str(value) for value in line_numbers)}")
    auto_resolved = report.get("autoResolvedFiles")
    if isinstance(auto_resolved, list) and auto_resolved:
        lines.extend(("", "## 已自动解决的窄范围冲突", ""))
        lines.extend(f"- `{path}`" for path in auto_resolved)
    if report.get("status") == "adapted":
        lines.extend((
            "",
            "## 生成结果",
            "",
            f"- CoreVersion：`{report.get('coreVersion', '')}`",
            f"- 源码适配文件：{report.get('sourceFiles', 0)}",
            f"- Overlay 文件：{report.get('overlayFiles', 0)}",
        ))
    message = report.get("message")
    if message:
        lines.extend(("", str(message)))
    lines.extend((
        "",
        "锁文件和 manifest 仅在无冲突、完整校验通过后原子更新。",
        "",
    ))
    return "\n".join(lines)


def write_report(path: Path, report: dict[str, object]) -> None:
    path = path.absolute()
    atomic_write(
        path,
        (json.dumps(report, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    atomic_write(path.with_suffix(".md"), report_markdown(report).encode("utf-8"))


def conflict_markers(path: Path) -> list[int]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return []
    return [
        number
        for number, line in enumerate(lines, 1)
        if line.startswith(("<<<<<<<", "=======", ">>>>>>>"))
    ]


def make_conflict_report(
    *,
    lock: dict[str, str],
    target_tag: str,
    target_revision: str,
    conflict_files: list[str],
    markers: dict[str, list[int]],
    adaptation_patch: Path,
    message: str,
    auto_resolved_files: list[str] | None = None,
) -> dict[str, object]:
    report = {
        "schemaVersion": 1,
        "status": "conflict",
        "currentTag": lock["tag"],
        "currentRevision": lock["revision"],
        "targetTag": target_tag,
        "targetRevision": target_revision,
        "conflictFiles": conflict_files,
        "conflictMarkers": markers,
        "adaptationPatch": adaptation_patch.name,
        "message": message,
    }
    if auto_resolved_files:
        report["autoResolvedFiles"] = auto_resolved_files
    return report


def load_manifest_bundle(
    lock: dict[str, str],
    patch_root: Path,
) -> tuple[dict[str, object], list[dict[str, object]], list[dict[str, object]]]:
    try:
        manifest = json.loads((patch_root / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        raise SyncError("cannot read the current mobile manifest") from None
    if not isinstance(manifest, dict):
        raise SyncError("mobile manifest must be an object")
    if manifest.get("schemaVersion") != 1 or manifest.get("sourceRevision") != lock["revision"]:
        raise SyncError("mobile manifest and upstream lock disagree")
    if manifest.get("goVersion") != lock["goVersion"]:
        raise SyncError("mobile manifest and upstream lock disagree on Go")
    patches = manifest.get("patches")
    overlays = manifest.get("overlays")
    if not isinstance(patches, list) or not patches or not isinstance(overlays, list) or not overlays:
        raise SyncError("mobile manifest has empty patches or overlays")
    if not all(isinstance(item, dict) for item in (*patches, *overlays)):
        raise SyncError("mobile manifest patch and overlay entries must be objects")
    return manifest, patches, overlays


def target_revision_details(
    upstream_root: Path,
    target_tag: str,
    target_revision: str,
) -> str:
    if not upstream_root.is_dir():
        raise SyncError("upstream checkout does not exist")
    head = git_text(upstream_root, "rev-parse", "HEAD")
    if head != target_revision:
        raise SyncError("upstream checkout HEAD differs from the target revision")
    if git_text(upstream_root, "cat-file", "-t", target_revision) != "commit":
        raise SyncError("target revision is not a Git commit")
    local_tag = git(
        upstream_root,
        "rev-parse",
        "--verify",
        f"refs/tags/{target_tag}^{{commit}}",
        check=False,
    )
    if local_tag.returncode == 0 and local_tag.stdout.decode("ascii", errors="strict").strip() != target_revision:
        raise SyncError("target tag points at a different commit")
    if not git_has_path(upstream_root, target_revision, "go.mod"):
        raise SyncError("target revision has no go.mod")
    target_go_mod = git_bytes(upstream_root, "show", f"{target_revision}:go.mod")
    versions = re.findall(rb"(?m)^go ([0-9]+(?:\.[0-9]+){1,2})$", target_go_mod)
    if len(versions) != 1:
        raise SyncError("target go.mod must contain exactly one go version")
    return versions[0].decode("ascii")


def merge_import_conflict(ours: str, theirs: str) -> str | None:
    lines = [line for side in (ours, theirs) for line in side.splitlines() if line.strip()]
    if not lines or not all(GO_IMPORT_LINE.fullmatch(line) for line in lines):
        return None
    return "\n".join(sorted(set(lines), key=lambda line: line.strip())) + "\n"


def merge_updater_guard(ours: str, theirs: str) -> str | None:
    behind = "u.behind = update.ReadBehind(Version)"
    guard = (
        "if mobilepolicy.Enabled {\n"
        "\treturn // APK owns native updates; do not place, spawn or register restarter\n"
        "}"
    )
    first_line = next(
        (line for line in (*ours.splitlines(), *theirs.splitlines()) if line.strip()),
        "",
    )
    if not first_line:
        return None
    indent = re.match(r"^[ \t]*", first_line).group(0)
    sides = {textwrap.dedent(ours).strip(), textwrap.dedent(theirs).strip()}
    if sides == {behind, ""}:
        return indent + behind + "\n"
    if sides != {behind, guard}:
        return None
    return textwrap.indent(guard + "\n" + behind + "\n", indent)


def auto_resolve_conflict_file(root: Path, name: str) -> str | None:
    path = APPLIER.safe_path(root, name)
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise SyncError(f"cannot inspect conflicted text file: {name}: {error}") from None
    if not CONFLICT_BLOCK.search(text):
        return None

    def resolve(match: re.Match[str]) -> str:
        ours, theirs = match.group(1), match.group(2)
        merged = merge_import_conflict(ours, theirs)
        if merged is not None:
            outside_imports = {
                line.strip()
                for line in (text[:match.start()] + text[match.end():]).splitlines()
                if GO_IMPORT_LINE.fullmatch(line)
            }
            merged_lines = [
                line for line in merged.splitlines()
                if line.strip() not in outside_imports
            ]
            merged = "\n".join(merged_lines) + ("\n" if merged_lines else "")
        if merged is None and name == "internal/gui/update.go":
            merged = merge_updater_guard(ours, theirs)
        return merged if merged is not None else match.group(0)

    updated = CONFLICT_BLOCK.sub(resolve, text)
    if updated == text:
        return None
    atomic_write(path, updated.encode("utf-8"), path.stat().st_mode & 0o777)
    return name if not CONFLICT_BLOCK.search(updated) else None


def validate_android_security_surface(sources: dict[str, str]) -> None:
    lan = sources.get("internal/gateway/lan.go")
    update = sources.get("internal/gui/update.go")
    gatewaymode = sources.get("internal/gui/gatewaymode.go")
    web = sources.get("internal/gui/web.go")
    syncfiles = sources.get("internal/gui/assets/syncfiles.js")
    required_sources = {
        "internal/gateway/lan.go": lan,
        "internal/gui/update.go": update,
        "internal/gui/gatewaymode.go": gatewaymode,
        "internal/gui/web.go": web,
        "internal/gui/assets/syncfiles.js": syncfiles,
    }
    missing = [name for name, value in required_sources.items() if value is None]
    if missing:
        raise SyncError("adapted package lost required Android source files: " + ", ".join(missing))

    lan_guards = (
        "if !mobilepolicy.Enabled && remote && corsPreflight(r)",
        "if !mobilepolicy.Enabled && remote && (keyPageOpen(r) || caFileOpen(r) || chatPageOpen(r))",
        "if !mobilepolicy.Enabled && remote && imageTokenAsked(r)",
        "if !mobilepolicy.Enabled && remote && oidcExchange(r)",
        "if !mobilepolicy.Enabled && remote && proveAsked(r)",
    )
    for guard in lan_guards:
        if guard not in lan:
            raise SyncError("adapted lan.go lost an Android public-route guard")
    legacy_lan_guard = "if !mobilepolicy.Enabled && remote && (keyPageOpen(r) || caFileOpen(r))"
    if legacy_lan_guard in lan:
        raise SyncError("adapted lan.go dropped the newer public-route guard")

    guard_start = update.find("func (u *updater) start()")
    behind = update.find("u.behind = update.ReadBehind(Version)", guard_start)
    mobile_guard = update.find("if mobilepolicy.Enabled {", guard_start)
    if guard_start < 0 or mobile_guard < 0 or behind < 0 or mobile_guard > behind:
        raise SyncError("adapted update.go no longer disables the native updater before side effects")

    for token in (
        '"github.com/yetone/magpie/internal/library"',
        '"github.com/yetone/magpie/internal/mobilepolicy"',
        "if mobilepolicy.FullAdmin {",
    ):
        if token not in gatewaymode:
            raise SyncError("adapted gatewaymode.go lost Android gateway-mode policy")
    if "func webHandler(port, key, where string, box boxSignIn, page http.Handler) http.Handler" not in web:
        raise SyncError("adapted web.go no longer accepts the upstream where string")
    if "androidHostEditor(port, key, where, box, page)" not in web:
        raise SyncError("adapted web.go no longer routes through the Android editor wrapper")

    for token in (
        "usage: use.checked",
        "window.bootPrefs?.androidHost === true",
        "androidHostBackupDownload(body)",
        'api("backup/export", body)',
    ):
        if token not in syncfiles:
            raise SyncError("adapted syncfiles.js lost upstream export fields or Android backup routing")


def create_lock_text(lock: dict[str, str], target_tag: str, target_revision: str, go_version: str) -> str:
    version = target_tag.removeprefix("v")
    values = {
        "schemaVersion": "1",
        "repository": lock["repository"],
        "tag": target_tag,
        "revision": target_revision,
        "version": version,
        "goVersion": go_version,
        "coreVersion": version + lock["coreSuffix"],
        "coreSuffix": lock["coreSuffix"],
    }
    return "".join(f"{key}={values[key]}\n" for key in (
        "schemaVersion",
        "repository",
        "tag",
        "revision",
        "version",
        "goVersion",
        "coreVersion",
        "coreSuffix",
    ))


def adapt_upstream(
    *,
    lock_path: Path,
    patch_root: Path,
    upstream_root: Path,
    target_tag: str,
    target_revision: str,
    report_path: Path,
    enforce_android_security: bool = True,
) -> tuple[int, dict[str, object]]:
    try:
        lock = LOCK_READER.load_lock(lock_path)
    except ValueError as error:
        raise SyncError(f"invalid upstream lock: {error}") from None
    if not re.fullmatch(r"v[0-9]+(?:\.[0-9]+){2}", target_tag):
        raise SyncError("target tag must be a stable vX.Y.Z release")
    target_version_parts = tuple(int(part) for part in target_tag.removeprefix("v").split("."))
    current_version_parts = tuple(int(part) for part in lock["version"].split("."))
    if target_version_parts <= current_version_parts:
        raise SyncError("target release must be newer than the locked release")
    if not re.fullmatch(r"[0-9a-f]{40}", target_revision):
        raise SyncError("target revision must be a lowercase 40-character Git SHA")
    go_version = target_revision_details(upstream_root, target_tag, target_revision)

    old_exists = git(upstream_root, "cat-file", "-e", lock["revision"] + "^{commit}", check=False).returncode == 0
    if not old_exists:
        run([
            "git", "-C", str(upstream_root), "fetch", "--no-tags", "origin",
            f"refs/tags/{lock['tag']}:refs/tags/{lock['tag']}",
        ])
        if git(upstream_root, "cat-file", "-e", lock["revision"] + "^{commit}", check=False).returncode:
            raise SyncError("current locked revision is unavailable from the upstream remote")

    manifest, patches, overlays = load_manifest_bundle(lock, patch_root)

    adaptation_patch = report_path.with_name("adaptation.patch")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="worktrees-", dir=report_path.parent))
    old_tree = temporary / "old"
    new_tree = temporary / "new"
    added: list[Path] = []
    try:
        required_paths = []
        for patch in patches:
            path = patch.get("path")
            if not isinstance(path, str):
                raise SyncError("mobile manifest has a non-string patch path")
            APPLIER.safe_path(upstream_root, path)
            required_paths.append(path)
        for overlay in overlays:
            path = overlay.get("path")
            if not isinstance(path, str):
                raise SyncError("mobile manifest has a non-string overlay path")
            APPLIER.safe_path(upstream_root, path)
        missing_sources = [
            path for path in required_paths
            if not git_has_path(upstream_root, target_revision, path)
        ]
        overlay_collisions = [
            overlay["path"] for overlay in overlays
            if git_has_path(upstream_root, target_revision, overlay["path"])
        ]
        preflight_conflicts = missing_sources + overlay_collisions
        if preflight_conflicts:
            atomic_write(adaptation_patch, b"")
            report = make_conflict_report(
                lock=lock,
                target_tag=target_tag,
                target_revision=target_revision,
                conflict_files=preflight_conflicts,
                markers={},
                adaptation_patch=adaptation_patch,
                message="目标上游删除/重命名了必需适配文件，或已经占用 Android host overlay 路径；拒绝覆盖。",
            )
            write_report(report_path, report)
            return 2, report

        run(["git", "-C", str(upstream_root), "worktree", "add", "--detach", str(old_tree), lock["revision"]])
        added.append(old_tree)
        changes = APPLIER.plan(old_tree, patch_root, lock_path)
        APPLIER.apply(changes)
        git(old_tree, "add", "-A")
        patch_data = git_bytes(old_tree, "diff", "--cached", "--binary", "--full-index", lock["revision"], "--")
        if not patch_data:
            raise SyncError("current mobile policy produced an empty adaptation patch")
        atomic_write(adaptation_patch, patch_data)

        run(["git", "-C", str(upstream_root), "worktree", "add", "--detach", str(new_tree), target_revision])
        added.append(new_tree)
        auto_resolved: list[str] = []
        applied = git(new_tree, "apply", "--3way", "--index", "--whitespace=nowarn", str(adaptation_patch), check=False)
        if applied.returncode:
            original_conflicts = [
                value
                for value in git_text(new_tree, "diff", "--name-only", "--diff-filter=U").splitlines()
                if value
            ]
            for path in original_conflicts:
                resolved = auto_resolve_conflict_file(new_tree, path)
                if resolved:
                    auto_resolved.append(resolved)
            git(new_tree, "add", "-A")
            conflict_files = [
                path for path in original_conflicts
                if conflict_markers(APPLIER.safe_path(new_tree, path))
            ]
            unmerged = [
                value
                for value in git_text(new_tree, "diff", "--name-only", "--diff-filter=U").splitlines()
                if value
            ]
            if unmerged and not conflict_files:
                conflict_files = unmerged
            markers = {
                path: conflict_markers(APPLIER.safe_path(new_tree, path))
                for path in conflict_files
            }
            if conflict_files or unmerged:
                report = make_conflict_report(
                    lock=lock,
                    target_tag=target_tag,
                    target_revision=target_revision,
                    conflict_files=conflict_files,
                    markers={path: values for path, values in markers.items() if values},
                    adaptation_patch=adaptation_patch,
                    message="三方合并存在冲突；锁文件和 manifest 未更新，必须人工审查后重新生成补丁。",
                    auto_resolved_files=auto_resolved,
                )
                write_report(report_path, report)
                return 2, report

        git(new_tree, "add", "-A")
        generated_patches = []
        generated_source_text: dict[str, str] = {}
        destinations = set()
        covered_markers: dict[str, list[int]] = {}
        for patch in patches:
            path = patch.get("path")
            if not isinstance(path, str) or path in destinations:
                raise SyncError("mobile manifest has an invalid or duplicate source path")
            APPLIER.safe_path(upstream_root, path)
            destinations.add(path)
            if not git_has_path(upstream_root, target_revision, path):
                raise SyncError(f"target upstream deleted required adapted file: {path}")
            before = git_bytes(upstream_root, "show", f"{target_revision}:{path}")
            try:
                old_text = before.decode("utf-8")
                new_text = APPLIER.read_regular(new_tree, path).decode("utf-8")
            except (UnicodeDecodeError, ValueError) as error:
                raise SyncError(f"adapted source is not UTF-8 text: {path}: {error}") from None
            if not old_text:
                raise SyncError(f"cannot encode a non-empty unique whole-file patch: {path}")
            result = old_text.replace(old_text, new_text, 1).encode("utf-8")
            if digest(result) != digest(new_text.encode("utf-8")):
                raise SyncError(f"generated patch changed its own result: {path}")
            marker_lines = conflict_markers(APPLIER.safe_path(new_tree, path))
            if marker_lines:
                covered_markers[path] = marker_lines
            generated_patches.append({
                "path": path,
                "sha256": digest(before),
                "resultSha256": digest(result),
                "replacements": [{"old": old_text, "new": new_text}],
            })
            generated_source_text[path] = new_text
        if covered_markers:
            report = make_conflict_report(
                lock=lock,
                target_tag=target_tag,
                target_revision=target_revision,
                conflict_files=sorted(covered_markers),
                markers=covered_markers,
                adaptation_patch=adaptation_patch,
                message="三方合并结果仍含冲突标记；锁文件和 manifest 未更新。",
            )
            write_report(report_path, report)
            return 2, report
        if enforce_android_security:
            validate_android_security_surface(generated_source_text)

        generated_overlays = []
        for overlay in overlays:
            path = overlay.get("path")
            if not isinstance(path, str) or path in destinations:
                raise SyncError("mobile manifest has an invalid or duplicate overlay path")
            APPLIER.safe_path(upstream_root, path)
            destinations.add(path)
            if git_has_path(upstream_root, target_revision, path):
                raise SyncError(f"target upstream added overlay path: {path}")
            data = APPLIER.read_regular(new_tree, path)
            expected_hash = overlay.get("sha256")
            if not isinstance(expected_hash, str) or digest(data) != expected_hash:
                raise SyncError(f"overlay changed during sync: {path}")
            generated_overlays.append({"path": path, "sha256": digest(data)})

        generated_checks = []
        check_paths = set()
        for check in manifest.get("checks", []):
            path = check.get("path")
            if not isinstance(path, str) or path in check_paths or not git_has_path(upstream_root, target_revision, path):
                raise SyncError("target upstream is missing a manifest check path")
            check_paths.add(path)
            data = git_bytes(upstream_root, "show", f"{target_revision}:{path}")
            generated_checks.append({"path": path, "sha256": digest(data)})
        if "go.mod" not in check_paths:
            raise SyncError("mobile manifest must retain the go.mod integrity check")

        new_manifest = {
            "schemaVersion": 1,
            "sourceRevision": target_revision,
            "goVersion": go_version,
            "checks": generated_checks,
            "patches": generated_patches,
            "overlays": generated_overlays,
        }
        lock_text = create_lock_text(lock, target_tag, target_revision, go_version)
        staged_lock = report_path.parent / "upstream.lock.properties.next"
        atomic_write(staged_lock, lock_text.encode("utf-8"))
        validated_lock = LOCK_READER.load_lock(staged_lock)

        old_manifest_bytes = (patch_root / "manifest.json").read_bytes()
        old_lock_bytes = Path(lock_path).read_bytes()
        report = {
            "schemaVersion": 1,
            "status": "adapted",
            "currentTag": lock["tag"],
            "currentRevision": lock["revision"],
            "targetTag": target_tag,
            "targetRevision": target_revision,
            "targetVersion": target_tag.removeprefix("v"),
            "targetGoVersion": validated_lock["goVersion"],
            "coreVersion": validated_lock["coreVersion"],
            "sourceFiles": len(generated_patches),
            "overlayFiles": len(generated_overlays),
            "adaptationPatch": adaptation_patch.name,
            "message": "三方合并成功；锁文件和精确整文件补丁已生成，仍需通过完整 Validation。",
        }
        if auto_resolved:
            report["autoResolvedFiles"] = auto_resolved
        try:
            atomic_write(
                patch_root / "manifest.json",
                (json.dumps(new_manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
            )
            atomic_write(Path(lock_path), lock_text.encode("utf-8"))
            write_report(report_path, report)
        except (OSError, ValueError):
            atomic_write(patch_root / "manifest.json", old_manifest_bytes)
            atomic_write(Path(lock_path), old_lock_bytes)
            raise
        finally:
            staged_lock.unlink(missing_ok=True)
        return 0, report
    finally:
        for worktree in reversed(added):
            git(upstream_root, "worktree", "remove", "--force", str(worktree), check=False)
        shutil.rmtree(temporary, ignore_errors=True)
        git(upstream_root, "worktree", "prune", check=False)


def finalize_upstream(
    *,
    lock_path: Path,
    patch_root: Path,
    resolved_root: Path,
    target_tag: str,
    target_revision: str,
    report_path: Path,
    enforce_android_security: bool = True,
) -> tuple[int, dict[str, object]]:
    """Regenerate the pinned package from a reviewed, conflict-free worktree."""
    try:
        lock = LOCK_READER.load_lock(lock_path)
    except ValueError as error:
        raise SyncError(f"invalid upstream lock: {error}") from None
    if not re.fullmatch(r"v[0-9]+(?:\.[0-9]+){2}", target_tag):
        raise SyncError("target tag must be a stable vX.Y.Z release")
    target_version_parts = tuple(int(part) for part in target_tag.removeprefix("v").split("."))
    current_version_parts = tuple(int(part) for part in lock["version"].split("."))
    if target_version_parts <= current_version_parts:
        raise SyncError("target release must be newer than the locked release")
    if not re.fullmatch(r"[0-9a-f]{40}", target_revision):
        raise SyncError("target revision must be a lowercase 40-character Git SHA")
    patch_root = patch_root.absolute()
    resolved_root = resolved_root.absolute()
    go_version = target_revision_details(resolved_root, target_tag, target_revision)
    manifest, patches, overlays = load_manifest_bundle(lock, patch_root)

    patch_paths: list[str] = []
    overlay_paths: list[str] = []
    for patch in patches:
        path = patch.get("path")
        if not isinstance(path, str):
            raise SyncError("mobile manifest has a non-string patch path")
        APPLIER.safe_path(resolved_root, path)
        if not git_has_path(resolved_root, target_revision, path):
            raise SyncError(f"target upstream deleted required adapted file: {path}")
        patch_paths.append(path)
    for overlay in overlays:
        path = overlay.get("path")
        if not isinstance(path, str):
            raise SyncError("mobile manifest has a non-string overlay path")
        APPLIER.safe_path(resolved_root, path)
        if git_has_path(resolved_root, target_revision, path):
            raise SyncError(f"target upstream added overlay path: {path}")
        overlay_paths.append(path)
    if len(set(patch_paths)) != len(patch_paths) or len(set(overlay_paths)) != len(overlay_paths):
        raise SyncError("mobile manifest has duplicate destination paths")
    if set(patch_paths) & set(overlay_paths):
        raise SyncError("mobile manifest uses one destination for a patch and overlay")
    expected_paths = set(patch_paths) | set(overlay_paths)

    unmerged = [
        value for value in git_text(
            resolved_root, "diff", "--name-only", "--diff-filter=U"
        ).splitlines() if value
    ]
    if unmerged:
        raise SyncError("resolved worktree still has unmerged paths: " + ", ".join(unmerged))
    unstaged = [
        value for value in git_text(resolved_root, "diff", "--name-only").splitlines()
        if value
    ]
    if unstaged:
        raise SyncError(
            "resolved worktree has unstaged changes; stage the complete reviewed inventory: "
            + ", ".join(unstaged)
        )
    tracked = {
        value for value in git_text(
            resolved_root, "diff", "--name-only", target_revision, "--"
        ).splitlines() if value
    }
    untracked = {
        value for value in git_text(
            resolved_root, "ls-files", "--others", "--exclude-standard"
        ).splitlines() if value
    }
    actual_paths = tracked | untracked
    missing = sorted(expected_paths - actual_paths)
    unexpected = sorted(actual_paths - expected_paths)
    if missing or unexpected:
        detail = []
        if missing:
            detail.append("missing " + ", ".join(missing))
        if unexpected:
            detail.append("unexpected " + ", ".join(unexpected))
        raise SyncError("resolved worktree does not match the package inventory: " + "; ".join(detail))

    generated_patches = []
    generated_source_text: dict[str, str] = {}
    for patch, path in zip(patches, patch_paths):
        before = git_bytes(resolved_root, "show", f"{target_revision}:{path}")
        after = APPLIER.read_regular(resolved_root, path)
        try:
            old_text = before.decode("utf-8")
            new_text = after.decode("utf-8")
        except UnicodeDecodeError as error:
            raise SyncError(f"adapted source is not UTF-8 text: {path}: {error}") from None
        markers = conflict_markers(APPLIER.safe_path(resolved_root, path))
        if markers:
            raise SyncError(
                f"resolved source still has conflict markers: {path} at {', '.join(map(str, markers))}"
            )
        if not old_text or not new_text or old_text == new_text:
            raise SyncError(f"adapted source must differ from upstream: {path}")
        result = old_text.replace(old_text, new_text, 1).encode("utf-8")
        if digest(result) != digest(after):
            raise SyncError(f"generated patch changed its own result: {path}")
        generated_patches.append({
            "path": path,
            "sha256": digest(before),
            "resultSha256": digest(after),
            "replacements": [{"old": old_text, "new": new_text}],
        })
        generated_source_text[path] = new_text
    if enforce_android_security:
        validate_android_security_surface(generated_source_text)

    generated_overlays = []
    for path in overlay_paths:
        data = APPLIER.read_regular(resolved_root, path)
        if conflict_markers(APPLIER.safe_path(resolved_root, path)):
            raise SyncError(f"resolved overlay still has conflict markers: {path}")
        generated_overlays.append({"path": path, "sha256": digest(data)})

    generated_checks = []
    check_paths = set()
    for check in manifest.get("checks", []):
        path = check.get("path")
        if not isinstance(path, str) or path in check_paths or not git_has_path(
            resolved_root, target_revision, path
        ):
            raise SyncError("target upstream is missing a manifest check path")
        check_paths.add(path)
        data = git_bytes(resolved_root, "show", f"{target_revision}:{path}")
        generated_checks.append({"path": path, "sha256": digest(data)})
    if "go.mod" not in check_paths:
        raise SyncError("mobile manifest must retain the go.mod integrity check")

    new_manifest = {
        "schemaVersion": 1,
        "sourceRevision": target_revision,
        "goVersion": go_version,
        "checks": generated_checks,
        "patches": generated_patches,
        "overlays": generated_overlays,
    }
    finalized_patch = git_bytes(
        resolved_root,
        "diff",
        "--cached",
        "--binary",
        "--full-index",
        target_revision,
        "--",
    )
    if not finalized_patch:
        raise SyncError("resolved worktree produced an empty final adaptation patch")
    lock_text = create_lock_text(lock, target_tag, target_revision, go_version)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    staged_lock = report_path.parent / "upstream.lock.properties.next"
    atomic_write(staged_lock, lock_text.encode("utf-8"))
    try:
        validated_lock = LOCK_READER.load_lock(staged_lock)
        report = {
            "schemaVersion": 1,
            "status": "adapted",
            "mode": "finalize",
            "currentTag": lock["tag"],
            "currentRevision": lock["revision"],
            "targetTag": target_tag,
            "targetRevision": target_revision,
            "targetVersion": target_tag.removeprefix("v"),
            "targetGoVersion": validated_lock["goVersion"],
            "coreVersion": validated_lock["coreVersion"],
            "sourceFiles": len(generated_patches),
            "overlayFiles": len(generated_overlays),
            "adaptationPatch": "adaptation.patch",
            "message": "已从人工解决后的固定 revision 重新生成锁文件和精确整文件补丁；仍需通过完整 Validation。",
        }
        old_manifest_bytes = (patch_root / "manifest.json").read_bytes()
        old_lock_bytes = Path(lock_path).read_bytes()
        try:
            atomic_write(report_path.with_name("adaptation.patch"), finalized_patch)
            atomic_write(
                patch_root / "manifest.json",
                (json.dumps(new_manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
            )
            atomic_write(Path(lock_path), lock_text.encode("utf-8"))
            write_report(report_path, report)
        except (OSError, ValueError):
            atomic_write(patch_root / "manifest.json", old_manifest_bytes)
            atomic_write(Path(lock_path), old_lock_bytes)
            raise
        return 0, report
    finally:
        staged_lock.unlink(missing_ok=True)


def write_github_outputs(values: dict[str, object]) -> None:
    mappings = {
        "update_available": "updateAvailable",
        "repository": "repository",
        "tag": "tag",
        "revision": "revision",
        "version": "version",
        "core_version": "coreVersion",
        "release_url": "releaseUrl",
    }
    for output, key in mappings.items():
        value = values.get(key)
        if isinstance(value, bool):
            value = str(value).lower()
        elif value is None:
            value = ""
        print(f"{output}={value}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    subparsers = parser.add_subparsers(dest="command", required=True)

    detect_parser = subparsers.add_parser("detect")
    detect_parser.add_argument("--tag", help="check this stable tag instead of GitHub's latest release")
    detect_parser.add_argument("--report", type=Path)
    detect_parser.add_argument("--format", choices=("json", "github-output"), default="json")

    adapt_parser = subparsers.add_parser("adapt")
    adapt_parser.add_argument("--patch-root", type=Path, default=DEFAULT_PATCH_ROOT)
    adapt_parser.add_argument("--upstream-root", type=Path, required=True)
    adapt_parser.add_argument("--target-tag", required=True)
    adapt_parser.add_argument("--target-revision", required=True)
    adapt_parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)

    finalize_parser = subparsers.add_parser("finalize")
    finalize_parser.add_argument("--patch-root", type=Path, default=DEFAULT_PATCH_ROOT)
    finalize_parser.add_argument("--resolved-root", type=Path, required=True)
    finalize_parser.add_argument("--target-tag", required=True)
    finalize_parser.add_argument("--target-revision", required=True)
    finalize_parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)

    args = parser.parse_args(argv)
    try:
        lock = LOCK_READER.load_lock(args.lock)
        if args.command == "detect":
            token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
            result = detect_release(GitHubApi(token), lock, args.tag)
            if args.report:
                write_report(args.report, result)
            if args.format == "github-output":
                write_github_outputs(result)
            else:
                print(json.dumps(result, indent=2, sort_keys=True))
            return 0

        if args.command == "adapt":
            code, result = adapt_upstream(
                lock_path=args.lock,
                patch_root=args.patch_root.absolute(),
                upstream_root=args.upstream_root.absolute(),
                target_tag=args.target_tag,
                target_revision=args.target_revision,
                report_path=args.report.absolute(),
            )
        else:
            code, result = finalize_upstream(
                lock_path=args.lock,
                patch_root=args.patch_root.absolute(),
                resolved_root=args.resolved_root.absolute(),
                target_tag=args.target_tag,
                target_revision=args.target_revision,
                report_path=args.report.absolute(),
            )
        print(json.dumps(result, indent=2, sort_keys=True))
        return code
    except (APPLIER.PatchError, LOCK_READER.LockError, SyncError, OSError, ValueError) as error:
        if args.command in ("adapt", "finalize"):
            failure = {
                "schemaVersion": 1,
                "status": "error",
                "targetTag": args.target_tag,
                "targetRevision": args.target_revision,
                "message": str(error),
            }
            try:
                write_report(args.report, failure)
            except OSError:
                pass
        print(f"upstream sync failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
