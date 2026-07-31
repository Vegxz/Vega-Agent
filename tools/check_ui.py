#!/usr/bin/env python3
"""Static cross-referencer for the Vega UI layer — the stand-in for a compiler.

There is no Kotlin toolchain on the build host, so nothing verifies that the
names the UI code passes around actually exist. Six classes of mistake are
invisible to `grep` and to review, and every one of them ships silently:

  1. An icon name with no entry in `Icons.kt`. The lookup is a `Map` miss, so it
     falls back to the "help" glyph — no compile error, no crash, no log line,
     just a permanently wrong picture. This is the single most common defect.
  2. `Fa.SOMETHING` that no longer exists (a string was renamed or pruned).
  3. `Theme.SOMETHING` that was deleted (the gradient helpers, `tintPanel`, ...).
  4. `Ui.something(...)` called with the WRONG NUMBER OF ARGUMENTS. A plain grep
     for the name passes happily while the call does not compile. Arity drift is
     exactly what happens when a helper gains a parameter mid-refactor.
  5. `R.<type>.<name>` that is not declared in `res/values/public.xml`.
  6. Unbalanced `{}`, `()` or `[]` in a file. Deleting a view builder and
     orphaning its closing brace is the most likely mechanical break in a large
     UI rewrite, and it usually reads as valid code right up to the last line.

Everything is string- and comment-aware: ordinary strings, Kotlin raw strings
(triple-quoted), char literals, `//`, nested block comments and backslash
escapes are all handled, so prose that merely mentions code is never mistaken
for code.

Usage:  python3 tools/check_ui.py        (run from anywhere; paths are absolute)
Exit:   0 = clean, 1 = at least one violation (each printed as file:line: msg)

Stdlib only. No arguments. No configuration.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/com/vepro/code"

OPEN = {"(": ")", "[": "]", "{": "}"}
CLOSE = {")": "(", "]": "[", "}": "{"}


# --------------------------------------------------------------------------
# Lexing: two views of a file, both keeping every newline so that
# `text[:offset].count("\n")` is always the true line number.
# --------------------------------------------------------------------------

def lex(source):
    """Return (no_comments, code_only).

    `no_comments` blanks comments but KEEPS string contents — passes that need
    to read icon-name literals use this one.
    `code_only` blanks comments AND string/char literals — the brace balancer
    uses this one, so a `"}"` in a message never counts as a bracket.
    """
    keep = []          # no_comments
    bare = []          # code_only
    i, n = 0, len(source)
    while i < n:
        ch = source[i]
        # ---- line comment ----
        if source.startswith("//", i):
            end = source.find("\n", i)
            end = n if end < 0 else end
            blank = " " * (end - i)
            keep.append(blank)
            bare.append(blank)
            i = end
            continue
        # ---- block comment (Kotlin allows nesting) ----
        if source.startswith("/*", i):
            depth, j = 0, i
            while j < n:
                if source.startswith("/*", j):
                    depth += 1
                    j += 2
                elif source.startswith("*/", j):
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                else:
                    j += 1
            chunk = source[i:j]
            blank = "".join("\n" if c == "\n" else " " for c in chunk)
            keep.append(blank)
            bare.append(blank)
            i = j
            continue
        # ---- raw string ----
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            j = n if end < 0 else end + 3
            chunk = source[i:j]
            keep.append(chunk)
            bare.append("".join("\n" if c == "\n" else " " for c in chunk))
            i = j
            continue
        # ---- escaped string / char literal ----
        if ch in '"\'':
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == ch or source[j] == "\n":
                    j += 1
                    break
                j += 1
            chunk = source[i:j]
            keep.append(chunk)
            bare.append("".join("\n" if c == "\n" else " " for c in chunk))
            i = j
            continue
        # ---- backtick-escaped identifier ----
        if ch == "`":
            end = source.find("`", i + 1)
            j = n if end < 0 else end + 1
            chunk = source[i:j]
            keep.append(chunk)
            bare.append("".join("\n" if c == "\n" else " " for c in chunk))
            i = j
            continue
        keep.append(ch)
        bare.append(ch)
        i += 1
    return "".join(keep), "".join(bare)


def line_of(text, offset):
    return text[:offset].count("\n") + 1


def split_args(text, open_index):
    """`text[open_index]` is '('. Return (top_level_args, index_after_close).

    Nested calls, lambdas, generics-in-brackets and quoted commas are all kept
    inside their argument. Returns None if the call is never closed.
    """
    depth, current, args = 0, [], []
    i, n = open_index, len(text)
    while i < n:
        ch = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            j = n if end < 0 else end + 3
            current.append(text[i:j])
            i = j
            continue
        if ch in '"\'':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch or text[j] == "\n":
                    j += 1
                    break
                j += 1
            current.append(text[i:j])
            i = j
            continue
        if ch in OPEN:
            depth += 1
            if depth > 1:
                current.append(ch)
            i += 1
            continue
        if ch in CLOSE:
            depth -= 1
            if depth == 0:
                args.append("".join(current))
                return args, i + 1
            current.append(ch)
            i += 1
            continue
        if ch == "," and depth == 1:
            args.append("".join(current))
            current = []
            i += 1
            continue
        current.append(ch)
        i += 1
    return None


def arg_count(args):
    stripped = [a.strip() for a in args]
    # Kotlin permits a trailing comma in an argument list; it is not an argument.
    if len(stripped) > 1 and stripped[-1] == "":
        stripped.pop()
    return 0 if stripped == [""] else len(stripped)


def kotlin_files():
    return sorted(SRC.glob("*.kt"))


# --------------------------------------------------------------------------
# Pass 1 — every icon name resolves in Icons.kt
# --------------------------------------------------------------------------

# `icon` is the 2nd argument of these Ui helpers, which forward it verbatim.
ICON_SECOND = ("iconButton", "softIconButton", "circleButton", "selectorChip",
               "cardRow", "iconBadge", "iconLabel")
# ... and the 3rd argument of these (label comes first).
ICON_THIRD = ("pillButton", "primaryPill")


def icon_uses(no_comments):
    """(name, offset) for every icon-name literal reaching the glyph table."""
    patterns = (
        r'Icons\.(?:of|filled)\(\s*"([^"]*)"',
        r'Icons\.view\(\s*[^,()"]*,\s*"([^"]*)"',
        r'Ui\.(?:' + "|".join(ICON_SECOND) + r')\(\s*[^,()"]*,\s*"([^"]*)"',
        r'Ui\.(?:' + "|".join(ICON_THIRD) + r')\(\s*[^,()"]*,\s*[^,()"]*,\s*"([^"]*)"',
    )
    out = []
    for pattern in patterns:
        for match in re.finditer(pattern, no_comments):
            out.append((match.group(1), match.start()))
    # Named-argument form: `icon = "folder"`.
    for match in re.finditer(r'\bicon\s*=\s*"([^"]*)"', no_comments):
        out.append((match.group(1), match.start()))
    return out


def check_icons(views, report):
    icons = (SRC / "Icons.kt").read_text(encoding="utf-8")
    entries = re.findall(r'put\(\s*"([^"]+)"', icons)
    known = set(entries)
    for name in sorted(known):
        if entries.count(name) > 1:
            match = re.search(r'put\(\s*"' + re.escape(name) + r'"', icons)
            report("Icons.kt", line_of(icons, match.start()) if match else 0,
                   f'icon "{name}" is put() {entries.count(name)} times; buildMap keeps '
                   "the last path and silently discards the earlier one")
    if "help" not in known:
        report("Icons.kt", 1, 'no "help" glyph — it is the unknown-name fallback')
    for path, (no_comments, _code) in views.items():
        for name, offset in icon_uses(no_comments):
            if name not in known:
                report(path.name, line_of(no_comments, offset),
                       f'unknown icon "{name}" — no put("{name}") in Icons.kt, so this '
                       'renders the "help" glyph with no error')


# --------------------------------------------------------------------------
# Pass 2 — every Fa.X exists in Fa.kt
# --------------------------------------------------------------------------

def check_fa(views, report):
    fa = (SRC / "Fa.kt").read_text(encoding="utf-8")
    strings = set(re.findall(r"var\s+(\w+)\s*:\s*String", fa))
    # Fa also exposes helpers (apply, isPlaceholderTitle, isStalledMessage) and
    # a few non-String members; those are legal references too.
    others = (set(re.findall(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*[(<]", fa))
              | set(re.findall(r"\b(?:var|val)\s+(\w+)\s*[:=]", fa)))
    known = strings | others
    for path, (_no_comments, code) in views.items():
        if path.name == "Fa.kt":
            continue
        for match in re.finditer(r"\bFa\.([A-Za-z_]\w*)", code):
            if match.group(1) not in known:
                report(path.name, line_of(code, match.start()),
                       f"Fa.{match.group(1)} does not exist in Fa.kt "
                       "(a renamed or pruned string)")


# --------------------------------------------------------------------------
# Pass 3 — every Theme.X exists in Theme.kt
# --------------------------------------------------------------------------

def check_theme(views, report):
    theme = (SRC / "Theme.kt").read_text(encoding="utf-8")
    known = (set(re.findall(r"\b(?:var|val)\s+(\w+)", theme))
             | set(re.findall(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*[(<]", theme)))
    for path, (_no_comments, code) in views.items():
        if path.name == "Theme.kt":
            continue
        for match in re.finditer(r"\bTheme\.([A-Za-z_]\w*)", code):
            if match.group(1) not in known:
                report(path.name, line_of(code, match.start()),
                       f"Theme.{match.group(1)} does not exist in Theme.kt "
                       "(the gradient helpers were deleted — use a flat fill)")


# --------------------------------------------------------------------------
# Pass 4 — every Ui.x(...) exists AND is called with a legal argument count
# --------------------------------------------------------------------------

def ui_signatures():
    """name -> set of (min_args, max_args) over all overloads."""
    ui = (SRC / "Ui.kt").read_text(encoding="utf-8")
    no_comments, _ = lex(ui)
    sigs = {}
    for match in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", no_comments):
        parsed = split_args(no_comments, match.end() - 1)
        if parsed is None:
            continue
        params = [p.strip() for p in parsed[0]]
        if params == [""]:
            params = []
        total = len(params)
        # A parameter with a default value may be omitted at the call site.
        required = sum(1 for p in params if "=" not in p)
        sigs.setdefault(match.group(1), set()).add((required, total))
    return sigs


def check_ui_arity(views, report):
    sigs = ui_signatures()
    for path, (no_comments, _code) in views.items():
        if path.name == "Ui.kt":
            continue
        for match in re.finditer(r"\bUi\.(\w+)\s*\(", no_comments):
            name = match.group(1)
            line = line_of(no_comments, match.start())
            if name not in sigs:
                report(path.name, line, f"Ui.{name} does not exist in Ui.kt")
                continue
            parsed = split_args(no_comments, match.end() - 1)
            if parsed is None:
                report(path.name, line, f"Ui.{name}( is never closed")
                continue
            args, after = parsed
            count = arg_count(args)
            # A trailing lambda supplies one more argument outside the parens.
            trailing = re.match(r"\s*\{", no_comments[after:]) is not None
            candidates = {count, count + 1} if trailing else {count}
            legal = any(low <= c <= high
                        for c in candidates for low, high in sigs[name])
            if not legal:
                shape = " or ".join(f"{low}..{high}" for low, high in sorted(sigs[name]))
                report(path.name, line,
                       f"Ui.{name} called with {count} argument(s)"
                       f"{' + a trailing lambda' if trailing else ''}, "
                       f"but Ui.kt declares {shape}")


# --------------------------------------------------------------------------
# Pass 5 — every R.<type>.<name> is declared in res/values/public.xml
# --------------------------------------------------------------------------

def check_resources(views, report):
    public = (ROOT / "res/values/public.xml").read_text(encoding="utf-8")
    declared = set(re.findall(r'<public\s+type="(\w+)"\s+name="([\w.]+)"', public))
    types = {kind for kind, _name in declared}
    # `(?<![\w.])` keeps `android.R.anim.fade_in` out: that is the platform's R.
    pattern = re.compile(r"(?<![\w.])R\.(\w+)\.(\w+)")
    for path, (_no_comments, code) in views.items():
        for match in pattern.finditer(code):
            kind, name = match.group(1), match.group(2)
            if (kind, name) in declared:
                continue
            line = line_of(code, match.start())
            if kind not in types:
                report(path.name, line,
                       f"R.{kind}.{name} — res/values/public.xml declares no "
                       f'"{kind}" resources at all')
            else:
                report(path.name, line,
                       f"R.{kind}.{name} is not declared in res/values/public.xml "
                       "(the offline build generates R from that file only)")


# --------------------------------------------------------------------------
# Pass 6 — brackets balance in every file
# --------------------------------------------------------------------------

def check_balance(views, report):
    for path, (_no_comments, code) in views.items():
        stack = []
        broken = False
        for index, ch in enumerate(code):
            if ch in OPEN:
                stack.append((ch, index))
            elif ch in CLOSE:
                if not stack:
                    report(path.name, line_of(code, index),
                           f"stray closing '{ch}' — nothing is open here "
                           "(a deleted block probably left its closer behind)")
                    broken = True
                    break
                opener, opened_at = stack.pop()
                if OPEN[opener] != ch:
                    report(path.name, line_of(code, index),
                           f"'{ch}' closes the '{opener}' opened on line "
                           f"{line_of(code, opened_at)}, which expects "
                           f"'{OPEN[opener]}'")
                    broken = True
                    break
        if broken:
            continue
        for opener, opened_at in stack:
            report(path.name, line_of(code, opened_at),
                   f"'{opener}' is never closed — expected a "
                   f"'{OPEN[opener]}' before end of file")


# --------------------------------------------------------------------------

PASSES = (
    ("icon names resolve in Icons.kt", check_icons),
    ("Fa.* strings exist", check_fa),
    ("Theme.* members exist", check_theme),
    ("Ui.* exists with a matching argument count", check_ui_arity),
    ("R.* resources are declared in public.xml", check_resources),
    ("brackets balance", check_balance),
)


def main():
    if not SRC.is_dir():
        print(f"check_ui: no Kotlin sources at {SRC}", file=sys.stderr)
        return 2
    views = {path: lex(path.read_text(encoding="utf-8")) for path in kotlin_files()}
    total = 0
    for index, (title, check) in enumerate(PASSES, 1):
        found = []
        check(views, lambda name, line, message: found.append((name, line, message)))
        for name, line, message in sorted(found):
            print(f"{SRC / name if (SRC / name).exists() else name}:{line}: {message}")
        state = "OK" if not found else f"{len(found)} violation(s)"
        print(f"check_ui pass {index}/{len(PASSES)} — {title}: {state}")
        total += len(found)
    if total:
        print(f"\ncheck_ui: FAIL — {total} violation(s) across "
              f"{len(views)} Kotlin file(s)")
        return 1
    print(f"\ncheck_ui: PASS — {len(PASSES)} passes clean over "
          f"{len(views)} Kotlin file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
