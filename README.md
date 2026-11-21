<!--
# SPDX-FileCopyrightText: Copyright 2026 atlonxp (Eden Extended changes)
# SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
# SPDX-FileCopyrightText: 2018 yuzu Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later
-->

<h1 align="center">
  <br>
  <img src="./dist/branding/eden-extended-icon-1024.png" alt="Eden Extended" width="160">
  <br>
  <b>Eden Extended — RP5 / TOTK</b>
  <br>
</h1>

<h4 align="center">An ongoing, device-specific Eden fork focused exclusively on improving The Legend of Zelda: Tears of the Kingdom on the Retroid Pocket 5.</h4>

<p align="center">Experimental project. Not affiliated with or endorsed by the Eden team, Nintendo, or Retroid.</p>

## Project scope

This repository is a focused, ongoing optimisation project for **The Legend of Zelda: Tears of the Kingdom (TOTK)** running on the **Retroid Pocket 5 (RP5)**.

The target hardware is the RP5's **Qualcomm Snapdragon 865** platform with **Adreno 650** graphics. Development and testing are centred on this device and this game. The aim is to improve performance, frame pacing, stability, graphics behaviour and the overall play experience through emulator-level investigation and changes.

**This is not a general-purpose Android performance fork.** Changes are made and evaluated for the specific RP5 + TOTK combination. They may not improve other games or devices, and could have regressions outside the target setup. No compatibility or performance claims are made for other hardware or titles.

The project is actively evolving. Results, known issues and priorities will change as testing continues.

## Current development focus

Recent work has concentrated on texture-cache memory management and GPU synchronisation:

- Improved texture-cache garbage collection to reduce unnecessary GPU synchronisation overhead.
- Batched GPU-to-guest texture write-backs so a cleanup pass can process multiple evictions with fewer GPU finish operations.
- Added diagnostics for texture-cache memory budgets, cleanup duration, eviction counts and GPU finish wait time.
- Added throttled warnings to help identify recurring memory-pressure and cleanup behaviour during gameplay.

These changes are intended to reduce performance disruption caused by texture-cache cleanup. Early on-device testing has shown a more responsive feel, although performance varies by gameplay situation.

### Known area still being investigated

Shader compilation and pipeline loading can still cause noticeable temporary slowdowns. This remains an active area for investigation; the current work should not be taken as a claim that shader-related stutter has been solved.

## Target configuration

| Component | Target |
|---|---|
| Game | The Legend of Zelda: Tears of the Kingdom (TOTK) |
| Device | Retroid Pocket 5 |
| SoC | Qualcomm Snapdragon 865 |
| GPU | Qualcomm Adreno 650 |
| Platform | Android |
| Project status | Ongoing / experimental |

Testing is performed on the target handheld. Performance can vary with game location, scene complexity, emulator settings, graphics driver, shader-cache state, device temperature and background activity.

## Builds and testing

Development builds are produced through the repository's GitHub Actions workflow. Check the **Actions** tab for the latest successful build and its downloadable APK artifact, or the **Releases** page for published builds.

The `relWithDebInfo` APK is a debuggable development build and may perform differently from a release build. Treat builds from this project as experimental and keep backups of important save data.

## Building

The build system is based on Eden. Refer to the [Eden build instructions](https://git.eden-emu.dev/eden-emu/eden) for general prerequisites and setup.

For Android builds, the project workflow currently uses JDK 17, Android NDK 28.2.13676358 and SDK CMake 3.31.6. From `src/android`:

```sh
./gradlew assembleMainlineRelease
./gradlew assembleMainlineRelWithDebInfo
```

The APK is produced under `src/android/app/build/outputs/apk/mainline/`.

## Project status and contributions

This is a personal, ongoing optimisation effort with a deliberately narrow target: **TOTK on the Retroid Pocket 5**. Changes are developed iteratively, tested on-device and retained when they provide a worthwhile result for that target.

Bug reports and test feedback are most useful when they include:
- The exact game area or gameplay situation.
- Emulator build or commit.
- Graphics driver and relevant emulator settings.
- Whether the issue happens consistently or only on first load.
- Any available logs or diagnostic output.

Please keep in mind that a fix for the target setup may involve trade-offs elsewhere. Broader compatibility is not the objective of this repository.

## Upstream and licensing

Eden Extended is based on the Eden Nintendo Switch emulator. It is licensed under the GNU General Public License v3.0 or later, the same as Eden. See [LICENSE.txt](LICENSE.txt) and the `LICENSES/` folder for the licenses of bundled third-party code.

Copyright for the underlying emulator belongs to the Eden Emulator Project and the yuzu Emulator Project. Changes specific to this fork are copyright 2026 atlonxp.

This repository does not include Nintendo games, firmware or encryption keys. You must provide content obtained from your own legally owned hardware and software.
