package github.vega.agent

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Inflater
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every tool the agent can call: the file system (confined to the app
 * workspace), resumable downloads, archive inspection, PDF text extraction,
 * web search/fetch delegation and long-term memory.
 *
 * Each tool runs on its own daemon thread so a wedged syscall can be abandoned,
 * and every path goes through [resolve], which enforces workspace containment.
 */
class Tools(context: Context) {

    private val ctx: Context = context.applicationContext
    private val memory: Memory
    private val prefs: Prefs

    /**
     * Cancellation token for the tool call running on THIS thread.
     *
     * A plain volatile field used to live here: two tool calls sharing one
     * Tools instance then shared one token, so stopping one run could cancel
     * -- or fail to cancel -- the other. Thread-local, so each run sees only
     * its own. (Anonymous subclass, not withInitial: withInitial is API 24.)
     */
    private val activeToken = object : ThreadLocal<CancellationToken>() {
        override fun initialValue(): CancellationToken = CancellationToken()
    }

    private fun noteRead(file: File) {
        try {
            noteReadPath(file.canonicalPath)
        } catch (e: Exception) {
            noteReadPath(file.absolutePath)
        }
    }

    private fun hasBeenRead(file: File): Boolean {
        return try {
            READ_PATHS.contains(file.canonicalPath)
        } catch (e: Exception) {
            READ_PATHS.contains(file.absolutePath)
        }
    }

    init {
        memory = Memory(ctx)
        prefs = Prefs(ctx)
        // Enable WebView-backed "human mode" fetching (anti-bot / JS challenges).
        HumanFetch.init(ctx)
    }

    /** Constant tool names, shared with Prefs and the system prompt. */
    object ToolNames {
        const val DELETE = "delete_path"
        const val DOWNLOAD = "download_file"
        const val EDIT_FILE = "edit_file"
        const val FILE_INFO = "file_info"
        const val GLOB = "glob"
        const val LIST_ARCHIVE = "list_archive"
        const val LIST_DIR = "list_dir"
        const val MKDIR = "make_dir"
        const val MOVE = "move_path"
        const val READ_ARCHIVE_ENTRY = "read_archive_entry"
        const val EXTRACT_ARCHIVE_ENTRY = "extract_archive_entry"
        const val READ_FILE = "read_file"
        const val READ_PDF = "read_pdf"
        const val RECALL = "recall"
        const val REMEMBER = "remember"
        const val SEARCH = "search_files"
        const val WEB_FETCH = "web_fetch"
        const val WEB_FETCH_MANY = "web_fetch_many"
        const val WEB_SEARCH = "web_search"
        const val WRITE_FILE = "write_file"

        /**
         * Installs an agent skill into the app's skills folder from a name,
         * a description and the full skill instructions (SKILL.md body).
         * This is the tool the agent uses when the user asks it to find
         * skills for a topic on the web and install them.
         */
        const val INSTALL_SKILL = "install_skill"

        /**
         * Dynamic Workflow's delegation tool. Executed by AgentEngine (it spawns
         * a sub-agent rather than touching the device), but it must be a KNOWN
         * name here or the tool-call parser discards the invocation as ordinary
         * JSON the model was merely displaying.
         */
        const val TASK = "task"
    }

    // ---- path safety -------------------------------------------------------

    /**
     * Resolves a tool-supplied path against the workspace root and refuses
     * anything that escapes it or touches the app's own private data.
     */
    fun resolve(path: String?): File {
        val file: File
        if (path.isNullOrBlankJava()) {
            file = externalRoot(ctx)
        } else {
            var trimmed = path.trimJava()
            if (trimmed.startsWith("~")) {
                trimmed = externalRoot(ctx).absolutePath + trimmed.substring(1)
            }
            // Percent-encoded paths still arrive: an older prompt told the
            // model to write %20 for spaces, and copied paths keep their
            // encoding. A literal "%20" almost never names a real file, so
            // when the raw spelling does not exist, try the decoded spelling
            // before giving up — while never letting the encoded form win over
            // a file that genuinely exists under its literal name.
            val requested = File(trimmed)
            val literal = if (requested.isAbsolute) requested else File(externalRoot(ctx), trimmed)
            file = if (!literal.exists() && trimmed.contains('%')) {
                val decoded = decodePercent(trimmed)
                if (decoded != trimmed) {
                    val d = File(decoded)
                    if (d.isAbsolute) d else File(externalRoot(ctx), decoded)
                } else {
                    literal
                }
            } else {
                literal
            }
        }
        val root: String
        val candidate: String
        try {
            root = externalRoot(ctx).canonicalPath
            candidate = file.canonicalPath
        } catch (error: IOException) {
            // An I/O failure while resolving is NOT a policy refusal: report
            // it as a transient failure so the model retries instead of
            // treating the path as forbidden.
            throw IOException(
                "That file path could not be resolved (" + error.message +
                    ") — a transient I/O failure, not a refusal. Retry the call.",
                error
            )
        }
        if (candidate != root && !candidate.startsWith(root + File.separator)) {
            throw SecurityException(
                "path is outside the workspace. Allowed root: " + root +
                " — pass an absolute path under that root (or a path relative " +
                "to it). If the file really is elsewhere on the device, storage " +
                "access may not be granted yet."
            )
        }
        if (isProtectedPath(file)) {
            throw SecurityException(
                "The app's own private data (keys and settings) is off limits: " +
                    file.absolutePath
            )
        }
        return file
    }

    /**
     * Blocks the agent's file tools from touching the app's own private data
     * dir (where prefs / encrypted key live) — defence-in-depth against leaks.
     */
    private fun isProtectedPath(target: File): Boolean {
        try {
            val p = target.canonicalPath
            val pkg = ctx.packageName
            if (p == "/data/data/$pkg" || p.startsWith("/data/data/$pkg/") ||
                p == "/data/user/0/$pkg" || p.startsWith("/data/user/0/$pkg/")
            ) {
                return true
            }
            // getDataDir() is API 24+; on Android 6 it throws NoSuchMethodError
            // (an Error, which the catch below cannot see), breaking every
            // file tool. The hard-coded paths above already cover API 23.
            if (Build.VERSION.SDK_INT >= 24) {
                val dataDir = ctx.dataDir
                if (dataDir != null) {
                    val d = dataDir.canonicalPath
                    if (p == d || p.startsWith("$d/")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
        }
        return false
    }

    /** Redacts the stored API key from any text the agent tries to persist. */
    private fun redactSecrets(text: String?): String? {
        if (text == null) {
            return null
        }
        try {
            val key = prefs.apiKey()
            if (key.trimJava().length >= 8 && text.contains(key.trimJava())) {
                return text.replace(key.trimJava(), "[REDACTED_API_KEY]")
            }
        } catch (e: Exception) {
        }
        return text
    }

    /**
     * The `path` argument, tolerating the aliases models actually emit.
     *
     * A call that says `file_path` instead of `path` used to read as an EMPTY
     * path, which resolves to the workspace root — so the tool reported
     * "not found" (or "is a directory") for a file the model had named
     * perfectly well, and the user saw an edit fail for no visible reason.
     * Accepting the obvious synonyms costs nothing and removes a whole class of
     * confusing failures.
     */
    /** Aliases the model actually emits for a file path, in priority order. */
    private val PATH_KEYS = arrayOf(
        "path", "file_path", "filepath", "file", "filename", "target",
        "dir", "directory", "folder", "destination", "dest", "src", "source"
    )

    /**
     * The argument keys the model actually sent, for error messages.
     *
     * A bare "requires path" used to leave the model guessing which of its
     * keys was wrong; echoing the received keys lets it self-correct in one
     * retry instead of three.
     */
    private fun receivedKeys(args: JSONObject): String {
        val out = ArrayList<String>()
        try {
            val it = args.keys()
            while (it.hasNext() && out.size < 8) {
                out.add(it.next())
            }
        } catch (e: Exception) {
        }
        return if (out.isEmpty()) "(none)" else out.joinToString(", ")
    }

    /**
     * Unwraps one level of accidental nesting: some providers emit
     * `{"arguments": {"path": "..."}}` or `{"input": {"paths": [...]}}`.
     * Only unwraps when no path key matched at the top level AND the object
     * holds exactly one key whose value is itself an object containing a
     * path key — never a guess, so a legit single-key call is untouched.
     */
    private fun unwrappedArgs(args: JSONObject): JSONObject {
        if (args.length() != 1) {
            return args
        }
        for (key in PATH_KEYS) {
            if (args.has(key)) {
                return args
            }
        }
        if (args.has("paths") || args.has("files")) {
            return args
        }
        try {
            val it = args.keys()
            if (!it.hasNext()) {
                return args
            }
            val only = it.next()
            val inner = args.optJSONObject(only) ?: return args
            for (key in PATH_KEYS) {
                if (inner.has(key)) {
                    return inner
                }
            }
            if (inner.has("paths") || inner.has("files")) {
                return inner
            }
        } catch (e: Exception) {
        }
        return args
    }

    /**
     * Every path the call names, for tools that accept several.
     *
     * Accepts `paths`/`files` as a JSON array, as one string, or as a
     * comma/semicolon/newline-separated list inside one string — the three
     * shapes models actually emit — plus every single-path alias via
     * [pathArg]. Deduplicated, order-preserving.
     */
    internal fun pathListArg(rawArgs: JSONObject): List<String> {
        val args = unwrappedArgs(rawArgs)
        val out = ArrayList<String>()
        val multiKeys = arrayOf("paths", "files")
        for (key in multiKeys) {
            val arr = args.optJSONArray(key)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optStr(i, "").trimJava()
                    if (v.isNotEmpty() && !out.contains(v)) {
                        out.add(v)
                    }
                }
                return out
            }
            val single = args.optStr(key, "").trimJava()
            if (single.isNotEmpty()) {
                // One string holding several paths, or just one path.
                val parts = single.split(',', ';', '\n', '\r')
                for (part in parts) {
                    val v = part.trimJava()
                    if (v.isNotEmpty() && !out.contains(v)) {
                        out.add(v)
                    }
                }
                return out
            }
        }
        val one = pathArg(args)
        if (one.isNotEmpty()) {
            out.add(one)
        }
        return out
    }

    private fun pathArg(rawArgs: JSONObject): String {
        val args = unwrappedArgs(rawArgs)
        for (key in PATH_KEYS) {
            val value = args.optStr(key, "").trimJava()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    /** Every non-empty path-alias value, in priority order, deduplicated. */
    private fun pathArgValues(rawArgs: JSONObject): List<String> {
        val args = unwrappedArgs(rawArgs)
        val out = ArrayList<String>()
        for (key in PATH_KEYS) {
            val value = args.optStr(key, "").trimJava()
            if (value.isNotEmpty() && !out.contains(value)) {
                out.add(value)
            }
        }
        return out
    }

    /**
     * Repairs the model's most common path mistake:
     * `{"path": "<a directory>", "filename": "<a name>"}`.
     *
     * pathArg() takes the directory and the name is silently dropped — which
     * is exactly the "that path is a folder, not a file" failure. When the
     * winning value is an existing directory and another alias is a bare file
     * name, the intent is unambiguous, so join them instead of failing.
     * Returns null when this repair does not apply.
     */
    private fun joinDirAndFilename(args: JSONObject, winner: String): File? {
        val dir = try {
            resolve(winner)
        } catch (e: Exception) {
            return null
        }
        if (!dir.isDirectory) {
            return null
        }
        for (value in pathArgValues(args)) {
            if (value == winner) {
                continue
            }
            if (!value.contains('/') && !value.contains('\\') && !value.startsWith("~") &&
                value != "." && value != ".."
            ) {
                return resolve(File(dir, value).absolutePath)
            }
        }
        return null
    }

    /**
     * A different mistake: two aliases naming two genuinely DIFFERENT paths
     * (`{"path": "a.txt", "file": "b.txt"}`). Acting on one and silently
     * dropping the other is data confusion; refuse with both named so the
     * model sends exactly one path on the retry.
     */
    private fun pathConflictError(args: JSONObject, winner: String, tool: String): String? {
        for (value in pathArgValues(args)) {
            if (value != winner) {
                return "ERROR: conflicting paths in " + tool + " — one call must " +
                    "name exactly one path, but several path arguments were given " +
                    "(\"" + Util.truncate(winner, 80) + "\" and \"" +
                    Util.truncate(value, 80) + "\").\n" +
                    "Send the call again with a single \"path\". Nothing was changed."
            }
        }
        return null
    }

    /** The outcome of resolving a tool's single path argument. */
    private class ResolvedTarget(val file: File?, val error: String?)

    /**
     * Resolves the single path argument for a file tool, model-proofed:
     * blank input becomes a "needs a path" error (never a silent resolve to
     * the workspace root), a directory+filename pair is joined, and two
     * genuinely different paths are refused with both named.
     */
    private fun resolveTargetArg(args: JSONObject, tool: String): ResolvedTarget {
        val rawPath = pathArg(args)
        if (rawPath.isEmpty()) {
            return ResolvedTarget(
                null,
                "ERROR: " + tool + " needs a \"path\" — the call named no file or " +
                    "folder at all (received keys: " + receivedKeys(args) + ").\n" +
                    "Send { \"path\": \"<absolute path or a path under the workspace root>\" }. " +
                    "Nothing was changed."
            )
        }
        val joined = joinDirAndFilename(args, rawPath)
        if (joined != null) {
            return ResolvedTarget(joined, null)
        }
        val conflict = pathConflictError(args, rawPath, tool)
        if (conflict != null) {
            return ResolvedTarget(null, conflict)
        }
        return ResolvedTarget(resolve(rawPath), null)
    }

    /**
     * Decodes %XX sequences (UTF-8), leaving '+' and malformed '%' runs
     * untouched. java.net.URLDecoder would turn '+' into a space, which
     * corrupts real file names — so this is done by hand.
     */
    internal fun decodePercent(s: String): String {
        if (!s.contains('%')) {
            return s
        }
        val bytes = ByteArrayOutputStream()
        var i = 0
        var changed = false
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hexVal(s[i + 1])
                val lo = hexVal(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write(hi * 16 + lo)
                    i += 3
                    changed = true
                    continue
                }
            }
            val raw = c.toString().toByteArray(Charsets.UTF_8)
            bytes.write(raw, 0, raw.size)
            i++
        }
        return if (changed) String(bytes.toByteArray(), Charsets.UTF_8) else s
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * Turns a raw storage failure into something the model can act on. A bare
     * "EACCES (Permission denied)" on an internal temp path tells the model
     * nothing about what to fix; the folder it actually wanted and the remedy
     * (grant storage access, or stay under the workspace) do.
     */
    private fun storageError(what: String, target: File, e: Exception? = null): String {
        val msg = e?.message ?: ""
        val denied = msg.contains("EACCES", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true)
        val where = try {
            target.absolutePath
        } catch (ignored: Exception) {
            target.toString()
        }
        return if (denied) {
            "ERROR: " + what + " — storage access was denied:\n" + where +
                "\nThe app may not have storage permission yet. Ask the user to " +
                "grant storage access (the banner at the top of the chat), or use " +
                "a path under the app workspace instead. Nothing was changed." +
                (if (msg.isNotEmpty()) "\nUnderlying error: " + msg else "")
        } else {
            "ERROR: " + what + ": " + where +
                (if (msg.isNotEmpty()) "\nReason: " + msg else "") +
                "\nNothing was changed."
        }
    }

    /** True when [file] is a symlink (never traversed, only ever deleted as a link). */
    private fun isSymlink(file: File): Boolean {
        return try {
            file.canonicalPath != file.absolutePath
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The storage volume a canonical path lives on, for move diagnostics.
     * /storage/emulated/0/… → /storage/emulated/0; /storage/ABCD-1234/… →
     * /storage/ABCD-1234. Anything else falls back to its first segment.
     */
    private fun volumeRootOf(canonical: String): String {
        if (canonical.startsWith("/storage/")) {
            val rest = canonical.substring("/storage/".length)
            val slash = rest.indexOf('/')
            return if (slash < 0) canonical else "/storage/" + rest.substring(0, slash)
        }
        val rest = canonical.trimStart('/')
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else "/" + rest.substring(0, slash)
    }

    private fun sameVolume(a: File, b: File): Boolean {
        return try {
            volumeRootOf(a.canonicalPath) == volumeRootOf(b.canonicalPath)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * One lock per file path, so two parallel edit_file calls cannot
     * read-modify-write over each other and silently lose one edit.
     * computeIfAbsent is API 24+, so this is a synchronized HashMap instead.
     */
    private val editLockMap = HashMap<String, Any>()

    private fun editLockFor(file: File): Any {
        val key = try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }
        synchronized(editLockMap) {
            var lock = editLockMap[key]
            if (lock == null) {
                lock = Any()
                if (editLockMap.size > 2000) {
                    editLockMap.clear()
                }
                editLockMap[key] = lock
            }
            return lock
        }
    }

    private fun rel(file: File): String = file.absolutePath

    /**
     * Saves a fetched page's text under `<workspace>/.webpages/` and appends the
     * path to the tool result, so the model can re-read it with read_file later.
     *
     * A tool result only survives in the transcript, and a long conversation
     * eventually compacts it away — so the agent would "forget" a page it had
     * read minutes earlier and, worse, refetch it. A file on disk is permanent,
     * greppable and window-readable, which is what makes "read this site and
     * remember it" actually hold.
     *
     * Failure here is never fatal: the page text is already in the result, so a
     * read-only filesystem just means no cache line is appended.
     */
    private fun cacheFetchedPage(url: String?, result: String): String {
        if (result.startsWith("ERROR") || result.startsWith("BLOCKED") ||
            result.startsWith("CANCELLED") || result.length < 400
        ) {
            return result
        }
        return try {
            val dir = File(externalRoot(ctx), ".webpages")
            if (!dir.isDirectory) {
                dir.mkdirs()
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            var slug = (url ?: "page")
                .replace(Regex("^https?://"), "")
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trimJava()
            if (slug.length > 60) {
                slug = slug.substring(0, 60)
            }
            if (slug.isEmpty()) {
                slug = "page"
            }
            val file = File(dir, webCacheFileName(url, stamp, slug))
            FileOutputStream(file).use { out ->
                out.write(result.toByteArray(Charsets.UTF_8))
            }
            enforceWebCacheCap(dir)
            result + "\n\nSaved full page text to: " + rel(file) +
                "\n(Re-read any part of it later with read_file instead of fetching again.)"
        } catch (ignored: Exception) {
            result
        }
    }

    /**
     * "ERROR: not found" for [target], plus a "did you mean …" hint when a
     * sibling file's name differs by a single character.
     *
     * A streamed tool call can lose or gain one character at a truncation seam
     * (the bug that turned `about_me.txt` into `about_me.xt`). Rather than let
     * the model fail blindly and rewrite the whole file, point it at the obvious
     * near-miss so it self-corrects on the next call.
     */
    private fun notFound(target: File): String {
        // Headline first, path second. The hint used to be appended AFTER the
        // absolute path, so on the one screen that shows a single clipped line it
        // was the first thing cut — the near-miss was computed and then hidden
        // from the only reader who could act on it in one tap.
        val base = "ERROR: no such file — nothing was read.\nPath: " + rel(target)
        try {
            val parent = target.parentFile ?: return base
            val wanted = target.name
            val siblings = parent.listFiles() ?: return base
            var best: String? = null
            for (sibling in siblings) {
                val name = sibling.name
                if (name == wanted) {
                    continue
                }
                if (Math.abs(name.length - wanted.length) <= 1 &&
                    editDistanceWithin1(name, wanted)
                ) {
                    if (best != null) {
                        // More than one near-miss: don't guess between them.
                        return base
                    }
                    best = name
                }
            }
            if (best != null) {
                return "ERROR: no such file — did you mean \"" +
                    Util.truncate(best, 60) + "\"?\nPath: " + rel(target) +
                    "\nA file by that name exists in the same folder and differs by " +
                    "one character. Retry with the corrected name, or call list_dir " +
                    "on the folder to see what is really there."
            }
        } catch (ignored: Exception) {
        }
        return base
    }

    /** True when [a] and [b] are equal or one insert/delete/substitute apart. */
    private fun editDistanceWithin1(a: String, b: String): Boolean {
        if (a == b) {
            return true
        }
        val la = a.length
        val lb = b.length
        if (Math.abs(la - lb) > 1) {
            return false
        }
        if (la == lb) {
            var diffs = 0
            for (i in 0 until la) {
                if (a[i] != b[i] && ++diffs > 1) {
                    return false
                }
            }
            return true
        }
        // Lengths differ by one: check for a single insertion/deletion.
        val shorter = if (la < lb) a else b
        val longer = if (la < lb) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else {
                if (skipped) {
                    return false
                }
                skipped = true
                j++
            }
        }
        return true
    }

    // ---- dispatch ----------------------------------------------------------

    fun run(name: String, args: JSONObject?): String = run(name, args, CancellationToken())

    /**
     * Anything a tool learns that is worth SHOWING but not worth telling the model.
     *
     * Deliberately a per-call listener rather than a field on Tools or a static:
     * tools run on their own thread, several can be in flight across a run, and a
     * shared slot would hand one step's results to another. Optional, so every
     * existing caller is unaffected.
     */
    interface Observer {
        fun onSearchResults(results: List<Web.SearchResult>)
        fun onProgress(detail: String)

        /**
         * A file is about to change, with the before and after text.
         *
         * Reported from inside the tool because this is the only place both sides
         * exist: by the time a result string comes back, `before` has been
         * overwritten on disk. The listener narrows and stores what it wants — the
         * tool does not decide how much to keep, and must not, since it has no idea
         * whether anything is watching.
         */
        fun onFileChange(path: String, before: String, after: String)
    }

    /**
     * Rate-limited progress for the tools that walk a tree or a long list.
     *
     * A recursive search visits thousands of directories, and reporting each one
     * would post thousands of UI updates to a display that can show four a second.
     * This coalesces to one report per [INTERVAL_MS] and drops the rest, which is
     * exactly the shape of TrailView's own timer — and it means a walk can be
     * instrumented at the top of its loop without the caller having to think about
     * the cost of doing so.
     */
    private class Progress(private val observer: Observer, private val root: File) {

        private var lastAt = 0L

        fun at(dir: File, matches: Int) {
            val now = System.currentTimeMillis()
            if (now - lastAt < INTERVAL_MS) {
                return
            }
            lastAt = now
            val where = try {
                val path = dir.absolutePath
                val base = root.absolutePath
                if (path.length > base.length + 1 && path.startsWith(base)) {
                    path.substring(base.length + 1)
                } else {
                    dir.name
                }
            } catch (ignored: Exception) {
                dir.name
            }
            val label = if (where.isEmpty()) Fa.TRAIL_PROG_SCANNING else where
            observer.onProgress(
                if (matches > 0) {
                    label + "  \u00b7  " + Fa.TRAIL_PROG_MATCHED.format(matches.toString())
                } else {
                    label
                }
            )
        }

        private companion object {
            const val INTERVAL_MS = 220L
        }
    }

    /**
     * Runs one tool call on a daemon worker thread and returns its result.
     *
     * NOTE (#10): [clearReadPaths] is deliberately NOT called here. This
     * method runs once per tool call, not once per run — clearing here would
     * wipe the read-before-edit memory between a read_file and the edit_file
     * that legitimately follows it in the same run. The run boundary is
     * detected inside [runInternal] via [onRunToken] instead: a run's first
     * tool call carries the run's fresh CancellationToken (also AgentBus's
     * current token), which no earlier run can present.
     */
    fun run(
        name: String,
        args: JSONObject?,
        token: CancellationToken?,
        observer: Observer? = null
    ): String {
        val effective = token ?: CancellationToken()
        val result = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()

        val worker = Thread({
            try {
                result.set(runInternal(name, args, effective, observer))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "vega-tool-$name")
        worker.isDaemon = true
        worker.start()

        var cancellationRequested = false
        var cancelledAt = 0L
        while (worker.isAlive) {
            if (effective.isCancelled && !cancellationRequested) {
                cancellationRequested = true
                cancelledAt = System.currentTimeMillis()
                worker.interrupt()
            }
            // BUGFIX: a worker wedged in an uninterruptible syscall used to keep
            // this loop (and therefore the whole agent run) spinning forever,
            // leaving the global run slot busy until the process died. After a
            // bounded grace period the daemon worker is abandoned instead — it
            // cannot keep the process alive, and the run frees up cleanly.
            if (cancellationRequested && System.currentTimeMillis() - cancelledAt > 6000L) {
                return "CANCELLED: user stopped this tool"
            }
            try {
                worker.join(80L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                effective.cancel()
                if (!cancellationRequested) {
                    cancellationRequested = true
                    cancelledAt = System.currentTimeMillis()
                }
                worker.interrupt()
            }
        }
        // Order matters: the worker may well have COMPLETED inside the same
        // poll window in which Stop was pressed. Reporting "CANCELLED" then
        // threw away a real result — a download that had already finished
        // writing was reported as stopped while the file sat on disk.
        val error = failure.get()
        val value = result.get()
        if (error == null && value != null) {
            return value
        }
        if (cancellationRequested || effective.isCancelled) {
            return "CANCELLED: user stopped this tool"
        }
        if (error != null) {
            return "ERROR: " + error.javaClass.simpleName + ": " + error.message
        }
        return value ?: "ERROR: tool returned no result"
    }

    private fun runInternal(
        name: String,
        args: JSONObject?,
        token: CancellationToken,
        observer: Observer? = null
    ): String {
        activeToken.set(token)
        // An absent args object is treated as empty rather than dereferenced.
        val a = args ?: JSONObject()
        return try {
            activeToken.get().throwIfCancelled()
            // #10: run boundary for the read-before-edit memory. Placed AFTER
            // the cancellation check so a stopped run's stragglers (cancelled
            // token) can never wipe the new run's reads.
            onRunToken(token)
            when (name) {
                ToolNames.LIST_DIR -> listDir(a)
                ToolNames.READ_FILE -> readFile(a)
                ToolNames.WRITE_FILE -> writeFile(a, observer)
                ToolNames.EDIT_FILE -> editFile(a, observer)
                ToolNames.INSTALL_SKILL -> installSkill(a)
                ToolNames.DELETE -> deletePath(a)
                ToolNames.MKDIR -> makeDir(a)
                ToolNames.MOVE -> movePath(a, observer)
                ToolNames.SEARCH -> searchFiles(a, observer)
                ToolNames.GLOB -> glob(a, observer)
                ToolNames.FILE_INFO -> fileInfo(a)
                ToolNames.DOWNLOAD -> downloadFile(a, activeToken.get(), observer)

                ToolNames.WEB_SEARCH -> if (prefs.webSearch()) {
                    val outcome = Web.searchDetailed(a.optStr("query"), activeToken.get())
                    if (outcome.results.isNotEmpty()) {
                        observer?.onSearchResults(outcome.results)
                    }
                    outcome.text
                } else {
                    "BLOCKED: web tools are disabled in Settings."
                }

                ToolNames.WEB_FETCH -> if (prefs.webSearch()) {
                    val fetchProgress: ((String) -> Unit)? = observer?.let { obs ->
                        { detail: String -> obs.onProgress(detail) }
                    }
                    cacheFetchedPage(
                        a.optStr("url"),
                        webFetchTool(a, activeToken.get(), fetchProgress)
                    )
                } else {
                    "BLOCKED: web tools are disabled in Settings."
                }

                ToolNames.WEB_FETCH_MANY -> if (prefs.webSearch()) {
                    webFetchManyTool(a, activeToken.get())
                } else {
                    "BLOCKED: web tools are disabled in Settings."
                }

                ToolNames.REMEMBER -> {
                    val raw = a.optStr("text")
                    val safe = redactSecrets(raw)
                    val res = memory.remember(safe)
                    if (raw != safe) res + Fa.SECURITY_REDACTED else res
                }

                ToolNames.EXTRACT_ARCHIVE_ENTRY -> extractArchiveEntry(a, activeToken.get(), observer)
                ToolNames.RECALL -> memory.recall()
                ToolNames.LIST_ARCHIVE -> listArchive(a, observer)
                ToolNames.READ_ARCHIVE_ENTRY -> readArchiveEntry(a, observer)
                ToolNames.READ_PDF -> readPdf(a, observer)
                else -> "ERROR: unknown tool '$name'"
            }
        } catch (cancelled: CancellationToken.CancelledException) {
            "CANCELLED: user stopped this tool"
        } catch (e: Exception) {
            "ERROR: " + e.javaClass.simpleName + ": " + e.message
        }
    }

    // ------------------------------------------------------------------
    // web_fetch { url, fast?, max_chars?, depth?, prompt?/extract? }
    // web_fetch_many { urls[] } → parallel fast reads of several pages
    // ------------------------------------------------------------------

    /**
     * web_fetch implementation.
     *
     * The default path streams the page through [WebFast] (single-pass
     * tokenize + readability scoring, first text in under a second) and
     * falls back to the careful [Web.fetch] path on any failure. `depth >=
     * 1` auto-follows the page's top 3 links with [WebFast.prefetch] and
     * appends their digests; `prompt`/`extract` reorders the text for the
     * extraction query (extract-then-compress contract: 400-char chunks
     * ranked by keyword overlap, best first).
     */
    private fun webFetchTool(
        a: JSONObject,
        token: CancellationToken,
        progress: ((String) -> Unit)?
    ): String {
        val url = a.optStr("url")
        val fast = a.optBoolean("fast", true)
        val maxChars = a.optInt("max_chars", 38000).coerceIn(2000, 38000)
        val depth = a.optInt("depth", 0).coerceIn(0, 2)
        val promptArg = a.optStr("prompt", "")
        val extractionPrompt = (if (promptArg.isEmpty()) a.optStr("extract", "") else promptArg)
            .trimJava().ifEmpty { null }
        if (fast) {
            val digest = try {
                token.throwIfCancelled()
                WebFast.fetchFast(
                    url, token,
                    WebFast.FetchOpts(maxChars = maxChars, extractionPrompt = extractionPrompt)
                )
            } catch (cancelled: CancellationToken.CancelledException) {
                return "CANCELLED: user stopped web fetch"
            } catch (e: Exception) {
                null
            }
            if (digest != null) {
                val sb = StringBuilder(Web.formatPageDigest(digest))
                if (depth >= 1 && digest.links.isNotEmpty()) {
                    progress?.invoke(Fa.WEB_PROG_EXTRACTING)
                    val followUrls = digest.links.take(3).map { it.url }
                    val digests = try {
                        WebFast.prefetch(followUrls, token, perPageChars = 4000)
                    } catch (cancelled: CancellationToken.CancelledException) {
                        return "CANCELLED: user stopped web fetch"
                    } catch (e: Exception) {
                        emptyList<String>()
                    }
                    if (digests.isNotEmpty()) {
                        sb.append("\n\n=== FOLLOW-UP READS ===\n")
                        sb.append(digests.joinToString("\n\n"))
                    }
                }
                return cutAtLineBoundary(sb.toString(), 47000)
            }
            // Fast path failed — fall through to the careful path below.
        }
        return Web.fetch(url, token, progress, WebFast.FetchOpts(fast = false))
    }

    /**
     * web_fetch_many implementation: fast-reads several page URLs in
     * parallel and returns their digests, numbered to match the input order.
     */
    private fun webFetchManyTool(a: JSONObject, token: CancellationToken): String {
        val urls = parseUrlList(a)
        if (urls.isEmpty()) {
            return "ERROR: urls is required — pass a JSON array of page URLs, " +
                "e.g. {\"urls\": [\"https://example.com/a\", \"https://example.com/b\"]}"
        }
        val digests = try {
            WebFast.prefetch(urls.take(8), token)
        } catch (cancelled: CancellationToken.CancelledException) {
            return "CANCELLED: user stopped this tool"
        } catch (e: Exception) {
            return "ERROR: web_fetch_many failed: " + e.message
        }
        return cutAtLineBoundary(digests.joinToString("\n\n"), 47000)
    }

    /** Tolerant URL-list parse: JSONArray under "urls", or a single "url". */
    private fun parseUrlList(a: JSONObject): List<String> {
        val out = ArrayList<String>()
        val single = a.optStr("url", "").trimJava()
        if (single.isNotEmpty()) {
            out.add(single)
        }
        val arr = a.optJSONArray("urls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optString(i, "").trimJava()
                if (u.isNotEmpty()) {
                    out.add(u)
                }
            }
        }
        return out
    }

    /**
     * Truncates [text] to [limit] chars at the last newline boundary, so a
     * URL or a sentence is never cut mid-string (#19).
     */
    internal fun cutAtLineBoundary(text: String, limit: Int): String {
        if (text.length <= limit) {
            return text
        }
        val cut = text.lastIndexOf('\n', limit)
        return if (cut > limit / 2) text.substring(0, cut) else text.substring(0, limit)
    }

    // ------------------------------------------------------------------
    // download_file { url, filename? } → saves into the phone's Downloads
    // ------------------------------------------------------------------

    /**
     * Durable staging for partial downloads. This is filesDir (never cache —
     * the system may clear cache at any time), keyed by URL hash, so a
     * download cancelled by the user, killed with the process, or interrupted
     * by a dead radio resumes on the next download_file call for the same URL
     * instead of starting over.
     */
    private fun downloadStagingDir(): File {
        val dir = File(ctx.filesDir, "dl_staging")
        try {
            if (!dir.isDirectory) {
                dir.mkdirs()
            }
        } catch (e: Exception) {
        }
        return dir
    }

    private fun dlKey(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun dlPartFile(key: String): File = File(downloadStagingDir(), "dl_$key.part")

    private fun dlMetaFile(key: String): File = File(downloadStagingDir(), "dl_$key.json")

    /** Metadata for a staged partial download, so a later call can resume it. */
    private class DlMeta(
        val url: String,
        val finalUrl: String?,
        val etag: String?,
        val total: Long,
        val name: String,
        val mime: String?
    )

    private fun emptyToNull(s: String): String? = if (s.isEmpty()) null else s

    private fun readDlMeta(meta: File, url: String): DlMeta? {
        return try {
            if (!meta.isFile) {
                return null
            }
            val o = JSONObject(meta.readText())
            val mUrl = o.optString("url", "")
            if (mUrl != url) {
                return null
            }
            DlMeta(
                mUrl,
                emptyToNull(o.optString("finalUrl", "")),
                emptyToNull(o.optString("etag", "")),
                o.optLong("total", -1L),
                o.optString("name", ""),
                emptyToNull(o.optString("mime", ""))
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDlMeta(meta: File, m: DlMeta) {
        try {
            val o = JSONObject()
            o.put("url", m.url)
            o.put("finalUrl", m.finalUrl ?: "")
            o.put("etag", m.etag ?: "")
            o.put("total", m.total)
            o.put("name", m.name)
            o.put("mime", m.mime ?: "")
            meta.writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    /** Drops staged partials older than 7 days — URLs the user never retried. */
    private fun cleanStaleStaging() {
        try {
            val dir = downloadStagingDir()
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val files = dir.listFiles() ?: return
            for (f in files) {
                val name = f.name
                if ((name.startsWith("dl_") && (name.endsWith(".part") || name.endsWith(".json"))) &&
                    f.lastModified() < cutoff
                ) {
                    try {
                        f.delete()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** True when a 206's Content-Range starts exactly where we left off. */
    private fun rangeStartOk(conn: HttpURLConnection, resumeFrom: Long): Boolean {
        return try {
            val cr = conn.getHeaderField("Content-Range") ?: return false
            cr.startsWith("bytes $resumeFrom-")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True when a 416 (Range Not Satisfiable) means "you already have it
     * all": the server's Content-Range total (the "bytes star-slash-N" form)
     * fits inside the staged file, and agrees with the metadata when we have
     * it. A lying or confused server never validates here — we restart instead.
     */
    private fun rangeAlreadyComplete(
        conn: HttpURLConnection?,
        staged: File,
        meta: DlMeta?
    ): Boolean {
        if (conn == null || meta == null) {
            return false
        }
        return try {
            val cr = conn.getHeaderField("Content-Range") ?: return false
            val total = cr.substringAfter("/").trimJava().toLongOrNull()
                ?: return false
            if (total <= 0) {
                return false
            }
            val have = staged.length()
            have >= total && (meta.total <= 0 || meta.total == total)
        } catch (e: Exception) {
            false
        }
    }

    /** A single download connection + its cancellation registration. */
    private class DlConn {
        var conn: HttpURLConnection? = null
        var watch: CancellationToken.Registration? = null
        var finalUrl: String? = null
        var code = 0
    }

    private fun downloadFile(
        args: JSONObject,
        token: CancellationToken,
        observer: Observer? = null
    ): String {
        val requested = args.optStr("url", "").trimJava()
        if (requested.isEmpty()) {
            return "ERROR: url is required"
        }
        val url0 = Util.cleanUrl(requested) ?: return "ERROR: url is required"
        try {
            NetworkPolicy.requireSafeHttps(url0)
        } catch (blocked: Exception) {
            return "BLOCKED: " + blocked.message
        }
        val referer = args.optStr("referer", "")
        // Durable staging: the partial file and its metadata live past this
        // call, so a cancelled or killed download resumes next time instead
        // of starting over. Deleted on success or hard failure.
        var part: File? = null
        var metaF: File? = null
        var keepStaged = false
        var dlName = ""
        var dlMime: String? = null
        var dlTotal = -1L
        var dlFinalUrl: String? = null
        var dlEtag: String? = null
        var dl: DlConn? = null
        try {
            token.throwIfCancelled()
            // If the exact URL is dead we retry a couple of safe encoding
            // variants (decoded form, %20→_ , %20→-) before giving up — found
            // links often differ from the real file name only in separators.
            val candidates = ArrayList<String>()
            candidates.add(url0)
            try {
                val decoded = URLDecoder.decode(url0, "UTF-8")
                if (decoded != url0 && decoded.startsWith("http") && !decoded.contains(" ")) {
                    candidates.add(decoded)
                }
            } catch (e: Exception) {
            }
            if (url0.contains("%20")) {
                candidates.add(url0.replace("%20", "_"))
                candidates.add(url0.replace("%20", "-"))
            }

            // Phase 1 — establish a live 2xx connection over the candidates.
            // #5: connecting and warming up are bound by a flat 10-minute
            // deadline; the copy phase extends it below, scaled to the file
            // size, so a large file on a slow-but-steady link is not killed
            // for being large.
            val downloadStartAt = System.currentTimeMillis()
            var downloadDeadlineAt = downloadStartAt + 10 * 60 * 1000L
            val lastCode = intArrayOf(0)
            var firstLiveError: Exception? = null
            try {
                dl = firstLive(candidates, referer, token, lastCode, downloadDeadlineAt)
            } catch (cancelled: CancellationToken.CancelledException) {
                throw cancelled
            } catch (security: SecurityException) {
                // A redirect target tripped the URL policy — never warm that up.
                throw security
            } catch (e: Exception) {
                if (token.isCancelled) {
                    throw CancellationToken.CancelledException()
                }
                firstLiveError = e
                // #7: a throw is not a verdict — fall through to the human
                // warmup below instead of skipping it.
            }
            // If the file host answered with an anti-bot wall (403/503/429) —
            // or the first attempt threw transiently (#7) — clear it in a real
            // browser engine (WebView) to harvest the clearance cookie, then
            // retry: download_file replays that solved session automatically.
            // A hard DNS failure is never bot-wall-shaped, so it skips warmup.
            val threwTransiently = firstLiveError != null &&
                firstLiveError !is UnknownHostException
            if (dl == null && HumanFetch.available() &&
                (lastCode[0] == 403 || lastCode[0] == 503 || lastCode[0] == 429 || threwTransiently)
            ) {
                // The browser warmup is part of the 10-minute budget too: never
                // start it already past the deadline, and never let it run past it.
                if (System.currentTimeMillis() > downloadDeadlineAt) {
                    throw IOException("download exceeded its 10-minute deadline")
                }
                val warm = humanWarmup(url0, token, downloadDeadlineAt)
                // #11: an interactive CAPTCHA or a hard block the real browser
                // could not clear either — say so plainly instead of
                // downloading a challenge page.
                if (warm != null && warm.interactiveCaptcha) {
                    return "ERROR: this file's host is behind an interactive CAPTCHA " +
                        "(image challenges only a person can solve). download_file cannot " +
                        "clear it — web_fetch the page where you found this link and pick " +
                        "a different host, or download the file in your own browser."
                }
                if (warm != null && warm.blocked) {
                    return "ERROR: this host still shows an anti-bot challenge after a " +
                        "real-browser retry (HTTP " + lastCode[0] + "). web_fetch the page " +
                        "where you found this link and pick a different host."
                }
                token.throwIfCancelled()
                try {
                    dl = firstLive(candidates, referer, token, lastCode, downloadDeadlineAt)
                } catch (cancelled: CancellationToken.CancelledException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (token.isCancelled) {
                        throw CancellationToken.CancelledException()
                    }
                    return "ERROR: download failed: " + e.javaClass.simpleName + ": " + e.message
                }
            }
            val live = dl
            val conn = live?.conn
            if (live == null || conn == null) {
                return "ERROR: HTTP " + lastCode[0] + " — this link is dead or blocked (" +
                    candidates.size + " variant(s) tried). Do NOT retry this URL and do NOT invent another one. Instead: web_fetch the page where you found this link and pick a DIFFERENT link from its DOWNLOADABLE section (prefer entries marked [OK ✓]), or try a completely different site."
            }

            var total = conn.contentLengthLong
            if (total > 4294967296L) {
                return "ERROR: file exceeds the 4 GB safety limit"
            }
            // Scale the copy deadline to the file size at a 50 KB/s floor: a
            // 500 MB file on a steady link gets ~3 h, not 10 min. A genuinely
            // stalled transfer still fails fast — the copy loop aborts after 3
            // minutes without a byte, and reconnects stay bounded.
            if (total > 0) {
                val scaled = downloadStartAt + total / 50
                if (scaled > downloadDeadlineAt) {
                    downloadDeadlineAt = scaled
                }
            }
            val mime = stripMime(conn.contentType)
            val name = pickDownloadName(
                args.optStr("filename", ""),
                conn.getHeaderField("Content-Disposition"),
                live.finalUrl,
                mime
            )
            dlName = name
            dlMime = mime
            dlTotal = total
            dlFinalUrl = live.finalUrl
            dlEtag = conn.getHeaderField("ETag")

            // Stream to a staged .part file first so aborted downloads never
            // leave half-written garbage in Downloads. The copy is RESUMABLE
            // twice over: within this call it reconnects with Range when the
            // socket drops (app backgrounded, keep-alive closed early), and
            // ACROSS calls it continues a staged partial for the same URL.
            cleanStaleStaging()
            val key = dlKey(url0)
            val staged = dlPartFile(key)
            val stagedMeta = dlMetaFile(key)
            part = staged
            metaF = stagedMeta

            // Disk-space precheck: never start a download that cannot fit.
            if (total > 0) {
                val free = downloadStagingDir().usableSpace
                if (free > 0 && free < total) {
                    return "ERROR: not enough free space for this download (" +
                        Util.humanSize(total) + " needed, " +
                        Util.humanSize(free) + " free)"
                }
            }

            // Cross-call resume: a previous call staged a partial file for
            // this exact URL. Reconnect with Range and continue it when the
            // server still serves the same bytes (206 + matching range start
            // + ETag when we have one); otherwise discard the stale partial.
            var startAt = 0L
            val oldMeta = readDlMeta(stagedMeta, url0)
            if (staged.exists() && staged.length() > 0 && oldMeta != null) {
                val resumeDl = try {
                    connectFollowing(
                        oldMeta.finalUrl ?: url0, referer, staged.length(),
                        token, downloadDeadlineAt
                    )
                } catch (e: Exception) {
                    null
                }
                val resumeConn = resumeDl?.conn
                if (resumeDl != null && resumeConn != null && resumeDl.code == 206 &&
                    rangeStartOk(resumeConn, staged.length()) &&
                    (oldMeta.etag == null || oldMeta.etag == resumeConn.getHeaderField("ETag"))
                ) {
                    closeQuiet(dl)
                    dl = resumeDl
                    startAt = staged.length()
                    dlEtag = resumeConn.getHeaderField("ETag")
                    val remaining = resumeConn.contentLengthLong
                    total = if (remaining > 0) {
                        startAt + remaining
                    } else if (oldMeta.total > 0) {
                        oldMeta.total
                    } else {
                        total
                    }
                    dlTotal = total
                    if (total > 0) {
                        val scaled = downloadStartAt + total / 50
                        if (scaled > downloadDeadlineAt) {
                            downloadDeadlineAt = scaled
                        }
                    }
                } else if (resumeDl != null && resumeDl.code == 416 &&
                    rangeAlreadyComplete(resumeConn, staged, oldMeta)
                ) {
                    // The server says our range starts past EOF and its own
                    // total fits inside what we staged: the previous run died
                    // after the last byte. Keep the file; the skipCopy check
                    // below goes straight to verify + save.
                    closeQuiet(resumeDl)
                    startAt = staged.length()
                    total = staged.length()
                    dlTotal = total
                } else {
                    closeQuiet(resumeDl)
                    try {
                        staged.delete()
                    } catch (e: Exception) {
                    }
                    try {
                        stagedMeta.delete()
                    } catch (e: Exception) {
                    }
                }
            }

            val active = dl
                ?: return "ERROR: download connection lost before the copy started"
            // The resume metadata is durable BEFORE the first byte is copied:
            // if the process dies mid-transfer, the next call finds the .part
            // file AND the .json it needs to continue it.
            writeDlMeta(
                stagedMeta,
                DlMeta(url0, dlFinalUrl, dlEtag, dlTotal, dlName, dlMime)
            )
            var skipCopy = false
            if (startAt > 0 && staged.length() >= startAt && dlTotal > 0 &&
                staged.length() >= dlTotal
            ) {
                // The staged file already holds the whole payload (a 416 on
                // the resume probe, or a previous run that died after the
                // last byte but before the save): skip the copy entirely.
                skipCopy = true
            }
            val done = if (skipCopy) {
                staged.length()
            } else {
                copyWithResume(
                    active, staged, url0, referer, total, token, observer,
                    downloadDeadlineAt, dlEtag, startAt
                )
            }
            if (skipCopy) {
                total = staged.length()
                dlTotal = total
            }

            token.throwIfCancelled()
            if (total > 0 && done < total) {
                return "ERROR: download incomplete (" + Util.humanSize(done) + " of " +
                    Util.humanSize(total) +
                    ") — the connection kept dropping after several retries. " +
                    "Try again, or pick a different link/source."
            }
            // Optional integrity check: when the caller knows the expected
            // SHA-256 (release pages publish them), verify BEFORE the file
            // lands in Downloads — a truncated-or-swapped payload must never
            // be presented as a good download.
            val wantSha = args.optStr("sha256", "").trimJava().lowercase()
            if (wantSha.isNotEmpty()) {
                val gotSha = sha256Of(staged)
                if (gotSha != wantSha) {
                    return "ERROR: SHA-256 mismatch — the downloaded file does not " +
                        "match the expected hash (got " + gotSha.take(16) + "…, " +
                        "expected " + wantSha.take(16) + "…). The file was NOT saved; " +
                        "the source may be corrupt or serving the wrong payload."
                }
            }
            val savedPath = saveIntoDownloads(staged, name, mime, token)
            return "OK: downloaded \"" + name + "\" (" + Util.humanSize(done) +
                (if (mime != null) ", $mime" else "") + ")\nSaved to: " + savedPath
        } catch (e: CancellationToken.CancelledException) {
            // Keep the partial file and record how to resume it: the next
            // download_file call for this URL continues where this one left
            // off instead of starting over.
            keepStaged = true
            val p = part
            val m = metaF
            if (p != null && m != null && p.exists() && p.length() > 0) {
                writeDlMeta(
                    m,
                    DlMeta(url0, dlFinalUrl, dlEtag, dlTotal, dlName, dlMime)
                )
            }
            return "CANCELLED: user stopped the download"
        } catch (e: UnknownHostException) {
            // Transient: the network may be back on the next call. Keep the
            // partial (with its metadata) so it resumes instead of restarting.
            keepStaged = true
            return "ERROR: cannot resolve host (no internet?): " + e.message
        } catch (e: SocketTimeoutException) {
            // Transient: keep the partial for the next call.
            keepStaged = true
            return "ERROR: connection timed out"
        } catch (e: Exception) {
            if (token.isCancelled) {
                return "CANCELLED: user stopped the download"
            }
            // Any other failure after staging began (a socket that died past
            // the retry budget, an unexpected error): the bytes on disk are
            // still valid, so keep them for the next call rather than forcing
            // a large download to start over. Only success and hard verdicts
            // (SHA mismatch, dead link) drop the staging.
            keepStaged = true
            return "ERROR: download failed: " + e.javaClass.simpleName + ": " + e.message
        } finally {
            closeQuiet(dl)
            // Success and hard failures drop the staging; a cancelled
            // download keeps it (with metadata) for the next call.
            if (!keepStaged) {
                try {
                    part?.delete()
                } catch (e: Exception) {
                }
                try {
                    metaF?.delete()
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Tries each candidate URL until one answers with a 2xx; returns that live
     * connection, or null (with the last non-2xx code in lastCodeOut[0]).
     * [deadlineAt] bounds the whole attempt: a hanging host must not burn the
     * download's 10-minute budget before the copy even starts.
     */
    @Throws(Exception::class)
    private fun firstLive(
        candidates: List<String>,
        referer: String?,
        token: CancellationToken,
        lastCodeOut: IntArray,
        deadlineAt: Long
    ): DlConn? {
        for (candidate in candidates) {
            token.throwIfCancelled()
            if (System.currentTimeMillis() > deadlineAt) {
                throw IOException(
                    "download exceeded its 10-minute deadline while connecting — " +
                        "the host is too slow; try web_fetch instead, or a different source"
                )
            }
            val attempt = connectFollowing(candidate, referer, 0, token, deadlineAt)
            if (attempt.conn != null && attempt.code >= 200 && attempt.code < 300) {
                return attempt
            }
            lastCodeOut[0] = attempt.code
            closeQuiet(attempt)
        }
        return null
    }

    /**
     * Opens a GET to startUrl, following redirects manually (so http↔https hops
     * work), optionally resuming from [resumeFrom] bytes via a Range header.
     * Returns a DlConn at the final non-3xx response (conn==null if redirects
     * ran out).
     */
    @Throws(Exception::class)
    /**
     * Opens the (redirect-following) connection for a download candidate.
     * [deadlineAt] clamps the connect/read timeouts to the remaining overall
     * budget (#5): without it a single hanging connect could burn 20 s past a
     * deadline that was 5 s away.
     */
    private fun connectFollowing(
        startUrl: String,
        referer: String?,
        resumeFrom: Long,
        token: CancellationToken,
        deadlineAt: Long = Long.MAX_VALUE
    ): DlConn {
        var current = startUrl
        var redirects = 0
        while (true) {
            token.throwIfCancelled()
            if (System.currentTimeMillis() > deadlineAt) {
                throw IOException("download exceeded the 10-minute overall deadline")
            }
            NetworkPolicy.requireSafeHttps(current)
            val conn = URL(current).openConnection() as HttpURLConnection
            val watch = token.watchConnection(conn)
            conn.instanceFollowRedirects = false
            val remaining = Math.max(1L, deadlineAt - System.currentTimeMillis())
            conn.connectTimeout = Math.min(20000L, remaining).toInt()
            conn.readTimeout = Math.min(60000L, remaining).toInt()
            applyDownloadHeaders(conn, current, referer, resumeFrom)
            val code: Int
            try {
                code = conn.responseCode
            } catch (e: Exception) {
                watch.close()
                try {
                    conn.disconnect()
                } catch (ignored: Exception) {
                }
                throw e
            }
            if (code >= 300 && code < 400) {
                val location = conn.getHeaderField("Location")
                watch.close()
                try {
                    conn.disconnect()
                } catch (ignored: Exception) {
                }
                if (location == null || ++redirects > 6) {
                    val dead = DlConn()
                    dead.code = code
                    dead.finalUrl = current
                    return dead
                }
                current = URL(URL(current), location).toString()
                NetworkPolicy.requireSafeHttps(current)
                continue
            }
            val out = DlConn()
            out.conn = conn
            out.watch = watch
            out.finalUrl = current
            out.code = code
            return out
        }
    }

    /**
     * Browser-like request headers for a file download. Accept-Encoding is forced
     * to identity so Content-Length matches the raw bytes and byte-range resume is
     * reliable; a clearance cookie harvested in human mode is replayed if present.
     */
    private fun applyDownloadHeaders(
        conn: HttpURLConnection,
        urlForReferer: String,
        referer: String?,
        resumeFrom: Long
    ) {
        conn.setRequestProperty("User-Agent", Web.UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
        conn.setRequestProperty("sec-ch-ua", Web.SEC_CH_UA)
        conn.setRequestProperty("sec-ch-ua-mobile", "?1")
        conn.setRequestProperty("sec-ch-ua-platform", "\"Android\"")
        conn.setRequestProperty("Sec-Fetch-Dest", "empty")
        conn.setRequestProperty("Sec-Fetch-Mode", "no-cors")
        conn.setRequestProperty("Sec-Fetch-Site", "same-origin")

        var ref = referer
        if (ref.isNullOrEmpty()) {
            // default the Referer to the file's own origin — many hosts reject
            // hot-linked downloads without it
            ref = try {
                val u = URL(urlForReferer)
                u.protocol + "://" + u.host + "/"
            } catch (e: Exception) {
                ""
            }
        }
        if (!ref.isNullOrEmpty()) {
            conn.setRequestProperty("Referer", ref)
        }
        try {
            val cookie = HumanFetch.cookiesFor(URL(urlForReferer).host)
            if (!cookie.isNullOrEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
            }
        } catch (e: Exception) {
        }
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
    }

    /**
     * Streams [dl] into [tmp], resuming with Range requests whenever the stream
     * breaks before the known total is reached. Returns the bytes written.
     */
    @Throws(Exception::class)
    /**
     * Copies a download connection to disk, reconnecting and resuming as
     * needed. [deadlineAt] is the hard overall deadline (#5): once it
     * passes, the read loop and the reconnect loop both stop with a
     * readable error instead of retrying forever.
     */
    private fun copyWithResume(
        dl: DlConn,
        tmp: File,
        url: String,
        referer: String?,
        total: Long,
        token: CancellationToken,
        observer: Observer? = null,
        deadlineAt: Long = Long.MAX_VALUE,
        expectEtag: String? = null,
        startAt: Long = 0L
    ): Long {
        // startAt > 0 continues a staged partial from a previous call; the
        // file already holds those bytes, so we append to it.
        var done = startAt
        var lastUi = 0L
        var attempts = 0
        var append = startAt > 0
        // Stall detector: a socket that stops delivering without closing must
        // not burn the deadline silently — it trips a reconnect like any
        // other broken stream.
        var lastByteAt = System.currentTimeMillis()

        while (true) {
            var input: InputStream? = null
            var streamEnded = false
            try {
                val active = dl.conn ?: throw IOException("download connection closed")
                // held in a non-null local: a captured `var` cannot be smart-cast
                // inside the use{} lambda below
                val stream = active.inputStream
                input = stream
                FileOutputStream(tmp, append).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (System.currentTimeMillis() - lastByteAt > 180000L) {
                            throw IOException(
                                "download stalled: no bytes for 3 minutes at " +
                                    Util.humanSize(done)
                            )
                        }
                        val n = stream.read(buffer)
                        if (n < 0) {
                            break
                        }
                        token.throwIfCancelled()
                        if (System.currentTimeMillis() > deadlineAt) {
                            throw IOException(
                                "download exceeded the 10-minute overall deadline " +
                                    "at " + Util.humanSize(done) + " — try web_fetch instead, " +
                                    "or retry with a smaller/faster source"
                            )
                        }
                        output.write(buffer, 0, n)
                        done += n
                        lastByteAt = System.currentTimeMillis()
                        if (done > 4294967296L) {
                            throw Exception("file exceeds the 4 GB safety limit")
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 600) {
                            lastUi = now
                            reportProgress(done, total, observer)
                        }
                    }
                }
                streamEnded = true
            } catch (ce: CancellationToken.CancelledException) {
                throw ce
            } catch (ioe: IOException) {
                token.throwIfCancelled()
                if (!canResume(total, done, attempts)) {
                    if (total <= 0 && done > 0) {
                        return done // unknown length: keep what we managed to read
                    }
                    throw ioe
                }
                // otherwise fall through to reconnect
            } finally {
                if (input != null) {
                    try {
                        input.close()
                    } catch (ignored: Exception) {
                    }
                }
            }

            if (streamEnded) {
                if (total <= 0 || done >= total) {
                    return done // finished (or length unknown and stream closed)
                }
                if (!canResume(total, done, attempts)) {
                    throw IOException("stream ended early at $done/$total")
                }
            }

            // Reconnect and resume from `done`. The CONNECT itself is retried in an
            // inner loop so a transient non-2xx never falls back into reading a
            // closed socket — `dl.conn` is only re-armed once we truly have a live
            // stream, so the outer loop's read can never hit a null connection.
            var reconnected = false
            while (!reconnected) {
                attempts++
                if (attempts > 12 || System.currentTimeMillis() > deadlineAt) {
                    throw IOException(
                        "download exceeded its retry/deadline budget at " +
                            Util.humanSize(done) + " — the host is not resuming cleanly; " +
                            "try web_fetch instead, or a different source"
                    )
                }
                closeQuiet(dl)
                if (sleepCancelable(Math.min(4000L, 400L * attempts), token)) {
                    throw CancellationToken.CancelledException()
                }
                reportProgress(done, total)
                // #6: the reconnect itself is transient-fallible — a thrown
                // connect error is retried (bounded above), not fatal.
                val retry = try {
                    connectFollowing(url, referer, done, token, deadlineAt)
                } catch (cancelled: CancellationToken.CancelledException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (token.isCancelled) {
                        throw CancellationToken.CancelledException()
                    }
                    continue
                }
                if (retry.code == 206) {
                    val rConn = retry.conn
                    val okRange = rConn != null && rangeStartOk(rConn, done)
                    val okEtag = expectEtag == null || rConn == null ||
                        expectEtag == rConn.getHeaderField("ETag")
                    if (rConn != null && okRange && okEtag) {
                        append = true // partial content — append the rest
                        dl.conn = rConn
                        dl.watch = retry.watch
                        dl.finalUrl = retry.finalUrl
                        dl.code = retry.code
                        reconnected = true
                    } else {
                        // The server moved on (a different file under the same
                        // URL, or a lying range): appending would corrupt the
                        // file, so drop the partial and restart from scratch.
                        // The loop below reconnects without Range.
                        closeQuiet(retry)
                        try {
                            tmp.delete()
                        } catch (e: Exception) {
                        }
                        done = 0
                        append = false
                    }
                } else if (retry.conn != null && retry.code >= 200 && retry.code < 300) {
                    append = false // server ignored Range — restart from scratch
                    done = 0
                    dl.conn = retry.conn
                    dl.watch = retry.watch
                    dl.finalUrl = retry.finalUrl
                    dl.code = retry.code
                    reconnected = true
                } else if (retry.code == 416) {
                    closeQuiet(retry) // range not satisfiable — we already have it all
                    return done
                } else {
                    closeQuiet(retry)
                    if (attempts >= 6) {
                        throw IOException("resume failed: HTTP " + retry.code)
                    }
                    // transient (or redirects ran out) — loop and try connecting again
                }
            }
        }
    }

    private fun reportProgress(done: Long, total: Long, observer: Observer? = null) {
        val progress = if (total > 0) {
            Util.humanSize(done) + " / " + Util.humanSize(total)
        } else {
            Util.humanSize(done)
        }
        // Two destinations, deliberately. The pill is transient chrome at the top of
        // the screen; the trail row is the durable record of the step, and it was
        // the one showing nothing for the whole of a multi-minute download.
        observer?.onProgress(Fa.TRAIL_PROG_DOWNLOAD.format(progress))
        AgentBus.listener?.onToolRunning(ToolNames.DOWNLOAD, progress)
    }

    /**
     * Clears a JS/anti-bot wall on the file's host in a real WebView so the
     * clearance cookie is harvested for replay on the subsequent download.
     * Returns the WebView result so the caller can abort on an interactive
     * CAPTCHA or a hard block (#11) instead of downloading a challenge page.
     */
    private fun humanWarmup(fileUrl: String, token: CancellationToken, deadlineAt: Long): HumanFetch.Result? {
        return try {
            val remaining = deadlineAt - System.currentTimeMillis()
            if (remaining <= 0) {
                return null
            }
            val origin = try {
                val u = URL(fileUrl)
                u.protocol + "://" + u.host + "/"
            } catch (e: Exception) {
                fileUrl
            }
            // Never more than 20 s for the warmup — and never past the
            // download's own deadline.
            HumanFetch.fetch(origin, Math.min(20000L, remaining), token)
        } catch (ignored: Exception) {
            null
        }
    }

    /** SHA-256 of a file, lowercase hex — for download integrity checks. */
    internal fun sha256Of(file: File): String = sha256Hex(file)

    /**
     * Moves a finished temp file into the public Downloads folder.
     * Direct file I/O when we have all-files access (or pre-Q); MediaStore
     * otherwise, so the tool works even without the storage permission.
     */
    @Throws(Exception::class)
    private fun saveIntoDownloads(
        tmp: File,
        name: String,
        mime: String?,
        token: CancellationToken
    ): String {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = File(externalRoot(ctx), "Downloads")
            if (!dir.exists() && !dir.mkdirs()) {
                throw Exception("cannot create app download directory")
            }
            val out = dedupe(dir, name)
            if (!tmp.renameTo(out)) {
                copyFile(tmp, out, token)
            }
            return out.absolutePath
        }

        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        if (!mime.isNullOrEmpty()) {
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val item = ctx.contentResolver.insert(collection, values)
            ?: throw Exception("MediaStore insert failed")

        var committed = false
        try {
            FileInputStream(tmp).use { input ->
                ctx.contentResolver.openOutputStream(item).use { output ->
                    if (output == null) {
                        throw Exception("MediaStore output stream unavailable")
                    }
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) {
                            break
                        }
                        token.throwIfCancelled()
                        output.write(buffer, 0, n)
                    }
                }
            }
            token.throwIfCancelled()
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(item, values, null, null)
            committed = true
            // MediaStore renames on collision (song.mp3 -> "song (1).mp3"), so
            // the requested name is not necessarily the stored one. Returning
            // the requested name handed the model a path that does not exist,
            // and its follow-up file_info / read_file then failed.
            var stored = name
            try {
                val cursor = ctx.contentResolver.query(
                    item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
                )
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            val actual = cursor.getString(0)
                            if (!actual.isNullOrBlankJava()) {
                                stored = actual
                            }
                        }
                    } finally {
                        cursor.close()
                    }
                }
            } catch (ignored: Exception) {
            }
            @Suppress("DEPRECATION")
            return Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ).absolutePath + "/" + stored
        } finally {
            if (!committed) {
                try {
                    ctx.contentResolver.delete(item, null, null)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    // ---- file tools --------------------------------------------------------

    private fun listDir(args: JSONObject): String {
        val rawPath = pathArg(args)
        // No path at all means the workspace root — the one sensible default.
        val conflict = if (rawPath.isEmpty()) {
            null
        } else {
            pathConflictError(args, rawPath, "list_dir")
        }
        if (conflict != null) {
            return conflict
        }
        val dir = resolve(pathArg(args))
        if (!dir.exists()) {
            return "ERROR: not found: " + rel(dir)
        }
        if (!dir.isDirectory) {
            return "ERROR: not a directory: " + rel(dir)
        }
        val children = dir.listFiles()
            ?: return "ERROR: cannot read (permission?): " + rel(dir)

        children.sortWith(DIRS_FIRST)
        val sb = StringBuilder()
        sb.append("Directory: ").append(rel(dir)).append("  (")
            .append(children.size).append(" entries)\n")
        // A downloads folder can hold tens of thousands of entries; emitting
        // every one built a multi-megabyte reply the model then paid for in
        // tokens. The count above stays exact, the listing is a bounded sample.
        var shown = 0
        for (file in children) {
            if (shown >= MAX_DIR_ENTRIES) {
                break
            }
            if (file.isDirectory) {
                sb.append("  ▸ ").append(file.name).append("/\n")
            } else {
                sb.append("  · ").append(file.name).append("  (")
                    .append(Util.humanSize(file.length())).append(")\n")
            }
            shown++
        }
        if (children.size > shown) {
            sb.append("  …and ").append(children.size - shown)
                .append(" more entries (not shown)\n")
        }
        return sb.toString()
    }

    private fun fileInfo(args: JSONObject): String {
        val target = resolve(pathArg(args))
        if (!target.exists()) {
            return "ERROR: not found: " + rel(target)
        }
        val sb = StringBuilder()
        sb.append("Path: ").append(rel(target)).append('\n')
        sb.append("Type: ").append(if (target.isDirectory) "directory" else "file").append('\n')
        sb.append("Size: ").append(Util.humanSize(target.length())).append('\n')
        sb.append("Readable: ").append(target.canRead())
            .append(", Writable: ").append(target.canWrite()).append('\n')
        sb.append("MIME: ").append(Util.mimeOf(target.name))
        return sb.toString()
    }

    @Throws(Exception::class)
    private fun readFile(args: JSONObject): String {
        val rt = resolveTargetArg(args, "read_file")
        if (rt.error != null) {
            return rt.error
        }
        val target = rt.file
        if (target == null) {
            // Unreachable by construction: resolveTargetArg sets exactly one
            // of file/error. Spelled out because `!!` is banned by the source
            // contracts (test_no_force_unwrap).
            return "ERROR: the path could not be resolved. Nothing was changed."
        }
        if (!target.exists()) {
            return notFound(target)
        }
        if (target.isDirectory) {
            return "ERROR: is a directory (use list_dir): " + rel(target)
        }
        val mime = Util.mimeOf(target.name)
        if (!Util.isTextMime(mime) && !isProbablyText(target)) {
            return "Binary file (" + mime + ", " + Util.humanSize(target.length()) +
                "). Cannot display as text: " + rel(target)
        }
        // Read a WINDOW, always, and always with line numbers.
        //
        // The old default — "no range given, hand back the first 120 000 chars,
        // unnumbered" — is what taught the model to treat a file as one
        // indivisible blob. Without line numbers it cannot cite a region, and
        // with a whole file in context it reaches for write_file. A bounded,
        // numbered window with an explicit "call again from line N" footer is
        // what makes chunk-by-chunk reading the path of least resistance, which
        // is the only thing that reliably produces surgical edits.
        val maxBytes = Math.max(1, args.optInt("max_bytes", 20000))
        val from = Math.max(0, args.optInt("start_line", 0) - 1)
        val endLine = args.optInt("end_line", 0)

        // Streamed, bounded, and it STOPS when the window is full.
        //
        // The old code read the ENTIRE file into a String and split it just to
        // hand back a slice, so a ranged read of a 15 MB log allocated ~45 MB —
        // and anything past Util's 16 MB ceiling failed outright with "input
        // exceeds safe memory limit" instead of returning its first hundred
        // lines. Three rules keep this honest:
        //   * readLine() is not used: on a minified one-line file it would
        //     return the whole 20 MB as a single String, defeating the cap.
        //     [readWindowLine] stops at LINE_CHAR_CAP characters instead.
        //   * '\r' is kept. Splitting on '\n' the way the original did leaves
        //     CRLF endings intact, and edit_file matches old_string byte for
        //     byte — strip them here and no multi-line edit to a Windows file
        //     could ever match.
        //   * reading ends as soon as the window is complete, so a 500 MB log
        //     costs the window, not the log.
        if (endLine > 0 && endLine < from + 1) {
            return "ERROR: end_line (" + endLine + ") is before start_line (" + (from + 1) + ")"
        }

        val window = BufferedReader(
            InputStreamReader(FileInputStream(target), Charsets.UTF_8)
        ).use { r ->
            readWindow(r, from, endLine, maxBytes, activeToken.get())
        }
        noteRead(target)

        if (window.emitted == 0) {
            return "File: " + rel(target) + " (" + Util.humanSize(target.length()) +
                ") — no lines at or after line " + (from + 1) + "."
        }
        val header = "File: " + rel(target) + " (lines " + (from + 1) + "-" + window.lastLine +
            ", " + Util.humanSize(target.length()) + ")\n" +
            "Each line below is prefixed with its line number and a TAB. That prefix is " +
            "NOT part of the file — strip it before using the text as old_string.\n"
        if (window.atEof) {
            return header + window.text + "[END OF FILE]"
        }
        return header + window.text +
            "…[more lines follow] — continue with read_file {\"path\": \"" + rel(target) +
            "\", \"start_line\": " + (window.lastLine + 1) + "}"
    }

    /** Heuristic text sniff for files whose extension has no known mime. */
    private fun isProbablyText(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                // Long.compare, not toInt(): a >2 GB file overflowed toInt to a
                // negative and crashed in ByteArray() instead of being sniffed.
                val buffer = ByteArray(Math.min(512L, file.length()).toInt())
                val read = input.read(buffer)
                if (read <= 0) {
                    return true
                }
                var control = 0
                for (i in 0 until read) {
                    val value = buffer[i].toInt() and 255
                    if (value == 0) {
                        return false
                    }
                    if (value < 9 || (value > 13 && value < 32)) {
                        control++
                    }
                }
                control < read * 0.1
            }
        } catch (e: Exception) {
            false
        }
    }

    @Throws(Exception::class)
    /**
     * Implements the install_skill tool: the agent's path for "find skills for
     * <topic> on the web and install them".
     *
     * The expected flow is web_search for skill sources (GitHub repos carrying
     * a SKILL.md), web_fetch the SKILL.md content, then one call per skill with
     * the faithful name/description/instructions — never invented. Validation,
     * kebab-case enforcement and the atomic staging install all live in
     * [Skills.install]; this wrapper only maps the args and formats the
     * outcome. Like every mutating tool it runs through the normal approval
     * path, so in ACCEPT mode the user confirms each install.
     */
    private fun installSkill(args: JSONObject): String {
        val name = args.optStr("name", "").trimJava()
        val description = args.optStr("description", "").trimJava()
        // Skills are prompt text that lives on the device; an API key pasted
        // into one would sit in the skills folder forever. Same redaction the
        // file tools apply.
        val instructions = redactSecrets(args.optStr("instructions", ""))?.trimJava() ?: ""
        if (name.isEmpty() || instructions.isEmpty()) {
            return "ERROR: install_skill needs \"name\" and \"instructions\" — " +
                "the call left one of them blank. Nothing was installed."
        }
        val sourceUrl = args.optStr("source_url", "").trimJava()
        val allowedTools = try {
            val arr = args.optJSONArray("allowed_tools")
            if (arr != null) {
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i, "").trimJava()
                    if (t.isNotEmpty()) {
                        list.add(t)
                    }
                }
                list.distinct()
            } else {
                Skills.parseAllowedTools(args.optStr("allowed_tools", ""))
            }
        } catch (ignored: Exception) {
            emptyList<String>()
        }
        return try {
            val info = Skills.install(
                ctx, prefs, name, description, instructions, sourceUrl, allowedTools
            )
            val sb = StringBuilder()
            sb.append("Installed skill \"").append(info.name).append('"')
            if (info.renamedFrom.isNotEmpty()) {
                sb.append(" (renamed from \"").append(info.renamedFrom).append("\")")
            }
            sb.append(
                " — it is enabled and will be offered to me whenever its " +
                    "description matches a future task."
            )
            sb.toString()
        } catch (e: Exception) {
            "ERROR: could not install the skill: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeFile(args: JSONObject, observer: Observer? = null): String {
        val rt = resolveTargetArg(args, "write_file")
        if (rt.error != null) {
            return rt.error
        }
        val target = rt.file
        if (target == null) {
            // Unreachable by construction: resolveTargetArg sets exactly one
            // of file/error. Spelled out because `!!` is banned by the source
            // contracts (test_no_force_unwrap).
            return "ERROR: the path could not be resolved. Nothing was changed."
        }
        val overwrite = args.optBoolean("overwrite", false)
        // write_file is for NEW files. Rewriting an existing one wholesale is
        // the single worst habit a file agent can have: it silently drops
        // anything the model did not happen to reproduce, it costs a full file
        // of output tokens, and it is the most common way a long file ends up
        // truncated. The system prompt has always said so; nothing enforced it,
        // so it happened anyway. Now it is a hard error with the right tool
        // named in the message — and an explicit escape hatch for the rare
        // legitimate case.
        if (target.isFile && !overwrite) {
            return "ERROR: that file already exists — write_file only CREATES files.\n" +
                "File: " + rel(target) + "\n" +
                "To change it, read the region with read_file and patch it with edit_file " +
                "(old_string → new_string), one function or block at a time — do not " +
                "regenerate the whole file. If you truly must replace the entire file, " +
                "repeat the call with \"overwrite\": true."
        }
        if (target.isDirectory) {
            return "ERROR: that path is a folder, not a file.\nPath: " + rel(target) +
                "\nTo create a file inside it, send the full file path (folder + file name)."
        }
        val raw = args.optStr("content", "")
        val safe = redactSecrets(raw) ?: ""
        val redacted = raw != safe
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory) {
            try {
                if (!parent.mkdirs() && !parent.isDirectory) {
                    return storageError("could not create the parent folder", parent)
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                return storageError("could not create the parent folder", parent, e)
            }
        }
        // Last-moment cancel check so a stopped run never lands a write after the
        // wrapper already reported CANCELLED.
        activeToken.get().throwIfCancelled()
        // Re-check the create-only guard here: a file created between the check
        // above and this write must not slip through.
        if (target.isFile && !overwrite) {
            return "ERROR: that file already exists — write_file only CREATES files.\n" +
                "File: " + rel(target) + "\n" +
                "It appeared while this call was being prepared. Read it with " +
                "read_file and patch it with edit_file, or repeat with " +
                "\"overwrite\": true to replace it."
        }
        val bytes = safe.toByteArray(Charsets.UTF_8)
        // write_file reported nothing at all, start to finish. Writing a large
        // generated file is one of the longest single things the agent does, and it
        // is also the one where the user most wants to see WHAT was written — so it
        // reports both its progress and its content as a diff against what was
        // there (nothing, for a create).
        observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_WRITING)
        val previous = if (target.isFile) {
            try {
                String(Util.readAll(target), Charsets.UTF_8)
            } catch (ignored: Exception) {
                ""
            }
        } else {
            ""
        }
        observer?.onFileChange(rel(target), previous, safe)
        val nonAtomic = try {
            writeBytesAtomic(target, bytes)
        } catch (e: IOException) {
            return storageError("could not write", target, e)
        }
        // The model authored this content, so it knows it: count that as a read
        // and let it patch the file straight away without a round trip.
        noteRead(target)
        return "OK: wrote " + bytes.size + " bytes to " + rel(target) +
            (if (nonAtomic) " (warning: the filesystem refused an atomic rename, so this write was not atomic)" else "") +
            (if (redacted) Fa.SECURITY_REDACTED else "")
    }

    /**
     * Surgical file editor (Claude-Code style). Two shapes:
     *
     *   single: { path, old_string, new_string, replace_all? }
     *   multi : { path, edits: [ {old_string,new_string,replace_all?}, ... ] }
     *
     * The multi form applies every edit in order and ATOMICALLY — if any single
     * edit fails to match, nothing is written, so the file is never left half
     * changed. When an exact match isn't found we try one whitespace-tolerant
     * pass (indentation width / tabs-vs-spaces / trailing spaces / CRLF) so a
     * near-miss doesn't force the model to rewrite the whole file.
     */
    @Throws(Exception::class)
    private fun editFile(args: JSONObject, observer: Observer? = null): String {
        val rt = resolveTargetArg(args, "edit_file")
        if (rt.error != null) {
            return rt.error
        }
        val target = rt.file
        if (target == null) {
            // Unreachable by construction: resolveTargetArg sets exactly one
            // of file/error. Spelled out because `!!` is banned by the source
            // contracts (test_no_force_unwrap).
            return "ERROR: the path could not be resolved. Nothing was changed."
        }
        if (!target.exists()) {
            return notFound(target)
        }
        // A directory used to fall through to the read/modify/write path and
        // fail deep inside with a confusing message.
        if (target.isDirectory) {
            return "ERROR: that path is a folder, not a file — edit_file patches files.\n" +
                "Path: " + rel(target) + "\n" +
                "Use list_dir to see what's inside it, then edit one of its files."
        }
        // Read before you edit. old_string has to match byte-for-byte, so a
        // model editing blind mostly fails anyway — but it fails *after*
        // guessing, which burns a turn and tempts it into a full rewrite. Ask
        // for the read up front instead.
        if (!hasBeenRead(target)) {
            return "ERROR: read_file comes first — this file has not been read in " +
                "this session.\n" +
                "File: " + rel(target) + "\n" +
                "Call read_file on the region you intend to change, copy old_string " +
                "straight out of that output byte for byte (WITHOUT the \"12<TAB>\" " +
                "line-number prefixes), then send the edit. Nothing was changed."
        }
        // Two parallel edit_file calls used to read-modify-write over each
        // other and silently lose one edit; edits to one file are serialized.
        synchronized(editLockFor(target)) {
            return editFileLocked(args, observer, target)
        }
    }

    private fun editFileLocked(args: JSONObject, observer: Observer?, target: File): String {
        // Validate the line-range form BEFORE the big read, so a file too
        // large to load gets the same argument errors as a small one.
        val startLine = args.optInt("start_line", 0)
        var endLine = startLine
        var replacement = ""
        var replacementRedacted = false
        if (startLine > 0) {
            endLine = args.optInt("end_line", startLine)
            if (endLine < startLine) {
                return "ERROR: end_line (" + endLine + ") is before start_line (" +
                    startLine + ") — nothing was changed.\n" +
                    "Both are 1-based and inclusive, so end_line must be at least " +
                    "start_line. To replace a single line, send the same number twice."
            }
            if (!args.has("new_text") && !args.has("new_string")) {
                return "ERROR: a line-range edit needs new_text — nothing was changed.\n" +
                    "Send { path, start_line, end_line, new_text }. Pass \"\" as " +
                    "new_text to delete those lines outright."
            }
            val rawNew = args.optStr("new_text", args.optStr("new_string", ""))
            replacement = redactSecrets(rawNew) ?: ""
            replacementRedacted = rawNew != replacement
        }
        // Files above the read cap cannot be loaded whole. The line-range form
        // streams instead; the needle forms refuse with a pointer at it rather
        // than dying inside the read with "input exceeds safe memory limit".
        // (16 MB matches Util's MAX_READ_BYTES; it is private there.)
        val huge = target.length() > 16L * 1024L * 1024L
        if (huge && startLine <= 0) {
            return "ERROR: this file is " + Util.humanSize(target.length()) +
                " — too large to edit by matching text.\n" +
                "File: " + rel(target) + "\n" +
                "Use the line-range form instead: { path, start_line, end_line, new_text } " +
                "(read the region with read_file first to get line numbers). " +
                "Nothing was changed."
        }
        if (huge) {
            return editFileLineRangeStreaming(
                target, startLine, endLine, replacement, replacementRedacted, observer
            )
        }
        // A long edit used to report NOTHING until it finished, which on a big file
        // or a long list of edits is indistinguishable from the app hanging. Each
        // stage now says what it is doing — and the FIRST stage is the read, which
        // on a multi-megabyte file is itself the slow part and used to happen before
        // anything had been reported at all.
        observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_READING)
        val content = String(Util.readAll(target), Charsets.UTF_8)
        observer?.onProgress(rel(target))

        // ---- line-range form: edit_file { path, start_line, end_line, new_text }
        //
        // The deterministic escape hatch from old_string matching.
        //
        // Matching a copied snippet byte-for-byte is fragile in exactly the cases
        // that matter most: minified files (one enormous line), files with mixed
        // indentation, and any text the model retyped slightly differently. Since
        // read_file already hands back numbered lines, addressing the edit BY
        // THOSE NUMBERS removes the guesswork completely — there is nothing to
        // mis-copy. Line numbers are 1-based and inclusive, matching read_file.
        if (startLine > 0) {
            val lines = content.split("\n").toMutableList()
            if (startLine > lines.size) {
                return "ERROR: start_line " + startLine + " is past the end of the file, " +
                    "which has " + lines.size + " lines — nothing was changed.\n" +
                    "File: " + rel(target) + "\n" +
                    "Re-read the file with read_file to get current line numbers; an " +
                    "earlier edit in this session may have shortened it. To append, " +
                    "use start_line " + lines.size + " and repeat that line at the " +
                    "start of new_text."
            }
            val last = Math.min(endLine, lines.size)
            val removed = lines.subList(startLine - 1, last).toList()
            val newLines = if (replacement.isEmpty()) {
                emptyList()
            } else {
                replacement.split("\n")
            }
            val rebuilt = ArrayList<String>(lines.size)
            rebuilt.addAll(lines.subList(0, startLine - 1))
            rebuilt.addAll(newLines)
            rebuilt.addAll(lines.subList(last, lines.size))
            activeToken.get().throwIfCancelled()
            val rebuiltText = rebuilt.joinToString("\n")
            // The line-range form reported nothing between the read and the write.
            // On a large file that is a silent gap of seconds with the strip frozen
            // on a bare path — indistinguishable from a hang, which is the exact
            // complaint this whole mechanism exists to answer.
            observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_WRITING)
            observer?.onFileChange(rel(target), content, rebuiltText)
            val nonAtomic = writeUtf8(target, rebuiltText)
            noteRead(target)
            return "OK: replaced lines " + startLine + "-" + last + " of " + rel(target) +
                " (" + removed.size + " removed, " + newLines.size + " written)" +
                (if (nonAtomic) " (warning: the write was not atomic)" else "") +
                (if (replacementRedacted) Fa.SECURITY_REDACTED else "")
        }

        val edits = args.optJSONArray("edits")
        if (edits != null && edits.length() > 0) {
            // NO hard ceiling, and NOT all-or-nothing.
            //
            // Rejecting a big batch outright just blocked the work, and the old
            // atomic behaviour was worse: one bad needle in a batch of five
            // discarded the four edits that matched perfectly, so the model
            // redid them and usually failed the same way again. Now every edit
            // that matches is applied and kept, and only the failures come back
            // — with enough detail to fix exactly those. Forward progress is
            // always preserved; the prompt is what keeps batches small.
            var working = content
            var totalReplacements = 0
            var fuzzy = 0
            var anyRedacted = false
            var applied = 0
            val failures = StringBuilder()
            for (i in 0 until edits.length()) {
                val edit = edits.optJSONObject(i)
                if (edit == null) {
                    failures.append("\n  • edit #").append(i + 1).append(": not an object")
                    continue
                }
                val oldS: String? = edit.optStrOrNull("old_string")
                val rawNew = edit.optStr("new_string", "")
                val newS = redactSecrets(rawNew) ?: ""
                if (rawNew != newS) {
                    anyRedacted = true
                }
                val all = edit.optBoolean("replace_all", false)
                if (oldS.isNullOrEmpty()) {
                    failures.append("\n  • edit #").append(i + 1)
                        .append(": old_string is missing or empty, so there is ")
                        .append("nothing to find.")
                    continue
                }
                observer?.onProgress(
                    rel(target) + "  ·  " + (i + 1) + "/" + edits.length()
                )
                val result = applyOne(working, oldS, newS, all)
                val failed = result.error
                if (failed != null) {
                    failures.append("\n  • edit #").append(i + 1).append(": ").append(failed)
                    // The detail is indented under its bullet so a batch of three
                    // failures does not read as one run-on paragraph.
                    if (result.detail.isNotEmpty()) {
                        for (line in result.detail.split("\n")) {
                            failures.append("\n    ").append(line)
                        }
                    }
                    continue
                }
                working = result.text ?: working
                totalReplacements += result.count
                applied++
                if (result.fuzzy) {
                    fuzzy++
                }
            }
            activeToken.get().throwIfCancelled()
            var nonAtomic = false
            if (applied > 0) {
                observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_WRITING)
                observer?.onFileChange(rel(target), content, working)
                nonAtomic = writeUtf8(target, working)
            }
            val summary = StringBuilder()
            if (applied > 0) {
                summary.append("OK: edited ").append(rel(target)).append(" (")
                    .append(applied).append(" of ").append(edits.length())
                    .append(" edits applied, ").append(totalReplacements)
                    .append(" replacements")
                if (fuzzy > 0) {
                    summary.append(", ").append(fuzzy).append(" whitespace-tolerant")
                }
                summary.append(")")
                if (nonAtomic) {
                    summary.append(" (warning: the write was not atomic)")
                }
            } else {
                summary.append("ERROR: none of the ").append(edits.length())
                    .append(" edits matched — the file is unchanged.")
                    .append("\nFile: ").append(rel(target))
            }
            if (failures.isNotEmpty()) {
                summary.append(
                    if (applied > 0) {
                        "\nThe edits above are SAVED — do not send them again. Still failing:"
                    } else {
                        "\nFailures:"
                    }
                )
                summary.append(failures)
                summary.append(
                    "\nRe-read the affected region with read_file and retry ONLY the failed edits."
                )
            }
            if (anyRedacted) {
                summary.append(Fa.SECURITY_REDACTED)
            }
            return summary.toString()
        }

        val oldS: String? = args.optStrOrNull("old_string")
        val rawNew = args.optStr("new_string", "")
        val newS = redactSecrets(rawNew) ?: ""
        val redacted = rawNew != newS
        val all = args.optBoolean("replace_all", false)
        if (oldS.isNullOrEmpty()) {
            return "ERROR: edit_file needs a non-empty old_string.\n" +
                "Send { path, old_string, new_string }. If you cannot reproduce the " +
                "text exactly — minified or generated files, very long lines — use " +
                "the line-range form instead: " +
                "{ path, start_line, end_line, new_text }."
        }
        val result = applyOne(content, oldS, newS, all)
        val failed = result.error
        if (failed != null) {
            // Headline, then the path, then the detail — in that order and on
            // separate lines. The collapsed activity row shows line one only, and
            // the absolute path of a file in /storage/emulated/0/Download is
            // longer than the whole row: putting it first is what produced four
            // identical red rows that said nothing but the filename.
            return "ERROR: " + failed + "\nFile: " + rel(target) +
                (if (result.detail.isEmpty()) "" else "\n" + result.detail)
        }
        activeToken.get().throwIfCancelled()
        val edited = result.text ?: content
        observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_WRITING)
        observer?.onFileChange(rel(target), content, edited)
        val nonAtomic = writeUtf8(target, edited)
        return "OK: edited " + rel(target) + " (" +
            (if (all) result.count.toString() + " replacements" else "1 replacement") +
            (if (result.fuzzy) ", whitespace-tolerant match" else "") + ")" +
            (if (nonAtomic) " (warning: the write was not atomic)" else "") +
            (if (redacted) Fa.SECURITY_REDACTED else "")
    }

    /** "\n" as bytes, shared by the streaming editor. */
    private val NEWLINE_BYTES = "\n".toByteArray(Charsets.UTF_8)

    /**
     * Line-range edit for files above the read cap: streams the file
     * line-by-line through a temp file instead of loading it whole, so a
     * 50 MB log can still be patched without ever holding it in memory.
     * Newline handling matches the in-memory path (lines rejoined with "\n").
     */
    private fun editFileLineRangeStreaming(
        target: File,
        startLine: Int,
        endLine: Int,
        replacement: String,
        redacted: Boolean,
        observer: Observer?
    ): String {        observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_READING)
        val temp = File(
            target.parentFile,
            "." + target.name + ".vega-edit-" + Thread.currentThread().id +
                "-" + System.nanoTime()
        )
        var total = 0
        var inserted = false
        val newLines = if (replacement.isEmpty()) {
            emptyList()
        } else {
            replacement.split("\n")
        }
        try {
            BufferedReader(InputStreamReader(FileInputStream(target), Charsets.UTF_8), 65536)
                .use { reader ->
                    FileOutputStream(temp).use { out ->
                        var writtenAny = false
                        var lineNo = 0
                        while (true) {
                            activeToken.get().throwIfCancelled()
                            val line = reader.readLine() ?: break
                            lineNo++
                            if (lineNo == startLine) {
                                for (newLine in newLines) {
                                    if (writtenAny) {
                                        out.write(NEWLINE_BYTES)
                                    }
                                    out.write(newLine.toByteArray(Charsets.UTF_8))
                                    writtenAny = true
                                }
                                inserted = true
                            } else if (lineNo < startLine || lineNo > endLine) {
                                if (writtenAny) {
                                    out.write(NEWLINE_BYTES)
                                }
                                out.write(line.toByteArray(Charsets.UTF_8))
                                writtenAny = true
                            }
                            total = lineNo
                            if (lineNo % 20000 == 0) {
                                observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_READING)
                            }
                        }
                        out.flush()
                    }
                }
        } catch (e: Exception) {
            try {
                temp.delete()
            } catch (ignored: Exception) {
            }
            throw e
        }
        if (!inserted) {
            try {
                temp.delete()
            } catch (ignored: Exception) {
            }
            return "ERROR: start_line " + startLine + " is past the end of the file, " +
                "which has " + total + " lines — nothing was changed.\n" +
                "File: " + rel(target) + "\n" +
                "Re-read the file with read_file to get current line numbers; an " +
                "earlier edit in this session may have shortened it. To append, " +
                "use start_line " + total + " and repeat that line at the " +
                "start of new_text."
        }
        val last = Math.min(endLine, total)
        observer?.onProgress(rel(target) + "  ·  " + Fa.TRAIL_PROG_WRITING)
        // The full before/after cannot go through onFileChange — the file is
        // tens of megabytes. A descriptor keeps the trail honest instead.
        observer?.onFileChange(
            rel(target),
            "(file too large to preview: " + total + " lines)",
            "(lines " + startLine + "-" + last + " replaced)"
        )
        try {
            if (!temp.renameTo(target)) {
                throw IOException("could not replace " + rel(target) + " with the edited copy")
            }
        } catch (e: Exception) {
            try {
                temp.delete()
            } catch (ignored: Exception) {
            }
            throw e
        }
        noteRead(target)
        return "OK: replaced lines " + startLine + "-" + last + " of " + rel(target) +
            " (" + (last - startLine + 1) + " removed, " + newLines.size + " written, " +
            total + " lines total — streamed, file too large to hold in memory)" +
            (if (redacted) Fa.SECURITY_REDACTED else "")
    }

    /**
     * The outcome of one needle-and-replacement pass.
     *
     * [error] and [detail] are deliberately separate. A tool result's FIRST LINE
     * is all the collapsed activity row shows (see `AgentEngine.closeTrailStep`,
     * which clips `firstMeaningfulLine` to 120 characters), so the headline has
     * to be a complete sentence on its own — while the part that actually lets a
     * model self-correct, the surrounding file text and the ordered list of
     * likely causes, needs hundreds of characters. Concatenating them into one
     * blob gave the user a red row reading "old_string not found. The file
     * actually contains, around that poi…" and the model no priority order at
     * all.
     */
    private class EditResult {
        var text: String? = null
        var count = 0
        var fuzzy = false

        /** One self-contained sentence. Never a path, never wrapped. */
        var error: String? = null

        /** Everything after the headline: causes, context, what to do next. */
        var detail: String = ""
    }

    private fun deletePath(rawArgs: JSONObject): String {
        val args = unwrappedArgs(rawArgs)
        // Single-path form naming two genuinely different paths
        // ({"path": "a", "file": "b"}): deleting one and silently dropping the
        // other is data confusion — refuse with both named.
        if (!args.has("paths") && !args.has("files")) {
            val values = pathArgValues(args)
            if (values.size > 1) {
                return "ERROR: conflicting paths in delete_path — one call must " +
                    "name exactly one path, but several path arguments were given " +
                    "(\"" + Util.truncate(values[0], 80) + "\" and \"" +
                    Util.truncate(values[1], 80) + "\").\n" +
                    "Send the call again with a single \"path\", or a \"paths\" " +
                    "array to delete several. Nothing was deleted."
            }
        }
        val paths = pathListArg(args)
        if (paths.isNotEmpty()) {
            // One path: the direct verdict, not a summary wrapper — the
            // model (and the regression tests) read "OK:"/"ERROR:" prefixes.
            if (paths.size == 1) {
                return deleteSinglePath(paths[0])
            }
            var deleted = 0
            var rejected = 0
            var failed = 0
            val details = StringBuilder()
            for (i in paths.indices) {
                if (activeToken.get().isCancelled) {
                    details.append("CANCELLED: ").append(paths[i]).append('\n')
                    break
                }
                // The multi form skips the single-path conflict check: naming
                // several paths IS the point of the call. Each entry is still
                // resolved (and contained) individually by deleteSinglePath.
                val result = deleteSinglePath(paths[i])
                when {
                    result.startsWith("OK:") -> deleted++
                    result.startsWith("ERROR: refusing") -> rejected++
                    else -> failed++
                }
                details.append(result).append('\n')
            }
            return "DELETE SUMMARY: requested=" + paths.size + ", deleted=" + deleted +
                ", rejected=" + rejected + ", failed=" + failed + "\n" + details.toString().trimJava()
        }
        // No usable path under any alias. Echo the received keys so the model
        // can self-correct in one retry instead of guessing blind.
        return "ERROR: delete_path requires \"path\" or \"paths\" — the call named " +
            "no file or folder at all (received keys: " + receivedKeys(rawArgs) + ").\n" +
            "Send { \"path\": \"<file or folder>\" } or " +
            "{ \"paths\": [\"<file1>\", \"<file2>\"] }. Nothing was deleted."
    }

    private fun deleteSinglePath(path: String): String {
        val target = resolve(path)
        if (!target.exists()) {
            return "ERROR: not found: " + rel(target)
        }
        // The workspace root resolves fine — deleting it would wipe everything
        // the agent can touch, which no single delete call ever means.
        val rootPath = try {
            externalRoot(ctx).canonicalPath
        } catch (e: Exception) {
            ""
        }
        val targetPath = try {
            target.canonicalPath
        } catch (e: Exception) {
            ""
        }
        if (targetPath.isNotEmpty() && targetPath == rootPath) {
            return "ERROR: refusing to delete the workspace root itself (" + rel(target) +
                ").\nDelete the files or folders inside it instead — one by one " +
                "or in a \"paths\" array. Nothing was deleted."
        }
        val ok = deleteRecursive(target)
        return (if (ok) "OK: deleted " else "ERROR: failed to delete ") + rel(target)
    }

    /**
     * Post-order delete that cannot overflow the stack: the recursive version
     * died with a StackOverflowError on very deep trees (node_modules and the
     * like are thousands of levels in the wild), which the run then reported
     * as a generic tool crash. Iterative, cancellable, and honest — a child
     * that refuses to delete is reported instead of silently skipped.
     *
     * Symlinks are deleted as LINKS, never traversed: listFiles() follows a
     * symlink, so descending into one would delete the LINK TARGET's contents
     * — which can live outside the workspace entirely.
     */
    private fun deleteRecursive(file: File): Boolean {
        val stack = ArrayDeque<File>()
        val postOrder = ArrayDeque<File>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            activeToken.get().throwIfCancelled()
            val current = stack.removeLast()
            postOrder.add(current)
            if (current.isDirectory && !isSymlink(current)) {
                val children = current.listFiles()
                if (children != null) {
                    for (child in children) {
                        stack.add(child)
                    }
                }
            }
        }
        var ok = true
        while (postOrder.isNotEmpty()) {
            activeToken.get().throwIfCancelled()
            val current = postOrder.removeLast()
            if (!current.delete()) {
                ok = false
            }
        }
        return ok
    }

    private fun makeDir(args: JSONObject): String {
        val rawPath = pathArg(args)
        if (rawPath.isEmpty()) {
            return "ERROR: make_dir needs a \"path\" — the call named no folder " +
                "at all (received keys: " + receivedKeys(args) + "). " +
                "Nothing was created."
        }
        val conflict = pathConflictError(args, rawPath, "make_dir")
        if (conflict != null) {
            return conflict
        }
        val target = resolve(rawPath)
        if (target.isDirectory) {
            return "OK: already exists " + rel(target)
        }
        // A regular file at this path is not a folder that "already exists" —
        // saying so taught the model the folder was ready, and the next write
        // into it failed mysteriously.
        if (target.isFile) {
            return "ERROR: a file already exists at that path — cannot create a " +
                "folder over it.\nPath: " + rel(target) + "\nNothing was created."
        }
        return try {
            if (target.mkdirs() || target.isDirectory) {
                "OK: created " + rel(target)
            } else {
                storageError("could not create folder", target)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            storageError("could not create folder", target, e)
        }
    }

    private fun movePath(args: JSONObject, observer: Observer? = null): String {
        val rawFrom = args.optStr("from", "").trimJava()
        val rawTo = args.optStr("to", "").trimJava()
        // Two absent paths used to resolve to the workspace root — and the call
        // then tried to move the root onto itself.
        if (rawFrom.isEmpty() || rawTo.isEmpty()) {
            return "ERROR: move_path needs both \"from\" and \"to\".\n" +
                "Send { \"from\": \"<existing path>\", \"to\": \"<new path>\" }. " +
                "Nothing was moved."
        }
        val from = resolve(rawFrom)
        val to = resolve(rawTo)
        if (!from.exists()) {
            return "ERROR: source not found: " + rel(from)
        }
        // rename() atomically REPLACES an existing destination on Linux, so an
        // unchecked move silently destroys whatever was there. Refuse unless
        // the caller says overwrite:true explicitly.
        val overwrite = args.optBoolean("overwrite", false)
        if (to.exists() && !overwrite) {
            return "ERROR: destination already exists — move_path will not silently " +
                "replace it.\nDestination: " + rel(to) + "\n" +
                "Repeat with \"overwrite\": true to replace it, or pick another " +
                "destination. Nothing was moved."
        }
        // Moving the workspace root itself is never what a move means.
        val rootPath = try {
            externalRoot(ctx).canonicalPath
        } catch (e: Exception) {
            ""
        }
        val fromPath = try {
            from.canonicalPath
        } catch (e: Exception) {
            ""
        }
        if (fromPath.isNotEmpty() && fromPath == rootPath) {
            return "ERROR: refusing to move the workspace root itself (" + rel(from) +
                ").\nMove the files or folders inside it instead. Nothing was moved."
        }
        val parent = to.parentFile
        if (parent != null && !parent.isDirectory) {
            try {
                if (!parent.mkdirs() && !parent.isDirectory) {
                    return storageError("could not create the destination folder", parent)
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                return storageError("could not create the destination folder", parent, e)
            }
        }
        if (activeToken.get().isCancelled) {
            return "CANCELLED: user stopped this tool"
        }
        if (from.renameTo(to)) {
            return "OK: moved to " + rel(to)
        }
        // rename() only works within one filesystem. A phone workspace
        // routinely spans several mounts (internal storage, an SD card, an OTG
        // volume), so a perfectly ordinary move used to fail with a bare "move
        // failed". Say which volumes are involved — and when the source is a
        // directory, do the copy-then-delete here instead of making the model
        // reimplement it one file at a time.
        if (from.isDirectory) {
            if (sameVolume(from, to)) {
                return "ERROR: move failed: " + rel(from) + " could not be moved to " +
                    rel(to) + " (same storage volume — the destination may be inside " +
                    "the source, or the name may be invalid). Nothing was moved."
            }
            return moveDirectoryTree(from, to, overwrite, observer)
        }
        if (to.isDirectory) {
            return "ERROR: destination is a directory: " + rel(to)
        }
        // Copy to a sibling temp file and rename into place, the same way
        // writeBytesAtomic does. Opening `to` directly would TRUNCATE it the
        // instant the stream opened, so a copy that then ran out of space — or
        // that the user simply stopped — would leave the destination destroyed
        // with nothing to restore it from.
        val temp = File(
            to.parentFile,
            "." + to.name + ".vega-move-" + Thread.currentThread().id +
                "-" + System.nanoTime()
        )
        return try {
            FileInputStream(from).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        activeToken.get().throwIfCancelled()
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (!temp.renameTo(to)) {
                temp.delete()
                "ERROR: move failed: could not place the copy at " + rel(to)
            } else if (!from.delete()) {
                "OK: copied to " + rel(to) + " (source could not be removed: " + rel(from) + ")"
            } else {
                "OK: moved to " + rel(to)
            }
        } catch (cancelled: CancellationToken.CancelledException) {
            try {
                temp.delete()
            } catch (ignored: Exception) {
            }
            // Let the tool wrapper report this as CANCELLED, not as a failure.
            throw cancelled
        } catch (e: Exception) {
            try {
                temp.delete()
            } catch (ignored: Exception) {
            }
            "ERROR: move failed: " + e.message
        }
    }

    /**
     * Moves a directory across storage volumes (or anywhere rename() refuses):
     * copies the whole tree, then deletes the source. Never follows symlinks —
     * they are skipped rather than duplicating outside content. Cancellable at
     * every file, with progress every 25 files so a big move does not look hung.
     */
    private fun moveDirectoryTree(
        from: File,
        to: File,
        overwrite: Boolean,
        observer: Observer?
    ): String {
        return try {
            if (!to.isDirectory) {
                if (!to.mkdirs() && !to.isDirectory) {
                    return storageError("could not create the destination folder", to)
                }
            }
            var files = 0
            val stack = ArrayDeque<Pair<File, String>>()
            stack.add(Pair(from, ""))
            val buffer = ByteArray(65536)
            while (stack.isNotEmpty()) {
                activeToken.get().throwIfCancelled()
                val (current, relPath) = stack.removeLast()
                if (isSymlink(current)) {
                    continue
                }
                val dest = if (relPath.isEmpty()) to else File(to, relPath)
                if (current.isDirectory) {
                    if (!dest.isDirectory && !dest.mkdirs()) {
                        return "ERROR: move failed: could not create " + rel(dest) +
                            ". Nothing was moved."
                    }
                    val children = current.listFiles()
                    if (children != null) {
                        for (child in children) {
                            val childRel = if (relPath.isEmpty()) {
                                child.name
                            } else {
                                relPath + "/" + child.name
                            }
                            stack.add(Pair(child, childRel))
                        }
                    }
                } else {
                    if (dest.exists() && !overwrite) {
                        return "ERROR: move failed: the destination already contains " +
                            rel(dest) + " — repeat with \"overwrite\": true to replace " +
                            "it. Nothing was moved."
                    }
                    FileInputStream(current).use { input ->
                        FileOutputStream(dest).use { output ->
                            while (true) {
                                activeToken.get().throwIfCancelled()
                                val read = input.read(buffer)
                                if (read <= 0) {
                                    break
                                }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                            try {
                                output.fd.sync()
                            } catch (ignored: Exception) {
                            }
                        }
                    }
                    files++
                    if (files % 25 == 0) {
                        observer?.onProgress(
                            rel(to) + "  ·  " + Fa.TRAIL_PROG_WRITING +
                                " (" + files + " files)"
                        )
                    }
                }
            }
            if (!deleteRecursive(from)) {
                return "OK: copied " + files + " files to " + rel(to) +
                    " but the source could not be fully removed: " + rel(from) +
                    "\nDelete it by hand with delete_path once you have checked the copy."
            }
            "OK: moved " + rel(from) + " to " + rel(to) + " (" + files + " files copied)"
        } catch (cancelled: CancellationToken.CancelledException) {
            // Let the tool wrapper report this as CANCELLED, not as a failure.
            throw cancelled
        } catch (e: Exception) {
            "ERROR: move failed: " + e.message + ". Nothing was moved."
        }
    }

    @Throws(Exception::class)
    private fun searchFiles(args: JSONObject, observer: Observer? = null): String {
        val root = resolve(pathArg(args))
        val query = args.optStr("query", "")
        val nameOnly = args.optBoolean("name_only", false)
        val maxResults = args.optInt("max_results", 100)
        if (query.isEmpty()) {
            return "ERROR: query is required"
        }
        val hits = ArrayList<String>()
        // A content search reads every text file under the root. On a real
        // Documents tree that is tens of seconds during which nothing was
        // reported — so the walk reports the folder it is in and the running
        // match count, throttled so a deep tree cannot flood the UI.
        val progress = if (observer == null) null else Progress(observer, root)
        searchWalk(root, query.lowercase(Locale.US), nameOnly, hits, maxResults, 0, progress)
        if (activeToken.get().isCancelled) {
            return "CANCELLED: user stopped file search"
        }
        if (hits.isEmpty()) {
            return "No matches for \"" + query + "\" under " + rel(root)
        }
        val sb = StringBuilder("Matches for \"" + query + "\" (" + hits.size + "):\n")
        for (hit in hits) {
            sb.append("  ").append(hit).append('\n')
        }
        return sb.toString()
    }

    private fun searchWalk(
        dir: File,
        needle: String,
        nameOnly: Boolean,
        hits: MutableList<String>,
        limit: Int,
        depth: Int,
        progress: Progress? = null
    ) {
        if (activeToken.get().isCancelled || hits.size >= limit || depth > 12) {
            return
        }
        progress?.at(dir, hits.size)
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (activeToken.get().isCancelled || hits.size >= limit) {
                return
            }
            val name = file.name
            if (name.startsWith(".")) {
                continue
            }
            if (name.lowercase(Locale.US).contains(needle)) {
                hits.add((if (file.isDirectory) "▸ " else "· ") + file.absolutePath)
            }
            if (file.isDirectory) {
                searchWalk(file, needle, nameOnly, hits, limit, depth + 1, progress)
            } else if (!nameOnly && file.length() < SEARCH_FILE_BYTES &&
                Util.isTextMime(Util.mimeOf(name))
            ) {
                try {
                    // Bounded: read at most what will be scanned, and sniff the
                    // head for binary content first — a binary file with a text
                    // extension used to be decoded whole (up to 2 MB) for
                    // nothing. Cancellation is honoured mid-read.
                    val bytes = FileInputStream(file).use { input ->
                        readBounded(input, SEARCH_FILE_BYTES, activeToken.get())
                    }
                    if (!looksBinary(bytes)) {
                        val lower = String(bytes, Charsets.UTF_8).lowercase(Locale.US)
                        val at = lower.indexOf(needle)
                        if (at >= 0) {
                            val lineStart = lower.lastIndexOf('\n', at) + 1
                            var lineEnd = lower.indexOf('\n', at)
                            if (lineEnd < 0) {
                                lineEnd = lower.length
                            }
                            hits.add(
                                "› " + file.absolutePath + " : " +
                                    lower.substring(
                                        lineStart, Math.min(lineEnd, lineStart + 160)
                                    ).trimJava()
                            )
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun glob(args: JSONObject, observer: Observer? = null): String {
        val root = resolve(pathArg(args))
        val pattern = args.optStr("pattern", "*")
        val hits = ArrayList<String>()
        val progress = if (observer == null) null else Progress(observer, root)
        globWalk(
            root, Regex(globToRegex(pattern), RegexOption.IGNORE_CASE), hits, 200, 0, progress
        )
        if (activeToken.get().isCancelled) {
            return "CANCELLED: user stopped glob search"
        }
        if (hits.isEmpty()) {
            return "No files matching " + pattern + " under " + rel(root)
        }
        val sb = StringBuilder("Glob " + pattern + " (" + hits.size + "):\n")
        for (hit in hits) {
            sb.append("  ").append(hit).append('\n')
        }
        return sb.toString()
    }

    private fun globWalk(
        dir: File,
        pattern: Regex,
        hits: MutableList<String>,
        limit: Int,
        depth: Int,
        progress: Progress? = null
    ) {
        if (activeToken.get().isCancelled || hits.size >= limit || depth > 12) {
            return
        }
        progress?.at(dir, hits.size)
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (activeToken.get().isCancelled || hits.size >= limit) {
                return
            }
            if (file.name.startsWith(".")) {
                continue
            }
            if (file.isFile && pattern.matches(file.name)) {
                hits.add(file.absolutePath)
            }
            if (file.isDirectory) {
                globWalk(file, pattern, hits, limit, depth + 1, progress)
            }
        }
    }

    // ---- archives ----------------------------------------------------------

    private fun listArchive(args: JSONObject, observer: Observer? = null): String {
        val archive = resolve(pathArg(args))
        if (!archive.exists()) {
            return "ERROR: not found: " + rel(archive)
        }
        if (archive.isDirectory) {
            return "ERROR: is a directory (use list_dir): " + rel(archive)
        }
        val maxResults = args.optInt("max_results", 800)
        return try {
            ZipFile(archive).use { zip ->
                val sb = StringBuilder(
                    "Archive: " + rel(archive) + "  (" + zip.size() + " entries)\n"
                )
                val entries = zip.entries()
                var shown = 0
                var lastReport = 0L
                while (!activeToken.get().isCancelled && entries.hasMoreElements() &&
                    shown < maxResults
                ) {
                    val entry = entries.nextElement()
                    shown++
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250L) {
                        lastReport = now
                        observer?.onProgress(
                            rel(archive) + "  \u00b7  " +
                                Fa.TRAIL_PROG_ENTRIES.format(shown.toString())
                        )
                    }
                    if (entry.isDirectory) {
                        sb.append("  ▸ ").append(entry.name).append("\n")
                    } else {
                        val size = if (entry.size >= 0) entry.size else 0L
                        sb.append("  · ").append(entry.name).append("  (")
                            .append(Util.humanSize(size)).append(")\n")
                    }
                }
                if (activeToken.get().isCancelled) {
                    return "CANCELLED: user stopped archive listing"
                }
                if (zip.size() > maxResults) {
                    sb.append("  …and ").append(zip.size() - maxResults).append(" more\n")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            "ERROR: not a valid archive: " + e.message
        }
    }

    private fun readArchiveEntry(args: JSONObject, observer: Observer? = null): String {
        val archive = resolve(pathArg(args))
        val entryName = args.optStr("entry", "")
        if (!archive.exists()) {
            return "ERROR: not found: " + rel(archive)
        }
        if (entryName.isEmpty()) {
            return "ERROR: entry is required"
        }
        val maxBytes = Math.max(1, args.optInt("max_bytes", 100000))
        return try {
            ZipFile(archive).use { zip ->
                observer?.onProgress(rel(archive) + "  \u00b7  " + entryName)
                val entry = zip.getEntry(entryName)
                    ?: return "ERROR: entry not found in archive: $entryName"
                if (entry.isDirectory) {
                    return "ERROR: entry is a directory: $entryName"
                }
                // Stream at most what will be shown. The old code inflated the
                // WHOLE entry first (up to 16 MB) and truncated afterwards — the
                // allocation happened before the cap, which is the wrong order
                // on a phone. A zip bomb entry also inflated megabytes here.
                val total = if (entry.size >= 0) entry.size else -1L
                val bytes = zip.getInputStream(entry).use { input ->
                    readBounded(input, maxBytes + 1, activeToken.get())
                }
                val sizeText = if (total >= 0) Util.humanSize(total)
                else Util.humanSize(bytes.size.toLong())
                if (looksBinary(bytes)) {
                    "Binary entry " + entryName + " (" + sizeText +
                        ").\n" + hexPreview(bytes, 512)
                } else {
                    val shown = Math.min(bytes.size, maxBytes)
                    val out = StringBuilder("Entry: ").append(entryName)
                        .append(" (").append(sizeText).append(")\n")
                        .append(String(bytes, 0, shown, Charsets.UTF_8))
                    if (bytes.size > maxBytes) {
                        out.append("\n…[showing first ").append(maxBytes)
                            .append(" bytes of ").append(sizeText).append("]")
                    }
                    out.toString()
                }
            }
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    /**
     * Reads at most [limit] bytes from [input], honouring cancellation.
     * The buffer never grows past the limit, so callers that only show the
     * first N bytes stream instead of materialising a whole file and
     * truncating afterwards.
     */
    private fun readBounded(
        input: InputStream,
        limit: Int,
        token: CancellationToken
    ): ByteArray {
        val out = ByteArrayOutputStream(Math.min(limit, 65536))
        val buffer = ByteArray(65536)
        var total = 0
        while (total < limit) {
            token.throwIfCancelled()
            val n = input.read(buffer, 0, Math.min(buffer.size, limit - total))
            if (n < 0) {
                break
            }
            if (n == 0) {
                continue
            }
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    @Throws(Exception::class)
    private fun extractArchiveEntry(
        args: JSONObject,
        token: CancellationToken,
        observer: Observer? = null
    ): String {
        val archive = resolve(pathArg(args))
        val entryName = args.optStr("entry", "")
        val toArg = args.optStr("to", "")
        if (!archive.exists()) {
            return "ERROR: not found: " + rel(archive)
        }
        if (archive.isDirectory) {
            return "ERROR: is a directory (use list_dir): " + rel(archive)
        }
        if (entryName.isEmpty()) {
            return "ERROR: entry is required"
        }
        if (toArg.isBlankJava()) {
            return "ERROR: 'to' destination path is required"
        }
        return try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: return "ERROR: entry not found in archive: $entryName"
                if (entry.isDirectory) {
                    return "ERROR: entry is a directory: $entryName"
                }
                observer?.onProgress(rel(archive) + "  \u00b7  " + entryName)
                var dest = resolve(toArg)
                var base = entryName.replace('\\', '/')
                val slash = base.lastIndexOf('/')
                if (slash >= 0) {
                    base = base.substring(slash + 1)
                }
                if (base.isEmpty()) {
                    base = "extracted"
                }
                if (dest.isDirectory || toArg.endsWith("/") || toArg.endsWith(File.separator)) {
                    // Re-validate: `base` comes from the archive, and an entry
                    // named "x/.." makes File(validatedDir, "..") resolve
                    // outside the checked subtree. Containment was previously
                    // accidental (the write happened to fail on a directory),
                    // not enforced.
                    dest = resolve(File(dest, base).path)
                }
                val parent = dest.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                // A zip bomb (gigabytes from kilobytes) used to inflate straight
                // onto the disk with no ceiling; a cancelled extraction left a
                // half-written file behind that looked complete.
                val declared = entry.size
                if (declared > MAX_EXTRACT_BYTES) {
                    return "ERROR: entry too large to extract (" +
                        Util.humanSize(declared) + " > " +
                        Util.humanSize(MAX_EXTRACT_BYTES) + ")"
                }
                // Write to a sibling temp file and rename into place, so a
                // failure or a cancelled run never leaves a partial `dest`.
                val temp = File(
                    parent,
                    "." + dest.name + ".vega-extract-" + Thread.currentThread().id +
                        "-" + System.nanoTime()
                )
                var written = 0L
                var complete = false
                try {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) {
                                    break
                                }
                                token.throwIfCancelled()
                                if (written + n > MAX_EXTRACT_BYTES) {
                                    throw IOException(
                                        "entry exceeds the " +
                                            Util.humanSize(MAX_EXTRACT_BYTES) +
                                            " extraction limit"
                                    )
                                }
                                output.write(buffer, 0, n)
                                written += n
                            }
                        }
                    }
                    if (!temp.renameTo(dest)) {
                        throw IOException("could not move extracted file into place")
                    }
                    complete = true
                } finally {
                    if (!complete) {
                        temp.delete()
                    }
                }
                val mime = Util.mimeOf(dest.name)
                "OK: extracted \"" + entryName + "\" -> " + rel(dest) + " (" +
                    Util.humanSize(written) + ", " + mime + ")"
            }
        } catch (e: CancellationToken.CancelledException) {
            "CANCELLED: user stopped extraction"
        }
    }

    // ---- pdf ---------------------------------------------------------------

    private fun readPdf(args: JSONObject, observer: Observer? = null): String {
        val target = resolve(pathArg(args))
        if (!target.exists()) {
            return "ERROR: not found: " + rel(target)
        }
        val maxBytes = args.optInt("max_bytes", 120000)
        return try {
            observer?.onProgress(rel(target) + "  \u00b7  " + Fa.TRAIL_PROG_READING)
            val bytes = Util.readAll(target)
            val raw = String(bytes, StandardCharsets.ISO_8859_1)
            val sb = StringBuilder()
            var cursor = 0
            var pages = 0
            var lastReport = 0L
            do {
                // A big scanned PDF spends seconds in this loop with no way
                // out; the user stopping the run used to wait for it to finish.
                activeToken.get().throwIfCancelled()
                val streamAt = raw.indexOf("stream", cursor)
                if (streamAt < 0) {
                    break
                }
                var start = streamAt + 6
                if (start < raw.length && raw[start] == '\r') {
                    start++
                }
                if (start < raw.length && raw[start] == '\n') {
                    start++
                }
                val endAt = raw.indexOf("endstream", start)
                if (endAt < 0) {
                    break
                }
                val length = endAt - start
                val chunk = ByteArray(length)
                System.arraycopy(bytes, start, chunk, 0, length)
                extractPdfText(inflateOrRaw(chunk), sb)
                pages++
                val now = System.currentTimeMillis()
                if (now - lastReport > 250L) {
                    lastReport = now
                    observer?.onProgress(
                        rel(target) + "  \u00b7  " +
                            Fa.TRAIL_PROG_PAGES.format(pages.toString())
                    )
                }
                cursor = endAt + 9
            } while (sb.length <= maxBytes)

            val text = sb.toString()
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trimJava()
            if (text.isEmpty()) {
                "PDF parsed but no extractable text found (it may be scanned/image-based): " +
                    rel(target)
            } else {
                "PDF: " + rel(target) + " (" + Util.humanSize(target.length()) + ")\n" +
                    Util.truncate(text, maxBytes)
            }
        } catch (cancelled: CancellationToken.CancelledException) {
            // Let the tool wrapper report this as CANCELLED, not as a failure.
            throw cancelled
        } catch (e: Exception) {
            "ERROR: read_pdf failed: " + e.message
        }
    }

    companion object {

        /**
         * Hard ceiling on the lines one read_file call returns.
         *
         * Sized so a window comfortably survives the agent loop's 24 000-char
         * tool-result cap, which used to silently swallow most of a large read
         * *after* the model had been told it got 120 000 characters.
         */
        /**
         * Files the agent has read, by canonical path.
         *
         * This is the mechanical half of "edit surgically": [editFile] refuses
         * to touch a file that is not in here, so the model has to look before
         * it patches. It is process-wide on purpose — [AgentService] builds a
         * fresh [Tools] for every user message, so per-instance state would
         * expire between "read Foo.kt" and the very next turn's "now rename
         * that function", and every follow-up edit would cost a redundant read.
         */
        private val READ_PATHS = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

        /**
         * Cap on remembered read paths: an ordered, bounded structure (#10).
         * The eldest entries are evicted first, so the set can neither grow
         * unbounded nor forget a file the model just read.
         */
        private const val READ_PATHS_CAP = 2000

        /**
         * Remembers one read path, evicting the oldest entries past the cap.
         * Synchronized with the set's own monitor so compound add/evict is
         * atomic (#10).
         */
        internal fun noteReadPath(path: String) {
            synchronized(READ_PATHS) {
                READ_PATHS.add(path)
                var over = READ_PATHS.size - READ_PATHS_CAP
                val it = READ_PATHS.iterator()
                while (over > 0 && it.hasNext()) {
                    it.next()
                    it.remove()
                    over--
                }
            }
        }

        /** Current remembered-read count — test hook. */
        internal fun readPathsCount(): Int = READ_PATHS.size

        /**
         * Empties the remembered read paths (#10). The automatic run-boundary
         * wiring lives in [onRunToken]; this stays as the explicit hook for
         * tests and for any future engine-level call.
         */
        internal fun clearReadPaths() {
            READ_PATHS.clear()
        }

        /**
         * The token of the run whose reads are currently remembered, or null
         * before the first tool call.
         */
        @Volatile
        private var lastRunToken: CancellationToken? = null

        /**
         * Run-boundary hook for the read-before-edit memory (#10).
         *
         * READ_PATHS is process-wide but belongs to a single run: a new run
         * must never see the previous run's reads. A run's first tool call
         * carries the run's fresh [CancellationToken] — which is also
         * [AgentBus]'s current token (the bus mints one per run; sub-agents
         * share their parent's). Seeing that token here for the first time
         * means a new run started, so the previous run's reads are dropped.
         *
         * Two things this deliberately does NOT do:
         *  * clear on every token change — ad-hoc and test callers mint a
         *    token per call, and wiping between a read_file and its edit_file
         *    would break them (and the gate);
         *  * clear in `run()` or `init` — those run once per call / per Tools
         *    instance, and a sub-agent's fresh Tools would wipe its parent's
         *    reads mid-run.
         */
        internal fun onRunToken(token: CancellationToken) {
            if (lastRunToken === token) {
                return
            }
            if (token === AgentBus.token()) {
                synchronized(READ_PATHS) {
                    // Re-checked INSIDE the lock: two threads racing on the
                    // first tool calls of a run must not clear twice — the
                    // second clear would erase a path the first thread had
                    // already recorded.
                    if (lastRunToken !== token) {
                        lastRunToken = token
                        READ_PATHS.clear()
                    }
                }
            }
        }

        /** Upper bound on cached pages: the newest stay, the oldest are deleted (#9). */
        internal const val WEB_CACHE_MAX_FILES = 50

        const val MAX_READ_LINES = 400

        private val webCacheSeq = AtomicLong(0)

        /**
         * Unique cache file name: timestamp + nanoTime + a process-wide
         * sequence, so two fetches of the same URL in the same second never
         * collide (#9). Pure — testable without a Context.
         */
        internal fun webCacheFileName(url: String?, stamp: String, slug: String): String =
            stamp + "_" + System.nanoTime() + "_" + webCacheSeq.getAndIncrement() + "_" + slug + ".txt"

        /**
         * Caps the .webpages cache at [maxFiles] newest files, deleting the
         * oldest first (#9). Pure file ops — testable without a Context.
         */
        internal fun enforceWebCacheCap(dir: File, maxFiles: Int = WEB_CACHE_MAX_FILES) {
            try {
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
                if (files.size <= maxFiles) {
                    return
                }
                files.sortBy { it.lastModified() }
                var i = 0
                while (files.size - i > maxFiles && i < files.size) {
                    try {
                        files[i].delete()
                    } catch (ignored: Exception) {
                    }
                    i++
                }
            } catch (ignored: Exception) {
            }
        }

        /**
         * Longest directory listing one list_dir call returns. Past this the
         * reply names the exact total and stops; huge folders used to produce
         * multi-megabyte tool results.
         */
        private const val MAX_DIR_ENTRIES = 2000

        /**
         * Ceiling on one extracted archive entry. A zip bomb inflates gigabytes
         * from kilobytes; without this the phone's storage was the limit.
         */
        private const val MAX_EXTRACT_BYTES = 512L * 1024L * 1024L

        /**
         * Longest file content search will scan per file. Files at or above
         * this are skipped by name only, so one giant log cannot stall a
         * whole-tree search.
         */
        private const val SEARCH_FILE_BYTES = 2000000

        /**
         * Longest single line one read_file window will materialise. A minified
         * bundle is one "line"; without this the length cap does nothing.
         */
        const val LINE_CHAR_CAP = 8000

        /** [readWindowLine] outcomes. */
        private const val LINE_EOF = -1
        private const val LINE_OK = 0
        private const val LINE_TRUNCATED = 1

        /** One numbered read_file window. */
        internal class ReadWindow {
            val text = StringBuilder()
            var emitted = 0

            /** File line number of the last line emitted. */
            var lastLine = 0

            /** True only when the reader reached a genuine end of input. */
            var atEof = false
        }

        /**
         * Builds one numbered window from [reader], honouring the line, byte and
         * range bounds. Pure apart from the reader, so the geometry it promises
         * — same line numbers as the file, no newlines the file does not have —
         * can actually be tested.
         */
        @Throws(IOException::class)
        internal fun readWindow(
            reader: BufferedReader,
            from: Int,
            endLine: Int,
            maxBytes: Int,
            token: CancellationToken?
        ): ReadWindow {
            val window = ReadWindow()
            val line = StringBuilder()
            var lineNo = 0
            var bytes = 0
            window.lastLine = from
            while (true) {
                token?.throwIfCancelled()
                line.setLength(0)
                val status = readWindowLine(reader, line)
                if (status == LINE_EOF) {
                    window.atEof = true
                    break
                }
                lineNo++
                if (lineNo <= from) {
                    continue
                }
                if (window.emitted >= MAX_READ_LINES || (endLine > 0 && lineNo > endLine)) {
                    break
                }
                bytes += line.length + 1
                if (bytes > maxBytes && window.emitted > 0) {
                    break
                }
                window.text.append(lineNo).append("\t").append(line)
                if (status == LINE_TRUNCATED) {
                    window.text.append("  ⟵[line truncated at ").append(LINE_CHAR_CAP)
                        .append(" chars — do NOT copy this line into old_string]")
                }
                window.text.append('\n')
                window.emitted++
                window.lastLine = lineNo
            }
            return window
        }

        /**
         * Reads one '\n'-terminated line into [out], keeping any '\r'.
         *
         * `BufferedReader.readLine` has no length bound: a minified bundle or a
         * single-line JSON dump is one "line", and asking for it materialises
         * the whole file. This keeps the first [LINE_CHAR_CAP] characters and
         * then DISCARDS the rest of that physical line rather than emitting it
         * as a second one — splitting it would renumber every following line and
         * hand the model a window containing newlines the file does not have, so
         * no `old_string` copied out of it could ever match.
         *
         * Returns [LINE_EOF] at a true end of input, [LINE_TRUNCATED] when the
         * line was longer than the cap, [LINE_OK] otherwise.
         */
        @Throws(IOException::class)
        private fun readWindowLine(reader: BufferedReader, out: StringBuilder): Int {
            var any = false
            while (true) {
                val c = reader.read()
                if (c < 0) {
                    return if (any) LINE_OK else LINE_EOF
                }
                any = true
                if (c == '\n'.code) {
                    return LINE_OK
                }
                if (out.length < LINE_CHAR_CAP) {
                    out.append(c.toChar())
                    continue
                }
                // Never cut a surrogate pair in half: the two halves encode to
                // '?' each, and the region becomes unmatchable forever.
                if (out.isNotEmpty() && Character.isHighSurrogate(out[out.length - 1])) {
                    out.setLength(out.length - 1)
                }
                while (true) {
                    val drain = reader.read()
                    if (drain < 0 || drain == '\n'.code) {
                        return LINE_TRUNCATED
                    }
                }
            }
        }

        /** Directories first, then case-insensitive by name. */
        private val DIRS_FIRST = Comparator<File> { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                a.name.compareTo(b.name, ignoreCase = true)
            }
        }

        /**
         * Tools that change the device — blocked entirely in PLAN (read-only)
         * mode.
         *
         * `remember` is deliberately NOT here (B14): saving to the agent's
         * own memory is not a device/file mutation, and plan mode must still
         * learn.
         */
        fun isMutating(name: String?): Boolean =
            ToolNames.WRITE_FILE == name || ToolNames.EDIT_FILE == name ||
                ToolNames.DELETE == name || ToolNames.MKDIR == name ||
                ToolNames.MOVE == name || ToolNames.INSTALL_SKILL == name ||
                ToolNames.DOWNLOAD == name || ToolNames.EXTRACT_ARCHIVE_ENTRY == name

        /** Every tool name the agent can actually call. */
        private val ALL_TOOLS: Set<String> = hashSetOf(
            ToolNames.DELETE, ToolNames.DOWNLOAD, ToolNames.EDIT_FILE, ToolNames.FILE_INFO,
            ToolNames.GLOB, ToolNames.LIST_ARCHIVE, ToolNames.LIST_DIR, ToolNames.MKDIR,
            ToolNames.MOVE, ToolNames.READ_ARCHIVE_ENTRY, ToolNames.EXTRACT_ARCHIVE_ENTRY,
            ToolNames.READ_FILE, ToolNames.READ_PDF, ToolNames.RECALL, ToolNames.REMEMBER,
            ToolNames.SEARCH, ToolNames.WEB_FETCH, ToolNames.WEB_FETCH_MANY, ToolNames.WEB_SEARCH,
            ToolNames.WRITE_FILE, ToolNames.TASK, ToolNames.INSTALL_SKILL
        )

        /**
         * True only for names in [ALL_TOOLS].
         *
         * The tool-call parser leans on this to tell an actual invocation from a
         * JSON snippet the model was merely *showing* the user.
         */
        fun isKnownTool(name: String?): Boolean = !name.isNullOrEmpty() && ALL_TOOLS.contains(name)

        /**
         * Tools that require the user's consent in ACCEPT mode.
         *
         * This used to be an alias of [isMutating], so ACCEPT only ever asked
         * before a *change* — the agent read every file, searched the whole
         * workspace and browsed the web without a word. That is AUTO with a
         * confirmation dialog bolted on, not an approval mode. Everything the
         * agent does on the user's behalf is gated now; "always allow" on the
         * approval sheet is what keeps that from becoming tedious.
         */
        fun needsApproval(name: String?): Boolean =
            !name.isNullOrEmpty() && ToolNames.RECALL != name

        /**
         * Plain-language name for a tool, for the approval sheet and the tool
         * cards. `read_file` means nothing to someone who is being asked
         * whether to allow it; "read a file" does.
         */
        fun actionLabel(name: String?): String = when (name) {
            ToolNames.READ_FILE -> Fa.ACT_READ
            ToolNames.LIST_DIR, ToolNames.FILE_INFO -> Fa.ACT_LIST
            ToolNames.SEARCH, ToolNames.GLOB -> Fa.ACT_SEARCH
            ToolNames.WRITE_FILE -> Fa.ACT_WRITE
            ToolNames.EDIT_FILE -> Fa.ACT_EDIT
            ToolNames.DELETE -> Fa.ACT_DELETE
            ToolNames.MOVE -> Fa.ACT_MOVE
            ToolNames.MKDIR -> Fa.ACT_MKDIR
            ToolNames.DOWNLOAD -> Fa.ACT_DOWNLOAD
            ToolNames.WEB_SEARCH -> Fa.ACT_WEB_SEARCH
            ToolNames.WEB_FETCH -> Fa.ACT_WEB_FETCH
            ToolNames.WEB_FETCH_MANY -> Fa.ACT_WEB_FETCH
            ToolNames.LIST_ARCHIVE, ToolNames.READ_ARCHIVE_ENTRY,
            ToolNames.EXTRACT_ARCHIVE_ENTRY -> Fa.ACT_ARCHIVE
            ToolNames.READ_PDF -> Fa.ACT_PDF
            ToolNames.REMEMBER, ToolNames.RECALL -> Fa.ACT_MEMORY
            ToolNames.INSTALL_SKILL -> Fa.ACT_INSTALL_SKILL
            ToolNames.TASK -> Fa.ACT_TASK
            else -> Fa.ACT_OTHER
        }

        /** Icon key for a tool, so a web search does not look like a file write. */
        fun actionIcon(name: String?): String = when (name) {
            ToolNames.TASK -> "sparkle"
            ToolNames.INSTALL_SKILL -> "plus"
            ToolNames.WEB_SEARCH -> "search"
            ToolNames.WEB_FETCH, ToolNames.WEB_FETCH_MANY, ToolNames.DOWNLOAD -> "globe"
            ToolNames.READ_FILE, ToolNames.READ_PDF, ToolNames.FILE_INFO -> "file"
            ToolNames.LIST_DIR, ToolNames.MKDIR -> "folder"
            ToolNames.SEARCH, ToolNames.GLOB -> "search"
            ToolNames.WRITE_FILE, ToolNames.EDIT_FILE -> "edit"
            ToolNames.DELETE -> "trash"
            else -> "tool"
        }

        /**
         * The agent's workspace root — the folder every path in [resolve] is
         * measured against.
         *
         * Three cases, and the middle one used to be missing:
         *
         *  * **API 30+** — scoped storage. Shared storage is reachable only with
         *    All-files access, so the whole card is the workspace once
         *    `isExternalStorageManager()` says yes.
         *  * **API 23-29** — the legacy model. `READ_EXTERNAL_STORAGE` is a real
         *    runtime grant over the whole shared volume, and this function used
         *    to ignore it completely and hand back `getExternalFilesDir` anyway.
         *    An Android 6 user therefore saw the permission prompt, granted it,
         *    and nothing whatsoever changed: the agent still could not see
         *    /sdcard/Download, so every path the user named resolved outside the
         *    workspace and was refused — by an app whose own banner had just
         *    told them access was granted.
         *  * **Nothing granted** — the app-private directory, which needs no
         *    permission on any version and is always writable.
         *
         * Every platform call is version-guarded for a 23 floor:
         * `isExternalStorageManager` is API 30 and `checkSelfPermission` is API
         * 23, which is the floor itself.
         */
        fun externalRoot(context: Context): File {
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= 30) {
                if (Environment.isExternalStorageManager()) {
                    @Suppress("DEPRECATION")
                    return Environment.getExternalStorageDirectory()
                }
            } else if (Build.VERSION.SDK_INT >= 23 && hasLegacyReadStorage(app)) {
                @Suppress("DEPRECATION")
                val shared: File? = Environment.getExternalStorageDirectory()
                // A device with no mounted shared volume returns null here, and a
                // null root would make every resolve() throw. Fall through to the
                // private directory instead.
                if (shared != null) {
                    return shared
                }
            }
            val root = app.getExternalFilesDir(null)
            return root ?: app.filesDir
        }

        /**
         * True when the legacy whole-volume storage grant is held. Only ever
         * consulted on API 23-29; the permission is meaningless above that.
         */
        private fun hasLegacyReadStorage(app: Context): Boolean {
            if (Build.VERSION.SDK_INT < 23) {
                return false
            }
            return try {
                app.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") ==
                    PackageManager.PERMISSION_GRANTED
            } catch (ignored: Throwable) {
                false
            }
        }

        private fun canResume(total: Long, done: Long, attempts: Int): Boolean =
            total > 0 && done < total && attempts < 6

        /** Sleeps up to [ms], waking early (and returning true) if cancelled. */
        private fun sleepCancelable(ms: Long, token: CancellationToken): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                if (token.isCancelled) {
                    return true
                }
                try {
                    Thread.sleep(100)
                } catch (ie: InterruptedException) {
                    // A foreign interrupt is never swallowed: restore the
                    // flag so the owner still sees it, and stop sleeping.
                    Thread.currentThread().interrupt()
                    return true
                }
            }
            return token.isCancelled
        }

        private fun stripMime(mime: String?): String? {
            if (mime == null) {
                return null
            }
            val semicolon = mime.indexOf(';')
            return if (semicolon > 0) mime.substring(0, semicolon).trimJava() else mime
        }

        private fun closeQuiet(d: DlConn?) {
            if (d == null) {
                return
            }
            try {
                d.watch?.close()
            } catch (ignored: Exception) {
            }
            d.watch = null
            try {
                d.conn?.disconnect()
            } catch (ignored: Exception) {
            }
            d.conn = null
        }

        @Throws(Exception::class)
        private fun copyFile(from: File, to: File, token: CancellationToken) {
            var complete = false
            try {
                FileInputStream(from).use { input ->
                    FileOutputStream(to).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) {
                                break
                            }
                            token.throwIfCancelled()
                            output.write(buffer, 0, n)
                        }
                    }
                }
                complete = true
            } finally {
                if (!complete && to.exists()) {
                    to.delete()
                }
            }
        }

        private fun dedupe(dir: File, name: String): File {
            val direct = File(dir, name)
            if (!direct.exists()) {
                return direct
            }
            var base = name
            var ext = ""
            val dot = name.lastIndexOf('.')
            if (dot > 0) {
                base = name.substring(0, dot)
                ext = name.substring(dot)
            }
            for (i in 1 until 500) {
                val candidate = File(dir, "$base ($i)$ext")
                if (!candidate.exists()) {
                    return candidate
                }
            }
            return File(dir, base + "_" + System.currentTimeMillis() + ext)
        }

        private val DISPOSITION_EXT =
            Regex("filename\\*=(?:UTF-8''|utf-8'')([^;\\r\\n]+)")
        private val DISPOSITION_PLAIN = Regex("filename=\"?([^\";\\r\\n]+)\"?")

        /** Filename precedence: explicit arg → Content-Disposition → URL path → generated. */
        private fun pickDownloadName(
            arg: String?,
            disposition: String?,
            url: String?,
            mime: String?
        ): String {
            var name = arg?.trimJava() ?: ""
            if (name.isEmpty() && disposition != null) {
                val extended = DISPOSITION_EXT.find(disposition)
                if (extended != null) {
                    try {
                        name = URLDecoder.decode(extended.groupValues[1].trimJava(), "UTF-8")
                    } catch (e: Exception) {
                    }
                }
                if (name.isEmpty()) {
                    val plain = DISPOSITION_PLAIN.find(disposition)
                    if (plain != null) {
                        name = plain.groupValues[1].trimJava()
                    }
                }
            }
            if (name.isEmpty()) {
                try {
                    val path = URL(url).path
                    val slash = path.lastIndexOf('/')
                    val last = if (slash >= 0) path.substring(slash + 1) else path
                    if (last.isNotEmpty()) {
                        name = URLDecoder.decode(last, "UTF-8")
                    }
                } catch (e: Exception) {
                }
            }
            if (name.isEmpty()) {
                name = "download_" + System.currentTimeMillis()
            }
            name = cleanFileName(name)
            // make sure there's a sensible extension
            if (name.indexOf('.') < 0 && mime != null) {
                var ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (ext == null) {
                    if (mime == "audio/mpeg") {
                        ext = "mp3"
                    } else if (mime == "image/jpeg") {
                        ext = "jpg"
                    }
                }
                if (!ext.isNullOrEmpty()) {
                    name = "$name.$ext"
                }
            }
            return name
        }

        /**
         * Keeps unicode (Persian!) names intact; strips only characters that are
         * actually illegal or dangerous in file names.
         */
        private fun cleanFileName(value: String): String {
            var out = value.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trimJava()
            while (out.startsWith(".")) {
                out = out.substring(1)
            }
            if (out.length > 120) {
                var ext = ""
                val dot = out.lastIndexOf('.')
                if (dot > out.length - 12 && dot > 0) {
                    ext = out.substring(dot)
                }
                out = out.substring(0, 120 - ext.length) + ext
            }
            return if (out.isEmpty()) "download" else out
        }

        // ---- edit helpers --------------------------------------------------

        /** Applies one old→new replacement, with a safe whitespace-tolerant fallback. */
        /**
         * Strips a `12⇥` line-number prefix from every line, if every line has
         * one.
         *
         * read_file hands the model numbered lines, and the prompt tells it to
         * copy old_string straight out of what it read — so sooner or later it
         * copies the numbers too. The needle then cannot match, `fuzzyFind`
         * cannot rescue it either (digits are not whitespace), and the model's
         * next move is to give up and rewrite the whole file, which is exactly
         * what all of this is meant to prevent. Returns null when the text is
         * not uniformly prefixed, so real code that happens to start with a
         * number is never mangled.
         */
        internal fun stripLineNumbers(text: String): String? {
            if (text.isEmpty() || !text.contains('\t')) {
                return null
            }
            val lines = text.split("\n")
            val out = StringBuilder()
            var stripped = 0
            var kept = 0
            for (i in lines.indices) {
                val line = lines[i]
                if (i > 0) {
                    out.append('\n')
                }
                // A trailing empty segment is just the final newline.
                if (line.isEmpty() && i == lines.size - 1) {
                    continue
                }
                val bare = withoutLineNumber(line)
                if (bare != null) {
                    out.append(bare)
                    stripped++
                } else {
                    // PER LINE, not all-or-nothing. This used to bail out the
                    // moment a single line lacked the prefix — which is the
                    // common case, because a copied region usually contains a
                    // blank line, a line the model retyped, or a wrapped one.
                    // One unprefixed line then defeated the whole repair and the
                    // edit failed with "old_string not found".
                    out.append(line)
                    kept++
                }
            }
            // Require real evidence of numbering: a lone "1\tx" inside otherwise
            // plain text is far more likely to be a genuine tab-separated line.
            if (stripped == 0 || stripped < kept) {
                return null
            }
            return out.toString()
        }

        /** `"  12\tcode"` -> `"code"`, or null when the line is not numbered. */
        private fun withoutLineNumber(line: String): String? {
            val tab = line.indexOf('\t')
            if (tab <= 0) {
                return null
            }
            var i = 0
            // read_file emits no leading space, but a model re-indenting its
            // paste is common enough to tolerate.
            while (i < tab && (line[i] == ' ')) {
                i++
            }
            if (i >= tab) {
                return null
            }
            for (j in i until tab) {
                if (line[j] < '0' || line[j] > '9') {
                    return null
                }
            }
            return line.substring(tab + 1)
        }

        private fun applyOne(
            content: String,
            oldS: String,
            newS: String,
            all: Boolean
        ): EditResult {
            if (oldS.isEmpty()) {
                val empty = EditResult()
                // Belt and braces: callers already reject this, and an empty needle
                // would otherwise mean "match everywhere" — never a real edit.
                empty.error = "old_string is empty, so there is nothing to find."
                empty.detail =
                    "Send the exact text to be replaced in old_string. To INSERT " +
                    "rather than replace, use a unique nearby line as old_string and " +
                    "put that same line plus your new text in new_string."
                return empty
            }
            val direct = applyExact(content, oldS, newS, all)
            if (direct.error == null) {
                return direct
            }
            // The model pasted read_file's "12⇥" prefixes into the needle. Retry
            // ONCE without them.
            //
            // new_string has to be stripped in the same breath: a model that
            // copies a numbered region to edit it produces both strings in
            // numbered form, and stripping only the needle would match and then
            // write "4\t    val x = 2" into the file — silent corruption
            // reported as a clean edit. Done iteratively, not by recursing:
            // recursion on a wide numeric TSV row overflowed the stack.
            val bareOld = stripLineNumbers(oldS)
            if (bareOld != null && bareOld != oldS && bareOld.isNotEmpty()) {
                val bareNew = stripLineNumbers(newS) ?: newS
                val retry = applyExact(content, bareOld, bareNew, all)
                if (retry.error == null) {
                    return retry
                }
            }
            return direct
        }

        /** One exact-or-whitespace-tolerant replacement pass. */
        private fun applyExact(
            content: String,
            oldS: String,
            newS: String,
            all: Boolean
        ): EditResult {
            val result = EditResult()
            val count = countOccurrences(content, oldS)
            if (count == 0) {
                val span = fuzzyFind(content, oldS)
                if (span != null) {
                    result.text = content.substring(0, span[0]) + newS +
                        content.substring(span[1])
                    result.count = 1
                    result.fuzzy = true
                    return result
                }
                // Last resort: anchor on the first and last non-blank lines.
                // Indentation drift in the MIDDLE of a copied block is the most
                // common remaining cause of a failed match, and the ends are
                // usually reproduced faithfully.
                val anchored = anchorFind(content, oldS)
                if (anchored != null) {
                    result.text = content.substring(0, anchored[0]) + newS +
                        content.substring(anchored[1])
                    result.count = 1
                    result.fuzzy = true
                    return result
                }
                // Show what IS there, and name the causes in the order they
                // actually occur. Without this the model is guessing blind and
                // usually gives up and rewrites the whole file — the exact failure
                // this tool exists to prevent.
                //
                // Getting here means the ENTIRE ladder missed: exact, then
                // whitespace-tolerant, then first/last-line anchored, and (in
                // applyOne) the same three again with line-number prefixes
                // stripped. So the needle is not a near miss — it is text that is
                // not in the file in any recognisable form, and the message has to
                // be honest about the several reasons that can happen.
                result.error = "old_string not found — nothing was changed."
                result.detail =
                    "Most likely, in this order:\n" +
                    "  1. old_string was retyped or reformatted instead of copied. " +
                    "One different space, tab, quote or line break is enough; the " +
                    "match already tolerates indentation drift and CRLF, so this is " +
                    "a real difference.\n" +
                    "  2. the \"12<TAB>\" line-number prefixes from read_file were " +
                    "left in old_string.\n" +
                    "  3. that region already changed — an earlier edit in this same " +
                    "call, or the file moved on since you read it.\n" +
                    "Do this: call read_file on the exact lines you want to change, " +
                    "copy old_string straight out of that output byte for byte, " +
                    "WITHOUT the \"12<TAB>\" line-number prefixes, and retry. " +
                    "If it fails a second time, stop matching and use the line-range " +
                    "form instead: edit_file { path, start_line, end_line, new_text }, " +
                    "which needs no matching at all." +
                    nearbyHint(content, oldS)
                return result
            }
            if (count > 1 && !all) {
                result.error =
                    "old_string matches " + count + " places, so it is ambiguous — " +
                    "nothing was changed."
                result.detail =
                    "An edit must name exactly one place unless you say otherwise. " +
                    "Two ways forward, pick one:\n" +
                    "  1. make it unique — extend old_string with the lines directly " +
                    "above and below the ONE occurrence you mean, then retry.\n" +
                    "  2. change every one of the " + count + " — repeat the call with " +
                    "\"replace_all\": true."
                return result
            }
            result.text = if (all) {
                content.replace(oldS, newS)
            } else {
                replaceFirst(content, oldS, newS)
            }
            result.count = if (all) count else 1
            return result
        }

        /**
         * Whitespace-tolerant search: every run of whitespace in the needle matches
         * any run of whitespace in the haystack (so indentation width, tabs vs
         * spaces, trailing spaces and CRLF/LF differences don't block a match).
         * Returns the [start,end] of the single match, or null if absent/ambiguous.
         */
        /**
         * Locates a block by its FIRST and LAST non-blank lines.
         *
         * A model re-indenting the middle of a pasted block, or normalising a
         * blank line inside it, defeats both exact and whitespace-tolerant
         * matching even though the region is unmistakable. Anchoring on the two
         * ends recovers it — but only when both anchors are unique, so this can
         * never silently edit the wrong place.
         */
        private fun anchorFind(content: String, oldS: String): IntArray? {
            val oldLines = oldS.split("\n")
            // Drop a trailing empty segment from a block that ends in a newline.
            val body = if (oldLines.isNotEmpty() && oldLines.last().isBlankJava()) {
                oldLines.subList(0, oldLines.size - 1)
            } else {
                oldLines
            }
            if (body.size < 2) {
                return null
            }
            val firstKey = body.first().trimJava()
            val lastKey = body.last().trimJava()
            // The FIRST line must be distinctive enough to locate unambiguously.
            // The last one only has to agree once we are there, so a block ending
            // in a bare "}" — which is most code — still works.
            if (firstKey.length < 6) {
                return null
            }
            val lines = content.split("\n")
            var found = -1
            for (i in lines.indices) {
                if (lines[i].trimJava() == firstKey) {
                    if (found >= 0) {
                        return null // ambiguous start — never guess
                    }
                    found = i
                }
            }
            if (found < 0) {
                return null
            }
            val endLine = found + body.size - 1
            if (endLine >= lines.size) {
                return null
            }
            // Same shape, same end: this is the block, just re-indented.
            if (lines[endLine].trimJava() != lastKey) {
                return null
            }
            var begin = 0
            for (i in 0 until found) {
                begin += lines[i].length + 1
            }
            var end = begin
            for (i in found..endLine) {
                end += lines[i].length + 1
            }
            end -= 1 // the final newline is not part of the span
            if (end > content.length) {
                end = content.length
            }
            return intArrayOf(begin, end)
        }

        /**
         * The ~400 characters that are REALLY at the place the needle aimed at,
         * quoted verbatim, with the line number they start on.
         *
         * This is the single most useful thing a failed match can hand back, and
         * the line number is what makes it actionable twice over: the model can
         * either copy the quoted text byte for byte, or — if it still cannot
         * reproduce it, which is the normal outcome on generated HTML with
         * 2000-character lines — address the region by number with the
         * line-range form and skip matching entirely.
         *
         * Returns "" (and the caller simply omits the block) when no line of the
         * needle is distinctive enough to locate.
         */
        private fun nearbyHint(content: String, oldS: String): String {
            try {
                val probe = oldS.split("\n").map { it.trimJava() }
                    .firstOrNull { it.length >= 8 } ?: return ""
                val at = content.indexOf(probe)
                if (at < 0) {
                    return ""
                }
                var begin = content.lastIndexOf('\n', at) + 1
                if (begin < 0) {
                    begin = 0
                }
                val end = Math.min(content.length, begin + 400)
                var line = 1
                for (i in 0 until begin) {
                    if (content[i] == '\n') {
                        line++
                    }
                }
                return "\nWhat the file actually contains there, from line " + line +
                    " (verbatim, no line-number prefixes):\n---\n" +
                    content.substring(begin, end) + "\n---"
            } catch (ignored: Exception) {
                return ""
            }
        }

        private fun fuzzyFind(content: String, oldS: String): IntArray? {
            val rx = StringBuilder()
            var i = 0
            val n = oldS.length
            while (i < n) {
                if (Character.isWhitespace(oldS[i])) {
                    while (i < n && Character.isWhitespace(oldS[i])) {
                        i++
                    }
                    rx.append("\\s+")
                } else {
                    var j = i
                    while (j < n && !Character.isWhitespace(oldS[j])) {
                        j++
                    }
                    rx.append(Regex.escape(oldS.substring(i, j)))
                    i = j
                }
            }
            try {
                val pattern = Regex(rx.toString())
                val first = pattern.find(content)
                if (first != null) {
                    val start = first.range.first
                    val end = first.range.last + 1
                    if (pattern.find(content, end) != null) {
                        return null // ambiguous — refuse rather than edit the wrong spot
                    }
                    return intArrayOf(start, end)
                }
            } catch (ignored: Exception) {
            }
            return null
        }

        /**
         * Writes [content] to [file] atomically: everything lands in a sibling
         * temp file which is then renamed over the target.
         *
         * `FileOutputStream(file)` truncates the moment it is opened, so a
         * failure part-way through the write (ENOSPC, a cancellation, a killed
         * process) left the user's file empty or half-written — directly
         * contradicting the "the file is never left half changed" contract this
         * editor advertises to the model. A rename is the only way to keep that
         * promise on a real filesystem.
         *
         * Returns true when the atomic rename was refused and the fallback
         * direct write was used instead, so callers can report honestly that
         * the write was not atomic.
         */
        @Throws(Exception::class)
        internal fun writeUtf8(file: File, content: String): Boolean {
            val bytes = content.toByteArray(Charsets.UTF_8)
            return writeBytesAtomic(file, bytes)
        }

        @Throws(Exception::class)
        internal fun writeBytesAtomic(file: File, bytes: ByteArray): Boolean {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) {
                if (!parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("could not create parent folder: " + parent.absolutePath)
                }
            }
            // Unique per call: tools run on their own threads, so two parallel
            // writes to the same target used to share one temp path, interleave
            // bytes, and let the last rename win silently. The temp stays a
            // sibling of the target so the rename keeps its atomicity.
            val temp = File(
                parent,
                "." + file.name + ".vega-tmp-" + Thread.currentThread().id +
                    "-" + System.nanoTime()
            )
            var nonAtomic = false
            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.flush()
                    try {
                        output.fd.sync()
                    } catch (ignored: Exception) {
                    }
                }
                if (!temp.renameTo(file)) {
                    // Same-directory rename should never fail, but a few OEM
                    // sdcardfs mounts do; fall back to a direct write so the
                    // operation still succeeds rather than failing outright.
                    // This path is NOT atomic — the destination is truncated on
                    // open — so it fsyncs too and the caller says so.
                    FileOutputStream(file).use { output ->
                        output.write(bytes)
                        output.flush()
                        try {
                            output.fd.sync()
                        } catch (ignored: Exception) {
                        }
                    }
                    nonAtomic = true
                }
                try {
                    temp.delete()
                } catch (ignored: Exception) {
                }
            } catch (error: Exception) {
                try {
                    temp.delete()
                } catch (ignored: Exception) {
                }
                throw error
            }
            return nonAtomic
        }

        /**
         * BUGFIX vs the original Java: an empty needle made this loop forever
         * (`indexOf("", from)` never returns -1 and `from` never advanced), which
         * would peg a CPU core inside the foreground service until the process was
         * killed. `editFile` happens to reject an empty `old_string` before
         * calling in, so the hang was latent rather than reachable — but the
         * helper is now safe on its own terms so no future caller can trip it.
         */
        private fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) {
                return 0
            }
            var count = 0
            var from = 0
            while (true) {
                val at = haystack.indexOf(needle, from)
                if (at == -1) {
                    return count
                }
                count++
                from = at + needle.length
            }
        }

        private fun replaceFirst(haystack: String, needle: String, replacement: String): String {
            val at = haystack.indexOf(needle)
            if (at < 0) {
                return haystack
            }
            return haystack.substring(0, at) + replacement +
                haystack.substring(at + needle.length)
        }

        // ---- glob / binary / pdf helpers -----------------------------------

        private fun globToRegex(pattern: String): String {
            val sb = StringBuilder()
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '.' -> sb.append("\\.")
                    '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return sb.toString()
        }

        private fun looksBinary(bytes: ByteArray): Boolean {
            val limit = Math.min(bytes.size, 512)
            var control = 0
            for (i in 0 until limit) {
                val value = bytes[i].toInt() and 255
                if (value == 0) {
                    return true
                }
                if (value < 9 || (value > 13 && value < 32)) {
                    control++
                }
            }
            return limit > 0 && control >= limit * 0.12
        }

        private fun hexPreview(bytes: ByteArray, max: Int): String {
            val sb = StringBuilder()
            val limit = Math.min(bytes.size, max)
            var offset = 0
            while (offset < limit) {
                sb.append(String.format(Locale.US, "%08X  ", offset))
                val ascii = StringBuilder()
                var column = 0
                while (column < 16) {
                    val index = offset + column
                    if (index >= limit) {
                        break
                    }
                    val value = bytes[index].toInt() and 255
                    sb.append(String.format(Locale.US, "%02X ", value))
                    ascii.append(if (value < 32 || value >= 127) '.' else value.toChar())
                    column++
                }
                sb.append("  ").append(ascii).append('\n')
                offset += 16
            }
            return sb.toString()
        }

        /** Hard ceiling on one inflated PDF stream. */
        private const val MAX_INFLATED = 8 shl 20

        private fun inflateOrRaw(bytes: ByteArray): String {
            val inflater = Inflater()
            try {
                inflater.setInput(bytes)
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var i = 0
                while (!inflater.finished() && i < 100000) {
                    val produced = inflater.inflate(buffer)
                    if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        break
                    }
                    out.write(buffer, 0, produced)
                    // Without this the loop could emit 100000 * 8192 = 800 MB
                    // from one crafted or corrupt stream. An OutOfMemoryError is
                    // an Error, not an Exception, so it sails straight past
                    // every catch on the way out and can take the process with
                    // it before the outermost Throwable handler sees it.
                    if (out.size() >= MAX_INFLATED) {
                        break
                    }
                    i++
                }
                if (out.size() > 0) {
                    return String(out.toByteArray(), StandardCharsets.ISO_8859_1)
                }
            } catch (e: Exception) {
            } finally {
                // Frees the native zlib buffer. Previously skipped on every
                // DataFormatException, so a PDF full of bad streams leaked one
                // native allocation per stream.
                try {
                    inflater.end()
                } catch (ignored: Exception) {
                }
            }
            return String(bytes, StandardCharsets.ISO_8859_1)
        }

        private val PDF_TEXT = Regex("\\(((?:\\\\.|[^()\\\\])*)\\)")

        private fun extractPdfText(chunk: String, sb: StringBuilder) {
            var any = false
            for (match in PDF_TEXT.findAll(chunk)) {
                val text = unescapePdf(match.groupValues[1])
                if (text.isNotEmpty()) {
                    sb.append(text)
                    any = true
                }
            }
            if (any) {
                sb.append('\n')
            }
        }

        private fun unescapePdf(value: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '\\' || i + 1 >= value.length) {
                    sb.append(c)
                } else {
                    var next = i + 1
                    val escaped = value[next]
                    when (escaped) {
                        '(' -> sb.append('(')
                        ')' -> sb.append(')')
                        '\\' -> sb.append('\\')
                        'b' -> sb.append('\b')
                        // Kotlin has no '\f' escape; U+000C is the form feed
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> {
                            if (escaped in '0'..'7') {
                                var octal = escaped - '0'
                                var digits = 0
                                while (digits < 2 && next + 1 < value.length &&
                                    value[next + 1] in '0'..'7'
                                ) {
                                    octal = (octal * 8) + (value[next + 1] - '0')
                                    digits++
                                    next++
                                }
                                sb.append(octal.toChar())
                            } else {
                                sb.append(escaped)
                            }
                        }
                    }
                    i = next
                }
                i++
            }
            return sb.toString()
        }
    }
}

/**
 * SHA-256 of a file as lowercase hex. Top-level (not a Tools member) so
 * regression tests can call it without constructing a Tools instance.
 */
internal fun sha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) {
                break
            }
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
