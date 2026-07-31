package com.vepro.code

import org.json.JSONArray
import org.json.JSONObject

/**
 * The live "what the agent is doing right now" record for one user request.
 *
 * ### Why this exists
 *
 * A run used to be visible only as a transient one-line indicator plus whatever
 * prose each intermediate step happened to print — which meant the raw ```json
 * tool call was the most prominent thing on screen while the agent worked, and
 * the moment a step finished its evidence vanished. There was no answer to the
 * only question a waiting user actually has: *what is it doing, and how far in
 * is it?*
 *
 * A [Trail] is that answer, and it is deliberately a DATA structure rather than
 * a view: it is built by the engine, persisted with the chat, and re-rendered
 * from scratch whenever the transcript is rebuilt, so a rotation, a theme flip
 * or a reopened conversation shows exactly the same history of the run instead
 * of an empty strip.
 *
 * One trail spans the WHOLE request — every step of it — and is attached to the
 * first assistant message of the run. Intermediate steps fold into it and stop
 * being separate bubbles; when the final answer starts arriving the trail
 * collapses to a single "thought for N seconds" summary.
 */
class Trail {

    /**
     * Every activity line, oldest first.
     *
     * PRIVATE, and every path in or out of it is synchronized on this trail.
     *
     * The engine mutates a trail from its worker thread and the UI reads it on the
     * main thread, microseconds apart — a row is appended, the change is posted,
     * and the next append happens while the posted redraw is still running. An
     * unguarded ArrayList in that position is not a theoretical race: a concurrent
     * `removeAt(0)` (the 60-row cap) makes the UI's `for (i in from until size)`
     * index past the end, and an unsynchronized `add` that grows the backing array
     * can hand the reader a null element. The chat's own message list is
     * synchronized everywhere for exactly this reason; the trail now matches it.
     */
    private val stepList: MutableList<TrailStep> = mutableListOf()

    /**
     * The short human sentence describing the CURRENT phase, e.g. "Checking the
     * current dollar exchange rate". Taken from the model's own preamble when it
     * writes one, otherwise derived from the running tool, because a label the
     * model chose reads far better than one assembled from a tool name.
     *
     * `@Volatile` like everything else here, and it was the one field that was not.
     * The engine writes it from its worker thread (five sites: the opening phase,
     * both retry paths, the fault path, a step's own prose) and the strip and the
     * panel both read it on the main thread. Every other cross-thread scalar in
     * this file was already guarded; this one was reachable through the same race
     * and just happened not to have been caught by it yet.
     */
    @Volatile
    var phase: String = ""

    /** Uptime-independent wall clock start, for the elapsed-seconds counter. */
    @Volatile
    var startedAt: Long = 0L

    /** Set when the run finishes; 0 while it is still going. */
    @Volatile
    var endedAt: Long = 0L

    /** True once the answer began and the strip folded into its summary. */
    @Volatile
    var collapsed: Boolean = false

    /** True while the run is live, so the UI knows to animate. */
    @Volatile
    var running: Boolean = false

    /**
     * Every distinct source host touched this run, in the order first seen.
     *
     * Backs the "N pages" pill INSIDE the Thoughts panel — it used to sit loose in
     * the transcript under the strip, which is what made it look like a stray
     * fragment of the answer once the answer arrived. A [LinkedHashSet] because the
     * pill shows the first few favicons and the count of all of them, and both want
     * a stable order.
     */
    private val pageSet: MutableSet<String> = LinkedHashSet()

    /**
     * The rows, copied. The UI iterates the copy, so the engine may keep working
     * while a frame is being built.
     */
    fun steps(): List<TrailStep> = synchronized(this) { ArrayList(stepList) }

    /** Every source host touched this run, copied. */
    fun pages(): List<String> = synchronized(this) { ArrayList(pageSet) }

    /** Appends a row, enforcing the cap. */
    fun addStep(step: TrailStep, cap: Int) {
        synchronized(this) {
            stepList.add(step)
            // A long run would otherwise grow an unbounded list to lay out on
            // every redraw. The strip shows a window on the newest work; the
            // expanded view is a summary, not a log.
            while (stepList.size > cap) {
                stepList.removeAt(0)
            }
        }
    }

    /** Records source hosts against the run as a whole. */
    fun addPages(hosts: Collection<String>) {
        synchronized(this) {
            for (host in hosts) {
                if (host.isNotBlankJava()) {
                    pageSet.add(host)
                }
            }
        }
    }

    /** Total seconds the run took (or has taken so far). */
    fun elapsedMs(now: Long): Long {
        if (startedAt <= 0L) {
            return 0L
        }
        val end = if (endedAt > 0L) endedAt else now
        val span = end - startedAt
        return if (span < 0L) 0L else span
    }

    /** The step still in flight, if any. */
    fun active(): TrailStep? = synchronized(this) {
        for (i in stepList.indices.reversed()) {
            if (stepList[i].status == TrailStep.RUNNING) {
                return stepList[i]
            }
        }
        return null
    }

    /**
     * Closes every still-running step, so a cancelled run leaves no spinner.
     *
     * [interrupted] is the honest answer to "did this step finish?". It used to
     * always write DONE, which meant a run the user *stopped* mid-edit reloaded
     * looking like it had completed successfully — the single most misleading thing
     * a history can say, because the file on disk may be half written. A stopped
     * step is now marked [TrailStep.STOPPED] and says so.
     */
    fun settle(now: Long, interrupted: Boolean = false) {
        val outcome = if (interrupted) TrailStep.STOPPED else TrailStep.DONE
        synchronized(this) {
            for (step in stepList) {
                if (step.status == TrailStep.RUNNING) {
                    step.status = outcome
                    step.endedAt = now
                }
            }
        }
        running = false
        if (endedAt <= 0L) {
            endedAt = now
        }
    }

    /** True when there is genuinely nothing worth showing. */
    fun isEmpty(): Boolean =
        synchronized(this) { stepList.isEmpty() } && phase.isBlankJava()

    /**
     * True when the agent actually DID something — ran a tool, opened a page,
     * searched, delegated.
     *
     * The strip is a record of work, so a turn that was purely an answer (a
     * greeting, a question about something already on screen) must not carry one:
     * a "reviewed for 2 seconds" line over a one-line reply is noise pretending to
     * be progress. Thinking alone does not count, because every turn thinks.
     */
    fun didWork(): Boolean = synchronized(this) {
        for (step in stepList) {
            if (step.kind != TrailStep.THINK) {
                return true
            }
        }
        return false
    }

    /**
     * True when this trail actually HOLDS reasoning rows.
     *
     * The reasoning card is suppressed on the promise that the panel shows the same
     * text — so the promise has to be checked, not assumed. Two cases break it: a
     * turn that called no tool has a hidden strip and therefore no reachable panel,
     * and a conversation saved by an earlier build has a trail with no THINK rows in
     * it at all. In both, suppressing the card deleted reasoning the user had just
     * watched arrive, with no way to get it back.
     */
    fun hasThoughts(): Boolean = synchronized(this) {
        for (step in stepList) {
            if (step.kind == TrailStep.THINK && step.detail.isNotBlankJava()) {
                return true
            }
        }
        return false
    }

    /** Total tool steps, for the panel's subtitle. */
    fun workCount(): Int =
        synchronized(this) { stepList.count { it.kind != TrailStep.THINK } }

    /** Steps that ended badly, for the panel's subtitle. */
    fun failedCount(): Int = synchronized(this) {
        stepList.count { it.status == TrailStep.FAILED || it.status == TrailStep.STOPPED }
    }

    /** Reasoning rows, for the panel's subtitle. */
    fun thoughtCount(): Int =
        synchronized(this) { stepList.count { it.kind == TrailStep.THINK } }

    /** Total files this run changed, counted once each. */
    fun editedFiles(): List<String> = synchronized(this) {
        val out = ArrayList<String>()
        for (step in stepList) {
            val path = step.filePath
            if (path.isNotBlankJava() && !out.contains(path)) {
                out.add(path)
            }
        }
        return out
    }

    /** Added and removed line totals across every edit in the run. */
    fun changeTotals(): IntArray = synchronized(this) {
        var added = 0
        var removed = 0
        for (step in stepList) {
            added += step.added
            removed += step.removed
        }
        return intArrayOf(added, removed)
    }

    /**
     * The live reasoning row, if one is open.
     *
     * Reasoning used to reach the trail only at tool boundaries, in one lump, which
     * left the strip saying "Thinking about your request" and nothing else for as
     * long as the model reasoned — and put the actual words in a card OUTSIDE the
     * strip in the meantime. The engine now keeps one open THINK row and rewrites
     * it as tokens arrive, so the words appear where the work does.
     */
    fun openThought(): TrailStep? = synchronized(this) {
        for (i in stepList.indices.reversed()) {
            val step = stepList[i]
            if (step.kind == TrailStep.THINK) {
                return if (step.status == TrailStep.RUNNING) step else null
            }
        }
        return null
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("phase", phase)
        json.put("startedAt", startedAt)
        json.put("endedAt", endedAt)
        json.put("collapsed", collapsed)
        val list = JSONArray()
        for (step in steps()) {
            list.put(step.toJson())
        }
        json.put("steps", list)
        val hosts = JSONArray()
        for (host in pages()) {
            hosts.put(host)
        }
        json.put("pages", hosts)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject?): Trail? {
            if (json == null) {
                return null
            }
            val trail = Trail()
            trail.phase = json.optStr("phase", "")
            trail.startedAt = json.optLong("startedAt", 0L)
            trail.endedAt = json.optLong("endedAt", 0L)
            trail.collapsed = json.optBoolean("collapsed", true)
            // A persisted trail is never live: the process that ran it is gone.
            trail.running = false
            json.optJSONArray("steps")?.let { list ->
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { item ->
                        TrailStep.fromJson(item)?.let {
                            // Anything mid-flight when the app died must not spin
                            // for ever after a reload — and must not claim to have
                            // succeeded either. A step that was RUNNING when the
                            // process went away is exactly "we do not know", which
                            // is what STOPPED says.
                            if (it.status == TrailStep.RUNNING) {
                                it.status = TrailStep.STOPPED
                            }
                            trail.addStep(it, Int.MAX_VALUE)
                        }
                    }
                }
            }
            json.optJSONArray("pages")?.let { hosts ->
                val list = ArrayList<String>()
                for (i in 0 until hosts.length()) {
                    list.add(hosts.optStr(i, ""))
                }
                trail.addPages(list)
            }
            return trail
        }
    }
}

/**
 * One line of the trail: a search, a page open, a file tool, a delegated
 * sub-task. Carries just enough to render the row without consulting anything
 * else — label, the detail the user cares about (the query, the domain, the
 * path), how it ended, and the sources it produced.
 */
class TrailStep(val kind: String, val label: String, detail: String) {

    @Volatile
    var detail: String = detail

    @Volatile
    var status: Int = RUNNING

    /**
     * How many results a search returned, or -1 when the notion does not apply.
     * Drives the "5 results" chip.
     */
    @Volatile
    var resultCount: Int = -1

    /**
     * Source hosts this step produced, for the cluster of circles.
     *
     * Guarded for the same reason as [Trail.stepList]: written by the engine as a
     * tool finishes, read by the UI as it lays the row out.
     */
    private val domainList: MutableList<String> = mutableListOf()

    @Volatile
    var startedAt: Long = System.currentTimeMillis()

    @Volatile
    var endedAt: Long = 0L

    /**
     * The file this step changed, relative to the workspace, or empty.
     *
     * Kept apart from [detail] because [detail] is live progress text — it says
     * `src/Foo.kt · 3/7` while a multi-edit runs and is overwritten constantly —
     * whereas this is the durable fact the panel groups and labels by.
     */
    @Volatile
    var filePath: String = ""

    /** Lines added by this step, for the `+N` on its row. */
    @Volatile
    var added: Int = 0

    /** Lines removed by this step, for the `−N` on its row. */
    @Volatile
    var removed: Int = 0

    /**
     * The changed region of the file, before and after, narrowed by [Diff.hunk].
     *
     * Narrowed rather than whole because a trail is persisted with the
     * conversation: two full copies of every file the agent touched would be
     * megabytes of JSON per chat. It is also the better thing to show — the panel
     * opens on the lines that changed, not on the 900 above them.
     */
    @Volatile
    var diffBefore: String = ""

    @Volatile
    var diffAfter: String = ""

    /** True when the stored region is only part of the change. */
    @Volatile
    var diffClipped: Boolean = false

    /**
     * Why this step failed, in one short line.
     *
     * This field is the fix for the app's most-reported bug. A tool failure was
     * shown as a red "Failed" and a duration, and nothing else — the reason was
     * handed to the model in full and then dropped on the floor before it reached
     * any screen. So a user watching `edit_file` fail four times in a row could
     * see only that it had failed, four times, while the model silently read
     * "old_string not found" each time and guessed at a fix.
     *
     * Deliberately separate from [detail]. `detail` is live progress text — the
     * tool's own observer overwrites it with the file path and a `3/7` counter
     * while the work runs — so a reason written there is guaranteed to be
     * clobbered by the next progress tick. That is exactly what made the failed
     * row look like it was saying something useful when it was showing a path.
     *
     * One line. The full text lives in [output]; this is what fits on a row.
     */
    @Volatile
    var reason: String = ""

    /**
     * The tool's complete result text, for the sheet a failed row opens.
     *
     * Only kept when the step did not succeed. A successful tool's output can be
     * hundreds of kilobytes of file content and is already visible in the
     * conversation; a failed one's is a few hundred bytes of diagnosis that had
     * nowhere to live.
     */
    @Volatile
    var output: String = ""

    /** True when this step has a reason worth showing. */
    fun hasReason(): Boolean = reason.isNotBlankJava()

    /** How long this step took, or how long it has been running. */
    fun durationMs(now: Long): Long {
        if (startedAt <= 0L) {
            return 0L
        }
        val end = if (endedAt > 0L) endedAt else now
        val span = end - startedAt
        return if (span < 0L) 0L else span
    }

    /** True when this step has a file change worth opening. */
    fun hasDiff(): Boolean =
        diffAfter.isNotEmpty() || diffBefore.isNotEmpty()

    /** True when this step changed a measurable number of lines. */
    fun hasChangeCounts(): Boolean = added > 0 || removed > 0

    /** Records a file change on this step. */
    fun noteChange(path: String, hunk: Diff.Hunk) {
        filePath = path
        added = hunk.added
        removed = hunk.removed
        diffBefore = hunk.before
        diffAfter = hunk.after
        diffClipped = hunk.clipped
    }

    /**
     * The results this step produced, for the panel behind the row.
     *
     * Guarded like every other collection here: filled by the tool's own thread as
     * the search returns, read by the UI when the sheet opens.
     */
    private val resultList: MutableList<Web.SearchResult> = mutableListOf()

    /** The hosts, copied. */
    fun domains(): List<String> = synchronized(this) { ArrayList(domainList) }

    /** The results, copied. */
    fun results(): List<Web.SearchResult> = synchronized(this) { ArrayList(resultList) }

    fun addResults(items: List<Web.SearchResult>) {
        synchronized(this) {
            for (item in items) {
                if (resultList.none { it.url == item.url }) {
                    resultList.add(item)
                }
            }
        }
    }

    /** True when tapping this row has something to show. */
    fun hasResults(): Boolean = synchronized(this) { resultList.isNotEmpty() }

    fun addDomains(hosts: Collection<String>) {
        synchronized(this) {
            for (host in hosts) {
                if (host.isNotBlankJava() && !domainList.contains(host)) {
                    domainList.add(host)
                }
            }
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("kind", kind)
        json.put("label", label)
        json.put("detail", detail)
        json.put("status", status)
        json.put("resultCount", resultCount)
        json.put("startedAt", startedAt)
        json.put("endedAt", endedAt)
        if (filePath.isNotEmpty()) {
            json.put("filePath", filePath)
        }
        if (added != 0 || removed != 0) {
            json.put("added", added)
            json.put("removed", removed)
        }
        if (hasDiff()) {
            json.put("diffBefore", diffBefore)
            json.put("diffAfter", diffAfter)
            json.put("diffClipped", diffClipped)
        }
        // Reopening a chat has to reopen the diagnosis with it. A failure the user
        // scrolled past yesterday is exactly the one they come back to.
        if (reason.isNotEmpty()) {
            json.put("reason", reason)
        }
        if (output.isNotEmpty()) {
            json.put("output", output)
        }
        val hosts = JSONArray()
        for (host in domains()) {
            hosts.put(host)
        }
        json.put("domains", hosts)
        val found = JSONArray()
        for (item in results()) {
            found.put(item.toJson())
        }
        if (found.length() > 0) {
            json.put("results", found)
        }
        return json
    }

    companion object {
        const val RUNNING = 0
        const val DONE = 1
        const val FAILED = 2

        /**
         * Stopped before it finished — the user pressed Stop, or the process died.
         *
         * Distinct from [FAILED] because the two mean different things to whoever
         * reads the history back: FAILED is "the tool ran and said no", STOPPED is
         * "we do not know how far this got". Both used to be written as [DONE] on
         * settle and on reload, so a run killed halfway through an edit reopened
         * looking like a clean success.
         */
        const val STOPPED = 3

        /**
         * The user was asked and said no.
         *
         * Distinct from [FAILED], which it used to be folded into. Declining an
         * action is a decision the user made on purpose; showing it in the same
         * red "Failed" treatment as a tool that broke told them their own answer
         * was an error, and left them looking for a fault that was not there.
         */
        const val REJECTED = 4

        /** Row shapes. The UI picks an icon and a label style from these. */
        const val SEARCH = "search"
        const val FETCH = "fetch"
        const val TOOL = "tool"
        const val TASK = "task"

        /**
         * The model's own reasoning, in its own words, in run order.
         *
         * It used to live in a separate collapsible "reasoning" card, which split
         * one narrative in two: the panel said what the agent DID and the card said
         * what it was THINKING, with no way to line them up. As a row it sits
         * exactly where it happened — between the step it followed and the step it
         * led to.
         */
        const val THINK = "think"

        /**
         * The model's own narration — what it says it is about to do.
         *
         * Distinct from [THINK]: reasoning is the model working something out
         * privately, narration is it telling the user. "Now let me improve the
         * avatar with a better gradient" is not a thought, it is a sentence
         * addressed to somebody.
         *
         * It needs a row because it used to have nowhere to be. A turn that wrote
         * prose and then called a tool had its prose promoted to the trail's single
         * `phase` line, where the next step immediately overwrote it; a turn that
         * wrote prose and called nothing kept it as a chat bubble. So the same kind
         * of sentence landed in one of two completely different places depending on
         * whether a tool call happened to follow it in the same message — and the
         * running commentary of a long job ended up interleaved with the answer
         * instead of inside the review section that exists to hold it.
         */
        const val NOTE = "note"

        fun fromJson(json: JSONObject?): TrailStep? {
            if (json == null) {
                return null
            }
            val kind = json.optStr("kind", TOOL)
            val step = TrailStep(kind, json.optStr("label", ""), json.optStr("detail", ""))
            step.status = json.optInt("status", DONE)
            step.resultCount = json.optInt("resultCount", -1)
            step.startedAt = json.optLong("startedAt", 0L)
            step.endedAt = json.optLong("endedAt", 0L)
            step.filePath = json.optStr("filePath", "")
            step.added = json.optInt("added", 0)
            step.removed = json.optInt("removed", 0)
            step.reason = json.optStr("reason", "")
            step.output = json.optStr("output", "")
            step.diffBefore = json.optStr("diffBefore", "")
            step.diffAfter = json.optStr("diffAfter", "")
            step.diffClipped = json.optBoolean("diffClipped", false)
            json.optJSONArray("domains")?.let { hosts ->
                val list = ArrayList<String>()
                for (i in 0 until hosts.length()) {
                    list.add(hosts.optStr(i, ""))
                }
                step.addDomains(list)
            }
            json.optJSONArray("results")?.let { found ->
                val list = ArrayList<Web.SearchResult>()
                for (i in 0 until found.length()) {
                    Web.SearchResult.fromJson(found.optJSONObject(i))?.let { list.add(it) }
                }
                step.addResults(list)
            }
            return step
        }
    }
}

/**
 * The Dynamic Workflow board: the decomposition of a request into phases, each
 * handed to its own focused sub-agent.
 *
 * Dynamic Workflow already delegated real work to real sub-agents, but every
 * trace of it was invisible — the user switched on a mode that promised the job
 * would be broken up and then watched the same opaque single-line indicator as
 * before. The board is the missing half: it names each phase, shows which
 * sub-agent is on it right now, and keeps the report each one handed back.
 */
class Workflow {

    /**
     * The phases. Private and synchronized for the same reason as
     * [Trail.stepList]: [claim] appends from the engine's worker thread while the
     * board is being laid out on the main one.
     */
    private val phaseList: MutableList<WorkPhase> = mutableListOf()

    /** True while the run owning this board is live. */
    @Volatile
    var running: Boolean = false

    /** The phases, copied for safe iteration. */
    fun phases(): List<WorkPhase> = synchronized(this) { ArrayList(phaseList) }

    fun size(): Int = synchronized(this) { phaseList.size }

    fun add(phase: WorkPhase) {
        synchronized(this) { phaseList.add(phase) }
    }

    /** Sub-agents that have actually been launched on this board. */
    @Volatile
    var launched: Int = 0
        private set

    /** How many sub-agents the run may keep in flight at once. */
    @Volatile
    var parallel: Int = 1

    /** Index of the phase currently being worked, or -1. */
    fun activeIndex(): Int = synchronized(this) {
        for (i in phaseList.indices) {
            if (phaseList[i].status == WorkPhase.RUNNING) {
                return i
            }
        }
        return -1
    }

    fun doneCount(): Int =
        synchronized(this) { phaseList.count { it.status == WorkPhase.DONE } }

    /**
     * Sub-agents working RIGHT NOW.
     *
     * The board used to say "Split across N sub-agents" where N was the number of
     * bulleted lines the model happened to type — so it read "Split across 7
     * sub-agents" after a single delegation, and it said it before any agent had
     * even started. This counts running phases, which is a count of agents,
     * because a phase only reaches RUNNING when a real sub-agent was dispatched
     * onto it.
     */
    fun liveCount(): Int =
        synchronized(this) { phaseList.count { it.status == WorkPhase.RUNNING } }

    /** The topics of the agents working right now, for the board's subheading. */
    fun liveTopics(): List<String> = synchronized(this) {
        phaseList.filter { it.status == WorkPhase.RUNNING }
            .map { if (it.topic.isNotBlankJava()) it.topic else it.title }
    }

    /** Seeds PENDING rows from the lead agent's own plan. */
    fun seed(titles: List<String>) {
        synchronized(this) {
            for (title in titles) {
                if (title.isNotBlankJava()) {
                    phaseList.add(WorkPhase(phaseList.size + 1, title))
                }
            }
        }
    }

    /**
     * Binds a real delegation to the phase the LEAD AGENT NAMED, or to a new row.
     *
     * This is where the board stopped guessing. It used to score the `task` call's
     * name against every unstarted plan line by counting shared words, and — when
     * nothing scored two — hand the work to whichever row happened to be first
     * unstarted. So the board could confidently attribute a sub-agent's work to a
     * plan line that had nothing to do with it, and nothing anywhere recorded the
     * true mapping. It was a picture of what the model SAID it would do, refreshed
     * by guesswork.
     *
     * The `task` tool now takes an explicit `phase` number, which the model can
     * always supply because it wrote the numbered plan itself. When it does,
     * [planIndex] is that number and the binding is a fact. When it does not, a
     * new row is added rather than an existing one being claimed on a hunch: an
     * extra row is honest, a mis-attributed row is not.
     *
     * [agentId] and [topic] are what let the card say "3 agents working on: X, Y,
     * Z" — the board records agents now, not just phases.
     */
    fun launch(planIndex: Int, topic: String, fallbackTitle: String): WorkPhase =
        synchronized(this) {
            launched++
            val agentId = launched
            val named = if (planIndex >= 1) {
                phaseList.firstOrNull { it.index == planIndex && it.status == WorkPhase.PENDING }
            } else {
                null
            }
            val phase = named ?: WorkPhase(
                phaseList.size + 1,
                if (topic.isBlankJava()) fallbackTitle else topic
            ).also { phaseList.add(it) }
            phase.agentId = agentId
            phase.topic = if (topic.isBlankJava()) fallbackTitle else topic
            phase.startedAt = System.currentTimeMillis()
            phase.status = WorkPhase.RUNNING
            return phase
        }

    /**
     * Kept for the callers that have only a name to go on.
     *
     * Matching by shared words is still the best available answer when the model
     * omitted the phase number, but the blind fall-through is gone: below the
     * two-word confidence bar this now adds a row instead of claiming one.
     */
    fun claim(name: String, fallbackTitle: String): WorkPhase = synchronized(this) {
        val needle = normalise(name)
        if (needle.isNotEmpty()) {
            var best: WorkPhase? = null
            var bestScore = 0
            for (phase in phaseList) {
                if (phase.status != WorkPhase.PENDING) {
                    continue
                }
                val score = overlap(needle, normalise(phase.title))
                if (score > bestScore) {
                    bestScore = score
                    best = phase
                }
            }
            // Two shared words is a real match; one is a coincidence ("the").
            if (best != null && bestScore >= 2) {
                return best
            }
        }
        // The title must not be blank — an unnamed `task` call used to add a phase
        // with an empty title, which rendered as a blank row and was then thrown
        // away by [WorkPhase.fromJson] on reload, taking the whole board with it
        // when it was the only phase.
        val title = if (name.isBlankJava()) fallbackTitle else name
        val extra = WorkPhase(phaseList.size + 1, title)
        phaseList.add(extra)
        return extra
    }

    /**
     * Ends the board's live state.
     *
     * [interrupted] is passed through for the same reason as [Trail.settle]: a
     * phase that was running when the user pressed Stop did not finish, and
     * recording it as DONE is a lie the history then repeats for ever.
     */
    fun settle(interrupted: Boolean = false) {
        running = false
        val outcome = if (interrupted) WorkPhase.STOPPED else WorkPhase.DONE
        val now = System.currentTimeMillis()
        synchronized(this) {
            for (phase in phaseList) {
                if (phase.status == WorkPhase.RUNNING) {
                    phase.status = outcome
                }
                // Backfill an end time for anything that STARTED and has none,
                // not only for what was still running.
                //
                // The launcher stamps `endedAt` when it closes a row, and that is
                // the path every real delegation takes — but it is not the only
                // path a status can change by, and a row that reports a duration of
                // zero because nobody stamped it is worse than one that reports
                // slightly too long. Settle is the last chance to make the board
                // internally consistent before it is persisted.
                if (phase.startedAt > 0L && phase.endedAt == 0L) {
                    phase.endedAt = now
                }
            }
        }
    }

    /**
     * Fails every phase still open, with one reason.
     *
     * For the abnormal exits — a step that threw, the mode being switched off
     * mid-run — where the launcher never got to close its own rows. A row left
     * RUNNING is worse than a row marked failed: the next tool to finish used to
     * resolve it, so an unrelated `read_file` would mark a delegated phase DONE
     * and write its own first line into that phase's note.
     */
    fun failOpen(reason: String) {
        synchronized(this) {
            for (phase in phaseList) {
                if (phase.status == WorkPhase.RUNNING) {
                    phase.status = WorkPhase.FAILED
                    phase.note = reason
                    if (phase.endedAt == 0L) {
                        phase.endedAt = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    /** Phases not started yet, so the board can say how much is still queued. */
    fun pendingCount(): Int =
        synchronized(this) { phaseList.count { it.status == WorkPhase.PENDING } }

    /** Phases that ended badly. */
    fun failedCount(): Int = synchronized(this) {
        phaseList.count { it.status == WorkPhase.FAILED || it.status == WorkPhase.STOPPED }
    }

    /** Total tool steps taken by every sub-agent on this board. */
    fun stepTotal(): Int = synchronized(this) { phaseList.sumOf { it.steps } }

    fun toJson(): JSONObject {
        val json = JSONObject()
        val list = JSONArray()
        for (phase in phases()) {
            list.put(phase.toJson())
        }
        json.put("phases", list)
        json.put("launched", launched)
        json.put("parallel", parallel)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject?): Workflow? {
            if (json == null) {
                return null
            }
            val workflow = Workflow()
            workflow.launched = json.optInt("launched", 0)
            workflow.parallel = json.optInt("parallel", 1)
            json.optJSONArray("phases")?.let { list ->
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { item ->
                        WorkPhase.fromJson(item)?.let {
                            // Same rule as a trail step: a phase still RUNNING in
                            // the saved copy means the process died on it, not that
                            // its sub-agent quietly succeeded.
                            if (it.status == WorkPhase.RUNNING) {
                                it.status = WorkPhase.STOPPED
                            }
                            workflow.add(it)
                        }
                    }
                }
            }
            return if (workflow.size() == 0) null else workflow
        }

        /**
         * Case-folded, and with the punctuation a plan line collects stripped.
         *
         * Lowercasing alone was not enough. Plan lines arrive as `"۱. بررسی فایل‌ها:"`
         * or `"1) Audit the sources —"`, and a `task` name arrives bare, so the
         * numbering, the trailing colon and the Persian zero-width non-joiner all
         * counted as part of the words and no two tokens ever matched. Stripping
         * them is what lets the board line up with a plan written in either script.
         */
        private fun normalise(text: String): String {
            val lower = text.lowercase(java.util.Locale.US)
            val sb = StringBuilder(lower.length)
            for (c in lower) {
                when {
                    // ZWNJ and the Arabic tatweel carry no meaning for matching.
                    c == '‌' || c == 'ـ' -> sb.append(' ')
                    Character.isLetterOrDigit(c) -> sb.append(c)
                    else -> sb.append(' ')
                }
            }
            return sb.toString()
        }

        /**
         * Number of shared words worth counting.
         *
         * Three characters is the floor in Latin script, where short words are
         * mostly function words. Persian and Arabic pack far more meaning into three
         * letters — `فایل`, `متن`, `کد` are all content words — so the floor drops to
         * two once a token is non-Latin. Without that, a Persian plan scored zero
         * against every task name and the board silently fell back to filling rows
         * in call order.
         */
        private fun overlap(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) {
                return 0
            }
            val words = HashSet<String>()
            for (word in a.split(' ')) {
                if (worthMatching(word)) {
                    words.add(word)
                }
            }
            var shared = 0
            val seen = HashSet<String>()
            for (word in b.split(' ')) {
                if (worthMatching(word) && words.contains(word) && seen.add(word)) {
                    shared++
                }
            }
            return shared
        }

        private fun worthMatching(word: String): Boolean {
            if (word.length > 3) {
                return true
            }
            if (word.length < 2) {
                return false
            }
            // Non-ASCII at this point means a script whose words are shorter.
            for (c in word) {
                if (c.code > 0x7F) {
                    return true
                }
            }
            return false
        }
    }
}

/** One phase of a [Workflow]. */
class WorkPhase(val index: Int, val title: String) {

    @Volatile
    var status: Int = PENDING

    /** One-line outcome once the sub-agent reports back. */
    @Volatile
    var note: String = ""

    /** How many tool steps the sub-agent took, for the "N steps" hint. */
    @Volatile
    var steps: Int = 0

    /**
     * Which sub-agent is on this phase, or 0 before one has been dispatched.
     *
     * The board is a record of agents now, not only of planned phases. A row with
     * an id had a real sub-agent on it; a row without one is still just something
     * the lead said it intended to do. Keeping them distinguishable is what stops
     * the card counting intentions as workers, which is exactly what the old
     * "Split across N sub-agents" line did.
     */
    @Volatile
    var agentId: Int = 0

    /**
     * What this agent was actually asked to do, from its brief.
     *
     * Distinct from [title]: the title is the plan line the user read, the topic
     * is the subject of the brief the sub-agent received. They are usually close
     * and occasionally not, and when they differ the topic is the truthful one.
     */
    @Volatile
    var topic: String = ""

    @Volatile
    var startedAt: Long = 0L

    @Volatile
    var endedAt: Long = 0L

    /** How long this phase took, or has been running. */
    fun durationMs(now: Long): Long {
        if (startedAt <= 0L) {
            return 0L
        }
        val end = if (endedAt > 0L) endedAt else now
        val span = end - startedAt
        return if (span < 0L) 0L else span
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("index", index)
        json.put("title", title)
        json.put("status", status)
        json.put("note", note)
        json.put("steps", steps)
        json.put("agentId", agentId)
        json.put("topic", topic)
        json.put("startedAt", startedAt)
        json.put("endedAt", endedAt)
        return json
    }

    companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val DONE = 2
        const val FAILED = 3

        /** Delegated, then stopped before the sub-agent reported back. */
        const val STOPPED = 4

        fun fromJson(json: JSONObject?): WorkPhase? {
            if (json == null) {
                return null
            }
            val title = json.optStr("title", "")
            if (title.isBlankJava()) {
                return null
            }
            val phase = WorkPhase(json.optInt("index", 1), title)
            phase.status = json.optInt("status", DONE)
            phase.note = json.optStr("note", "")
            phase.steps = json.optInt("steps", 0)
            phase.agentId = json.optInt("agentId", 0)
            phase.topic = json.optStr("topic", "")
            phase.startedAt = json.optLong("startedAt", 0L)
            phase.endedAt = json.optLong("endedAt", 0L)
            return phase
        }
    }
}
