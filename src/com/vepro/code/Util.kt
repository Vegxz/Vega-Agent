package com.vepro.code

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/** Small shared helpers: bounded IO, mime/kind sniffing, URL hygiene. */
object Util {

    private const val MAX_READ_BYTES = 16 * 1024 * 1024

    /** Extensions we force to text/plain when the platform has no mapping. */
    private val TEXT_EXTENSIONS = setOf(
        "md", "txt", "log", "json", "xml", "java", "kt", "py",
        "js", "ts", "c", "cpp", "h", "sh", "html", "css"
    )

    @Throws(Exception::class)
    fun readAll(file: File): ByteArray = FileInputStream(file).use { readAll(it) }

    /**
     * Reads at most [limit] bytes from [file].
     *
     * Callers that only intend to show the first N bytes should use this rather
     * than reading the whole file and truncating afterwards — the truncation
     * happens after the allocation, which is exactly the wrong order for a
     * multi-megabyte log on a phone.
     */
    @Throws(Exception::class)
    fun readAtMost(file: File, limit: Int): ByteArray {
        if (limit <= 0) {
            return ByteArray(0)
        }
        val cap = Math.min(limit, MAX_READ_BYTES)
        FileInputStream(file).use { input ->
            val out = ByteArrayOutputStream(Math.min(cap, 65536))
            val buffer = ByteArray(65536)
            var total = 0
            while (total < cap) {
                val count = input.read(buffer, 0, Math.min(buffer.size, cap - total))
                if (count < 0) {
                    break
                }
                out.write(buffer, 0, count)
                total += count
            }
            return out.toByteArray()
        }
    }

    /** Reads a stream fully, refusing anything above [MAX_READ_BYTES]. */
    @Throws(Exception::class)
    fun readAll(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            if (count == 0) {
                continue
            }
            if (count > MAX_READ_BYTES - total) {
                throw IllegalStateException("input exceeds safe memory limit")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb)
        }
        val mb = kb / 1024.0
        return if (mb < 1024.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }

    fun ext(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase(Locale.US) else ""
    }

    fun mimeOf(name: String): String {
        val extension = ext(name)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        return if (extension in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }

    fun kindOf(mime: String?): String {
        if (mime == null) {
            return "file"
        }
        return when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("text/") || mime.contains("json") ||
                mime.contains("xml") || mime.contains("javascript") -> "text"
            else -> "file"
        }
    }

    fun isTextMime(mime: String?): Boolean = kindOf(mime) == "text"

    /**
     * `mime` is nullable on purpose: an attachment can reach here without a
     * sniffed type, and the Java built the same "data:null;base64,…" string
     * rather than failing. Kept identical so request bodies do not change.
     */
    @Throws(Exception::class)
    fun base64DataUri(file: File, mime: String?): String =
        "data:" + mime + ";base64," + Base64.encodeToString(readAll(file), Base64.NO_WRAP)

    /** Copies a content:// stream into the cache dir; null when it cannot be read. */
    fun cacheFromUri(context: Context, uri: Uri, name: String?): File? {
        var target: File? = null
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    return null
                }
                val file = File(context.cacheDir, "att_" + System.nanoTime() + "_" + sanitize(name))
                target = file
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count == 0) {
                            continue
                        }
                        if (count > MAX_READ_BYTES - total) {
                            throw IllegalStateException("attachment exceeds safe memory limit")
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
                file
            }
        } catch (e: Exception) {
            target?.delete()
            null
        }
    }

    fun sanitize(name: String?): String =
        name?.replace(Regex("[^a-zA-Z0-9._-]"), "_") ?: "file"

    /**
     * Normalises a URL before it is fetched or downloaded. A valid URL never
     * contains raw whitespace, yet models and RTL text rendering frequently
     * inject stray spaces (famously around dots: "nex1music. ir") or invisible
     * bidi/zero-width marks. We strip all of those, drop surrounding markdown
     * angle-brackets/quotes, and default a bare host to https://. Any real
     * space that belongs in a path is preserved as %20.
     */
    fun cleanUrl(value: String?): String? {
        if (value == null) {
            return null
        }
        var url = value.trimJava()
        // strip wrapping < > or quotes the model sometimes adds
        while (url.length > 1 &&
            (url.startsWith("<") || url.startsWith("\"") || url.startsWith("'") || url.startsWith("("))
        ) {
            url = url.substring(1).trimJava()
        }
        while (url.length > 1) {
            val last = url[url.length - 1]
            // A trailing ')' is only punctuation when the URL has no '(' of its
            // own — stripping it unconditionally broke every Wikipedia link of
            // the "…/Java_(programming_language)" shape into a 404.
            val stripping = when (last) {
                '>', '"', '\'', '.', ',' -> true
                ')' -> !url.contains("(")
                else -> false
            }
            if (!stripping) {
                break
            }
            url = url.substring(0, url.length - 1).trimJava()
        }
        // remove zero-width / bidi control marks anywhere
        url = url.replace(Regex("[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]"), "")

        var schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            // bare host like "site.com/x" → assume https, and strip all spaces
            url = "https://$url"
            schemeEnd = 5
        }
        val head = url.substring(0, schemeEnd + 3)
        val rest = url.substring(schemeEnd + 3)

        // split authority (host[:port]) from the path/query
        val slash = rest.indexOf('/')
        val authorityRaw = if (slash < 0) rest else rest.substring(0, slash)
        val pathRaw = if (slash < 0) "" else rest.substring(slash)

        // host must never contain whitespace — remove it entirely
        val authority = authorityRaw.replace(Regex("\\s+"), "")

        // in the path, collapse stray whitespace: a space adjacent to a
        // separator is almost always an artifact (drop it); a genuine internal
        // space becomes %20
        // In the path, a space sitting directly against a *structural*
        // separator is almost always a copy/paste artifact and is dropped.
        // '.', '-' and '_' are deliberately NOT in that set: they occur inside
        // real file names, and treating them as separators silently rewrote
        // ".../Ali Reza - Bahar 320.mp3" into ".../Ali%20Reza-Bahar%20320.mp3",
        // a URL that 404s — which the agent then reported as a dead link.
        val path = pathRaw
            .replace(Regex("\\s+([/?=&#])"), "$1")
            .replace(Regex("([/?=&#])\\s+"), "$1")
            .replace(Regex("\\s+"), "%20")

        return head + authority + path
    }

    fun truncate(text: String?, limit: Int): String {
        if (text == null) {
            return ""
        }
        if (text.length <= limit) {
            return text
        }
        return text.substring(0, limit) + "\n…[truncated " + (text.length - limit) + " chars]"
    }
}
