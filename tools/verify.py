#!/usr/bin/env python3
"""Static parity harness for the Java -> Kotlin port.

The cheap first line of defence. It needs nothing but python3, so it runs before
anything is compiled, and it catches the class of mistake that is easiest to make
and hardest to spot by reading:

  1. string-literal parity  (did a Persian string, a JSON key, a prompt, a header
     name or an error message get silently dropped or altered?)
  2. brace / paren / bracket balance in the Kotlin
  3. Java-isms left behind  (new X(), .replaceAll(, Pattern.compile(, ...)
  4. the Kotlin numeric traps  (ARGB > 0x7FFFFFFF without .toInt())
  5. !! usage

For the strong check -- actually running both builds and comparing return values
-- see tools/build-offline.sh --java <dir>, which drives tools/DiffHarness.java.
This script is complementary: it compares *source text*, so it notices a changed
literal even in code no test happens to execute.

    python3 tools/verify.py [java-src-dir] [kotlin-src-dir]

Both default to the layout this port was developed in, where the original Java
tree sits next to the Kotlin one.
"""
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)

# argv overrides; otherwise look for the original Java tree beside this project.
_ARGS = [a for a in sys.argv[1:] if not a.startswith('-')]
JAVA_DIR = _ARGS[0] if len(_ARGS) > 0 else os.path.join(
    os.path.dirname(_ROOT), 'VeproCode-v1-src/src/com/vepro/code')
KT_DIR = _ARGS[1] if len(_ARGS) > 1 else os.path.join(_ROOT, 'src/com/vepro/code')

if not os.path.isdir(JAVA_DIR):
    print(f'skip: original Java tree not found at {JAVA_DIR}')
    print('      pass it explicitly:  python3 tools/verify.py <java-src> [kotlin-src]')
    sys.exit(0)
if not os.path.isdir(KT_DIR):
    sys.exit(f'kotlin source tree not found at {KT_DIR}')


def java_literals(src):
    """Extract decoded Java string literals, skipping comments."""
    return _literals(src, kotlin=False)


def kotlin_literals(src):
    return _literals(src, kotlin=True)


def _literals(src, kotlin):
    out = []
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        # line comment
        if ch == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        # block comment
        if ch == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        # char literal (java)
        if not kotlin and ch == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        if kotlin and ch == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        # raw string (kotlin)
        if kotlin and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                break
            out.append(src[i + 3:j])
            i = j + 3
            continue
        if ch == '"':
            j = i + 1
            buf = []
            while j < n:
                c = src[j]
                if c == '\\':
                    nxt = src[j + 1] if j + 1 < n else ''
                    if nxt == 'n':
                        buf.append('\n')
                    elif nxt == 't':
                        buf.append('\t')
                    elif nxt == 'r':
                        buf.append('\r')
                    elif nxt == '"':
                        buf.append('"')
                    elif nxt == '\\':
                        buf.append('\\')
                    elif nxt == "'":
                        buf.append("'")
                    elif nxt == '$':
                        buf.append('$')
                    elif nxt == 'u':
                        try:
                            buf.append(chr(int(src[j + 2:j + 6], 16)))
                        except Exception:
                            buf.append('\\u')
                        j += 6
                        continue
                    else:
                        buf.append('\\' + nxt)
                    j += 2
                    continue
                if c == '"':
                    break
                if c == '\n':
                    break
                buf.append(c)
                j += 1
            out.append(''.join(buf))
            i = j + 1
            continue
        i += 1
    return out


def balance(src):
    """Brace/paren/bracket balance outside strings and comments."""
    depth = {'{': 0, '(': 0, '[': 0}
    pairs = {'}': '{', ')': '(', ']': '['}
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        if ch == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if ch == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"' or src[j] == '\n':
                    break
                j += 1
            i = j + 1
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        if ch in depth:
            depth[ch] += 1
        elif ch in pairs:
            depth[pairs[ch]] -= 1
        i += 1
    return depth


JAVAISMS = [
    (r'\bnew\s+[A-Z]\w*\s*\(', 'Java "new X(" constructor call'),
    (r'\.replaceAll\(', 'Java String.replaceAll (use replace(Regex(..)))'),
    (r'Pattern\.compile\(', 'Java Pattern.compile (use Regex)'),
    # Kotlin's String.equals(other, ignoreCase = true) is legitimate; only a
    # bare .equals(x) is a leftover Java-ism.
    (r'\.equals\((?![^()]*ignoreCase)', 'Java .equals( (use == )'),
    (r'\bpublic\s+static\b', 'Java modifier leaked'),
    (r';\s*$', 'trailing semicolon'),
    (r'\.getInstance\(\)\.get[A-Z]\w*\(\)\s*;', 'java-ish chain'),
    (r'\bString\[\]', 'Java array syntax String[]'),
    (r'\bint\b(?!\w)', 'Java primitive "int"'),
    (r'\bboolean\b(?!\w)', 'Java primitive "boolean"'),
    (r'@Override\b', 'Java @Override'),
]

SKIP_JAVAISM_IN_COMMENT = True

# Raw strings the Java passed to framework calls, which the Kotlin replaces with
# the equivalent named platform constant. Same value, clearer code.
CONST_SUBSTITUTES = {
    'notification': 'Context.NOTIFICATION_SERVICE',
    'power': 'Context.POWER_SERVICE',
    'clipboard': 'Context.CLIPBOARD_SERVICE',
    'input_method': 'Context.INPUT_METHOD_SERVICE',
    'activity': 'Context.ACTIVITY_SERVICE',
    'connectivity': 'Context.CONNECTIVITY_SERVICE',
    'android.intent.action.OPEN_DOCUMENT': 'Intent.ACTION_OPEN_DOCUMENT',
    'android.intent.category.OPENABLE': 'Intent.CATEGORY_OPENABLE',
    'android.intent.extra.ALLOW_MULTIPLE': 'Intent.EXTRA_ALLOW_MULTIPLE',
}


def strip_comments_and_strings(src):
    out = []
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        if ch == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            out.append('\n')
            continue
        if ch == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append('""')
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"' or src[j] == '\n':
                    break
                j += 1
            i = j + 1
            out.append('""')
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def main():
    kt_files = sorted(f for f in os.listdir(KT_DIR) if f.endswith('.kt'))
    problems = 0
    print('=' * 72)
    print('%-24s %6s %6s  %s' % ('file', 'javaL', 'ktL', 'status'))
    print('=' * 72)

    missing_report = []
    for kt in kt_files:
        name = kt[:-3]
        jpath = os.path.join(JAVA_DIR, name + '.java')
        kpath = os.path.join(KT_DIR, kt)
        ksrc = open(kpath, encoding='utf-8').read()

        # --- balance ---
        bal = balance(ksrc)
        bal_bad = [k for k, v in bal.items() if v != 0]

        # --- javaisms ---
        code = strip_comments_and_strings(ksrc)
        isms = []
        for pat, desc in JAVAISMS:
            hits = len(re.findall(pat, code, re.M))
            if hits:
                isms.append('%s x%d' % (desc, hits))

        # --- numeric trap: 0x literal > 0x7FFFFFFF without .toInt() ---
        argb = []
        for m in re.finditer(r'0x([0-9A-Fa-f]{8})\b(?!\s*\.toInt\(\))', code):
            if int(m.group(1), 16) > 0x7FFFFFFF:
                argb.append('0x' + m.group(1))

        bangbang = len(re.findall(r'(?<![!=<>])!!', code))

        # --- literal parity ---
        jl = kl = 0
        miss = []
        if os.path.exists(jpath):
            jsrc = open(jpath, encoding='utf-8').read()
            jlits = [s for s in java_literals(jsrc)]
            klits = kotlin_literals(ksrc)
            jl, kl = len(jlits), len(klits)
            for s in set(jlits):
                if len(s) < 4:
                    continue
                # charset names legitimately become Charsets.UTF_8 / StandardCharsets
                if s in ('UTF-8', 'utf-8') and (
                    'Charsets.UTF_8' in ksrc or 'StandardCharsets.UTF_8' in ksrc
                ):
                    continue
                if s == 'ISO-8859-1' and 'StandardCharsets.ISO_8859_1' in ksrc:
                    continue
                # getSystemService("x") -> Context.X_SERVICE named constant
                if s in CONST_SUBSTITUTES and CONST_SUBSTITUTES[s] in ksrc:
                    continue
                if any(s in k for k in klits):
                    continue
                miss.append(s)

        status = []
        if bal_bad:
            status.append('UNBALANCED:' + ','.join('%s%+d' % (k, bal[k]) for k in bal_bad))
        if miss:
            status.append('missing %d literal(s)' % len(miss))
        if isms:
            status.append('; '.join(isms))
        if argb:
            status.append('ARGB needs .toInt(): ' + ','.join(sorted(set(argb))[:4]))
        if bangbang:
            status.append('!! x%d' % bangbang)
        if not os.path.exists(jpath):
            status.append('(no java counterpart)')

        ok = not (bal_bad or miss or isms or argb or bangbang)
        if not ok:
            problems += 1
        print('%-24s %6d %6d  %s' % (kt, jl, kl, 'OK' if ok else ' | '.join(status)))
        if miss:
            missing_report.append((kt, miss))

    print('=' * 72)
    ported = set(f[:-3] for f in kt_files)
    all_java = set(f[:-5] for f in os.listdir(JAVA_DIR) if f.endswith('.java'))
    todo = sorted(all_java - ported)
    print('ported: %d/%d' % (len(ported & all_java), len(all_java)))
    if todo:
        print('NOT YET PORTED: ' + ', '.join(todo))
    print('files with findings: %d' % problems)

    for kt, miss in missing_report:
        print('\n--- %s: literals present in Java but not found in Kotlin ---' % kt)
        for s in sorted(miss)[:40]:
            print('    %r' % s)
        if len(miss) > 40:
            print('    ... and %d more' % (len(miss) - 40))
    return 0


if __name__ == '__main__':
    sys.exit(main())
