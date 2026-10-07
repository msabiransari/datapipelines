# shellcheck shell=bash
# One gate/pregate per user across worktrees. Acquire BEFORE deleting logs or
# starting Gradle. Keep fd 9 in the wrapper, close it in Gradle children so an
# idle daemon cannot retain the lock after the wrapper exits. Never unlink it:
# replacing a locked inode would let a second wrapper acquire a different lock.
verification::lock() {
  local kind="$1" root="$2"
  local dir="${XDG_CACHE_HOME:-$HOME/.cache}/datapipelines"
  command -v flock >/dev/null 2>&1 || {
    echo "verification refused: flock is required (install util-linux)." >&2
    return 2
  }
  mkdir -p "$dir" || return 2
  exec 9>>"$dir/verification.lock" || return 2
  if ! flock -n 9; then
    echo "verification busy: another gate/pregate holds $dir/verification.lock" >&2
    if [ -r "$dir/verification-holder.txt" ]; then
      cat "$dir/verification-holder.txt" >&2
    fi
    exec 9>&-
    return 75
  fi
  printf 'kind=%s pid=%s cwd=%s started=%s\n' "$kind" "$$" "$root" \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$dir/verification-holder.txt" || {
      exec 9>&-
      return 2
    }
}
