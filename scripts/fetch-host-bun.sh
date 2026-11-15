#!/usr/bin/env bash
# CI-only Linux runtime for the actual Go/Bun plugin integration checks.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build-tmp

MAGPIE_HOST_BUN_SHA256=c678040f14fe0440eb839d37cbd0ce4c051a32da72806ac97de6a6aab6bf728f
MAGPIE_HOST_BUN_URL=https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-x64-baseline.zip
MAGPIE_HOST_BUN_ARCHIVE=build-tmp/bun-linux-host.zip

if [ ! -f "$MAGPIE_HOST_BUN_ARCHIVE" ]; then
  curl -fL --retry 2 --connect-timeout 15 --max-time 300 \
    -o "$MAGPIE_HOST_BUN_ARCHIVE" "$MAGPIE_HOST_BUN_URL"
fi
printf '%s  %s\n' "$MAGPIE_HOST_BUN_SHA256" "$MAGPIE_HOST_BUN_ARCHIVE" | sha256sum -c -
python3 - <<'PY'
from pathlib import Path
import zipfile
with zipfile.ZipFile('build-tmp/bun-linux-host.zip') as archive:
    entries = [entry for entry in archive.infolist()
               if not entry.is_dir() and entry.filename.rsplit('/', 1)[-1] == 'bun']
    if len(entries) != 1:
        raise SystemExit('Expected exactly one Bun executable in the verified archive')
    executable = Path('build-tmp/bun-linux-host')
    executable.write_bytes(archive.read(entries[0]))
    executable.chmod(0o755)
PY
