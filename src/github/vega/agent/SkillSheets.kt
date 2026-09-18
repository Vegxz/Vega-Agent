package github.vega.agent

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Add skill" flow behind Settings → "Tools and access".
 *
 * Four sheets, one story:
 *  1. [startAddSkill] — gates (endpoint configured? storage access?) then…
 *  2. folder picker — a dedicated, folders-only in-app browser (breadcrumb,
 *     pinned "use this folder" pill, per-folder quick-pick ticks) in the same
 *     visual language as [FileBrowser]; the chosen folder is remembered and a
 *     `Vega Skills` directory is created inside it.
 *  3. GitHub URL sheet — the user pastes the skill repo's link.
 *  4. install sheet — live progress while the repo is fetched from GitHub, the
 *     user's own configured model analyzes it into skill definition(s), and
 *     each is saved under `Vega Skills/`.
 *
 * All network and AI work runs on a background thread; every UI touch goes
 * through [ui]. [onChanged] refreshes the installed-skills list in Settings.
 */
class SkillSheets(
    private val activity: Activity,
    private val prefs: Prefs,
    private val ui: Handler,
    private val say: (String, Boolean) -> Unit,
    private val onChanged: () -> Unit
) {

    // ---- entry -----------------------------------------------------------

    /** "Add skill" button. */
    fun startAddSkill() {
        if (!prefs.isConfigured()) {
            val sheet = Sheet(activity)
            sheet.header("plug", Fa.SKILL_ADD, null)
            sheet.body.addView(noteText(Fa.SKILL_NEEDS_SETUP))
            sheet.body.addView(
                Ui.pillButton(activity, Fa.SKILL_CLOSE, null, Ui.PRIMARY) {
                    sheet.dismiss()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            sheet.show()
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            storagePermissionSheet { pickFolder { onFolderForNewSkill(it) } }
            return
        }
        pickFolder { onFolderForNewSkill(it) }
    }

    /** "Change folder" row: re-pick the folder without starting an import. */
    fun changeFolder() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            storagePermissionSheet { pickFolder { onFolderPicked(it) } }
            return
        }
        pickFolder { onFolderPicked(it) }
    }

    private fun onFolderForNewSkill(dir: File) {
        onFolderPicked(dir)
        askGitHubUrl()
    }

    private fun onFolderPicked(dir: File) {
        prefs.setSkillsFolder(dir.absolutePath)
        try {
            File(dir, Skills.DIR_NAME).mkdirs()
        } catch (ignored: Exception) {
        }
        onChanged()
    }

    // ---- storage permission ----------------------------------------------

    private fun storagePermissionSheet(continueAction: () -> Unit) {
        val sheet = Sheet(activity)
        sheet.header("shield", Fa.PERM_TITLE, Fa.PERM_MSG)
        sheet.body.addView(noteText(Fa.PERM_HINT))
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(activity)
        val later = Ui.pillButton(activity, Fa.PERM_LATER, null, Ui.SECONDARY) {
            sheet.dismiss()
            continueAction()
        }
        val laterLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        laterLp.marginEnd = Theme.dp(activity, 8.0f)
        row.addView(later, laterLp)
        row.addView(
            Ui.pillButton(activity, Fa.PERM_GRANT, "check", Ui.PRIMARY) {
                sheet.dismiss()
                openAllFilesSettings()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }


    private fun openAllFilesSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            } else {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (ignored: Exception) {
            }
        }
    }

    // ---- folder picker -----------------------------------------------------

    /**
     * Folders-only in-app browser. Same visual language as [FileBrowser] —
     * breadcrumb pill, pinned primary "use this folder" pill, per-folder
     * quick-pick ticks — but files are not shown at all, because this sheet
     * picks a folder and nothing else.
     */
    private fun pickFolder(onPick: (File) -> Unit) {
        val root = Tools.externalRoot(activity)
        val sheet = Sheet(activity)
        sheet.header("folder", Fa.SKILL_PICK_TITLE, Fa.SKILL_PICK_SUB)

        val crumbWrap = HorizontalScrollView(activity)
        crumbWrap.isHorizontalScrollBarEnabled = false
        val crumb = LinearLayout(activity)
        crumb.orientation = LinearLayout.HORIZONTAL
        crumb.gravity = Gravity.CENTER_VERTICAL
        crumb.layoutDirection = Lang.direction(activity)
        crumbWrap.addView(crumb)
        val crumbLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        crumbLp.bottomMargin = Theme.dp(activity, 10.0f)
        sheet.body.addView(crumbWrap, crumbLp)

        val scroll = ScrollView(activity)
        scroll.isVerticalScrollBarEnabled = false
        val list = LinearLayout(activity)
        list.orientation = LinearLayout.VERTICAL
        list.layoutDirection = Lang.direction(activity)
        scroll.addView(list)
        sheet.body.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight())
        )

        var cur: File = root
        fun refresh() {
            crumb.removeAllViews()
            list.removeAllViews()

            // Breadcrumb: "Storage" + path relative to the root.
            val rootPath = root.absolutePath
            val label = if (cur.absolutePath == rootPath) {
                "Storage"
            } else {
                "Storage" + cur.absolutePath.removePrefix(rootPath)
            }
            val crumbRow = LinearLayout(activity)
            crumbRow.orientation = LinearLayout.HORIZONTAL
            crumbRow.gravity = Gravity.CENTER_VERTICAL
            crumbRow.background = Theme.chip(Theme.R_PILL, activity)
            val padH = Theme.dp(activity, Ui.Space.M)
            val padV = Theme.dp(activity, 7.0f)
            crumbRow.setPadding(padH, padV, padH, padV)
            val pin = ImageView(activity)
            pin.setImageDrawable(Icons.of("hard-drive", Theme.TEXT_FAINT, Ui.STROKE))
            pin.scaleType = ImageView.ScaleType.FIT_CENTER
            val pinSize = Theme.dp(activity, 14.0f)
            val pinLp = LinearLayout.LayoutParams(pinSize, pinSize)
            pinLp.marginEnd = Theme.dp(activity, Ui.Space.S)
            crumbRow.addView(pin, pinLp)
            val crumbText = TextView(activity)
            crumbText.text = label
            crumbText.setTextColor(Theme.TEXT_MUTED)
            crumbText.textSize = Ui.Type.MICRO
            crumbText.typeface = Theme.mono()
            crumbText.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            crumbRow.addView(crumbText)
            crumb.addView(crumbRow)

            // Pinned primary action: use the folder we are looking at.
            val pickHere = Ui.pillButton(activity, Fa.SKILL_PICK_HERE, "check", Ui.PRIMARY) {
                sheet.dismiss()
                onPick(cur)
            }
            val pickLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            pickLp.bottomMargin = Theme.dp(activity, 10.0f)
            list.addView(pickHere, pickLp)

            if (cur.absolutePath != rootPath) {
                list.addView(
                    browserRow("corner-up-left", Fa.BROWSER_UP, Theme.TEXT_MUTED) {
                        val parent = cur.parentFile
                        if (parent != null && parent.absolutePath.startsWith(rootPath)) {
                            cur = parent
                        }
                        refresh()
                    }
                )
            }

            val children = try {
                cur.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            } catch (ignored: Exception) {
                null
            }?.sortedWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })

            if (children.isNullOrEmpty()) {
                val empty = TextView(activity)
                empty.typeface = Theme.ui()
                empty.text = Fa.BROWSER_EMPTY
                empty.setTextColor(Theme.TEXT_FAINT)
                empty.textSize = Ui.Type.META
                empty.gravity = Gravity.CENTER
                empty.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, activity)
                val pad = Theme.dp(activity, Ui.Space.XXL)
                empty.setPadding(pad, pad, pad, pad)
                val emptyLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                emptyLp.topMargin = Theme.dp(activity, 8.0f)
                list.addView(empty, emptyLp)
                return
            }

            for (folder in children) {
                list.addView(folderRow(folder,
                    onEnter = {
                        cur = folder
                        refresh()
                    },
                    onPickFolder = {
                        sheet.dismiss()
                        onPick(folder)
                    }
                ))
            }
        }
        refresh()
        sheet.show()
    }

    /** One folder row: tap the row to enter, tap the tick to pick it. */
    private fun folderRow(
        folder: File,
        onEnter: () -> Unit,
        onPickFolder: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Theme.rippleTransparent(Theme.R_MD, activity)
        row.minimumHeight = Theme.dp(activity, 48.0f)
        row.setPaddingRelative(
            Theme.dp(activity, Ui.Space.M), Theme.dp(activity, Ui.Space.S),
            Theme.dp(activity, Ui.Space.XS), Theme.dp(activity, Ui.Space.S)
        )

        val glyph = ImageView(activity)
        glyph.setImageDrawable(Icons.of("folder", Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(activity, Ui.Space.XL)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(activity, Ui.Space.L)
        row.addView(glyph, glyphLp)

        val text = TextView(activity)
        text.text = folder.name
        text.setTextColor(Theme.TEXT)
        text.textSize = Ui.Type.BODY
        text.typeface = Theme.ui()
        text.setSingleLine(true)
        text.ellipsize = TextUtils.TruncateAt.MIDDLE
        text.textDirection = View.TEXT_DIRECTION_LTR
        text.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val pick = ImageView(activity)
        pick.setImageDrawable(Icons.of("check", Theme.TEXT_MUTED, Ui.STROKE))
        pick.scaleType = ImageView.ScaleType.FIT_CENTER
        pick.contentDescription = Fa.SKILL_PICK_HERE
        val pickBox = Theme.dp(activity, 44.0f)
        val pickPad = Theme.dp(activity, Ui.Space.M)
        pick.setPadding(pickPad, pickPad, pickPad, pickPad)
        pick.background = Theme.rippleTransparent(Theme.R_PILL, activity)
        pick.setOnClickListener { onPickFolder() }
        row.addView(pick, LinearLayout.LayoutParams(pickBox, pickBox))

        row.setOnClickListener { onEnter() }
        Ui.pressScale(row)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = Theme.dp(activity, 2.0f)
        row.layoutParams = lp
        return row
    }

    /** Generic tappable browser row (parent-folder, etc.). */
    private fun browserRow(
        icon: String,
        label: String,
        color: Int,
        action: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Theme.rippleTransparent(Theme.R_MD, activity)
        row.minimumHeight = Theme.dp(activity, 48.0f)
        row.setPaddingRelative(
            Theme.dp(activity, Ui.Space.M), Theme.dp(activity, Ui.Space.S),
            Theme.dp(activity, Ui.Space.M), Theme.dp(activity, Ui.Space.S)
        )
        val glyph = ImageView(activity)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(activity, Ui.Space.XL)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(activity, Ui.Space.L)
        row.addView(glyph, glyphLp)
        val text = TextView(activity)
        text.text = label
        text.setTextColor(color)
        text.textSize = Ui.Type.BODY
        text.typeface = Theme.ui()
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        row.setOnClickListener { action() }
        Ui.pressScale(row)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = Theme.dp(activity, 2.0f)
        row.layoutParams = lp
        return row
    }

    private fun listHeight(): Int {
        val screen = activity.resources.displayMetrics.heightPixels
        val ceiling = Theme.dp(activity, 440.0f).coerceAtMost((screen * 0.62f).toInt())
        val floor = Theme.dp(activity, 260.0f).coerceAtMost(ceiling)
        return (screen * 0.5f).toInt().coerceIn(floor, ceiling)
    }

    // ---- GitHub URL ----------------------------------------------------------

    private fun askGitHubUrl() {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val sheet = Sheet(activity)
        sheet.header("globe", Fa.SKILL_URL_TITLE, Fa.SKILL_URL_SUB)

        val input = EditText(activity)
        input.typeface = Theme.ui()
        input.setTextColor(Theme.TEXT)
        input.setHintTextColor(Theme.TEXT_FAINT)
        input.textSize = Ui.Type.LABEL
        input.hint = Fa.SKILL_URL_HINT
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.isSingleLine = true
        input.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, activity)
        val pad = Theme.dp(activity, Ui.Space.M)
        input.setPadding(pad, pad, pad, pad)
        input.textDirection = View.TEXT_DIRECTION_LTR
        val inputLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        inputLp.bottomMargin = Theme.dp(activity, Ui.Space.L)
        sheet.body.addView(input, inputLp)

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(activity)
        val cancel = Ui.pillButton(activity, Fa.SKILL_CANCEL, null, Ui.SECONDARY) {
            sheet.dismiss()
        }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(activity, 8.0f)
        row.addView(cancel, cancelLp)
        row.addView(
            Ui.pillButton(activity, Fa.SKILL_ANALYZE, "wand", Ui.PRIMARY) {
                val ref = Skills.parseGitHubUrl(input.text?.toString())
                if (ref == null) {
                    say(Fa.SKILL_URL_BAD, false)
                    return@pillButton
                }
                sheet.dismiss()
                runInstall(ref)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    // ---- install ---------------------------------------------------------------

    private fun runInstall(ref: Skills.RepoRef) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val token = CancellationToken()
        val sheet = Sheet(activity)
        sheet.setCancelable(false)
        sheet.header("wand", Fa.SKILL_ADD, "github.com/" + ref.owner + "/" + ref.repo)

        val status = TextView(activity)
        status.typeface = Theme.ui()
        status.setTextColor(Theme.TEXT_MUTED)
        status.textSize = Ui.Type.LABEL
        status.text = Fa.SKILL_STEP_FETCH
        Ui.rowLabel(status)
        val statusLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        statusLp.bottomMargin = Theme.dp(activity, Ui.Space.M)
        sheet.body.addView(status, statusLp)

        val spinner = ProgressBar(activity)
        spinner.isIndeterminate = true
        val spinLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        spinLp.gravity = Gravity.CENTER_HORIZONTAL
        spinLp.bottomMargin = Theme.dp(activity, Ui.Space.L)
        sheet.body.addView(spinner, spinLp)

        sheet.body.addView(
            Ui.pillButton(activity, Fa.SKILL_STOP, null, Ui.GHOST) {
                token.cancel()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()

        val postStatus: (String) -> Unit = { text ->
            ui.post { status.text = text }
        }
        // B15: fetchRepo always analyzes the default branch — when the user
        // pasted a /tree/<branch> link, say so visibly instead of silently
        // ignoring the tail they typed.
        val branchWarning = if (ref.branch.isNullOrBlankJava()) {
            null
        } else {
            String.format(
                Lang.text(
                    activity,
                    "Branch “%s” is ignored — the repo's default branch is analyzed instead.",
                    "«شاخه %s نادیده گرفته شد — شاخه پیش‌فرض ریپو تحلیل می‌شود»"
                ),
                ref.branch
            )
        }
        Thread({
            // Notes surfaced in the result sheet (B15 branch, B16 truncation).
            val notes = ArrayList<String>()
            if (branchWarning != null) {
                notes.add(branchWarning)
            }
            try {
                postStatus(Fa.SKILL_STEP_FETCH)
                val snapshot = Skills.fetchRepo(ref, token)
                token.throwIfCancelled()
                // B16: the tree or the SKILL.md set was capped — some skills
                // may never have reached the analyzer.
                if (snapshot.treeCapped || snapshot.skillsCapped) {
                    notes.add(
                        Lang.text(
                            activity,
                            "The repo file list was truncated — some skills may have been missed.",
                            "فهرست فایل‌های ریپو ناقص بود؛ ممکن است بعضی اسکیل‌ها جا مانده باشند"
                        )
                    )
                }

                postStatus(Fa.SKILL_STEP_ANALYZE)
                val prompt = Skills.analysisPrompt(snapshot, Fa.farsi)
                val answer = LlmClient(prefs).completeOnce(
                    Skills.analysisMessages(prompt), token
                )
                token.throwIfCancelled()

                val parsed = Skills.parseAnalysisResult(answer)
                if (parsed.isEmpty()) {
                    throw java.io.IOException(Fa.SKILL_NO_SKILL)
                }
                token.throwIfCancelled()

                postStatus(Fa.SKILL_STEP_INSTALL)
                val sourceUrl = "https://github.com/" + ref.owner + "/" + ref.repo
                val installed = ArrayList<Skills.Info>()
                for (skill in parsed) {
                    token.throwIfCancelled()
                    installed.add(
                        Skills.install(
                            activity, prefs,
                            skill.name, skill.description, skill.instructions,
                            sourceUrl, skill.allowedTools
                        )
                    )
                }
                // Kebab-case auto-fixes are a WARN, not a silent mangle.
                for (info in installed) {
                    if (info.renamedFrom.isNotBlankJava()) {
                        notes.add(
                            String.format(
                                Lang.text(
                                    activity,
                                    "Renamed “%s” to “%s” (skill names must be kebab-case).",
                                    "«%s» به «%s» تغییر نام یافت (نام اسکیل باید kebab-case باشد)"
                                ),
                                info.renamedFrom, info.name
                            )
                        )
                    }
                }
                ui.post {
                    // B5: the install outlived the screen — the skills still
                    // landed on disk, so refresh anyway and announce the
                    // outcome instead of going silent.
                    onChanged()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showInstallResult(sheet, installed, null, notes)
                    } else {
                        sheet.dismiss()
                        sayInstalled(installed.size)
                    }
                }
            } catch (cancelled: CancellationToken.CancelledException) {
                ui.post { sheet.dismiss() }
            } catch (e: Exception) {
                ui.post {
                    onChanged()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showInstallResult(sheet, null, e, notes)
                    } else {
                        sheet.dismiss()
                        sayFailed(e)
                    }
                }
            }
        }, "vega-skill-install").apply { isDaemon = true }.start()
    }

    /** B5: announces a finished install when the settings screen is gone. */
    private fun sayInstalled(count: Int) {
        try {
            say(
                if (count == 1) {
                    Fa.SKILL_DONE
                } else {
                    String.format(Fa.SKILL_DONE_N, localizeDigits(count.toString()))
                },
                false
            )
        } catch (ignored: Exception) {
            // Toasting on a dead Activity is best-effort.
        }
    }

    /** B5: announces a failed install when the settings screen is gone. */
    private fun sayFailed(e: Exception) {
        try {
            val detail = e.message?.trimJava()
            say(
                Fa.SKILL_FAILED + if (detail.isNullOrEmpty()) "" else ": " + detail,
                true
            )
        } catch (ignored: Exception) {
        }
    }

    private fun showInstallResult(
        sheet: Sheet,
        installed: List<Skills.Info>?,
        error: Exception?,
        notes: List<String>
    ) {
        sheet.dismiss()
        val result = Sheet(activity)
        if (installed != null && installed.isNotEmpty()) {
            val names = installed.joinToString("\n") { "• " + it.name }
            result.header("check-circle", Fa.SKILL_DONE, null)
            val msg = TextView(activity)
            msg.typeface = Theme.ui()
            msg.setTextColor(Theme.TEXT_MUTED)
            msg.textSize = Ui.Type.LABEL
            msg.text = if (installed.size == 1) {
                names
            } else {
                String.format(Fa.SKILL_DONE_N, localizeDigits(installed.size.toString())) + "\n" + names
            }
            Ui.rowLabel(msg)
            val msgLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            msgLp.bottomMargin = Theme.dp(activity, Ui.Space.L)
            result.body.addView(msg, msgLp)
            // B15/B16/rename warnings: visible notes, not silent footnotes.
            for (note in notes) {
                result.body.addView(noteText(note))
            }
            onChanged()
        } else {
            result.header("alert", Fa.SKILL_FAILED, null)
            val detail = error?.message?.trimJava()
            result.body.addView(
                noteText(
                    if (!detail.isNullOrEmpty()) detail else Fa.SKILL_FAILED
                )
            )
            for (note in notes) {
                result.body.addView(noteText(note))
            }
        }
        result.body.addView(
            Ui.pillButton(activity, Fa.SKILL_CLOSE, null, Ui.PRIMARY) {
                result.dismiss()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        result.show()
    }

    // ---- rows for the Settings card -------------------------------------------

    /**
     * One installed-skill row for the Tools & access card: wand glyph, name +
     * description, an enable/disable switch (B6), a delete button with a
     * confirm sheet, and — when the skill's folder sits outside the workspace
     * so the agent can never read it — a small warning badge (B7) instead of
     * advertising the skill silently. Tapping the row itself opens the skill
     * viewer/editor.
     */
    fun skillRow(info: Skills.Info): LinearLayout {
        val trailing = LinearLayout(activity)
        trailing.orientation = LinearLayout.HORIZONTAL
        trailing.gravity = Gravity.CENTER_VERTICAL
        trailing.layoutDirection = Lang.direction(activity)

        // B7: promptBlock() skips skills outside the workspace root — the row
        // must not pretend the agent can see them.
        if (!Skills.dirIsUsable(info.dir, Skills.workspaceRootPath(activity))) {
            val badge = TextView(activity)
            badge.text = Lang.text(
                activity, "not visible to the agent", "برای مدل قابل مشاهده نیست"
            )
            badge.setTextColor(Theme.TEXT_FAINT)
            badge.textSize = Ui.Type.MICRO
            badge.typeface = Theme.ui()
            badge.background = Theme.chip(Theme.R_PILL, activity)
            val bpH = Theme.dp(activity, Ui.Space.S)
            val bpV = Theme.dp(activity, 3.0f)
            badge.setPadding(bpH, bpV, bpH, bpV)
            val badgeLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            badgeLp.marginEnd = Theme.dp(activity, Ui.Space.S)
            trailing.addView(badge, badgeLp)
        }

        // B6: the enable switch. Same tint language as the settings toggles.
        val toggle = Switch(activity)
        toggle.isChecked = info.enabled
        toggle.contentDescription = Lang.text(activity, "Skill enabled", "اسکیل فعال باشد")
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        toggle.thumbTintList = ColorStateList(
            states, intArrayOf(Theme.ACCENT, if (Theme.DARK) Theme.TEXT_FAINT else Theme.SURFACE)
        )
        toggle.trackTintList = ColorStateList(
            states,
            intArrayOf(
                Theme.alpha(Theme.ACCENT, 110), Theme.alpha(Theme.TEXT_FAINT, 70)
            )
        )
        toggle.setOnCheckedChangeListener { view, isChecked ->
            Ui.tick(view)
            Thread({
                val failed = try {
                    Skills.setEnabled(info, isChecked)
                    false
                } catch (ignored: Exception) {
                    true
                }
                ui.post {
                    // B6 hardening: a failed toggle must say so — otherwise
                    // the switch silently snaps back on refresh and the user
                    // never learns the setting did not stick.
                    if (failed && !activity.isFinishing && !activity.isDestroyed) {
                        try {
                            say(
                                Lang.text(
                                    activity,
                                    "Could not save the skill setting",
                                    "ذخیره تنظیم اسکیل ناموفق بود"
                                ),
                                true
                            )
                        } catch (ignored: Exception) {
                        }
                    }
                    onChanged()
                }
            }, "vega-skill-toggle").apply { isDaemon = true }.start()
        }
        val toggleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        toggleLp.marginEnd = Theme.dp(activity, Ui.Space.XS)
        trailing.addView(toggle, toggleLp)

        val trash = ImageView(activity)
        trash.setImageDrawable(Icons.of("trash", Theme.TEXT_MUTED, Ui.STROKE))
        trash.scaleType = ImageView.ScaleType.FIT_CENTER
        trash.contentDescription = Fa.ACT_DELETE
        val box = Theme.dp(activity, 44.0f)
        val tpad = Theme.dp(activity, Ui.Space.M)
        trash.setPadding(tpad, tpad, tpad, tpad)
        trash.background = Theme.rippleTransparent(Theme.R_PILL, activity)
        trash.setOnClickListener { confirmDelete(info) }
        Ui.pressScale(trash)
        trailing.addView(trash, LinearLayout.LayoutParams(box, box))

        val desc: String? = if (info.description.isEmpty()) null else info.description
        return Ui.cardRow(activity, "wand", info.name, desc, trailing,
            Runnable { showSkillDetail(info) })
    }

    private fun confirmDelete(info: Skills.Info) {
        val sheet = Sheet(activity)
        sheet.header("trash", info.name, Fa.SKILL_DELETE_ASK)
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(activity)
        val cancel = Ui.pillButton(activity, Fa.SKILL_CANCEL, null, Ui.SECONDARY) {
            sheet.dismiss()
        }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(activity, 8.0f)
        row.addView(cancel, cancelLp)
        row.addView(
            Ui.pillButton(activity, Fa.ACT_DELETE, "trash", Ui.DANGER) {
                sheet.dismiss()
                // B4: deleting walks the whole skill folder — off the UI thread.
                Thread({
                    val failure = Skills.delete(info)
                    ui.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            if (failure == null) {
                                say(Fa.SKILL_DELETED, false)
                            } else {
                                // B3: a failed delete used to vanish silently.
                                say(
                                    Lang.text(
                                        activity,
                                        "Could not delete the skill",
                                        "حذف اسکیل ناموفق بود"
                                    ) + ": " + failure,
                                    true
                                )
                            }
                        }
                        onChanged()
                    }
                }, "vega-skill-delete").apply { isDaemon = true }.start()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    // ---- view & edit (B6) --------------------------------------------------------

    /**
     * Tapping a skill row opens its viewer: name, description, source URL,
     * installed date, allowed tools and the full SKILL.md instructions, with
     * an Edit button. The SKILL.md read is disk I/O, so it happens off the UI
     * thread and the sheet is built on arrival.
     */
    private fun showSkillDetail(info: Skills.Info) {
        Thread({
            val parsed = try {
                Skills.parseSkillMarkdown(
                    File(info.dir, Skills.SKILL_FILE).readText(StandardCharsets.UTF_8)
                )
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@post
                }
                showSkillDetailSheet(info, parsed)
            }
        }, "vega-skill-read").apply { isDaemon = true }.start()
    }

    private fun showSkillDetailSheet(info: Skills.Info, parsed: Skills.ParsedSkill?) {
        val sheet = Sheet(activity)
        val subtitle = if (info.description.isEmpty()) null else info.description
        sheet.header("wand", info.name, subtitle)

        sheet.body.addView(
            metaRow(
                Lang.text(activity, "Source", "منبع"),
                info.sourceUrl.ifEmpty { "—" }
            )
        )
        sheet.body.addView(
            metaRow(
                Lang.text(activity, "Installed", "نصب"),
                formatInstalledAt(info.installedAt)
            )
        )
        sheet.body.addView(
            metaRow(
                Lang.text(activity, "Tools", "ابزارها"),
                info.allowedTools.joinToString(", ").ifEmpty { "—" }
            )
        )
        sheet.body.addView(
            metaRow(
                Lang.text(activity, "State", "وضعیت"),
                if (info.enabled) {
                    Lang.text(activity, "Enabled", "فعال")
                } else {
                    Lang.text(activity, "Disabled", "غیرفعال")
                }
            )
        )

        val cap = TextView(activity)
        cap.typeface = Theme.ui()
        cap.text = Lang.text(activity, "Instructions", "دستورالعمل")
        cap.setTextColor(Theme.TEXT_FAINT)
        cap.textSize = Ui.Type.META
        Ui.rowLabel(cap)
        val capLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        capLp.topMargin = Theme.dp(activity, Ui.Space.M)
        capLp.bottomMargin = Theme.dp(activity, 4.0f)
        sheet.body.addView(cap, capLp)

        val body = TextView(activity)
        body.typeface = Theme.mono()
        body.text = parsed?.instructions?.ifEmpty { "—" } ?: "—"
        body.setTextColor(Theme.TEXT_MUTED)
        body.textSize = Ui.Type.LABEL
        body.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        body.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, activity)
        val pad = Theme.dp(activity, Ui.Space.M)
        body.setPadding(pad, pad, pad, pad)
        val bodyLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bodyLp.bottomMargin = Theme.dp(activity, Ui.Space.L)
        sheet.body.addView(body, bodyLp)

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(activity)
        val close = Ui.pillButton(
            activity, Lang.text(activity, "Close", "بستن"), null, Ui.SECONDARY
        ) {
            sheet.dismiss()
        }
        val closeLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        closeLp.marginEnd = Theme.dp(activity, 8.0f)
        row.addView(close, closeLp)
        row.addView(
            Ui.pillButton(
                activity, Lang.text(activity, "Edit", "ویرایش"), "edit", Ui.PRIMARY
            ) {
                sheet.dismiss()
                showSkillEditor(info, parsed?.instructions ?: "")
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    /** One label/value line for the skill viewer. */
    private fun metaRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(activity)
        val key = TextView(activity)
        key.typeface = Theme.ui()
        key.text = label
        key.setTextColor(Theme.TEXT_FAINT)
        key.textSize = Ui.Type.META
        val keyLp = LinearLayout.LayoutParams(
            Theme.dp(activity, 92.0f), ViewGroup.LayoutParams.WRAP_CONTENT
        )
        keyLp.marginEnd = Theme.dp(activity, Ui.Space.S)
        row.addView(key, keyLp)
        val valView = TextView(activity)
        valView.typeface = Theme.ui()
        valView.text = value
        valView.setTextColor(Theme.TEXT_MUTED)
        valView.textSize = Ui.Type.LABEL
        valView.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        row.addView(
            valView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = Theme.dp(activity, Ui.Space.S)
        row.layoutParams = lp
        return row
    }

    private fun formatInstalledAt(millis: Long): String {
        if (millis <= 0L) {
            return "—"
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
        } catch (e: Exception) {
            "—"
        }
    }

    /**
     * The skill editor: name, description, allowed tools and instructions.
     * Save validates (readable errors, sheet stays open so nothing is lost)
     * and rewrites SKILL.md + the sidecar atomically on a background thread.
     */
    private fun showSkillEditor(info: Skills.Info, instructions: String) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val sheet = Sheet(activity)
        sheet.header(
            "wand",
            Lang.text(activity, "Edit skill", "ویرایش اسکیل"),
            info.name
        )

        val nameField = editorField(
            sheet.body,
            Lang.text(activity, "Name", "نام"),
            info.name,
            Lang.text(activity, "lowercase-words-like-this", "مثل-this"),
            false
        )
        val descField = editorField(
            sheet.body,
            Lang.text(activity, "Description", "توضیح"),
            info.description,
            Lang.text(activity, "What it does and when to use it", "چه کاری می‌کند و کی به کار می‌آید"),
            false
        )
        val toolsField = editorField(
            sheet.body,
            Lang.text(activity, "Preferred tools (optional)", "ابزارهای ترجیحی (اختیاری)"),
            info.allowedTools.joinToString(", "),
            "read_file, search_files",
            false
        )
        val instructionsField = editorField(
            sheet.body,
            Lang.text(activity, "Instructions", "دستورالعمل"),
            instructions,
            "",
            true
        )

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(activity)
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.topMargin = Theme.dp(activity, Ui.Space.L)
        row.layoutParams = rowLp
        val cancel = Ui.pillButton(
            activity, Lang.text(activity, "Cancel", "انصراف"), null, Ui.SECONDARY
        ) {
            sheet.dismiss()
        }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(activity, 8.0f)
        row.addView(cancel, cancelLp)
        row.addView(
            Ui.pillButton(
                activity, Lang.text(activity, "Save", "ذخیره"), "check", Ui.PRIMARY
            ) {
                // Capture the field contents on the UI thread — views must
                // not be touched from the worker below.
                val newName = nameField.text?.toString() ?: ""
                val newDesc = descField.text?.toString() ?: ""
                val newInstructions = instructionsField.text?.toString() ?: ""
                val newTools = Skills.parseAllowedTools(toolsField.text?.toString() ?: "")
                Thread({
                    var updated: Skills.Info? = null
                    var failure: String? = null
                    try {
                        updated = Skills.update(info, newName, newDesc, newInstructions, newTools)
                    } catch (e: Exception) {
                        val msg = e.message?.trimJava()
                        failure = if (msg.isNullOrEmpty()) e.javaClass.simpleName else msg
                    }
                    ui.post {
                        if (activity.isFinishing || activity.isDestroyed) {
                            return@post
                        }
                        if (updated != null) {
                            sheet.dismiss()
                            if (updated.renamedFrom.isNotBlankJava()) {
                                say(
                                    String.format(
                                        Lang.text(
                                            activity,
                                            "Saved as “%s” (skill names must be kebab-case).",
                                            "با نام «%s» ذخیره شد (نام اسکیل باید kebab-case باشد)"
                                        ),
                                        updated.name
                                    ),
                                    false
                                )
                            } else {
                                say(
                                    Lang.text(activity, "Skill saved", "اسکیل ذخیره شد"),
                                    false
                                )
                            }
                            onChanged()
                        } else {
                            // Validation failed: keep the sheet open so no
                            // typing is lost, and say what to fix.
                            say(failure ?: "", true)
                        }
                    }
                }, "vega-skill-update").apply { isDaemon = true }.start()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(row)
        sheet.show()
    }

    /** One labeled text field for the skill editor. */
    private fun editorField(
        parent: LinearLayout,
        label: String,
        initial: String,
        hint: String,
        multiLine: Boolean
    ): EditText {
        val caption = TextView(activity)
        caption.typeface = Theme.ui()
        caption.text = label
        caption.setTextColor(Theme.TEXT_FAINT)
        caption.textSize = Ui.Type.META
        Ui.rowLabel(caption)
        val capLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        capLp.topMargin = Theme.dp(activity, Ui.Space.M)
        capLp.bottomMargin = Theme.dp(activity, 4.0f)
        parent.addView(caption, capLp)

        val field = EditText(activity)
        field.typeface = Theme.ui()
        field.setText(initial)
        field.hint = hint
        field.setTextColor(Theme.TEXT)
        field.setHintTextColor(Theme.TEXT_FAINT)
        field.textSize = Ui.Type.LABEL
        field.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, activity)
        val pad = Theme.dp(activity, Ui.Space.M)
        field.setPadding(pad, pad, pad, pad)
        field.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (multiLine) {
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            field.gravity = Gravity.TOP or Gravity.START
            field.minLines = 8
        } else {
            field.inputType = InputType.TYPE_CLASS_TEXT
            field.isSingleLine = true
        }
        parent.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return field
    }

    // ---- small builders ----------------------------------------------------------

    private fun noteText(text: String): TextView {
        val note = TextView(activity)
        note.typeface = Theme.ui()
        note.text = text
        note.setTextColor(Theme.TEXT_MUTED)
        note.textSize = Ui.Type.LABEL
        note.setLineSpacing(Theme.dpf(activity, 3.0f), 1.0f)
        Ui.rowLabel(note)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = Theme.dp(activity, 18.0f)
        note.layoutParams = lp
        return note
    }

    /** Persian digits when the interface is Persian, like the rest. */
    private fun localizeDigits(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '0'..'9') {
                sb.append(Lang.num(activity, ch - '0'))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
