#!/usr/bin/env bash
# site-lighthouse.sh — the marketing site's Lighthouse budget, as a guard.
#
# What it measures: Lighthouse's performance, accessibility, best-practices and
# SEO categories, on six pages of the exported static site:
#   /  /how-it-works  /faq  /tableau  /published-api  /for/saas-teams
# (115 §C added /how-it-works.)
#
# The floor is 95 in every audited category on every page. Any score below the
# floor names the page, the category and the score, and the script exits 1.
# Fix the CAUSE (an unsized image, a missing alt, a contrast pair, a blocking
# resource) — never lower the floor.
#
# How to run:
#   ./gradlew :modules:web:websiteExport   # produces modules/web/build/website-export
#   scripts/site-lighthouse.sh
#
# NOT wired into ./gradlew build, deliberately: the run needs the network
# (npx downloads the pinned Lighthouse on first use) and a Chrome binary, and
# the build gate stays hermetic. This script is run by the handback and
# re-run by the orchestrator at merge.
#
# The Lighthouse version is PINNED (12.8.2, the newest 12.x — read from
# https://registry.npmjs.org/lighthouse at authoring time, 2026-09-10; 13.x is
# out but the brief pinned the 12.x line) so a score is comparable across runs.
# Born 111 §C. Baseline: five pages, four categories, all >= 95. 115 added /how-it-works.

set -euo pipefail

LIGHTHOUSE_VERSION="12.8.2"
FLOOR=95
EXPORT_DIR="modules/web/build/website-export"
# Trailing slashes: the export is a directory tree, and python's http.server answers a
# slash-less directory path with a redirect — one wasted round trip on the critical chain.
PAGES=("/" "/how-it-works/" "/faq/" "/tableau/" "/published-api/" "/for/saas-teams/")
CATEGORIES=("performance" "accessibility" "best-practices" "seo")

command -v python3 >/dev/null || { echo "site-lighthouse: python3 is required to serve the export" >&2; exit 1; }
command -v npx >/dev/null || { echo "site-lighthouse: npx is required (Node toolchain)" >&2; exit 1; }

if [ ! -f "$EXPORT_DIR/index.html" ]; then
  echo "site-lighthouse: $EXPORT_DIR/index.html not found — run './gradlew :modules:web:websiteExport' first" >&2
  exit 1
fi

# A free local port, so two lanes can run this at once.
PORT=$(python3 - <<PY
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
)

WORK_DIR=$(mktemp -d)
trap 'kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$WORK_DIR"' EXIT

echo "site-lighthouse: serving $EXPORT_DIR on http://127.0.0.1:$PORT (Lighthouse $LIGHTHOUSE_VERSION)"
# 111 §C: a stdlib static server (python3's own http.server building blocks) rather than the
# bare `python3 -m http.server` one-liner. Two deliberate differences, both closer to any
# production static host: HTTP/1.1 with keep-alive (the one-liner speaks HTTP/1.0, one
# connection per request), and gzip for compressible types (the one-liner serves raw bytes).
# Measured on this tree: raw HTTP/1.0 scores the pages 1-3 points below the identical pages
# over HTTP/1.1+gzip — a property of the harness, not of the pages.
python3 - "$EXPORT_DIR" "$PORT" <<'PY' >/dev/null 2>&1 &
import functools
import gzip
import http.server
import io
import socketserver
import sys

root, port = sys.argv[1], int(sys.argv[2])

COMPRESSIBLE = (
    ".html", ".css", ".js", ".json", ".svg", ".xml", ".txt", ".webmanifest",
)


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send_head(self):
        path = self.translate_path(self.path)
        if path.endswith("/") or "." not in path.rsplit("/", 1)[-1]:
            path = path.rstrip("/") + "/index.html"
        try:
            f = open(path, "rb")
        except OSError:
            self.send_error(404, "File not found")
            return None
        ctype = self.guess_type(path)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        wants_gzip = "gzip" in (self.headers.get("Accept-Encoding") or "")
        if path.endswith(COMPRESSIBLE) and wants_gzip:
            body = gzip.compress(f.read(), 6)
            f.close()
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return io.BytesIO(body)
        self.send_header("Content-Length", str(f.seek(0, 2)))
        f.seek(0)
        self.end_headers()
        return f

    def log_message(self, *args):
        pass


socketserver.ThreadingTCPServer.allow_reuse_address = True
Handler = functools.partial(Handler, directory=root)
with socketserver.ThreadingTCPServer(("127.0.0.1", port), Handler) as httpd:
    httpd.serve_forever()
PY
SERVER_PID=$!
disown "$SERVER_PID"

# The server is up when the socket answers; bounded, so a wedged box fails loudly.
for _ in $(seq 1 50); do
  if python3 -c "import socket,sys; s=socket.create_connection(('127.0.0.1',$PORT),0.5); s.close()" 2>/dev/null; then
    break
  fi
  sleep 0.2
done

FAILED=0
printf "%-22s %-14s %-14s %-16s %-6s\n" "page" "performance" "accessibility" "best-practices" "seo"
printf "%-22s %-14s %-14s %-16s %-6s\n" "----" "-----------" "-------------" "---------------" "---"

for page in "${PAGES[@]}"; do
  OUT="$WORK_DIR/$(echo "$page" | tr '/ ' '__').json"
  npx --yes "lighthouse@$LIGHTHOUSE_VERSION" \
    "http://127.0.0.1:$PORT$page" \
    --quiet \
    --chrome-flags="--headless=new --no-sandbox --disable-gpu" \
    --only-categories="$(IFS=,; echo "${CATEGORIES[*]}")" \
    --output=json \
    --output-path="$OUT" \
    >/dev/null 2>&1

  if ! python3 - "$page" "$OUT" "$FLOOR" <<PY
import json, sys

page, path, floor = sys.argv[1], sys.argv[2], int(sys.argv[3])
scores = json.load(open(path))["categories"]
cells, bad = [], []
for key in ("performance", "accessibility", "best-practices", "seo"):
    score = scores[key]
    value = score["score"]  # 0..1, or None when not scored
    if value is None:
        cells.append("n/a")
        bad.append((key, "n/a"))
        continue
    value = round(value * 100)
    cells.append(str(value))
    if value < floor:
        bad.append((key, value))
print("%-22s %-14s %-14s %-16s %-6s" % (page, *cells))
if bad:
    for category, value in bad:
        print(f"site-lighthouse: FAIL {page} {category} = {value} (floor {floor})", file=sys.stderr)
    sys.exit(1)
PY
  then
    FAILED=1
  fi
done

if [ "$FAILED" -ne 0 ]; then
  echo "site-lighthouse: FAIL — at least one page scored below the floor; fix the cause, never lower the floor" >&2
  exit 1
fi
echo "site-lighthouse: OK — six pages, four categories, all >= $FLOOR"
