#!/usr/bin/env python3
"""Run after KSP: exported Room schemas must match reviewed migrations."""
import json
from pathlib import Path
import subprocess

schema_dir = Path("app/schemas/cc.fplj.magpiemobile.data.ChatDatabase")
v1 = schema_dir / "1.json"
v2 = schema_dir / "2.json"
for path in (v1, v2):
    subprocess.run(
        ["git", "ls-files", "--error-unmatch", str(path)],
        check=True,
        capture_output=True,
    )
subprocess.run(["git", "diff", "--exit-code", "--", "app/schemas/"], check=True)

v1_schema = json.loads(v1.read_text())["database"]
assert v1_schema["version"] == 1
assert v1_schema["identityHash"] == "9a2f15e5cf2cf935d4a2c7b3cd5da77f"

schema = json.loads(v2.read_text())["database"]
assert schema["version"] == 2
assert schema["identityHash"] == "700b90261d8521af7afbde8eaffd2469"
tables = {item["tableName"] for item in schema["entities"]}
assert tables == {"conversation", "message", "operation", "metadata"}

message = next(item for item in schema["entities"] if item["tableName"] == "message")
reasoning = next(field for field in message["fields"] if field["fieldPath"] == "reasoning")
assert reasoning["columnName"] == "reasoning"
assert reasoning["affinity"] == "TEXT"
assert reasoning["notNull"] is True
assert reasoning["defaultValue"] == "''"

print("PASS: exported Room v1/v2 match reviewed schema and 1->2 reasoning migration")
