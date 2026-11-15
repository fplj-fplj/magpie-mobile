#!/usr/bin/env python3
"""Verify the actual packaged font's identity, CJK glyphs and redistribution license.

Uses only Python's standard library. This inspects assets; it does not compile Android.
"""
import hashlib
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
font = ROOT / "app/src/main/res/font/lxgw_wenkai_screen.ttf"
data = font.read_bytes()
assert hashlib.sha256(data).hexdigest() == "abd13364163e3cd84372b333f2fdcee9efb848634668f1c0ce5788dcd059ee61", "WenKai Screen v1.530 differs from the pinned upstream asset"
assert data[:4] == b"\x00\x01\x00\x00", "Expected Android-compatible TrueType font"

def u16(offset):
    return struct.unpack_from(">H", data, offset)[0]

def u32(offset):
    return struct.unpack_from(">I", data, offset)[0]

tables = {}
for index in range(u16(4)):
    offset = 12 + index * 16
    tag, _, position, length = struct.unpack_from(">4sIII", data, offset)
    tables[tag] = (position, length)
assert {b"cmap", b"name", b"glyf", b"head"} <= tables.keys()
assert u16(tables[b"OS/2"][0] + 4) == 400, "Screen's heavier outlines must retain upstream Regular metadata"
cmap = tables[b"cmap"][0]
subtables = []
for index in range(u16(cmap + 2)):
    record = cmap + 4 + index * 8
    platform, encoding, offset = struct.unpack_from(">HHI", data, record)
    if platform == 0 or (platform == 3 and encoding in (1, 10)):
        subtables.append(cmap + offset)

def has_glyph(code):
    for table in subtables:
        fmt = u16(table)
        if fmt == 12:
            for index in range(u32(table + 12)):
                start, end, glyph = struct.unpack_from(">III", data, table + 16 + index * 12)
                if start <= code <= end and glyph + code - start:
                    return True
        elif fmt == 4 and code <= 0xFFFF:
            count = u16(table + 6) // 2
            ends = table + 14
            starts = ends + count * 2 + 2
            deltas = starts + count * 2
            ranges = deltas + count * 2
            for index in range(count):
                start, end = u16(starts + index * 2), u16(ends + index * 2)
                if start <= code <= end:
                    delta, distance = u16(deltas + index * 2), u16(ranges + index * 2)
                    glyph = (code + delta) & 0xFFFF if distance == 0 else u16(ranges + index * 2 + distance + (code - start) * 2)
                    if distance and glyph:
                        glyph = (glyph + delta) & 0xFFFF
                    if glyph:
                        return True
    return False

required = "霞鹜文楷屏幕阅读网关提供商模型对话连接应用客户端密钥路由号池用量速度系统诊断电池网络设置历史会话启停参数工具" + "".join(chr(c) for c in range(32, 127))
missing = [char for char in sorted(set(required)) if not has_glyph(ord(char))]
assert not missing, f"Missing CJK glyphs: {''.join(missing)}"
license_text = (ROOT / "app/src/main/assets/licenses/ofl-lxgw-wenkai.txt").read_text()
assert "SIL OPEN FONT LICENSE Version 1.1" in license_text and "LXGW" in license_text
print(f"PASS: pinned WenKai Screen v1.530 TrueType ({len(data):,} bytes), {len(set(required))} Chinese/Latin glyphs and bundled OFL")
