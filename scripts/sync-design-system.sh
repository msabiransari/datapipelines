#!/usr/bin/env bash
# sync-design-system.sh — vendor the design-system CSS into the web module.
#
#   ./scripts/sync-design-system.sh            # copy dist/ → web static vendor, rewrite manifest
#   ./scripts/sync-design-system.sh --check    # verify committed assets + manifest against dist/ (no writes)
#
# Source of truth: the SIBLING project ../design-system-starter (see
# DEVELOPMENT.md §5 and docs/pipeline-editor.md §13.2 — the design system is
# an independent project; this repo vendors a specific build of it for
# reproducibility). Build it first: `cd ../design-system-starter && npm run
# build` produces dist/.
#
# What this script guarantees (F7, 013 — until now the sync was done by hand
# and five documents referenced a script that did not exist):
#   * copies each vendored file from dist/ and verifies the copy's SHA-256
#     against the source's (a truncated/partial copy fails loudly);
#   * records the package name + version (from design-system-starter's
#     package.json) and the SHA-256 of EVERY vendored file in
#     modules/web/src/main/resources/static/vendor/design-system/vendor-manifest.json,
#     rewriting ONLY the "design-system" block — the cytoscape/dagre/alpine
#     entries stay byte-identical;
#   * is idempotent: running it with an unchanged dist/ produces NO diff;
#   * refuses loudly (exit 1) if dist/ or package.json is absent, a listed
#     file is missing from dist/, a copied file's hash does not match its
#     source, or the manifest cannot be parsed/validated. Any failure exits
#     non-zero — there is no fail-soft path in a vendoring step.
#   * --check additionally asserts the manifest's sha256 KEY SET *and* files
#     ARRAY each equal the curated list in BOTH directions (014/F3, 018/F6):
#     a list addition that was never synced, a stale manifest entry after a
#     removal, or a hand-edit/bad merge that leaves design-system.files out
#     of step with VENDED_FILES is drift — the per-file hash loop alone
#     cannot see any of these. Both sides are compared under LC_ALL=C
#     ordering (018/F4): the shell's `sort` uses the locale collation
#     (en_US.UTF-8 orders themes/auto.css BEFORE themes/Auto.css) while
#     Python's sorted() is codepoint (the reverse), so equal sets could
#     compare unequal and feed comm mismatched orderings.
#
# The vendored surface is a CURATED SUBSET of dist/ (the files the app
# actually serves): classless.css, base-scoped.css, adapters/, and the icon
# sprite are deliberately NOT vendored. Files in dist/ that are not on the
# list are announced (not copied); adding a file to the vendored surface is
# a deliberate edit to the list below, reviewed like any code change.
#
# A stale vendored file (committed but no longer on the list) is ANNOUNCED,
# never deleted by this script — removal from the repo is a reviewed change.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

SRC_PROJECT="$ROOT/../design-system-starter"
SRC_DIST="$SRC_PROJECT/dist"
DEST="$ROOT/modules/web/src/main/resources/static/vendor/design-system"
MANIFEST="$DEST/vendor-manifest.json"
# Recorded in the manifest exactly as the committed block has it today.
MANIFEST_SOURCE="../design-system-starter/dist/"

# The curated vendored surface, in manifest order.
VENDED_FILES=(
  "tokens.css"
  "base.css"
  "motion.css"
  "primitives.css"
  "icons.css"
  "themes/saas.css"
  "themes/light.css"
  "themes/dark.css"
  "themes/professional.css"
  "themes/auto.css"
  "themes/healthcare.css"
  "themes/minimal.css"
  "themes/forest.css"
  "themes/ocean.css"
)

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

fail() { echo "sync-design-system: FAIL — $*" >&2; exit 1; }

[ -d "$SRC_DIST" ] || fail "design-system dist/ not found at $SRC_DIST — run 'npm install && npm run build' in ../design-system-starter first."
[ -f "$SRC_PROJECT/package.json" ] || fail "$SRC_PROJECT/package.json not found — cannot determine package name/version."
[ -f "$MANIFEST" ] || fail "manifest not found at $MANIFEST."

ds_package=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$SRC_PROJECT/package.json") \
  || fail "cannot read the package name from $SRC_PROJECT/package.json."
ds_version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$SRC_PROJECT/package.json") \
  || fail "cannot read the version from $SRC_PROJECT/package.json."
ds_license=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("license", "UNKNOWN"))' "$SRC_PROJECT/package.json") \
  || fail "cannot read the license from $SRC_PROJECT/package.json."

for f in "${VENDED_FILES[@]}"; do
  [ -f "$SRC_DIST/$f" ] || fail "listed vendored file '$f' is missing from $SRC_DIST — curate the list or rebuild the design system."
done

CHECK_ONLY=false
[ "${1:-}" = "--check" ] && CHECK_ONLY=true

# Announce dist/ content that is deliberately not vendored (curation made
# visible, so new dist files are a DECISION, not a silent gap).
announce_not_vendored() {
  local listed sorted_dist extra
  listed=$(printf '%s\n' "${VENDED_FILES[@]}" | LC_ALL=C sort)
  sorted_dist=$(cd "$SRC_DIST" && find . -type f ! -path './.*' | sed 's|^\./||' | LC_ALL=C sort)
  extra=$(comm -13 <(printf '%s\n' "$listed") <(printf '%s\n' "$sorted_dist") || true)
  if [ -n "$extra" ]; then
    echo "sync-design-system: note — present in dist/ but NOT vendored (curated surface):"
    printf '%s\n' "$extra" | sed 's/^/    /'
  fi
}
announce_not_vendored

if $CHECK_ONLY; then
  # Verify the committed vendored files AND dist/ against the manifest's
  # recorded hashes, and the recorded version against package.json. This is
  # the mechanical check behind pipeline-editor.md §13.1 step 4. (Read via
  # process substitution, NOT a pipe: a `| while` subshell would swallow the
  # failure status. The manifest's JSON validity is proven FIRST — a process
  # substitution's own failure is invisible to the while loop.)
  status=0
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$MANIFEST" || fail "manifest is not valid JSON."
  # The manifest's sha256 KEY SET must EQUAL the curated list, both
  # directions (014/F3): the verification loop below is driven by the
  # manifest's keys while the OK line counts VENDED_FILES — a file added
  # to the list but never synced was claimed verified without ever being
  # looked at (proven: classless.css added to the list → "check OK — 15
  # vendored file(s)", exit 0). Missing keys = unsynced addition; extra
  # keys = stale manifest entry after a list removal.
  # LC_ALL=C (018/F4): `sort` under the locale collation and Python's
  # codepoint sorted() disagree on case-differing names (verified:
  # themes/auto.css vs themes/Auto.css), so equal sets could compare
  # unequal and hand comm mismatched orderings. The `|| true`s on the
  # comm calls (the :99 precedent) keep a residual ordering surprise from
  # aborting the script before either DRIFT message prints.
  listed=$(printf '%s\n' "${VENDED_FILES[@]}" | LC_ALL=C sort)
  in_manifest=$(python3 - "$MANIFEST" <<'PY'
import json, sys
block = json.load(open(sys.argv[1])).get("design-system", {})
print("\n".join(sorted(block.get("sha256", {}).keys())))
PY
)
  if [ "$in_manifest" != "$listed" ]; then
    unsynced=$(comm -23 <(printf '%s\n' "$listed") <(printf '%s\n' "$in_manifest") || true)
    stale_key=$(comm -13 <(printf '%s\n' "$listed") <(printf '%s\n' "$in_manifest") || true)
    if [ -n "$unsynced" ]; then
      echo "sync-design-system: DRIFT — in the curated list but NOT in the manifest (run a full sync):" >&2
      printf '%s\n' "$unsynced" | sed 's/^/    /' >&2
    fi
    if [ -n "$stale_key" ]; then
      echo "sync-design-system: DRIFT — in the manifest but NOT in the curated list (stale entry):" >&2
      printf '%s\n' "$stale_key" | sed 's/^/    /' >&2
    fi
    status=1
  fi
  # The manifest's "files" ARRAY must equal the curated list too, both
  # directions (018/F6): a full sync writes both the files array and the
  # sha256 map from the same VENDED_FILES, so any divergence is a hand-edit
  # or bad merge — and it passed --check while every sha256 key stayed
  # intact (proven: files array with one entry swapped → "check OK", exit 0).
  # Same LC_ALL=C comparison; order is NOT asserted (the sync writes curated
  # order, but drift means CONTENT, not sequence).
  in_manifest_files=$(python3 - "$MANIFEST" <<'PY'
import json, sys
block = json.load(open(sys.argv[1])).get("design-system", {})
print("\n".join(sorted(block.get("files", []))))
PY
)
  if [ "$in_manifest_files" != "$listed" ]; then
    files_unsynced=$(comm -23 <(printf '%s\n' "$listed") <(printf '%s\n' "$in_manifest_files") || true)
    files_stale=$(comm -13 <(printf '%s\n' "$listed") <(printf '%s\n' "$in_manifest_files") || true)
    if [ -n "$files_unsynced" ]; then
      echo "sync-design-system: DRIFT — files array: in the curated list but NOT in the manifest (run a full sync):" >&2
      printf '%s\n' "$files_unsynced" | sed 's/^/    /' >&2
    fi
    if [ -n "$files_stale" ]; then
      echo "sync-design-system: DRIFT — files array: in the manifest but NOT in the curated list (stale entry):" >&2
      printf '%s\n' "$files_stale" | sed 's/^/    /' >&2
    fi
    status=1
  fi
  first=true
  saw_hash=false
  while IFS= read -r line; do
    if $first; then
      first=false
      if [ "$line" != "$ds_version" ]; then
        echo "sync-design-system: version drift — manifest has '$line', package.json has '$ds_version'" >&2
        status=1
      fi
      continue
    fi
    rel=${line%% *}
    want=${line#* }
    [ -n "$rel" ] || continue
    saw_hash=true
    for side in "$DEST/$rel" "$SRC_DIST/$rel"; do
      if [ ! -f "$side" ]; then
        echo "sync-design-system: MISSING — $side" >&2
        status=1
        continue
      fi
      got=$(sha256_of "$side")
      if [ "$got" != "$want" ]; then
        echo "sync-design-system: HASH MISMATCH — $side (manifest=$want actual=$got)" >&2
        status=1
      fi
    done
  done < <(python3 - "$MANIFEST" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
block = m.get("design-system", {})
print(block.get("version", ""))
for k, v in block.get("sha256", {}).items():
    print(f"{k} {v}")
PY
)
  if ! $saw_hash; then
    echo "sync-design-system: manifest has no sha256 map for design-system — nothing was verified; run a full sync once." >&2
    status=1
  fi
  if [ "$status" -eq 0 ]; then
    echo "sync-design-system: check OK — ${#VENDED_FILES[@]} vendored file(s) match the manifest on both sides; version $ds_version."
  fi
  exit "$status"
fi

# ---- copy + verify -----------------------------------------------------------
for f in "${VENDED_FILES[@]}"; do
  mkdir -p "$DEST/$(dirname "$f")"
  src_hash=$(sha256_of "$SRC_DIST/$f") || fail "cannot hash $SRC_DIST/$f."
  cp "$SRC_DIST/$f" "$DEST/$f" || fail "copying $SRC_DIST/$f failed."
  dst_hash=$(sha256_of "$DEST/$f") || fail "cannot hash the copied $DEST/$f."
  [ "$src_hash" = "$dst_hash" ] || fail "copied $f does not match its source (source=$src_hash copy=$dst_hash) — refusing to continue."
done

# Announce (never delete) stale vendored files no longer on the list.
stale=$(cd "$DEST" && find . -type f -name '*.css' ! -name 'vendor-manifest.json' | sed 's|^\./||' | LC_ALL=C sort \
  | while IFS= read -r rel; do
      printf '%s\n' "${VENDED_FILES[@]}" | grep -qx "$rel" || echo "$rel"
    done)
if [ -n "$stale" ]; then
  echo "sync-design-system: note — STALE vendored file(s) present but no longer on the list (remove deliberately):"
  printf '%s\n' "$stale" | sed 's/^/    /'
fi

# ---- manifest: rewrite ONLY the design-system block --------------------------
# python3 does the surgical splice (brace-matched block replacement — the same
# dependency docs-audit.sh already relies on): every byte outside the block,
# including the cytoscape/dagre/alpine entries, is preserved verbatim, and
# the result must re-parse as JSON before it is written back.
python3 - "$MANIFEST" "$ds_package" "$ds_version" "$MANIFEST_SOURCE" "$ds_license" "${VENDED_FILES[@]}" <<'PY'
import json, sys

manifest_path = sys.argv[1]
package, version, source, license_ = sys.argv[2:6]
files = sys.argv[6:]
import hashlib
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()

import os
root = os.path.dirname(manifest_path)
lines = open(manifest_path, encoding="utf-8").read().splitlines(keepends=True)

# Locate the top-level "design-system": { ... } block by brace counting.
start = None
for i, line in enumerate(lines):
    if line.startswith('  "design-system": {'):
        start = i
        break
if start is None:
    sys.stderr.write("sync-design-system: FAIL — no top-level \"design-system\" block in %s\n" % manifest_path)
    sys.exit(1)
depth = 0
end = None
for i in range(start, len(lines)):
    depth += lines[i].count("{") - lines[i].count("}")
    if i > start and depth == 0:
        end = i
        break
if end is None:
    sys.stderr.write("sync-design-system: FAIL — unbalanced braces while locating the design-system block\n")
    sys.exit(1)
trailing_comma = lines[end].rstrip().endswith(",")

block = ['  "design-system": {\n',
         '    "package": %s,\n' % json.dumps(package),
         '    "version": %s,\n' % json.dumps(version),
         '    "source": %s,\n' % json.dumps(source),
         '    "files": [\n']
block += ['      %s,\n' % json.dumps(f) for f in files[:-1]]
block += ['      %s\n' % json.dumps(files[-1]), '    ],\n', '    "sha256": {\n']
hashes = [(f, sha(os.path.join(root, f))) for f in files]
block += ['      %s: %s,\n' % (json.dumps(f), json.dumps(h)) for f, h in hashes[:-1]]
block += ['      %s: %s\n' % (json.dumps(hashes[-1][0]), json.dumps(hashes[-1][1])), '    },\n']
block += ['    "license": %s\n' % json.dumps(license_), '  }%s\n' % ("," if trailing_comma else "")]

new_content = "".join(lines[:start] + block + lines[end + 1:])
json.loads(new_content)  # must parse before we write anything back
open(manifest_path, "w", encoding="utf-8").write(new_content)
print("sync-design-system: manifest design-system block rewritten (package %s %s, %d files, SHA-256 recorded)." % (package, version, len(files)))
PY

echo "sync-design-system: vendored ${#VENDED_FILES[@]} file(s) from $SRC_DIST into $DEST."
