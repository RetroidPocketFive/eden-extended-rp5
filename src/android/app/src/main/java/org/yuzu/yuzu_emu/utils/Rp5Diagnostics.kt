// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.os.Build
import android.os.Process
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, bounded diagnostics store for RP5 builds.
 *
 * The event log is deliberately independent of Android logcat so it remains
 * available to the app's user-facing report exporter without ADB/root.
 */
object Rp5Diagnostics {
    private const val EVENT_FILE = "rp5-events.log"
    private const val JAVA_CRASH_FILE = "rp5-java-crash.txt"
    private const val MAX_EVENT_BYTES = 96 * 1024
    private const val MAX_EVENT_LINES = 512
    private const val MAX_CRASH_BYTES = 256 * 1024

    @Volatile private var installed = false
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun install() {
        synchronized(lock) {
            if (installed) return
            installed = true
            record("APP", "Application process started")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                record("CRASH", "Uncaught exception on thread ${thread.name} (id=${thread.id})")
                saveJavaCrash(thread, throwable)
                // Preserve Android/Eden's existing fatal-exception handling.
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    fun record(category: String, message: String) {
        val safeCategory = category.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(24)
        val safeMessage = message.replace('\n', ' ').replace('\r', ' ').take(1000)
        val line = "${timestampFormat.format(Date())} [${safeCategory}] ${safeMessage}\n"
        synchronized(lock) {
            runCatching {
                val file = File(YuzuApplication.appContext.filesDir, EVENT_FILE)
                file.parentFile?.mkdirs()
                file.appendText(line)
                trimEvents(file)
            }
        }
    }

    private fun trimEvents(file: File) {
        if (!file.exists() || file.length() <= MAX_EVENT_BYTES) return
        val lines = file.readLines()
        val retained = lines.takeLast(MAX_EVENT_LINES)
        file.writeText(retained.joinToString(separator = "\n", postfix = "\n"))
        if (file.length() > MAX_EVENT_BYTES) {
            val bytes = file.readBytes()
            file.writeBytes(bytes.copyOfRange((bytes.size - MAX_EVENT_BYTES).coerceAtLeast(0), bytes.size))
        }
    }

    private fun saveJavaCrash(thread: Thread, throwable: Throwable) {
        runCatching {
            val context = YuzuApplication.appContext
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            val report = buildString {
                appendLine("Eden uncaught Java exception")
                appendLine("Timestamp: ${Date()}")
                appendLine("Package: ${context.packageName}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name} (id=${thread.id})")
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine()
                appendLine("Full stack trace:")
                appendLine(writer.toString())
                appendLine()
                appendLine("Recent RP5 event log:")
                appendLine(readEvents())
            }
            val bytes = report.toByteArray(Charsets.UTF_8)
            FileOutputStream(File(context.filesDir, JAVA_CRASH_FILE)).use { output ->
                output.write(bytes, 0, bytes.size.coerceAtMost(MAX_CRASH_BYTES))
                output.fd.sync()
            }
        }
    }

    fun readEvents(): String = runCatching {
        File(YuzuApplication.appContext.filesDir, EVENT_FILE)
            .takeIf { it.isFile }?.readText()?.takeLast(MAX_EVENT_BYTES) ?: "No event breadcrumbs recorded."
    }.getOrDefault("Event log unavailable.")

    fun readJavaCrash(): String? = runCatching {
        File(YuzuApplication.appContext.filesDir, JAVA_CRASH_FILE)
            .takeIf { it.isFile }?.readText()
    }.getOrNull()

    fun clearJavaCrash() {
        runCatching { File(YuzuApplication.appContext.filesDir, JAVA_CRASH_FILE).delete() }
    }
}
