import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Differential test: loads the ORIGINAL Java build and the PORTED Kotlin build
 * into two isolated class loaders and compares real return values, member for
 * member, over a large adversarial input corpus.
 *
 * This is the strongest correctness evidence obtainable without a device: it
 * does not check that the port "looks right", it checks that both builds
 * compute identical answers for identical inputs — including thrown-exception
 * type and message, and including every private helper reachable by reflection.
 *
 *   java DiffHarness <javaClassesDir> <ktClassesDir> <supportCp...>
 */
public final class DiffHarness {

    // ---------------------------------------------------------------- corpora

    /** Nasty strings: RTL marks, zero-width chars, unbalanced markup, CRLF, … */
    static final String[] TEXT = {
        null, "", " ", "\n", "\r\n", "\t", "   \n\n   ",
        "hello", "Hello World", "سلام دنیا", "متن فارسی با نیم‌فاصله",
        "a\u200bb\u200cc\u200dd\u200ee\u200ff", "\u202atest\u202c", "\u2066x\u2069",
        "\ufeffBOM at start", "trailing\ufeff",
        "<think>secret</think>visible", "<think>only thinking",
        "<think>a</think>mid<think>b</think>end",
        "</think>orphan close", "<think></think>", "<thinking>x</thinking>y",
        "pre<think>\nmulti\nline\n</think>post",
        "# Heading\ntext", "## H2", "###### H6", "#NotAHeading",
        "> quote\n> more", ">no space", "- bullet\n- two", "* star\n+ plus",
        "1. one\n2. two", "```kt\nval x = 1\n```", "```\nplain\n```",
        "```python", "~~~\nfence\n~~~",
        "**bold** *em* `code` ~~strike~~", "**unclosed", "`unclosed",
        "[link](https://x.com)", "![img](a.png)", "[bad](", "|a|b|\n|-|-|\n|1|2|",
        "\\n literal backslash n", "\\t\\r\\\\ \\u0041 \\x41", "\\\\n double",
        "a\\nb\\nc", "100% \\d+ [a-z] $1 \\1",
        "&amp;&lt;&gt;&quot;&#39;&nbsp;&#x27;&#65;&unknown;",
        "<p>html</p><br><script>x=1</script><style>a{}</style>",
        "<!-- comment --><div class=\"x\">y</div>",
        "<a href=\"/rel\">r</a><a href='//proto'>p</a><a href=http://bare>b</a>",
        "line1\n\n\n\nline2", "  padded  ",
        "{\"tool\":\"read\",\"args\":{\"path\":\"a.txt\"}}",
        "{'tool':'x'} trailing", "{\"a\":1,} // comment\n",
        "```json\n{\"tool\":\"web_fetch\",\"args\":{\"url\":\"x\"}}\n```",
        "text before {\"tool\":\"ls\",\"args\":{}} text after",
        "{\"tool\":\"a\",\"args\":{\"nested\":{\"deep\":[1,2,{\"k\":\"}\"}]}}}",
        "{\"unbalanced\":{", "}}}{{{",
        "https://example.com", "http://example.com/a b/c",
        "nex1music. ir", "site.com/x", "  <https://q.com/p>  ",
        "\"https://quoted.com\".", "(https://paren.com),",
        "https://x.com/a?b=1&c=2#frag", "https://x.com/ a . b ",
        "HTTPS://UPPER.COM", "ftp://other.com/z", "://noscheme",
        "https://", "https://host:8443/p", "localhost:1234/x",
        "https://x.com/path%20already", "https://x.com/سلام",
        "file.md", "a.TXT", "noext", ".hidden", "a.b.c.json", "x.",
        "photo.PNG", "clip.mp4", "song.mp3", "archive.tar.gz", "s.sh",
        "Retry after 30s", "please retry in 1m30s", "wait 2h", "500ms",
        "quota exceeded for model", "rate limit reached", "RESOURCE_EXHAUSTED",
        "insufficient_quota", "invalid api key", "permission denied",
        "{\"error\":{\"message\":\"boom\",\"code\":429}}",
        "{\"error\":\"flat\"}", "not json at all",
        "attention is all you need", rep("a", 300),
        rep("سلام", 80), " null byte", "emoji \uD83D\uDE80\uD83C\uDF89 ok",
        "surrogate \uD83D\uDE00 pair", "lone high \uD83D end",
        "tab\tsep\tvalues", "mixed\r\nline\rends\n",
        "C:\\Users\\x\\file.txt", "../../etc/passwd", "/absolute/path",
        "sk-proj-AAAABBBBCCCCDDDD", "Bearer abcdefghijklmnop",
        "AIzaSyAAAABBBBCCCCDDDDEEEEFFFF", "api_key=\"secret12345\"",
    };

    static final int[] INTS = {
        Integer.MIN_VALUE, -1000, -1, 0, 1, 8, 100, 200, 255, 300, 400, 401,
        402, 403, 404, 408, 413, 429, 500, 502, 503, 504, 599, 1000, 65536,
        Integer.MAX_VALUE,
    };

    static final long[] LONGS = {
        0L, 1L, 512L, 1023L, 1024L, 1025L, 1536L, 10240L, 1048575L, 1048576L,
        1048577L, 1572864L, 10485760L, 1073741823L, 1073741824L, 1073741825L,
        1099511627776L, Long.MAX_VALUE / 2, Long.MAX_VALUE,
    };

    static final float[] FLOATS = {
        -1f, 0f, 0.001f, 0.1f, 0.25f, 0.333f, 0.5f, 0.666f, 0.75f, 0.999f, 1f,
        1.5f, 2f, 100f,
    };

    static final int[] COLORS = {
        0x00000000, 0xFF000000, 0xFFFFFFFF, 0xFF0A0C16, 0xFF10B981, 0xFFEF4444,
        0x80123456, 0xFF7C3AED, 0x0FFFFFFF, 0xFFF59E0B,
    };

    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ------------------------------------------------------------------ state

    static ClassLoader JV, KT;
    static int checks = 0, mismatches = 0;
    static final List<String> failures = new ArrayList<>();
    static final List<String> fixed = new ArrayList<>();
    static final Map<String, Integer> perTarget = new LinkedHashMap<>();

    /** Sentinel for "this call did not return within the budget". */
    static final Object HUNG = new Object() {
        @Override public String toString() { return "<HUNG>"; }
    };
    static final long CALL_BUDGET_MS = 2000;
    static int unexpectedHangs = 0;

    /**
     * Defects in the ORIGINAL Java that the port fixes. The reference cannot be
     * invoked for these inputs — it never returns, and JDK 21 has no way to kill
     * the thread, so every abandoned call would keep burning a core. Instead the
     * harness asserts that the PORT returns promptly and counts the case as a
     * fixed bug. Each entry is a target plus a predicate over the arguments.
     *
     * countOccurrences(h, ""): `indexOf("", from)` never yields -1 and `from`
     * never advances, so the original spins forever. applyOne() reaches it with
     * the same empty needle.
     */
    static boolean referenceHangs(String target, Object[] argv) {
        if (!target.equals("Tools.countOccurrences") && !target.equals("Tools.applyOne")) {
            return false;
        }
        return argv.length >= 2 && "".equals(argv[1]);
    }

    static java.util.concurrent.ExecutorService POOL = fresh();

    static java.util.concurrent.ExecutorService fresh() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "diff-call");
            t.setDaemon(true);          // a wedged call must not block JVM exit
            return t;
        });
    }

    /**
     * Every call is time-boxed. A reference implementation that never returns is
     * itself a defect, so the harness reports "java hangs, kotlin returns" as a
     * FIXED bug rather than as a parity mismatch.
     */
    static Object timed(String target, java.util.concurrent.Callable<Object> body) {
        java.util.concurrent.Future<Object> f;
        try {
            f = POOL.submit(body);
        } catch (RuntimeException e) {
            POOL = fresh();
            f = POOL.submit(body);
        }
        try {
            return f.get(CALL_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            POOL = fresh();             // abandon the wedged thread, keep going
            if (++unexpectedHangs > 4) {
                System.out.println("ABORT: too many undeclared hangs (last: "
                    + target + ") — a wedged JDK 21 thread cannot be killed, so "
                    + "the harness stops rather than spin the machine.");
                System.exit(2);
            }
            return HUNG;
        } catch (Throwable t) {
            return unwrap(t);
        }
    }

    public static void main(String[] args) throws Exception {
        List<URL> support = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            support.add(new File(args[i]).toURI().toURL());
        }
        JV = loader(args[0], support);
        KT = loader(args[1], support);

        constants();
        strings();
        numbers();
        toolCalls();
        toolHelpers();
        webInternals();
        markdownInternals();
        svgPaths();
        themePalette();
        clientRouting();
        fuzz();

        System.out.println();
        System.out.println("── differential coverage ──");
        for (Map.Entry<String, Integer> e : perTarget.entrySet()) {
            System.out.printf("  %-44s %6d comparisons%n", e.getKey(), e.getValue());
        }
        System.out.printf("%ntargets exercised : %d%n", perTarget.size());
        System.out.printf("total comparisons : %d%n", checks);
        System.out.printf("mismatches        : %d%n", mismatches);
        System.out.printf("bugs fixed in port: %d%n", fixed.size());
        if (!fixed.isEmpty()) {
            System.out.println("\nDEFECTS IN THE ORIGINAL THAT THE PORT FIXES:");
            for (String f : fixed) {
                String t = f.substring(0, f.indexOf(' '));
                System.out.println("  ✔ " + f + "  [" + fixedCount.get(t)
                    + " inputs]");
            }
        }
        if (!failures.isEmpty()) {
            System.out.println("\nMISMATCHES BY TARGET:");
            for (Map.Entry<String, Integer> e : failCount.entrySet()) {
                System.out.printf("  %-42s %5d / %d%n", e.getKey(), e.getValue(),
                    perTarget.getOrDefault(e.getKey(), 0));
            }
            int show = Integer.getInteger("diff.show", 40);
            System.out.println("\nFAILURES (first " + show + "):");
            for (int i = 0; i < Math.min(show, failures.size()); i++) {
                System.out.println("  " + failures.get(i));
            }
            System.out.println("\nFAIL DiffHarness");
            System.exit(1);
        }
        System.out.println("\nPASS DiffHarness: original Java and ported Kotlin agree "
            + "on all " + checks + " comparisons across " + perTarget.size()
            + " members");
    }

    static ClassLoader loader(String dir, List<URL> support) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(new File(dir).toURI().toURL());
        urls.addAll(support);
        // platform parent: com.vepro.code resolves per-loader, never shared
        return new URLClassLoader(urls.toArray(new URL[0]),
            ClassLoader.getPlatformClassLoader());
    }

    // ------------------------------------------------------------- comparison

    static final Map<String, Integer> fixedCount = new LinkedHashMap<>();
    static final Map<String, Integer> failCount = new java.util.TreeMap<>();

    /** Records "original hangs, port returns" once per target, with a hit count. */
    static void fixedOnce(String target, String portResult) {
        if (fixedCount.merge(target, 1, Integer::sum) == 1) {
            fixed.add(target + " — original Java never returns on an empty needle; "
                + "port returns " + portResult);
        }
    }

    static void compare(String target, String label, Object a, Object b) {
        checks++;
        perTarget.merge(target, 1, Integer::sum);
        if (a == HUNG && b != HUNG) {
            fixedOnce(target, describe(b));
            return;
        }
        String sa = describe(a), sb = describe(b);
        if (!sa.equals(sb)) {
            mismatches++;
            failCount.merge(target, 1, Integer::sum);
            failures.add(target + "  [" + label + "]\n      java  : " + sa
                + "\n      kotlin: " + sb);
        }
    }

    /**
     * Loader-independent rendering. Structural results are flattened
     * positionally so a Java `String[]` and the Kotlin data class that replaced
     * it render identically — that is the whole point of the port's type
     * changes, and it must not hide a behavioural difference.
     */
    static String describe(Object o) {
        if (o == null) return "<null>";
        if (o instanceof Throwable) {
            Throwable t = (Throwable) o;
            if (t instanceof NullPointerException) {
                // Both builds reject a null argument by throwing NPE; only the
                // wording differs (JDK helpful-NPE text vs Kotlin's intrinsic
                // "Parameter specified as non-null is null"). The observable
                // contract — "this call fails, it does not silently proceed" — is
                // identical, and no app code constructs these messages, so the
                // message is deliberately excluded from the comparison.
                return "!NullPointerException";
            }
            String m = t.getMessage();
            return "!" + t.getClass().getSimpleName() + ":"
                + (m == null ? "" : esc(maskTime(m)));
        }
        if (o instanceof String) return "\"" + esc(maskTime((String) o)) + "\"";
        if (o instanceof Float) return fmt((Float) o);
        if (o instanceof Double) return fmt((Double) o);
        if (o instanceof Integer) return "0x" + Integer.toHexString((Integer) o);
        if (o instanceof Number || o instanceof Boolean || o instanceof Character) {
            return String.valueOf(o);
        }
        if (o instanceof CharSequence) return "\"" + esc(o.toString()) + "\"";
        if (o instanceof File) return "File(" + ((File) o).getName() + ")";
        Object rec = tryCall(o, "recordedOps");   // android.graphics.Path stub
        if (rec != null) return "Path{" + rec + "}";
        Object desc = tryCall(o, "describe");     // android.graphics.RectF stub
        if (desc != null) return "RectF{" + desc + "}";
        if (o.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0, n = Array.getLength(o); i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append(describe(Array.get(o, i)));
            }
            return sb.append("]").toString();
        }
        if (o instanceof Map) {
            TreeSet<String> out = new TreeSet<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                out.add(describe(e.getKey()) + "=" + describe(e.getValue()));
            }
            return out.toString();
        }
        if (o instanceof Collection) {
            StringBuilder sb = new StringBuilder("[");
            for (Object x : (Collection<?>) o) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(describe(x));
            }
            return sb.append("]").toString();
        }
        if (o.getClass().getName().startsWith("com.vepro.code.")) {
            return positional(o);
        }
        return o.getClass().getSimpleName() + "(" + o + ")";
    }

    /**
     * Types that replaced a Java array (`Think.Parts` for String[2], `Web.Hit`
     * for String[3], `MarkdownRenderer.Row` for int[3]) render positionally so
     * they line up with the array the original returned. Everything else renders
     * as a name-sorted map, so a field-declaration-order difference between the
     * two builds is not mistaken for a behavioural one.
     */
    static final java.util.Set<String> POSITIONAL =
        new java.util.HashSet<>(Arrays.asList("Parts", "Hit", "Row"));

    static String positional(Object o) {
        String simple = o.getClass().getName();
        simple = simple.substring(simple.lastIndexOf('$') + 1);
        boolean byIndex = POSITIONAL.contains(simple);
        List<String> ordered = new ArrayList<>();
        TreeSet<String> named = new TreeSet<>();
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            try {
                f.setAccessible(true);
                String v = describe(f.get(o));
                ordered.add(v);
                named.add(f.getName() + "=" + v);
            } catch (Throwable ignored) { /* inaccessible: skip */ }
        }
        return byIndex ? ordered.toString() : named.toString();
    }

    static String fmt(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
        return String.format(java.util.Locale.US, "%.6f", d);
    }

    static Object tryCall(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Blanks out epoch-millis / nanos runs. `pickDownloadName` and the attachment
     * cache embed System.currentTimeMillis(), so two calls can never produce the
     * same string; only the surrounding shape is meaningful.
     */
    static String maskTime(String s) {
        return s.replaceAll("[0-9]{10,}", "<ts>");
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20 || (c >= 0x200b && c <= 0x200f)
                     || (c >= 0x202a && c <= 0x202e) || c == 0xfeff) {
                sb.append(String.format("\\u%04x", (int) c));
            } else sb.append(c);
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- invoke logic

    static final Map<String, Object> RESOLVED = new java.util.HashMap<>();

    /** Resolves a member on either build, handling Kotlin object/Companion/mangling. */
    static Object[] resolve(ClassLoader cl, String cls, String name, Class<?>... sig)
            throws Exception {
        String key = System.identityHashCode(cl) + "#" + cls + "#" + name + "#"
            + Arrays.toString(sig);
        Object cached = RESOLVED.get(key);
        if (cached instanceof Object[]) return (Object[]) cached;
        if (cached instanceof Exception) throw (Exception) cached;
        try {
            Object[] rm = resolveUncached(cl, cls, name, sig);
            RESOLVED.put(key, rm);
            return rm;
        } catch (Exception e) {
            RESOLVED.put(key, e);
            throw e;
        }
    }

    static Object[] resolveUncached(ClassLoader cl, String cls, String name,
                                    Class<?>... sig) throws Exception {
        Class<?> c = Class.forName("com.vepro.code." + cls, true, cl);
        Object receiver = instance(c);
        Method m = find(c, name, sig);
        if (m == null) m = find(c, name + "$main", sig);       // internal mangling
        if (m == null) {
            try {
                Field comp = c.getDeclaredField("Companion");
                comp.setAccessible(true);
                Object companion = comp.get(null);
                Class<?> cc = companion.getClass();
                Method cm = find(cc, name, sig);
                if (cm == null) cm = find(cc, name + "$main", sig);
                if (cm != null) {
                    cm.setAccessible(true);
                    return new Object[] { companion, cm };
                }
            } catch (NoSuchFieldException ignored) { }
            throw new NoSuchMethodException(cls + "." + name);
        }
        m.setAccessible(true);
        if (Modifier.isStatic(m.getModifiers())) receiver = null;
        return new Object[] { receiver, m };
    }

    static Object instance(Class<?> c) {
        try {
            Field inst = c.getDeclaredField("INSTANCE");
            inst.setAccessible(true);
            return inst.get(null);
        } catch (Throwable ignored) {
            return null;                                       // java: plain statics
        }
    }

    static Method find(Class<?> c, String name, Class<?>[] sig) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, sig);
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    static Object invoke(ClassLoader cl, String cls, String name,
                         Class<?>[] sig, Object[] argv) {
        final Object[] rm;
        try {
            rm = resolve(cl, cls, name, sig);
        } catch (Throwable t) {
            return unwrap(t);
        }
        return timed(cls + "." + name, () -> ((Method) rm[1]).invoke(rm[0], argv));
    }

    static Throwable unwrap(Throwable t) {
        while (t.getCause() != null && t.getCause() != t
               && (t instanceof java.lang.reflect.InvocationTargetException
                   || t instanceof java.util.concurrent.ExecutionException)) {
            t = t.getCause();
        }
        return t;
    }

    /** Runs one member on both builds with the same args and compares. */
    static void both(String cls, String name, Class<?>[] sig, Object[] argv,
                     String label) {
        if (referenceHangs(cls + "." + name, argv)) {
            // Reference is known-broken here: assert the port is not.
            Object port = invoke(KT, cls, name, sig, argv);
            checks++;
            perTarget.merge(cls + "." + name, 1, Integer::sum);
            if (port == HUNG) {
                mismatches++;
                failures.add(cls + "." + name + "  [" + label
                    + "] PORT ALSO HANGS — the fix did not take");
            } else {
                fixedOnce(cls + "." + name, describe(port));
            }
            return;
        }
        Object a = invoke(JV, cls, name, sig, argv);
        Object b = invoke(KT, cls, name, sig, argv);
        if (a instanceof NoSuchMethodException || b instanceof NoSuchMethodException) {
            mismatches++;
            failures.add(cls + "." + name + " NOT RESOLVED: java="
                + describe(a) + " kotlin=" + describe(b));
            perTarget.merge(cls + "." + name, 0, Integer::sum);
            return;
        }
        compare(cls + "." + name, label, a, b);
    }

    static final Class<?>[] S = { String.class };
    static final Class<?>[] SS = { String.class, String.class };
    static final Class<?>[] SSS = { String.class, String.class, String.class };
    static final Class<?>[] SI = { String.class, int.class };
    static final Class<?>[] II = { int.class, int.class };
    static final Class<?>[] IIF = { int.class, int.class, float.class };
    static final Class<?>[] IF = { int.class, float.class };
    static final Class<?>[] I = { int.class };
    static final Class<?>[] J = { long.class };

    static String q(String s) { return s == null ? "<null>" : "\"" + esc(s) + "\""; }

    // -------------------------------------------------------------- the suites

    /** Every UI string, every icon path, every compile-time constant. */
    static void constants() throws Exception {
        Class<?> fj = Class.forName("com.vepro.code.Fa", true, JV);
        Class<?> fk = Class.forName("com.vepro.code.Fa", true, KT);
        int n = 0;
        for (Field f : fj.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            Field g = fk.getDeclaredField(f.getName());
            f.setAccessible(true);
            g.setAccessible(true);
            compare("Fa.<ui strings>", f.getName(), f.get(null), g.get(null));
            n++;
        }
        System.out.println("Fa            : " + n + " UI strings compared");

        Map<?, ?> dj = (Map<?, ?>) staticField("Icons", "D", JV);
        Map<?, ?> dk = (Map<?, ?>) staticField("Icons", "D", KT);
        compare("Icons.D", "keySet", new TreeSet<>(strings(dj.keySet())),
            new TreeSet<>(strings(dk.keySet())));
        for (String k : new TreeSet<>(strings(dj.keySet()))) {
            compare("Icons.D", k, dj.get(k), dk.get(k));
        }
        System.out.println("Icons         : " + dj.size() + " vector paths compared");

        int c = 0;
        for (String cls : new String[] { "Web", "Prefs", "LlmClient", "Theme",
                "Memory", "ChatStore", "NetworkPolicy", "AgentEngine", "Tools",
                "SecureStore", "AgentService", "Util", "Think", "Chat" }) {
            Class<?> a = Class.forName("com.vepro.code." + cls, true, JV);
            for (Field f : a.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != String.class && !f.getType().isPrimitive()) continue;
                Field g = findField(cls, f.getName(), KT);
                if (g == null) continue;   // renamed/moved: covered behaviourally
                f.setAccessible(true);
                g.setAccessible(true);
                compare(cls + ".<consts>", f.getName(), f.get(null), g.get(null));
                c++;
            }
        }
        System.out.println("constants     : " + c + " static finals compared");
    }

    static List<String> strings(Collection<?> in) {
        List<String> out = new ArrayList<>();
        for (Object o : in) out.add(String.valueOf(o));
        return out;
    }

    static Field findField(String cls, String name, ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.vepro.code." + cls, true, cl);
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                Field comp = c.getDeclaredField("Companion");
                comp.setAccessible(true);
                return comp.get(null).getClass().getDeclaredField(name);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static Object staticField(String cls, String name, ClassLoader cl) throws Exception {
        Class<?> c = Class.forName("com.vepro.code." + cls, true, cl);
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** Pure text transforms over the whole adversarial corpus. */
    static void strings() {
        for (String s : TEXT) {
            Object[] a1 = { s };
            both("Util", "cleanUrl", S, a1, q(s));
            both("Util", "sanitize", S, a1, q(s));
            both("Util", "ext", S, a1, q(s));
            both("Util", "mimeOf", S, a1, q(s));
            both("Util", "kindOf", S, a1, q(s));
            both("Util", "isTextMime", S, a1, q(s));
            both("Think", "split", S, a1, q(s));
            both("Think", "visible", S, a1, q(s));
            both("Think", "stripForModel", S, a1, q(s));
            both("MarkdownRenderer", "normalizeEscapes", S, a1, q(s));
            both("MarkdownRenderer", "codeLang", S, a1, q(s));
            both("MarkdownRenderer", "codeBody", S, a1, q(s));
            both("MarkdownRenderer", "inlineToHtml", S, a1, q(s));
            both("MarkdownRenderer", "inline", S, a1, q(s));
            both("Web", "htmlToText", S, a1, q(s));
            both("Web", "looksBlocked", S, a1, q(s));
            both("Web", "stripTags", S, a1, q(s));
            both("Web", "unescape", S, a1, q(s));
            both("Web", "decodeBingHref", S, a1, q(s));
            both("Web", "decodeDuckHref", S, a1, q(s));
            both("AgentEngine", "stripToolCalls", S, a1, q(s));
            both("AgentEngine", "reasoningGuidance", S, a1, q(s));
            both("LlmClient", "parseProviderDuration", S, a1, q(s));
            both("LlmClient", "classify429", S, a1, q(s));
            both("LlmClient", "extractError", S, a1, q(s));
            both("Tools", "cleanFileName", S, a1, q(s));
            both("Tools", "stripMime", S, a1, q(s));
            both("Tools", "globToRegex", S, a1, q(s));
            both("Tools", "unescapePdf", S, a1, q(s));
            for (int lim : new int[] { 0, 1, 5, 50, 300 }) {
                both("Util", "truncate", SI, new Object[] { s, lim }, q(s) + "," + lim);
            }
        }
        String[] partners = { null, "", "x", "https://base.com/dir/p", "http://h/",
                              "думать", "<think>t</think>" };
        for (String a : TEXT) {
            for (String b : partners) {
                both("Think", "merge", SS, new Object[] { a, b }, q(a) + "|" + q(b));
                both("Web", "resolveUrl", SS, new Object[] { b, a }, q(b) + "|" + q(a));
                both("Web", "extract", SS, new Object[] { a, b }, q(a) + "|" + q(b));
                both("Tools", "countOccurrences", SS, new Object[] { a, b },
                    q(a) + "|" + q(b));
            }
        }
        both("AgentEngine", "reasoningIntegrityRule", new Class<?>[0], new Object[0], "-");
        networkPolicy();
    }

    /**
     * SSRF guard. Only DNS-free inputs (literals / loopback names) are used so
     * the suite stays deterministic and offline — the interesting logic is the
     * scheme check and the private-range classifier, both of which are covered.
     */
    static void networkPolicy() {
        String[] urls = {
            null, "", "   ", "ftp://x.com", "file:///etc/passwd", "javascript:1",
            "HTTP://LOCALHOST:8080", "http://localhost", "http://localhost:8080/v1",
            "http://a.localhost/x", "http://127.0.0.1:1/x", "http://[::1]:80/",
            "http://10.0.0.1/", "http://192.168.1.1/",
            "https://localhost", "https://a.localhost", "https://x.local",
            "https://metadata.google.internal", "https://169.254.169.254",
            "https://10.0.0.1", "https://10.255.255.255", "https://192.168.1.1",
            "https://172.15.0.1", "https://172.16.0.1", "https://172.31.255.255",
            "https://172.32.0.1", "https://100.63.0.1", "https://100.64.0.1",
            "https://100.127.255.255", "https://100.128.0.1",
            "https://198.17.0.1", "https://198.18.0.1", "https://198.19.255.255",
            "https://198.20.0.1", "https://127.0.0.1", "https://0.0.0.0",
            "https://[::1]", "https://[fc00::1]", "https://[fd12::1]",
            "https://[fe80::1]", "https://[2001:4860:4860::8888]",
            "https://user@8.8.8.8", "https://8.8.8.8:0/", "https://8.8.8.8",
            "https://8.8.8.8:443/v1", "https:///nohost", "not a uri {",
            "https://8.8.8.8/../..", "  https://8.8.8.8  ",
        };
        for (String u : urls) {
            both("NetworkPolicy", "requireSafeHttps", S, new Object[] { u }, q(u));
        }
        String[] ips = {
            "0.0.0.0", "127.0.0.1", "10.1.2.3", "169.254.1.1", "172.16.5.5",
            "172.32.5.5", "192.168.0.1", "100.64.1.1", "198.18.1.1",
            "8.8.8.8", "1.1.1.1", "224.0.0.1", "::1", "fc00::1", "fe80::1",
            "2001:db8::1",
        };
        for (String ip : ips) {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                both("NetworkPolicy", "isPrivate",
                    new Class<?>[] { java.net.InetAddress.class },
                    new Object[] { addr }, ip);
            } catch (Exception ignored) { }
        }
    }

    static void numbers() {
        for (long v : LONGS) both("Util", "humanSize", J, new Object[] { v }, "" + v);
        for (int c : COLORS) {
            both("MarkdownRenderer", "hex", I, new Object[] { c }, hex(c));
            for (int a : new int[] { 0, 1, 64, 128, 200, 255 }) {
                both("Theme", "alpha", II, new Object[] { c, a }, hex(c) + "," + a);
            }
            for (float f : FLOATS) {
                both("Theme", "lighten", IF, new Object[] { c, f }, hex(c) + "," + f);
                both("Theme", "darken", IF, new Object[] { c, f }, hex(c) + "," + f);
                for (int d : COLORS) {
                    both("Theme", "mix", IIF, new Object[] { c, d, f },
                        hex(c) + "," + hex(d) + "," + f);
                }
            }
        }
        for (int code : INTS) {
            for (String body : new String[] { null, "", "boom",
                    "{\"error\":{\"message\":\"m\"}}", "quota exceeded",
                    "{\"error\":{\"message\":\"m\",\"type\":\"tokens\"}}" }) {
                both("LlmClient", "httpMessage",
                    new Class<?>[] { int.class, String.class },
                    new Object[] { code, body }, code + "," + q(body));
                for (long ra : new long[] { -1L, 0L, 1L, 42L, 3600L }) {
                    for (String p : new String[] { "openai", "anthropic", "gemini" }) {
                        both("LlmClient", "httpMessage",
                            new Class<?>[] { int.class, String.class, long.class,
                                             String.class },
                            new Object[] { code, body, ra, p },
                            code + "," + q(body) + "," + ra + "," + p);
                    }
                }
            }
        }
        for (long total : new long[] { -1, 0, 100, 1024, 1048576 }) {
            for (long have : new long[] { -1, 0, 50, 100, 1024, 2048 }) {
                for (int st : new int[] { 200, 206, 416, 404 }) {
                    both("Tools", "canResume",
                        new Class<?>[] { long.class, long.class, int.class },
                        new Object[] { total, have, st },
                        total + "/" + have + "/" + st);
                }
            }
        }
    }

    static String hex(int c) { return "0x" + Integer.toHexString(c); }

    /**
     * Deterministic fuzz. A fixed 64-bit LCG (no Math.random, so runs are
     * reproducible and a failure can always be replayed) draws from an alphabet
     * of the characters that actually break these parsers: markdown delimiters,
     * JSON punctuation, HTML brackets, backslash escapes, RTL/zero-width marks,
     * Persian letters, CR/LF/TAB and astral-plane surrogates.
     */
    static long seed = 0x5DEECE66DL;

    static int rnd(int bound) {
        seed = (seed * 6364136223846793005L + 1442695040888963407L);
        int r = (int) ((seed >>> 33) % bound);
        return r < 0 ? -r : r;
    }

    static final char[] ALPHABET = (
        "abcXYZ019 \t\n\r" + "*_~`#>-+[]()|" + "{}\":,\\/" + "<>&;=?%!."
        + "\u200b\u200c\u200e\u202a\u202c\ufeff"
        + "\u0633\u0644\u0627\u0645\u06cc\u0654"
        + "\ud83d\ude00\u00e9\u0000\u000c\u001b"
    ).toCharArray();

    static String fuzzString(int maxLen) {
        int n = rnd(maxLen);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ALPHABET[rnd(ALPHABET.length)]);
        return sb.toString();
    }

    static void fuzz() {
        int rounds = Integer.getInteger("diff.fuzz", 400);
        String[] unary = {
            "Util.cleanUrl", "Util.sanitize", "Util.ext", "Util.mimeOf",
            "Util.kindOf", "Util.isTextMime", "Think.split", "Think.visible",
            "Think.stripForModel", "MarkdownRenderer.normalizeEscapes",
            "MarkdownRenderer.codeLang", "MarkdownRenderer.codeBody",
            "MarkdownRenderer.inlineToHtml", "MarkdownRenderer.inline",
            "Web.htmlToText", "Web.looksBlocked", "Web.stripTags", "Web.unescape",
            "Web.decodeBingHref", "Web.decodeDuckHref", "Web.parseDuck",
            "Web.parseLite", "Web.parseBing", "AgentEngine.stripToolCalls",
            "AgentEngine.parseToolCall", "AgentEngine.extractBalancedObjects",
            "AgentEngine.stripJsonComments", "AgentEngine.tryParse",
            "LlmClient.parseProviderDuration", "LlmClient.classify429",
            "LlmClient.extractError", "Tools.cleanFileName", "Tools.stripMime",
            "Tools.globToRegex", "Tools.unescapePdf", "Icons$SvgPath.parse",
        };
        for (int i = 0; i < rounds; i++) {
            String s = fuzzString(120);
            for (String t : unary) {
                int dot = t.lastIndexOf('.');
                both(t.substring(0, dot), t.substring(dot + 1), S,
                    new Object[] { s }, "fuzz#" + i);
            }
            String a = fuzzString(60), b = fuzzString(30);
            both("Think", "merge", SS, new Object[] { a, b }, "fuzz#" + i);
            both("Web", "resolveUrl", SS, new Object[] { a, b }, "fuzz#" + i);
            both("Web", "extract", SS, new Object[] { a, b }, "fuzz#" + i);
            if (!b.isEmpty()) {
                both("Tools", "countOccurrences", SS, new Object[] { a, b },
                    "fuzz#" + i);
                both("Tools", "fuzzyFind", SS, new Object[] { a, b }, "fuzz#" + i);
                both("Tools", "replaceFirst", SSS, new Object[] { a, b, "R" },
                    "fuzz#" + i);
                both("Tools", "applyOne",
                    new Class<?>[] { String.class, String.class, String.class,
                                     boolean.class },
                    new Object[] { a, b, "R", (i & 1) == 0 }, "fuzz#" + i);
            }
            both("Util", "truncate", SI, new Object[] { a, rnd(40) }, "fuzz#" + i);
            both("Tools", "pickDownloadName",
                new Class<?>[] { String.class, String.class, String.class,
                                 String.class },
                new Object[] { a, b, fuzzString(20), "fb.bin" }, "fuzz#" + i);
        }
        System.out.println("fuzz          : " + rounds
            + " random inputs x " + (unary.length + 9) + " members");
    }

    /** Tool-call extraction: the most failure-prone parser in the app. */
    static void toolCalls() {
        String[] cases = {
            null, "", "no call here",
            "{\"tool\":\"read_file\",\"args\":{\"path\":\"a.kt\"}}",
            "```json\n{\"tool\":\"read_file\",\"args\":{\"path\":\"a.kt\"}}\n```",
            "```\n{\"tool\":\"ls\",\"args\":{}}\n```",
            "prose {\"tool\":\"web_search\",\"args\":{\"query\":\"kotlin\"}} more",
            "{\"tool\":\"write_file\",\"args\":{\"path\":\"x\",\"content\":\"a\\nb\"}}",
            "{\"tool\":\"a\",\"args\":{\"s\":\"}\"}}",
            "{\"tool\":\"a\",\"args\":{\"s\":\"{\"}}",
            "{\"tool\":\"a\",\"args\":{\"s\":\"\\\"}\\\"\"}}",
            "{ \"tool\" : \"spaced\" , \"args\" : { } }",
            "{\"name\":\"alt_key\",\"args\":{}}",
            "{\"tool\":\"no_args\"}",
            "{\"args\":{},\"tool\":\"reordered\"}",
            "{\"tool\":\"x\",\"args\":{}} {\"tool\":\"y\",\"args\":{}}",
            "// leading comment\n{\"tool\":\"c\",\"args\":{}}",
            "{\"tool\":\"c\",\"args\":{}} /* trailing */",
            "{\"tool\":\"c\",\"args\":{/*inner*/}}",
            "{\"tool\":\"t\",\"args\":{\"trailing\":1,}}",
            "{\"tool\":\"deep\",\"args\":{\"a\":{\"b\":{\"c\":[1,[2,[3]]]}}}}",
            "{\"tool\":\"unicode\",\"args\":{\"p\":\"مسیر/فایل.txt\"}}",
            "{\"tool\":\"web_fetch\",\"args\":{\"url\":\"https://x.com/a b\"}}",
            "{\"tool\":", "{\"tool\":\"x\",\"args\":", "}{",
            "{\"tool\":\"x\",\"args\":\"stringnotobject\"}",
            "{\"tool\":123,\"args\":{}}",
            "text\n```tool\n{\"tool\":\"fenced_lang\",\"args\":{}}\n```\ntext",
            "{\n  \"tool\": \"pretty\",\n  \"args\": {\n    \"k\": \"v\"\n  }\n}",
            "I will now run {\"tool\":\"bash\"} and then stop.",
            "{\"tool\":\"esc\",\"args\":{\"c\":\"line1\\\\nline2\"}}",
            "{\"tool\":\"http\",\"args\":{\"u\":\"http://x/?a=1&b=2\"}}",
        };
        for (String c : cases) {
            Object[] a1 = { c };
            both("AgentEngine", "parseToolCall", S, a1, q(c));
            both("AgentEngine", "stripToolCalls", S, a1, q(c));
            both("AgentEngine", "extractBalancedObjects", S, a1, q(c));
            both("AgentEngine", "stripJsonComments", S, a1, q(c));
            both("AgentEngine", "tryParse", S, a1, q(c));
        }
        String[] jsons = {
            "{}", "{\"path\":\"a.txt\"}", "{\"path\":\"a\",\"content\":\"x\\ny\"}",
            "{\"query\":\"سلام\"}", "{\"url\":\"https://x\"}", "{\"n\":42,\"b\":true}",
            "{\"long\":\"" + rep("z", 400) + "\"}", "{\"nested\":{\"a\":1}}",
            "{\"arr\":[1,2,3]}", "{\"null\":null}",
            "{\"path\":\"p\",\"old_string\":\"a\",\"new_string\":\"b\"}",
        };
        for (String j : jsons) {
            compare("AgentEngine.summarizeArgs", j,
                withJson(JV, "AgentEngine", "summarizeArgs", j),
                withJson(KT, "AgentEngine", "summarizeArgs", j));
        }
    }

    static Object withJson(ClassLoader cl, String cls, String name, String json) {
        try {
            Class<?> jo = Class.forName("org.json.JSONObject", true, cl);
            Object arg = jo.getConstructor(String.class).newInstance(json);
            final Object[] rm = resolve(cl, cls, name, jo);
            return timed(cls + "." + name,
                () -> ((Method) rm[1]).invoke(rm[0], arg));
        } catch (Throwable t) {
            return unwrap(t);
        }
    }

    /** Tools' pure helpers: file naming, fuzzy edit matching, PDF/zip decoding. */
    static void toolHelpers() {
        String[] names = {
            null, "", "a.txt", "  spaced  .txt", "a/b/c.txt", "..", ".",
            "CON", "nul.txt", "very" + rep("long", 80) + ".txt",
            "بارگیری.pdf", "file:name?.txt", "trailing.", "*?<>|\"",
            "%D8%B3%D9%84%D8%A7%D9%85.zip", "a\u0000b.txt", "\u200bzero.txt",
        };
        String[] dispositions = {
            null, "", "attachment; filename=\"a.txt\"",
            "attachment; filename=a.txt", "inline; filename*=UTF-8''%D8%B3.txt",
            "attachment;filename=\"سلام.pdf\"", "attachment; filename=",
            "attachment; filename=\"\"", "garbage",
        };
        String[] urls = {
            null, "", "https://x.com/file.zip", "https://x.com/",
            "https://x.com/a/b/c.tar.gz?q=1", "https://x.com/%D8%B3.pdf",
            "https://x.com/no-name?download=1", "https://x.com/a#frag",
        };
        String[] mimes = { null, "", "text/plain", "application/pdf",
                           "application/octet-stream", "image/png; charset=x" };
        for (String u : urls) {
            for (String d : dispositions) {
                for (String m : mimes) {
                    both("Tools", "pickDownloadName",
                        new Class<?>[] { String.class, String.class, String.class,
                                         String.class },
                        new Object[] { u, d, m, "fallback.bin" },
                        q(u) + "|" + q(d) + "|" + q(m));
                }
            }
        }
        for (String n : names) both("Tools", "cleanFileName", S, new Object[] { n }, q(n));

        String[] haystacks = {
            "", "line one\nline two\nline three\n",
            "  indented\n\tTabbed\n", "a\r\nb\r\nc\r\n",
            "func x() {\n  return 1\n}\n", rep("dup\n", 5),
            "trailing spaces   \nnext\n", "سلام\nدنیا\n",
        };
        String[] needles = {
            "", "line two", "LINE TWO", "  line two  ", "line  two",
            "line\ttwo", "return 1", "dup", "nope", "\n", "a\nb",
            "سلام", "line one\nline two",
        };
        for (String h : haystacks) {
            for (String n : needles) {
                both("Tools", "fuzzyFind", SS, new Object[] { h, n },
                    q(h) + "|" + q(n));
                both("Tools", "countOccurrences", SS, new Object[] { h, n },
                    q(h) + "|" + q(n));
                for (String r : new String[] { "", "X", "multi\nline" }) {
                    both("Tools", "replaceFirst", SSS, new Object[] { h, n, r },
                        q(h) + "|" + q(n) + "|" + q(r));
                    for (boolean all : new boolean[] { true, false }) {
                        both("Tools", "applyOne",
                            new Class<?>[] { String.class, String.class,
                                             String.class, boolean.class },
                            new Object[] { h, n, r, all },
                            q(h) + "|" + q(n) + "|" + q(r) + "|" + all);
                    }
                }
            }
        }
        String[] globs = {
            null, "", "*", "*.kt", "**/*.kt", "a?c", "src/**", "[abc].txt",
            "a.b.c", "**", "*/*", "{a,b}", "a+b(c)", "\\*literal",
        };
        for (String g : globs) both("Tools", "globToRegex", S, new Object[] { g }, q(g));

        byte[][] blobs = {
            new byte[0], "plain text".getBytes(),
            new byte[] { 0, 1, 2, 3 },
            new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 },
            "سلام".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new byte[] { 0x1f, (byte) 0x8b, 8, 0 },
            new byte[] { 'a', 0, 'b', 0 },
            new byte[256],
        };
        for (byte[] b : blobs) {
            both("Tools", "looksBinary", new Class<?>[] { byte[].class },
                new Object[] { b }, "len" + b.length);
            both("Tools", "inflateOrRaw", new Class<?>[] { byte[].class },
                new Object[] { b }, "len" + b.length);
            for (int lim : new int[] { 0, 4, 16, 512 }) {
                both("Tools", "hexPreview", new Class<?>[] { byte[].class, int.class },
                    new Object[] { b, lim }, "len" + b.length + "/" + lim);
            }
        }
        String[] pdf = {
            null, "", "(hello) Tj", "(a\\(b\\)c) Tj", "(line\\ntab\\t) Tj",
            "(\\053\\101) Tj", "(\\\\) Tj", "(unterminated", "(\\f) Tj",
            "[(a) -100 (b)] TJ", "(سلام) Tj", "BT /F1 12 Tf (x) Tj ET",
        };
        for (String p : pdf) both("Tools", "unescapePdf", S, new Object[] { p }, q(p));
        for (String p : pdf) {
            Object a = withBuilder(JV, p), b = withBuilder(KT, p);
            compare("Tools.extractPdfText", q(p), a, b);
        }
        for (String t : new String[] { "read_file", "write_file", "edit_file",
                "delete_path", "make_dir", "move_path", "download_file", "ls",
                "web_search", "web_fetch", "remember", "recall", "glob",
                "search_files", "list_archive", "read_pdf", "unknown_tool",
                null, "", "READ_FILE" }) {
            both("Tools", "isMutating", S, new Object[] { t }, q(t));
            both("Tools", "needsApproval", S, new Object[] { t }, q(t));
        }
    }

    /** extractPdfText writes into a StringBuilder — compare the buffer. */
    static Object withBuilder(ClassLoader cl, String src) {
        try {
            final StringBuilder sb = new StringBuilder();
            final Object[] rm = resolve(cl, "Tools", "extractPdfText",
                String.class, StringBuilder.class);
            Object r = timed("Tools.extractPdfText",
                () -> ((Method) rm[1]).invoke(rm[0], src, sb));
            return r == HUNG ? HUNG : sb.toString();
        } catch (Throwable t) {
            return unwrap(t);
        }
    }

    /** Search-result scraping: three parsers over realistic result HTML. */
    static void webInternals() {
        String duck = "<html><body>"
            + "<div class=\"result results_links\">"
            + "<a class=\"result__a\" href=\"/l/?uddg=https%3A%2F%2Fa.com%2Fp\">"
            + "Title <b>One</b></a>"
            + "<a class=\"result__snippet\">Snippet one text</a></div>"
            + "<div class=\"result results_links\">"
            + "<a class=\"result__a\" href=\"https://b.com\">Two</a>"
            + "<a class=\"result__snippet\">Snip &amp; two</a></div>"
            + "</body></html>";
        String lite = "<table><tr><td>"
            + "<a class=\"result-link\" href=\"//c.com/x\">Lite One</a></td></tr>"
            + "<tr><td class=\"result-snippet\">lite snip</td></tr>"
            + "<tr><td><a class=\"result-link\" href=\"http://d.com\">Lite2</a>"
            + "</td></tr></table>";
        String bing = "<ol id=\"b_results\">"
            + "<li class=\"b_algo\"><h2><a href=\"https://e.com/a\">Bing One</a></h2>"
            + "<p>bing snippet</p></li>"
            + "<li class=\"b_algo\"><h2>"
            + "<a href=\"https://www.bing.com/ck/a?!&&u=a1aHR0cHM6Ly9mLmNvbQ&ntb=1\">"
            + "Bing Two</a></h2><p>second</p></li></ol>";
        String[] pages = { null, "", "<html></html>", duck, lite, bing,
                           duck + lite + bing, "<div class=\"result__a\">broken" };
        for (String p : pages) {
            both("Web", "parseDuck", S, new Object[] { p }, q(p));
            both("Web", "parseLite", S, new Object[] { p }, q(p));
            both("Web", "parseBing", S, new Object[] { p }, q(p));
            both("Web", "htmlToText", S, new Object[] { p }, q(p));
            both("Web", "stripTags", S, new Object[] { p }, q(p));
        }
        String[] hrefs = {
            null, "", "/l/?uddg=https%3A%2F%2Fa.com%2Fp",
            "//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.com&rut=1",
            "https://www.bing.com/ck/a?!&&u=a1aHR0cHM6Ly9mLmNvbQ&ntb=1",
            "https://www.bing.com/ck/a?u=notbase64!!!",
            "https://plain.com/x", "/relative", "?onlyquery",
        };
        for (String h : hrefs) {
            both("Web", "decodeDuckHref", S, new Object[] { h }, q(h));
            both("Web", "decodeBingHref", S, new Object[] { h }, q(h));
        }
        for (int code : INTS) {
            for (String host : new String[] { null, "", "x.com", "duckduckgo.com" }) {
                both("Web", "statusHint", new Class<?>[] { int.class, String.class },
                    new Object[] { code, host }, code + "|" + q(host));
            }
        }
        for (long[] pair : new long[][] { { 0, 0 }, { 100, 0 }, { 0, 100 },
                { 1048576, 200 }, { -1, -1 } }) {
            // headCheck needs the network; skip. join() is pure:
        }
        List<String> joinCases = Arrays.asList("a", "b", "c");
        compare("Web.join", "abc",
            invoke(JV, "Web", "join", new Class<?>[] { List.class },
                new Object[] { joinCases }),
            invoke(KT, "Web", "join", new Class<?>[] { List.class },
                new Object[] { joinCases }));
        compare("Web.join", "empty",
            invoke(JV, "Web", "join", new Class<?>[] { List.class },
                new Object[] { new ArrayList<String>() }),
            invoke(KT, "Web", "join", new Class<?>[] { List.class },
                new Object[] { new ArrayList<String>() }));
    }

    /** The line differ: Java returns List<int[]>, Kotlin List<Row>. Same shape. */
    static void markdownInternals() {
        String[][] pairs = {
            { "", "" }, { "a", "a" }, { "a", "b" }, { "a\nb", "a\nb" },
            { "a\nb\nc", "a\nc" }, { "a\nc", "a\nb\nc" },
            { "one\ntwo\nthree", "one\n2\nthree" },
            { "x", "" }, { "", "y" },
            { "a\na\na", "a\na" }, { "a\nb\nc\nd", "d\nc\nb\na" },
            { rep("l\n", 20), rep("l\n", 20) },
            { "سلام\nدنیا", "سلام\nجهان" },
            { "tab\there", "tab here" },
        };
        for (String[] p : pairs) {
            String[] oldL = p[0].split("\n", -1), newL = p[1].split("\n", -1);
            Object a = invoke(JV, "MarkdownRenderer", "diff",
                new Class<?>[] { String[].class, String[].class },
                new Object[] { oldL, newL });
            Object b = invoke(KT, "MarkdownRenderer", "diff",
                new Class<?>[] { List.class, List.class },
                new Object[] { Arrays.asList(oldL), Arrays.asList(newL) });
            compare("MarkdownRenderer.diff", q(p[0]) + "|" + q(p[1]), a, b);
        }
        for (String s : TEXT) {
            for (String[] rp : new String[][] { { "*", "<b>" }, { "`", "<code>" },
                    { "~~", "<s>" }, { "", "x" } }) {
                both("MarkdownRenderer", "repl", SSS,
                    new Object[] { s, rp[0], rp[1] }, q(s) + "|" + rp[0]);
            }
        }
    }

    /** All 47 icons plus synthetic path-grammar edge cases, op trace for op trace. */
    static void svgPaths() throws Exception {
        Map<?, ?> d = (Map<?, ?>) staticField("Icons", "D", JV);
        List<String> names = new ArrayList<>(strings(d.keySet()));
        java.util.Collections.sort(names);
        for (String n : names) {
            both("Icons$SvgPath", "parse", S,
                new Object[] { String.valueOf(d.get(n)) }, n);
        }
        String[] edge = {
            "", "M0 0", "m1 1 l2 2", "M0,0L10,10Z", "M0 0 H5 V5 h-5 v-5 z",
            "M0 0 C1 1 2 2 3 3", "M0 0 c1 1 2 2 3 3 s1 1 2 2",
            "M0 0 Q1 1 2 2 T3 3", "M0 0 q1 1 2 2 t3 3",
            "M0 0 A5 5 0 0 1 10 10", "M0 0 a5 5 0 1 0 10 10",
            "M0 0 A5 5 0 1 1 0 0", "M0 0 A0 0 0 0 1 5 5",
            "M0 0A1.5.5 0 01 3.5.5", "M.5.5L1.5-.5", "M1e2 1E-2 L2 2",
            "M0 0 L 1 1 L 2 2 L 3 3 Z M 4 4 L 5 5",
            "M0 0 l1 1 2 2 3 3", "M0 0 z z z", "Z", "M", "M0",
            "M0 0 L", "M0 0 X9 9", "  M 0   0   L  1   1  ",
            "M-0-0l-1-1", "M0 0 A 5 5 45 1 1 10 0", "M0 0 A 5 5 -45 0 0 10 0",
            "M0 0 C", "M0 0 S1 1 2 2", "M0 0 T1 1", "M1,2 3,4 5,6",
        };
        for (String e : edge) {
            both("Icons$SvgPath", "parse", S, new Object[] { e }, q(e));
        }
    }

    /** apply(dark) must produce an identical palette on both sides. */
    static void themePalette() throws Exception {
        for (boolean dark : new boolean[] { true, false, true }) {
            invoke(JV, "Theme", "apply", new Class<?>[] { boolean.class },
                new Object[] { dark });
            invoke(KT, "Theme", "apply", new Class<?>[] { boolean.class },
                new Object[] { dark });
            Class<?> a = Class.forName("com.vepro.code.Theme", true, JV);
            Class<?> b = Class.forName("com.vepro.code.Theme", true, KT);
            for (Field f : a.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != int.class && f.getType() != boolean.class
                    && f.getType() != int[].class) continue;
                Field g = b.getDeclaredField(f.getName());
                f.setAccessible(true);
                g.setAccessible(true);
                compare("Theme.apply(dark=" + dark + ")", f.getName(),
                    f.get(null), g.get(null));
            }
        }
    }

    /** Endpoint building and protocol resolution: silent breakage here is fatal. */
    static void clientRouting() {
        String[] bases = {
            null, "", "https://api.openai.com/v1", "https://api.openai.com/v1/",
            "https://api.anthropic.com", "https://generativelanguage.googleapis.com",
            "https://generativelanguage.googleapis.com/v1beta",
            "https://api.openai.com/v1/chat/completions",
            "https://api.anthropic.com/v1/messages",
            "https://openrouter.ai/api/v1", "https://x.com/v1/messages/",
            "http://127.0.0.1:8080", "http://127.0.0.1:8080/v1",
            "https://host/deep/path/", "api.local/v1", "https://x.com//v1//",
            "https://x.com/v1/models/m:generateContent",
        };
        String[] provs = { null, "", "auto", "openai", "anthropic", "gemini",
                           "OpenAI", "unknown" };
        // resolveProtocol's return domain — endpointFor takes a protocol, not a
        // provider. It is declared non-null in the port because the only caller
        // feeds it resolveProtocol(), which never returns null.
        String[] protocols = { "openai", "anthropic", "gemini", "unknown" };
        String[] models = { null, "", "gpt-4o", "claude-3-5-sonnet-latest",
                            "gemini-2.0-flash", "o1-mini", "grok-2",
                            "anthropic/claude-3.5", "models/gemini-pro",
                            "gpt-5-thinking", "deepseek-reasoner" };
        for (String base : bases) {
            for (String prov : provs) {
                for (String model : models) {
                    String lbl = q(base) + "|" + prov + "|" + model;
                    both("LlmClient", "resolveProtocol", SSS,
                        new Object[] { base, prov, model }, lbl);
                    both("LlmClient", "isReasoningModel", S,
                        new Object[] { model }, q(model));
                }
            }
        }
        for (String base : bases) {
            for (String model : models) {
                for (String protocol : protocols) {
                    for (boolean stream : new boolean[] { true, false }) {
                        both("LlmClient", "endpointFor",
                            new Class<?>[] { String.class, String.class,
                                             String.class, boolean.class },
                            new Object[] { base, model, protocol, stream },
                            q(base) + "|" + model + "|" + protocol + "|" + stream);
                    }
                }
            }
        }
        for (String lvl : new String[] { null, "", "off", "low", "medium", "high",
                                         "max", "LOW", "weird" }) {
            both("Prefs", "thinkingBudgetForLevel", S, new Object[] { lvl }, q(lvl));
        }
    }
}
