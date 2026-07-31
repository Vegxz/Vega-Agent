package com.vepro.code

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A single conversation. [messages] is guarded by its own monitor because the
 * agent worker thread appends to it while the UI thread renders it.
 */
class Chat(
    var id: String,
    var title: String,
    var createdAt: Long
) {
    var updatedAt: Long = createdAt

    /**
     * Kept at the top of the drawer, above every other conversation.
     *
     * Ordering is by pin first and recency second, so pinning is a promise that a
     * chat stays findable however much is started after it — which is the only
     * reason to pin anything.
     */
    var pinned: Boolean = false

    val messages: MutableList<Message> = mutableListOf()

    /** Snapshot taken under the list monitor, safe to iterate off-thread. */
    private fun messageSnapshot(): List<Message> = synchronized(messages) { messages.toList() }

    /** Names the chat after its first non-empty user turn. */
    fun autoTitle() {
        for (message in messageSnapshot()) {
            if (message.role == "user" && message.content.isNotBlankJava()) {
                var candidate = message.content.trimJava().replace(Regex("\\s+"), " ")
                if (candidate.length > 42) {
                    candidate = candidate.substring(0, 42) + "…"
                }
                title = candidate
                return
            }
        }
    }

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        if (pinned) {
            json.put("pinned", true)
        }
        json.put("title", title)
        json.put("createdAt", createdAt)
        json.put("updatedAt", updatedAt)

        val array = JSONArray()
        for (message in messageSnapshot()) {
            array.put(message.toJson())
        }
        json.put("messages", array)
        return json
    }

    companion object {
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): Chat {
            val chat = Chat(
                json.getString("id"),
                // Keep the stored value verbatim (often ""): the UI substitutes
                // the placeholder at render time via Fa.isPlaceholderTitle.
                json.optStr("title", ""),
                json.optLong("createdAt", 0L)
            )
            chat.updatedAt = json.optLong("updatedAt", chat.createdAt)
            chat.pinned = json.optBoolean("pinned", false)
            json.optJSONArray("messages")?.let { array ->
                for (i in 0 until array.length()) {
                    chat.messages.add(Message.fromJson(array.getJSONObject(i)))
                }
            }
            return chat
        }
    }
}
