# tools/

The verification stack. Everything here is wired into `runtests.sh` or
`.github/workflows/android.yml` — nothing is decoration.

The unifying idea: **this project can be compiled, executed and differentially
tested with no Android SDK and no network access.** That is not a party trick; it
is what made it possible to prove the Kotlin port behaves identically to the Java
original rather than merely asserting it.

| file | what it does | used by |
|---|---|---|
| `gen_stubs.py` | Generates an `android.jar`-style stub source tree — 145 classes covering exactly the platform surface this app touches. Bodies throw, like the real `android.jar`. | `build-offline.sh` |
| `json-src/` | A small, correct `org.json` (`JSONObject`, `JSONArray`, `JSONTokener`, `JSONException`). On device this comes from the platform, so an off-device run has to supply it. | `build-offline.sh` |
| `gen_r.py` | Regenerates `R.java` from `res/values/public.xml`, which pins every resource id explicitly. Same class aapt2 would emit. | `build-offline.sh` |
| `find-kotlinc.sh` | Locates a Kotlin compiler: `$KOTLIN_HOME`, then `PATH`, then — the useful part — the `kotlin-compiler-embeddable` jar inside any Gradle installation, wrapping it in a working `kotlinc` launcher. | `build-offline.sh`, `runtests.sh` |
| `build-offline.sh` | The driver: stubs → org.json → R.java → compile the port → optionally run `CoreRegressionTests` → optionally compile the original Java and run the differential test. | `runtests.sh`, CI |
| `DiffHarness.java` | The differential test. See below. | `build-offline.sh` |
| `verify.py` | Static parity verifier: string-literal parity against the Java, brace/paren balance, leftover Java-isms, un-narrowed ARGB literals, `!!` usage. Cheap first line of defence, kept because it catches things before anything is compiled. | CI |
| `mkkeystore.sh` | Generates a release signing key with `keytool` alone. The shipped `keystore/vega-release-v1.jks` came from exactly this script. | `keystore/README-KEYSTORE.md` |

## Why generated stubs, and why they can be trusted

An `android.jar` is a compile-time contract: every method body throws, and the
real implementations arrive at runtime from the device. `gen_stubs.py` builds the
same thing from a compact spec.

The obvious objection is "your stubs could be wrong in a way that hides a bug."
That is why `build-offline.sh --java <original-src>` compiles the **original
Java** against the same stubs. The Java is known to compile against the real SDK,
so if it also compiles here with zero errors, the stubs faithfully model every
signature, return type, constant and inheritance relationship the app depends on.
The stubs are validated by the very code they are used to check.

A handful of stubs are deliberately **behavioural** rather than throwing, so real
app logic can be executed and compared:

- `android.util.Base64` delegates to `java.util.Base64`
- `android.text.TextUtils.isEmpty` is implemented
- `android.webkit.MimeTypeMap` is a real singleton whose lookup returns `null`,
  so both builds fall through to `Util.TEXT_EXTENSIONS` identically
- `android.graphics.Path` **records every operation** and exposes
  `recordedOps()` — this is what lets the SVG parser be compared op-for-op
- `android.graphics.RectF` has real fields plus `describe()`

## The differential test

`DiffHarness.java` loads the two builds into two `URLClassLoader`s whose parent is
the *platform* loader, so `com.vepro.code` resolves independently on each side and
the two versions of a class never collide. It then invokes matching members via
reflection and compares results.

What it handles that a naive version would get wrong:

- **Java `static` vs Kotlin `object`** — finds `INSTANCE`, or the `Companion`, or
  falls back to a genuine static.
- **`internal` name mangling** — a Kotlin `internal fun foo` is emitted as
  `foo$main`; the resolver tries both.
- **Private members** — `getDeclaredMethod` + `setAccessible`, so private helpers
  (`Tools.applyOne`, `Web.parseDuck`, `AgentEngine.tryParse`) are covered too.
- **Type changes** — where the port replaced a `String[]`/`int[]` with a small
  data class (`Think.Parts`, `Web.Hit`, `MarkdownRenderer.Row`), results are
  flattened *positionally* so the array and the class render identically. Every
  other object renders as a name-sorted field map, so a field-declaration-order
  difference is never mistaken for a behavioural one.
- **Per-loader argument types** — `summarizeArgs(JSONObject)` needs a
  `JSONObject` built by the *same* loader as the callee; the harness constructs
  one per side.
- **Reference hangs** — every call is time-boxed. When the original never returns
  and the port does, that is reported as a **found defect**, not as a mismatch.
  Inputs known to hang the reference are never fed to it (a wedged thread cannot
  be killed on JDK 21), but the port is still asserted to return.

Coverage comes from three sources:

1. A hand-written corpus of ~110 adversarial strings: RTL and bidi marks,
   zero-width characters, BOMs, unbalanced markdown, CRLF mixtures, truncated
   JSON, braces inside string literals, URLs with stray spaces, Persian text,
   astral-plane emoji, lone surrogates, secret-looking tokens.
2. Exhaustive parameter sweeps where the space is small enough to enumerate
   (every HTTP status × body × provider; every base × model × protocol × stream).
3. A **deterministic** fuzz phase — a fixed 64-bit LCG (never `Math.random`, so
   any failure can be replayed) drawing from an alphabet of the characters that
   actually break these parsers.

```bash
tools/build-offline.sh --java ../VeproCode-v1-src/src --fuzz 2000
```

Raising `--fuzz` is the cheapest way to look for more bugs. It is how bug #2 in
`BUGS_FIXED.md` was found.
