# shellcheck shell=bash
# Verification slots per user across worktrees (#472, #485). Acquire BEFORE deleting
# logs or starting Gradle; every refusal returns 75.
#  - Two slot files, verification.slot.0 and .1. A pregate-class holder (every kind
#    except `gate`) takes ONE on fd 9, so the existing `9>&-` on Gradle children
#    still keeps a daemon from inheriting it. `gate` takes BOTH (fd 9, fd 10) plus
#    the pre-#485 verification.lock (fd 11), which older script copies still use,
#    so a gate stays exclusive against old and new wrappers; gate.sh closes all three.
#  - A pregate-class holder only probes verification.lock (take and drop): an older
#    copy holding it refuses us; an older copy starting later does not see our slot.
#  - One holder per checkout: a root equal to a live holder's cwd is refused even
#    with a slot free (one build/, one .pregate-logs/).
#  - MemAvailable below DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB refuses first.
# Never unlink a slot or lock file: replacing a locked inode would let a second
# wrapper acquire a different lock.

# Default floor: estimate from recorded JVM peaks, rounded up to the GB (1 GB =
# 1024 MB), #1095. Peak resident memory: Gradle daemon 2047888 kB -> 2000 MB
# (lane 1058, 2026-11-22); Kotlin daemon 3.65 GB -> 3738 MB (full compile, run
# 20261009T134317Z-b3fef5b0-1423801); test JVM 4575140 kB -> 4468 MB at -Xmx2g
# (lane 1058, 2026-11-22, web suite). Sum 10206 MB -> 10240 MB (10 GB).
# No 1.5 x margin: follows the gate floor's rule (owner 2026-11-13,
# gate-stages.sh); applying that margin would round up to 15 GB.
# Not measured: all three JVMs in one cold run, the integration task with its
# own 6g heap, or guard-stage containers; these terms do not bound total RSS.
# Changing the pregate's -Pdp.test.heap means measuring the test JVM again and
# updating its term; the self-test ties the measured heap to the pregate's heap.
VERIFICATION_PREGATE_TEST_HEAP=2g
VERIFICATION_PREGATE_PEAK_MB=(2000 3738 4468)
VERIFICATION_MEM_FLOOR_DEFAULT_MB=$(( (VERIFICATION_PREGATE_PEAK_MB[0] + VERIFICATION_PREGATE_PEAK_MB[1] + VERIFICATION_PREGATE_PEAK_MB[2] + 1023) / 1024 * 1024 ))
VERIFICATION_SLOTS=(0 1)

# Prints the floor in MB; garbage falls back to the default with a stderr line.
verification::_mem_floor_mb() {
  local raw="${DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB-}"
  if [ -z "$raw" ]; then
    printf '%s\n' "$VERIFICATION_MEM_FLOOR_DEFAULT_MB"
  elif [[ "$raw" =~ ^[0-9]{1,9}$ ]]; then
    printf '%s\n' "$((10#$raw))"
  else
    echo "verification: DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB='$raw' is not an integer of at most 9 digits; using the default ${VERIFICATION_MEM_FLOOR_DEFAULT_MB} MB" >&2
    printf '%s\n' "$VERIFICATION_MEM_FLOOR_DEFAULT_MB"
  fi
}

# Returns 0 when admitted, 75 below the floor, 2 when MemAvailable is unreadable.
verification::_check_memory() {
  local floor avail
  floor="$(verification::_mem_floor_mb)"
  [ "$floor" -gt 0 ] || return 0
  avail="$(awk '/^MemAvailable:/ { print int($2 / 1024); exit }' /proc/meminfo 2>/dev/null)"
  if [ -z "$avail" ]; then
    echo "verification refused: cannot read MemAvailable from /proc/meminfo (set DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB=0 to skip the floor)" >&2
    return 2
  fi
  if [ "$avail" -lt "$floor" ]; then
    echo "verification refused: MemAvailable $avail MB below floor $floor MB (DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB)" >&2
    return 75
  fi
}

# A lock file is held when a fresh descriptor cannot take it (nothing is kept).
verification::_held() { ! flock -n "$1" true 2>/dev/null; }

# Prints `slot=<n> <record>` for every live slot except the caller's own.
verification::_live_holders() {
  local dir="$1" own="${2:-}" slot record
  for slot in "${VERIFICATION_SLOTS[@]}"; do
    [ "$slot" != "$own" ] || continue
    verification::_held "$dir/verification.slot.$slot" || continue
    record="(no holder record)"
    [ ! -r "$dir/verification-holder.$slot.txt" ] || record="$(cat "$dir/verification-holder.$slot.txt")"
    printf 'slot=%s %s\n' "$slot" "$record"
  done
}

# Prints the live holder lines whose cwd is exactly <root>.
verification::_same_checkout() {
  local dir="$1" root="$2" own="${3:-}" line
  while IFS= read -r line; do
    [ "$(printf '%s\n' "$line" | sed -n 's/.* cwd=\(.*\) started=.*/\1/p')" != "$root" ] || printf '%s\n' "$line"
  done < <(verification::_live_holders "$dir" "$own")
}

verification::_busy() { # reason dir → prints the refusal with every live holder
  echo "verification busy: $1" >&2
  verification::_live_holders "$2" >&2
}

# The gate's descriptors; a pregate-class holder owns fd 9 only.
verification::_release() { exec 9>&- 10>&- 11>&-; }

verification::_record() { # file kind root
  printf 'kind=%s pid=%s cwd=%s started=%s\n' "$2" "$$" "$3" \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$1"
}

verification::lock() {
  local kind="$1" root="$2"
  local dir="${XDG_CACHE_HOME:-$HOME/.cache}/datapipelines" rc=0 slot taken= same
  command -v flock >/dev/null 2>&1 || {
    echo "verification refused: flock is required (install util-linux)." >&2
    return 2
  }
  root="$(realpath -m -- "$root" 2>/dev/null || printf '%s' "$root")"
  verification::_check_memory || return $?
  mkdir -p "$dir" || return 2
  if [ "$kind" = gate ]; then
    exec 11>>"$dir/verification.lock" || return 2
    if ! flock -n 11; then
      echo "verification busy: a gate, or an older gate/pregate copy, holds $dir/verification.lock" >&2
      [ ! -r "$dir/verification-holder.txt" ] || cat "$dir/verification-holder.txt" >&2
      verification::_release; return 75
    fi
    local fd=9
    for slot in "${VERIFICATION_SLOTS[@]}"; do
      # Numbered descriptor: the path is quoted inside the eval and expanded there.
      eval "exec $fd>>\"\$dir/verification.slot.\$slot\"" || { verification::_release; return 2; }
      if ! flock -n "$fd"; then
        verification::_release
        verification::_busy "a gate needs every verification slot and slot $slot is held" "$dir"
        return 75
      fi
      fd=$((fd + 1))
    done
    for slot in "${VERIFICATION_SLOTS[@]}"; do
      verification::_record "$dir/verification-holder.$slot.txt" "$kind" "$root" || rc=2
    done
    # Older copies print this record when they are refused.
    verification::_record "$dir/verification-holder.txt" "$kind" "$root" || rc=2
    [ "$rc" -eq 0 ] || verification::_release
    return "$rc"
  fi
  if verification::_held "$dir/verification.lock"; then
    echo "verification busy: a gate, or an older gate/pregate copy, holds $dir/verification.lock" >&2
    [ ! -r "$dir/verification-holder.txt" ] || cat "$dir/verification-holder.txt" >&2
    verification::_live_holders "$dir" >&2
    return 75
  fi
  for slot in "${VERIFICATION_SLOTS[@]}"; do
    exec 9>>"$dir/verification.slot.$slot" || { exec 9>&-; return 2; }
    if flock -n 9; then taken="$slot"; break; fi
    exec 9>&-
  done
  if [ -z "$taken" ]; then
    verification::_busy "every verification slot is held (${#VERIFICATION_SLOTS[@]} pregate-class holders at most; a gate holds them all)" "$dir"
    return 75
  fi
  verification::_record "$dir/verification-holder.$taken.txt" "$kind" "$root" || { exec 9>&-; return 2; }
  # Same checkout: checked AFTER our record is written, so of two contenders for one
  # checkout racing for the two slots, at least the later checker sees the other.
  same="$(verification::_same_checkout "$dir" "$root" "$taken")"
  if [ -n "$same" ]; then
    exec 9>&-
    echo "verification busy: this checkout already has a live holder (one build/ and .pregate-logs/ per checkout): $root" >&2
    verification::_live_holders "$dir" >&2
    return 75
  fi
}
