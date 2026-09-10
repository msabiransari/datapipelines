#!/usr/bin/env bash
# build-site-chrome.sh — regenerate static/site/css/site-chrome.css from its three
# vendored sources (tokens, base, motion), in that order, under the derivation header.
# SiteCssBundleParityTest fails the build when the bundle drifts from the sources, so
# run this after any design-system update. Born 111 §C.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="modules/web/src/main/resources/static/site/css/site-chrome.css"
V="modules/web/src/main/resources/static/vendor/design-system"
python3 - "$V" "$OUT" <<'PY'
import sys
v, out = sys.argv[1], sys.argv[2]
current = open(out).read() if __import__('os').path.exists(out) else None
header = current.split('============================================================ */\n', 1)[0] + '============================================================ */\n\n' if current else None
tokens = open(f'{v}/tokens.css').read()
base = open(f'{v}/base.css').read()
motion = open(f'{v}/motion.css').read()
auto = open(f'{v}/themes/auto.css').read()
site = open('modules/web/src/main/resources/static/site/css/site.css').read()
body = tokens + '\n' + base + '\n' + motion + '\n' + auto + '\n' + site
if header is None:
    sys.exit('site-chrome.css not found; the header is authored, not generated')
open(out, 'w').write(header + body)
print(f'site-chrome.css regenerated: {len(header + body)} bytes')
PY
