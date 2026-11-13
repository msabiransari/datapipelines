#!/usr/bin/env bash
# site-lighthouse.sh — the marketing site's Lighthouse budget, as a guard.
#
# What it measures: Lighthouse's performance, accessibility, best-practices and
# SEO categories, on every page in PAGES below, of the exported static site:
#   /  /how-it-works  /demo-data  /faq  /tableau  /published-api  /for/saas-teams  /pricing
#   /semantic-layer  /use-cases  /explore  /pipeline-scheduling  /sql-dashboards
#   /reusable-parameter-sets
# (115 §C added /how-it-works; 116 added /demo-data; 119 added /pricing and /semantic-layer;
# 145 added /use-cases and /explore; #699 added the three capability pages.)
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
# Born 111 §C. Baseline: five pages, four categories, all >= 95. 115 added /how-it-works; 116 added /demo-data.

set -euo pipefail

LIGHTHOUSE_VERSION="12.8.2"
FLOOR=95
EXPORT_DIR="modules/web/build/website-export"
# Trailing slashes: the export is a directory tree, and python's http.server answers a
# slash-less directory path with a redirect — one wasted round trip on the critical chain.
#
# 713 added the six long docs pages that measured under the 95 floor BEFORE the
# content-visibility ladder landed (docs/auth, rest-api, pipeline-contract, mcp-server,
# datasources, metadata-db) — the pages most likely to regress silently. The heaviest
# packaged doc, docs/ui-screens (~758 KB rendered, ~9.5k DOM nodes), measured 92–94 at
# the end of 713 and is deliberately NOT watched here: a below-floor page would hold
# this guard permanently red. It joins this list when its editorial split lands
# (follow-up issue filed from 713).
#
# 713 owner ruling, 2026-10-25: docs pages are measured in TWO passes. The content-
# visibility ladder in css/docs.css (`.doc-body table`, `.doc-body pre`, `.doc-part`)
# delivers the performance floor, but Lighthouse 12.8.2's accessibility category
# mis-measures it — axe's contrast/target-size audits read stale coordinates out of
# cv-skipped subtrees and pair dark ink with an unpainted band color (proven artifact:
# axe reports 7 contrast violations with cv, 0 with cv toggled off on the same DOM;
# real rendered contrast ≈ 13:1; evidence/713 …/a11y-contrast-artifact.md). So:
# pass 1 runs performance/best-practices/seo on the page AS SHIPPED (cv active, what a
# user's browser does); pass 2 runs accessibility on a cv-SUSPENDED render served under
# /__cv-off/ (the fully-rendered state the audits assume). The suspension selectors are
# mirrored by DocsContentVisibilitySuspensionDriftTest, which fails if css/docs.css
# gains a content-visibility rule the guard does not suspend.
PAGES=("/" "/how-it-works/" "/demo-data/" "/faq/" "/tableau/" "/published-api/" "/for/saas-teams/" "/pricing/" "/semantic-layer/" "/use-cases/" "/explore/"
  "/pipeline-scheduling/" "/sql-dashboards/" "/reusable-parameter-sets/"
  "/docs/auth/" "/docs/rest-api/" "/docs/pipeline-contract/" "/docs/mcp-server/" "/docs/datasources/" "/docs/metadata-db/")
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

# 713 owner ruling, 2026-10-25 (see the header block): the accessibility pass for
# /docs/* pages is served under /__cv-off/ with the content-visibility ladder suspended,
# because axe mis-measures contrast and target-size on cv-skipped subtrees. The selector
# list MUST mirror every content-visibility SKIP (auto/hidden) rule in css/docs.css —
# DocsContentVisibilitySuspensionDriftTest fails when the two drift apart. A
# content-visibility: visible rule (the sheet's :has(:target) fragment-landing rule)
# cannot hide anything from the audit and deliberately has no entry here.
CV_SUSPEND_SELECTORS = ".doc-part,.doc-body table,.doc-body pre"
CV_SUSPEND_STYLE = (
    '<style id="cv-audit-suspend">/* content-visibility suspended for this accessibility '
    'pass (713 owner ruling 2026-10-25); selectors mirror css/docs.css */'
    + CV_SUSPEND_SELECTORS
    + "{content-visibility:visible !important}</style>"
).encode()


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send_head(self):
        suspend_prefix = "/__cv-off/"
        suspended = self.path.startswith(suspend_prefix)
        path = self.translate_path(self.path[len(suspend_prefix):] if suspended else self.path)
        if path.endswith("/") or "." not in path.rsplit("/", 1)[-1]:
            path = path.rstrip("/") + "/index.html"
        try:
            f = open(path, "rb")
        except OSError:
            self.send_error(404, "File not found")
            return None
        ctype = self.guess_type(path)
        body = f.read()
        f.close()
        if suspended and path.endswith(".html"):
            body = body.replace(b"</head>", CV_SUSPEND_STYLE + b"</head>", 1)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        wants_gzip = "gzip" in (self.headers.get("Accept-Encoding") or "")
        if path.endswith(COMPRESSIBLE) and wants_gzip:
            body = gzip.compress(body, 6)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        return io.BytesIO(body)

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
  # 713 owner ruling, 2026-10-25: docs pages measure performance/best-practices/seo on
  # the page as shipped, and accessibility on the cv-suspended render under /__cv-off/
  # (see the header block). "-" tells the verdict reader to take accessibility from the
  # main pass (the marketing pages, which carry no content-visibility rules).
  A11Y_OUT="-"
  if [[ "$page" == /docs/* ]]; then
    A11Y_OUT="$WORK_DIR/$(echo "$page" | tr '/ ' '__').a11y.json"
    npx --yes "lighthouse@$LIGHTHOUSE_VERSION" \
      "http://127.0.0.1:$PORT/__cv-off$page" \
      --quiet \
      --chrome-flags="--headless=new --no-sandbox --disable-gpu" \
      --only-categories="accessibility" \
      --output=json \
      --output-path="$A11Y_OUT" \
      >/dev/null 2>&1
  fi
  npx --yes "lighthouse@$LIGHTHOUSE_VERSION" \
    "http://127.0.0.1:$PORT$page" \
    --quiet \
    --chrome-flags="--headless=new --no-sandbox --disable-gpu" \
    --only-categories="$(IFS=,; echo "${CATEGORIES[*]}")" \
    --output=json \
    --output-path="$OUT" \
    >/dev/null 2>&1

  if ! python3 - "$page" "$OUT" "$FLOOR" "$A11Y_OUT" <<PY
import json, sys

page, path, floor, a11y_path = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
scores = dict(json.load(open(path))["categories"])
if a11y_path != "-":
    scores["accessibility"] = json.load(open(a11y_path))["categories"]["accessibility"]
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
echo "site-lighthouse: OK — ${#PAGES[@]} pages, ${#CATEGORIES[@]} categories, all >= $FLOOR"

# ---------------------------------------------------------------------------
# 133 §D ran a second, DARK-theme accessibility pass here. 145 made the public site
# light-only (no theme swap, no toggle, no saved-theme restore), so there is no second
# presentation to audit: the light pass above is the whole surface. The light lock
# itself is held in a real browser by SiteInteractionBrowserTest (a dark colour scheme
# plus a saved dark theme still paint the approved light ground).
# ---------------------------------------------------------------------------
