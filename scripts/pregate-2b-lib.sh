# shellcheck shell=bash
#
# pregate-2b-lib.sh — stage 2b's affected-test selection, as a sourced library.
#
# Why a library: the selection must be drivable by `scripts/pregate.sh --self-test`
# against ISOLATED fixture trees and a recording, refusing Gradle stand-in (342-b,
# #342 round) — not only against the working tree's real diff.
#
# The contract (what "affected" means, fixed by the orchestrator reviews of 2026-09-30):
#   * a changed CONCRETE test class runs focused, by class (--tests FQCN);
#   * the runnable identity is the file's DECLARED class, not its file name (342-c):
#     Kotlin files are not one-class-per-file by rule, and a file whose declared class
#     differs from its file name is ordinary Kotlin. A changed runnable file therefore
#     selects the class its declarations NAME. A file whose identity cannot be derived
#     with certainty — no parseable concrete declaration, SEVERAL (two runnable classes
#     in one file, or a test and a helper together), or a declaration-shaped line the
#     parse cannot resolve — is NEVER focused on a guess: the whole module runs, which
#     is complete no matter what the file declares;
#   * a changed file that is NOT a runnable test class — an abstract base, a sealed
#     hierarchy, an interface, a helper object, a @TestConfiguration, a helper class —
#     must schedule its REAL runnable consumers: the test classes that reference the
#     type, found by its DECLARED name(s) in the module's test sources and expanded
#     TRANSITIVELY through intermediate non-runnable bases, also by their DECLARED
#     names (BrowserSuite → SchedulesBrowserSuite → the Schedules*BrowserTest classes
#     never textually mention their grandparent base — and an intermediate whose
#     declaration differs from its file name is traversed by the name it declares);
#   * when consumers cannot be established — no references, no parseable declaration to
#     traverse through, or the file was DELETED or RENAMED (no longer on disk) — the
#     fallback is the WHOLE module, never a silent skip;
#   * a changed build file, or a changed test RESOURCE, also means the whole module;
#   * while changed test sources exist the plan is NEVER EMPTY: focused-with-classes or
#     whole. The delivered abstract/sealed skip produced a green no-op instead — a
#     BrowserSuite-only change printed "skipping … nothing to run here" and no browser
#     class executed, which is the defect this selector replaces. Its file-name-derived
#     identity was the second defect: FooTest.kt declaring FooTest AND BarTest scheduled
#     only FooTest — a green plan that silently dropped a real case (342-c).
#
# Entry points (all pure over the filesystem: no git, no gradle):
#   pg2b::is_runnable_test_file <file>            — exit 0 if the FILE carries runnable tests
#   pg2b::declared_types <file>                   — "concrete|other <Name>" per top-level type
#   pg2b::has_unparsed_declaration <file>         — exit 0 if a declaration-shaped line is unparseable
#   pg2b::file_classes <file> <src-root>          — FQCN(s) of a runnable file's declared class; exit 1 = not derivable
#   pg2b::consumers_of <src-root> <exclude-file> <TypeName...> — runnable consumer paths, one per line
#   pg2b::plan_module <module> <build-file> <src-root> <changed-files...>
#       → stdout: "whole" | ("focused" + FQCN lines) | "none";  reasons on stderr
#   pg2b::gradle_args <module> <plan-words...>    → gradle argv, one arg per line
#   pg2b::self_test                               — fixtures + recording stub; 0 = PASS

pg2b::is_runnable_test_file() {
  local f="$1"
  [ -f "$f" ] || return 1
  # A helper beside a concrete test does not make that test disappear. Mixed
  # declarations go through file_classes, whose conservative fallback runs all tests.
  pg2b::declared_types "$f" | grep -q '^concrete ' || return 1
  # And a concrete class is a TEST class only if it carries a test annotation
  # (@TestInstance alone does not make a file runnable; @TestConfiguration is not @Test;
  # the annotation may be fully qualified — @org.junit.jupiter.api.Test).
  grep -qE '^[[:space:]]*@[A-Za-z0-9_.]*(Test|ParameterizedTest|TestFactory|RepeatedTest)([(:]|$)' "$f"
}

# The top-level type declarations of a Kotlin file, one per line: "concrete <Name>" for a
# concrete class (the only kind Gradle's --tests can execute as a test), "other <Name>"
# for abstract/sealed classes, interfaces, objects and enums (never test containers
# themselves, but the names consumers reference — traversal seeds). Column-0 only: Kotlin
# nests declarations indented, and a nested class is not independently selectable.
pg2b::declared_types() {
  local f="$1"
  # Groups: 1 = modifier repetition, 2 = last modifier token, 3 = annotation args,
  # 4 = keyword, 5 = name. A same-line annotation whose arguments span lines defeats the
  # parse — that file lands in the conservative whole-module branch, never on a guess.
  local re='^((public|internal|private|protected|open|final|abstract|sealed|data|value|inner|enum|expect|actual|annotation|fun|@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?)[[:space:]]+)*(class|interface|object)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)'
  local line
  while IFS= read -r line; do
    [[ "$line" =~ $re ]] || continue
    local mods="${BASH_REMATCH[1]}"
    local kw="${BASH_REMATCH[4]}"
    local name="${BASH_REMATCH[5]}"
    if [ "$kw" = class ] && ! [[ "$mods" =~ (abstract|sealed|enum|annotation) ]]; then
      printf 'concrete %s\n' "$name"
    else
      printf 'other %s\n' "$name"
    fi
  done < "$f"
}

# A column-0 line that DECLARES a type but yields no parseable declaration (a keyword
# with no name beside it, or modifier spellings outside the known set) means the file's
# declaration set is not fully derivable — the caller must not focus on a partial parse.
pg2b::has_unparsed_declaration() {
  grep -qE '^([A-Za-z@_][A-Za-z0-9_.]*[[:space:]]+)*(class|interface|object)([[:space:]]*\{|[[:space:]]*$)' "$1"
}

# The FQCN a runnable file's declared class is selected by, or exit 1 when the identity
# is not derivable — never a file-name guess (the delivered defect: Misnamed.kt declaring
# ActualTest scheduled witness.Misnamed, a class that does not exist).
pg2b::file_classes() {
  local f="$1" src="$2"
  if pg2b::has_unparsed_declaration "$f"; then return 1; fi
  local -a concrete=()
  local line
  while IFS= read -r line; do
    # A helper can have consumers in other files while this file owns tests too.
    # Focusing either side alone is incomplete; the whole module is the safe plan.
    [ "${line%% *}" = concrete ] || return 1
    concrete+=("${line#* }")
  done < <(pg2b::declared_types "$f")
  # Exactly one concrete declaration, or the conservative fallback: two runnable classes
  # in one file (or a test beside a helper) must not silently drop the second case.
  if [ "${#concrete[@]}" -ne 1 ]; then return 1; fi
  pg2b::fqcn "$f" "$src" "${concrete[0]}"
}

# The FQCN a class is selected by: DECLARED package (not the path — a file's directory is
# only a convention), plus the DECLARED class name passed by the caller.
pg2b::fqcn() {
  local f="$1" src="$2" name="$3"
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

# Runnable test files that reference any of the given TYPE NAMES, directly or through
# intermediate non-runnable declarations (an abstract base's subclasses are found via the
# base's DECLARED name). Sets PG2B_UNCERTAIN=1 when an intermediate file's declarations
# cannot be parsed — its subclasses are unfindable, so its consumers are not derivable.
PG2B_UNCERTAIN=0
pg2b::consumers_of() {
  local src="$1" exclude="$2"
  shift 2
  local -a frontier=("$@")
  local -A seen=()
  local name
  for name in "$@"; do seen["$name"]=1; done
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
        # An intermediate base or shared helper: recurse on the names IT declares.
        local -a names=()
        local declared
        while IFS= read -r declared; do
          [ -z "$declared" ] && continue
          names+=("${declared#* }")
        done < <(pg2b::declared_types "$found")
        if [ "${#names[@]}" -eq 0 ]; then
          PG2B_UNCERTAIN=1
        else
          frontier+=("${names[@]}")
        fi
      fi
    done < <(grep -rlE "\\b${probe}\\b" "$src" --include='*.kt' 2>/dev/null)
  done
  printf '%s' "$consumers"
  # Status crosses command substitution; a mutated shell variable does not.
  [ "$PG2B_UNCERTAIN" -eq 0 ]
}

# stdout: "whole" (fallback: consumers or identity not derivable — build file, resource,
# orphan helper, deleted/renamed file, unparseable or multiple declarations), "focused"
# + one FQCN per line, or "none" (no test source of the module changed). Reasons for
# every decision go to stderr.
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
      local fc
      fc="$(pg2b::file_classes "$f" "$src")" || {

          echo "  2b tests/$module: $(basename "$f") — runnable, but its declared class identity is not derivable (0 or several concrete declarations, or an unparseable declaration line) → whole module" >&2
          fallback=whole
        }
        [ -n "$fc" ] && classes+=("$fc")
    else
      PG2B_UNCERTAIN=0
      local -a seed=()
      local declared
      while IFS= read -r declared; do
        [ -z "$declared" ] && continue
        seed+=("${declared#* }")
      done < <(pg2b::declared_types "$f")
      if [ "${#seed[@]}" -eq 0 ] || pg2b::has_unparsed_declaration "$f"; then
        echo "  2b tests/$module: $(basename "$f") — not runnable, its declared type names are not derivable → whole module" >&2
        fallback=whole
        continue
      fi
      consumers="$(pg2b::consumers_of "$src" "$f" "${seed[@]}")" || PG2B_UNCERTAIN=1
      if [ "$PG2B_UNCERTAIN" -ne 0 ]; then
        echo "  2b tests/$module: $(basename "$f") — an intermediate helper's declarations are not derivable; consumers not establishable → whole module" >&2
        fallback=whole
      elif [ -n "$consumers" ]; then
        echo "  2b tests/$module: $(basename "$f") — not runnable itself; scheduling its runnable consumers" >&2
        local cc
        while IFS= read -r c; do
          [ -z "$c" ] && continue
          cc="$(pg2b::file_classes "$c" "$src")" || {
            echo "  2b tests/$module: $(basename "$c") — a consumer's declared class identity is not derivable → whole module" >&2
            fallback=whole
            continue
          }
          classes+=("$cc")
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
  mkdir -p "$src" "$res" "$src/mismatch"
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

  # --- 342-c fixtures: file name is not class identity -------------------------
  # The brief's root case: FooTest.kt declaring runnable FooTest AND BarTest — the
  # delivered selector scheduled only FooTest and returned green with a real case
  # dropped. Two runnable declarations in one file are not derivable with certainty
  # at class level: the whole module runs, which drops nothing.
  cat > "$src/FooTest.kt" <<'EOF'
package co.x

class FooTest {
    @org.junit.jupiter.api.Test
    fun f1() {}
}

class BarTest {
    @org.junit.jupiter.api.Test
    fun f2() {}
}
EOF
  # The delivered selector derived identity from the file name: Misnamed.kt declaring
  # ActualTest scheduled witness.Misnamed — a class that does not exist.
  cat > "$src/Misnamed.kt" <<'EOF'
package co.x

class ActualTest {
    @org.junit.jupiter.api.Test
    fun m() {}
}
EOF
  # An intermediate helper whose DECLARATION differs from its file name: traversal must
  # seed the declared name, or the real consumer is unfindable.
  cat > "$src/MisnamedBase.kt" <<'EOF'
package co.x

abstract class ActualBase {
    fun shared() = 1
}
EOF
  cat > "$src/ChildOfActualTest.kt" <<'EOF'
package co.x

class ChildOfActualTest : ActualBase() {
    @org.junit.jupiter.api.Test
    fun c() {}
}
EOF
  # Package/directory mismatch: the DECLARED package names the class, not the path.
  cat > "$src/mismatch/PkgMismatchTest.kt" <<'EOF'
package co.y

class PkgMismatchTest {
    @org.junit.jupiter.api.Test
    fun p() {}
}
EOF
  # Mixed helper/test declarations in one file: identity not derivable → whole module.
  cat > "$src/MixedDeclarations.kt" <<'EOF'
package co.x

class MixedTest {
    @org.junit.jupiter.api.Test
    fun t() {}
}

class MixedHelper {
    fun h() = 1
}
EOF

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

  # --- the 342-c declared-identity assertions ---------------------------------
  # Two runnable classes in one file (FooTest.kt declaring FooTest AND BarTest): the
  # delivered selector returned green having scheduled only FooTest. Now: whole module.
  ck "two-runnable-classes-in-one-file" "whole" "$src/FooTest.kt"
  # A file whose declared class differs from its file name: the DECLARED class runs.
  ck "misnamed-file-selects-declared-class" "focused co.x.ActualTest" "$src/Misnamed.kt"
  # An intermediate helper declared under a different name: its real consumer runs.
  ck "misnamed-intermediate-schedules-real-consumer" "focused co.x.ChildOfActualTest" "$src/MisnamedBase.kt"
  # Package/directory mismatch: the declared package names the class.
  ck "package-directory-mismatch-uses-declared-package" "focused co.y.PkgMismatchTest" "$src/mismatch/PkgMismatchTest.kt"
  # A runnable test and a helper declared together: not derivable → whole module.
  ck "mixed-helper-and-test-declarations" "whole" "$src/MixedDeclarations.kt"

  # A runnable class beside a non-runnable helper owns tests AND has consumers.
  # Selecting only the helper's consumers silently drops the changed file's own test.
  cat > "$src/OwnAndHelper.kt" <<'EOF'
package co.x
class OwnTest {
    @org.junit.jupiter.api.Test
    fun own() {}
}
object SharedHelper
EOF
  cat > "$src/OtherTest.kt" <<'EOF'
package co.x
class OtherTest {
    val helper = SharedHelper
    @org.junit.jupiter.api.Test
    fun other() {}
}
EOF
  ck "runnable-and-object-with-consumer" "whole" "$src/OwnAndHelper.kt"

  # The traversal runs inside command substitution. An uncertainty flag set only
  # in that subshell must not disappear while its partial consumer list survives.
  cat > "$src/RootBase.kt" <<'EOF'
package co.x
abstract class RootBase
EOF
  cat > "$src/Intermediate.kt" <<'EOF'
package co.x
abstract class
    Intermediate : RootBase()
EOF
  cat > "$src/DirectTest.kt" <<'EOF'
package co.x
class DirectTest : RootBase() {
    @org.junit.jupiter.api.Test
    fun direct() {}
}
EOF
  cat > "$src/IndirectTest.kt" <<'EOF'
package co.x
class IndirectTest : Intermediate() {
    @org.junit.jupiter.api.Test
    fun indirect() {}
}
EOF
  ck "uncertain-intermediate-with-known-consumer" "whole" "$src/RootBase.kt"

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
