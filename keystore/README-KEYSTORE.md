# Release signing

**This repository contains no signing key, and must never contain one.** A private
key in a public tree lets anybody publish an update that your users' phones accept
as genuine, so `.gitignore` refuses every `*.jks`, `*.keystore` and `*.p12` with no
exceptions.

The maintainer's release key is delivered separately, alongside its credentials.
Keep it in a password manager or an encrypted backup — anywhere that is not a git
remote.

## Building a signed release

Point the build at a key held outside the repository:

```bash
export VEGA_KEYSTORE_PATH="$HOME/private/vega-release-v1.jks"
export VEGA_KEYSTORE_PASSWORD='…'
export VEGA_KEY_ALIAS=vega
./mkapk.sh 1.1.0 17 Vega-v1.1.0.apk       # versionCode rises every release (17 = v1.1.0)
```

Gradle reads the same three variables, or `keystore/keystore.properties` — which is
also git-ignored. Copy `keystore.properties.example` to `keystore.properties` and
fill it in if you prefer a file to environment variables.

Without a key, `./mkapk.sh` stops and says so, and `./gradlew assembleRelease`
produces an unsigned APK. Neither will silently ship something unsigned.

## Making your own key

`tools/mkkeystore.sh` generates one with `keytool` alone — no Android SDK needed:

```bash
tools/mkkeystore.sh my-release.jks
```

Store it outside the tree.

## Why the key matters more than it looks

Android refuses an update signed by a different key than the installed app. Every
build signed with the same key installs cleanly over the last one; a build signed
with a *different* key cannot be installed as an update at all — every user has to
uninstall first, which clears their API key, settings and saved chats.

So the key is not a build detail. It is the only thing standing between an update
and a forced reinstall for everyone who has the app. Back it up before anything
else, and keep `versionCode` rising.

## CI

The workflow signs with a key supplied through repository secrets:

```bash
base64 -w0 vega-release-v1.jks   # -> VEGA_KEYSTORE_BASE64
```

plus `VEGA_KEYSTORE_PASSWORD` and `VEGA_KEY_ALIAS`. Without those secrets the
build still runs, but with a throwaway key — that artifact is a test build and must
not be distributed as a release.
