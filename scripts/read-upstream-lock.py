#!/usr/bin/env python3
"""Read and strictly validate the single pinned upstream version lock."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCK = ROOT / "upstream.lock.properties"
REQUIRED_KEYS = {
    "schemaVersion",
    "repository",
    "tag",
    "revision",
    "version",
    "goVersion",
    "coreVersion",
    "coreSuffix",
}
GITHUB_OUTPUT_NAMES = {
    "repository": "repository",
    "tag": "tag",
    "revision": "revision",
    "version": "version",
    "goVersion": "go_version",
    "coreVersion": "core_version",
}


class LockError(ValueError):
    """A lock file that cannot safely drive a source checkout or release."""


def _parse_properties(path: Path) -> dict[str, str]:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise LockError(f"cannot read lock file: {path}") from error
    if len(raw) > 64 * 1024:
        raise LockError("upstream lock exceeds 64 KiB")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise LockError("upstream lock is not UTF-8") from error

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(text.splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "\\" in raw_line:
            raise LockError(f"lock line {line_number} uses unsupported properties syntax")
        if "=" not in line:
            raise LockError(f"lock line {line_number} must be key=value")
        key, value = (part.strip() for part in line.split("=", 1))
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key):
            raise LockError(f"invalid lock key on line {line_number}")
        if key in values:
            raise LockError(f"duplicate lock key: {key}")
        if not value or any(ord(character) < 0x20 for character in value):
            raise LockError(f"invalid value for lock key: {key}")
        values[key] = value

    keys = set(values)
    if keys != REQUIRED_KEYS:
        missing = sorted(REQUIRED_KEYS - keys)
        extra = sorted(keys - REQUIRED_KEYS)
        raise LockError(f"lock keys differ from schema 1; missing={missing}, extra={extra}")
    return values


def load_lock(path: Path | str = DEFAULT_LOCK) -> dict[str, str]:
    values = _parse_properties(Path(path))
    if values["schemaVersion"] != "1":
        raise LockError("only upstream lock schemaVersion=1 is supported")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", values["repository"]):
        raise LockError("repository must be an owner/name pair")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2}", values["version"]):
        raise LockError("version must contain three numeric components")
    if values["tag"] != "v" + values["version"]:
        raise LockError("tag must be v followed by version")
    if not re.fullmatch(r"[0-9a-f]{40}", values["revision"]):
        raise LockError("revision must be a lowercase 40-character Git SHA")
    if not re.fullmatch(r"1(?:\.[0-9]+){1,2}", values["goVersion"]):
        raise LockError("goVersion must be a Go 1.x version")
    if not re.fullmatch(r"-[A-Za-z0-9._-]+", values["coreSuffix"]):
        raise LockError("coreSuffix must be a non-empty release suffix")
    if values["coreVersion"] != values["version"] + values["coreSuffix"]:
        raise LockError("coreVersion must equal version plus coreSuffix")
    return values


def github_outputs(values: dict[str, str]) -> dict[str, str]:
    return {github_name: values[key] for key, github_name in GITHUB_OUTPUT_NAMES.items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--get", help="print one validated lock value")
    parser.add_argument(
        "--format",
        choices=("properties", "json", "github-output"),
        default="properties",
        help="output format when --get is absent",
    )
    args = parser.parse_args(argv)
    try:
        values = load_lock(args.lock)
        if args.get:
            if args.get not in values:
                raise LockError(f"unknown lock key: {args.get}")
            print(values[args.get])
        elif args.format == "json":
            print(json.dumps(values, indent=2, sort_keys=True))
        elif args.format == "github-output":
            for key, value in github_outputs(values).items():
                print(f"{key}={value}")
        else:
            for key in sorted(values):
                print(f"{key}={values[key]}")
        return 0
    except LockError as error:
        print(f"upstream lock rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
