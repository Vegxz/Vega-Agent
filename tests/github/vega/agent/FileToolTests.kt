package github.vega.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/**
 * Regression tests for the file-tool hardening: the unreliable
 * create/edit/delete/move behaviour behind "that path is a folder, not a
 * file" and the /storage/emulated/0 failures.
 *
 * Every test gets a FRESH workspace root (a temp dir), so nothing here can
 * touch the developer's real files. Tools is constructed against a minimal
 * Context fake modelled on AgentLoopTests' Ctx.
 */
object FileToolTests {

    private var assertions = 0

    private fun truth(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError(message)
        }
    }

    // ---- minimal platform fakes -------------------------------------------

    private class Sp : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun contains(key: String): Boolean = values.containsKey(key)

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        override fun getString(key: String, defValue: String?): String? {
            val stored = values[key] ?: return defValue
            return stored as? String ?: defValue
        }

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun edit(): SharedPreferences.Editor = Ed(this)

        private class Ed(private val owner: Sp) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                owner.values[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                owner.values.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                owner.values.clear()
                return this
            }

            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    private class Ctx(val dir: File) : ContextWrapper(null) {
        private val prefs = Sp()

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = dir
        override fun getCacheDir(): File = dir
        override fun getExternalFilesDir(type: String?): File = dir
    }

    /** A Tools wired to a fresh temp workspace, plus that workspace root. */
    private fun freshTools(): Pair<Tools, File> {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "vega-filetest-" + System.nanoTime()
        )
        truth(dir.mkdirs(), "could not create temp workspace " + dir.absolutePath)
        return Tools(Ctx(dir)) to dir
    }

    private fun run(tools: Tools, name: String, args: JSONObject): String =
        tools.run(name, args, CancellationToken())

    private fun writeArgs(path: String, content: String): JSONObject =
        JSONObject().put("path", path).put("content", content)

    // ---- the tests ----------------------------------------------------------

    /**
     * The headline bug: a call that names no path at all used to resolve to
     * the workspace root, and a file-only tool then reported "that path is a
     * folder, not a file" — or worse, acted on the root. Now it is a clear
     * error and nothing is touched.
     */
    private fun testMissingPathIsAnErrorNotTheRoot() {
        val (tools, root) = freshTools()
        val result = run(tools, "write_file", JSONObject().put("content", "x"))
        truth(result.startsWith("ERROR:"), "missing path was not an error: $result")
        truth(
            result.contains("needs a") && result.contains("path"),
            "missing-path error does not name the problem: $result"
        )
        truth(
            root.listFiles()?.isEmpty() != false,
            "a missing path wrote something into the workspace root"
        )
    }

    private fun testBlankPathIsAnError() {
        val (tools, _) = freshTools()
        val result = run(tools, "read_file", JSONObject().put("path", "   "))
        truth(result.startsWith("ERROR:"), "blank path was not an error: $result")
    }

    /**
     * Two aliases naming two different paths: acting on one and silently
     * dropping the other is data confusion. Refuse, naming both.
     */
    private fun testConflictingAliasesAreRefused() {
        val (tools, _) = freshTools()
        val args = JSONObject().put("path", "a.txt").put("file", "b.txt")
            .put("content", "x")
        val result = run(tools, "write_file", args)
        truth(result.startsWith("ERROR:"), "conflicting aliases were not refused: $result")
        truth(
            result.contains("a.txt") && result.contains("b.txt"),
            "conflict error does not name both paths: $result"
        )
    }

    /**
     * The model's most common path mistake: {"path": "<a directory>",
     * "filename": "<a name>"}. The name used to be silently dropped and the
     * call failed with "that path is a folder, not a file".
     */
    private fun testDirPlusFilenameIsJoined() {
        val (tools, root) = freshTools()
        truth(File(root, "sub").mkdir(), "could not create subdir")
        val args = JSONObject().put("path", "sub").put("filename", "notes.txt")
            .put("content", "hello")
        val result = run(tools, "write_file", args)
        truth(result.startsWith("OK:"), "dir+filename was not joined: $result")
        truth(
            File(root, "sub/notes.txt").readText() == "hello",
            "joined file does not have the expected content"
        )
    }

    /** Deleting the workspace root itself is never what a delete means. */
    private fun testRootDeleteIsRefused() {
        val (tools, root) = freshTools()
        val result = run(tools, "delete_path", JSONObject().put("path", root.absolutePath))
        truth(result.startsWith("ERROR:"), "root delete was not refused: $result")
        truth(
            result.contains("refusing") || result.contains("root"),
            "root-delete refusal is not explicit: $result"
        )
        truth(root.isDirectory, "the workspace root was deleted!")
    }

    /** Moving the workspace root itself is never what a move means. */
    private fun testRootMoveIsRefused() {
        val (tools, root) = freshTools()
        val args = JSONObject().put("from", root.absolutePath)
            .put("to", root.absolutePath + "/moved")
        val result = run(tools, "move_path", args)
        truth(result.startsWith("ERROR:"), "root move was not refused: $result")
        truth(root.isDirectory, "the workspace root was moved!")
    }

    /**
     * A symlink to a directory is deleted as a LINK. The old recursive
     * delete followed it and wiped the target tree.
     */
    private fun testDeleteDoesNotFollowDirectorySymlink() {
        val (tools, root) = freshTools()
        val real = File(root, "real")
        truth(real.mkdirs(), "could not create real dir")
        File(real, "secret.txt").writeText("precious")
        val link = root.toPath().resolve("link")
        try {
            Files.createSymbolicLink(link, real.toPath())
        } catch (e: UnsupportedOperationException) {
            return // platform cannot make symlinks; nothing to test
        }
        val result = run(tools, "delete_path", JSONObject().put("path", "link"))
        truth(result.startsWith("OK:"), "symlink delete failed: $result")
        truth(Files.notExists(link), "the symlink itself was not deleted")
        truth(
            File(real, "secret.txt").readText() == "precious",
            "delete followed the symlink and destroyed the target tree"
        )
    }

    /** A move with no source is an error, not a resolve-to-root surprise. */
    private fun testMoveRejectsMissingSource() {
        val (tools, _) = freshTools()
        val args = JSONObject().put("from", "nope.txt").put("to", "x.txt")
        val result = run(tools, "move_path", args)
        truth(result.startsWith("ERROR:"), "missing source was not an error: $result")
        truth(result.contains("nope"), "missing-source error does not name the source: $result")
    }

    private fun testMoveNeedsBothEnds() {
        val (tools, _) = freshTools()
        val result = run(tools, "move_path", JSONObject().put("from", "a.txt"))
        truth(result.startsWith("ERROR:"), "one-ended move was not an error: $result")
    }

    /**
     * rename() atomically REPLACES an existing destination on Linux, so an
     * unchecked move silently destroys whatever was there. Refuse unless the
     * caller says overwrite:true explicitly.
     */
    private fun testMoveRefusesExistingDestinationWithoutOverwrite() {
        val (tools, root) = freshTools()
        File(root, "a.txt").writeText("aaa")
        File(root, "b.txt").writeText("bbb")
        val args = JSONObject().put("from", "a.txt").put("to", "b.txt")
        val result = run(tools, "move_path", args)
        truth(result.startsWith("ERROR:"), "clobbering move was not refused: $result")
        truth(
            result.contains("overwrite"),
            "refusal does not name the overwrite escape hatch: $result"
        )
        truth(File(root, "b.txt").readText() == "bbb", "the destination was clobbered")
        truth(File(root, "a.txt").exists(), "the source vanished on a refused move")
    }

    private fun testMoveOverwriteReplacesDestination() {
        val (tools, root) = freshTools()
        File(root, "a.txt").writeText("aaa")
        File(root, "b.txt").writeText("bbb")
        val args = JSONObject().put("from", "a.txt").put("to", "b.txt")
            .put("overwrite", true)
        val result = run(tools, "move_path", args)
        truth(result.startsWith("OK:"), "overwrite move failed: $result")
        truth(File(root, "b.txt").readText() == "aaa", "destination was not replaced")
        truth(!File(root, "a.txt").exists(), "source still exists after move")
    }

    /**
     * Percent-encoded paths still arrive (an older prompt told the model to
     * write %20 for spaces). When the literal spelling does not exist, the
     * decoded spelling is tried.
     */
    private fun testPercentEncodedPathDecodes() {
        val (tools, root) = freshTools()
        val result = run(tools, "write_file", writeArgs("my%20file.txt", "x"))
        truth(result.startsWith("OK:"), "encoded write failed: $result")
        truth(File(root, "my file.txt").exists(), "%20 was not decoded to a space")
    }

    /**
     * But a literal '+' is a real file-name character, not an encoded space:
     * java.net.URLDecoder would corrupt it, so decoding is done by hand.
     */
    private fun testLiteralPlusIsPreserved() {
        val (tools, root) = freshTools()
        val result = run(tools, "write_file", writeArgs("a+b.txt", "x"))
        truth(result.startsWith("OK:"), "plus write failed: $result")
        truth(File(root, "a+b.txt").exists(), "literal + was mangled")
    }

    /**
     * The /storage/emulated/0 failure: an absolute path outside the workspace
     * is refused with the allowed root named — never silently remapped, never
     * acted on.
     */
    private fun testOutsideWorkspaceIsRefused() {
        val (tools, root) = freshTools()
        val result = run(
            tools, "write_file",
            writeArgs("/storage/emulated/0/outside.txt", "x")
        )
        truth(result.startsWith("ERROR:"), "outside path was not refused: $result")
        truth(
            result.contains("outside the workspace") || result.contains(root.canonicalPath),
            "refusal does not explain the boundary: $result"
        )
        truth(
            !File("/storage/emulated/0/outside.txt").exists(),
            "a file was written outside the workspace!"
        )
    }

    /** edit_file on a directory names the mistake instead of patching it. */
    private fun testEditOnDirectoryNamesTheMistake() {
        val (tools, root) = freshTools()
        truth(File(root, "sub").mkdir(), "could not create subdir")
        val args = JSONObject().put("path", "sub")
            .put("old_string", "a").put("new_string", "b")
        val result = run(tools, "edit_file", args)
        truth(result.startsWith("ERROR:"), "directory edit was not an error: $result")
        truth(
            result.contains("folder") || result.contains("directory"),
            "error does not say it is a folder: $result"
        )
    }

    /**
     * edit_file demands read_file first (old_string must match byte for
     * byte); the read-then-edit sequence works end to end.
     */
    private fun testReadBeforeEditThenEditWorks() {
        val (tools, root) = freshTools()
        File(root, "doc.txt").writeText("alpha\nbeta\n")
        val blind = run(
            tools, "edit_file",
            JSONObject().put("path", "doc.txt")
                .put("old_string", "alpha").put("new_string", "ALPHA")
        )
        truth(blind.startsWith("ERROR:"), "blind edit was not refused: $blind")
        truth(blind.contains("read_file"), "blind-edit error does not name read_file: $blind")
        val read = run(tools, "read_file", JSONObject().put("path", "doc.txt"))
        truth(!read.startsWith("ERROR:"), "read_file failed: $read")
        val edit = run(
            tools, "edit_file",
            JSONObject().put("path", "doc.txt")
                .put("old_string", "alpha").put("new_string", "ALPHA")
        )
        truth(edit.startsWith("OK:"), "edit after read failed: $edit")
        truth(
            File(root, "doc.txt").readText() == "ALPHA\nbeta\n",
            "edit did not apply cleanly"
        )
    }

    private fun testWriteReadRoundTrip() {
        val (tools, root) = freshTools()
        val written = run(tools, "write_file", writeArgs("hello.txt", "hi there"))
        truth(written.startsWith("OK:"), "write failed: $written")
        val read = run(tools, "read_file", JSONObject().put("path", "hello.txt"))
        truth(read.contains("hi there"), "read did not return the content: $read")
        truth(!read.startsWith("ERROR:"), "read was an error: $read")
    }

    private fun testDeleteMissingIsAnError() {
        val (tools, _) = freshTools()
        val result = run(tools, "delete_path", JSONObject().put("path", "ghost.txt"))
        truth(result.startsWith("ERROR:"), "deleting a missing file was not an error: $result")
    }

    /** make_dir is idempotent: creating an existing directory is fine. */
    private fun testMakeDirExistingIsOk() {
        val (tools, root) = freshTools()
        truth(File(root, "d").mkdir(), "could not create dir")
        val result = run(tools, "make_dir", JSONObject().put("path", "d"))
        truth(
            !result.startsWith("ERROR:"),
            "re-creating an existing directory was an error: $result"
        )
    }
}
