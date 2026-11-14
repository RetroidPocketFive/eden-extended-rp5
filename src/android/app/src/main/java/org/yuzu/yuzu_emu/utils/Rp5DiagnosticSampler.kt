// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Low-frequency, local Android context samples for correlating emulator slowdowns
 * with thermal and memory pressure. Samples are written to the existing app log;
 * no network transmission or sysfs polling is performed.
 */
object Rp5DiagnosticSampler {
    private const val SAMPLE_INTERVAL_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(sampleRunnable)
        handler.post(sampleRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sampleRunnable)
    }

    private fun sample() {
        val context = YuzuApplication.appContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val processPssKb = Debug.getPss()

        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val temperatureTenthsC = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.currentThermalStatus
        } else {
            -1
        }

        Log.info(
            "[RP5-Diagnostics] AndroidContext " +
                "elapsedRealtimeMs=${android.os.SystemClock.elapsedRealtime()} " +
                "thermalStatus=${thermalStatus} " +
                "batteryTempC=${if (temperatureTenthsC >= 0) temperatureTenthsC / 10.0 else "unavailable"} " +
                "charging=${charging} " +
                "processPssKb=${processPssKb} " +
                "systemAvailableMemKb=${memoryInfo.availMem / 1024L} " +
                "systemLowMemory=${memoryInfo.lowMemory}"
        )
    }
}
