package com.vepro.code

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Process-wide safety net.
 *
 * The app draws its whole UI in code and talks to many different, sometimes
 * misbehaving, LLM gateways, so no amount of local try/catch can rule out every
 * unforeseen throw on every OEM ROM. Without a last-resort handler, any such
 * throw shows the system "Vega keeps stopping" dialog and drops the user
 * cold. This installs a default uncaught-exception handler that instead
 * relaunches the app cleanly — with a loop guard so a deterministic startup
 * crash cannot trap the user in an endless restart.
 *
 * Background worker threads (tool/LLM/download) already catch Throwable
 * themselves; this exists for the main thread and any stray thread that slips
 * through.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Always leave a trace first. Before Android 8 the FATAL EXCEPTION
            // line is logged by the default handler, so a handler that never
            // delegates would make field crashes invisible.
            try {
                Log.e("Vega", "uncaught on " + thread.name, error)
            } catch (ignored: Throwable) {
            }
            // Chat writes are asynchronous and this process is about to end on
            // either branch below, so anything still queued is microseconds from
            // being lost — the user's last turn included. Bounded, so a wedged
            // eMMC turns a crash into a slightly slower crash rather than a hang.
            try {
                ChatStore.flushPendingWrites(1500L)
            } catch (ignored: Throwable) {
            }
            val recovered = try {
                scheduleRestart(error)
            } catch (ignored: Throwable) {
                false
            }
            if (recovered) {
                // The corrupted process must not keep running. The alarm below
                // brings the app back in a fresh process.
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            } else {
                // Either the loop guard tripped, or this platform will not let a
                // dead background process relaunch itself. Defer to the platform
                // handler so the system does its normal thing (which on a
                // foreground crash includes restarting the task) instead of the
                // app silently vanishing.
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Schedules a relaunch a moment from now and returns true, or returns false
     * to let the platform handle the crash normally.
     *
     * Returns false when:
     *  - another crash happened within [LOOP_WINDOW_MS] — a deterministic loop,
     *    so the user must not be trapped restarting forever; or
     *  - the OS would block the relaunch anyway (see below).
     *
     * The alarm relaunch is an activity start from a process that is about to
     * die. Android 10 began restricting background activity starts and the old
     * grace period is gone for apps targeting 14+, so on API 31 and above the
     * PendingIntent is silently discarded — the app would just disappear, which
     * is worse than the system's own "app stopped" handling. There, the platform
     * handler is used instead.
     */
    private fun scheduleRestart(error: Throwable): Boolean {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CRASH, 0L)
        // commit(), not apply(): apply() writes on a background thread, and this
        // process is killed microseconds later — the guard would never reach
        // disk, so a deterministic startup crash could restart forever.
        prefs.edit()
            .putLong(KEY_LAST_CRASH, now)
            .putString(KEY_LAST_MESSAGE, error.javaClass.simpleName + ": " + (error.message ?: ""))
            .commit()
        if (last != 0L && now - last < LOOP_WINDOW_MS) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return false
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        var flags = PendingIntent.FLAG_ONE_SHOT
        if (Build.VERSION.SDK_INT >= 23) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pending = PendingIntent.getActivity(this, RESTART_REQUEST, launch, flags)
        val alarm = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        alarm.set(AlarmManager.RTC, now + RESTART_DELAY_MS, pending)
        return true
    }

    companion object {
        private const val CRASH_PREFS = "vepro_crash"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val RESTART_REQUEST = 8317
        private const val LOOP_WINDOW_MS = 8000L
        private const val RESTART_DELAY_MS = 400L

        /**
         * OEM background-execution screens: {manufacturer, package, activity}.
         *
         * THE ONE PLACE A MANUFACTURER CHECK IS CORRECT. Everywhere else this app
         * deliberately branches on [Build.VERSION.SDK_INT] and on capabilities
         * (does the intent resolve, is the permission held) rather than on
         * [Build.MANUFACTURER] — brand sniffing is how apps end up broken on the
         * next ROM, and every earlier "MIUI does X" special case that lived in the
         * UI layer was removed for that reason. This table is different in kind:
         * these are not behavioural workarounds, they are ADDRESSES. Xiaomi's
         * autostart list simply is at com.miui.securitycenter and nowhere else,
         * and no capability query can tell you where a proprietary settings
         * screen lives. The brand only narrows the search; the address is still
         * probed with the package manager before it is offered, so a ROM that
         * moved or dropped the screen quietly falls through to the next entry.
         *
         * Several rows per vendor on purpose: the class names moved between ROM
         * generations, so an EMUI 9 phone and an EMUI 12 phone need different
         * ones, and both are tried in newest-first order.
         */
        private val OEM_AUTOSTART: Array<Array<String>> = arrayOf(
            // Xiaomi / Redmi / POCO — MIUI & HyperOS "Autostart".
            arrayOf(
                "xiaomi", "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            arrayOf(
                "redmi", "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            arrayOf(
                "poco", "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // Huawei / Honor — EMUI & Magic UI "App launch" / "Protected apps".
            arrayOf(
                "huawei", "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            arrayOf(
                "huawei", "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            arrayOf(
                "honor", "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // OPPO / realme / OnePlus — ColorOS "Startup manager".
            arrayOf(
                "oppo", "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            arrayOf(
                "oppo", "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            arrayOf(
                "realme", "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            arrayOf(
                "oneplus", "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ),
            // vivo / iQOO — "Auto start" and the high-background-power whitelist.
            arrayOf(
                "vivo", "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            arrayOf(
                "vivo", "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            // Samsung — One UI has no autostart list; the equivalent switch is
            // "Sleeping apps" inside Device care's battery screen.
            arrayOf(
                "samsung", "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            arrayOf(
                "samsung", "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )

        /**
         * Every settings screen worth offering this user for "stop killing my
         * background work", best first.
         *
         * Android's own battery-optimisation exemption is NOT enough on MIUI, EMUI
         * or ColorOS: those ROMs run a second, proprietary autostart list that the
         * Doze intent never touches, so a long run is still killed minutes after
         * the screen goes off even with the exemption granted. There is no API for
         * that list; opening the vendor's screen and letting the user flip the
         * switch is the whole of the available remedy.
         *
         * Every entry is checked with the package manager first, so nothing that
         * would throw ActivityNotFoundException is ever returned. The last entry
         * is the generic Doze settings list, which exists on every API 23+ device
         * — so the result is effectively never empty, and a caller can hand the
         * first element straight to `startActivity`.
         *
         * Cheap enough to call from a click listener; no disk or network work.
         */
        fun autostartIntents(context: Context): List<Intent> {
            val out = mutableListOf<Intent>()
            val maker = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
            for (row in OEM_AUTOSTART) {
                if (!maker.contains(row[0])) {
                    continue
                }
                val intent = Intent()
                intent.setClassName(row[1], row[2])
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (resolves(context, intent)) {
                    out.add(intent)
                }
            }
            // Final fallback, and the only entry on a Pixel or an emulator: the
            // platform's own battery-optimisation list. Deliberately the LIST
            // screen rather than ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS —
            // that one is the direct allow-dialog the storage/battery prompt
            // already uses, and re-asking it here would show the user a dialog
            // they have answered rather than the screen they were promised.
            val generic = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            generic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolves(context, generic)) {
                out.add(generic)
            }
            return out
        }

        /**
         * True when this device actually has a vendor autostart screen — i.e.
         * [autostartIntents] found something beyond the generic Doze fallback.
         *
         * The caller's cue for whether the extra nudge is worth showing at all:
         * on a Pixel the platform exemption is the whole story and a second sheet
         * saying "your manufacturer also blocks background work" would be a lie.
         */
        fun hasOemAutostartScreen(context: Context): Boolean {
            val maker = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
            for (row in OEM_AUTOSTART) {
                if (!maker.contains(row[0])) {
                    continue
                }
                val intent = Intent()
                intent.setClassName(row[1], row[2])
                if (resolves(context, intent)) {
                    return true
                }
            }
            return false
        }

        /**
         * Opens the best available screen from [autostartIntents], falling through
         * to the next candidate if one throws anyway (a resolvable activity can
         * still be guarded by a signature permission on some ROMs).
         *
         * Returns false only when every candidate failed, which is the caller's
         * signal to say so rather than leave a button that appears to do nothing.
         */
        fun openAutostartSettings(context: Context): Boolean {
            for (intent in autostartIntents(context)) {
                try {
                    context.startActivity(intent)
                    return true
                } catch (ignored: Throwable) {
                }
            }
            return false
        }

        /** Can this device open [intent] at all? Never throws. */
        private fun resolves(context: Context, intent: Intent): Boolean = try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (ignored: Throwable) {
            false
        }
    }
}
