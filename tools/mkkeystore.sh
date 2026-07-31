#!/usr/bin/env bash
# Generates a fresh release signing key. Uses only `keytool` from the JDK.
#
#   tools/mkkeystore.sh                                  # defaults below
#   tools/mkkeystore.sh out.jks myalias 'my-password'
#
# This project ships NO keystore — the release key is private. This script is
# how that private key was made and how you make your own. Keep the output
# OUTSIDE the repo (every *.jks is git-ignored) and register it as CI secrets;
# see keystore/README-KEYSTORE.md.
#
# If you do not pass a password, a strong random one is generated and printed
# ONCE below — copy it into your password manager immediately.
#
# IMPORTANT: an Android app's identity IS its signing key. Once a version is
# installed, only an APK signed with the same key can update it. Back this file
# up somewhere you will still have in five years; there is no recovery.
set -euo pipefail

OUT="${1:-vepro-release-private.jks}"
ALIAS="${2:-vepro}"
PASS="${3:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)}"
DNAME="${VEPRO_DNAME:-CN=Vega, OU=Mobile, O=Vega, L=Tehran, C=IR}"
DAYS="${VEPRO_VALIDITY_DAYS:-10950}"      # 30 years

command -v keytool >/dev/null || { echo "keytool not found (need a JDK)" >&2; exit 2; }

if [[ -e "$OUT" ]]; then
  echo "refusing to overwrite existing keystore: $OUT" >&2
  echo "(delete it first, or pass a different output path)" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

keytool -genkeypair \
  -keystore "$OUT" \
  -storetype PKCS12 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity "$DAYS" \
  -dname "$DNAME"

printf '\n== created %s ==\n' "$OUT"
keytool -list -v -keystore "$OUT" -storepass "$PASS" -alias "$ALIAS" \
  | grep -E 'Alias|Creation|Owner|Valid|SHA256:|Signature algorithm|Subject Public Key'

cat <<EOF

Next:
  export VEPRO_KEYSTORE_PATH="\$PWD/$OUT"
  export VEPRO_KEYSTORE_PASSWORD='$PASS'
  export VEPRO_KEY_ALIAS='$ALIAS'
  ./mkapk.sh 1 14 Vega-v1.apk

To use it from CI, store the keystore base64-encoded as a repository secret:
  base64 -w0 "$OUT"
EOF
