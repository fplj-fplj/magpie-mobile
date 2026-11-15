#!/usr/bin/env python3
"""
Runtime payload probe (CI only): inspect the ELF engine payloads packaged in an
APK for the ABI the emulator test build claims, PIE, and 16KB PT_LOAD alignment.

PT_INTERP is loader-dependency evidence, not a requirement: a payload linked
against the Android dynamic linker is fine as long as the interpreter is an
Android linker; anything else fails.
"""

import argparse
import struct
import sys
import zipfile
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
PT_INTERP = 3
ALIGN_16KB = 16384
MACHINE_BY_ABI = {
    "arm64-v8a": (183, "AArch64"),
    "x86_64": (62, "x86-64"),
}
ANDROID_INTERPRETERS = ("/system/bin/linker64", "/apex/com.android.runtime/bin/linker64", "/bin/linker64")


def read_elf(data: bytes):
    """Parse the 64-bit little-endian ELF header; return (machine, e_type, segments) or a failure reason."""
    if len(data) < 64:
        return None, "file too small for an ELF header"
    if data[:4] != ELF_MAGIC:
        return None, "not an ELF binary"
    if data[4] != 2:
        return None, "not a 64-bit ELF"
    if data[5] != 1:
        return None, "not a little-endian ELF"
    e_type, e_machine = struct.unpack_from("<HH", data, 16)
    e_phoff, = struct.unpack_from("<Q", data, 32)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
    segments = []
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, base)
        if p_type == PT_LOAD:
            p_align, = struct.unpack_from("<Q", data, base + 48)
            segments.append(("PT_LOAD", p_align))
        elif p_type == PT_INTERP:
            p_offset, = struct.unpack_from("<Q", data, base + 8)
            p_filesz, = struct.unpack_from("<Q", data, base + 32)
            segments.append(("PT_INTERP", data[p_offset:p_offset + p_filesz].split(b"\0")[0].decode("utf-8", "replace")))
    return (e_machine, e_type, segments), None


def probe_payload(name: str, data: bytes, abi: str) -> list[str]:
    problems: list[str] = []
    expected_machine, label = MACHINE_BY_ABI[abi]
    parsed, why = read_elf(data)
    if parsed is None:
        return [f"{name}: {why}"]
    machine, e_type, segments = parsed
    if machine != expected_machine:
        problems.append(f"{name}: e_machine={machine}, expected {expected_machine} ({label} for {abi})")
    if e_type != 3:
        problems.append(f"{name}: e_type={e_type}, expected 3 (ET_DYN/PIE)")
    for kind, value in segments:
        if kind == "PT_LOAD" and value < ALIGN_16KB:
            problems.append(f"{name}: PT_LOAD p_align={value}, expected >= {ALIGN_16KB} (16KB page size)")
        if kind == "PT_INTERP" and value not in ANDROID_INTERPRETERS:
            problems.append(f"{name}: PT_INTERP={value!r} is not an Android dynamic linker")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, help="APK path to inspect")
    parser.add_argument("--abi", default="x86_64", choices=sorted(MACHINE_BY_ABI), help="expected payload ABI")
    parser.add_argument(
        "--payload",
        action="append",
        default=["libmagpie_exec.so", "libbun_exec.so"],
        help="packaged payload name(s) to verify (repeatable)",
    )
    args = parser.parse_args()

    problems: list[str] = []
    found: list[str] = []
    with zipfile.ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        for payload in args.payload:
            entry = f"lib/{args.abi}/{payload}"
            if entry not in names:
                problems.append(f"{entry}: missing from APK")
                continue
            found.append(entry)
            problems.extend(probe_payload(entry, apk.read(entry), args.abi))
        other_abi = sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2} - {args.abi})
        if other_abi:
            problems.append(f"APK unexpectedly packages non-test ABIs: {', '.join(other_abi)}")
    if not found:
        problems.append(f"no payloads found for {args.abi}")

    for entry in found:
        print(f"PASS: {entry}")
    for problem in problems:
        print(f"FAIL: {problem}")
    print(f"runtimeprobe: abi={args.abi} payloads={len(found)} problems={len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
