#!/usr/bin/env bash
#
# verify-image-pins.sh — every container image the tests pin must still exist where the pin
# says, checked against the REGISTRY, never the local cache.
#
# Why (MISTAKES.md, "Green on the Laptop Because of What the Laptop Has CACHED", twice):
# 2026-09-11 MinIO withdrew a tag from Docker Hub; 2026-09-24 MinIO removed EVERY tag from
# quay.io/minio/minio and its binary downloads (HTTP 410). Both times every local gate was
# green for days because the laptop had the image, and CI's integration job failed on every
# push. A `docker pull` is a cache hit here and a 2-minute Testcontainers timeout there.
#
# What it does: greps the test trees for `registry/repo:tag` and `registry/repo@sha256:…`
# literals (the shapes Testcontainers takes), de-duplicates them, and asks the registry for
# each manifest with `docker manifest inspect`, which never consults the local image store.
#
# Verdicts per pin (#341 — a transport error is not a pin verdict; on CI run 36662861214 a
# Docker Hub CDN hiccup was reported as a MISSING pin while the same run's gate job resolved
# the same pin seconds later):
#   ok           the registry serves the manifest.
#   MISSING      the registry itself answered "no such pin" — manifest unknown, 404, 410 —
#                with no retry (a verdict does not change on a second ask). Exit 1.
#   UNREACHABLE  every attempt failed WITHOUT a registry not-found answer (transport, auth,
#                rate limit): retried a bounded number of times with a backoff, then reported
#                as "the registry could not be reached", never as the pin being gone. Exit 1.
# Exit 2 when the grep found nothing (a broken grep must never read as "all pins fine" —
# the non-vacuity floor).
#
# Runs: in CI before the integration tests (a missing pin fails in seconds, not after a
# 2-minute pull timeout per suite), and by hand before a dependency review. Needs the Docker
# CLI and network; no daemon-side image is touched.
#
# Usage: ./scripts/verify-image-pins.sh            (from the repo root)
#        ./scripts/verify-image-pins.sh --self-test
# Knobs: VERIFY_PINS_ATTEMPTS (default 3 — the first ask plus two retries) and
#        VERIFY_PINS_BACKOFF_SECONDS (default 2, doubled per retry) exist for the self-test.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

if [[ "${1:-}" == "--self-test" ]]; then
  # Doctors a temp tree with four planted pins and a RECORDING docker stand-in, and asserts
  # all four verdicts plus the retry counts and the non-vacuity floor. The stub records its
  # argv and REFUSES anything the pins did not fake — never a fallthrough to the real docker
  # (MISTAKES.md: a stub that forwards to the real binary IS the real binary).
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/scripts" "$tmp/modules/selftest/src/test" "$tmp/stub"
  cp "$0" "$tmp/scripts/verify-image-pins.sh"
  {
    echo 'val ok = "ok.example/postgres:16-alpine"'
    echo 'val gone = "gone.example/minio:missing"'
    echo 'val flaky = "flaky.example/redis:7-alpine"'
    echo 'val down = "down.example/mysql:8"'
  } > "$tmp/modules/selftest/src/test/Pins.kt"
  cat > "$tmp/stub/docker" <<'STUB'
#!/usr/bin/env bash
set -u
log="${DOCKER_STUB_LOG:?DOCKER_STUB_LOG must be set}"
printf 'CALL %s\n' "$*" >> "$log"
[ "${1:-}" = "manifest" ] && [ "${2:-}" = "inspect" ] && [ -n "${3:-}" ] || {
  echo "stub: refused: $*" >&2; exit 64; }
image="$3"
case "$image" in
  ok.example/postgres:16-alpine) exit 0 ;;
  gone.example/minio:missing)
    echo "no such manifest: $image" >&2; exit 1 ;;
  flaky.example/redis:7-alpine)
    n=$(grep -cF "manifest inspect $image" "$log" || true)
    if [ "$n" -lt 3 ]; then
      echo "flaky.example transport error attempt $n: Get \"https://flaky.example/v2/\": dial tcp: i/o timeout" >&2
      exit 1
    fi
    exit 0 ;;
  down.example/mysql:8)
    echo "down.example transport error: Get \"https://down.example/v2/\": dial tcp: connection refused" >&2
    exit 1 ;;
  *)
    echo "stub: refused unexpected image: $image" >&2; exit 64 ;;
esac
STUB
  chmod +x "$tmp/stub/docker"
  export DOCKER_STUB_LOG="$tmp/stub.log"
  : > "$DOCKER_STUB_LOG"
  out=$(cd "$tmp" && PATH="$tmp/stub:$PATH" VERIFY_PINS_ATTEMPTS=3 VERIFY_PINS_BACKOFF_SECONDS=0 \
        bash scripts/verify-image-pins.sh 2>&1)
  rc=$?
  calls() { grep -cF "manifest inspect $1" "$DOCKER_STUB_LOG" || true; }
  # 1 = gone (MISSING) + down (UNREACHABLE); ok and flaky recovered.
  if [ "$rc" -eq 1 ] &&
     echo "$out" | grep -qF 'ok       ok.example/postgres:16-alpine' &&
     echo "$out" | grep -qF 'ok       flaky.example/redis:7-alpine' &&
     echo "$out" | grep -qF 'MISSING  gone.example/minio:missing' &&
     echo "$out" | grep -q 'UNREACHABLE  down.example/mysql:8' &&
     echo "$out" | grep -q 'could not be reached' &&
     [ "$(calls ok.example/postgres:16-alpine)" -eq 1 ] &&
     [ "$(calls gone.example/minio:missing)" -eq 1 ] &&
     [ "$(calls flaky.example/redis:7-alpine)" -eq 3 ] &&
     [ "$(calls down.example/mysql:8)" -eq 3 ] &&
     ! grep -q 'refused' "$DOCKER_STUB_LOG"; then
    # The non-vacuity floor's own negative: an empty tree must exit 2, never read as fine.
    mkdir -p "$tmp/empty-tree/scripts"
    cp "$0" "$tmp/empty-tree/scripts/verify-image-pins.sh"
    out2=$(cd "$tmp/empty-tree" && PATH="$tmp/stub:$PATH" bash scripts/verify-image-pins.sh 2>&1)
    rc2=$?
    if [ "$rc2" -eq 2 ] && echo "$out2" | grep -q 'found NO image pins'; then
      echo "self-test OK: MISSING (registry not-found, no retry), retry-then-ok (3 attempts), UNREACHABLE (3 attempts, own wording), every verdict from the recording stub, and the empty-tree exit 2 — the stub refused nothing it did not fake"
      exit 0
    fi
    echo "SELF-TEST FAILED: the non-vacuity floor did not hold (rc=$rc2, wanted exit 2 with the broken-grep wording)" >&2
    printf '%s\n' "$out2" | tail -10 >&2
    exit 1
  fi
  echo "SELF-TEST FAILED: verdicts or retry counts did not land as planted (rc=$rc; wanted MISSING on gone, ok on ok and flaky-after-2-retries, UNREACHABLE on down after 3 attempts, no stub refusals)" >&2
  printf '%s\n' "$out" | tail -15 >&2
  echo "--- stub log ---" >&2
  tail -15 "$DOCKER_STUB_LOG" >&2
  exit 1
fi

# registry-qualified or Docker-Hub-official images, with an exact tag; excludes build/ dirs.
tag_pins="$(grep -rhoE '"([a-z0-9.-]+(\.[a-z]{2,})(:[0-9]+)?/)?[a-z0-9._/-]+:[A-Za-z0-9][A-Za-z0-9._-]*"' \
          modules/*/src/test tests/*/src/test 2>/dev/null \
        | tr -d '"' \
        | grep -E '^([a-z0-9.-]+\.[a-z]{2,}(:[0-9]+)?/[a-z0-9._/-]+|(postgres|redis|mysql|mariadb|minio/minio|greenmail/[a-z-]+|gvenzl/[a-z-]+|testcontainers/[a-z-]+))(:[A-Za-z0-9][A-Za-z0-9._-]*)$' \
        | sort -u)"

# digest pins (`repo@sha256:<hex64>`); #229: a 64-hex digest is unambiguous, so no
# registry allowlist — the manifest check itself is the verdict. This is the hole the
# MinIO pin fell through: the tag-only grep never matched a `@sha256:` literal.
digest_pins="$(grep -rhoE '"[a-z0-9.-]+(\.[a-z]{2,})?(:[0-9]+)?(/[a-z0-9._/-]+)?@sha256:[a-f0-9]{64}"' \
          modules/*/src/test tests/*/src/test 2>/dev/null \
        | tr -d '"' \
        | sort -u)"

pins="$(printf '%s\n%s\n' "$tag_pins" "$digest_pins" | grep . | sort -u)"

count=$(printf '%s\n' "$pins" | grep -c . || true)
if [ "$count" -eq 0 ]; then
  echo "verify-image-pins: found NO image pins in modules/*/src/test or tests/*/src/test — the grep is broken, not the tree" >&2
  exit 2
fi
echo "verify-image-pins: $count pinned image(s) to check against their registries"

attempts="${VERIFY_PINS_ATTEMPTS:-3}"
backoff="${VERIFY_PINS_BACKOFF_SECONDS:-2}"

# True when the registry itself answered "no such pin" (#341): the CLI's not-found phrasings
# for manifest unknown / 404 / 410. A transport failure says Get "…", dial tcp, timeout,
# unauthorized, toomanyrequests — none of these phrases — and must never read as MISSING.
is_registry_not_found() {
  printf '%s' "$1" | grep -qiE 'no such manifest|manifest unknown|MANIFEST_UNKNOWN|NOT_FOUND|HTTP 404|HTTP 410|status:? ?404|status:? ?410|404 Not Found|410 Gone'
}

missing=0
unreachable=0
while IFS= read -r image; do
  [ -z "$image" ] && continue
  verdict=""
  out=""
  n=0
  while [ "$n" -lt "$attempts" ]; do
    if out=$(docker manifest inspect "$image" 2>&1 >/dev/null); then
      verdict=ok
      break
    fi
    if is_registry_not_found "$out"; then
      verdict=missing
      break
    fi
    n=$((n + 1))
    [ "$n" -lt "$attempts" ] && sleep "$((backoff * n))"
  done
  case "$verdict" in
    ok)
      echo "  ok       $image"
      ;;
    missing)
      echo "  MISSING  $image — $(echo "$out" | tr '\n' ' ' | cut -c1-160)"
      missing=$((missing + 1))
      ;;
    *)
      echo "  UNREACHABLE  $image — registry could not be reached after $attempts attempt(s); not a verdict on the pin — $(echo "$out" | tr '\n' ' ' | cut -c1-160)"
      unreachable=$((unreachable + 1))
      ;;
  esac
done <<< "$pins"

if [ "$missing" -gt 0 ]; then
  echo "verify-image-pins: $missing pin(s) no longer served by their registry. Move the pin (a mirror the project controls, or a tag that exists) before the next push; every local gate is green only because the image is cached here." >&2
  exit 1
fi
if [ "$unreachable" -gt 0 ]; then
  echo "verify-image-pins: $unreachable pin(s) could not be VERIFIED — the registry could not be reached (transport errors above). This says nothing about whether the pins exist; fix the network or a mirror and re-run before the next push." >&2
  exit 1
fi
echo "verify-image-pins: every pin resolves at its registry"
