package com.vepro.code

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/**
 * Foreground service that owns an agent run.
 *
 * Living in a service (not the Activity) is what lets a long task survive the
 * user leaving the app. A timed partial wake lock is re-armed both from every
 * callback boundary and from a fixed-cadence ticker, so a long silent operation
 * (e.g. a big download with no per-chunk callback) cannot let the CPU sleep and
 * stall the run.
 */
class AgentService : Service() {

    private lateinit var store: ChatStore
    private lateinit var prefs: Prefs

    @Volatile
    private var started = false

    @Volatile
    private var activeRunId = 0L

    @Volatile
    private var runThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Guards every read/modify of [wakeLock]. `releaseWake` runs on the worker
     * thread (finishRun) *and* the main thread (onDestroy) while `renewWake`
     * runs from every stream callback: with an unsynchronized field, a renew
     * that read the reference between "release it" and "null it" re-acquired a
     * lock nothing could ever release, burning CPU for the full 10-minute
     * timeout after the run had already ended.
     */
    private val wakeLockLock = Any()

    @Volatile
    private var lastWakeRenew = 0L

    // Re-arms the wake lock on a fixed cadence, independent of stream/tool
    // callbacks — so a long, silent operation (e.g. a big download with no
    // per-chunk callback) never lets the CPU sleep and stall the run mid-task.
    private val wakeTicker = Handler(Looper.getMainLooper())

    // Gates the self-reposting pulse: if it fires exactly as releaseWake() runs,
    // this flag stops it from re-arming the ticker forever after teardown.
    @Volatile
    private var ticking = false

    private val wakePulse: Runnable = object : Runnable {
        override fun run() {
            synchronized(wakeLockLock) {
                if (!ticking) {
                    return
                }
                try {
                    wakeLock?.let {
                        it.acquire(WAKE_MS)
                        lastWakeRenew = System.currentTimeMillis()
                    }
                } catch (ignored: Exception) {
                }
                wakeTicker.postDelayed(this, WAKE_TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = ChatStore(this)
        prefs = Prefs(this)
        NetworkPolicy.applyPrefs(prefs)
        // The service can be the first component to run in a cold process, before
        // any Activity has touched the string table. Fa is a table of immutable
        // vals now, so this is a no-op — kept as the one obvious hook if the
        // strings ever need preparing again.
        Fa.apply(this)
        // The service can be the first component to run in a cold process, in
        // which case no Activity has applied the palette yet and every Theme
        // colour would still be 0 (transparent) in the notification.
        Theme.applyFromPrefs(this, prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafe()) {
            AgentBus.requestCancel()
            AgentBus.finish(AgentBus.runId())
            stopSelfClean()
            return START_NOT_STICKY
        }

        if (intent != null && ACTION_STOP == intent.action) {
            // user tapped the notification's stop action
            if (AgentBus.requestCancel(true)) {
                notifyText(Fa.STOPPING)
            } else {
                // Nothing was actually running to cancel (race: the run just
                // finished). Don't leave an orphaned foreground service +
                // notification behind — tear it down now.
                stopSelfClean()
            }
            return START_NOT_STICKY
        }

        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val runId = intent?.getLongExtra(EXTRA_RUN_ID, 0L) ?: 0L

        if (started) {
            return START_NOT_STICKY
        }
        if (chatId == null || runId == 0L || runId != AgentBus.runId()) {
            stopSelfClean()
            return START_NOT_STICKY
        }

        started = true
        activeRunId = runId

        var resolved = AgentBus.liveChat
        if (resolved == null || chatId != resolved.id) {
            resolved = store.load(chatId)
            AgentBus.liveChat = resolved
        }
        if (resolved == null || !AgentBus.markRunning(runId)) {
            started = false
            AgentBus.finish(runId)
            stopSelfClean()
            return START_NOT_STICKY
        }

        val chat = resolved
        acquireWake()
        val engine = AgentEngine(this, prefs)
        val worker = Thread({ runWorker(engine, chat, runId) }, "vepro-agent-$runId")
        runThread = worker
        worker.start()
        // NOT sticky, and NOT redeliver. On Android 12+ a system-initiated
        // restart of a foreground service from the background can throw
        // ForegroundServiceStartNotAllowedException. A killed run cannot be
        // resumed cleanly anyway (its stream and UI are gone), so letting it stay
        // dead is strictly safer than risking that crash on a reclaim.
        return START_NOT_STICKY
    }

    private fun runWorker(engine: AgentEngine, chat: Chat, runId: Long) {
        val token = AgentBus.token()
        try {
            engine.run(chat, token, callback(chat, token))
        } finally {
            started = false
            runThread = null
            finishRun(chat, runId)
        }
    }

    private fun callback(chat: Chat, token: CancellationToken): AgentEngine.Callback =
        object : AgentEngine.Callback {

            override fun onNewAssistantMessage(message: Message) {
                AgentBus.listener?.onNewAssistantMessage(message)
            }

            override fun onDelta(message: Message, delta: String) {
                renewWake()
                AgentBus.listener?.onDelta(message)
            }

            override fun onThinking(message: Message, thinking: String) {
                renewWake()
                AgentBus.listener?.onThinking(message)
            }

            override fun onStepFinalized(message: Message) {
                store.save(chat)
                AgentBus.listener?.onStepFinalized(message)
            }

            override fun onToolRunning(tool: String, detail: String) {
                renewWake()
                updateNotification(tool)
                AgentBus.listener?.onToolRunning(tool, detail)
            }

            override fun onToolMessage(message: Message, detail: String) {
                renewWake()
                store.save(chat)
                AgentBus.listener?.onToolMessage(message)
            }

            override fun onTrailChanged(owner: Message) {
                renewWake()
                // Deliberately NOT saved here. The trail changes several times per
                // step — a phase line, a row opening, a row closing — and writing
                // the whole chat to disk on each one would turn one web search into
                // a burst of file IO. It rides along with the next real save
                // (onStepFinalized / onToolMessage / onComplete), every one of
                // which happens within a step of any trail change.
                AgentBus.listener?.onTrailChanged(owner)
            }

            override fun onComplete() {
                store.save(chat)
                AgentBus.listener?.onComplete()
            }

            override fun onError(error: String) {
                store.save(chat)
                AgentBus.listener?.onError(error)
            }

            override fun requestApproval(tool: String, args: JSONObject?): Boolean =
                AgentBus.awaitApproval(tool, args, token)

            override fun isCancelled(): Boolean = token.isCancelled
        }

    private fun finishRun(chat: Chat, runId: Long) {
        try {
            // saveNow, not save: this is the run's LAST write and stopSelf() is
            // four lines below, so a queued async write could still be in the
            // executor when the system reclaims the process — losing the final
            // answer. It blocks the agent worker thread, never the main thread.
            store.saveNow(chat)
        } catch (ignored: Throwable) {
        }
        AgentBus.finish(runId)
        activeRunId = 0L
        releaseWake()
        stopForegroundSafe()
        stopSelf()
    }

    // ---- foreground / notification ----------------------------------------

    private fun startForegroundSafe(): Boolean {
        ensureChannel()
        val notification = buildNotification(Fa.SVC_TEXT)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (first: Throwable) {
            // API 31+ can throw ForegroundServiceStartNotAllowedException here;
            // retry without the type, and if that also fails, report failure so
            // onStartCommand tears down cleanly instead of crashing.
            try {
                startForeground(NOTIF_ID, notification)
                true
            } catch (second: Throwable) {
                false
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        // No early return when the channel exists: createNotificationChannel
        // UPDATES the name and description of an existing id. Skipping it pinned
        // the channel to the wording it was first created with, and that label is
        // what the user sees in Android's notification settings forever — so an
        // upgrade that rewords it would never take effect.
        val channel = NotificationChannel(
            CHANNEL_ID, Fa.SVC_CHANNEL, NotificationManager.IMPORTANCE_LOW
        )
        channel.description = Fa.SVC_TEXT
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        // FLAG_IMMUTABLE arrived in API 23. It is the app's floor, so the branch is
        // belt-and-braces rather than strictly required — but it is the same shape
        // App.kt:97 uses for the same constant, and one spelling of a version check
        // is worth more than an argument about which sites need one.
        var pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) {
            pendingFlags = pendingFlags or PendingIntent.FLAG_IMMUTABLE
        }
        val open = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val stopIntent = Intent(this, AgentService::class.java)
        stopIntent.action = ACTION_STOP
        val stopAction = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle(Fa.SVC_TITLE)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_stat_vepro)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    R.mipmap.ic_stat_vepro, Fa.CANCEL, stopAction
                ).build()
            )
        try {
            // v5.7 brand violet accent on the notification (matches the logo)
            builder.setColor(Theme.ACCENT)
        } catch (e: Exception) {
        }
        return builder.build()
    }

    /** Notification text for "a tool named [text] is running". */
    fun updateNotification(text: String) {
        notifyText("Running " + text + " …")
    }

    /**
     * Notification text used VERBATIM.
     *
     * The stop path used notifyText(Fa.STOPPING), which wrapped it in the
     * running-tool template and produced "Running Stopping… …" — garbled, on the
     * path every user who cancels a long run sees.
     */
    private fun notifyText(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
        }
    }

    /**
     * Drops the foreground state and its notification.
     *
     * `stopForeground(int)` and the `STOP_FOREGROUND_REMOVE` flag it takes are
     * both API 24, so on Android 6 this raised NoSuchMethodError — on the one
     * path every finished, cancelled or timed-out run goes through, leaving an
     * undismissable notification and a service that never stopped. The
     * boolean overload is the exact pre-24 equivalent: `true` means "remove the
     * notification", which is precisely what STOP_FOREGROUND_REMOVE asks for.
     *
     * The catch is Throwable, not Exception: NoSuchMethodError is an Error, so
     * the old `catch (e: Exception)` could not have caught the very failure this
     * method existed to be safe against — it propagated out of finishRun and
     * killed the worker thread's unwind.
     */
    private fun stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Throwable) {
        }
    }

    private fun stopSelfClean() {
        stopForegroundSafe()
        stopSelf()
    }

    // ---- wake lock ---------------------------------------------------------

    private fun acquireWake() {
        synchronized(wakeLockLock) {
            try {
                val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vepro:agent")
                wakeLock = lock
                lock.setReferenceCounted(false)
                lock.acquire(WAKE_MS)
                lastWakeRenew = System.currentTimeMillis()
                ticking = true
                wakeTicker.removeCallbacks(wakePulse)
                wakeTicker.postDelayed(wakePulse, WAKE_TICK_MS)
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Re-arms the wake lock so long / heavy tasks never get suspended mid-run.
     * The lock is timed (so a crashed run can't hold the CPU forever), and this
     * is called from every callback boundary to push that deadline forward.
     * Throttled to once every 30s so per-token calls stay cheap.
     */
    private fun renewWake() {
        // Cheap unsynchronized pre-check keeps the per-token path free; the
        // actual acquire still happens under the lock.
        if (System.currentTimeMillis() - lastWakeRenew < 30000) {
            return
        }
        synchronized(wakeLockLock) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastWakeRenew < 30000) {
                    return
                }
                lastWakeRenew = now
                wakeLock?.acquire(WAKE_MS)
            } catch (e: Exception) {
            }
        }
    }

    private fun releaseWake() {
        synchronized(wakeLockLock) {
            ticking = false
            try {
                wakeTicker.removeCallbacks(wakePulse)
            } catch (ignored: Exception) {
            }
            // Null the field FIRST: anything that wakes up mid-release then
            // finds no lock to re-acquire, instead of resurrecting one that is
            // about to become unreachable.
            val lock = wakeLock
            wakeLock = null
            try {
                if (lock != null && lock.isHeld) {
                    lock.release()
                }
            } catch (e: Exception) {
            }
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * BUGFIX: Android 14 (API 34) delivers the dataSync foreground-service
     * timeout through THIS single-argument callback; only API 35+ uses the
     * two-argument overload below. Without this override the default no-op
     * ran, the service never stopped, and the system killed the whole app
     * with ForegroundServiceDidNotStopInTimeException after the 6-hour cap.
     */
    override fun onTimeout(startId: Int) {
        handleFgsTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleFgsTimeout(startId)
    }

    private fun handleFgsTimeout(startId: Int) {
        AgentBus.requestCancel()
        notifyText(Fa.STOPPING)
        runThread?.interrupt()
        stopForegroundSafe()
        stopSelf(startId)
    }

    override fun onDestroy() {
        val runId = activeRunId
        if (runId != 0L) {
            AgentBus.requestCancel()
            runThread?.interrupt()
            // The worker owns AgentBus.finish(runId) in its finally block. Do not
            // free the global run slot while file/network work can still unwind.
        }
        releaseWake()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "vepro_agent_run"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_RUN_ID = "runId"
        const val ACTION_STOP = "com.vepro.code.STOP_AGENT"
        const val NOTIF_ID = 4021

        private const val WAKE_MS = 600000L
        private const val WAKE_TICK_MS = 240000L // 4 min (< the 10-min lease)

        fun start(context: Context, chatId: String, runId: Long) {
            val intent = Intent(context, AgentService::class.java)
            intent.putExtra(EXTRA_CHAT_ID, chatId)
            intent.putExtra(EXTRA_RUN_ID, runId)
            if (Build.VERSION.SDK_INT < 26) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        }
    }
}
