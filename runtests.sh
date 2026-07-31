#!/usr/bin/env bash
# Runs the verification stack. Four suites, in increasing order of cost:
#
#   1. source    tests/source_regressions.py, then tools/check_ui.py and
#                tools/check_layoutparams.py
#                Pure source contracts — translation coverage, no `!!`, ARGB
#                literals narrowed, Java-exact trim() used everywhere, no raw
#                control bytes, the infinite-loop guard is present, the palette
#                is monochrome, every icon name resolves. Then two static
#                cross-referencers: every Icons/Fa/Theme/Ui/R reference resolves
#                (with matching arity), brackets balance, and no addView() hands
#                a child the wrong LayoutParams class. Needs only python3.
#
#   2. offline   tools/build-offline.sh --tests
#                Generates Android stubs + org.json + R.java, COMPILES the whole
#                app, then runs both behavioural suites against the stubs —
#                including AgentLoopTests, which drives the real agent loop over
#                loopback sockets. Needs a JDK and a Kotlin compiler (which
#                tools/find-kotlinc.sh will happily borrow from a Gradle
#                install). No Android SDK, no network.
#
#   3. diff      tools/build-offline.sh --java <original>
#                Differential test: runs the ORIGINAL Java and the ported Kotlin
#                side by side in two isolated class loaders and compares tens of
#                thousands of real return values. Needs the original Java tree;
#                point VEPRO_JAVA_SRC at it (or pass it after --diff).
#
#   4. jvm       tests/com/vepro/code/CoreRegressionTests.kt
#                Behavioural suite over loopback HTTP servers: provider routing,
#                per-provider request bodies, bounded retry, Key Router rotation
#                on 429, active cancellation. Needs a Kotlin distribution and an
#                android.jar.
#
# Usage:
#   ./runtests.sh                 # everything available, skipping what it can't run
#   ./runtests.sh --source        # (1) only
#   ./runtests.sh --offline       # (1) + (2)
#   ./runtests.sh --diff ../src   # (1) + (2) + (3)
#   ./runtests.sh --jvm           # (1) + (4)
#   ./runtests.sh --all ../src    # all four, and fail if any of them cannot run
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MODE="auto"
JAVA_SRC="${VEPRO_JAVA_SRC:-}"
STRICT=0

case "${1:-}" in
  -h|--help) sed -n '2,34p' "$0"; exit 0 ;;
  --source)  MODE="source" ;;
  --offline) MODE="offline" ;;
  --jvm)     MODE="jvm" ;;
  --diff)    MODE="diff";  JAVA_SRC="${2:-$JAVA_SRC}" ;;
  --all)     MODE="all";   JAVA_SRC="${2:-$JAVA_SRC}"; STRICT=1 ;;
  "")        ;;
  *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
esac

PASSED=()
SKIPPED=()
pass() { PASSED+=("$1"); }
skip() {
  SKIPPED+=("$1: $2")
  if [[ $STRICT == 1 ]]; then
    echo "FAIL (--all): $1 could not run — $2" >&2
    exit 3
  fi
  printf '\n-- skipped %s: %s\n' "$1" "$2"
}
banner() { printf '\n== %s ==\n' "$1"; }

# ---------------------------------------------------------------- 1. source ---
banner "source contracts"
python3 "$ROOT/tests/source_regressions.py"
pass source

# Two static cross-referencers that need nothing but python3, so they belong in
# the cheapest suite. They catch what a text linter cannot: a symbol that no
# longer resolves, and a LayoutParams class that does not match its parent.
banner "static cross-reference"
python3 "$ROOT/tools/check_ui.py"
python3 "$ROOT/tools/check_layoutparams.py"
pass crossref

if [[ "$MODE" == "source" ]]; then
  printf '\nsuites passed: %s\n' "${PASSED[*]}"
  exit 0
fi

# --------------------------------------------------------- 2/3. offline+diff ---
if [[ "$MODE" != "jvm" ]]; then
  if ! "$ROOT/tools/find-kotlinc.sh" >/dev/null 2>&1; then
    skip offline "no Kotlin compiler (set KOTLIN_HOME, or install Gradle)"
  else
    run_diff=0
    if [[ -d "${JAVA_SRC:-}" ]]; then
      run_diff=1
    elif [[ "$MODE" == "diff" || "$MODE" == "all" ]]; then
      skip diff "original Java tree not found (pass its src dir, or set VEPRO_JAVA_SRC)"
    fi
    if [[ $run_diff == 1 ]]; then
      banner "offline compile + differential test vs the original Java"
      "$ROOT/tools/build-offline.sh" --java "$JAVA_SRC" --fuzz "${VEPRO_FUZZ:-600}"
      pass offline
      pass diff
    else
      # --tests, always. The offline pipeline already compiles everything against the
      # stubs, so running the two test mains afterwards is nearly free — and one of
      # them is AgentLoopTests, which drives the real agent loop end to end. That
      # suite did not exist when a build shipped in which sending a message produced
      # nothing at all and every other suite passed, so it does not get to be
      # optional.
      banner "offline compile + behavioural suites (generated Android stubs, no SDK)"
      "$ROOT/tools/build-offline.sh" --tests
      pass offline
    fi
  fi
fi

if [[ "$MODE" == "offline" || "$MODE" == "diff" ]]; then
  printf '\nsuites passed: %s\n' "${PASSED[*]}"
  exit 0
fi

# ------------------------------------------------------------------- 4. jvm ---
resolve_jvm_prereqs() {
  if [[ -z "${JAVA_HOME:-}" ]]; then
    local javac_bin
    javac_bin="$(command -v javac || true)"
    [[ -n "$javac_bin" ]] || return 1
    JAVA_HOME="$(cd "$(dirname "$(readlink -f "$javac_bin")")/.." && pwd)"
  fi
  if [[ -z "${KOTLIN_HOME:-}" ]]; then
    local kotlinc_bin
    kotlinc_bin="$(command -v kotlinc || true)"
    if [[ -n "$kotlinc_bin" ]]; then
      KOTLIN_HOME="$(cd "$(dirname "$(readlink -f "$kotlinc_bin")")/.." && pwd)"
    else
      for candidate in "$ROOT/sdk/kotlinc" "$HOME/kotlinc" /usr/share/kotlin /opt/kotlinc; do
        [[ -x "$candidate/bin/kotlinc" ]] && { KOTLIN_HOME="$candidate"; break; }
      done
    fi
  fi
  [[ -n "${KOTLIN_HOME:-}" && -x "$KOTLIN_HOME/bin/kotlinc" ]] || return 1

  ANDROID_JAR="${VEPRO_ANDROID_JAR:-}"
  if [[ -z "$ANDROID_JAR" ]]; then
    for candidate in \
      "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" \
      "${ANDROID_HOME:-}/platforms/android-35/android.jar" \
      "$ROOT/sdk/platform-35/android-35/android.jar" \
      "$ROOT/sdk/android.jar"; do
      [[ -e "$candidate" ]] && { ANDROID_JAR="$candidate"; break; }
    done
  fi
  [[ -e "${ANDROID_JAR:-}" ]] || return 1
  return 0
}

if resolve_jvm_prereqs; then
  banner "CoreRegressionTests (loopback HTTP servers)"
  BUILD_DIR="${VEPRO_TEST_DIR:-$ROOT/.build-tests}"
  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR/classes"

  # One module, so `internal` members stay visible to the tests.
  #
  # Written to a file and passed with kotlinc's @argfile rather than expanded
  # through `mapfile < <(...)`: process substitution needs /dev/fd, which a
  # sandboxed or minimal /proc-less shell does not provide, and the whole suite
  # died there with "/dev/fd/63: No such file or directory".
  # R.java too. Without it the app sources do not compile at all here (every
  # R.mipmap / R.style / R.anim reference is unresolved), so this suite could
  # only ever have run in an environment where it silently skipped itself.
  # A REAL org.json. The one inside android.jar is a signature-only stub whose
  # every method throws RuntimeException("Stub!"), so the suite died on the first
  # JSONArray it constructed. tools/json-src holds the same small, correct
  # implementation the offline pipeline already compiles for this reason; it must
  # precede android.jar on the classpath so it wins the lookup.
  mkdir -p "$BUILD_DIR/json-classes"
  find "$ROOT/tools/json-src" -name '*.java' | sort > "$BUILD_DIR/json-files.txt"
  "$JAVA_HOME/bin/javac" -nowarn -d "$BUILD_DIR/json-classes" \
    @"$BUILD_DIR/json-files.txt"

  python3 "$ROOT/tools/gen_r.py" "$BUILD_DIR/gen"
  find "$ROOT/src" "$ROOT/tests" -name '*.kt' | sort > "$BUILD_DIR/sources.txt"
  echo "$BUILD_DIR/gen/com/vepro/code/R.java" >> "$BUILD_DIR/sources.txt"
  "$KOTLIN_HOME/bin/kotlinc" \
    -classpath "$BUILD_DIR/json-classes:$ANDROID_JAR" \
    -jvm-target 17 \
    -nowarn \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

  "$JAVA_HOME/bin/java" \
    -cp "$BUILD_DIR/classes:$BUILD_DIR/json-classes:$KOTLIN_HOME/lib/kotlin-stdlib.jar:$ANDROID_JAR" \
    com.vepro.code.CoreRegressionTests
  pass jvm
elif "$ROOT/tools/find-kotlinc.sh" >/dev/null 2>&1; then
  # No real android.jar — run the same suite against the generated stubs, which
  # is exactly what the offline pipeline was built for.
  banner "CoreRegressionTests against generated stubs (no android.jar found)"
  "$ROOT/tools/build-offline.sh" --tests
  pass jvm
else
  skip jvm "needs a Kotlin compiler (KOTLIN_HOME) — an android.jar is optional"
fi

printf '\nsuites passed: %s\n' "${PASSED[*]}"
if [[ ${#SKIPPED[@]} -gt 0 ]]; then
  printf 'skipped:\n'
  printf '  %s\n' "${SKIPPED[@]}"
fi
