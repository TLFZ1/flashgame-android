#!/usr/bin/env bash
# Download third-party runtime assets into app/assets.
# Usage: ./tools/fetch-deps.sh [ruffle-nightly-date]
set -euo pipefail
cd "$(dirname "$0")/.."

DATE="${1:-2026-10-23}"
ASSETS="app/assets"
mkdir -p "$ASSETS"

echo "==> Ruffle web (self-hosted) $DATE"
curl -fL -o /tmp/ruffle-web.zip \
  "https://github.com/ruffle-rs/ruffle/releases/download/nightly-$DATE/ruffle-nightly-${DATE//-/_}-web-selfhosted.zip"
python - <<'PY'
import zipfile, os, shutil
z = zipfile.ZipFile('/tmp/ruffle-web.zip')
for n in z.namelist():
    if n in ('ruffle.js', 'LICENSE_MIT', 'LICENSE_APACHE') or n.endswith('.wasm'):
        z.extract(n, 'app/assets')
        print('  extracted', n)
os.makedirs('app/assets/LICENSES', exist_ok=True)
for n in ('LICENSE_MIT', 'LICENSE_APACHE'):
    p = os.path.join('app/assets', n)
    if os.path.exists(p):
        shutil.move(p, os.path.join('app/assets/LICENSES', n))
PY

echo "==> Noto Sans JP (OFL 1.1)"
curl -fL -o /tmp/noto.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf"

python - <<'PY'
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
f = TTFont('/tmp/noto.ttf')
instantiateVariableFont(f, {'wght': 400}, inplace=True)
FAM, PS = 'Noto Sans CJK JP', 'NotoSansCJKJP-Regular'
for rec in f['name'].names:
    nid = rec.nameID
    try:
        if nid == 1: rec.string = FAM
        elif nid == 2: rec.string = 'Regular'
        elif nid == 4: rec.string = FAM + ' Regular'
        elif nid == 6: rec.string = PS
        elif nid == 16: rec.string = FAM
        elif nid == 17: rec.string = 'Regular'
    except Exception:
        pass
f.save('app/assets/Noto Sans CJK JP')
print('  wrote app/assets/Noto Sans CJK JP')
PY

curl -fL -o "$ASSETS/LICENSES/OFL_NotoSansJP.txt" \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/notosansjp/OFL.txt"

echo "done."
