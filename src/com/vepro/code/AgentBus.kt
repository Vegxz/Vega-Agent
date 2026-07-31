package com.vepro.code

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Global run coordinator shared by the UI and the foreground service.
 *
 * Exactly one agent run may be active at a time; the run slot is claimed
 * before `startForegroundService` so a second tap cannot start a duplicate.
 * All state transitions happen under [RUN_LOCK]; the fields are `@Volatile`
 * so readers outside the lock still observe fresh values.
 */
object AgentBus {

    enum class RunState {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    private val RUN_LOCK = Any()
    private var nextRunId = 1L

    /**
     * Process-scoped main-thread handler for callbacks that must outlive any one
     * Activity — chiefly the "did the service actually start?" watchdog, which
     * releases the global run slot. An Activity's own handler is cleared in
     * onDestroy, and losing that callback wedged the app as permanently busy.
     *
     * Created lazily: this object is also loaded by the off-device test suite,
     * where the Looper stub throws, and a handler in the initializer would take
     * the whole class down with it.
     */
    val watchdog: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var activeRunId = 0L

    @Volatile
    private var cancellation = CancellationToken()

    @Volatile
    private var runState = RunState.IDLE

    @Volatile
    var activeChatId: String? = null

    @Volatile
    var listener: UiListener? = null

    @Volatile
    var liveChat: Chat? = null

    /**
     * Every approval still waiting for an answer, oldest first. Guarded by
     * [RUN_LOCK] — see [awaitApproval] for why this is a queue and not one field.
     *
     * Small by construction: one entry per live agent thread, and the run caps
     * those at the lead plus its sub-agents. An ArrayList beats a deque here
     * because the hot operations are "read the head" and "remove by identity".
     */
    private val approvals = ArrayList<PendingApproval>()

    private var nextApprovalId = 1L

    /**
     * Label applied to approvals raised on THIS thread, so a queued request can
     * name who asked ("Agent 2 wants to edit …").
     *
     * Thread-scoped rather than a parameter because the ask travels through
     * `AgentEngine.Callback.requestApproval(tool, args)`, which has no room for
     * an identity and is implemented in files this change does not touch. A
     * sub-agent worker sets it once when it starts and clears it when it ends;
     * everything raised in between is attributed automatically. The explicit
     * parameter on [awaitApproval] still wins when a caller has the label to
     * hand.
     */
    private val threadAgentLabel = ThreadLocal<String>()

    /**
     * True when the current/most-recent cancel was initiated by the USER (stop
     * button / notification action); false when the run was cut by the system
     * (service killed, start watchdog). Lets the UI offer a "continue" card only
     * for system interruptions. Reset at the start of each run.
     */
    @Volatile
    var userStopped = false

    /**
     * Hard ceiling on how long an agent thread will sit on an approval.
     *
     * This is a backstop, not a policy: a person genuinely may take minutes to
     * read a diff and decide. It exists only so that a DROPPED decision — the
     * Activity destroyed between showing the sheet and delivering the tap, a
     * listener swapped mid-flight, a sheet dismissed by a window-manager error —
     * cannot pin an agent thread, and through it the global run slot, forever.
     */
    const val APPROVAL_TIMEOUT_MS = 10L * 60L * 1000L

    /** Outcome text for a granted request. English: it is read by the model. */
    const val REASON_APPROVED = "approved by the user"

    /** Outcome text for a request the user actively rejected. */
    const val REASON_DECLINED = "declined by the user"

    /** Outcome text when the run was cancelled while the ask was outstanding. */
    const val REASON_CANCELLED = "the run was cancelled before the user answered"

    /** Outcome text when the user pressed stop while the ask was outstanding. */
    const val REASON_STOPPED = "the user stopped the run before answering"

    /** Outcome text when the run ended while the ask was still queued. */
    const val REASON_RUN_ENDED = "the run ended before the user answered"

    /** Outcome text when no answer arrived inside [APPROVAL_TIMEOUT_MS]. */
    const val REASON_TIMED_OUT =
        "no answer arrived within the approval timeout, so it was treated as declined"

    interface UiListener {
        fun onApprovalRequested(approval: PendingApproval)
        fun onComplete()
        fun onDelta(message: Message)
        fun onError(error: String)
        fun onNewAssistantMessage(message: Message)
        fun onStepFinalized(message: Message)
        fun onThinking(message: Message)
        fun onToolMessage(message: Message)
        fun onToolRunning(tool: String, detail: String)

        /** The run's activity strip changed; re-read [Message.trail]. */
        fun onTrailChanged(owner: Message)

        /**
         * How many approvals are still outstanding, including the one on screen.
         *
         * Optional: a screen that shows one sheet at a time and nothing else can
         * ignore it, which is why it has a body. A concurrent workflow uses it to
         * say "2 more waiting" on the sheet the user is looking at.
         */
        fun onApprovalQueueChanged(outstanding: Int) {
        }
    }

    /**
     * A tool call blocked on the user's approval.
     *
     * Each instance owns its own latch, so several may be outstanding at once and
     * each waiter is released by ITS decision and nothing else. [id] and [agent]
     * exist so the sheet can label the ask — with three sub-agents running, "edit
     * a file" without a name is not a question anyone can answer.
     */
    class PendingApproval internal constructor(
        val id: Long,
        val agent: String,
        val tool: String,
        val args: JSONObject?
    ) {
        internal val latch = CountDownLatch(1)

        @Volatile
        internal var approved = false

        /**
         * Why this resolved the way it did, in English — it is reported back into
         * the model's conversation, not shown as interface prose.
         */
        @Volatile
        internal var reason = ""

        @Volatile
        var decided = false
            private set

        fun decide(approved: Boolean) {
            decide(approved, if (approved) REASON_APPROVED else REASON_DECLINED)
        }

        /**
         * Resolves this approval exactly once and hands the queue back to the UI.
         *
         * The state change is done under this object's own monitor; retiring from
         * the queue happens AFTER that monitor is released, because retiring takes
         * [RUN_LOCK] and the cancel paths take [RUN_LOCK] first and then decide.
         * Doing both in one order everywhere is what keeps the two locks acyclic.
         */
        internal fun decide(approved: Boolean, why: String) {
            val changed = synchronized(this) {
                if (decided) {
                    false
                } else {
                    this.approved = approved
                    this.reason = why
                    decided = true
                    latch.countDown()
                    true
                }
            }
            if (changed) {
                retire(this)
            }
        }
    }

    /** The answer to one approval: whether it was granted, and why. */
    class Decision internal constructor(
        val approved: Boolean,
        val reason: String
    )

    /** Claims the global run slot before startForegroundService is called. */
    fun beginStarting(chatId: String?, chat: Chat?): Long {
        return synchronized(RUN_LOCK) {
            if (runState != RunState.IDLE) {
                0L
            } else {
                activeRunId = nextRunId++
                activeChatId = chatId
                liveChat = chat
                cancellation = CancellationToken()
                runState = RunState.STARTING
                userStopped = false
                activeRunId
            }
        }
    }

    fun markRunning(runId: Long): Boolean {
        return synchronized(RUN_LOCK) {
            if (runId != activeRunId || runState == RunState.IDLE) {
                false
            } else {
                if (runState != RunState.STOPPING) {
                    runState = RunState.RUNNING
                }
                true
            }
        }
    }

    fun isBusy(): Boolean = runState != RunState.IDLE

    fun isStopping(): Boolean = runState == RunState.STOPPING

    fun isRunningFor(chatId: String?): Boolean =
        isBusy() && chatId != null && chatId == activeChatId

    fun state(): RunState = runState

    fun runId(): Long = activeRunId

    fun token(): CancellationToken = cancellation

    /**
     * Names the agent behind every approval raised on the calling thread.
     *
     * Call with a label when a sub-agent worker starts and with null when it
     * finishes; the value is thread-scoped, so concurrent workers cannot see each
     * other's. Passing null (or "") restores the unlabelled lead-agent case.
     */
    fun setAgentLabel(label: String?) {
        if (label.isNullOrEmpty()) {
            threadAgentLabel.remove()
        } else {
            threadAgentLabel.set(label)
        }
    }

    /** The approval the UI should be showing, or null when nothing is waiting. */
    fun currentApproval(): PendingApproval? {
        return synchronized(RUN_LOCK) { headLocked() }
    }

    /** How many approvals are outstanding, including the one on screen. */
    fun outstandingApprovals(): Int {
        return synchronized(RUN_LOCK) { approvals.count { !it.decided } }
    }

    /** Idempotent and effective even while the foreground service is still starting. */
    fun requestCancel(): Boolean = requestCancel(false)

    /**
     * @param byUser true when the user pressed stop (suppresses the "continue"
     * card); false when the system cut the run (offers "continue").
     */
    fun requestCancel(byUser: Boolean): Boolean {
        val orphaned = synchronized(RUN_LOCK) {
            if (runState == RunState.IDLE) {
                return false
            }
            if (byUser) {
                userStopped = true
            }
            runState = RunState.STOPPING
            drainLocked()
        }
        cancellation.cancel()
        // EVERY outstanding approval, not just the head. Rejecting only the one
        // on screen is what left the other agent threads parked on their latches.
        rejectAll(orphaned, if (byUser) REASON_STOPPED else REASON_CANCELLED)
        return true
    }

    /** Only the owner of this run may return the global state to IDLE. */
    fun finish(runId: Long) {
        val orphaned = synchronized(RUN_LOCK) {
            if (runId != activeRunId) {
                return
            }
            val pending = drainLocked()
            runState = RunState.IDLE
            activeRunId = 0L
            activeChatId = null
            // Without this the finished conversation — every message, every
            // attachment and every base64 data URI in it — stayed strongly
            // reachable from a static field for the rest of the process's life,
            // even after the user switched to a different chat.
            liveChat = null
            cancellation = CancellationToken()
            pending
        }
        rejectAll(orphaned, REASON_RUN_ENDED)
    }

    /**
     * Blocks the agent thread until the user approves or rejects [tool].
     * Returns false if cancelled, timed out, or rejected while waiting.
     *
     * ### The deadlock this replaces
     *
     * This used to be one `@Volatile pendingApproval` field. `awaitApproval`
     * OVERWROTE it and then blocked on that approval's own latch with no timeout,
     * which was sound for exactly as long as one agent thread existed.
     *
     * With concurrent sub-agents it stopped being sound. Two threads asking at
     * once meant the second assignment clobbered the first, and the clobbered
     * approval was then unreachable from the bus: `requestCancel()` and
     * `finish()` both read that single field, so they resolved the SURVIVOR and
     * only the survivor. The orphaned thread stayed parked on an untimed
     * `latch.await()` with nothing left in the process holding a reference that
     * could ever count it down. It never returned, so the run never finished, so
     * `finish()` was never reached, so the global run slot was never released and
     * the app was permanently "busy" until the process died. The user's stop
     * button, the notification action and the service teardown were all equally
     * powerless, because all three funnel into the same two methods.
     *
     * Three things fix it, and all three are needed:
     *
     *  1. [approvals] is a QUEUE, so no ask can displace another. Each waiter
     *     blocks on its own latch and is released by its own decision.
     *  2. [requestCancel] and [finish] drain the whole queue and reject every
     *     entry, so teardown reaches threads that were never on screen.
     *  3. The wait below is BOUNDED. Even if a decision is lost outright — an
     *     Activity destroyed mid-tap, a listener swapped, a sheet dismissed by
     *     the window manager — the thread leaves after [APPROVAL_TIMEOUT_MS] and
     *     reports the timeout as a rejection, rather than holding the run slot.
     *
     * The UI still sees ONE ask at a time: only the head of the queue is
     * published through [UiListener.onApprovalRequested], and the next is
     * published as soon as the head is answered.
     */
    fun awaitApproval(
        tool: String,
        args: JSONObject?,
        token: CancellationToken?,
        agent: String? = null
    ): Boolean = awaitApprovalDetailed(tool, args, token, agent).approved

    /**
     * [awaitApproval] with the reason attached, so a caller can tell a rejection
     * apart from a cancellation or a dropped decision and say so to the model.
     */
    fun awaitApprovalDetailed(
        tool: String,
        args: JSONObject?,
        token: CancellationToken?,
        agent: String? = null
    ): Decision {
        if (token == null || token.isCancelled) {
            return Decision(false, REASON_CANCELLED)
        }
        val label = if (agent.isNullOrEmpty()) {
            threadAgentLabel.get() ?: ""
        } else {
            agent
        }
        val approval = synchronized(RUN_LOCK) {
            // A run already tearing down must never gain a new waiter: the drain
            // has been and gone, so nothing would ever resolve this one.
            if (runState == RunState.STOPPING) {
                return Decision(false, REASON_CANCELLED)
            }
            val queued = PendingApproval(nextApprovalId++, label, tool, args)
            approvals.add(queued)
            queued
        }
        val registration = token.onCancel { approval.decide(false, REASON_CANCELLED) }
        try {
            publishHead()
            if (!approval.latch.await(APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                approval.decide(false, REASON_TIMED_OUT)
            }
            if (approval.approved && token.isCancelled) {
                return Decision(false, REASON_CANCELLED)
            }
            return Decision(approval.approved, approval.reason)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            approval.decide(false, REASON_CANCELLED)
            return Decision(false, REASON_CANCELLED)
        } finally {
            registration.close()
            // Belt and braces: decide() retires on the deciding thread, but a
            // waiter that leaves by any other route must not leave a corpse at
            // the head of the queue blocking the next ask.
            retire(approval)
        }
    }

    /** Re-shows the approval sheet after the Activity is recreated. */
    fun redeliverPendingApproval() {
        publishHead()
    }

    // ---- queue internals ---------------------------------------------------

    /** Head of the queue. Caller must hold [RUN_LOCK]. */
    private fun headLocked(): PendingApproval? {
        for (approval in approvals) {
            if (!approval.decided) {
                return approval
            }
        }
        return null
    }

    /** Empties the queue and returns what was in it. Caller must hold [RUN_LOCK]. */
    private fun drainLocked(): List<PendingApproval> {
        if (approvals.isEmpty()) {
            return emptyList()
        }
        val copy = ArrayList<PendingApproval>(approvals)
        approvals.clear()
        return copy
    }

    /**
     * Drops [approval] from the queue and hands the next one to the UI.
     *
     * `internal` rather than private because [PendingApproval.decide] calls it,
     * and decisions arrive on the UI thread while waiters retire on agent
     * threads. Idempotent: removing an entry that is already gone is a no-op.
     */
    internal fun retire(approval: PendingApproval) {
        // Publish on any real removal rather than trying to detect a head change
        // here. By the time a decision reaches this point the entry is already
        // marked decided, so [headLocked] has ALREADY skipped past it — a
        // before/after comparison sees no change and the next ask never reaches
        // the screen. Re-publishing an unchanged head is harmless: the UI
        // deduplicates by approval identity.
        val removed = synchronized(RUN_LOCK) { approvals.remove(approval) }
        if (removed) {
            publishHead()
        }
    }

    /**
     * Rejects a drained batch. Called with [RUN_LOCK] RELEASED: each `decide`
     * re-enters the bus to retire itself, and deciding under the lock would make
     * the two monitors cyclic the moment a decision also arrives from the UI.
     */
    private fun rejectAll(batch: List<PendingApproval>, reason: String) {
        for (approval in batch) {
            approval.decide(false, reason)
        }
        publishHead()
    }

    /**
     * Publishes the head of the queue, and only the head — a person answers one
     * question at a time. Never called while holding [RUN_LOCK].
     */
    private fun publishHead() {
        val ui = listener ?: return
        val outstanding = outstandingApprovals()
        ui.onApprovalQueueChanged(outstanding)
        val head = currentApproval() ?: return
        if (head.decided || cancellation.isCancelled || runState == RunState.STOPPING) {
            return
        }
        ui.onApprovalRequested(head)
    }
}
