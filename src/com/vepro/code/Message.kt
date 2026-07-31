package com.vepro.code

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One turn in a conversation, plus anything the user attached to it. */
class Message(var role: String, content: String?) {

    var content: String = content ?: ""
    var thinking: String? = ""
    var streaming: Boolean = false
    var isError: Boolean = false

    /**
     * The live activity record for the whole request, attached to the FIRST
     * assistant message of a run. Null on every other message.
     */
    var trail: Trail? = null

    /** The Dynamic Workflow board for this run, when the mode is on. */
    var workflow: Workflow? = null

    /**
     * True for an intermediate assistant turn that ended in a tool call — one
     * whose prose is now shown as a phase line inside the run's [Trail] rather
     * than as a bubble of its own.
     *
     * Only ever set by a run that HAS a trail, so a chat saved by an older build
     * keeps rendering every step exactly as before instead of silently losing
     * half its history.
     */
    var isStep: Boolean = false

    val attachments: MutableList<Attachment> = mutableListOf()
    val toolLog: MutableList<String> = mutableListOf()

    /** A user-supplied file riding along with a message. */
    class Attachment(
        var name: String?,
        var path: String?,
        var mime: String?,
        var size: Long,
        var kind: String?
    ) {
        /** Populated lazily for images that get inlined into the request body. */
        var dataUri: String? = null
    }

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("role", role)
        json.put("content", content)
        json.put("thinking", thinking ?: "")
        json.put("isError", isError)
        if (isStep) {
            json.put("isStep", true)
        }
        // Only written when there is something to write, so an ordinary message
        // costs no extra bytes in the chat file.
        trail?.let { json.put("trail", it.toJson()) }
        workflow?.let { json.put("workflow", it.toJson()) }

        val files = JSONArray()
        for (attachment in attachments) {
            val item = JSONObject()
            item.put("name", attachment.name)
            item.put("path", attachment.path ?: "")
            item.put("mime", attachment.mime ?: "")
            item.put("size", attachment.size)
            item.put("kind", attachment.kind ?: "file")
            files.put(item)
        }
        json.put("attachments", files)

        val log = JSONArray()
        for (entry in toolLog) {
            log.put(entry)
        }
        json.put("toolLog", log)
        return json
    }

    companion object {
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): Message {
            val message = Message(json.getString("role"), json.optStr("content", ""))
            message.thinking = json.optStr("thinking", "")
            message.isError = json.optBoolean("isError", false)
            message.isStep = json.optBoolean("isStep", false)
            message.trail = Trail.fromJson(json.optJSONObject("trail"))
            message.workflow = Workflow.fromJson(json.optJSONObject("workflow"))

            json.optJSONArray("attachments")?.let { files ->
                for (i in 0 until files.length()) {
                    val item = files.getJSONObject(i)
                    val attachment = Attachment(
                        item.optStr("name"),
                        item.optStr("path"),
                        item.optStr("mime"),
                        item.optLong("size"),
                        item.optStr("kind", "file")
                    )
                    // an empty stored path means "no local file any more"
                    if (attachment.path?.isEmpty() == true) {
                        attachment.path = null
                    }
                    message.attachments.add(attachment)
                }
            }

            json.optJSONArray("toolLog")?.let { log ->
                for (i in 0 until log.length()) {
                    message.toolLog.add(log.getString(i))
                }
            }
            return message
        }
    }
}
