#!/usr/bin/env bash
# Compiles and verifies the whole app with NO Android SDK and NO network.
#
# How it works:
#   1. tools/gen_stubs.py generates an android.jar-style stub source tree that
#      covers exactly the platform surface this app touches. Every body throws,
#      like the real android.jar — it is a compile-time contract. A handful of
#      stubs are deliberately *behavioural* (Base64, TextUtils, Path, RectF,
#      MimeTypeMap) so pure app logic can actually be executed.
#   2. tools/json-src is a small, correct org.json — on device this comes from
#      the platform, so it has to be supplied here.
#   3. tools/gen_r.py regenerates R.java from res/values/public.xml.
#   4. The Kotlin is compiled against (1)+(2)+(3).
#   5. If --java <dir> points at the ORIGINAL Java sources, they are compiled the
#      same way and tools/DiffHarness.java runs both builds side by side,
#      comparing tens of thousands of real return values.
#
# Step 5 is what validates the stubs themselves: the Java is known to compile
# against the real SDK, so if it also compiles here, the stubs are faithful.
#
#   tools/build-offline.sh                       # compile + type-check the port
#   tools/build-offline.sh --tests               # + run CoreRegressionTests
#   tools/build-offline.sh --java ../src         # + full differential test
#   tools/build-offline.sh --java ../src --tests --fuzz 2000
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${VEPRO_OFFLINE_DIR:-$ROOT/.build-offline}"
JAVA_SRC=""
FUZZ=600
RUN_TESTS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java) JAVA_SRC="$2"; shift 2 ;;
    --fuzz) FUZZ="$2"; shift 2 ;;
    --tests) RUN_TESTS=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

command -v javac >/dev/null || { echo "javac not found (need a JDK 17+)" >&2; exit 2; }
eval "$("$ROOT/tools/find-kotlinc.sh")"

rm -rf "$OUT"
mkdir -p "$OUT"/{stubs-classes,json-classes,kt-classes,diff-classes}

step() { printf '\n== %s ==\n' "$1"; }

step "android stubs"
python3 "$ROOT/tools/gen_stubs.py" "$OUT/stubs-src" >/dev/null
find "$OUT/stubs-src" -name '*.java' > "$OUT/stub-files.txt"
javac -nowarn -d "$OUT/stubs-classes" @"$OUT/stub-files.txt"
echo "stub classes: $(find "$OUT/stubs-classes" -name '*.class' | wc -l)"
# Package them so the stubs can stand in for android.jar on a classpath.
(cd "$OUT/stubs-classes" && jar cf "$OUT/android-stubs.jar" .)

step "org.json"
find "$ROOT/tools/json-src" -name '*.java' > "$OUT/json-files.txt"
javac -nowarn -d "$OUT/json-classes" @"$OUT/json-files.txt"

step "R.java"
python3 "$ROOT/tools/gen_r.py" "$OUT/gen"
R_JAVA="$OUT/gen/com/vepro/code/R.java"

SUPPORT="$OUT/stubs-classes:$OUT/json-classes"

step "compile Kotlin app"
find "$ROOT/src" -name '*.kt' | sort > "$OUT/kt-files.txt"
"$KOTLINC" -nowarn -jvm-target 17 -cp "$SUPPORT" -d "$OUT/kt-classes" \
    @"$OUT/kt-files.txt" "$R_JAVA" 2>&1 | tee "$OUT/kotlinc.log" | grep -E ': error:' && {
  echo "KOTLIN COMPILE FAILED" >&2
  exit 1
} || true
if grep -qE ': error:' "$OUT/kotlinc.log"; then
  echo "KOTLIN COMPILE FAILED" >&2
  exit 1
fi
# kotlinc only *resolves* R.java, it never emits it — compile it for real.
javac -nowarn -proc:none -cp "$SUPPORT" -d "$OUT/kt-classes" "$R_JAVA"
echo "kotlin errors: 0   classes: $(find "$OUT/kt-classes" -name '*.class' | wc -l)"

STDLIB="$(compgen -G "$KOTLIN_LIB/kotlin-stdlib*.jar" | sort | tail -1)"

if [[ $RUN_TESTS == 1 ]]; then
  step "CoreRegressionTests against the generated stubs"
  # src + tests compile as ONE module so `internal` members stay visible.
  mkdir -p "$OUT/test-classes"
  find "$ROOT/src" "$ROOT/tests" -name '*.kt' | sort > "$OUT/test-files.txt"
  "$KOTLINC" -nowarn -jvm-target 17 -cp "$SUPPORT" -d "$OUT/test-classes" \
      @"$OUT/test-files.txt" "$R_JAVA" 2>&1 | tee "$OUT/kotlinc-tests.log" || true
  if grep -qE ': error:' "$OUT/kotlinc-tests.log"; then
    echo "TEST COMPILE FAILED" >&2
    exit 1
  fi
  javac -nowarn -proc:none -cp "$SUPPORT" -d "$OUT/test-classes" "$R_JAVA"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" com.vepro.code.CoreRegressionTests

  # The agent loop itself, end to end, over real sockets. Separate runner because it
  # needs the behavioural stubs above — a real android.jar throws on every call.
  step "AgentLoopTests against the generated stubs"
  env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 \
      -cp "$OUT/test-classes:$SUPPORT:$STDLIB" com.vepro.code.AgentLoopTests
fi

if [[ -z "$JAVA_SRC" ]]; then
  printf '\nOK — the Kotlin port compiles clean with no SDK.\n'
  printf 'Pass --java <original-java-src-dir> to also run the differential test.\n'
  exit 0
fi

[[ -d "$JAVA_SRC" ]] || { echo "--java: not a directory: $JAVA_SRC" >&2; exit 2; }

step "compile original Java (validates the stubs)"
mkdir -p "$OUT/java-classes"
find "$JAVA_SRC" -name '*.java' | sort > "$OUT/java-files.txt"
javac -nowarn -proc:none -cp "$SUPPORT" -d "$OUT/java-classes" \
    @"$OUT/java-files.txt" "$R_JAVA"
echo "java errors: 0   classes: $(find "$OUT/java-classes" -name '*.class' | wc -l)"

step "differential test: original Java vs ported Kotlin"
javac -nowarn -d "$OUT/diff-classes" "$ROOT/tools/DiffHarness.java"
env JAVA_TOOL_OPTIONS= java -Dstdout.encoding=UTF-8 -Ddiff.fuzz="$FUZZ" -cp "$OUT/diff-classes" DiffHarness \
    "$OUT/java-classes" "$OUT/kt-classes" \
    "$OUT/stubs-classes" "$OUT/json-classes" "$STDLIB"
