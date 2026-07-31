#!/usr/bin/env bash
# Reproducible release build: aapt2 -> kotlinc -> javac(R) -> d8 -> zipalign -> apksigner
#
# Kotlin edition. The only change versus the Java build is the compile step:
# kotlinc compiles src/*.kt (with the aapt2-generated R.java passed in for
# symbol resolution), javac then compiles R.java itself, and d8 additionally
# dexes kotlin-stdlib.jar.
#
# Works out-of-the-box on-device (Termux/proot with the Debian android-sdk
# packages) as well as on a desktop with a full Android SDK:
#
#   JAVA_HOME            JDK 17+ home        (default: auto-detected from javac)
#   KOTLIN_HOME          kotlinc dist root   (default: auto-detected from kotlinc)
#   ANDROID_SDK_ROOT     SDK root            (default: auto-detected)
#   VEPRO_ANDROID_JAR    path to android.jar (default: sdk/platforms or ./sdk/)
#   VEPRO_R8_JAR         path to r8.jar      (default: $BT/d8 or ./sdk/r8.jar)
#   VEPRO_KEYSTORE_PATH  release .jks        (required)
#   VEPRO_KEYSTORE_PASSWORD / VEPRO_KEY_PASSWORD / VEPRO_KEY_ALIAS=vepro
#
#   ./mkapk.sh [versionName] [versionCode] [out.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVAC_BIN="$(command -v javac || true)"
  [[ -n "$JAVAC_BIN" ]] || { echo "javac not found; set JAVA_HOME" >&2; exit 2; }
  JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVAC_BIN")")/.." && pwd)"
fi

# ---- Kotlin compiler -------------------------------------------------------
# Accept either a kotlinc on PATH, an explicit KOTLIN_HOME, or a vendored copy
# under ./sdk/kotlinc (handy for offline / on-device builds).
if [[ -z "${KOTLIN_HOME:-}" ]]; then
  KOTLINC_BIN="$(command -v kotlinc || true)"
  if [[ -n "$KOTLINC_BIN" ]]; then
    KOTLIN_HOME="$(cd "$(dirname "$(readlink -f "$KOTLINC_BIN")")/.." && pwd)"
  else
    for candidate in "$ROOT/sdk/kotlinc" "$HOME/kotlinc" /usr/share/kotlin /opt/kotlinc; do
      if [[ -x "$candidate/bin/kotlinc" ]]; then
        KOTLIN_HOME="$candidate"
        break
      fi
    done
  fi
fi
[[ -n "${KOTLIN_HOME:-}" ]] || {
  echo "kotlinc not found. Install Kotlin (https://kotlinlang.org/docs/command-line.html)" >&2
  echo "or set KOTLIN_HOME, or unpack a kotlin-compiler zip into ./sdk/kotlinc" >&2
  exit 2
}
KOTLINC="$KOTLIN_HOME/bin/kotlinc"
[[ -x "$KOTLINC" ]] || { echo "not executable: $KOTLINC" >&2; exit 2; }

KOTLIN_STDLIB="${VEPRO_KOTLIN_STDLIB:-$KOTLIN_HOME/lib/kotlin-stdlib.jar}"
[[ -e "$KOTLIN_STDLIB" ]] || { echo "kotlin-stdlib.jar not found at $KOTLIN_STDLIB" >&2; exit 2; }

BT=""
for candidate in \
  "${ANDROID_SDK_ROOT:-}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.1}" \
  /usr/lib/android-sdk/build-tools/debian \
  "$HOME/android-sdk/build-tools/35.0.1"; do
  if [[ -x "$candidate/aapt2" ]]; then
    BT="$candidate"
    break
  fi
done

ANDROID_JAR="${VEPRO_ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" \
    "$ROOT/sdk/platform-35/android-35/android.jar" \
    "$ROOT/sdk/android.jar"; do
    if [[ -e "$candidate" ]]; then
      ANDROID_JAR="$candidate"
      break
    fi
  done
fi

# Old aapt2 builds (e.g. Debian's 2.19) cannot read the android-35 resource
# table; linking may use an older platform jar while kotlinc/d8 use the new one.
AAPT2_JAR="${VEPRO_AAPT2_JAR:-}"
if [[ -z "$AAPT2_JAR" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/platforms/android-31/android.jar" \
    "$ROOT/sdk/platform-31/android-12/android.jar" \
    "$ANDROID_JAR"; do
    if [[ -e "$candidate" ]]; then
      AAPT2_JAR="$candidate"
      break
    fi
  done
fi

BUILD_DIR="${VEPRO_BUILD_DIR:-$ROOT/.build-release}"
VNAME="${1:-1}"
VCODE="${2:-1}"
OUT_ARG="${3:-Vega-v${VNAME}.apk}"

if [[ "$OUT_ARG" = /* ]]; then
  OUT="$OUT_ARG"
else
  OUT="$ROOT/$OUT_ARG"
fi

: "${VEPRO_KEYSTORE_PATH:?Set VEPRO_KEYSTORE_PATH to the release .jks file}"
: "${VEPRO_KEYSTORE_PASSWORD:?Set VEPRO_KEYSTORE_PASSWORD securely in the environment}"
VEPRO_KEY_PASSWORD="${VEPRO_KEY_PASSWORD:-$VEPRO_KEYSTORE_PASSWORD}"
VEPRO_KEY_ALIAS="${VEPRO_KEY_ALIAS:-vepro}"

D8=()
if [[ -n "$BT" && -x "$BT/d8" ]]; then
  D8=("$BT/d8")
else
  R8_JAR="${VEPRO_R8_JAR:-$ROOT/sdk/r8.jar}"
  if [[ -e "$R8_JAR" ]]; then
    D8=("$JAVA_HOME/bin/java" "-cp" "$R8_JAR" "com.android.tools.r8.D8")
  fi
fi

for required in "$JAVA_HOME/bin/javac" "$ANDROID_JAR" "$VEPRO_KEYSTORE_PATH"; do
  if [[ ! -e "$required" ]]; then
    echo "Missing build dependency: $required" >&2
    exit 2
  fi
done
[[ -n "$BT" ]] || { echo "aapt2 not found; set ANDROID_SDK_ROOT" >&2; exit 2; }
[[ ${#D8[@]} -gt 0 ]] || { echo "d8 not found; set VEPRO_R8_JAR or install build-tools" >&2; exit 2; }

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/out"

printf '== compile resources ==\n'
"$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD_DIR/out/res.zip"

printf '== link resources ==\n'
"$BT/aapt2" link -o "$BUILD_DIR/out/base.apk" \
  -I "$AAPT2_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -A "$ROOT/assets" \
  -0 ttf -0 png \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 23 --target-sdk-version 35 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "$BUILD_DIR/out/res.zip"

printf '== compile Kotlin ==\n'
# kotlinc is given BOTH the Kotlin sources and the generated R.java. It does not
# emit bytecode for .java inputs — they exist only so `R.mipmap.…` resolves.
python3 - "$ROOT/src" "$BUILD_DIR/gen" "$BUILD_DIR/out/sources.txt" <<'PY'
from pathlib import Path
import sys
kotlin_root = Path(sys.argv[1])
gen_root = Path(sys.argv[2])
out = Path(sys.argv[3])
files = sorted(str(p) for p in kotlin_root.rglob('*.kt'))
files += sorted(str(p) for p in gen_root.rglob('*.java'))
out.write_text('\n'.join(files) + '\n', encoding='utf-8')
PY
# shellcheck disable=SC2046
"$KOTLINC" \
  -classpath "$ANDROID_JAR" \
  -jvm-target 17 \
  -no-reflect \
  -nowarn \
  -d "$BUILD_DIR/classes" \
  $(cat "$BUILD_DIR/out/sources.txt")

printf '== compile generated R.java ==\n'
python3 - "$BUILD_DIR/gen" "$BUILD_DIR/out/java-sources.txt" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
Path(sys.argv[2]).write_text(
    '\n'.join(sorted(str(p) for p in root.rglob('*.java'))) + '\n', encoding='utf-8')
PY
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 \
  -cp "$ANDROID_JAR:$BUILD_DIR/classes" -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/out/java-sources.txt"

printf '== stage kotlin-stdlib ==\n'
# d8 in some build-tools releases chokes on module-info.class, so dex a copy of
# the stdlib with the module descriptor and signatures removed.
cp "$KOTLIN_STDLIB" "$BUILD_DIR/out/kotlin-stdlib.jar"
python3 - "$BUILD_DIR/out/kotlin-stdlib.jar" "$BUILD_DIR/out/kotlin-stdlib-dex.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename
        if name == 'module-info.class' or name.endswith('/module-info.class'):
            continue
        if name.startswith('META-INF/') and name.endswith(('.SF', '.RSA', '.DSA')):
            continue
        zout.writestr(item, zin.read(name))
print('staged stdlib without module-info')
PY

printf '== create release dex ==\n'
python3 - "$BUILD_DIR/classes" "$BUILD_DIR/out/classes.txt" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
Path(sys.argv[2]).write_text('\n'.join(sorted(str(path) for path in root.rglob('*.class'))) + '\n')
PY
# --min-api MUST match aapt2's --min-sdk-version, apksigner's --min-sdk-version,
# AndroidManifest's <uses-sdk> and build.gradle.kts's minSdk. They disagreed
# (24/24/23/24/24) and a signer that promised Android 6 while d8 desugared for
# Android 7 is precisely how API-24 calls reached a device that has no such method.
"${D8[@]}" --release --min-api 23 --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" \
  "$BUILD_DIR/out/kotlin-stdlib-dex.jar" \
  @"$BUILD_DIR/out/classes.txt"

printf '== package and align ==\n'
cp "$BUILD_DIR/out/base.apk" "$BUILD_DIR/out/unsigned.apk"
python3 - "$BUILD_DIR/out/unsigned.apk" "$BUILD_DIR/dex" <<'PY'
import sys, zipfile
from pathlib import Path
apk, dex_dir = sys.argv[1], Path(sys.argv[2])
# d8 emits classes.dex plus classes2.dex… when the stdlib pushes us over 64k
dex_files = sorted(dex_dir.glob('classes*.dex'),
                   key=lambda p: (len(p.name), p.name))
if not dex_files:
    raise SystemExit('no dex output produced')
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as archive:
    for dex in dex_files:
        archive.write(dex, dex.name)
print('packaged: ' + ', '.join(p.name for p in dex_files))
PY
"$BT/zipalign" -p -f 4 "$BUILD_DIR/out/unsigned.apk" "$BUILD_DIR/out/aligned.apk"

printf '== sign release ==\n'
SIGN_ARGS=(
  --ks "$VEPRO_KEYSTORE_PATH"
  --ks-key-alias "$VEPRO_KEY_ALIAS"
  --ks-pass "env:VEPRO_KEYSTORE_PASSWORD"
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true
  --min-sdk-version 23
  --out "$OUT" "$BUILD_DIR/out/aligned.apk"
)
# A separate key password is only forwarded when it actually differs (some
# apksigner builds mishandle an env:-indirected --key-pass).
if [[ "$VEPRO_KEY_PASSWORD" != "$VEPRO_KEYSTORE_PASSWORD" ]]; then
  SIGN_ARGS=(
    --ks "$VEPRO_KEYSTORE_PATH"
    --ks-key-alias "$VEPRO_KEY_ALIAS"
    --ks-pass "env:VEPRO_KEYSTORE_PASSWORD"
    --key-pass "env:VEPRO_KEY_PASSWORD"
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true
    --min-sdk-version 23
    --out "$OUT" "$BUILD_DIR/out/aligned.apk"
  )
fi
"$BT/apksigner" sign "${SIGN_ARGS[@]}"

printf '== verify ==\n'
"$BT/apksigner" verify --min-sdk-version 23 --verbose --print-certs "$OUT"
sha256sum "$OUT"
printf 'Built: %s\n' "$OUT"
