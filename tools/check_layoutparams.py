#!/usr/bin/env python3
"""Flag addView() calls whose LayoutParams class does not match the parent.

Why this exists
---------------
`ViewGroup.addViewInner` does NOT crash on a mismatched LayoutParams. It calls
`checkLayoutParams(params)` and, when that is false, silently replaces them via
`generateLayoutParams(params)`. So a `FrameLayout.LayoutParams` handed to a
`LinearLayout` is quietly converted -- and every field the target class does not
know about is DROPPED in the conversion. Most importantly `gravity`: a child
positioned with `FrameLayout.LayoutParams.gravity` inside a `LinearLayout`, or
with `LinearLayout.LayoutParams.gravity` inside a `FrameLayout`, lands wherever
the default puts it. Width, height and margins survive; the positioning does
not.

That makes it invisible to a compiler, invisible to a text linter, and invisible
in a diff -- the layout is just subtly wrong on one screen. Hence a checker.

`ScrollView` and `HorizontalScrollView` extend `FrameLayout`, so their children
take `FrameLayout.LayoutParams`.

Heuristic, deliberately: it resolves a receiver only when the variable is
constructed locally (`val row = LinearLayout(this)`) or declared with an
explicit type. Anything it cannot resolve is reported under --verbose rather
than guessed at, so a clean run means "no mismatch among the calls I could
prove", not "no mismatch".
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "com", "vepro", "code")

# parent view class -> the LayoutParams class its children must use
EXPECTED = {
    "LinearLayout": "LinearLayout",
    "FrameLayout": "FrameLayout",
    "ScrollView": "FrameLayout",
    "HorizontalScrollView": "FrameLayout",
}

DECL = re.compile(
    r"\b(?:val|var)\s+(\w+)\s*(?::\s*(\w+)\s*\??)?\s*="
    r"\s*(?:\w+\.)?(" + "|".join(EXPECTED) + r")\s*\("
)
TYPED_DECL = re.compile(
    r"\b(?:private\s+|internal\s+)?(?:val|var)\s+(\w+)\s*:\s*("
    + "|".join(EXPECTED)
    + r")\s*\??"
)
LP_DECL = re.compile(r"\b(?:val|var)\s+(\w+)\s*=\s*(\w+)\.LayoutParams\s*\(")
ADD_INLINE = re.compile(r"\b(\w+)\s*\.\s*addView\s*\(\s*[^,()]+,\s*(\w+)\.LayoutParams\s*\(")
ADD_VAR = re.compile(r"\b(\w+)\s*\.\s*addView\s*\(\s*[^,()]+,\s*(\w+)\s*\)")

# `val row = Ui.row(ctx)` is as much a LinearLayout declaration as
# `val row = LinearLayout(ctx)` is; the factory just hides it.
FACTORY = re.compile(r"\b(?:val|var)\s+(\w+)\s*=\s*(?:Ui\.)?(row|column)\s*\(")
FACTORY_TYPE = {"row": "LinearLayout", "column": "LinearLayout"}

# An explicitly-typed function parameter or property: `container: LinearLayout`.
PARAM = re.compile(r"\b(\w+)\s*:\s*(" + "|".join(EXPECTED) + r")\s*\??(?![\w.])")

# `val container = messagesContainer ?: return` — carry the field's type across.
ELVIS = re.compile(r"\b(?:val|var)\s+(\w+)\s*=\s*(\w+)\s*\?:")

# Receivers reached through a property on another object, where the property's
# declared type is unambiguous across the codebase.
ACCESSOR_TYPE = {"body": "LinearLayout"}  # Sheet.body
ADD_ACCESSOR = re.compile(
    r"\b\w+\s*\.\s*(\w+)\s*\.\s*addView\s*\(\s*[^,()]+,\s*(?:(\w+)\.LayoutParams\s*\(|(\w+)\s*\))"
)


def strip_line_comment(line):
    cut = line.find("//")
    return line if cut < 0 else line[:cut]


def scan(path):
    """Return (mismatches, unresolved) for one Kotlin file."""
    text = open(path, encoding="utf8").read()
    lines = [strip_line_comment(l) for l in text.splitlines()]

    # Pass 1: receiver name -> view class, and LayoutParams variable -> its class.
    parents, lp_vars, elvis = {}, {}, {}
    for line in lines:
        for m in DECL.finditer(line):
            parents[m.group(1)] = m.group(3)
        for m in FACTORY.finditer(line):
            parents.setdefault(m.group(1), FACTORY_TYPE[m.group(2)])
        for m in TYPED_DECL.finditer(line):
            parents.setdefault(m.group(1), m.group(2))
        for m in PARAM.finditer(line):
            parents.setdefault(m.group(1), m.group(2))
        for m in LP_DECL.finditer(line):
            lp_vars[m.group(1)] = m.group(2)
        for m in ELVIS.finditer(line):
            elvis[m.group(1)] = m.group(2)
    # Resolve `val a = b ?: return` once b's own type is known.
    for alias, source in elvis.items():
        if alias not in parents and source in parents:
            parents[alias] = parents[source]

    # Pass 2: every addView with a two-argument form.
    mismatches, unresolved = [], []
    for n, line in enumerate(lines, 1):
        calls = []
        # Accessor form first (`sheet.body.addView(...)`). The plain matchers
        # below also see these lines, but with the property name as the
        # receiver -- so record which names are already accounted for and skip
        # them, or every accessor call is reported twice: once resolved, once
        # unresolved.
        covered = set()
        for m in ADD_ACCESSOR.finditer(line):
            prop, inline_lp, var_lp = m.group(1), m.group(2), m.group(3)
            if prop not in ACCESSOR_TYPE:
                continue
            covered.add(prop)
            given = inline_lp if inline_lp else lp_vars.get(var_lp)
            calls.append((prop, given, ACCESSOR_TYPE[prop]))

        for m in list(ADD_INLINE.finditer(line)) + list(ADD_VAR.finditer(line)):
            if m.group(1) in covered:
                continue
            given = m.group(2) if m.re is ADD_INLINE else lp_vars.get(m.group(2))
            calls.append((m.group(1), given, parents.get(m.group(1))))

        for recv, given, parent in calls:
            if given is None:
                continue  # second arg is an index or an opaque expression
            if parent is None:
                unresolved.append((n, recv, given))
                continue
            want = EXPECTED[parent]
            if given not in EXPECTED or EXPECTED[given] != want:
                mismatches.append((n, recv, parent, given, want))
    return mismatches, unresolved


def main():
    verbose = "--verbose" in sys.argv
    bad = 0
    unresolved_total = 0
    checked = 0
    for name in sorted(os.listdir(SRC)):
        if not name.endswith(".kt"):
            continue
        checked += 1
        mismatches, unresolved = scan(os.path.join(SRC, name))
        unresolved_total += len(unresolved)
        for n, recv, parent, given, want in mismatches:
            bad += 1
            print(
                "%s:%d: `%s` is a %s, so its child needs %s.LayoutParams "
                "-- got %s.LayoutParams (silently converted; gravity is lost)"
                % (name, n, recv, parent, want, given)
            )
        if verbose:
            for n, recv, given in unresolved:
                print("%s:%d: (unresolved receiver `%s`, given %s.LayoutParams)"
                      % (name, n, recv, given))

    print()
    if bad:
        print("check_layoutparams: FAIL -- %d mismatch(es)" % bad)
        return 1
    print("check_layoutparams: PASS -- no mismatch among the resolvable "
          "addView calls in %d file(s) (%d receiver(s) unresolved; "
          "re-run with --verbose to list them)" % (checked, unresolved_total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
