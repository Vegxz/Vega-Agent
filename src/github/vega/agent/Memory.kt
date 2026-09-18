package github.vega.agent

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only long-term memory scratchpad exposed to the agent as the
 * `remember` / `recall` tools.
 *
 * Bounded: the file is capped at [MAX_BYTES] (oldest notes are dropped first)
 * and a single note at [MAX_NOTE_CHARS], so a runaway `remember` loop can
 * neither fill the disk nor blow up the system prompt this file is pasted
 * into on every agent turn. All access is synchronized: `read()` used to race
 * with `remember()`'s append, which could hand the model a half-written note.
 */
class Memory(context: Context) {

    private val file = File(context.filesDir, "agent_memory.txt")

    @Synchronized
    fun remember(note: String?): String {
        val trimmed = note?.trimJava()
        if (trimmed.isNullOrEmpty()) {
            return "ERROR: nothing to remember"
        }
        // One giant paste must not evict everything else already remembered.
        val safe = trimmed.replace(Regex("[\\r\\n]+"), " ").take(MAX_NOTE_CHARS)
        val line = "- " + safe + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        // use() so the stream is always closed, even if the write throws
        return try {
            compactLocked(bytes.size)
            FileOutputStream(file, true).use { out ->
                out.write(bytes)
            }
            "OK: saved to memory."
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    @Synchronized
    fun recall(): String {
        val body = readLocked()
        return if (body.isBlankJava()) "(memory is empty)" else "Saved memory:\n$body"
    }

    @Synchronized
    fun read(): String = readLocked()

    @Synchronized
    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun readLocked(): String {
        if (!file.exists()) {
            return ""
        }
        return try {
            String(Util.readAll(file), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Drops oldest whole notes until [need] more bytes fit under [MAX_BYTES].
     * Writes the survivors through a temp file + rename so a crash or a full
     * disk can never leave a half-written memory file behind; on any failure
     * the old file is kept untouched and the caller still appends.
     */
    private fun compactLocked(need: Int) {
        if (!file.exists()) {
            return
        }
        val excess = file.length() + need - MAX_BYTES
        if (excess <= 0) {
            return
        }
        try {
            val lines = file.readLines(Charsets.UTF_8)
            var dropBytes = 0L
            var drop = 0
            while (drop < lines.size && dropBytes < excess) {
                dropBytes += lines[drop].toByteArray(Charsets.UTF_8).size + 1
                drop++
            }
            val kept = lines.drop(drop)
            val tmp = File(file.parent, file.name + ".tmp")
            try {
                tmp.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
            }
        } catch (e: Exception) {
            // keep the old file rather than losing memory
        }
    }

    companion object {
        /**
         * Hard ceiling on the whole memory file. Sized so the block this is
         * pasted into in every system prompt stays a few dozen KB, not a
         * megabyte the model pays for on every turn.
         */
        private const val MAX_BYTES = 32 * 1024

        /** Longest single note; a pasted wall of text is trimmed, not dropped. */
        private const val MAX_NOTE_CHARS = 1000
    }
}
