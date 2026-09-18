package github.vega.agent

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent skills, Claude Code style.
 *
 * A skill is a folder holding a `SKILL.md` file: YAML frontmatter with `name:`
 * and `description:`, followed by the markdown instructions an agent should
 * follow for that kind of task. Skills live in a `Vega Skills` directory —
 * either inside the folder the user picked in Settings ("Tools and access" →
 * "Add skill"), or in `<workspace>/Vega Skills` when no folder was picked.
 *
 * Two halves:
 *  * **Storage & discovery** ([skillsRoot], [list], [install], [delete],
 *    [parseSkillMarkdown]) — pure file logic, no network, no UI.
 *  * **GitHub import** ([parseGitHubUrl], [fetchRepo], [analysisPrompt],
 *    [parseAnalysisResult]) — reads a repo through the GitHub API plus
 *    raw.githubusercontent.com, then asks the user's own configured model to
 *    distill the agent skill(s) it contains into installable form.
 *
 * At runtime [promptBlock] renders the installed skills as a `# Agent skills`
 * section of the system prompt: name + one-line description per skill (that is
 * how the model *chooses*), with an instruction to `read_file` the SKILL.md of
 * whichever skill actually fits the task before starting that part of the work.
 * Descriptions in context, full bodies on demand — exactly how Claude Code does
 * it, so installing twenty skills does not cost twenty files of context.
 */
object Skills {

    /** The directory skills are installed into, created inside the user folder. */
    const val DIR_NAME = "Vega Skills"

    /** The skill definition file inside each skill folder. */
    const val SKILL_FILE = "SKILL.md"

    /** Sidecar written next to every installed SKILL.md: provenance metadata. */
    const val META_FILE = "vega-skill.json"

    /** A repo whose recursive tree lists more than this is still imported. */
    private const val MAX_TREE_PATHS = 20000

    /** At most this many SKILL.md files are downloaded from one repo. */
    private const val MAX_SKILL_FILES = 6

    /** Bytes kept per downloaded SKILL.md. */
    private const val MAX_SKILL_BYTES = 60000

    /** Bytes kept of a repo README used as analysis context. */
    private const val MAX_README_BYTES = 30000

    /** Bytes kept per GitHub API JSON response. */
    private const val MAX_API_BYTES = 4000000

    /** At most this many skills are installed from a single repo import. */
    private const val MAX_SKILLS_PER_REPO = 8

    /**
     * Hard field caps. Over-long values are REJECTED at install time
     * ([validateSkillFields], B12) and TRUNCATED in AI analysis output
     * ([parseAnalysisResult], B14) — the model gets a trim, the user gets an
     * error they can act on.
     */
    const val MAX_NAME_LEN = 80
    const val MAX_DESCRIPTION_LEN = 300
    const val MAX_INSTRUCTIONS_LEN = 200_000

    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val MAX_REDIRECTS = 4

    /**
     * Serializes every mutation of the skills tree (install / update /
     * enable / delete). Two installs racing on the same name used to
     * check-then-act the same target dir and delete each other's output (B11);
     * now the whole reserve-and-write sequence holds this lock, and directory
     * reservation itself is atomic (temp dir + rename).
     */
    private val ioLock = Any()

    // ---- data ------------------------------------------------------------

    /** One installed skill on disk. */
    data class Info(
        val name: String,
        val description: String,
        val dir: File,
        val sourceUrl: String,
        val installedAt: Long,
        /** B6: false hides the skill from the model without uninstalling it. */
        val enabled: Boolean = true,
        /** Claude Code `allowed-tools` frontmatter, parsed from SKILL.md. */
        val allowedTools: List<String> = emptyList(),
        /**
         * Set when install/update auto-fixed a non-kebab-case name via
         * [slugify]; empty otherwise. Lets the UI warn instead of silently
         * mangling the name the user/AI chose.
         */
        val renamedFrom: String = ""
    )

    /** A skill definition parsed from SKILL.md text or produced by AI analysis. */
    data class ParsedSkill(
        val name: String,
        val description: String,
        val instructions: String,
        val allowedTools: List<String> = emptyList()
    )

    /**
     * A `github.com/<owner>/<repo>` reference. [branch] is the `/tree/<branch>`
     * tail when the user pasted one — [fetchRepo] still analyzes the default
     * branch, so the UI warns that the tail was ignored (B15).
     */
    data class RepoRef(
        val owner: String,
        val repo: String,
        val branch: String? = null
    )

    /** Everything the AI analysis step needs about a repo. */
    data class RepoSnapshot(
        val ref: RepoRef,
        val branch: String,
        val repoDescription: String,
        val tree: List<String>,
        val truncated: Boolean,
        val readme: String,
        val skillFiles: Map<String, String>,
        /** B16: the file list hit GitHub's truncation or [MAX_TREE_PATHS]. */
        val treeCapped: Boolean = false,
        /** B16: more SKILL.md files existed than [MAX_SKILL_FILES]. */
        val skillsCapped: Boolean = false
    )

    // ---- storage ---------------------------------------------------------

    /**
     * The `Vega Skills` directory: inside the folder the user picked, or the
     * workspace when nothing was picked. Created on demand by [install].
     */
    fun skillsRoot(context: Context, prefs: Prefs): File {
        val picked = try {
            prefs.skillsFolder()
        } catch (ignored: Exception) {
            ""
        }
        val base = if (picked.isNotBlankJava()) File(picked) else Tools.externalRoot(context)
        return File(base, DIR_NAME)
    }

    /**
     * Every installed skill: each subfolder of the skills root that holds a
     * SKILL.md. Sorted by name, case-insensitively. Never throws — a broken
     * install shows as absent, not as a crash.
     */
    fun list(context: Context, prefs: Prefs): List<Info> {
        val root = skillsRoot(context, prefs)
        val dirs = try {
            root.listFiles()
        } catch (ignored: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<Info>()
        for (dir in dirs) {
            if (!dir.isDirectory) {
                continue
            }
            if (dir.name.startsWith(".")) {
                // Hidden bookkeeping dirs — stale `.install-*` staging left
                // behind by a killed install — are never skills.
                continue
            }
            val md = File(dir, SKILL_FILE)
            if (!md.isFile) {
                continue
            }
            try {
                val parsed = parseSkillMarkdown(md.readText(StandardCharsets.UTF_8))
                val meta = readMeta(dir)
                out.add(
                    Info(
                        parsed.name,
                        parsed.description,
                        dir,
                        meta.optString("source", ""),
                        meta.optLong("installed_at", 0L),
                        // B6: absent flag means a legacy install — enabled.
                        meta.optBoolean("enabled", true),
                        parsed.allowedTools
                    )
                )
            } catch (ignored: Exception) {
                // One corrupt skill must not hide the rest.
            }
        }
        out.sortWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })
        return out
    }

    private fun readMeta(dir: File): JSONObject {
        return try {
            JSONObject(File(dir, META_FILE).readText(StandardCharsets.UTF_8))
        } catch (ignored: Exception) {
            JSONObject()
        }
    }

    /**
     * B6: whether the skill is enabled. Reads the sidecar flag; a missing
     * flag (legacy installs) means enabled.
     */
    fun isEnabled(dir: File): Boolean = readMeta(dir).optBoolean("enabled", true)

    /**
     * B6: flips the sidecar flag, preserving every other metadata field. The
     * write goes through a temp file + rename so a torn write can never
     * corrupt the sidecar (a corrupt sidecar reads back as "enabled").
     */
    fun setEnabled(info: Info, enabled: Boolean) {
        synchronized(ioLock) {
            val meta = readMeta(info.dir).put("enabled", enabled)
            val tmp = File(info.dir, "$META_FILE.tmp")
            tmp.writeText(meta.toString(), StandardCharsets.UTF_8)
            if (!tmp.renameTo(File(info.dir, META_FILE))) {
                try {
                    tmp.delete()
                } catch (ignored: Exception) {
                }
                throw IOException("could not save $META_FILE")
            }
        }
    }

    /**
     * Validated skill fields. Throws [IllegalArgumentException] with a
     * human-readable reason (B12) — the install sheet shows the message, so
     * it must say what to fix, not just what broke.
     */
    data class ValidatedSkill(
        val name: String,
        val description: String,
        val instructions: String
    )

    @Throws(IllegalArgumentException::class)
    fun validateSkillFields(
        name: String,
        description: String,
        instructions: String
    ): ValidatedSkill {
        val cleanName = name.trimJava()
        if (cleanName.isEmpty()) {
            throw IllegalArgumentException("The skill needs a name.")
        }
        if (cleanName.length > MAX_NAME_LEN) {
            throw IllegalArgumentException(
                "The skill name is too long (maximum $MAX_NAME_LEN characters)."
            )
        }
        val cleanDescription = description.trimJava().replace("\n", " ").trimJava()
        if (cleanDescription.length > MAX_DESCRIPTION_LEN) {
            throw IllegalArgumentException(
                "The skill description is too long (maximum $MAX_DESCRIPTION_LEN characters)."
            )
        }
        val cleanInstructions = instructions.trimJava()
        if (cleanInstructions.isEmpty()) {
            throw IllegalArgumentException("The skill instructions are empty.")
        }
        if (cleanInstructions.length > MAX_INSTRUCTIONS_LEN) {
            throw IllegalArgumentException(
                "The skill instructions are too long (maximum $MAX_INSTRUCTIONS_LEN characters)."
            )
        }
        return ValidatedSkill(cleanName, cleanDescription, cleanInstructions)
    }

    /**
     * Agent Skills spec: the name is kebab-case — lowercase ASCII letters,
     * digits and hyphens — and the directory name matches it exactly.
     */
    fun isKebabCase(name: String): Boolean {
        if (name.isEmpty()) {
            return false
        }
        for (c in name) {
            if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) {
                return false
            }
        }
        return true
    }

    /**
     * Enforces the kebab-case rule on a validated name: already-conforming
     * names pass through untouched; anything else is auto-fixed with
     * [slugify] and the original is returned so the UI can WARN instead of
     * silently mangling it.
     *
     * @return pair of (effective name, original name or "" when unchanged).
     */
    private fun enforceKebabCase(name: String): Pair<String, String> {
        if (isKebabCase(name)) {
            return Pair(name, "")
        }
        val fixed = slugify(name)
        return if (fixed == name) Pair(name, "") else Pair(fixed, name)
    }

    private fun skillMarkdown(
        name: String,
        description: String,
        instructions: String,
        allowedTools: List<String>,
        sourceUrl: String
    ): String {
        val md = StringBuilder()
        md.append("---\n")
        md.append("name: ").append(name.replace("\n", " ").trimJava()).append('\n')
        if (description.isNotEmpty()) {
            md.append("description: ").append(description).append('\n')
        }
        if (allowedTools.isNotEmpty()) {
            md.append("allowed-tools: ").append(allowedTools.joinToString(", ")).append('\n')
        }
        if (sourceUrl.isNotBlankJava()) {
            md.append("source: ").append(sourceUrl).append('\n')
        }
        md.append("---\n\n")
        md.append(instructions).append('\n')
        return md.toString()
    }

    /**
     * Installs one skill: creates `<root>/<name>/` holding SKILL.md (with
     * frontmatter) plus a `vega-skill.json` sidecar recording where it came
     * from, when, and whether it is enabled. A name collision gets a `-2`,
     * `-3`, … suffix rather than overwriting the skill that is already there.
     *
     * Thread-safe (B11): the whole reserve-and-write sequence holds [ioLock],
     * and the directory is reserved atomically — files are written into a
     * hidden staging dir that is renamed into place, so two concurrent
     * same-name installs can never see or delete each other's output.
     *
     * @return the installed skill's [Info]; [Info.renamedFrom] is set when the
     * name was auto-fixed to kebab-case.
     */
    @Throws(Exception::class)
    fun install(
        context: Context,
        prefs: Prefs,
        name: String,
        description: String,
        instructions: String,
        sourceUrl: String,
        allowedTools: List<String> = emptyList()
    ): Info {
        // B12: validated BEFORE any I/O or Context use, so bad input fails
        // fast with a readable error and never leaves a half-made directory.
        val fields = validateSkillFields(name, description, instructions)
        val (finalName, renamedFrom) = enforceKebabCase(fields.name)
        synchronized(ioLock) {
            val root = skillsRoot(context, prefs)
            if (!root.isDirectory && !root.mkdirs()) {
                throw IOException("could not create " + root.absolutePath)
            }
            // B4: sweep staging dirs left behind by a crashed install. Races
            // with a live install are impossible under ioLock; anything older
            // than an hour belongs to a dead process. Staging dirs are hidden
            // (".install-*") so list() never shows them either way.
            val staleCutoff = System.currentTimeMillis() - 3_600_000L
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith(".install-") &&
                    f.lastModified() < staleCutoff
                ) {
                    try {
                        f.deleteRecursively()
                    } catch (ignored: Exception) {
                    }
                }
            }
            val cleanTools = allowedTools
                .map { it.trimJava() }
                .filter { it.isNotEmpty() }
                .distinct()
            val cleanSource = sourceUrl.trimJava()
            val staging = File(
                root,
                ".install-" + System.nanoTime() + "-" + Thread.currentThread().id
            )
            if (!staging.mkdirs()) {
                throw IOException("could not create " + staging.absolutePath)
            }
            try {
                // Atomic reservation: files are fully written into the hidden
                // staging dir, then the dir is renamed into the first free
                // name — WITH that name already baked into SKILL.md, so the
                // frontmatter `name` always matches the directory (Agent Skills
                // spec). A lost race just rewrites staging with the next
                // suffix and retries — nobody's output is ever clobbered.
                var attempt = finalName
                var n = 2
                var effective = ""
                while (true) {
                    File(staging, SKILL_FILE).writeText(
                        skillMarkdown(
                            attempt, fields.description, fields.instructions,
                            cleanTools, cleanSource
                        ),
                        StandardCharsets.UTF_8
                    )
                    val meta = JSONObject()
                        .put("name", attempt)
                        .put("description", fields.description)
                        .put("source", cleanSource)
                        .put("installed_at", System.currentTimeMillis())
                        .put("enabled", true)
                    File(staging, META_FILE).writeText(meta.toString(), StandardCharsets.UTF_8)

                    val target = File(root, attempt)
                    if (staging.renameTo(target)) {
                        effective = attempt
                        break
                    }
                    if (!target.exists()) {
                        throw IOException("could not create " + target.absolutePath)
                    }
                    attempt = finalName + "-" + n
                    n++
                }
                val now = System.currentTimeMillis()
                return Info(
                    effective, fields.description, File(root, effective),
                    cleanSource, now, true, cleanTools, renamedFrom
                )
            } catch (failed: Exception) {
                // Half-written installs are worse than no install.
                try {
                    staging.deleteRecursively()
                } catch (ignored: Exception) {
                }
                throw failed
            }
        }
    }

    /**
     * Rewrites an installed skill's SKILL.md + sidecar (B6 view/edit flow).
     * Both files are written to temp files and renamed over the originals, so
     * a crash mid-save can never leave a half-written skill. The directory
     * itself is NOT renamed — other references (prompt text, open sheets)
     * point at it.
     *
     * @return the updated [Info]; [Info.renamedFrom] is set when the name was
     * auto-fixed to kebab-case.
     */
    @Throws(Exception::class)
    fun update(
        info: Info,
        name: String,
        description: String,
        instructions: String,
        allowedTools: List<String> = emptyList()
    ): Info {
        val fields = validateSkillFields(name, description, instructions)
        val (finalName, renamedFrom) = enforceKebabCase(fields.name)
        synchronized(ioLock) {
            val dir = info.dir
            if (!dir.isDirectory) {
                throw IOException("skill folder is gone: " + dir.absolutePath)
            }
            val cleanTools = allowedTools
                .map { it.trimJava() }
                .filter { it.isNotEmpty() }
                .distinct()
            val mdTmp = File(dir, "$SKILL_FILE.tmp")
            mdTmp.writeText(
                skillMarkdown(
                    finalName, fields.description, fields.instructions,
                    cleanTools, info.sourceUrl
                ),
                StandardCharsets.UTF_8
            )
            if (!mdTmp.renameTo(File(dir, SKILL_FILE))) {
                try {
                    mdTmp.delete()
                } catch (ignored: Exception) {
                }
                throw IOException("could not save " + SKILL_FILE)
            }
            val oldMeta = readMeta(dir)
            val meta = oldMeta
                .put("name", finalName)
                .put("description", fields.description)
                .put("source", info.sourceUrl)
            if (!oldMeta.has("installed_at")) {
                meta.put("installed_at", System.currentTimeMillis())
            }
            if (!oldMeta.has("enabled")) {
                meta.put("enabled", true)
            }
            val metaTmp = File(dir, "$META_FILE.tmp")
            metaTmp.writeText(meta.toString(), StandardCharsets.UTF_8)
            if (!metaTmp.renameTo(File(dir, META_FILE))) {
                try {
                    metaTmp.delete()
                } catch (ignored: Exception) {
                }
                throw IOException("could not save $META_FILE")
            }
            return Info(
                finalName, fields.description, dir, info.sourceUrl,
                meta.optLong("installed_at", System.currentTimeMillis()),
                meta.optBoolean("enabled", true), cleanTools, renamedFrom
            )
        }
    }

    /**
     * Deletes an installed skill folder.
     *
     * @return null when the skill is gone afterwards, otherwise a
     * human-readable reason (B3) — the caller surfaces it instead of failing
     * silently.
     */
    fun delete(info: Info): String? {
        return synchronized(ioLock) {
            try {
                if (!info.dir.exists()) {
                    null
                } else if (info.dir.deleteRecursively()) {
                    null
                } else {
                    "could not delete " + info.dir.absolutePath
                }
            } catch (e: Exception) {
                val msg = e.message?.trimJava()
                if (msg.isNullOrEmpty()) e.javaClass.simpleName else msg
            }
        }
    }

    // ---- SKILL.md parsing --------------------------------------------------

    /**
     * Parses SKILL.md text in the Claude Code shape: optional `---` YAML
     * frontmatter with `name:` / `description:` / `allowed-tools:`, then the
     * markdown body. Falls back to the first `#` heading for the name and the
     * first plain paragraph for the description, so hand-written skill files
     * still work.
     */
    fun parseSkillMarkdown(text: String): ParsedSkill {
        val t = text.trimJava()
        var name = ""
        var description = ""
        var allowedTools = emptyList<String>()
        var body = t
        if (t.startsWith("---")) {
            val end = t.indexOf("\n---", 3)
            if (end > 0) {
                val front = t.substring(3, end)
                body = t.substring(end + 4).trimJava()
                for (rawLine in front.lines()) {
                    val line = rawLine.trimJava()
                    // B13: split on the FIRST colon so `name :` (space before
                    // the colon) parses exactly like `name:`.
                    val colon = line.indexOf(':')
                    if (colon <= 0) {
                        continue
                    }
                    val key = line.substring(0, colon).trimJava().lowercase(Locale.US)
                    val value = unquote(line.substring(colon + 1).trimJava())
                    when (key) {
                        "name" -> name = value
                        "description" -> description = value
                        "allowed-tools" -> allowedTools = parseAllowedTools(value)
                    }
                }
            }
        }
        val contentLines = body.lines()
            .map { it.trimJava() }
            .filter { it.isNotEmpty() && !it.startsWith("---") && !it.startsWith("```") }
        if (name.isEmpty()) {
            val titleLine = contentLines.firstOrNull { it.startsWith("#") }
            name = if (titleLine != null) {
                titleLine.trimStart('#').trimJava()
            } else {
                // No "# Title" line: the first body line becomes the name.
                contentLines.firstOrNull()?.take(60) ?: ""
            }
        }
        if (description.isEmpty()) {
            description = contentLines.firstOrNull {
                it != name && !it.startsWith("#")
            } ?: ""
        }
        if (name.isEmpty()) {
            name = "skill"
        }
        return ParsedSkill(name, description, body, allowedTools)
    }

    /**
     * Claude Code `allowed-tools` frontmatter: tool names separated by commas
     * and/or whitespace.
     */
    fun parseAllowedTools(value: String): List<String> {
        return value.split(',', ' ', '\t', '\n', '\r')
            .map { it.trimJava() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value[0]
            val last = value[value.length - 1]
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }

    /**
     * Filesystem-safe slug for a skill folder: lowercase ASCII, words joined
     * with hyphens, everything else dropped. Never empty.
     */
    fun slugify(name: String): String {
        val sb = StringBuilder()
        for (c in name.trimJava().lowercase(Locale.US)) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c == ' ' || c == '_' || c == '-' || c == '.' -> sb.append('-')
                // Anything else (including non-Latin scripts) is dropped: the
                // slug is a directory name, the display name keeps the original.
            }
        }
        var slug = sb.toString()
        while (slug.contains("--")) {
            slug = slug.replace("--", "-")
        }
        slug = slug.trim('-')
        return slug.ifEmpty { "skill" }
    }

    // ---- GitHub import -----------------------------------------------------

    /**
     * Parses what the user pasted into a `github.com/<owner>/<repo>`
     * reference. Tolerates a missing scheme, `www.`, a trailing `.git`,
     * `/tree/<branch>` tails and query strings. Null when it is not a GitHub
     * repo link at all.
     */
    fun parseGitHubUrl(raw: String?): RepoRef? {
        var s = (raw ?: "").trimJava()
        if (s.isEmpty()) {
            return null
        }
        if (!s.contains("://")) {
            s = "https://$s"
        }
        val uri = try {
            URI(s)
        } catch (ignored: Exception) {
            return null
        }
        var host = (uri.host ?: "").lowercase(Locale.US)
        if (host.startsWith("www.")) {
            host = host.substring(4)
        }
        if (host != "github.com") {
            return null
        }
        val segs = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
        if (segs.size < 2) {
            return null
        }
        val owner = segs[0]
        var repo = segs[1]
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length - 4)
        }
        if (!validRepoPart(owner) || !validRepoPart(repo)) {
            return null
        }
        // B15: a /tree/<branch> tail does NOT change what fetchRepo reads (it
        // uses the default branch), but the caller needs the branch name to
        // warn the user it was ignored.
        val branch = if (segs.size >= 4 && segs[2] == "tree") {
            val raw = segs.subList(3, segs.size).joinToString("/").trimJava()
            if (raw.isEmpty()) null else raw
        } else {
            null
        }
        return RepoRef(owner, repo, branch)
    }

    private fun validRepoPart(part: String): Boolean {
        if (part.isEmpty() || part.length > 100) {
            return false
        }
        for (c in part) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.'
            if (!ok) {
                return false
            }
        }
        return true
    }

    /**
     * Reads a repo for skill import: repo metadata (for the default branch),
     * the recursive file tree, up to [MAX_SKILL_FILES] SKILL.md files (shallow
     * first), and the root README as analysis context. Throws on network or
     * API failures; [CancellationToken] aborts between steps.
     */
    @Throws(Exception::class)
    fun fetchRepo(ref: RepoRef, token: CancellationToken): RepoSnapshot {
        token.throwIfCancelled()
        val apiBase = "https://api.github.com/repos/" + ref.owner + "/" + ref.repo
        val info = try {
            JSONObject(httpGetText(apiBase, token, MAX_API_BYTES))
        } catch (e: Exception) {
            throw IOException(Fa.SKILL_NET_FAIL + " (" + shortError(e) + ")")
        }
        val branch = info.optString("default_branch", "main").trimJava().ifEmpty { "main" }
        val repoDescription = info.optString("description", "").trimJava()

        token.throwIfCancelled()
        val treeJson = try {
            JSONObject(httpGetText("$apiBase/git/trees/$branch?recursive=1", token, MAX_API_BYTES))
        } catch (e: Exception) {
            throw IOException(Fa.SKILL_NET_FAIL + " (" + shortError(e) + ")")
        }
        val paths = ArrayList<String>()
        val tree = treeJson.optJSONArray("tree")
        if (tree != null) {
            for (i in 0 until tree.length()) {
                if (paths.size >= MAX_TREE_PATHS) {
                    break
                }
                val entry = tree.optJSONObject(i) ?: continue
                if (entry.optString("type") != "blob") {
                    continue
                }
                val path = entry.optString("path", "")
                if (path.isNotEmpty()) {
                    paths.add(path)
                }
            }
        }
        val truncated = treeJson.optBoolean("truncated", false)

        // SKILL.md candidates, shallow paths first — a repo root or
        // `skills/<name>/SKILL.md` layout beats a vendored copy ten deep.
        // B16: count before taking, so the UI can warn when some were skipped.
        val allSkillPaths = paths
            .filter { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
        val candidates = allSkillPaths
            .sortedBy { it.count { c -> c == '/' } }
            .take(MAX_SKILL_FILES)
        val skillsCapped = allSkillPaths.size > MAX_SKILL_FILES
        val skillFiles = LinkedHashMap<String, String>()
        for (path in candidates) {
            token.throwIfCancelled()
            try {
                skillFiles[path] = httpGetText(rawUrl(ref, branch, path), token, MAX_SKILL_BYTES)
            } catch (ignored: Exception) {
                // One unreadable file does not fail the import.
            }
        }

        var readme = ""
        val readmePath = paths.firstOrNull {
            !it.contains('/') && it.equals("README.md", ignoreCase = true)
        }
        if (readmePath != null) {
            try {
                readme = httpGetText(rawUrl(ref, branch, readmePath), token, MAX_README_BYTES)
            } catch (ignored: Exception) {
            }
        }
        return RepoSnapshot(
            ref, branch, repoDescription, paths, truncated, readme, skillFiles,
            treeCapped = truncated || paths.size >= MAX_TREE_PATHS,
            skillsCapped = skillsCapped
        )
    }

    private fun rawUrl(ref: RepoRef, branch: String, path: String): String =
        "https://raw.githubusercontent.com/" + ref.owner + "/" + ref.repo +
            "/" + branch + "/" + path

    private fun shortError(e: Exception): String {
        val message = e.message?.trimJava() ?: ""
        return when {
            message.isEmpty() -> e.javaClass.simpleName
            message.length <= 120 -> message
            else -> message.substring(0, 120) + "…"
        }
    }

    /**
     * Plain HTTPS GET with the GitHub API headers, manual redirect handling
     * (each hop re-validated by [NetworkPolicy.requireSafeHttps]), and a hard
     * byte cap. Throws on non-2xx, with rate-limit and not-found cases given
     * their real meaning instead of a bare status code.
     */
    @Throws(Exception::class)
    private fun httpGetText(url: String, token: CancellationToken, maxBytes: Int): String {
        token.throwIfCancelled()
        NetworkPolicy.requireSafeHttps(url)
        var current = url
        var redirects = 0
        while (true) {
            token.throwIfCancelled()
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "VegaAgent/1.1.0 (Android)")
                val watch = token.watchConnection(connection)
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlankJava() || redirects >= MAX_REDIRECTS) {
                            throw IOException("too many redirects")
                        }
                        redirects++
                        current = URL(URL(current), location).toString()
                        NetworkPolicy.requireSafeHttps(current)
                        continue
                    }
                    if (code == 404) {
                        throw IOException("not found (404)")
                    }
                    if (code == 403 || code == 429) {
                        val body = readCapped(connection.errorStream, 4000)
                        if (body.contains("rate limit", ignoreCase = true)) {
                            throw IOException("GitHub API rate limit reached — try again later")
                        }
                        throw IOException("access denied ($code)")
                    }
                    if (code < 200 || code >= 300) {
                        throw IOException("HTTP $code")
                    }
                    return readCapped(connection.inputStream, maxBytes)
                } finally {
                    watch.close()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readCapped(stream: java.io.InputStream?, maxBytes: Int): String {
        if (stream == null) {
            return ""
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) {
                    break
                }
                val room = maxBytes - total
                if (room <= 0) {
                    break
                }
                out.write(buffer, 0, Math.min(n, room))
                total += Math.min(n, room)
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                stream.close()
            } catch (ignored: Exception) {
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    // ---- AI analysis -------------------------------------------------------

    /**
     * The prompt sent to the user's own configured model: repo tree, README
     * and any SKILL.md files found, asking for a strict JSON list of the
     * agent skill(s) the repo contains.
     */
    fun analysisPrompt(snapshot: RepoSnapshot, farsi: Boolean): String {
        val sb = StringBuilder()
        sb.append("You are analyzing a GitHub repository to extract the AGENT SKILL it contains, ")
        sb.append("so an AI coding agent (like Claude Code) can install and use it.\n\n")
        sb.append("Repository: ").append(snapshot.ref.owner).append('/').append(snapshot.ref.repo)
        if (snapshot.repoDescription.isNotBlankJava()) {
            sb.append(" — ").append(snapshot.repoDescription)
        }
        sb.append("\n\n")
        if (snapshot.skillFiles.isNotEmpty()) {
            sb.append("The repo contains SKILL.md file(s). They are the source of truth — ")
            sb.append("base the skill on them faithfully, cleaning up formatting only:\n\n")
            for ((path, content) in snapshot.skillFiles) {
                sb.append("===== ").append(path).append(" =====\n")
                sb.append(Util.truncate(content, 20000)).append("\n\n")
            }
        } else {
            sb.append("The repo has no SKILL.md file. Study the file list and README below and decide ")
            sb.append("whether the repo documents a reusable AGENT SKILL: a workflow, set of instructions, ")
            sb.append("scripts or conventions an AI agent should follow for a class of tasks.\n\n")
        }
        sb.append("File list")
        if (snapshot.truncated) {
            sb.append(" (truncated by GitHub)")
        }
        sb.append(":\n")
        val showTree = snapshot.tree.take(400)
        for (path in showTree) {
            sb.append("- ").append(path).append('\n')
        }
        if (snapshot.tree.size > showTree.size) {
            sb.append("… (+").append(snapshot.tree.size - showTree.size).append(" more)\n")
        }
        if (snapshot.readme.isNotBlankJava()) {
            sb.append("\nREADME.md:\n").append(Util.truncate(snapshot.readme, 12000)).append('\n')
        }
        sb.append("\nRespond with EXACTLY ONE JSON object and nothing else — no markdown fences, no prose:\n")
        sb.append("{\"skills\": [{\"name\": \"short-hyphenated-name\", ")
        sb.append("\"description\": \"one line: what it does and when the agent should use it\", ")
        sb.append("\"allowed_tools\": \"read_file, search_files\", ")
        sb.append("\"instructions\": \"the full skill instructions in markdown, self-contained\"}]}\n")
        sb.append("Rules:\n")
        sb.append("- name: short kebab-case — lowercase letters, digits and hyphens only ")
        sb.append("(e.g. pdf-tools). The install folder is named after it, so they must match.\n")
        sb.append("- description: one line, ")
        sb.append(if (farsi) "in Persian" else "in English")
        sb.append(", SPECIFIC and ACTION-ORIENTED. This line is the ONLY thing the agent reads when ")
        sb.append("deciding whether to use the skill, so a vague description means the skill is never used. ")
        sb.append("Start with a verb saying what the skill DOES, then name the exact situations, file types ")
        sb.append("or user phrasings that should trigger it. ")
        sb.append("Example: \"Refactor Python code with rope: use when the user asks to rename, extract ")
        sb.append("or reorganize Python functions or classes.\"\n")
        sb.append("- allowed_tools (optional): comma-separated tool names the skill works best with ")
        sb.append("(e.g. read_file, search_files, web_fetch). Omit it when the skill is tool-agnostic.\n")
        sb.append("- instructions: complete and self-contained. With SKILL.md sources above, keep their substance. ")
        sb.append("Without them, distill the skill from the README/docs — never invent capabilities the repo does not document.\n")
        sb.append("- Several distinct skills → one object each. No usable agent skill → {\"skills\": []}.\n")
        sb.append("- Drop marketing fluff; keep everything the agent needs to actually follow the skill.\n")
        return sb.toString()
    }

    /**
     * Parses the model's analysis answer into skill definitions. Tolerates
     * markdown fences and surrounding prose; returns an empty list when the
     * model found no skill (or the answer is not parseable).
     *
     * B14: AI-returned fields are capped to the same limits as [install]
     * ([MAX_NAME_LEN] / [MAX_DESCRIPTION_LEN] / [MAX_INSTRUCTIONS_LEN]) —
     * trimmed, not rejected — and entries with a blank name or blank
     * instructions are dropped.
     */
    fun parseAnalysisResult(raw: String): List<ParsedSkill> {
        val json = extractJsonArray(raw) ?: return emptyList()
        val array = try {
            JSONArray(json)
        } catch (ignored: Exception) {
            return emptyList()
        }
        val out = ArrayList<ParsedSkill>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_SKILLS_PER_REPO) {
                break
            }
            val entry = array.optJSONObject(i) ?: continue
            val name = entry.optString("name", "").trimJava()
            val description = entry.optString("description", "").trimJava()
            val instructions = entry.optString("instructions", "").trimJava()
            if (name.isEmpty() || instructions.isEmpty()) {
                continue
            }
            out.add(
                ParsedSkill(
                    name.take(MAX_NAME_LEN),
                    description.take(MAX_DESCRIPTION_LEN),
                    instructions.take(MAX_INSTRUCTIONS_LEN),
                    parseAllowedTools(entry.optString("allowed_tools", ""))
                )
            )
        }
        return out
    }

    /**
     * Pulls the skills array out of the model's answer. Preferred shape is the
     * `{"skills": [...]}` envelope the prompt asks for; a bare `[...]` array
     * is tolerated too, because models do not always obey the envelope.
     * Markdown fences and surrounding prose are stripped first.
     */
    private fun extractJsonArray(raw: String): String? {
        var s = raw.trimJava()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            val lastFence = s.lastIndexOf("```")
            if (firstNl > 0 && lastFence > firstNl) {
                s = s.substring(firstNl + 1, lastFence).trimJava()
            }
        }
        val objStart = s.indexOf('{')
        if (objStart >= 0) {
            val objEnd = s.lastIndexOf('}')
            if (objEnd > objStart) {
                try {
                    val arr = JSONObject(s.substring(objStart, objEnd + 1))
                        .optJSONArray("skills")
                    if (arr != null) {
                        return arr.toString()
                    }
                } catch (ignored: Exception) {
                }
            }
        }
        val arrStart = s.indexOf('[')
        val arrEnd = s.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            return s.substring(arrStart, arrEnd + 1)
        }
        // Tolerated: a single bare {"name": ..., ...} object.
        val objOnly = s.trimJava()
        if (objOnly.startsWith("{") && objOnly.endsWith("}")) {
            try {
                if (JSONObject(objOnly).has("name")) {
                    return "[$objOnly]"
                }
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    // ---- runtime: what the model sees --------------------------------------

    /**
     * The workspace root's canonical path, or "" when it cannot be resolved.
     * Split out so both the prompt builder and the settings UI share the one
     * definition of "inside the workspace" (B7).
     */
    fun workspaceRootPath(context: Context): String {
        return try {
            Tools.externalRoot(context).canonicalPath
        } catch (ignored: Exception) {
            ""
        }
    }

    /**
     * Whether the agent's file tools can actually reach this skill's folder:
     * it must sit inside the workspace root (B7). Pure — unit-testable.
     */
    fun dirIsUsable(dir: File, workspaceRoot: String): Boolean {
        if (workspaceRoot.isEmpty()) {
            return false
        }
        return try {
            val dirPath = dir.canonicalPath
            dirPath == workspaceRoot || dirPath.startsWith(workspaceRoot + File.separator)
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * The `# Agent skills` block for the system prompt. Empty when nothing is
     * installed. Lists name + one-line description per skill — that is how the
     * model *chooses* — and tells it to `read_file` the SKILL.md of whichever
     * skill actually fits the task before starting that part of the work, and
     * to ignore the rest. Only enabled skills whose files sit inside the
     * workspace are listed: the agent's file tools cannot reach anything
     * outside it, and a disabled skill is installed but invisible (B6), so
     * advertising either would be a lie.
     */
    fun promptBlock(context: Context, prefs: Prefs): String =
        promptBlockFor(list(context, prefs), workspaceRootPath(context))

    /**
     * Pure core of [promptBlock] — no Context, so the enabled-flag filtering
     * and the workspace gate are unit-testable.
     *
     * Progressive disclosure, Claude Code style: name + description are always
     * in context (tier 1); the full SKILL.md body loads on demand via
     * read_file (tier 2); per-skill `allowed-tools` add a one-line preference
     * hint without bloating the block.
     */
    fun promptBlockFor(skills: List<Info>, workspaceRoot: String): String {
        if (skills.isEmpty()) {
            return ""
        }
        val usable = skills.filter { info ->
            info.enabled && dirIsUsable(info.dir, workspaceRoot)
        }
        if (usable.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        sb.append("# Agent skills (installed)\n")
        sb.append("The user installed these agent skills. Each skill is a folder holding a SKILL.md ")
        sb.append("file with instructions for a specific kind of task — the same concept as Claude Code skills.\n")
        for (info in usable) {
            sb.append("- ").append(info.name).append(": ")
            sb.append(if (info.description.isNotBlankJava()) info.description else "no description")
            if (info.allowedTools.isNotEmpty()) {
                sb.append(" When following this skill, prefer these tools: ")
                sb.append(info.allowedTools.joinToString(", "))
                sb.append('.')
            }
            sb.append("  [full instructions live in SKILL.md here: read_file \"")
            sb.append(info.dir.absolutePath).append('/').append(SKILL_FILE).append("\"]\n")
        }
        sb.append("How to choose the right skill:\n")
        sb.append("- Read each description as a WHEN clause: it names the exact kind of task the skill is for. ")
        sb.append("Use a skill ONLY when the user's current task falls inside that description.\n")
        sb.append("- Check EVERY task before you start it — including follow-ups inside a longer job, where the task type can change.\n")
        sb.append("- Never load a skill \"just in case\", by keyword coincidence, or because its name sounds vaguely related. ")
        sb.append("A wrong skill is worse than no skill.\n")
        sb.append("- When a skill IS the right fit, read its SKILL.md with read_file BEFORE you start that part of the work, ")
        sb.append("and follow its instructions for it.\n")
        sb.append("- If two skills could fit, read both SKILL.md files, then follow the better match.\n")
        sb.append("- If none fits, proceed without any skill — do not force one, and do not mention skills to the user unless a skill's own instructions say to.\n")
        sb.append("- A skill never overrides the user's direct instructions.\n\n")
        return sb.toString()
    }

    /**
     * Builds a one-shot chat message array for [LlmClient.completeOnce].
     */
    fun analysisMessages(prompt: String): JSONArray {
        return JSONArray()
            .put(JSONObject().put("role", "user").put("content", prompt))
    }
}
