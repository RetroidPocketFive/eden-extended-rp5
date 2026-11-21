// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File

/**
 * Collects Android's own record of the previous app-process exit. This runs on
 * the next launch because Android only exposes historical exit information
 * after the process has terminated.
 */
object Rp5CrashReporter {
    private const val REPORT_FILE = "rp5-crash-report.txt"
    private const val PREFS = "rp5_crash_reporter"
    private const val PREF_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"

    fun collectPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val context = YuzuApplication.appContext
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        }.getOrNull() ?: return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRecorded = prefs.getLong(PREF_LAST_EXIT_TIMESTAMP, 0L)
        val exit = exits.firstOrNull { it.timestamp > lastRecorded } ?: return

        // Ignore clean exits and user-requested stops. Keep resource kills,
        // Java/native crashes, ANRs, signals and unknown abnormal exits.
        val interestingReasons = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER
        )
        prefs.edit().putLong(PREF_LAST_EXIT_TIMESTAMP, exit.timestamp).apply()
        if (exit.reason !in interestingReasons) return

        val report = buildString {
            appendLine("Eden Android process-exit report")
            appendLine("Captured: ${java.util.Date().toString()}")
            appendLine("Package: ${context.packageName}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Previous process exit time: ${java.util.Date(exit.timestamp)}")
            appendLine("Exit reason: ${reasonName(exit.reason)} (${exit.reason})")
            appendLine("Exit status: ${exit.status}")
            appendLine("Process: ${exit.processName}")
            appendLine("Description: ${exit.description ?: "unavailable"}")
            appendLine("Importance: ${exit.importance}")
            appendLine("PSS: ${exit.pss} KB")
            appendLine("RSS: ${exit.rss} KB")
            appendLine()
            appendLine("Native crash trace (if Android supplied one):")
            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            appendLine(trace?.takeIf { it.isNotBlank() } ?: "No trace available from Android.")
            appendLine()
            appendLine("Current process diagnostics:")
            appendLine("PID: ${Process.myPid()}")
            appendLine("Process PSS: ${runCatching { Debug.getPss() }.getOrDefault(-1)} KB")
            appendLine("Elapsed realtime: ${SystemClock.elapsedRealtime()} ms")
            appendLine("Note: Android may not expose full driver/system logs to ordinary apps.")
        }

        runCatching {
            File(context.filesDir, REPORT_FILE).writeText(report)
            Log.warning("[RP5-Diagnostics] Previous abnormal process exit captured: ${reasonName(exit.reason)}")
        }.onFailure {
            Log.error("[RP5-Diagnostics] Could not save process-exit report: ${it.message}")
        }
    }

    fun hasPendingReport(context: Context = YuzuApplication.appContext): Boolean =
        File(context.filesDir, REPORT_FILE).isFile

    fun readReport(context: Context = YuzuApplication.appContext): String? {
        val previousExit = runCatching {
            File(context.filesDir, REPORT_FILE).takeIf { it.isFile }?.readText()
        }.getOrNull()
        val javaCrash = Rp5Diagnostics.readJavaCrash()
        val events = Rp5Diagnostics.readEvents()
        if (previousExit == null && javaCrash == null && events == "No event breadcrumbs recorded.") {
            return null
        }
        return buildString {
            if (javaCrash != null) {
                appendLine(javaCrash)
                appendLine()
            }
            if (previousExit != null) {
                appendLine(previousExit)
                appendLine()
            }
            appendLine("Recent RP5 event log:")
            appendLine(events)
        }
    }

    fun clearReport(context: Context = YuzuApplication.appContext) {
        runCatching { File(context.filesDir, REPORT_FILE).delete() }
        Rp5Diagnostics.clearJavaCrash()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "Process killed by signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        ApplicationExitInfo.REASON_OTHER -> "Other"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Normal self exit"
        else -> "Unknown"
    }
}
