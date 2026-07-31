package com.vepro.code

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Chats persisted one JSON file each under filesDir/chats. Saves are atomic
 * (write to a unique temp file, fsync, rename) so a crash mid-write can never
 * leave a truncated conversation behind.
 *
 * ### Why the write is asynchronous
 *
 * [save] used to serialise the conversation AND `fsync` it on the calling
 * thread, and about a dozen of its callers are on the main thread — every user
 * turn, every rename, every pin, every stop. On the 2016-era eMMC this build
 * now supports (API 23) an `fd.sync()` is routinely 10-50ms and occasionally far
 * worse when the flash is garbage-collecting, so the app's single largest
 * main-thread stall was its own durability.
 *
 * The split is [save] (asynchronous, coalescing) versus [saveNow] (blocking, for
 * teardown). Three properties are preserved exactly:
 *
 *  - **Ordering.** Every write goes through one single-threaded executor, so
 *    two saves can never land out of order — not for one chat, and not across
 *    the Activity's `ChatStore` and the service's, which are separate instances
 *    over one directory (see [saveLock]).
 *  - **Durability.** [saveNow] blocks until the bytes are on disk and is what
 *    the teardown paths call, so nothing is lost when the process is reclaimed.
 *    [flushPendingWrites] does the same for a crash unwind.
 *  - **Atomicity.** Unchanged: unique temp file, `fsync`, rename.
 *
 * ### The snapshot rule
 *
 * What crosses the thread boundary is a `ByteArray`, never a [Chat]. The
 * conversation is serialised on the CALLING thread, under the same lock and at
 * the same moment it always was, and only the finished bytes are queued. The
 * agent worker appends to `Message.toolLog` and to `Chat.messages` continuously
 * while a run streams; handing the live object to a background writer would
 * have meant serialising it concurrently with those appends, which is a
 * ConcurrentModificationException under exactly the load that made this slow in
 * the first place.
 */
class ChatStore(context: Context) {

    private val dir: File = File(context.filesDir, "chats").also {
        if (!it.exists()) {
            it.mkdirs()
        }
    }

    /** Strictly monotonic timestamp, so two saves in the same millisecond still order. */
    private fun now(): Long = nowStatic()

    fun create(): Chat {
        val stamp = now()
        // The title is stored EMPTY, never as the "New chat" placeholder text.
        // Writing the placeholder to disk froze whatever wording the build used at
        // creation time, and chats made by older builds are still on disk carrying
        // theirs. Every display site falls back to Fa.NEW_CHAT for an empty title
        // (and Fa.isPlaceholderTitle recognises the older spellings), so the label
        // always matches the current build, and autoTitle() replaces it on the
        // first real message.
        val chat = Chat("chat_$stamp", "", stamp)
        // Blocking: the id is handed straight to AgentService, which loads the
        // chat back off disk by id. An async create could still be queued when
        // that load runs, and the run would abort on a chat that "does not exist".
        // Once per new conversation, so the cost is irrelevant.
        saveNow(chat)
        return chat
    }

    /**
     * Queues the conversation to be written, and returns immediately.
     *
     * Coalescing: the snapshot is filed under the chat's id, so N saves of one
     * chat that arrive faster than the disk can absorb them collapse into a
     * single write of the LATEST state. A burst of tool observations therefore
     * costs one fsync, not one per observation.
     *
     * Does not throw. A write that fails has no caller left to tell — the
     * failure is logged, and the next [saveNow] (teardown, always on a path the
     * user can be told about) reports a fresh attempt honestly.
     */
    fun save(chat: Chat) {
        val id = chat.id
        // Snapshot and publish under ONE lock hold, so two threads saving the
        // same chat cannot interleave "serialise" and "publish" and leave the
        // older bytes as the newest pending entry.
        synchronized(saveLock) {
            chat.updatedAt = now()
            pending[id] = chat.toJson().toString().toByteArray(Charsets.UTF_8)
        }
        val target = File(dir, "$id.json")
        try {
            writer.execute(Runnable { report(id, drain(id, target)) })
        } catch (rejected: Throwable) {
            // The executor is shut down (process tearing down). Better a
            // synchronous write on this thread than a silently dropped chat.
            report(id, drain(id, target))
        }
    }

    /**
     * Writes the conversation and does not return until it is on disk.
     *
     * For teardown only — `onStop`, `onDestroy`, the end of a run — where an
     * asynchronous write could still be queued when the process is reclaimed.
     * Throws [IllegalStateException] if the write fails, exactly as the old
     * synchronous [save] did, so the existing "could not save" handling keeps
     * working on the paths that still use it.
     */
    fun saveNow(chat: Chat) {
        val id = chat.id
        synchronized(saveLock) {
            chat.updatedAt = now()
            pending[id] = chat.toJson().toString().toByteArray(Charsets.UTF_8)
        }
        val target = File(dir, "$id.json")
        // Run it ON the writer thread and wait, rather than writing here: that is
        // what keeps the total order intact. Anything queued before this call is
        // written first, and a queued older snapshot cannot land after this one
        // because there is only ever one thread doing the writing.
        val task: Future<IllegalStateException?>? = try {
            writer.submit(Callable { drain(id, target) })
        } catch (rejected: Throwable) {
            null
        }
        // The executor is gone (process teardown): write here instead of dropping
        // the conversation on the floor.
        if (task == null) {
            drain(id, target)?.let { throw it }
            return
        }
        val problem = try {
            task.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            throw IllegalStateException("chat save failed: " + e.message, e)
        }
        // drain() RETURNS its failure rather than throwing, so an async write can
        // never kill the writer thread; the blocking path is the one with a caller
        // to tell, so it re-raises.
        problem?.let { throw it }
    }

    /**
     * Writes whatever snapshot is currently filed for [id], if any.
     *
     * Runs on the writer thread. CLAIMING the snapshot here rather than carrying
     * it in the task is what makes coalescing safe: the first task to reach a
     * chat takes the newest bytes and every task queued behind it finds nothing
     * left to do, so a burst of saves is one write of the final state.
     *
     * Returns the failure instead of throwing it, so the caller decides whether
     * anyone is still listening.
     */
    private fun drain(id: String, target: File): IllegalStateException? {
        val data = synchronized(saveLock) { pending.remove(id) } ?: return null
        val tmp = File(
            dir,
            "$id.json.tmp." + Thread.currentThread().id + "." + System.nanoTime()
        )
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("atomic chat save failed")
            }
            null
        } catch (e: Exception) {
            if (tmp.exists()) {
                tmp.delete()
            }
            IllegalStateException("chat save failed: " + e.message, e)
        }
    }

    /** The async path's only recourse: there is no caller left to throw at. */
    private fun report(id: String, problem: IllegalStateException?) {
        if (problem != null) {
            Log.e("Vega", "chat save failed for $id", problem)
        }
    }

    fun load(id: String): Chat? {
        val file = File(dir, "$id.json")
        if (!file.exists()) {
            return null
        }
        return try {
            Chat.fromJson(JSONObject(String(Util.readAll(file), Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    fun delete(id: String) {
        // Drop any queued snapshot FIRST, then run the unlink on the writer
        // thread and wait for it. Both halves matter: without the first, a save
        // enqueued moments before the tap would rewrite the file straight after
        // the delete; without the second, a write already claimed by the writer
        // could land after the unlink. Either way the conversation the user just
        // deleted reappears in the drawer.
        synchronized(saveLock) { pending.remove(id) }
        val file = File(dir, "$id.json")
        val unlink = Runnable {
            if (file.exists()) {
                file.delete()
            }
        }
        val task = try {
            writer.submit(unlink)
        } catch (rejected: Throwable) {
            null
        }
        if (task == null) {
            unlink.run()
            return
        }
        try {
            task.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ignored: Exception) {
        }
    }

    /**
     * Lightweight drawer row: id + title + updatedAt, WITHOUT parsing the
     * conversation body.
     *
     * The drawer only needs titles, but [list] used to deserialize every message
     * of every chat just to show them — and the delete flow did that two or three
     * times in a single tap, then rebuilt a whole transcript. With many (or
     * large) chats that is megabytes of transient allocation on the UI thread,
     * which is what made deleting a chat crash (OutOfMemoryError) or freeze long
     * enough for the system to kill the app. Reading just the file header keeps
     * this O(number-of-chats), not O(total-conversation-bytes).
     */
    class Summary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val pinned: Boolean = false
    )

    fun listSummaries(): MutableList<Summary> {
        val out = mutableListOf<Summary>()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".json")) {
                readSummary(file)?.let { out.add(it) }
            }
        }
        // Pinned first, then most recent. A comparator rather than two passes so
        // the order is one rule the drawer cannot render out of step with.
        out.sortWith(
            compareByDescending<Summary> { it.pinned }.thenByDescending { it.updatedAt }
        )
        return out
    }

    private fun readSummary(file: File): Summary? {
        // Chats are written id/title/createdAt/updatedAt/messages in order, so the
        // header is normally the first ~150 bytes. Try a small head, then a larger
        // one (a very long title, or a title containing the literal text
        // "messages", can push the real cut further out) before paying for a full
        // parse.
        headerSummary(file, 8192)?.let { return it }
        headerSummary(file, 65536)?.let { return it }
        // Older or unusually-ordered files: full parse. Correct, just not cheap.
        // Unreadable files are skipped, not fatal.
        return try {
            val chat = Chat.fromJson(JSONObject(String(Util.readAll(file), Charsets.UTF_8)))
            Summary(chat.id, chat.title, chat.updatedAt, chat.pinned)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun headerSummary(file: File, headBytes: Int): Summary? {
        return try {
            val head = readHead(file, headBytes)
            val cut = head.indexOf("\"messages\"")
            val headerJson = if (cut > 0) {
                var h = head.substring(0, cut).trimJava()
                if (h.endsWith(",")) {
                    h = h.substring(0, h.length - 1)
                }
                "$h}"
            } else {
                head
            }
            val obj = JSONObject(headerJson)
            Summary(
                obj.getString("id"),
                obj.optStr("title", ""),
                obj.optLong("updatedAt", obj.optLong("createdAt", 0L)),
                obj.optBoolean("pinned", false)
            )
        } catch (headerFailure: Exception) {
            null
        }
    }

    private fun readHead(file: File, max: Int): String {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = input.read(buffer, total, max - total)
                if (n < 0) {
                    break
                }
                total += n
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    /** Most recently updated first. Unreadable files are skipped, not fatal. */
    fun list(): MutableList<Chat> {
        val chats = mutableListOf<Chat>()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".json")) {
                try {
                    chats.add(Chat.fromJson(JSONObject(String(Util.readAll(file), Charsets.UTF_8))))
                } catch (ignored: Exception) {
                }
            }
        }
        chats.sortByDescending { it.updatedAt }
        return chats
    }

    companion object {
        /**
         * The one thread that touches a chat file, process-wide.
         *
         * Single-threaded on purpose, and that single property is the whole
         * ordering guarantee: two snapshots of one conversation cannot be written
         * out of order, a save from the service cannot overtake a save from the
         * Activity, and [delete]'s unlink cannot race a write. A pool of any size
         * above one would need per-chat locking to say the same thing, and would
         * still not order a save against a delete.
         *
         * Daemon, so a queued write can never hold a dying process open; the
         * writes that must not be lost are the ones [saveNow] blocks on.
         */
        private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            val thread = Thread(runnable, "vepro-chat-store")
            thread.isDaemon = true
            thread
        }

        /**
         * chat id -> newest serialised snapshot not yet on disk.
         *
         * Guarded by [saveLock]. This map IS the coalescing: a second save of the
         * same chat replaces the entry rather than adding work, so the writer
         * always writes the latest state and never a stale one.
         */
        private val pending = HashMap<String, ByteArray>()

        /**
         * Blocks until everything queued so far has been written, or [timeoutMs]
         * elapses. Returns true if the queue drained.
         *
         * For a process that is about to die on a path with no chat in hand —
         * App's uncaught-exception handler, which kills the process a few
         * microseconds later. The barrier works because the executor is FIFO and
         * single-threaded: once a task submitted now has run, every task
         * submitted before it has run too.
         */
        fun flushPendingWrites(timeoutMs: Long): Boolean {
            return try {
                writer.submit(Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS)
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (ignored: Throwable) {
                false
            }
        }

        /**
         * The save lock and the monotonic clock are PROCESS-wide, not
         * per-instance.
         *
         * The Activity and the foreground service each construct their own
         * `ChatStore` over the same directory. With per-instance state their
         * saves never serialized against each other: `AgentService.finishRun`
         * writes the conversation on the worker thread *after* the UI has
         * already flipped to idle, so a message the user sent in that window
         * could be overwritten by the service's older snapshot landing second —
         * and the message was gone for good after a restart. The clock had the
         * same split-brain, which made the drawer's ordering flicker between
         * two saves in the same millisecond.
         */
        private val saveLock = Any()

        private var clock: Long = System.currentTimeMillis()

        @Synchronized
        private fun nowStatic(): Long {
            var stamp = System.currentTimeMillis()
            if (stamp <= clock) {
                stamp = clock + 1
            }
            clock = stamp
            return stamp
        }
    }
}
