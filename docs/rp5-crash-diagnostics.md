# RP5 crash diagnostics

This feature is scoped to the Retroid Pocket 5 Android build and is intended to improve post-crash reports without ADB, root, or a PC.

## Captured data

- Uncaught Java/Kotlin exceptions: exception class/message, crashing thread, complete Java stack trace, device and Android version.
- Android historical process-exit record (Android 11/API 30+): exit reason/status, timestamp, process name, description, importance, PSS/RSS, and the trace stream when Android supplies one.
- Bounded event breadcrumbs: application startup and emulation Activity create/resume/pause/destroy transitions, with native running/paused state at lifecycle boundaries.

The event log is stored in app-private storage and capped at approximately 96 KiB / 512 lines. A Java crash report is capped at 256 KiB. Reports are offered for sharing only after an abnormal exit or a captured uncaught Java exception. The user must explicitly choose Share; nothing is uploaded automatically.

## Limitations

- The default uncaught-exception handler captures uncaught Java/Kotlin exceptions only. It does not intercept native SIGSEGV/SIGABRT, GPU-driver faults, or process kills.
- Android's ApplicationExitInfo trace may be absent, truncated, or unavailable for a particular exit reason/device.
- PSS/RSS in ApplicationExitInfo describe the exited process; current-process memory is not substituted for the historical values.
- The breadcrumb log is a small lifecycle timeline, not a complete logcat or per-frame GPU trace. Existing Eden log export remains useful for shader/Vulkan messages.
- A crash before Application.onCreate or before the handler is installed cannot be captured by the Java handler.

## On-device validation

1. Install the test APK and launch Eden once.
2. Launch TOTK, play briefly, pause/resume, then exit normally. Confirm no crash-report prompt appears on the next launch.
3. For handler validation only, use a dedicated debug-only test hook in a development build; do not add a user-accessible crash button to release builds.
4. After an actual crash, relaunch Eden and choose **Share report** when prompted.
5. Confirm the report contains the Java exception (when applicable), Android exit record (when available), and recent RP5 event breadcrumbs.
6. Verify **Dismiss report** clears the pending Java/previous-exit report and that no report is shared without explicit user action.

No save data, ROM contents, shader-cache payloads, or personal files are collected by this feature.
