package com.vepro.code

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only long-term memory scratchpad exposed to the agent as the
 * `remember` / `recall` tools.
 */
class Memory(context: Context) {

    private val file = File(context.filesDir, "agent_memory.txt")

    @Synchronized
    fun remember(note: String?): String {
        val trimmed = note?.trimJava()
        if (trimmed.isNullOrEmpty()) {
            return "ERROR: nothing to remember"
        }
        val line = "- " + trimmed.replace("\n", " ") + "\n"
        // use() so the stream is always closed, even if the write throws
        return try {
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
            }
            "OK: saved to memory."
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    @Synchronized
    fun recall(): String {
        val body = read()
        return if (body.isBlankJava()) "(memory is empty)" else "Saved memory:\n$body"
    }

    fun read(): String {
        if (!file.exists()) {
            return ""
        }
        return try {
            String(Util.readAll(file), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }
}
