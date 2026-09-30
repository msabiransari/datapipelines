# shellcheck shell=bash
#
# pregate-2b-lib.sh — stage 2b's affected-test selection, as a sourced library.
#
# Why a library: the selection must be drivable by `scripts/pregate.sh --self-test`
# against ISOLATED fixture trees and a recording, refusing Gradle stand-in (342-b,
# #342 round) — not only against the working tree's real diff.
#
# The contract (what "affected" means, fixed by the orchestrator review of 2026-09-30):
#   * a changed CONCRETE test class runs focused, by class (--tests FQCN);
#   * a changed file that is NOT a runnable test class — an abstract base, a sealed
#     hierarchy, an interface, a helper object, a @TestConfiguration, a helper class —
#     must schedule its REAL runnable consumers: the test classes that reference the
#     type, found by name in the module's test sources and expanded TRANSITIVELY through
#     intermediate non-runnable bases (BrowserSuite → SchedulesBrowserSuite → the
#     Schedules*BrowserTest classes — a subclass of an intermediate base never
#     textually mentions the base it ultimately extends);
#   * when consumers cannot be established — no references, or the file was DELETED or
#     RENAMED (no longer on disk) — the fallback is the WHOLE module, never a silent
#     skip;
#   * a changed build file, or a changed test RESOURCE, also means the whole module;
#   * while changed test sources exist the plan is NEVER EMPTY: focused-with-classes or
#     whole. The delivered abstract/sealed skip produced a green no-op instead — a
#     BrowserSuite-only change printed "skipping … nothing to run here" and no browser
#     class executed, which is the defect this selector replaces.
#
# Entry points (all pure over the filesystem: no git, no gradle):
#   pg2b::is_runnable_test_file <file>            — exit 0 if Gradle's --tests can run it
#   pg2b::consumers_of <TypeName> <src-root> <exclude-file> — runnable consumers, one path/line
#   pg2b::plan_module <module> <build-file> <src-root> <changed-files...>
#       → stdout: "whole" | ("focused" + FQCN lines) | "none";  reasons on stderr
#   pg2b::gradle_args <module> <plan-words...>    → gradle argv, one arg per line
#   pg2b::self_test                               — fixtures + recording stub; 0 = PASS

pg2b::is_runnable_test_file() {
  local f="$1"
  [ -f "$f" ] || return 1
  # A file whose own top-level declaration cannot be selected by --tests.
  if grep -qE '^[[:space:]]*(abstract|sealed)[[:space:]]+(class|interface)[[:space:]]' "$f"; then return 1; fi
  if grep -qE '^[[:space:]]*(interface|object)[[:space:]]+[A-Z]' "$f"; then return 1; fi
  # And a concrete class is a TEST class only if it carries a test annotation
  # (@TestInstance alone does not make a file runnable; @TestConfiguration is not @Test;
  # the annotation may be fully qualified — @org.junit.jupiter.api.Test).
  grep -qE '^[[:space:]]*@[A-Za-z0-9_.]*(Test|ParameterizedTest|TestFactory|RepeatedTest)([(:]|$)' "$f"
}

# Runnable test classes that reference TypeName, directly or through intermediate
# non-runnable declarations (an abstract base's subclasses are found via the base).
pg2b::consumers_of() {
  local name="$1" src="$2" exclude="$3"
  local -a frontier=("$name")
  local -A seen=()
  seen["$name"]=1
  local consumers="" probe found
  while [ "${#frontier[@]}" -gt 0 ]; do
    probe="${frontier[0]}"
    frontier=("${frontier[@]:1}")
    while IFS= read -r found; do
      [ -z "$found" ] && continue
      [ "$found" = "$exclude" ] && continue
      [ -n "${seen["$found"]:-}" ] && continue
      seen["$found"]=1
      if pg2b::is_runnable_test_file "$found"; then
        consumers+="$found"$'\n'
      else
        # An intermediate base or shared helper: recurse on ITS type name.
        frontier+=("$(basename "$found" .kt)")
      fi
    done < <(grep -rlE "\\b${probe}\\b" "$src" --include='*.kt' 2>/dev/null)
  done
  printf '%s' "$consumers"
}

pg2b::fqcn_of() {
  local f="$1" src="$2"
  local name
  name="$(basename "$f" .kt)"
  # The runnable identity is the DECLARED package, not the path — Gradle's --tests
  # matches classes, and a file's directory is only a convention (the brief: file name
  # is not always runnable class identity). Path-derived package is the fallback.
  local pkg
  pkg="$(grep -m1 -E '^[[:space:]]*package[[:space:]]+[A-Za-z0-9_.]+' "$f" | awk '{print $2}')"
  if [ -n "$pkg" ]; then
    printf '%s.%s' "$pkg" "$name"
    return 0
  fi
  local rel="${f#"$src"/}"
  local dir
  dir="$(dirname "$rel")"
  if [ "$dir" = "." ]; then
    printf '%s' "$name"
  else
    printf '%s.%s' "$(echo "$dir" | tr '/' '.')" "$name"
  fi
}

# stdout: "whole" (fallback: consumers not derivable — build file, resource, orphan
# helper, deleted/renamed file), "focused" + one FQCN per line, or "none" (no test
# source of the module changed). Reasons for every decision go to stderr.
pg2b::plan_module() {
  local module="$1" build_file="$2" src="$3"
  shift 3
  local f
  for f in "$@"; do
    if [ "$f" = "$build_file" ]; then
      echo "  2b tests/$module: build file changed → whole module" >&2
      echo "whole"
      return 0
    fi
  done
  local -a classes=()
  local fallback="" consumers c
  for f in "$@"; do
    case "$f" in
      "$src"/*.kt) ;;
      *"/src/test/"*)
        echo "  2b tests/$module: $(basename "$f") — test resource changed → whole module" >&2
        echo "whole"
        return 0
        ;;
      *) continue ;; # another module's file — not stage 2b's concern
    esac
    if [ ! -f "$f" ]; then
      echo "  2b tests/$module: $(basename "$f") — deleted/renamed, consumers not derivable → whole module" >&2
      fallback=whole
      continue
    fi
    if pg2b::is_runnable_test_file "$f"; then
      classes+=("$(pg2b::fqcn_of "$f" "$src")")
    else
      consumers="$(pg2b::consumers_of "$(basename "$f" .kt)" "$src" "$f")"
      if [ -n "$consumers" ]; then
        echo "  2b tests/$module: $(basename "$f") — not runnable itself; scheduling its runnable consumers" >&2
        while IFS= read -r c; do
          [ -z "$c" ] && continue
          classes+=("$(pg2b::fqcn_of "$c" "$src")")
        done <<< "$consumers"
      else
        echo "  2b tests/$module: $(basename "$f") — not runnable, no consumers found → whole module" >&2
        fallback=whole
      fi
    fi
  done
  if [ -n "$fallback" ]; then
    echo "whole"
    return 0
  fi
  if [ "${#classes[@]}" -gt 0 ]; then
    echo "focused"
    printf '%s\n' "${classes[@]}" | sort -u
  else
    echo "none"
  fi
}

# The stage's gradle argv for one module's plan, one argument per line — the exact
# shape stage 2b passes to gradlew (the recording stand-in asserts it verbatim).
pg2b::gradle_args() {
  local module="$1"
  shift
  local plan="${1:-}"
  case "$plan" in
    whole)
      printf ':tests:%s:test\n' "$module"
      ;;
    focused)
      printf ':tests:%s:test\n' "$module"
      shift
      local c
      for c in "$@"; do printf -- '--tests\n%s\n' "$c"; done
      printf -- '-x\n:tests:%s:verifyTestsExecuted\n' "$module"
      ;;
    # "none": no output — nothing to run for this module.
  esac
}

pg2b::self_test() {
  local T
  T="$(mktemp -d /tmp/pg2b-selftest.XXXXXX)"
  # The value is captured NOW: at EXIT the local is out of scope (and set -u would fail).
  trap "rm -rf '$T'" EXIT
  local build="$T/tests/browser-tests/build.gradle.kts"
  local src="$T/tests/browser-tests/src/test/kotlin/co/x"
  local res="$T/tests/browser-tests/src/test/resources"
  mkdir -p "$src" "$res"
  echo '// fixture' > "$build"

  cat > "$src/BrowserSuite.kt" <<'EOF'
package co.x

abstract class BrowserSuite {
    protected val baseUrl: String get() = "http://localhost:0"
}
EOF
  cat > "$src/SchedulesBrowserSuite.kt" <<'EOF'
package co.x

abstract class SchedulesBrowserSuite : BrowserSuite() {
    fun helper() = 1
}
EOF
  cat > "$src/AlphaBrowserTest.kt" <<'EOF'
package co.x

class AlphaBrowserTest : BrowserSuite() {
    @org.junit.jupiter.api.Test
    fun a() {}
}
EOF
  cat > "$src/BetaSchedulesTest.kt" <<'EOF'
package co.x

class BetaSchedulesTest : SchedulesBrowserSuite() {
    @org.junit.jupiter.api.Test
    fun b() {}
}
EOF
  cat > "$src/GammaBrowserTest.kt" <<'EOF'
package co.x

class GammaBrowserTest {
    @org.junit.jupiter.api.Test
    fun g() {}
}
EOF
  cat > "$src/OriginHelper.kt" <<'EOF'
package co.x

class OriginHelper {
    fun hold(): Int = 1
}
EOF
  cat > "$src/HelperUserTest.kt" <<'EOF'
package co.x

class HelperUserTest {
    val h = OriginHelper()
    @org.junit.jupiter.api.Test
    fun u() {}
}
EOF
  cat > "$src/OrphanHelper.kt" <<'EOF'
package co.x

object OrphanHelper {
    const val K = 1
}
EOF
  cat > "$src/CanFoo.kt" <<'EOF'
package co.x

interface CanFoo {
    fun foo()
}
EOF
  cat > "$src/ImplTest.kt" <<'EOF'
package co.x

class ImplTest : CanFoo {
    override fun foo() {}
    @org.junit.jupiter.api.Test
    fun i() {}
}
EOF
  echo '{}' > "$res/fixture.json"

  local fails=0
  ck() { # ck <name> <expected-stdout> <changed-files...>
    local name="$1" expected="$2"
    shift 2
    local got
    got="$(pg2b::plan_module browser-tests "$build" "$src" "$@" 2>/dev/null | tr '\n' ' ' | sed 's/ *$//')"
    if [ "$got" = "$expected" ]; then
      echo "  ok   $name"
    else
      echo "  FAIL $name"
      echo "       expected: $expected"
      echo "       got:      $got"
      fails=$((fails + 1))
    fi
  }

  # The delivered defect, pinned as a property: a changed abstract base schedules its
  # REAL consumers, transitively through the intermediate abstract suite, and is never
  # a green no-op.
  ck "abstract-base-only-changed" "focused co.x.AlphaBrowserTest co.x.BetaSchedulesTest" "$src/BrowserSuite.kt"
  ck "abstract-base-plus-concrete" "focused co.x.AlphaBrowserTest co.x.BetaSchedulesTest co.x.GammaBrowserTest" "$src/BrowserSuite.kt" "$src/GammaBrowserTest.kt"
  # A helper class (concrete, no tests) with consumers: the consumers run, focused.
  ck "helper-with-consumers" "focused co.x.HelperUserTest" "$src/OriginHelper.kt"
  # An interface: its implementors run, focused.
  ck "interface-with-implementors" "focused co.x.ImplTest" "$src/CanFoo.kt"
  # Consumers that cannot be established: the WHOLE module — never a silent skip.
  ck "helper-without-consumers" "whole" "$src/OrphanHelper.kt"
  # A deleted/renamed file: consumers not derivable → whole module.
  ck "deleted-or-renamed" "whole" "$src/GhostTest.kt"
  # The knobs changed → whole module (unchanged rule).
  ck "build-file-changed" "whole" "$build"
  # A changed test RESOURCE can affect any test that loads it → whole module.
  ck "resource-changed" "whole" "$res/fixture.json"
  # An ordinary concrete test change stays focused — no module blow-up.
  ck "concrete-test-changed" "focused co.x.GammaBrowserTest" "$src/GammaBrowserTest.kt"
  # A file of the module that changed but carries no tests and no references
  # (@TestConfiguration-style wiring is just a helper without consumers here).
  cat > "$src/Wiring.kt" <<'EOF'
package co.x

class Wiring {
    fun bean() = Object()
}
EOF
  ck "wiring-without-consumers" "whole" "$src/Wiring.kt"

  # --- the recording, refusing Gradle stand-in --------------------------------
  # Records the argv it is handed and REFUSES anything that is not a :tests:*:test
  # invocation — never a fallthrough to a real gradle.
  local rec="$T/gradle-argv.log"
  : > "$rec"
  cat > "$T/gradlew-standin" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$@" >> "$rec"
if [ "\$#" -eq 0 ]; then
  echo "stand-in refused: empty argv" >> "$rec"
  exit 64
fi
case "\$1" in
  :tests:*:test) exit 0 ;;
  *) echo "stand-in refused: \$1" >> "$rec"; exit 64 ;;
esac
EOF
  chmod +x "$T/gradlew-standin"

  # The refusal has teeth: a non-task invocation exits 64.
  if "$T/gradlew-standin" compileKotlin >/dev/null 2>&1; then
    echo "  FAIL stand-in-refuses-non-task (exit was 0)"
    fails=$((fails + 1))
  else
    echo "  ok   stand-in-refuses-non-task"
  fi

  # Stage 2b's own arg construction, driven through the stand-in for the
  # abstract-base-only change: the recorded argv must be the REAL browser consumers —
  # a non-empty, runnable selection, not the delivered no-op.
  local plan
  plan="$(pg2b::plan_module browser-tests "$build" "$src" "$src/BrowserSuite.kt" 2>/dev/null)"
  local -a gargs=()
  local line
  while IFS= read -r line; do
    [ -n "$line" ] && gargs+=("$line")
  done < <(pg2b::gradle_args browser-tests $plan)
  : > "$rec" # only THIS drive's argv is under comparison
  "$T/gradlew-standin" ${gargs[@]+"${gargs[@]}"}
  local expected_argv=":tests:browser-tests:test|--tests|co.x.AlphaBrowserTest|--tests|co.x.BetaSchedulesTest|-x|:tests:browser-tests:verifyTestsExecuted"
  local got_argv
  got_argv="$(tr '\n' '|' < "$rec" | sed 's/|$//')"
  if [ "$got_argv" = "$expected_argv" ]; then
    echo "  ok   stand-in-recorded-real-browser-consumers"
  else
    echo "  FAIL stand-in-recorded-real-browser-consumers"
    echo "       expected: $expected_argv"
    echo "       got:      $got_argv"
    fails=$((fails + 1))
  fi

  if [ "$fails" -eq 0 ]; then
    echo "  2b selector self-test: PASS"
    return 0
  fi
  echo "  2b selector self-test: FAIL ($fails failure(s))"
  return 1
}
