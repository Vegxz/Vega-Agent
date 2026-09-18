# Vega Agent — v1.1.0

A hand-rolled, **zero-dependency** Android AI agent app (`github.vega.agent`).
The whole UI is built in code — no Jetpack, no third-party libraries; only the
Android framework, the platform's `org.json`, and the Kotlin standard library.

It is bilingual (English / Persian, mirrored) and runs an agent loop with real
tool use: it can read and edit files, fetch web pages, search the web, and
delegate work to sub-agents — all behind an approval system you control.

## Key features

- **Agent chat** with streaming responses, run modes (Auto / Accept / Plan),
  and tool-call approval sheets
- **Multi-provider LLM support** — OpenAI, Anthropic, Gemini, OpenRouter,
  Groq, Together, plus any compatible custom endpoint; per-provider reasoning
  ("thinking") levels
- **Key Router** — up to 50 API keys with automatic rotation on rate limits
- **Markdown rendering** including **pipe tables (new in v1.1.0)**,
  fenced code blocks with code cards, and safe tap-to-copy
- **Agent skills** — installable from a GitHub link (Settings → *Tools and
  access* → *Add skill*): the app reads the repo, your own configured model
  distills it into skill definition(s) stored under `<chosen folder>/Vega
  Skills`, and the agent later picks only the skills that genuinely fit the
  current task
- **File browser**, chat history drawer, instant settings, connection testing
- **Background runs** via a foreground service with a crash-loop guard
- Fully mirrored **Persian** interface alongside English

## Project layout

Flat tree — both build paths compile exactly the same files:

| path | what it is |
|---|---|
| `src/github/vega/agent/` | all Kotlin sources |
| `res/`, `assets/` | resources and fonts |
| `AndroidManifest.xml` | manifest (no `versionCode`/`versionName` — the build sets them) |
| `mkapk.sh` | **canonical build**: aapt2 → kotlinc → d8 → zipalign → apksigner |
| `runtests.sh` | the four-suite verification stack |
| `tests/source_regressions.py` | source-level regression contracts (incl. version parity) |
| `tools/` | offline compiler, stub generator, differential test harness |
| `build.gradle.kts` | optional Android Studio / Gradle build over the same flat layout |
| `.github/workflows/android.yml` | CI: verifies, builds and uploads a signed APK |

## Building on GitHub Actions

Push to `main`/`master` (or open a PR, or press *Run workflow*) and the
**build** workflow appears under *Actions*. When it finishes:

1. Open the run → **Artifacts**
2. Download `Vega-v1.1.0-signed-apk` — it contains `Vega-v1.1.0.apk`

Signing: add these repository secrets and the APK is signed with the real
release key —

`VEGA_KEYSTORE_BASE64` (`base64 -w0` of the release `.jks`),
`VEGA_KEYSTORE_PASSWORD`, `VEGA_KEY_ALIAS` (optional).

Without those secrets the workflow still builds, but signs with a throwaway CI
key — that artifact is a test build and must not be distributed as a release.
See `keystore/README-KEYSTORE.md`.

## Building locally with `./mkapk.sh`

Needs: JDK 17, a Kotlin compiler (`KOTLIN_HOME` or `kotlinc` on `PATH`),
Android build-tools **35.0.1** and platform **android-35**
(`ANDROID_SDK_ROOT`), and your release keystore.

```bash
./mkapk.sh [versionName] [versionCode] [out.apk]
```

```bash
export VEGA_KEYSTORE_PATH="$HOME/private/vega-release-v1.jks"
export VEGA_KEYSTORE_PASSWORD='…'
export VEGA_KEY_ALIAS=vega
./mkapk.sh 1.1.0 17 Vega-v1.1.0.apk
```

| variable | purpose | default |
|---|---|---|
| `JAVA_HOME` | JDK 17+ | auto-detected from `javac` |
| `KOTLIN_HOME` | kotlinc distribution root | auto-detected (`kotlinc`, `$HOME/kotlinc`, `./sdk/kotlinc`, …) |
| `ANDROID_SDK_ROOT` | Android SDK root | auto-detected |
| `ANDROID_BUILD_TOOLS_VERSION` | build-tools version | `35.0.1` |
| `VEGA_ANDROID_JAR` | `android.jar` override | SDK platform `android-35` |
| `VEGA_R8_JAR` | r8.jar if no `d8` binary | `$BT/d8` first |
| `VEGA_KEYSTORE_PATH` | release `.jks` (**required**) | — |
| `VEGA_KEYSTORE_PASSWORD` | keystore password (**required**) | — |
| `VEGA_KEY_PASSWORD` | key password, if different | same as keystore password |
| `VEGA_KEY_ALIAS` | key alias | `vega` |
| `VEGA_BUILD_DIR` | scratch dir | `.build-release/` (git-ignored) |

## Building with Android Studio / Gradle

Open the folder as a project — `build.gradle.kts` maps Gradle onto the same
flat layout (`src/`, `res/`, `assets/`, root manifest), no restructuring needed.

```bash
./gradlew assembleRelease \
  -PVEGA_KEYSTORE_PATH="$HOME/private/vega-release-v1.jks" \
  -PVEGA_KEYSTORE_PASSWORD='…' -PVEGA_KEY_ALIAS=vega
```

Credentials resolve from `-P` properties, then the environment, then
`keystore/keystore.properties` (git-ignored — copy
`keystore/keystore.properties.example`). Without a key the release build is
unsigned rather than silently shipping something else.

## Signing / keystore setup

**The repository contains no signing key and must never contain one.**
`.gitignore` refuses every `*.jks`, `*.keystore`, `*.p12` with no exceptions,
and a regression test enforces it.

Keep the release key outside the tree and point the build at it — Android
refuses an update signed by a different key, so the key (plus a rising
`versionCode`) is what lets releases install over each other. Full procedure:
**`keystore/README-KEYSTORE.md`**. Generate a new key with
`tools/mkkeystore.sh`.

## Running the tests

```bash
./runtests.sh              # everything available, skipping what it can't run
./runtests.sh --source     # python3 only: source contracts + static checks
./runtests.sh --offline    # + compile the whole app with generated stubs
./runtests.sh --jvm        # + behavioural suite (loopback HTTP servers)
./runtests.sh --all ../src # all four + differential test vs the original Java
```

The pieces, individually:

- `python3 tests/source_regressions.py` — ~100 source contracts: string-table
  shape, no `!!`, ARGB literals narrowed, monochrome palette, icon names
  resolve, **version parity across every surface**, and that no signing material
  or credential value is committed
- `python3 tools/check_ui.py`, `python3 tools/check_layoutparams.py` —
  cross-referencing every `Icons`/`Fa`/`Theme`/`Ui`/`R` symbol and LayoutParams
  class
- `python3 tools/verify.py` — static parity verifier (also a CI step)
- `tools/build-offline.sh` — compiles the entire app against generated Android
  stubs with **no SDK and no network**; `--tests` runs the behavioural suites,
  `--java <dir>` runs the differential test against the original Java

## Versioning policy

Two numbers, two jobs:

- `versionName` — the **displayed** version the user sees (`"1.1.0"`). It must
  read identically on every surface: `build.gradle.kts`, the CI artifact name,
  and the app's About row (`Fa.SET_VERSION`). A regression test fails the build
  if they disagree.
- `versionCode` — the **invisible** ordering number Android uses to accept an
  update. It **never goes down**: hand-built releases bump it in
  `build.gradle.kts` (currently 17); CI uses `100 + GITHUB_RUN_NUMBER`, offset
  past the last hand-built release so CI can never emit a lower code.

## Compatibility

`minSdk 23` (Android 6.0), `targetSdk 35`. The manifest, aapt2, d8 and
apksigner all say 23 — that agreement is load-bearing; see the comments in
`mkapk.sh` and `AndroidManifest.xml`.

The app ships **zero native libraries**, so one APK installs on every ABI
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) — there are no `abiFilters` or
APK splits to drift out of sync. The manifest declares no
`<uses-feature android:required="true">`, so no hardware requirement filters
devices out either.

## License

Vega Agent is free software: you can redistribute it and/or modify it under
the terms of the **GNU Affero General Public License v3.0** (or any later
version) — see the `LICENSE` file. Bundled fonts (Vazirmatn, JetBrains Mono,
Latin Modern Math) are under the SIL Open Font License 1.1; see
`assets/licenses/`.
