# RP5 TOTK performance test protocol

This protocol is for comparing development APKs on the Retroid Pocket 5. It is intentionally device-only: no ADB, root access, or desktop tooling is required.

## Test conditions

Keep these constant between builds:

- Retroid Pocket 5, Snapdragon 865 / Adreno 650.
- Same Eden graphics settings, resolution scale, accuracy settings, driver and Android power mode.
- Same TOTK version, save slot and in-game location.
- Similar device temperature. Avoid comparing a cool-device run with a heavily heat-soaked run.
- Same route, camera direction, actions and approximate duration.

Before each comparison, record the APK build/commit, graphics settings, driver, battery/power mode and whether the shader cache is cold or warm.

## Cold-cache / warm-cache sequence

1. Preserve the game save. Do not clear Eden app data.
2. If Eden provides a per-game shader-cache clear action, use only that action to prepare a cold-cache run. Do not clear the app's data or game save.
3. Launch TOTK and follow the selected route for approximately three minutes. Record the worst stutters, approximate FPS range and whether gameplay recovers.
4. Exit TOTK normally, relaunch it without clearing the shader cache, and repeat the same route. This is the warm-cache run.
5. Repeat the warm-cache run once more. Compare the second and third runs to identify recurring stalls rather than one-time compilation.
6. If a per-game cache-clear action is unavailable, label the first run after installing the APK as "first run / cache state unknown"; do not claim it is a confirmed cold-cache test.

## What to record

| Field | Observation |
|---|---|
| APK commit / artifact | |
| Date and approximate device temperature | |
| Cache state | cold / warm / unknown |
| Route and duration | |
| FPS range (if visible) | |
| Largest stutter location / action | |
| Did the stutter recur on the next run? | |
| Did performance return to normal? | |
| Freeze, crash, graphical corruption or audio issue? | |

Prefer repeatable frame-time spikes and stutter locations over average FPS alone. If an FPS counter is unavailable, describe the stutter consistently (brief hitch, multi-second pause, sustained slowdown).

## Recovery checks

After a noticeable hitch, keep the game running for up to 30 seconds if it remains responsive. Record whether frame pacing returns to normal without intervention. If safe, return to the title/menu and reload the same save; note whether recovery succeeds.

Stop the test if the device becomes unusually hot, the emulator repeatedly freezes, or graphical corruption persists. Do not repeatedly force-stop during a single measurement sequence; that changes cache and recovery conditions.

## Capturing shader diagnostics without ADB

The timing warnings are written to Eden's normal application log (eden_log.txt), not the separate GPU log.

1. Finish the test run and exit TOTK normally back to Eden's home screen.
2. Open Settings and select **Share log**. Do not select **Share GPU log** for these shader warnings.
3. Use the Android share sheet to send or save the eden_log.txt attachment.
4. Share that file together with the run notes. The relevant entries begin with "RP5 shader diagnostics".

The log can contain device and emulator diagnostic information. Review it before sharing it outside this debugging conversation.

## Diagnostic build notes

The shader diagnostics build emits warnings prefixed with "RP5 shader diagnostics" for graphics/compute shader translation, SPIR-V/module preparation, Vulkan graphics/compute pipeline creation, GPU-finish waits and Vulkan worker-drain waits taking at least 2 ms. Shader/pipeline messages include hashes and elapsed microseconds; scheduler messages report wait duration. The diagnostics do not themselves identify every GPU-driver stall, so correlate them with the exact gameplay moment.

A warm run that still stutters without corresponding shader/pipeline timing warnings points toward other causes (for example, resource uploads, synchronization, texture-cache pressure or GPU/driver scheduling).
