#!/usr/bin/env python3
"""Source-level regression checks, ported to the Kotlin tree.

Same contracts as the Java version — only the markers changed to match Kotlin
syntax (property assignment instead of setters, `object` instead of static
classes, `Regex` instead of `Pattern`).
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/com/vepro/code"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def test_the_string_table_is_one_line_per_string():
    """
    Fa.kt holds each string ONCE, with both languages on the same line.

    This file has had three shapes and this contract has had three jobs.

    v6 wrote every string three times — a Persian field default, an English
    overlay applied by `apply()`, and a Persian restore — and the contract's job
    was to check the three key sets still matched. v1 collapsed that to a single
    immutable English table and the job became "no second table may come back".
    Persian is back now, and the shape that avoids BOTH failure modes is a
    computed getter: `val X: String get() = if (farsi) "…" else "…"`.

    So the thing to defend is the shape itself. One line per string means a
    missing translation is a compile error rather than a silently English label,
    and there is no mutable state for a screen to disagree with another about.
    A `var` here is the first step back towards a second table.
    """
    source = read("Fa.kt")
    require("fun resetPersian" not in strip_comments(source),
            "the second string table is back")
    mutable = re.findall(r"^\s*var\s+(\w+)\s*:\s*String", source, re.MULTILINE)
    require(not mutable,
            f"Fa strings must be getters over both languages, not var: {sorted(mutable)}")
    getters = re.findall(r"^\s*val\s+(\w+): String get\(\)", source, re.MULTILINE)
    require(len(getters) > 250,
            f"the string table looks truncated: {len(getters)} entries")
    require(len(getters) == len(set(getters)),
            "a string is declared twice, so one of the two is dead")
    # Every string must offer both languages, or be deliberately identical in both
    # (the product name, protocol names, the version).
    bilingual = re.findall(
        r'^\s*val (\w+): String get\(\) = if \(farsi\)', source, re.MULTILINE)
    shared = re.findall(r'^\s*val (\w+): String get\(\) = "', source, re.MULTILINE)
    require(len(bilingual) + len(shared) == len(getters),
            "a Fa string is neither bilingual nor an explicit both-languages literal")
    require(len(bilingual) > 200,
            f"only {len(bilingual)} strings are translated; the rest are English-only")
    # The language flag is the single source of truth, and it is not writable
    # from outside.
    require("internal var farsi: Boolean = false" in source
            and "private set" in source,
            "the language flag is missing or externally writable")
def test_safe_selection_actions():
    for name in ("MainActivity.kt", "MarkdownRenderer.kt"):
        source = read(name)
        lines = source.splitlines()
        for index, line in enumerate(lines):
            if "setTextIsSelectable(true)" not in line:
                continue
            nearby = "\n".join(lines[index:index + 4])
            require("installSelectionActions" in nearby, f"unsafe selectable TextView in {name}:{index + 1}")
    renderer = read("MarkdownRenderer.kt")
    require("customSelectionActionModeCallback" in renderer, "custom selection ActionMode missing")
    require("catch (firstFailure: RuntimeException" in renderer, "clipboard OEM exception handling missing")


def test_multi_delete_contract():
    engine = read("AgentEngine.kt")
    require('optJSONArray("paths")' in engine, "delete_path paths array missing")
    require("runApprovedDeletes" in engine, "per-path approved delete runner missing")
    require("callback.requestApproval(Tools.ToolNames.DELETE, single)" in engine,
            "delete approval is not requested per path")
    require("DELETE SUMMARY: requested=" in engine, "verified delete summary missing")
    tools = read("Tools.kt")
    require('optJSONArray("paths")' in tools and "deleteSinglePath" in tools,
            "Tools does not safely execute a paths array")
    require('delete_path requires path or paths' in tools, "empty delete path guard missing")


def has_call(source, name, key):
    """Whitespace-tolerant `name("key"` match — Kotlin wraps long argument lists."""
    return re.search(re.escape(name) + r'\(\s*"' + re.escape(key) + r'"', source) is not None


def test_reasoning_contracts():
    client = read("LlmClient.kt")
    require(has_call(client, "put", "reasoning_effort"), "OpenAI reasoning effort missing")
    require(has_call(client, "put", "thinking"), "Anthropic thinking config missing")
    require(has_call(client, "put", "thinkingConfig")
            and has_call(client, "put", "includeThoughts"),
            "Gemini thinking config missing")
    require("isOfficialGeminiEndpoint(baseUrl) && isGeminiThinkingModel(model)" in client,
            "Gemini compatibility guard missing")


def test_direction_and_instant_settings():
    main = read("MainActivity.kt")
    settings = read("SettingsActivity.kt")
    require("drawerLp.gravity = Gravity.START" in main,
            "drawer is not anchored to logical START")
    # The drawer's travel is driven by the LAYOUT direction, not by the language.
    #
    # Those were the same thing while Persian mirrored the whole interface, and
    # conflating them is what let the two drift apart: Lang.direction is the single
    # source now, and every site that must know which physical edge it is anchored
    # to asks Lang.mirrored() rather than testing the language as a proxy for it.
    require("drawerHiddenTranslation" in main
            and "if (Lang.mirrored(this)) width.toFloat() else -width.toFloat()" in main,
            "the drawer's travel is not driven by the layout direction")
    require("Lang.english" not in _function_body(
        main, "    private fun drawerHiddenTranslation(): Float {"),
            "the drawer still infers its anchor edge from the language")
    require("panel.layoutDirection = Lang.direction(this)" in main,
            "drawer content direction is fixed")
    require("saveAll(" not in settings and "Fa.SET_SAVE" not in settings,
            "manual settings Save action still exists")
    require("installInstantTextSettings" in settings and "applyTextSettingsNow" in settings,
            "instant text settings are not installed")
    for setter in ("setProvider", "setThinkingLevel", "setThemeMode", "setWebSearch"):
        require(f"prefs.{setter}" in settings, f"instant setting missing: {setter}")
    # Kotlin has no statement semicolon, so strip comments before looking for
    # the call (the Java check relied on "recreate();").
    require("recreate()" not in strip_comments(main),
            "MainActivity still recreates during appearance changes")
    require("refreshAppearance()" in main and "reconcileRunningState()" in main,
            "in-place appearance refresh does not preserve active run state")


def test_no_texture_bitmaps_in_ui():
    # The UI must be drawn in code (gradients/vector paths); decorative texture
    # PNGs are banned — only launcher icons may be raster images.
    nodpi = ROOT / "res/drawable-nodpi"
    leftover = list(nodpi.glob("*.png")) if nodpi.is_dir() else []
    require(not leftover, f"texture bitmaps present: {leftover}")
    for name in ("MainActivity.kt", "SettingsActivity.kt"):
        source = read(name)
        require("aurora_welcome" not in source and "aurora_reasoning" not in source,
                f"aurora texture reference still in {name}")
        require("R.drawable." not in source, f"raster drawable reference still in {name}")


def test_key_router_and_reset_contracts():
    client = read("LlmClient.kt")
    require("KeyRouter.isRateLimit(error) && router.rotate()" in client,
            "silent rate-limit rotation missing")
    require("buildRouter(prefs)" in client, "router not built from prefs")
    require("currentApiKey()" in client, "per-attempt key selection missing")
    router = read("KeyRouter.kt")
    for marker in ('"429"', '"rpm"', '"quota"', '"rate limit"'):
        require(marker in router, f"rate-limit detector missing {marker}")
    prefs = read("Prefs.kt")
    require('putInt("router_index"' in prefs, "sticky router index missing")
    require("MAX_ROUTER_KEYS = 50" in prefs, "50-key cap missing")
    require("clearAll()" in prefs, "settings reset missing")
    require('getInt("max_tokens", 10000)' in prefs, "10k default max tokens missing")
    settings = read("SettingsActivity.kt")
    require("Fa.SET_KEY_ROUTER" in settings and "refreshKeyRows()" in settings,
            "key router settings section missing")
    require("Fa.SET_RESET" in settings and "confirmReset()" in settings,
            "reset settings section missing")


# ---- Kotlin-specific guards (new) ------------------------------------------

def test_no_java_isms_left():
    """Every ported file must be real Kotlin, not transliterated Java."""
    banned = (
        (r"Pattern\.compile\(", "use Regex(...)"),
        (r"\.replaceAll\(", "use replace(Regex(...))"),
        # word-boundary anchored: "lastWakeRenew = x" also contains "new "
        (r"\bnew\s+[A-Z]\w*\s*\(", 'Kotlin has no "new"'),
        (r"@Override\b", "use override"),
        (r"\bpublic\s+static\b", "Java modifier"),
    )
    for path in sorted(SRC.glob("*.kt")):
        code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', strip_comments(path.read_text(encoding="utf-8")))
        for pattern, hint in banned:
            found = re.search(pattern, code)
            require(found is None,
                    f"{path.name}: leftover Java-ism {found.group(0)!r} ({hint})" if found else "")


def strip_comments(source):
    """Drops comments so prose that merely mentions code is never scanned."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return re.sub(r"//[^\n]*", "", source)


def test_argb_literals_are_narrowed():
    """An ARGB literal above Int.MAX_VALUE is a Long in Kotlin and must be cast."""
    for path in sorted(SRC.glob("*.kt")):
        source = strip_comments(path.read_text(encoding="utf-8"))
        for match in re.finditer(r"0x([0-9A-Fa-f]{8})\b(?!\s*\.toInt\(\))", source):
            value = int(match.group(1), 16)
            require(value <= 0x7FFFFFFF,
                    f"{path.name}: 0x{match.group(1)} needs .toInt() (Kotlin types it as Long)")


def test_no_force_unwrap():
    """`!!` defeats the null-safety the port is supposed to gain."""
    for path in sorted(SRC.glob("*.kt")):
        code = strip_comments(path.read_text(encoding="utf-8"))
        code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', code)
        require("!!" not in code, f"{path.name}: uses !! instead of real null handling")


def test_java_exact_trim_is_used_everywhere():
    """
    `java.lang.String.trim()` strips every char <= U+0020; Kotlin's `trim()`
    strips `Char.isWhitespace()` chars instead. They disagree on C0 controls
    (NUL, ESC, ...) and on Unicode separators above U+0020, so a straight
    translation silently changes what the app does with model output. The port
    routes every such call through JavaText.kt — this test keeps it that way.
    """
    banned = {
        ".trim()": ".trimJava()",
        ".isBlank()": ".isBlankJava()",
        ".isNotBlank()": ".isNotBlankJava()",
        ".isNullOrBlank()": ".isNullOrBlankJava()",
    }
    for path in sorted(SRC.glob("*.kt")):
        if path.name == "JavaText.kt":
            continue
        code = strip_comments(path.read_text(encoding="utf-8"))
        code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', code)
        for bad, good in banned.items():
            # `.trimJava()` contains `.trim`, so match the exact call only.
            hits = re.findall(re.escape(bad) + r"(?![A-Za-z])", code)
            require(not hits, f"{path.name}: use {good} instead of {bad}")
    helper = read("JavaText.kt")
    for name in ("trimJava", "isBlankJava", "isNotBlankJava",
                 "isNullOrBlankJava", "trimJavaOrEmpty"):
        require(f"fun String" in helper and name in helper,
                f"JavaText.kt: missing {name}")
    require("<= ' '" in helper, "JavaText.kt: trimJava must compare against ' '")


def test_no_literal_control_bytes_in_source():
    r"""
    A raw C0 byte in a source file breaks `grep`, diff tools and some editors,
    and it is never what was meant - control characters belong in \uXXXX form.
    """
    allowed = {0x09, 0x0A, 0x0D}
    for path in sorted(SRC.glob("*.kt")):
        data = path.read_bytes()
        bad = sorted({b for b in data if b < 0x20 and b not in allowed})
        require(not bad, f"{path.name}: literal control bytes {[hex(b) for b in bad]}")


def test_count_occurrences_cannot_loop_forever():
    """
    The original Java spun forever on an empty needle (`indexOf("", from)` never
    returns -1 and `from` never advances). The port must guard it.
    """
    source = read("Tools.kt")
    body = source[source.index("private fun countOccurrences"):]
    body = body[:body.index("\n        }")]
    require("needle.isEmpty()" in body,
            "Tools.countOccurrences: missing the empty-needle guard (infinite loop)")


def test_all_java_files_were_ported():
    java_dir = ROOT.parent / "VeproCode-v1-src/src/com/vepro/code"
    if not java_dir.is_dir():
        return  # original tree not shipped alongside; nothing to compare
    java_names = {p.stem for p in java_dir.glob("*.java")}
    kotlin_names = {p.stem for p in SRC.glob("*.kt")}
    missing = sorted(java_names - kotlin_names)
    require(not missing, f"not ported to Kotlin: {missing}")


def test_stream_never_ends_silently():
    """
    An answer cut off by the output-token ceiling used to be indistinguishable
    from a finished one, so the run just stopped -- usually mid-code-block.
    The finish reason must be read, surfaced, and resumed from.
    """
    client = read("LlmClient.kt")
    require("fun onTruncated()" in client, "StreamCallback has no truncation signal")
    require("noteFinishReason" in client, "no finish_reason classification")
    for key in ('"finish_reason"', '"stop_reason"', '"finishReason"'):
        require(key in client, f"finish reason key {key} is never read")
    require("state.truncated = true" in client, "truncation is never recorded")
    require("!sawTerminal && !state.finishedCleanly" in client,
            "an abrupt end of stream is still reported as a clean finish")

    engine = read("AgentEngine.kt")
    require("MAX_CONTINUATIONS" in engine, "no bound on resume rounds")
    require("continuationMessages" in engine, "a truncated turn is never resumed")
    require("RESUME_INSTRUCTION" in engine, "resume carries no instruction")
    require("closeOpenFence" in engine, "a truncated fence is never balanced")


def test_run_modes_are_actually_distinct():
    """
    ACCEPT must gate every action, not only mutations -- otherwise it is AUTO
    with a dialog bolted on. PLAN must produce a decision block.
    """
    tools = read("Tools.kt")
    body = tools[tools.index("fun needsApproval"):]
    body = body[:body.index("\n\n")]
    require("isMutating" not in body,
            "needsApproval is still an alias of isMutating: ACCEPT never asks about reads")

    engine = read("AgentEngine.kt")
    require("isAllowedForSession" in engine, "no per-session allowance escape hatch")
    require("[BEST]" in engine, "PLAN mode never marks a recommended option")
    require(engine.count("[OPTION]") >= 3, "PLAN mode does not ask for three options")

    prefs = read("Prefs.kt")
    require("fun isValidMode" in prefs, "mode preference is not validated")
    require("if (isValidMode(stored)) stored else MODE_ACCEPT" in prefs,
            "an unknown stored mode still falls through to AUTO's else branch")


def test_edits_are_surgical_not_whole_file_rewrites():
    tools = read("Tools.kt")
    require("MAX_READ_LINES" in tools, "read_file is not windowed")
    require("hasBeenRead(target)" in tools, "edit_file does not require a prior read")
    require('args.optBoolean("overwrite", false)' in tools,
            "write_file still silently overwrites an existing file")
    require("stripLineNumbers" in tools,
            "a copied line-number prefix still breaks every edit")
    require("readWindowLine" in tools,
            "read_file uses readLine(), which materialises a whole minified file")


def test_fence_detection_is_line_anchored_everywhere():
    """
    'Code block' is decided by fence parity, so a ``` counted in the wrong place
    does not mis-render one span -- it swaps every span after it.
    """
    renderer = read("MarkdownRenderer.kt")
    require("splitFences" in renderer, "the renderer has no line-anchored fence split")
    code = "\n".join(line for line in renderer.splitlines()
                     if not line.lstrip().startswith(("*", "//", "/*")))
    require(re.search(r'\w\.split\("```"\)', code) is None,
            "the renderer still splits on every ``` occurrence")
    think = read("Think.kt")
    require("insideFence" in think,
            "a <think> tag quoted inside a code block still hides the rest of the answer")


def test_stream_reveal_is_opacity_only():
    reveal = read("StreamReveal.kt")
    require("setShadowLayer" not in reveal,
            "the streaming reveal still blurs text behind a shadow halo")
    require("START_ALPHA" in reveal, "the reveal is not a plain alpha ramp")


def test_status_bar_matches_the_header():
    main = read("MainActivity.kt")
    require("w.statusBarColor = Theme.BG_ELEV" in main,
            "the status bar no longer matches the header bar below it")
    require("statusScrim" in main,
            "nothing paints the status strip on API 35 edge-to-edge, where "
            "statusBarColor is ignored")


def read_root(name):
    return (ROOT / name).read_text(encoding="utf-8")


def read_test(name):
    return (ROOT / "tests/com/vepro/code" / name).read_text(encoding="utf-8")


def _function_body(source, signature):
    start = source.index(signature)
    rest = source[start + len(signature):]
    nxt = re.search(r"\n    (private|internal|public|override|fun) ", rest)
    return rest[: nxt.start()] if nxt else rest


def test_resume_never_splices_inside_a_tool_call():
    """
    A turn cut off mid tool call must NOT be resumed by character-splicing — a
    one-token-off resume stays valid JSON but changes a value (this is what
    turned about_me.txt into about_me.xt). The engine must detect the open
    ```json tool-call fence and re-emit the whole call instead.
    """
    engine = read("AgentEngine.kt")
    require("fun openToolCallFenceStart" in engine, "open tool-call fence detector missing")
    require("REEMIT_TOOL_INSTRUCTION" in engine, "tool-call re-emit instruction missing")
    require("openToolCallFenceStart(combined)" in engine,
            "the resume loop does not check for an open tool-call fence before splicing")


def test_stream_terminal_is_recognized_at_eof():
    """
    A stream ending `data: [DONE]` directly on EOF (no trailing blank line, as
    several gateways send) must still count as a clean terminal, or a COMPLETE
    answer gets flagged truncated and needlessly resumed — manufacturing the very
    seams that corrupt tool calls.
    """
    client = read("LlmClient.kt")
    require(len(re.findall(r"isTerminalEvent\(payload, protocol\)", client)) >= 2,
            "the EOF branch does not check for a terminal event")


def test_not_found_offers_a_near_miss_hint():
    """A one-character-off path should point the model at the real file, not just fail."""
    tools = read("Tools.kt")
    require("fun notFound(" in tools, "near-miss not-found helper missing")
    require("editDistanceWithin1" in tools, "near-miss edit-distance check missing")
    require("did you mean" in tools, "not-found hint text missing")
    require(tools.count("return notFound(target)") >= 2,
            "read_file/edit_file do not use the near-miss not-found helper")


def test_drawer_listing_is_titles_only():
    """
    Deleting a chat used to re-parse every message of every chat two or three
    times on the UI thread — an OOM/ANR once many tabs existed. The drawer and
    delete flow must use the header-only listing instead.
    """
    store = read("ChatStore.kt")
    require("fun listSummaries" in store, "titles-only chat listing missing")
    require("class Summary" in store, "chat Summary type missing")
    main = read("MainActivity.kt")
    refresh = _function_body(main, "private fun refreshChatList() {")
    require("store.listSummaries()" in refresh, "the drawer no longer uses the cheap listing")
    require("store.list()" not in refresh, "the drawer still parses whole conversations")


def test_global_uncaught_handler_is_installed():
    """Any unforeseen throw must relaunch cleanly, not show the OS crash dialog."""
    app = read("App.kt")
    require("setDefaultUncaughtExceptionHandler" in app, "no global crash handler")
    require("LOOP_WINDOW_MS" in app, "no crash-loop guard")
    manifest = read_root("AndroidManifest.xml")
    require('android:name=".App"' in manifest, "the Application class is not registered")


def test_foreground_service_start_is_not_auto_restarted():
    """
    A system restart of a foreground service from the background throws on
    Android 12+, so the run must not ask to be redelivered.
    """
    service = read("AgentService.kt")
    require("START_REDELIVER_INTENT" not in service,
            "a background FGS redelivery can crash on Android 12+")


def test_number_field_accepts_persian_digits():
    """A Persian keyboard enters ۰-۹; those must be parsed, not silently dropped."""
    settings = read("SettingsActivity.kt")
    require("fun normalizeDigits" in settings, "Persian/Arabic digit normalisation missing")
    require("normalizeDigits(value.trimJava())" in settings,
            "the number parser does not normalise digits before toInt()")


def test_tap_to_copy_panel_is_inline_and_safe():
    """
    Tapping a message reveals a copy panel. It must be an INLINE view, never a
    PopupWindow (which needs a live window token and is a classic source of
    BadTokenException / leaked windows), must reuse the hardened clipboard path,
    and must drop its reference when the transcript is torn down.
    """
    main = read("MainActivity.kt")
    require("attachCopyPanel" in main, "tap-to-copy panel missing")
    # Real usage only — the comments explain *why* a popup is avoided.
    require("PopupWindow(" not in strip_comments(main),
            "the copy panel must not use a PopupWindow")
    require("MarkdownRenderer.copyText" in main,
            "the copy panel does not reuse the hardened clipboard helper")
    require("openCopyPanel" in main, "no single-open-panel tracking")
    renderer = read("MarkdownRenderer.kt")
    require("fun copyText" in renderer, "public hardened copy entry point missing")
    require("TAG_CODE_CARD" in renderer and "TAG_CODE_CARD" in main,
            "code cards are not excluded from the message-level copy panel")
    # The panel must be cleared where the tree it lives in is destroyed.
    render_all = _function_body(main, "private fun renderAll() {")
    require("openCopyPanel = null" in render_all,
            "renderAll leaves a stale copy-panel reference into a torn-down tree")
    destroy = _function_body(main, "override fun onDestroy() {")
    require("openCopyPanel = null" in destroy, "onDestroy leaves a stale copy-panel reference")


def test_copy_panel_preserves_text_selection():
    """
    The message TextViews are selectable. The tap detector must not consume
    touches, or long-press selection and scrolling would break.
    """
    main = read("MainActivity.kt")
    attach = _function_body(main, "private fun attachCopyPanel(")
    require("scaledTouchSlop" in attach, "the tap detector does not respect touch slop")
    require("return false" in attach,
            "the tap detector consumes touches, which would break text selection")


def test_the_app_ships_two_languages():
    """
    English and Persian, chosen once on first launch and changeable in Settings.

    This contract used to assert the opposite. v1 removed Persian entirely and
    this test existed to keep it removed; the owner asked for it back, fully
    mirrored, so the invariants invert — but they are still worth pinning, because
    a bilingual app degrades silently. A missing `Fa.apply` on one screen, or one
    `Lang.text` pair left untranslated, produces a half-English screen that
    nothing else notices.
    """
    prefs = read("Prefs.kt")
    for needed in ("fun language(", "fun setLanguage", "fun languageChosen",
                   "fun setLanguageChosen"):
        require(needed in prefs, f"the language preference is missing: {needed}")
    require(".commit()" in _function_body(prefs, "fun setLanguageChosen() {"),
            "the language choice is not committed synchronously before the UI rebuild")

    # First launch asks, exactly once, and cannot be dismissed unanswered.
    main = read("MainActivity.kt")
    require("private fun showLanguagePicker" in main, "the first-launch chooser is gone")
    require("prefs.languageChosen()" in main, "the chooser is not gated on first launch")
    picker = _function_body(main, "    private fun showLanguagePicker() {")
    require("setCancelable(false)" in picker,
            "the language chooser can be dismissed unanswered, so it would ask again")
    # Each option in its own script — the one screen that must read to either.
    require("فارسی" in main and '"English"' in main,
            "the chooser does not offer each language in its own script")
    # MainActivity rebuilds in place; recreate() is banned here and always was.
    require("recreate()" not in strip_comments(main),
            "MainActivity recreates itself, which loses the open chat on MIUI")
    require("refreshAppearance()" in _function_body(
        main, "    private fun applyChosenLanguage(value: String) {"),
            "the language choice does not rebuild the UI through the in-place path")

    # Settings can change it later, and THERE recreate() is the right answer.
    settings = read("SettingsActivity.kt")
    require("prefs.setLanguage" in settings, "the language cannot be changed in Settings")
    require("recreate()" in settings, "the settings language switch does not rebuild")

    # Every screen re-reads the language on entry.
    for name in ("MainActivity.kt", "SettingsActivity.kt", "AgentService.kt"):
        require("Fa.apply(this)" in read(name), f"{name} never applies the language")
def test_model_is_told_the_date_and_told_to_search():
    """
    The model cannot know its knowledge is stale without today's date, so it
    answered time-sensitive questions from memory. It must receive the date and
    be told to search on its own initiative.
    """
    engine = read("AgentEngine.kt")
    require("fun todayStamp" in engine, "the model is never told the current date")
    require('SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)' in engine,
            "the date must be locale-independent Gregorian, not the phone's calendar")
    require("todayStamp()" in engine, "the date is computed but never sent")
    require("Staying current" in engine, "no proactive-search guidance in the system prompt")
    require("search FIRST with web_search" in engine,
            "the model is not told to search before answering from stale memory")


def test_persian_layout_matches_english():
    """
    The interface is English, and the CONTENT is whatever the user and the model
    wrote — Persian included. These are the specific regressions that made mixed
    content render badly.
    """
    main = read("MainActivity.kt")
    # There is no gradient wordmark any more: the header is a plain single-line
    # title. The old bug was a Latin wordmark whose gradient got mirrored via an
    # `!Lang.english(this)` rtl flag; the monochrome rebuild deletes the shader
    # outright, so the fix is now "the flag cannot come back because the thing
    # it flipped does not exist".
    require("titleShader" not in main,
            "the gradient wordmark is back on the chat screen; the header title is plain text")
    require("Lang.english" not in main,
            "a direction flag is being derived from the interface language — there is "
            "only one language now; use START/END or Lang.direction")
    theme = read("Theme.kt")
    require("titleShader" not in theme,
            "Theme.titleShader is back; it is on the deleted list (no gradients in the palette)")
    # Labels forced LTR inside a weighted slot must be pinned to the reading edge.
    # (`rawName` is gone — addToolRow no longer renders a raw mono tool name —
    # but the approval sheet's tool label still needs the pin.)
    require("toolLabel.textAlignment" in main,
            "toolLabel.textAlignment missing — the label will strand at the far edge")
    browser = read("FileBrowser.kt")
    require(browser.count("text.textAlignment = View.TEXT_ALIGNMENT_VIEW_START") >= 2,
            "file browser names are not pinned beside their icons")
    settings = read("SettingsActivity.kt")
    require("tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START" in settings,
            "the masked key strands at the far edge of its row")
    # The API-key fields are LTR islands so Persian matches English exactly.
    require(settings.count("box.layoutDirection = View.LAYOUT_DIRECTION_LTR") >= 2,
            "API key fields are not LTR islands, so the reveal button flips sides")
    # Mixed Persian+Latin strings must resolve per paragraph, not be forced LTR.
    # The running row carries a DETAIL lifted from the tool's arguments — a search
    # query, a file name — which is frequently Persian even though the label around
    # it is English.
    require("label.textDirection = View.TEXT_DIRECTION_FIRST_STRONG" in main,
            "the running indicator is forced LTR and reads backwards with Persian detail")


def test_directional_padding_is_relative():
    """
    setPadding's left/right do NOT mirror. Rows whose start and end insets differ
    must use setPaddingRelative or Persian gets the spacing backwards.
    """
    for name in ("MainActivity.kt", "FileBrowser.kt", "SettingsActivity.kt"):
        source = strip_comments(read(name))
        require("setPaddingRelative" in source, f"{name} has no relative padding at all")
    browser = strip_comments(read("FileBrowser.kt"))
    require("row.setPadding(padH" not in browser,
            "file browser rows still use physical padding, which flips in Persian")


def test_window_chrome_matches_the_palette():
    """
    The activity theme paints the cold-start frame before any view exists, so a
    stale colour here flashes the OLD palette on every launch.
    """
    theme = read("Theme.kt")
    for styles in ("res/values/styles.xml", "res/values-v29/styles.xml"):
        xml = read_root(styles)
        # The two grounds: dark #FF0D0D0D and light #FFF9F9F9.
        for literal in ("#FF0D0D0D", "#FFF9F9F9"):
            require(literal in xml, f"{styles} is out of step with Theme.kt ({literal} missing)")
        # Every palette this file has ever carried before the monochrome one.
        for stale in ("#FF0F1013", "#FF16171B", "#FFF7F7F8", "#FF07070C", "#FFF3F4F9"):
            require(stale not in xml, f"{styles} still carries the old {stale} palette")
    require("BG = 0xFF0D0D0D" in theme and "BG = 0xFFF9F9F9" in theme,
            "Theme.kt palette no longer matches the window chrome")


def test_system_bars_do_not_cover_the_drawer():
    """
    targetSdk 35 forces edge-to-edge on Android 15: without a bottom inset the
    drawer's last rows sit under the navigation bar and cannot be tapped.
    """
    main = read("MainActivity.kt")
    require("navigationBars()" in main, "the navigation-bar inset is never read")
    require("panel.paddingBottom != bottom" in main,
            "the drawer is not padded clear of the navigation bar")


def test_no_localized_string_is_persisted_or_compared():
    """
    A translated string written to disk freezes in the language that was active
    at write time, and comparing against the CURRENT language's value then fails.
    Both the chat-title placeholder and the run-stalled marker hit this.
    """
    fa = read("Fa.kt")
    require("fun isPlaceholderTitle" in fa and "fun isStalledMessage" in fa,
            "language-independent marker checks missing")
    # Real code only — the comments explain *why* the translated value is avoided.
    store = strip_comments(read("ChatStore.kt"))
    require("Fa.NEW_CHAT" not in store,
            "ChatStore persists the translated placeholder title again")
    chat = strip_comments(read("Chat.kt"))
    require("Fa.NEW_CHAT" not in chat, "Chat.fromJson bakes in a translated title")
    main = strip_comments(read("MainActivity.kt"))
    require("Fa.NEW_CHAT ==" not in main and "== Fa.NEW_CHAT" not in main,
            "a chat title is compared against the current language's string")
    require("Fa.RUN_STALLED ==" not in main and "== Fa.RUN_STALLED" not in main,
            "the stalled marker is compared against the current language's string")
    require("Fa.isStalledMessage(m.content)" in main,
            "the continue-card check is not language-independent")


def test_ui_state_survives_a_theme_rebuild():
    """
    buildUi() destroys the whole view tree on every theme change, so any state set
    imperatively afterwards must be re-derived in the builder.
    """
    main = read("MainActivity.kt")
    # The permission banner must reflect the real permission, not a hard-coded GONE.
    require("if (hasStorageAccess()) View.GONE else View.VISIBLE" in main,
            "the storage-permission banner resets to hidden on a theme change")
    # The running-tool pill must come back mid-step.
    require("runningTool" in main and "clearRunningTool" in main,
            "the running-tool pill is not restored after a rebuild")
    # Reading position must be kept.
    refresh = _function_body(main, "private fun refreshAppearance() {")
    require("scrollY" in refresh, "the reading position is lost on a theme change")
    require("Sheet.dismissAll()" in refresh,
            "old-palette sheets survive the rebuild and look foreign")
    # A dismissed continue card must stay dismissed.
    require("dismissedContinueAt" in main, "the continue card resurrects on every rebuild")


def test_one_approval_sheet_at_a_time():
    """
    Approval redelivery fires on every onStart/onResume/rebuild; without a guard
    it stacks duplicate modal sheets the user must dismiss one by one.
    """
    main = read("MainActivity.kt")
    require("shownApproval" in main, "no guard against duplicate approval sheets")
    require("if (shownApproval === approval)" in main,
            "the approval dedup guard does not compare identity")


def test_notification_channel_follows_the_language():
    """
    The channel name lives in system settings for the app's lifetime; skipping
    the update call froze it in the first-run language.
    """
    service = strip_comments(read("AgentService.kt"))
    require("if (manager.getNotificationChannel(CHANNEL_ID) != null)" not in service,
            "the notification channel name is frozen at first-run language")
    require("Fa.apply(this)" in service,
            "a cold service start would use the default language table")


def test_offline_stubs_cover_the_source():
    """
    tools/build-offline.sh compiles the whole app against generated stubs, with no
    Android SDK — it is the CI "verify" gate AND the only way the behavioural
    CoreRegressionTests suite can run. Every platform API the source uses must be
    declared in gen_stubs.py, or that entire gate silently fails and the
    behavioural suite is skipped.
    """
    stubs = read_root("tools/gen_stubs.py")
    # APIs the current source depends on that the generator once lacked.
    required = (
        "TEXT_ALIGNMENT_VIEW_START", "setClipChildren", "setPivotX",
        "addOnAttachStateChangeListener", "addOnLayoutChangeListener",
        "android.app.Application", "android.app.AlarmManager", "android.os.Process",
        "android.util.Log", "FLAG_ONE_SHOT", "getLaunchIntentForPackage",
        "android.animation.ValueAnimator", "LayoutTransition", "LinearInterpolator",
        "PathInterpolator", "ViewConfiguration", "getScaledFrameAtTime",
        "setTextClassifier", "setCustomInsertionActionModeCallback",
        "ClickableSpan", "android.text.Layout", "getSpans",
        "overridePendingTransition", "onSaveInstanceState", "putString",
        "int LEFT = 3", "getRawX", "ACTION_MOVE", "scrollTo", "ACTION_VIEW",
        # BrandMark.kt cuts the inner star out of the outer one as a hole, which
        # needs an even-odd fill rule; a WINDING-only Path stub silently fills it.
        "FillType", "EVEN_ODD", "setFillType",
    )
    missing = [name for name in required if name not in stubs]
    require(not missing, f"gen_stubs.py is missing APIs the source uses: {missing}")


def test_connection_test_agrees_with_chat():
    """
    The test button must not fail on an endpoint that chats fine. Two causes:
    a 16-token ceiling (a reasoning model spends it all on thinking and returns
    empty text) and treating empty text as a connection failure.
    """
    client = read("LlmClient.kt")
    body = _function_body(client, "        fun testConnection(")
    require("512" in body and "16, 0.2f" not in body,
            "the connection test still uses a token ceiling a reasoning model cannot answer within")
    require("Fa.SET_TEST_UNREADABLE" not in body,
            "an empty-but-valid reply is still reported as a failed connection")


def test_timeout_is_a_real_deadline():
    """
    readTimeout only fires while a read() is blocked, so a server that accepts
    the socket and then stalls ignored it entirely — which is why setting 10s
    appeared to do nothing. A wall-clock deadline is required.
    """
    client = read("LlmClient.kt")
    require("lastProgressAt" in client, "no wall-clock inactivity deadline in the stream loop")
    require("vepro-http-deadline" in client, "the non-streaming path has no hard deadline")
    require("connection.connectTimeout = Math.min(25000, timeoutMs)" in client,
            "connect can still outlast the user's timeout")
    retry = _function_body(client, "    private fun shouldRetry(")
    require("408" in retry, "a timeout is retried, multiplying the user's limit")


def test_web_fetch_reads_pages_properly():
    """Fewer false 'blocked' errors, real article extraction, and a durable copy."""
    web = read("Web.kt")
    require("fun readableText" in web, "no main-content extraction — the model reads nav+footer slurry")
    require("CHALLENGE_TEXT_MAX" in web,
            "a page that merely mentions 'captcha' is still reported as blocked")
    require("45000" in web, "long pages are still truncated to the old short limit")
    tools = read("Tools.kt")
    require("cacheFetchedPage" in tools,
            "fetched pages are not persisted, so the agent forgets them after compaction")


def test_dynamic_workflow():
    """Claude-Code-style delegation, opt-in, bounded, with an XHIGH floor."""
    prefs = read("Prefs.kt")
    require("fun dynamicWorkflow" in prefs and "fun setDynamicWorkflow" in prefs,
            "no Dynamic Workflow setting")
    effective = _function_body(prefs, "    fun effectiveThinkingLevel(): String {")
    require('"xhigh"' in effective and '"max"' in effective,
            "Dynamic Workflow does not raise reasoning to the xhigh floor")
    engine = read("AgentEngine.kt")
    require("fun runSubAgent" in engine, "no sub-agent runner")
    require("MAX_SUBAGENT_DEPTH" in engine and "MAX_SUBAGENT_STEPS" in engine,
            "sub-agents are unbounded (runaway cost / recursion)")
    require("callback.requestApproval(tool, args2)" in engine,
            "a sub-agent's actions bypass the user's approval")
    require("DYNAMIC WORKFLOW IS ON" in engine and "ALWAYS OPEN WITH A PLAN" in engine,
            "the model is never told how to use the task tool")
    require("WRITING THE BRIEF IS THE SKILL" in engine,
            "nothing tells the model a sub-agent brief must be self-contained")
    tools = read("Tools.kt")
    require("ToolNames.TASK" in tools,
            "task is not a known tool, so the parser discards the call")
    settings = read("SettingsActivity.kt")
    require("swWorkflow" in settings, "no Settings toggle for Dynamic Workflow")


def test_edits_are_incremental_and_forgiving():
    """
    Two reported failures: edits erroring with a blank path, and "old_string not
    found" on text the model had just read. Plus the batch-size problem — 12
    changes in one atomic call that all fail together.
    """
    tools = read("Tools.kt")
    # Alternate arg names must not read as an empty path.
    require("fun pathArg" in tools, "path aliases (file_path etc.) still read as empty")
    require('resolve(args.optStr("path", ""))' not in tools,
            "some file tool still reads the raw path arg without alias tolerance")
    # Matching must survive partial line-number prefixes and indent drift.
    require("fun withoutLineNumber" in tools,
            "line-number stripping is still all-or-nothing")
    require("fun anchorFind" in tools, "no anchored fallback for re-indented blocks")
    require("fun nearbyHint" in tools,
            "a failed edit does not show the model what the file actually contains")
    # Progress must never be thrown away, and there is no hard batch cap.
    require("too many edits in one call" not in tools,
            "a hard edit-count cap is back; the model should pace itself instead")
    require("edits applied" in tools,
            "multi-edit is atomic again — a single bad needle discards matching edits")
    require("do not send them again" in tools,
            "the model is not told which edits already saved")
    engine = read("AgentEngine.kt")
    require("ONE CONCERN PER CALL" in engine,
            "the model is not told to change one thing at a time")
    require("MAP THE FILE FIRST" in engine,
            "the model is not told to read the file's functions before editing")


def test_dynamic_workflow_is_locked_and_scoped():
    """The level must LOCK at xhigh, and a sub-agent must not be told to delegate."""
    settings = read("SettingsActivity.kt")
    require("bar.isEnabled = !locked" in settings,
            "the thinking slider is still draggable while Dynamic Workflow forces xhigh")
    slider = _function_body(settings, "    private fun refreshLevel() {")
    require("dynamicWorkflow()" in slider, "the slider does not reflect the forced level")
    require("if (!fromUser) {" in settings,
            "a programmatic slider move would overwrite the user's stored level")
    engine = read("AgentEngine.kt")
    require("prefs.dynamicWorkflow() && depth == 0" in engine,
            "sub-agents are offered a task tool they are forbidden to use")
    require("You are a focused sub-agent" in engine,
            "sub-agents get the lead's delegation playbook instead of their own brief")


def test_line_range_editing_exists():
    """
    The deterministic escape hatch from old_string matching. Minified files (one
    huge line) and any text the model cannot reproduce byte-for-byte are
    otherwise uneditable — every edit just reports "old_string not found".
    """
    tools = read("Tools.kt")
    require('args.optInt("start_line", 0)' in tools,
            "edit_file has no line-range form")
    require("replaced lines " in tools, "the line-range edit reports nothing useful")
    engine = read("AgentEngine.kt")
    require("LINE-RANGE replace" in engine,
            "the model is never told the line-range form exists")
    require("fails a SECOND time" in engine,
            "the model is not told to escalate to line-range after a failed match")


def test_path_errors_are_actionable():
    """A Persian-only SecurityException told the user nothing about what to fix."""
    tools = read("Tools.kt")
    require("path is outside the workspace. Allowed root:" in tools,
            "the out-of-workspace error does not name the allowed root")


def _strip_comments_keep_lines(source):
    """strip_comments, but every newline survives so line numbers stay truthful."""
    source = re.sub(r"/\*.*?\*/",
                    lambda m: "\n" * m.group(0).count("\n"), source, flags=re.S)
    return re.sub(r"//[^\n]*", "", source)


def test_palette_is_strictly_monochrome():
    """
    The target look has ZERO hue: every colour is black, white, or a grey. A
    single tinted literal (the old `#FF5B8CFF` accent, a Telegram blue, a red
    error tint) is invisible in a diff and obvious on screen, so the palette is
    checked arithmetically rather than by name.

    Two rules:
      1. Every 8-digit ARGB literal in Theme.kt must satisfy R == G == B.
         (Alpha is free — `0x14FFFFFF` and `0xB3000000` are washes, not hues.)
      2. Raw `0xFFFFFFFF` / `0xFF000000` may only appear in Theme.kt (which
         defines the palette) and BrandMark.kt (a pure-black vector logo).
         Everywhere else they must be routed through ACCENT / ON_ACCENT / TEXT,
         because ACCENT and ON_ACCENT INVERT between themes — a hardcoded white
         is invisible on the light ground, and a hardcoded black on the dark one.
    """
    theme = strip_comments(read("Theme.kt"))
    # The DIFF_* lines are the one sanctioned exception, and they are excluded by
    # NAME rather than by value so the exception cannot quietly widen: added and
    # removed are opposites, not two amounts of one thing, and lightness alone
    # cannot say which is which. Every other literal in the file must be grey.
    hued_ok = 0
    for line in theme.splitlines():
        if re.search(r"\bDIFF_(ADD|DEL)(_BG)?\s*=", line):
            hued_ok += 1
            continue
        for match in re.finditer(r"0x([0-9A-Fa-f]{8})\b", line):
            argb = match.group(1).upper()
            red, green, blue = argb[2:4], argb[4:6], argb[6:8]
            # A pure-alpha wash over black or white is monochrome by construction;
            # this is the same condition, spelled out so the intent is readable.
            wash = argb[2:] in ("000000", "FFFFFF")
            require(wash or (red == green == blue),
                    f"Theme.kt: 0x{argb} carries hue (R={red} G={green} B={blue}); "
                    "the palette must be pure greyscale")
    # Both palettes define all four, so a theme cannot render a colourless diff.
    require(hued_ok == 8,
            f"expected 8 diff colour assignments (4 per palette), found {hued_ok}")

    raw = re.compile(r"0x[Ff][Ff](?:[Ff]{6}|0{6})\b")
    for path in sorted(SRC.glob("*.kt")):
        if path.name in ("Theme.kt", "BrandMark.kt"):
            continue
        lines = _strip_comments_keep_lines(path.read_text(encoding="utf-8")).splitlines()
        for index, line in enumerate(lines):
            found = raw.search(line)
            require(found is None,
                    f"{path.name}:{index + 1}: hardcoded {found.group(0) if found else ''} — "
                    "route it through Theme.ACCENT / Theme.ON_ACCENT / Theme.TEXT / "
                    "Theme.SCRIM so it inverts with the theme")


def test_chat_screen_brand_usage_is_deliberate():
    """
    The chat screen carries the logo in exactly ONE deliberate place: a faint
    centred watermark behind the transcript. Everything the monochrome rebuild
    removed stays removed — no gradient wordmark, no brand tile in the header, no
    hero.

    It used to be two. The second was the standalone "Model reasoning" card's
    icon, and that card no longer exists — reasoning lives in the review section,
    which marks itself with the "neuron" glyph like every other activity row. A
    brand mark standing in for a reasoning icon was always the wrong sign anyway.

    The watermark must stay untappable and nearly invisible, or it stops being a
    watermark and starts being furniture that intercepts taps.

    Comments are stripped first, so a note explaining the intent is allowed.
    """
    main = strip_comments(read("MainActivity.kt"))
    for banned in ("heroTile", "brandTile", "titleShader"):
        require(banned not in main,
                f"{banned} is back in MainActivity.kt — the chat screen carries no brand "
                "tile, no hero and no gradient wordmark")
    # Exactly the two sanctioned uses.
    uses = main.count("BrandMark(")
    require(uses == 1,
            f"expected exactly 1 BrandMark use on the chat screen "
            f"(the watermark), found {uses}")
    require("watermark.alpha" in main, "the centred logo is not a faint watermark")
    require("watermark.isClickable = false" in main,
            "the watermark could intercept taps meant for the transcript")
    hosts = sorted(path.name for path in SRC.glob("*.kt")
                   if path.name != "BrandMark.kt"
                   and "BrandMark(" in strip_comments(path.read_text(encoding="utf-8")))
    require(hosts == ["MainActivity.kt", "SettingsActivity.kt"],
            f"the logo is drawn from unexpected screens: {hosts}")


def _icon_names_used(source):
    """(name, line) for every icon-name STRING LITERAL reaching the icon table.

    Covers the three direct entry points plus the Ui helpers that forward an
    `icon: String` straight through to them — the helper call sites are where
    most names are actually written.
    """
    direct = re.compile(r'Icons\.(?:of|filled)\(\s*"([^"]*)"'
                        r'|Icons\.view\(\s*[^,()"]*,\s*"([^"]*)"')
    # `icon` is the 2nd argument of these, the 3rd of pillButton/primaryPill.
    second = re.compile(r'Ui\.(?:iconButton|softIconButton|circleButton|selectorChip'
                        r'|cardRow|iconBadge|iconLabel)\(\s*[^,()"]*,\s*"([^"]*)"')
    third = re.compile(r'Ui\.(?:pillButton|primaryPill)\(\s*[^,()"]*,\s*[^,()"]*,\s*"([^"]*)"')
    found = []
    for pattern in (direct, second, third):
        for match in pattern.finditer(source):
            name = next(group for group in match.groups() if group is not None)
            found.append((name, source[:match.start()].count("\n") + 1))
    return found


def test_every_icon_name_resolves():
    """
    Icons are looked up by string in a Map, and a miss falls back to the "help"
    glyph. So a typo ("chevron_left", "arrow-right", "warning") produces no
    compile error, no crash and no log line — just a permanently wrong glyph that
    nobody notices until a screenshot. Kotlin cannot check this; this test can.
    """
    icons = read("Icons.kt")
    entries = re.findall(r'put\(\s*"([^"]+)"', icons)
    known = set(entries)
    require(len(entries) == len(known),
            "Icons.kt defines the same name twice: "
            f"{sorted({n for n in known if entries.count(n) > 1})} "
            "(buildMap keeps the LAST one, silently discarding the first path)")
    require("help" in known, 'Icons.kt has no "help" glyph, which is the unknown-name fallback')

    unresolved = []
    for path in sorted(SRC.glob("*.kt")):
        for name, line in _icon_names_used(path.read_text(encoding="utf-8")):
            if name not in known:
                unresolved.append(f"{path.name}:{line} \"{name}\"")
    require(not unresolved,
            "icon names with no entry in Icons.kt (these render the 'help' glyph "
            f"forever, with no error): {unresolved}")


def test_app_is_branded_vega():
    """
    The rebrand must be complete in everything a USER can see, while every
    persisted identifier keeps its old spelling — a renamed SharedPreferences
    file or keystore alias would silently orphan the user's API keys and chats.
    So this bans the display forms (`Vepro Code`, `VeproCode`) and deliberately
    tolerates the snake/dotted ones (vepro_prefs, vepro_apikey_v1,
    vepro_agent_run, vepro_chat_id, vepro_code_card, vepro-llm-io,
    com.vepro.code, ic_stat_vepro).
    """
    strings = read_root("res/values/strings.xml")
    require(">Vega<" in strings,
            "res/values/strings.xml no longer sets the launcher label to Vega")
    fa = read("Fa.kt")
    # ONE in-app product name, in all three tables.
    #
    # There used to be two keys: HEADER_BRAND said "Vepro Agent" and APP_NAME said
    # "Vega Agent", so the header disagreed with the drawer heading and the About row
    # in the same build. HEADER_BRAND is gone and APP_NAME is the single name. The
    # launcher label stays "Vega" — that is the package's identity, checked above.
    #
    # Count the ASSIGNMENTS, not loose occurrences: the name also appears inside
    # SVC_TITLE, so a bare substring count would pass with the field reverted.
    #
    # ONE assignment now, not three. The table used to carry every string three
    # times over — a field default, an English overlay and a Persian restore — and
    # this contract existed to stop the three drifting apart. v1 is English-only
    # and the table is a single set of immutable vals, so there is exactly one
    # place the product name can be written and nothing left to drift.
    named = re.findall(r'val APP_NAME: String get\(\) = "Vega Agent"', fa)
    require(len(named) == 1,
            "Fa.APP_NAME must be \"Vega Agent\" in BOTH languages, declared exactly "
            f"once as an unconditional getter; found {len(named)}. The product name is "
            "an identifier, not prose — a `if (farsi)` here would translate the brand.")
    require("HEADER_BRAND" not in fa,
            "the second, disagreeing brand string is back")
    stale = re.compile(r"Vepro[ ]Code|VeproCode")
    offenders = []
    for path in sorted(list((ROOT / "src").rglob("*.kt")) + list((ROOT / "res").rglob("*.xml"))):
        for index, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
            if stale.search(line):
                offenders.append(f"{path.relative_to(ROOT)}:{index + 1}")
    require(not offenders, f"the old display brand is still present: {offenders}")


def test_watermark_tracks_the_screen_state():
    """
    The watermark is a state indicator, not decoration: it lifts above centre,
    shrinks when the keyboard takes the lower half, and disappears once the
    conversation starts (Grok's empty-state behaviour).
    """
    main = read("MainActivity.kt")
    require("fun updateWatermark" in main, "the watermark has no state driver")
    for token in ("WATERMARK_LIFT", "WATERMARK_FILL", "WATERMARK_MIN_SCALE"):
        require(token in main, f"{token} missing — the watermark cannot change state")
    require("keyboardUp" in main, "the keyboard state is never tracked")
    require("WindowInsets.Type.ime()" in main, "the IME inset is never read")
    body = _function_body(main, "    private fun updateWatermark() {")
    require("View.GONE" in body,
            "the watermark is not removed once the conversation has begun")
    require("updateWatermark()" in _function_body(main, "    private fun renderAll() {"),
            "renderAll does not re-evaluate the watermark")

    # The mark is fitted to the space that ACTUALLY exists, not to a fraction of
    # the screen. Deriving it from displayMetrics plus the IME inset is what let
    # it sit full-size behind the keyboard: the inset is unreliable below API 30
    # and Android 15 stops resizing the window altogether.
    require("fun watermarkBand" in main,
            "the free band is never measured — the mark cannot fit itself to it")
    band = _function_body(main, "    private fun watermarkBand(): FloatArray? {")
    require("getLocationInWindow" in band,
            "the band is not measured from real view geometry")
    require("messagesScroll" in band,
            "the band ignores the transcript viewport, which is the free space")
    require("welcomeBlock" in band,
            "the band ignores the suggestion rows, so the mark can overlap them")
    require("scaleX(scale)" in body and "available" in body,
            "the mark does not scale to the measured band")

    # Every layout change re-places it, including the ones no inset reports.
    require("addOnLayoutChangeListener" in main,
            "nothing re-places the mark when the transcript viewport changes")


def test_watermark_motion_is_jump_free():
    """
    The mark must be TOP-anchored, not centred.

    `adjustResize` shrinks the content frame when the keyboard opens, so a
    CENTRED mark is re-centred inside the smaller frame on that layout pass —
    an instant, un-animated jump of half the keyboard's height, with the
    translationY tween then animating a second, smaller distance on top of it.
    A top-anchored view cannot be moved by a height change, so every pixel of
    travel belongs to the animation.
    """
    main = read("MainActivity.kt")
    build = _function_body(main, "    private fun buildUi() {")
    require("markLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL" in build,
            "the watermark is not top-anchored — a window resize will jump it")
    require("markLp.gravity = Gravity.CENTER" not in build,
            "the watermark is centred again; adjustResize will jump it")

    # The resting origin is pinned in one place, and re-pinned on a resize.
    require("fun syncWatermarkGeometry" in main,
            "nothing re-pins the mark's resting position after a screen resize")
    require("watermarkRestCentre" in main, "the mark has no fixed resting origin")
    config = _function_body(main, "    override fun onConfigurationChanged(configuration: Configuration) {")
    require("syncWatermarkGeometry()" in config,
            "rotation leaves the mark at a stale portrait resting position")

    # The keyboard is measured, not merely detected.
    require("imeInsetPx" in main,
            "the keyboard is still a boolean — the mark cannot target the visible band")
    body = _function_body(main, "    private fun updateWatermark() {")
    require("watermarkRestCentre" in body,
            "the mark's travel is not measured from its fixed resting origin")
    require("imeInsetPx" in body,
            "updateWatermark ignores the actual keyboard height")
    # Both directions animate, and both are eased.
    require(body.count("setInterpolator") >= 2,
            "one of the watermark's two branches animates without an interpolator")

    # The inset listener must NOT gate the update on the height having changed.
    # imeInsetPx outlives buildUi(), so after a rebuild with the keyboard already
    # open the next pass reports the same height, the gate rejects it, and the
    # fresh mark stays parked at full size behind the keyboard.
    watcher = _function_body(
        main, "    private fun installInsetWatcher(root: FrameLayout, scrim: View) {")
    require("if (ime != imeInsetPx)" not in watcher,
            "the watermark update is gated on the IME height changing")
    require("updateWatermark()" in watcher,
            "the inset pass never re-places the watermark")

    # Idempotent, because the layout listener fires on every frame of the
    # keyboard animation and restarting the tween each time makes it crawl.
    require("WATERMARK_EPSILON_PX" in body,
            "updateWatermark restarts its animation even when nothing moved")


def test_removed_documents_suggestion_is_gone():
    """The Documents-folder suggestion was cut; no half-removal may survive."""
    main = read("MainActivity.kt")
    fa = read("Fa.kt")
    require("SUG_2" not in fa, "Fa.SUG_2 still declared")
    require("SUG_2" not in main, "the empty state still renders a third suggestion")
    for survivor in ("SUG_1", "SUG_3"):
        require(survivor in fa and survivor in main,
                f"{survivor} was removed too — only SUG_2 should be gone")


def test_light_theme_rows_are_visible_on_a_sheet():
    """
    A `flatCard` row on a `Sheet` panel is SURFACE on SURFACE — a 1.00:1 fill
    ratio behind a 1.17:1 hairline, i.e. invisible on the light palette.
    """
    theme = read("Theme.kt")
    main = read("MainActivity.kt")
    require("fun sheetRow(" in theme, "no dedicated treatment for a row on a sheet")
    require("roundStroke(SURFACE, BORDER_HI" in theme,
            "sheetRow does not use the stronger edge, so it stays invisible")
    require("Theme.flatCard(" not in main,
            "a sheet row still uses flatCard and cannot be seen on the light theme")


def test_icon_stroke_is_one_width_on_screen():
    """
    Ui.STROKE claims to be the app's single outline width. It only is if the
    canvas scale is divided back out — otherwise the rendered width tracks the
    glyph's size (measured: 1.11dp at 14dp, 1.74dp at 22dp).
    """
    icons = read("Icons.kt")
    # The canvas scale must be divided back out, so the width that reaches the
    # screen is a DP figure rather than a fraction of the glyph's box.
    require("strokeVp * Theme.DENSITY" in icons,
            "the icon stroke is no longer density-corrected")
    require("snapped / scale" in icons,
            "the icon stroke still scales with the glyph size")
    # Crispness: a fractional origin or width is drawn as two half-lit pixel
    # columns instead of one solid one, which is what made the set look blurry.
    require("Math.round(box.left" in icons,
            "the glyph origin is not snapped to the pixel grid")
    # HALF pixels, not whole ones. Whole-pixel snapping made every size from 14dp to
    # 24dp render at the same 4px at 3x density, which quantised the optical ramp out
    # of existence and made the nominal width decorative.
    require("Math.round(onScreen * 2.0f)" in icons,
            "the stroke width is not snapped to the half-pixel grid")
    # The optical ramp is allowed, but it may only ever LIGHTEN — in either
    # direction. The ceiling is exactly 1.0, so the nominal width is the heaviest any
    # glyph is ever drawn, and the floor stays bounded so a small glyph cannot vanish.
    require("MAX_OPTICAL" in icons and "MIN_OPTICAL" in icons,
            "the optical stroke ramp is unbounded")
    for token in ("MAX_OPTICAL = 1.0", "MIN_OPTICAL = 0.84", "RISE_PER_DP", "FALL_PER_DP"):
        require(token in icons, f"the optical ramp lost its bound ({token} expected)")

    # ONE width means one width. Five sites used to hard-code 1.9f — 42% heavier
    # than the constant, and one of them was every sheet header glyph in the app.
    for name in ("MainActivity.kt", "SettingsActivity.kt", "Sheet.kt", "Ui.kt",
                 "TrailView.kt", "WorkflowView.kt", "FileBrowser.kt",
                 "MarkdownRenderer.kt"):
        source = strip_comments(read(name))
        for hit in re.findall(r"Icons\.of\([^()]*?,\s*(\d+\.\d+f)\s*\)", source):
            require(False, f"{name} hard-codes an icon stroke width: {hit}")
    theme = read("Theme.kt")
    require("var DENSITY" in theme, "Theme does not cache the density Icons needs")
    # The positional-matching trap: Icons.of's first parameter must stay the name.
    require("fun of(name: String" in icons,
            "Icons.of gained a leading parameter — check_ui and the icon test "
            "match names POSITIONALLY and would silently stop seeing any icon")


def test_status_strip_does_not_cover_the_drawer_scrim():
    """
    The strip paints an opaque fill. Added after the scrim it left a bright
    undimmed band across the top while the rest of the window dimmed.
    """
    main = read("MainActivity.kt")
    build = _function_body(main, "    private fun buildUi() {")
    strip_at = build.index("statusScrim = barStrip")
    scrim_at = build.index("drawerScrim = scrim")
    require(strip_at < scrim_at,
            "the status strip is still added above the drawer scrim")


def test_stream_reveal_stops_when_nothing_animates():
    """
    The reveal used to re-arm its 16ms invalidate loop unconditionally, so the
    first flush started a 60fps repaint that ran until the end of the turn.
    """
    reveal = read("StreamReveal.kt")
    require("animatingUntil" in reveal,
            "the frame loop has no stopping condition")
    require("SystemClock.uptimeMillis() < animatingUntil" in reveal,
            "the tick still re-arms itself with nothing left to animate")


def test_code_copy_reads_the_live_body():
    """
    Streaming mutates the body TextView in place, so a captured string is
    whatever the FIRST flush held — copying mid-stream truncated the block.
    """
    renderer = read("MarkdownRenderer.kt")
    require("bodyRef" in renderer,
            "the code card's copy button has no handle on the live body")
    require('copyToClipboard(context, "code", payload)' in renderer,
            "the copy button still copies the captured build-time string")


def test_storage_prompt_is_first_on_screen():
    """It is the first thing that needs answering, so it is the first thing shown."""
    main = read("MainActivity.kt")
    require("headerPermRow" in main, "no storage prompt pinned under the header")
    build = _function_body(main, "    private fun buildUi() {")
    header_at = build.index("column.addView(buildHeader())")
    perm_at = build.index("headerPermRow = topPerm")
    require(perm_at > header_at, "the storage prompt is not directly under the header")
    # and not asked twice on the same screen
    welcome = _function_body(main, "    private fun buildWelcome(): View {")
    require("permRow()" not in welcome,
            "the empty state still shows a second copy of the storage prompt")


def test_sheets_animate_out():
    """A surface that glides in and then vanishes reads as a glitch."""
    sheet = read("Sheet.kt")
    require("fun dismiss(immediate: Boolean)" in sheet, "no animated sheet exit")
    require("withEndAction { hardDismiss() }" in sheet,
            "the exit animation never actually dismisses the dialog")
    require("sheet.dismiss(true)" in sheet,
            "dismissAll must be immediate — a dying Activity has no more frames")
    main = read("MainActivity.kt")
    require("R.anim.settings_enter" in main,
            "the settings screen still uses the flat platform cross-fade")


def test_a_run_never_ends_on_a_broken_tool_call():
    """
    THE regression that produced "it stops in the middle of a web search".

    The model emits a ```json tool call, one character of it is invalid, so
    `parseToolCall` returns null — and because the message also held prose, the
    old loop treated that as a FINISHED run and called onComplete(). From the
    outside the agent simply stopped mid-task, next to a raw JSON card it had
    just printed.

    A malformed call is a typo, not a decision to stop.
    """
    engine = read("AgentEngine.kt")
    require("fun looksLikeAttemptedCall" in engine,
            "nothing distinguishes a fumbled tool call from a final answer")
    require("MAX_CALL_REPAIRS" in engine, "a broken tool call is not retried")
    require("NUDGE_REPAIR_CALL" in engine,
            "the model is never told WHY its tool call was rejected")
    body = _function_body(engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    require("looksLikeAttemptedCall(message.content)" in body,
            "the run loop does not check for an attempted tool call before finishing")
    # The repaired step must not be left in the transcript as raw JSON.
    require("dropMessage(chat, message)" in body,
            "the unparseable step stays in the chat and gets replayed as an answer")


def test_a_stream_failure_does_not_abandon_the_task():
    """
    LlmClient retries the REQUEST; nothing retried the STEP. One dropped
    connection mid-task ended the whole run with an error card and no way to
    carry on.
    """
    engine = read("AgentEngine.kt")
    require("MAX_FAULT_RECOVERIES" in engine, "a stream failure is not recovered")
    require("fun faultBackoffMs" in engine, "faults are retried with no backoff")
    require("NUDGE_AFTER_FAULT" in engine,
            "the model is not told to resume after a transient failure")
    body = _function_body(engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    require("faults < MAX_FAULT_RECOVERIES" in body,
            "the stream-error branch still terminates the run immediately")
    # A thrown step is a tool result, not the end of the world.
    require("the previous step failed with" in engine,
            "a thrown step is not handed back to the model as a recoverable error")


def test_prose_that_promises_more_work_is_probed():
    engine = read("AgentEngine.kt")
    require("fun promisesMore" in engine,
            "a preamble is still accepted as a final answer")
    require("MAX_FINISH_PROBES" in engine, "the finish probe is unbounded")
    require("PROMISE_MARKERS" in engine, "no phrases mark an announcement of work")
    # Narrow on purpose: it must never fire on a long, genuine answer.
    require("PROMISE_MAX_CHARS" in engine,
            "promisesMore has no length guard and can fire on a real answer")


def test_a_tool_call_is_never_visible():
    """
    `stripToolCalls` used to hide only a COMPLETE, VALID call. The fence regex
    needs a closing ```, so for the whole time a call was streaming there was
    nothing to strip and the renderer drew a live "json" code card; a call with
    a syntax error never became strippable at all and stayed on screen for good.
    """
    engine = read("AgentEngine.kt")
    require("fun cutOpenCall" in engine,
            "an unterminated tool call is never cut from the visible text")
    require("looksLikeCallBody" in engine,
            "a complete-but-invalid tool call still renders as a json card")
    body = _function_body(engine, "        fun stripToolCalls(raw: String?): String {")
    require("cutOpenCall(stripped)" in body,
            "stripToolCalls does not remove the still-open tail")
    require("looksLikeCallBody(inner)" in body,
            "stripToolCalls still requires a call to PARSE before hiding it")
    # A partial object the model is halfway through typing counts too.
    require("looksLikeCallPrefix" in engine,
            "a half-typed unfenced tool call can still flash on screen")


def test_plan_steps_are_never_blank():
    """
    The sheet showed rows reading "--". A markdown rule (`---`) matched the old
    bullet test, and stripping one leading `-` left the literal text `--`; a bare
    `1.` reduced to an empty string the same way.
    """
    engine = read("AgentEngine.kt")
    require("fun isPlanStep" in engine, "there is no shared plan-step test")
    require("fun stripPlanBullet" in engine, "there is no shared bullet stripper")
    require("MIN_STEP_CHARS" in engine,
            "a step with no content left after stripping is still accepted")
    body = _function_body(engine, "        fun isPlanStep(line: String): Boolean {")
    require("isRule(line)" in body, "a markdown rule still counts as a plan step")
    require('line.startsWith("|")' in body,
            "a table separator row still counts as a plan step")
    # The sheet must use the SHARED parser, not a private regex of its own.
    main = read("MainActivity.kt")
    require("AgentEngine.isPlanStep(line)" in main,
            "the plan sheet still parses steps with its own rules")
    require("steps.removeAll { it.isBlankJava() }" in main,
            "nothing guarantees a blank step never reaches a row")
    require("NUMBERED_STEP" not in main,
            "the old plan-step regex is still present and can be used again")


def test_plan_mode_refuses_instead_of_escalating():
    """
    PLAN mode does not quietly become ACCEPT mode.

    It used to. The first mutating call set a run-local `escalated` flag, the run
    carried on in ACCEPT, and the mode pill kept saying "Planning" throughout —
    because the escalation was deliberately never persisted and the pill reads the
    persisted value. So a mode whose entire promise is "I will show you a plan and
    wait" began editing files after showing nothing, while the interface insisted
    it was still planning. The owner reported exactly that.

    The call is refused now. The user approves work by tapping Run plan, and THAT
    writes ACCEPT to preferences for real — visibly, with the pill following. So
    the escalation flag this contract used to protect should no longer exist, and
    its absence is the thing worth pinning.
    """
    engine = read("AgentEngine.kt")
    body = _function_body(
        engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    require("escalated = true" not in body,
            "PLAN mode still escalates itself into ACCEPT behind the user's back")
    require("val mode = prefs.mode()" in body,
            "the run's mode is no longer read straight from the preference")
    require("planBlocked" in body, "nothing blocks a mutating call in PLAN mode")
    require("planBlocked -> PLAN_REFUSAL" in body,
            "a blocked call does not return the refusal")
    # Written FOR the model, or it retries the same change three different ways —
    # which is what the silent escalation was papering over.
    require("PLAN_REFUSAL" in engine, "the refusal text is missing")
    require("Do NOT retry this call" in engine,
            "the refusal does not tell the model to stop retrying")
    require("Run plan" in engine,
            "the refusal does not say how the user grants permission")
    # Still never written back from inside the run: the plan sheet's guard reads
    # prefs.mode(), and writing it here is what used to cancel the plan.
    require("prefs.setMode(Prefs.MODE_ACCEPT)" not in body,
            "the run writes the mode back to preferences")

    # PLAN asks permission for every action, exactly as ACCEPT does. It used to ask
    # for none — the guard tested MODE_ACCEPT alone — so the mode that exists to
    # keep the user informed was the one that asked them least.
    require("private fun asksPermission" in engine,
            "there is no shared notion of a mode that asks before acting")
    require("MODE_PLAN == mode" in _function_body(
        engine, "    private fun asksPermission(mode: String): Boolean ="),
            "PLAN mode does not ask permission before acting")
    require("asksPermission(mode) && Tools.needsApproval(call.name)" in body,
            "the approval gate no longer covers both asking modes")
def test_the_run_reports_what_it_is_doing():
    """
    A run's only visible narration was a transient one-line pill, so the most
    prominent thing on screen while the agent worked was its raw JSON.
    """
    for name in ("Trail.kt", "TrailView.kt"):
        require(name in _sources(), f"{name} missing — the activity strip is gone")
    engine = read("AgentEngine.kt")
    require("fun onTrailChanged" in engine, "the engine cannot report activity")
    require("fun openTrailStep" in engine, "activity rows are never opened")
    require("fun closeTrailStep" in engine, "activity rows are never closed")
    require("fun noteStepProse" in engine,
            "a step's own prose never becomes the phase line")
    # The row must open BEFORE the work runs, or the strip reports the past.
    body = _function_body(engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    open_at = body.index("openTrailStep(it, call)")
    run_at = body.index("else -> tools.run(call.name, call.args, token, watcher)")
    require(open_at < run_at, "the activity row opens only after the tool has run")

    # Everything in the chain has to forward the event.
    for name in ("AgentBus.kt", "AgentService.kt", "MainActivity.kt"):
        require("onTrailChanged" in read(name), f"{name} does not forward onTrailChanged")

    # The strip animates on vsync and must stop when the run does.
    view = read("TrailView.kt")
    require("Choreographer" in view, "the strip's glyph is not vsync-driven")
    require("fun stop()" in view, "the strip's timer cannot be stopped")
    require("onDetachedFromWindow" in view,
            "the glyph keeps animating after leaving the window")
    # ...and collapse to a summary once the answer arrives.
    require("TRAIL_THOUGHT_FOR" in view, "the finished run has no summary line")
    require("collapsed" in read("Trail.kt"), "a trail cannot collapse")


def test_a_folded_step_is_not_drawn_twice():
    """
    A step's prose and its tool result live in the strip. Drawing them as bubbles
    as well is what put a raw ```json card in the middle of the conversation.
    """
    message = read("Message.kt")
    require("var isStep" in message, "a message cannot be marked as a folded step")
    require('json.put("isStep", true)' in message, "isStep is not persisted")
    require('json.put("trail"' in message, "the trail is not persisted with the chat")
    main = read("MainActivity.kt")
    render = _function_body(main, "    private fun renderAll() {")
    require("if (message.isStep) {" in render,
            "renderAll still draws folded steps as bubbles")
    require("message.trail?.let { addTrailRow(message, it) }" in render,
            "renderAll never draws the activity strip")
    final = _function_body(main, "    private fun finalizeStep(message: Message) {")
    require("message.isStep && box != null" in final,
            "a step's bubble is not removed when it folds into the strip")
    # The engine has to decide step-ness BEFORE the UI draws the row. Setting
    # isStep afterwards raced the main-looper queue: the bubble appeared or not
    # depending on scheduling, and the live transcript then disagreed with what a
    # rebuild would show.
    engine = read("AgentEngine.kt")
    body = _function_body(engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    parse_at = body.index("val call = parseToolCall(message.content)")
    classify_at = body.index("message.isStep = true", parse_at)
    # The FIRST announce after the parse — earlier ones belong to the stream-error
    # branch, which returns before ever reaching here.
    final_at = body.index("callback.onStepFinalized(message)", parse_at)
    require(classify_at < final_at,
            "isStep is set after the step has already been announced")
    # ...and every branch below reuses that one decision rather than recomputing.
    require("val attempted = call == null" in body,
            "the attempted-call decision is not made up front")
    require("val promising = call == null" in body,
            "the promise decision is not made up front")
    require("if (attempted && repairs < MAX_CALL_REPAIRS" in body,
            "the repair branch recomputes its own decision")

    # Substantial prose is kept when the call beside it has to be re-emitted.
    require("PROSE_KEEP_CHARS" in engine,
            "a malformed call still discards the prose around it")
    require("if (keptAttempt) {" in body,
            "the repair path drops the message unconditionally")

    # One strip per RUN, keyed on the trail — a re-homed trail must not add a
    # second one (a stall streak added up to seven).
    require("trailModel === trail" in main,
            "the activity strip is keyed on the owning message, not the run")
    require("trailModel !== trail" in main,
            "refreshTrail cannot tell a re-homed trail from a new one")

    # Every terminal path settles BOTH the trail and the board, or a delegated
    # phase keeps its spinner posting frames after the run is over.
    require("fun settleAll(interrupted: Boolean = false)" in body,
            "there is no single terminal settle path")
    # A run the user stopped must not settle as a success. Both models take the
    # flag, so history can say "stopped" rather than inventing a clean finish.
    require("settleAll(interrupted = true)" in body,
            "a stopped run still settles as though it had finished")
    # Counted across BOTH forms: four exits are user-stop paths and settle as
    # interrupted, the rest are ordinary finishes.
    require(body.count("settleAll(") >= 9,
            "some terminal exit still settles only part of the run's state")
    require(body.count("settleAll(interrupted = true)") >= 4,
            "not every stop path records the run as interrupted")
    require("var announced: Message?" in body,
            "publishing through a cleared owner silently drops the final state")


def test_dynamic_workflow_is_visible():
    """
    Dynamic Workflow really did delegate to real sub-agents, and showed the user
    nothing at all — the mode's entire promise is that the job gets split up.
    """
    require("WorkflowView.kt" in _sources(), "there is no workflow board")
    trail = read("Trail.kt")
    require("class Workflow" in trail, "there is no workflow model")
    require("class WorkPhase" in trail, "a workflow has no phases")
    require("fun claim(" in trail,
            "delegated tasks are not matched to the plan's phases")
    engine = read("AgentEngine.kt")
    require("fun buildWorkflow" in engine,
            "the board is never built from the plan the lead wrote")
    body = _function_body(engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    # The RUNNING flip lives with the AGENTS now, not in the run loop.
    #
    # It was in `run` because there was a single `activePhase` field — a shape that
    # can only ever describe one worker. With up to three sub-agents in flight each
    # owns its own row and marks it RUNNING on the thread that actually picked it
    # up, which is also what stops the board claiming six working agents against a
    # pool of three.
    require("row.status = WorkPhase.RUNNING" in _function_body(
        engine, "    private fun runSubAgents("),
            "a delegated phase never shows as running")
    require("private fun runSubAgents" in engine, "there is no concurrent launcher")
    require("MAX_PARALLEL_AGENTS" in engine,
            "nothing bounds how many sub-agents run at once")
    require("fun launch(" in trail, "the board cannot record a real delegation")
    require("fun liveCount()" in trail and "fun liveTopics()" in trail,
            "the board cannot say how many agents are working, or on what")
    # The card must count AGENTS, never plan lines. "Split across %s sub-agents"
    # was formatted with the number of bullets the model happened to type.
    require("WF_SUBTITLE" not in read("Fa.kt"),
            "the plan-line-count subtitle is back")
    require("workflow?.settle(interrupted)" in body,
            "a finished run can leave a phase spinning for ever")
    # Each sub-agent's step count is real, not invented.
    require("steps++" in engine, "a sub-agent's step count is never measured")
    view = read("WorkflowView.kt")
    require("PhaseSpinner" in view, "a running phase has no activity indicator")
    require("onDetachedFromWindow" in view, "the phase spinner is never stopped")


def test_the_reveal_is_vsync_aligned():
    """
    A 16ms Handler loop is approximately a frame on a 60Hz panel and nothing at
    all on the 90Hz and 120Hz panels most phones ship: the callback and the
    refresh drift in and out of phase, so a mathematically smooth ramp is
    SAMPLED unevenly. No duration tweak can fix that.
    """
    reveal = read("StreamReveal.kt")
    require("Choreographer" in reveal, "the reveal still runs on a fixed-delay loop")
    require("postFrameCallback" in reveal, "the reveal never asks for a real frame")
    require("removeFrameCallback" in reveal, "the frame callback is never released")
    # Checked against CODE, not the comment that explains why it is gone.
    code = "\n".join(
        line for line in reveal.splitlines()
        if not line.lstrip().startswith(("*", "//", "/*"))
    )
    require("postDelayed" not in code, "a fixed-delay tick survives in the reveal")
    require("Handler" not in code, "the reveal still owns a Handler")
    # The cascade must stay bounded, or a big flush leaves the tail lagging.
    require("MAX_CASCADE_MS" in reveal, "a large flush can cascade for ever")
    require("countWords" in reveal, "the cascade cannot size itself to the flush")


def _sources():
    import os
    return set(os.listdir(os.path.join(os.path.dirname(__file__), "..", "src", "com", "vepro", "code")))


def test_the_trail_is_not_shared_unguarded():
    """
    The engine mutates a trail on its worker thread; the UI reads it on the main
    thread. The chat's own message list is synchronized everywhere for exactly
    this reason, and the trail must match it — an escaped raw ArrayList here is a
    crash, not a style question.
    """
    trail = read("Trail.kt")
    # The collections must not be reachable directly.
    for field in ("private val stepList", "private val pageSet",
                  "private val domainList", "private val phaseList"):
        require(field in trail, f"{field} is not private — the collection escapes")
    for leak in ("val steps: MutableList", "val pages: MutableSet",
                 "val domains: MutableList", "val phases: MutableList"):
        require(leak not in trail, f"a raw collection is still public: {leak}")
    # Reads hand out copies; writes take the lock.
    require("fun steps(): List<TrailStep> = synchronized(this)" in trail,
            "steps() does not snapshot under the lock")
    require("fun pages(): List<String> = synchronized(this)" in trail,
            "pages() does not snapshot under the lock")
    require("fun phases(): List<WorkPhase> = synchronized(this)" in trail,
            "phases() does not snapshot under the lock")
    require("fun domains(): List<String> = synchronized(this)" in trail,
            "domains() does not snapshot under the lock")
    require("fun claim(name: String, fallbackTitle: String): WorkPhase = synchronized(this)"
            in trail, "claim() appends without the lock")
    # Cross-thread scalars must not be torn or cached. `phase` is in this list
    # because it was the one field that was NOT volatile while five engine sites
    # wrote it and two views read it.
    for volatile in ("startedAt", "endedAt", "running", "collapsed", "status",
                     "resultCount", "note", "phase", "filePath", "added", "removed",
                     "diffBefore", "diffAfter"):
        pattern = f"@Volatile\n    var {volatile}"
        require(pattern in trail, f"{volatile} is read cross-thread without @Volatile")
    # The views must read through the snapshots, never a live collection.
    for name in ("TrailView.kt", "WorkflowView.kt"):
        view = read(name)
        for leak in (".steps\n", ".pages.", ".phases\n", ".domains.", ".phases.isEmpty",
                     ".steps.size", ".phases.size"):
            require(leak not in view, f"{name} touches a live collection: {leak.strip()}")


def test_persian_mirrors_the_whole_chassis():
    """
    Persian is right-to-left, and this build treats it as one.

    The previous two builds got this wrong in opposite directions. v6 mirrored
    nothing and ran only the TEXT right-to-left, leaving a Persian screen with
    English furniture — a drawer sliding in from the wrong side of the language,
    an outgoing bubble on the wrong edge of the conversation. v1 removed Persian
    altogether. The owner asked for the mirror, so [Lang.direction] returns RTL
    and every screen's root takes its direction from it.

    What makes that a one-line switch rather than a rewrite is the layout code:
    every container positions its children relatively, and the physical
    `Gravity.LEFT`/`RIGHT` forms are banned outright — which is what this half of
    the contract still guards.
    """
    lang = read("Lang.kt")
    require("View.LAYOUT_DIRECTION_RTL" in _function_body(
        lang, "    fun direction(context: Context): Int = if (farsi(context)) {"),
            "the layout no longer mirrors for Persian")
    require("fun mirrored(context: Context)" in lang,
            "there is no single place asking whether the layout is mirrored")
    require("fun farsi(context: Context)" in lang,
            "nothing exposes which language is active")
    # One cache, dropped by Fa.apply, so direction and strings cannot disagree.
    require("fun invalidate()" in lang
            and "Lang.invalidate()" in _function_body(
                read("Fa.kt"), "    fun apply(context: Context) {"),
            "the direction cache and the string table are not refreshed together")

    # No screen may hard-code a physical edge.
    for name in ("MainActivity.kt", "SettingsActivity.kt", "FileBrowser.kt",
                 "TrailView.kt", "WorkflowView.kt"):
        source = strip_comments(read(name))
        for banned in ("Gravity.LEFT", "Gravity.RIGHT"):
            require(banned not in source, f"{name} hard-codes a physical edge: {banned}")

    # Every screen root takes its direction from Lang.
    for name, anchor in (("MainActivity.kt", "rootFrame.layoutDirection"),
                         ("SettingsActivity.kt", "panel.layoutDirection")):
        require(f"{anchor} = Lang.direction(this)" in read(name),
                f"{name} does not take its layout direction from Lang")

    # The outgoing bubble stays at the END edge, which is what mirrors it.
    main = read("MainActivity.kt")
    require("column.gravity = Gravity.END" in _function_body(
        main, "    private fun addUserRow(message: Message) {"),
            "the user's bubble is no longer anchored to the end edge")
def test_the_trail_opens_a_panel():
    """
    Tapping the strip used to expand it in place, which grew a list of rows in the
    middle of the transcript and pushed the answer around while it was being read.
    """
    view = read("TrailView.kt")
    require("var onOpenPanel" in view, "the strip cannot open a panel")
    require("var onOpenStep" in view, "an activity row cannot open its results")
    require("onOpenPanel" in _function_body(view, "    init {"),
            "tapping the strip does not open the panel")
    # The pages pill belongs in the panel, not loose in the transcript.
    require("paintSources" not in view, "the sources pill is still drawn in the strip")

    main = read("MainActivity.kt")
    require("private fun showThoughtsSheet" in main, "there is no Thoughts panel")
    require("private fun showWebResultsSheet" in main, "there is no results panel")
    require("private fun sourcesPill" in main, "the pages pill moved nowhere")
    thoughts = _function_body(main, "    private fun showThoughtsSheet(trail: Trail) {")
    require("sourcesPill(hosts)" in thoughts, "the panel does not show the sources")
    require("trail.steps()" in thoughts, "the panel does not list the activity")
    require("Intent.ACTION_VIEW" in _function_body(
        main, "    private fun openLink(url: String) {"),
            "a result does not open its source")

    # A FINISHED run that used no tools has nothing to narrate. A LIVE one always
    # does — this is the fix for "I send a message and nothing happens": a run that is
    # connecting, waiting or retrying has no tools and no reasoning yet, and the old
    # test therefore hid the only surface that could have said so, leaving the
    # transcript blank for the whole of a failing request.
    require("trail.didWork() || trail.hasThoughts() ||" in main
            and "(trail.running && !trail.isEmpty())" in main,
            "a live run can still be drawn as a blank transcript")
    require("if (!worthShowing)" in main, "the no-work strip is never removed")
    require("fun didWork()" in read("Trail.kt"),
            "a trail cannot report whether it worked")

    # The panel is worth opening: timings, outcomes and change counts, all of which
    # were recorded from the first version and none of which were ever displayed.
    require("private fun statsLine" in main, "the panel does not say what the run cost")
    require("private fun outcomeMark" in main, "a failed step looks like a successful one")
    require("private fun showDiffSheet" in main, "an edit cannot be inspected")
    require("fun duration(context: Context, ms: Long)" in read("TrailView.kt"),
            "per-step timings are recorded and never rendered")


def test_reasoning_lives_in_the_panel():
    trail = read("Trail.kt")
    require("const val THINK" in trail, "there is no row shape for reasoning")
    engine = read("AgentEngine.kt")
    require("fun flushThinking" in engine, "reasoning never becomes a row")
    require("thoughtCursor" in engine, "reasoning would be added to the trail twice")
    main = read("MainActivity.kt")
    # The review section is the ONLY home for reasoning now.
    #
    # This used to assert that the standalone card was suppressed WHEN a trail
    # existed, which left the model's thinking appearing in one of two completely
    # different places depending on whether the turn happened to call a tool. The
    # card is deleted outright, and the review row survives a tool-less turn
    # precisely so the thinking has somewhere to be.
    for banned in ("buildThinkingCard", "currentThinkCard", "currentThinkTv"):
        require(banned not in main,
                f"the separate reasoning card is back: {banned}")
    require("trail.hasThoughts()" in _function_body(
        main, "    private fun addTrailRow(owner: Message, trail: Trail) {"),
            "a turn that only reasoned drops its review row, so the reasoning is lost")
    require('Icons.of("neuron"' in main,
            "the reasoning row does not carry the reasoning mark")


def test_search_results_are_data():
    web = read("Web.kt")
    require("class SearchResult" in web, "results are still only a formatted string")
    require("fun searchDetailed" in web, "there is no structured search entry point")
    require("fun search(query: String?, token: CancellationToken): String =" in web,
            "the text-only path no longer delegates, so the two can disagree")
    tools = read("Tools.kt")
    require("interface Observer" in tools, "a tool cannot report anything but its text")
    require("observer?.onSearchResults" in tools, "search results are not forwarded")
    require("observer?.onProgress" in tools, "editing reports no progress")
    engine = read("AgentEngine.kt")
    require("object : Tools.Observer" in engine, "the engine ignores tool observations")
    require("trailStep.addResults(results)" in engine,
            "results never reach the row that produced them")


def test_a_conversation_row_offers_more_than_delete():
    main = read("MainActivity.kt")
    require("private fun showChatMenu" in main, "there is no conversation menu")
    for name in ("private fun showRenameChat", "private fun renameChat",
                 "private fun togglePinned"):
        require(name in main, f"missing conversation action: {name}")
    row = _function_body(main, "    private fun refreshChatList() {")
    require("showChatMenu(" in row, "the overflow does not open the menu")
    require("confirmDeleteChat(entryId" not in row,
            "the overflow is still a disguised delete button")
    # Pinning is real: stored, and ordered on.
    require("var pinned" in read("Chat.kt"), "a conversation cannot be pinned")
    store = read("ChatStore.kt")
    require("compareByDescending<Summary> { it.pinned }" in store,
            "pinned conversations are not ordered first")
    require('optBoolean("pinned"' in store, "the pin is not read back from disk")

    # It is an ANCHORED menu, not a bottom sheet. A modal surface that dims the app
    # and takes the full width is the wrong instrument for a three-line overflow on
    # one row of a list — and it made deleting anything cost two sheets.
    menu = _function_body(main, "    private fun showChatMenu(")
    require("Sheet(this)" not in menu, "the conversation menu is still a bottom sheet")
    require("card.translationX" in menu and "card.translationY" in menu,
            "the menu is not positioned against its anchor")
    require("private fun dismissChatMenu" in main, "the menu cannot be closed")
    # Same rule as the copy panel: no PopupWindow anywhere in this screen.
    require("PopupWindow(" not in strip_comments(main),
            "the conversation menu must not use a PopupWindow")
    # An overlay that outlives what it was anchored to floats over unrelated content.
    for host in ("    private fun closeDrawer() {", "    override fun onDestroy() {"):
        require("dismissChatMenu()" in _function_body(main, host),
                f"the menu is not closed from {host.strip()}")


def test_the_header_names_the_product():
    main = read("MainActivity.kt")
    body = _function_body(main, "    private fun refreshTitle() {")
    require("Fa.APP_NAME" in body, "the header does not show the product name")
    require("current.title" not in body,
            "the header still shows the conversation's own title")


def test_the_composer_ring_is_released():
    """
    The ring is drawn from the field's focus state and nothing ever took that focus
    away, so it stayed lit around a box the user had visibly stopped using.
    """
    main = read("MainActivity.kt")
    require("private fun releaseComposerFocus" in main,
            "nothing releases the composer's focus")
    body = _function_body(main, "    private fun releaseComposerFocus() {")
    require("requestFocus()" in body,
            "focus is only cleared, so the platform hands it straight back")
    require("hideKeyboard()" in body, "releasing focus leaves the keyboard up")
    require("MotionEvent.ACTION_DOWN" in main,
            "touching the transcript does not release the composer")


def test_the_permission_card_is_inset():
    main = read("MainActivity.kt")
    build = _function_body(main, "    private fun buildUi() {")
    require("topPermLp.marginStart" in build and "topPermLp.marginEnd" in build,
            "the storage card's edges still run into the screen")


def test_diffs_carry_hue():
    """
    The one deliberate exception to the monochrome palette: added and removed are
    opposites, not two amounts of one thing, and lightness alone cannot say so.
    """
    theme = read("Theme.kt")
    for token in ("DIFF_ADD", "DIFF_ADD_BG", "DIFF_DEL", "DIFF_DEL_BG"):
        require(f"var {token}: Int = 0" in theme, f"{token} is not declared")
    # Both palettes must define them, or one theme renders a colourless diff.
    for token in ("DIFF_ADD =", "DIFF_DEL ="):
        require(theme.count(token) >= 2, f"{token} is only set for one palette")
    renderer = read("MarkdownRenderer.kt")
    require("Theme.DIFF_ADD_BG" in renderer and "Theme.DIFF_DEL_BG" in renderer,
            "the diff card does not use the diff washes")
    require("Theme.GREEN_BG" not in renderer,
            "the diff card still routes through the greyscale wash tokens")


def test_icons_are_not_inked():
    """
    v3 raised the stroke to 1.7dp AND added up to 12% on small glyphs. Stacked, the
    14-18dp glyphs the interface is mostly made of came out looking inked.
    """
    ui = read("Ui.kt")
    require("const val STROKE = 1.34f" in ui, "the icon stroke is not 1.34dp")
    # The ramp has to run in BOTH directions, or the small sizes the interface is
    # built from carry a large glyph's stroke.
    icons = read("Icons.kt")
    require("RISE_PER_DP" in icons, "small glyphs get no optical relief")
    require("Math.round(onScreen * 2.0f).toFloat() / 2.0f" in icons,
            "whole-pixel snapping quantises the optical ramp away")
    # The ceiling is what keeps the ramp honest: it may lighten a glyph in either
    # direction and may never make one heavier than the nominal width.
    require("const val MAX_OPTICAL = 1.0f" in icons,
            "the optical ramp can still ADD weight to a glyph")
    require("1.0f - (ANCHOR_DP - sizeDp) * RISE_PER_DP" in icons,
            "the small-glyph correction adds weight instead of relieving it")
    # Snapping stays — that is what actually made them crisp — on the half-pixel
    # grid, so it no longer swallows the correction above.
    require("Math.round(onScreen * 2.0f)" in icons, "the stroke is no longer snapped")
    require("Math.round(box.left" in icons, "the glyph origin is no longer snapped")
    # A dot's size must not be its stroke width.
    require('put(\n            "more-vertical"' in icons or "ALWAYS_FILLED" in icons,
            "the overflow dots are still drawn as capped zero-length segments")
    main = read("MainActivity.kt")
    require("WATERMARK_ALPHA_DARK = 0.035f" in main,
            "the watermark is still too present")


def test_reasoning_is_only_hidden_when_it_is_reachable():
    """
    Suppressing the reasoning card is a TRADE: the text moves into the Thoughts
    panel. It is only a trade if the panel is reachable and populated.

    Two cases broke it. A turn that called no tool has a hidden strip and therefore
    no way into the panel. And a conversation saved by an earlier build has a trail
    with no reasoning rows in it at all. In both, suppressing the card deleted
    reasoning the user had just watched arrive.
    """
    trail = read("Trail.kt")
    require("fun hasThoughts()" in trail, "a trail cannot report whether it holds reasoning")
    main = read("MainActivity.kt")
    require("private fun owningTrail" in main,
            "nothing checks that the panel can actually show the reasoning")
    body = _function_body(main, "    private fun owningTrail(trail: Trail?): Boolean {")
    require("didWork()" in body and "hasThoughts()" in body,
            "reasoning is hidden without checking the panel is reachable AND populated")
    owned = _function_body(main, "    private fun ownedByTrail(message: Message): Boolean {")
    require("trail != null" not in owned,
            "ownedByTrail still treats the mere presence of a trail as ownership")


def test_the_header_is_written_in_one_place():
    """
    refreshTitle() was corrected, but four other paths painted the conversation
    title straight over the brand — a flicker on every chat open and every first
    send, and a Persian title in a header now forced left-to-right.
    """
    main = strip_comments(read("MainActivity.kt"))
    writes = [line for line in main.splitlines() if "titleView?.text" in line]
    require(len(writes) == 1,
            f"the header is written from {len(writes)} places, not one: {writes}")
    require("Fa.APP_NAME" in writes[0],
            "the single header write does not paint the product name")


def test_no_screen_infers_a_physical_edge_from_the_language():
    """
    Layout direction and language were the same question only while Persian
    mirrored the interface. Four affordances were still asking the wrong one, and
    all four pointed the wrong way in Persian.
    """
    lang = read("Lang.kt")
    require("fun chevronBack" in lang, "there is no back-pointing chevron helper")
    for name in ("MainActivity.kt", "SettingsActivity.kt", "FileBrowser.kt", "Ui.kt"):
        source = strip_comments(read(name))
        for line in source.splitlines():
            if "Lang.english" not in line:
                continue
            for marker in ("chevron", "setLayerInset", "toFloat()", "Gravity", "pivotX"):
                require(marker not in line,
                        f"{name} decides a physical edge from the language: {line.strip()}")


def test_a_late_observation_cannot_reach_a_closed_step():
    """
    Tools.run abandons a wedged daemon worker six seconds after cancellation, and
    that worker still holds the observer. An edit loop could therefore report
    progress after its step closed, after the run settled, even after the user
    opened another chat — at which point publishing appended the finished run's
    strip into the wrong transcript.
    """
    engine = read("AgentEngine.kt")
    require("private fun live(): Boolean = trailStep.status == TrailStep.RUNNING" in engine,
            "the tool observer has no liveness guard")
    require(engine.count("if (!live()) {") >= 2,
            "not every observer callback checks that its step is still open")


def test_continuation_reasoning_reaches_the_panel():
    """
    On a truncation resume the reasoning buffer is folded into settledThinking and
    cleared. Both flush points only ever see the CURRENT round, so everything
    thought in the earlier rounds was dropped — invisibly, because there is no
    longer a separate card showing the whole of it.

    The buffer is read through effectiveReasoning() now, which falls back to any
    inline <think> block in the body when the provider has no reasoning channel of
    its own. The ordering requirement is unchanged: flush before folding.
    """
    engine = read("AgentEngine.kt")
    body = _function_body(
        engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    flush_at = body.index("flushThinking(it, effectiveReasoning(reasoning, body), thoughtCursor)")
    fold_at = body.index("settledThinking.append(reasoning)")
    require(flush_at < fold_at,
            "the reasoning buffer is folded away before the panel has flushed it")
    require("thoughtCursor = 0" in body[fold_at:fold_at + 400],
            "the flush cursor is not reset after the buffer is cleared")


def test_the_destructive_row_looks_destructive():
    """
    Theme.RED equals Theme.TEXT in both palettes, so tinting delete with it drew
    the row identically to rename and pin.
    """
    main = read("MainActivity.kt")
    menu = _function_body(main, "    private fun showChatMenu(")
    require("Theme.DIFF_DEL" in menu,
            "the delete row is not tinted with a colour that is actually red")
    # Checked against CODE, not the comment explaining why RED is wrong here.
    require("Theme.RED" not in strip_comments(menu),
            "the delete row still uses the greyscale RED token")


def test_a_run_never_fails_in_silence():
    """
    Sending a message produced NOTHING: a key aimed at the wrong endpoint was
    rejected, and the engine's answer to a rejected request was six retries across
    about half a minute of widening backoff with the reason withheld until the budget
    ran out — over a transcript whose only status surface was hidden because the turn
    had used no tools.
    """
    engine = read("AgentEngine.kt")
    # Caught before a byte leaves the phone.
    require("Preflight.check(prefs.baseUrl(), prefs.apiKey(), prefs.model())" in engine,
            "nothing checks the configuration before the first request")
    require("private const val MAX_SILENT_RECOVERIES" in engine,
            "a run that has produced nothing still gets the full retry budget")
    silent = "val budget = if (silent) MAX_SILENT_RECOVERIES else MAX_FAULT_RECOVERIES"
    require(silent in engine, "the retry budget does not depend on whether anything was shown")
    # The reason is on the trail while it is still trying, not only afterwards.
    require("private fun noteFailure" in engine, "a failed request never reaches the trail")
    require("noteFailure(it, error)" in engine, "the failure is recorded but never attached")
    require("Fa.RUN_RETRY_N.format" in engine, "a retry does not say which attempt it is on")
    # And the strip is reachable from the moment of send.
    require("Fa.RUN_CONNECTING" in engine, "a run does not report that it is connecting")

    main = read("MainActivity.kt")
    require("trail.didWork() || trail.hasThoughts() ||" in main
            and "(trail.running && !trail.isEmpty())" in main,
            "a live run can still be drawn as a blank transcript")

    # The impossible cases are named, and the ambiguous ones are deliberately not.
    pre = read("Preflight.kt")
    for name in ("NO_KEY", "KEY_ENDPOINT_MISMATCH", "NO_MODEL", "BAD_ENDPOINT"):
        require(f"const val {name}" in pre, f"Preflight cannot report {name}")
    require("keyVendor.isNotEmpty() && endpointVendor.isNotEmpty()" in pre,
            "Preflight would block a self-hosted gateway, which is a legitimate setup")
    # Ordering trap: every sk- form starts with "sk-", so the specific ones come first.
    body = pre[pre.index("fun vendorOfKey"):]
    for earlier, later in (('"sk-ant-"', '"sk-"'), ('"sk-or-"', '"sk-"')):
        require(body.index(earlier) < body.index(later),
                f"{earlier} is tested after {later}, so it can never match")


def test_the_agent_loop_is_actually_tested():
    """
    A build shipped in which sending a message produced no answer at all and every
    suite passed, because 665 assertions covered the helpers, the parsers and the HTTP
    client and not one of them ever called AgentEngine.run.
    """
    loop = read_test("AgentLoopTests.kt")
    require("AgentEngine(ctx, Prefs(ctx)).run(" in loop,
            "the loop tests do not run the real engine")
    for name in ("testAPlainAnswerReachesTheUser", "testARejectedKeyIsReportedAtOnce",
                 "testAMisconfiguredKeyNeverLeavesThePhone",
                 "testAToolCallRunsAndTheAnswerFollows"):
        require(f"fun {name}" in loop, f"the loop suite is missing {name}")
    # It has to be wired into the standard run, not left as something to remember.
    offline = read_root("tools/build-offline.sh")
    require("com.vepro.code.AgentLoopTests" in offline,
            "the loop suite is never executed by the offline pipeline")
    runner = read_root("runtests.sh")
    require("build-offline.sh\" --tests" in runner,
            "the standard run does not execute the behavioural suites")


def test_a_secret_is_never_thrown_away():
    """
    `encrypt` returned null when the keystore was unavailable and `setApiKey` turned
    that into false, so on those devices a user could never save an API key at all —
    every request went out unauthenticated and nothing on screen explained why.
    """
    store = read("SecureStore.kt")
    require("fun encrypt(plain: String?): String {" in store,
            "encrypt can still return null, which is read as 'do not save this'")
    require("private const val PLAIN" in store, "there is no fallback storage form")
    require("fun encrypted(stored: String?)" in store,
            "nothing can tell a protected value from an unprotected one")
    prefs = read("Prefs.kt")
    require("fun apiKeyIsEncrypted()" in prefs, "Prefs cannot report the storage form")
    require("SecureStore.encrypt(value ?: \"\") ?: return false" not in prefs,
            "setApiKey still refuses to save a key it cannot encrypt")
    settings = read("SettingsActivity.kt")
    require("!prefs.apiKeyIsEncrypted()" in settings,
            "an unprotected key is stored without ever saying so")


def test_settings_answers_the_connection_question_first():
    """
    Settings still answers "is this thing actually connected?", but no longer by
    devoting the top of the screen to it.

    It used to be a bordered card ~152dp tall — growing past 200dp once Preflight
    had something to say — sitting above the provider heading, which made the
    tallest object on the screen the one the user had least often come to use. It
    is a single row now, at the bottom of the provider card, directly under the
    three fields it actually tests. Same behaviour, a quarter of the height, and
    beside the thing it is about.
    """
    settings = read("SettingsActivity.kt")
    require("private fun connectionCard" in settings, "settings has no connection row")
    require("private fun refreshConnectionCard" in settings,
            "the connection summary cannot follow the fields")
    require("Preflight.check(base, key, model)" in settings,
            "settings never names a configuration that cannot work")
    require("private fun runTest" in settings, "the connection can no longer be tested")
    # Beside the fields it tests, not above the heading.
    require(settings.index("Ui.sectionLabel(this, Fa.SET_PROVIDER)") <
            settings.index("connectionCard()"),
            "the connection row is not inside the provider group")
    # And it must stay a row, not grow back into a card of its own.
    require("Fa.SET_TEST_SHORT" in settings,
            "the compact test affordance is gone; the tall card is probably back")

def test_the_chassis_is_never_mirrored():
    """
    One chassis, left to right, everywhere.

    The suggestion rows were the single exception: they flipped to RTL on the
    Persian interface, on the reasoning that a suggestion is a sentence the user is
    about to say and its glyph reads as that sentence's first character. With one
    interface language there is no second case to serve, and the exception was the
    only thing in the app still deriving a physical edge from a language.
    """
    main = strip_comments(read("MainActivity.kt"))
    require("row.layoutDirection = Lang.direction(this)" in _function_body(
        read("MainActivity.kt"),
        "    private fun suggestionRow(icon: String, label: String): View {"),
            "the suggestion row sets its direction by hand instead of asking Lang")
    require("layoutDirection = View.LAYOUT_DIRECTION_RTL" not in main,
            "something is mirroring the layout again")

def test_the_logo_is_rendered_at_full_coverage():
    """
    4x4 supersampling gives 16 coverage levels per pixel, which is what made the
    star's edges read as ragged rather than drawn.
    """
    logo = read_root("tools/mklogo.py")
    require("SS = 16" in logo, "the logo is still rendered at 4x4 supersampling")
    require("FLATTEN = 64" in logo, "the logo's curves are still flattened coarsely")


def test_the_app_still_renders_persian_text():
    """
    The INTERFACE is English. The CONTENT is not, and must not become so.

    Removing the second interface language is one change; removing the ability to
    read and write Persian would be a completely different one, and this is the
    line between them. Everything checked here serves text the user or the model
    wrote, not a label the app ships.
    """
    # The faces Persian is set in still ship and are still loaded unconditionally.
    theme = read("Theme.kt")
    require("Vazirmatn" in theme, "the Persian typeface is no longer loaded")
    for weight in ("Regular", "Medium", "SemiBold", "Bold"):
        require((ROOT / "assets" / "fonts" / f"Vazirmatn-{weight}.ttf").is_file(),
                f"assets/fonts/Vazirmatn-{weight}.ttf was deleted")

    # The manifest still permits relative layout resolution.
    manifest = read_root("AndroidManifest.xml")
    require('android:supportsRtl="true"' in manifest,
            "supportsRtl is off, so relative padding and FIRST_STRONG stop resolving")

    # Prose decides its own direction from its own first strong character. This is
    # the single mechanism that makes a Persian answer lay out right-to-left.
    for name, least in (("MainActivity.kt", 6), ("MarkdownRenderer.kt", 1)):
        source = read(name)
        found = source.count("TEXT_DIRECTION_FIRST_STRONG")
        require(found >= least,
                f"{name} has {found} FIRST_STRONG prose views, expected at least {least}")

    # A Persian keyboard types Persian numerals into number fields.
    settings = read("SettingsActivity.kt")
    require("fun normalizeDigits" in settings and "normalizeDigits(value.trimJava())" in settings,
            "Persian and Arabic numerals no longer parse in the number fields")

    # The model is told to mirror the user rather than to use a fixed language.
    engine = read("AgentEngine.kt")
    require("same language the user wrote to you in" in engine,
            "the model is no longer told to answer in the user's own language")
    require('if (prefs.language() == "en")' not in engine,
            "the reply language is pinned to a deleted preference again")

    # Model OUTPUT is still parsed as Persian where it has to be.
    trail = read("Trail.kt")
    require("c == '\u200c' || c == '\u0640'" in trail,
            "ZWNJ/tatweel normalisation is gone, so Persian plan lines stop matching tasks")


def test_the_review_heading_is_fixed():
    """
    The review row says "Reviewing request", and nothing the model writes can
    change that.

    The row's heading used to render `Trail.phase`, which the engine filled from
    the first line of the model's own preamble — so the heading of the review
    section became whatever the model happened to open with, changing sentence to
    sentence and turn to turn. The one label on the screen that should be a fixed
    landmark was the least stable text in the app.

    The prose is still kept. It goes to the panel, where a sentence about what is
    being done belongs, and where it cannot masquerade as a section title.
    """
    view = read("TrailView.kt")
    paint = strip_comments(_function_body(view, "    private fun paint() {"))
    require("value.phase" not in paint,
            "the model's prose can reach the review heading again")
    require("Fa.TRAIL_THINKING" in paint and "Fa.TRAIL_THOUGHT_FOR" in paint,
            "the review heading is not one of the two fixed strings")

    fa = read("Fa.kt")
    require('val TRAIL_THINKING: String get()' in fa and '"Reviewing request"' in fa,
            "the fixed review heading was reworded")

    # The prose still has somewhere to go.
    engine = read("AgentEngine.kt")
    require("private fun noteStepProse" in engine,
            "the model's step prose is no longer recorded at all")


def test_the_review_row_is_marked_with_a_lamp():
    """
    The review row's glyph is a lamp, and it is lit only while the model is
    working.

    It was a 3x3 grid of dots collapsing to a thin open ring — a circle sitting in
    front of a sentence, which is the least specific mark available for the one
    row on the screen whose subject is a request being thought about.
    """
    icons = read("Icons.kt")
    for name in ("bulb", "bulb-on", "neuron"):
        require(f'"{name}",' in icons or f'put("{name}"' in icons,
                f"the {name} glyph is missing")

    view = read("TrailView.kt")
    glyph = view[view.index("class PulseGlyph"):]
    require('Icons.of("bulb-on"' in glyph and 'Icons.of("bulb"' in glyph,
            "the review glyph is not drawn from the lamp icons")
    for gone in ("DOT_RADIUS", "for (row in 0 until 3)", "drawSummary"):
        require(gone not in strip_comments(glyph),
                f"the old dot-grid glyph is still there: {gone}")
    # It must stop when the run does: an idle 60fps animation is a battery bug.
    require("Choreographer.getInstance().removeFrameCallback" in glyph,
            "the lamp animation never stops")


def test_a_report_is_handed_over_not_swapped():
    """
    Status text that is rewritten while the user is watching transitions.

    Every one of these used to change with a bare `text =`, which at a glance is
    indistinguishable from a glitch: a sentence the eye is still reading is simply
    a different sentence, with nothing to say that a step completed.
    """
    ui = read("Ui.kt")
    require("fun swapText(label: TextView, next: CharSequence)" in ui,
            "there is no text hand-off helper")
    body = _function_body(ui, "    fun swapText(label: TextView, next: CharSequence) {")
    require("current.toString() == next.toString()" in body,
            "setting the same text twice would re-animate, so the heading flickers")

    view = read("TrailView.kt")
    require("Ui.swapText(" in view, "the review heading still snaps between states")

    main = read("MainActivity.kt")
    running = _function_body(
        main, "    private fun showRunningIndicator(tool: String, detail: String?) {")
    require("Ui.swapText(" in running,
            "the running row is still destroyed and rebuilt on every update")
    require("existing.parent === container" in running,
            "the running row is not reused, so its pulse restarts every step")


def test_a_panel_can_be_dragged_away():
    """
    Every sheet closes by being pulled down.

    The grabber at the top has always said "this panel drags" and was decorative:
    the only ways out were the back gesture and a tap on the scrim, neither of
    which is what a thumb already resting on a bottom sheet reaches for.

    Arbitration matters as much as the gesture. The content scrolls, so a downward
    drag is ambiguous between "scroll up through the list" and "throw this away" —
    scrolling wins while there is anything above to reveal.
    """
    sheet = read("Sheet.kt")
    require("private inner class DragScroll" in sheet,
            "the sheet's scroller does not handle the drag")
    require("override fun onInterceptTouchEvent" in sheet,
            "the gesture is not arbitrated against the content's own scrolling")
    take = _function_body(sheet, "        private fun shouldTakeOver(ev: MotionEvent): Boolean {")
    require("scrollY == 0" in take,
            "a drag can dismiss the sheet mid-list, so scrolling up throws it away")
    require("> slop" in take, "any touch becomes a drag, so taps would dismiss the sheet")
    require("!cancelable" in take,
            "a sheet the app is blocked on could be flicked away without answering it")
    require("VelocityTracker" in sheet, "a fling is treated the same as a slow drag")
    require("private fun dismissByDrag" in sheet,
            "a dragged sheet is dismissed by the standard exit, which pulls it back up")
    require("private fun settle" in sheet,
            "an abandoned drag never returns the sheet to rest")


def test_a_plan_sheet_has_something_in_it():
    """
    The plan sheet opens when it has a plan or a question, and not otherwise.

    It used to open on `hasDecision || steps >= 1 || length >= 60`, and that last
    clause is why it opened on almost everything: sixty characters is two
    sentences, so any plan-mode answer longer than a greeting produced a modal
    panel titled "Proposed plan" containing no plan and no question. Being asked to
    dismiss an empty sheet trains you to dismiss it unread — which is exactly when
    it matters.
    """
    main = read("MainActivity.kt")
    gate = _function_body(main, "    private fun maybeShowPlan() {")
    require("visible.trimJava().length >= 60" not in gate,
            "any answer longer than two sentences still opens the plan sheet")
    require("hasDecision || wantedChanges || steps >= MIN_PLAN_STEPS" in gate,
            "the sheet does not require an actual plan, an actual question, or a "
            "refused change")
    # A run that ASKED to change something is the strongest reason to open this
    # sheet: the model has work it wants permission for, and this panel is where
    # permission is given. The same signal used to mean the opposite — it recorded
    # that a PLAN run had silently escalated and was already executing, and the
    # sheet stayed shut so it would not pop over its own finished work.
    require("AgentEngine.lastRunWantedChanges" in gate,
            "a run refused a change does not offer its plan for approval")
    require("private const val MIN_PLAN_STEPS = 2" in main,
            "a one-line answer still counts as a plan")

    # The header names whichever of the two things the sheet actually holds.
    show = _function_body(main, "    private fun showPlanSheet(plan: String) {")
    require("if (steps.isEmpty() && question != null)" in show,
            "a bare question is still titled as a proposed plan")

    # And the model is no longer told to manufacture a question every turn.
    engine = read("AgentEngine.kt")
    require("still end with the decision block" not in engine,
            "plan mode still mandates a question on every single turn")
    require("Do NOT invent a question so you have something to ask" in engine,
            "nothing tells the model to ask only when there is a real choice")


def test_an_unfinished_run_gets_a_real_card():
    """
    "The response was left unfinished" looks like every other notice in the app.

    There were three treatments for this and only one was any good. A provider
    rejection got a proper card. A run the system cut short got a bare 2dp rail.
    And the stalled-run message — the one most likely to need explaining — was
    written into the transcript as an ordinary assistant turn with no error flag,
    so it rendered as plain markdown, indistinguishable from the model talking.
    """
    main = read("MainActivity.kt")
    require("private fun buildNoticeCard" in main,
            "there is no shared card for 'this did not go to plan'")
    require("private fun buildUnfinishedCard" in main,
            "an unfinished run has no card of its own")
    require("Fa.isStalledMessage(parts.visible)" in main,
            "the stalled marker still renders as ordinary model prose")
    require("Ui.railPanel(this, Theme.R_MD, Theme.ACCENT)" not in main,
            "the continue card is back on the thin rail instead of the shared card")
    # Both halves of the message are used: a headline and its detail.
    body = _function_body(main, "    private fun buildNoticeCard(")
    require("body.indexOf('\\n')" in body,
            "the notice card no longer splits a headline from its detail")

    fa = read("Fa.kt")
    # Both languages must carry the split: a card whose Persian rendering has no
    # newline shows a headline and an empty body.
    for key in ("RUN_STALLED", "RUN_INTERRUPTED"):
        line = re.search(rf"^\s*val {key}: String get\(\) = .*$", fa, re.MULTILINE)
        require(line is not None, f"Fa.{key} is missing")
        require(line.group(0).count("\\n") >= 2,
                f"Fa.{key} lacks a headline/detail split in both languages, so the "
                "card has an empty body")


def test_the_displayed_version_is_one():
    """
    Every surface that names a version says 1.

    They disagreed before: Gradle said versionName "1" while the CI workflow built
    and uploaded "Vega-v5.apk" with versionName "5", so the artifact a user
    downloaded was labelled differently from the app that installed.
    """
    gradle = read_root("build.gradle.kts")
    require('versionName = "1"' in gradle, "the build does not report version 1")
    workflow = read_root(".github/workflows/android.yml")
    require('./mkapk.sh "1"' in workflow, "CI still builds a different version")
    require("Vega-v5" not in workflow, "CI still names its artifact v5")
    fa = read("Fa.kt")
    require('val SET_VERSION: String get() = "v1"' in fa,
            "the About row shows a different version, or translates it — a version is "
            "an identifier and must read the same in both languages")
    # versionCode is the invisible ordering number and must never go backwards.
    code = re.search(r"versionCode = (\d+)", gradle)
    require(code is not None and int(code.group(1)) >= 14,
            "versionCode went backwards, so the update will be refused")


def test_dynamic_workflow_actually_delegates():
    """
    The mode's defining behaviour is enforced, not requested.

    Dynamic Workflow promises the job will be visibly split across focused
    sub-agents. Until now that rested entirely on the model choosing to call
    `task` — the prompt asked, and a model is free to ignore a prompt. It usually
    did: it would write a plan and then edit the files itself, so the board stayed
    empty and the mode was indistinguishable from having the switch off. That is
    precisely what the owner reported, twice.

    So changes go through a sub-agent, and the guard says so. Reads stay with the
    lead — investigating is how it writes a brief worth sending, and briefing is
    what this mode is actually about.
    """
    engine = read("AgentEngine.kt")
    body = _function_body(
        engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    require("val mustDelegate = depth == 0 && !planBlocked &&" in body,
            "the lead can still make changes itself in Dynamic Workflow mode")
    require("prefs.dynamicWorkflow() && Tools.isMutating(call.name)" in body,
            "the delegation guard does not test for a mutating tool")
    require("mustDelegate -> DELEGATE_REFUSAL" in body,
            "a direct change by the lead is not refused")
    # The refusal has to teach, or the model burns turns guessing.
    require("DELEGATE_REFUSAL" in engine, "the refusal text is missing")
    require("tasks: [ { name, prompt, phase }, ... ]" in engine,
            "the refusal does not show the batch form, so work stays sequential")
    require("They run in parallel" in engine,
            "the refusal does not say that batched tasks run at once")
    # Reads must NOT be blocked — the lead has to be able to investigate.
    require("Tools.isMutating" in body,
            "the guard blocks reads as well as changes")

    # And the board must appear from the PLAN, not only from the first delegation.
    require("val planned = buildWorkflow(chat)" in body,
            "the board is still only created when a delegation happens")
    require("if (planned.size() >= 2)" in body,
            "a plan does not seed the board")


def test_the_model_narrates_inside_the_review_section():
    """
    Only the model's FINAL message stands in the conversation.

    Everything it says on the way — "now let me improve the avatar with a better
    gradient" — belongs in the review section, beside the work it introduced. Two
    separate defects put it in the conversation instead: narration was promoted to
    the trail's single `phase` slot, which the next step immediately overwrote, so
    only one sentence of a run survived anywhere; and a turn whose prose did not
    match a "promises more work" marker was never folded at all.
    """
    trail = read("Trail.kt")
    require("const val NOTE" in trail,
            "there is no row shape for the model's narration")

    engine = read("AgentEngine.kt")
    note = _function_body(engine, "    private fun noteStepProse(trail: Trail, prose: String) {")
    require("TrailStep.NOTE" in note,
            "narration is still only written to the single phase slot")
    require("trail.addStep(step, MAX_TRAIL_STEPS)" in note,
            "narration is not recorded as a durable row")
    # Deduped, or a re-folded message says the same thing twice running.
    require("newest.kind == TrailStep.NOTE" in note,
            "narration is not deduped against the newest row")

    main = read("MainActivity.kt")
    require("private fun foldFinishedSteps" in main,
            "nothing guarantees a folded step loses its chat bubble")
    fold = _function_body(main, "    private fun foldFinishedSteps() {")
    require("owner.isStep" in fold and "removeViewAt" in fold,
            "the sweep does not remove step rows")
    # Deliberately NOT gated on `streaming`. The engine marks a turn a step the
    # moment its tool call opens, which is mid-stream, and folding it right then is
    # the point: the prose is already in the review section, so leaving the bubble
    # would show it twice and then delete one copy — which is what the owner
    # reported as "written in the chat and then removed".
    require("!owner.streaming" not in fold,
            "the sweep waits for the message to finish, so the prose is still "
            "written into the conversation and then deleted from it")
    engine = read("AgentEngine.kt")
    require("private fun looksLikeCallOpening" in engine,
            "nothing detects a tool call while it is still streaming")
    opening = _function_body(engine, "    private fun looksLikeCallOpening(body: CharSequence): Boolean {")
    # Narrow on purpose: a bare ``` fence is ordinary markdown, and matching it
    # would fold the FINAL answer of any turn containing a code block.
    require('"```json"' in opening and '\\"tool\\"' in opening,
            "the detector would fire on an ordinary markdown code fence")
    require("looksLikeCallOpening(body)" in _function_body(
        engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {"),
            "the early fold is never attempted while streaming")
    # Called on both live paths that can interleave ahead of finalizeStep.
    require(main.count("foldFinishedSteps()") >= 3,
            "the sweep is not called from the live paths that need it")
    # Rows must be findable by message, or the sweep has nothing to match on.
    require("row.tag = message" in main,
            "assistant rows are not tagged with their message")
    # And both surfaces have to render the new row kind.
    require("private fun narrationRow" in main,
            "the panel cannot render a narration row")
    require("TrailStep.NOTE" in read("TrailView.kt"),
            "the strip cannot render a narration row")


def test_the_answer_is_revealed_smoothly():
    """
    Text arrives as a soft wash, not as a sequence of words appearing.

    Smoothness here is an OVERLAP property, not a frame-rate one: the reveal reads
    as continuous only when a word is still resolving while its neighbours begin,
    so that the boundary between written and unwritten is a gradient. Three
    successive builds shortened the ramp to keep the tail from lagging and each
    made it choppier, because a ramp shorter than the gap between token batches
    finishes before the next batch arrives — every burst becomes a discrete event
    however many frames are drawn.

    The ramp must therefore be long relative to a token cadence (tens of
    milliseconds), the stagger long enough that a burst is not one event, and the
    cascade cap must scale WITH the ramp — a cap shorter than the ramp is what
    flattens the stagger back to nothing on providers that batch heavily.
    """
    reveal = read("StreamReveal.kt")
    duration = re.search(r"const val DURATION_MS = (\d+)L", reveal)
    require(duration is not None, "the reveal duration is missing")
    require(int(duration.group(1)) >= 1200,
            f"the reveal ramp is {duration.group(1)}ms — too short to overlap a "
            "token cadence, so the text arrives word by word")
    stagger = re.search(r"STAGGER_MS = (\d+)L", reveal)
    require(stagger is not None and int(stagger.group(1)) >= 80,
            "the stagger is too short for a burst to read as writing")
    cascade = re.search(r"MAX_CASCADE_MS = (\d+)L", reveal)
    require(cascade is not None, "the cascade cap is missing")
    require(int(cascade.group(1)) >= int(duration.group(1)),
            "the cascade cap is shorter than the ramp, so a batched provider "
            "compresses the stagger away and the reveal goes chunky again")
    # Still driven by vsync and still stopping dead when nothing is in flight.
    require("Choreographer" in reveal, "the reveal is not driven by the display")
    require("animatingUntil" in reveal,
            "the reveal has no idle gate, so it would animate for the whole turn")
    # From zero, or a word firms up from grey instead of arriving.
    require("START_ALPHA = 0.0f" in reveal,
            "words start part-visible, which reads as a flicker rather than a fade")


def test_screens_add_insets_to_their_own_padding():
    """
    A screen's own gutter survives the system bars.

    `fitsSystemWindows = true` reads as "keep my content clear of the system bars",
    and the framework's default implementation **REPLACES** the view's padding with
    the insets rather than adding to them. So Settings, which set a 16dp side gutter
    and then asked for `fitsSystemWindows`, had that gutter overwritten with the
    horizontal insets — zero on a portrait phone — and every card ran into both
    edges of the glass.

    Whether it broke depended on whether the platform DISPATCHED insets to that
    view, which is why it looked correct on a Samsung A12 on Android 12 (the decor
    had already consumed them, so the padding survived by accident) and broken on a
    POCO on Android 16 and on MIUI, which forces edge-to-edge earlier. A layout that
    is correct by accident on one OEM is the definition of the bug class this
    contract exists for.
    """
    ui = read("Ui.kt")
    require("fun applyWindowInsets(" in ui,
            "there is no additive inset helper")
    body = _function_body(ui, "    fun applyWindowInsets(")
    # Additive, not replacing — the whole point.
    require("basePadStart + insetStart" in body and "basePadTop + top" in body,
            "the helper replaces the screen's padding instead of adding to it")
    # Horizontal insets are handled: a landscape cutout or a curved edge reports
    # left/right, and a screen that only did top/bottom would sit under the notch.
    require("insetStart" in body and "insetEnd" in body,
            "horizontal insets are ignored, so content can sit under a cutout")
    # Physical left/right must be mapped through the layout direction.
    require("Lang.mirrored(target.context)" in body,
            "physical insets are not mapped onto start/end, so Persian pads the "
            "wrong side")
    # The resting padding must be applied unconditionally: a window that never
    # dispatches insets must still get the screen's own margins.
    require("view.setPaddingRelative(basePadStart, basePadTop, basePadEnd, basePadBottom)"
            in body,
            "the base padding depends on an inset callback that may never fire")
    require("view.fitsSystemWindows = false" in body,
            "the framework's replacing implementation is still active alongside ours")
    # API 23 floor: the modern accessor is guarded and the legacy one is the fallback.
    require("SDK_INT >= 30" in body and "systemWindowInsetTop" in body,
            "the helper is not safe on the API levels this app supports")

    # And Settings must actually use it, with no padding left to be clobbered.
    settings = read("SettingsActivity.kt")
    require("Ui.applyWindowInsets(panel, padH, 0, padH," in settings,
            "the settings panel does not route its padding through the helper")
    require("panel.fitsSystemWindows = true" not in settings,
            "the settings panel still asks the framework to replace its padding")
    # Nothing anywhere may pair its own padding with fitsSystemWindows again.
    #
    # Scoped to the ENCLOSING FUNCTION, not the file: `column` is a common local
    # name and two unrelated ones in the same 7000-line file are not the same view.
    # A file-wide match reports the chat column (which correctly has no padding of
    # its own) against buildWelcome's column (which correctly has padding and never
    # asks for insets).
    for name in sorted(path.name for path in SRC.glob("*.kt")):
        lines = strip_comments(read(name)).splitlines()
        starts = [i for i, line in enumerate(lines)
                  if re.match(r"    (?:private |internal )?fun \w", line)]
        for index, line in enumerate(lines):
            if "fitsSystemWindows = true" not in line:
                continue
            holder = line.strip().split(".")[0]
            begin = max([i for i in starts if i <= index], default=0)
            end = min([i for i in starts if i > index], default=len(lines))
            scope = "\n".join(lines[begin:end])
            require(f"{holder}.setPadding" not in scope,
                    f"{name}:{index + 1}: {holder} sets its own padding AND asks "
                    "fitsSystemWindows to replace it — use Ui.applyWindowInsets")


def test_a_workflow_run_waits_for_its_phases():
    """
    The run is not finished until the phases are.

    The model would delegate the first phase, get one successful report, and
    announce that everything was done — leaving a board reading "1 finished · 2
    queued" directly above the word "Finished", with two plan steps no agent was
    ever sent to do. An easy mistake for it to make: a successful report reads like
    an ending.

    The board knows better than the model here, so the board gets a say.
    """
    engine = read("AgentEngine.kt")
    body = _function_body(
        engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    require("val unfinished = workflow?.pendingCount() ?: 0" in body,
            "nothing checks whether the board still has undelegated phases")
    require("unfinished > 0 &&" in body,
            "a run can still complete with phases nobody was sent to do")
    require("nudges.add(phasePush(workflow))" in body,
            "the run completes instead of being pushed back to its phases")
    # Bounded: a model that has genuinely decided a phase is unnecessary must be
    # able to say so and stop, rather than be held in a loop by its own plan.
    require("pushes < MAX_PHASE_PUSHES" in body,
            "the push is unbounded, so a revised plan would loop for ever")
    require("private const val MAX_PHASE_PUSHES" in engine,
            "the push budget is missing")
    # The push has to be actionable, or the model just re-summarises.
    push = _function_body(engine, "    private fun phasePush(board: Workflow?): String {")
    require("phase.index" in push and "phase.title" in push,
            "the push does not name which phases are outstanding")
    require("tasks: [ { name, prompt, phase }, ... ]" in push,
            "the push does not remind the model to batch independent phases")


def test_absolute_positioning_survives_mirroring():
    """
    A view placed by `translationX` must not move when the language flips.

    `translationX` is a PHYSICAL offset from wherever a view was laid out — and a
    FrameLayout child with no gravity is laid out at its parent's START edge, which
    is the RIGHT under RTL. So the conversation menu, positioned with screen-absolute
    arithmetic, started at the right edge in Persian and the offset pushed it further
    right: the delete/rename/pin panel was clipped against the glass with its icons
    half missing.

    The fix is to make the POSITIONING frame direction-neutral and let only the
    card's content mirror. Absolute maths needs an absolute frame; mixing the two is
    what produced a menu that was correct in one language and off-screen in the
    other.
    """
    main = read("MainActivity.kt")
    body = _function_body(main, "    private fun showChatMenu(")
    require("holder.layoutDirection = View.LAYOUT_DIRECTION_LTR" in body,
            "the menu's positioning frame still flips with the language, so "
            "translationX means something different in Persian")
    require("menuLp.gravity = Gravity.TOP or Gravity.START" in body,
            "the menu's gravity is implicit, so it depends on the resolved direction")
    require("card.layoutDirection = Lang.direction(this)" in body,
            "the menu's contents do not mirror, so Persian rows read backwards")
    # The drawer's own offset must keep asking which physical edge it is on.
    require("if (Lang.mirrored(this)) width.toFloat() else -width.toFloat()" in main,
            "the drawer's travel no longer follows the physical edge it is hinged on")
    # And the header sweep must run in the reading direction.
    require("val progress = if (rtl) 1.0f - fraction else fraction" in main,
            "the header progress sweep runs the same way in both languages")


def test_a_language_change_applies_without_a_restart():
    """
    Switching language repaints the chat screen. It used to need killing the app.

    `Fa` is computed getters over one process-wide flag, so Settings changing the
    language takes effect for every STRING immediately — but it recreates only
    itself. The chat screen was never told, so it came back with a view tree built in
    the old direction while every string it re-read was in the new language: a
    half-translated screen with the drawer hinged on the wrong side and the menu
    glyph unflipped, recoverable only by force-quitting.

    The palette has always been handled this way, by comparing a generation against
    what the tree was painted with. The language needs exactly the same treatment,
    and this check went missing while the app was briefly English-only.
    """
    main = read("MainActivity.kt")
    require("private var lastLanguage" in main,
            "nothing records the language this view tree was built in")
    resume = _function_body(main, "    override fun onResume() {")
    require("prefs.language() != lastLanguage" in resume,
            "a language change made in Settings never reaches the chat screen")
    require("refreshAppearance()" in resume,
            "the screen is not rebuilt when the language changes")
    # Rebuilt in place, never recreated: an Activity restart mid-stream loses the run.
    require("recreate()" not in strip_comments(main),
            "the chat screen recreates itself, which loses the open chat on MIUI")
    # The rebuild path must re-read the flag and record what it built in.
    refresh = _function_body(main, "    private fun refreshAppearance() {")
    require("Fa.apply(this)" in refresh,
            "the rebuild does not re-read the language")
    require("lastLanguage = prefs.language()" in refresh,
            "the rebuild does not record the language it built in, so it would "
            "rebuild on every resume")


def test_a_premature_completion_is_never_shown():
    """
    The app must not announce that the job is done and then disagree with itself.

    Pushing the run back to its unfinished phases was only half a fix. The model had
    already written its summary, the user had already watched it arrive in the
    conversation, and the run then carried on — so what they saw was the app
    declaring completion and then visibly contradicting it. Being wrong quietly is
    recoverable; being wrong out loud and then correcting yourself is what makes
    software feel untrustworthy.

    So the premature summary is folded into the review section as the narration it
    turned out to be, and the conversation keeps only the summary written once the
    board is actually finished.
    """
    engine = read("AgentEngine.kt")
    body = _function_body(
        engine, "    fun run(chat: Chat, token: CancellationToken, callback: Callback) {")
    push = body[body.index("val unfinished = workflow?.pendingCount()"):]
    push = push[:push.index("continue")]
    require("message.isStep = true" in push,
            "the premature completion stays in the conversation as an answer")
    require("noteStepProse(trail, visible)" in push,
            "the premature completion is discarded rather than kept as narration")
    require("callback.onStepFinalized(message)" in push,
            "the UI is never told to fold the premature completion")
    # And the prompt must ask for the whole independent set up front, so the push
    # is a safety net rather than the normal path.
    require("Your FIRST task call must cover EVERY phase that does not depend on another one"
            in engine,
            "the model is not told to delegate the whole independent set at once")
    require("Do NOT write a closing summary until every phase has been delegated"
            in engine,
            "nothing tells the model to hold its summary until the board is done")


def test_the_reveal_curve_is_even():
    """
    The fade must be visible for as long as it lasts.

    This is the defect that made three separate duration increases do nothing. The
    curve was a quintic ease-OUT — 67% opacity at a quarter of the way through, 97%
    at halfway — so whatever the duration said, the visible part of every ramp was
    over in its first quarter and the rest was spent creeping between 97% and 100%.
    The animation was reported as too fast three times; each time the number was
    raised and the curve was left alone.

    Smoothstep spreads the change evenly, so the duration is what you actually see.
    """
    reveal = read("StreamReveal.kt")
    ease = _function_body(reveal, "    private fun ease(t: Float): Float {")
    require("x * x * (3.0f - 2.0f * x)" in ease,
            "the reveal curve is not smoothstep, so its duration is not what is seen")
    require("inv * inv * inv * inv * inv" not in ease,
            "the quintic ease-out is back — it compresses the whole fade into the "
            "first quarter of the ramp")
    # Sanity-check the shape numerically: an even curve is near 0.5 at the midpoint.
    # A quintic ease-out is at 0.97 there, which is what made the ramp invisible.
    require("3.0f - 2.0f * x" in ease,
            "the curve's midpoint is not near half opacity")


def test_no_signing_material_is_in_the_tree():
    """
    No private key, and no credential, anywhere in the repository.

    This tree used to ship its own release key on purpose, with the store password
    written into `keystore/README-KEYSTORE.md` and a `!keystore/…jks` exception in
    `.gitignore` to force it past the rule that was supposed to stop exactly that.
    The reasoning was that losing the key forces every user to uninstall and
    reinstall, so keeping it with the source made it hard to lose.

    That trade is defensible while a build is being handed to one person and
    indefensible the moment the tree becomes a public repository: anyone holding the
    key can publish an update that users' phones accept as genuine, and a leaked
    signing key cannot be revoked — it can only be replaced, which costs every user
    their API key, settings and chats.

    So the key lives outside the tree now, and this contract is what keeps it there.
    It is deliberately blunt: no keystore extension, no `!` exception, no password
    field with a value in it.
    """
    # No key material, by extension, anywhere.
    for pattern in ("*.jks", "*.keystore", "*.p12", "*.pepk"):
        found = [str(path.relative_to(ROOT)) for path in ROOT.rglob(pattern)
                 if ".build" not in str(path)]
        require(not found, f"signing material is in the tree: {found}")

    # .gitignore must refuse them all, with no exception carved out.
    ignore = read_root(".gitignore")
    for pattern in ("*.jks", "*.keystore", "*.p12"):
        require(pattern in ignore, f".gitignore does not ignore {pattern}")
    for line in ignore.splitlines():
        stripped = line.strip()
        if not stripped.startswith("!"):
            continue
        lowered = stripped.lower()
        for extension in (".jks", ".keystore", ".p12", ".pepk"):
            require(extension not in lowered,
                    f".gitignore carves an exception for signing material: {stripped}")
    require("keystore/keystore.properties" in ignore,
            "the credentials file is not ignored")

    # No credential VALUE committed. Checks the documented property names rather
    # than guessing at secrets: a populated password in a tracked file is the
    # failure mode, and the example file is allowed to show empty placeholders.
    fields = ("storePassword", "keyPassword", "VEPRO_KEYSTORE_PASSWORD")
    for path in sorted(list(ROOT.rglob("*.md")) + list(ROOT.rglob("*.properties"))):
        if ".build" in str(path) or path.name.endswith(".example"):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for field in fields:
            for match in re.finditer(
                rf"{field}\s*[=:|]\s*[`'\"]?([^\s`'\"|<]+)", text
            ):
                value = match.group(1)
                # A shell reference or a placeholder is fine; a literal is not.
                if value.startswith("$") or set(value) <= set(".…-_"):
                    continue
                require(False,
                        f"{path.relative_to(ROOT)} contains a literal {field}")


def main():
    tests = [value for name, value in globals().items() if name.startswith("test_") and callable(value)]
    for test in sorted(tests, key=lambda item: item.__name__):
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS source regressions: {len(tests)} checks")


if __name__ == "__main__":
    main()
