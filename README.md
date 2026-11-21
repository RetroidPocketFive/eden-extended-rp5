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
  <b>Eden Extended — Retroid Pocket 5 / TOTK</b>
  <br>
</h1>

<h4 align="center">A device- and game-focused fork of the Eden Nintendo Switch emulator, fine-tuned for The Legend of Zelda: Tears of the Kingdom on the Retroid Pocket 5.</h4>

<p align="center">Independent community project. Not affiliated with or endorsed by the Eden team, Nintendo, or Retroid.</p>

## What this project is

**The goal is simple: make playing <em>The Legend of Zelda: Tears of the Kingdom (TOTK)</em> on the <strong>Retroid Pocket 5 (RP5)</strong> as smooth, stable and practical as the hardware and emulator allow.**

This repository is a focused effort to fine-tune the Eden Nintendo Switch emulator for one specific combination:

- **Handheld:** Retroid Pocket 5
- **Processor:** Qualcomm Snapdragon 865
- **Graphics:** Qualcomm Adreno 650
- **Operating system:** Android
- **Primary game:** The Legend of Zelda: Tears of the Kingdom

Rather than trying to be an all-purpose emulator fork, development prioritises the bottlenecks and problems that matter to TOTK on this handheld. That means investigating frame pacing, shader and pipeline stutter, texture-cache behaviour, GPU synchronisation, memory pressure, stability and the quality of diagnostic information available when something goes wrong.

The intention is to make targeted, measurable improvements—not to promise a particular frame rate or claim that demanding parts of the game will run perfectly. The RP5 has finite CPU and GPU resources, and results will depend on the scene, settings, driver, cache state and device temperature.

## What we are optimising for

The work is guided by a few practical priorities:

1. **Consistent frame pacing.** Reduce avoidable stalls and disruptive frame-time spikes, not just chase a higher peak FPS number.
2. **Efficient graphics and memory handling.** Investigate texture-cache pressure, resource cleanup and unnecessary GPU waits.
3. **Less disruptive shader and pipeline work.** Measure compilation and pipeline creation costs, then use those measurements to guide safe improvements.
4. **Reliable longer play sessions.** Improve stability and make crashes easier to understand and reproduce.
5. **Useful on-device workflows.** Make it easier to collect logs and transfer compatible shader-cache data without requiring a PC, ADB or root access for normal testing.

Changes are judged primarily by how TOTK behaves on an actual RP5. A change that helps another game or a more powerful device is not automatically a priority here.

## Current project work

The following work is present in the active `rp5-development` branch. These are engineering features and diagnostic improvements; their presence does not mean every performance issue is solved.

### Texture-cache and GPU synchronisation

- Texture-cache garbage-collection diagnostics for memory budgets, cleanup duration, eviction counts and GPU-finish wait time.
- Batched GPU-to-guest texture write-backs, reducing repeated GPU-finish operations during a cleanup pass.
- Throttled diagnostic warnings to help identify recurring cache pressure and expensive cleanup.

The aim is to reduce avoidable stalls when the emulator has to reclaim texture resources. The real effect must still be judged in repeatable gameplay tests.

### Shader and pipeline diagnostics

- Timing for graphics and compute shader translation.
- Timing for SPIR-V and shader-module preparation.
- Vulkan graphics and compute pipeline-creation timing.
- Instrumentation for GPU-finish and Vulkan worker-drain waits.

These diagnostics help distinguish shader compilation and pipeline creation from other sources of stutter. They are measurement tools, not a claim that shader stutter has been eliminated.

### Shader-cache export and import

The Android game-properties screen includes a local shader-cache export/import workflow:

- Exports the game's Eden Vulkan shader cache into an `.eden-cache` package.
- Includes a manifest with game identity, cache format, build/device details and a SHA-256 integrity value.
- Checks the package and warns about relevant build, game, driver or add-on differences before import.
- Requires emulation to be stopped and does not include save data or ROM/game content.

The transferable package contains Eden's serialized `vulkan.bin` cache. The opaque Vulkan driver pipeline cache (`vulkan_pipelines.bin`) is deliberately excluded because it is driver- and device-specific. Cache packages should be treated as performance data, not as universally portable cache files.

See [the shader-cache package notes](docs/rp5-shader-cache-package.md) for details.

### Crash diagnostics

- Captures uncaught Java/Kotlin exception details and stack traces.
- Combines available Android application-exit information with a bounded app/emulation event log.
- Adds lifecycle breadcrumbs to help establish what the app was doing before a crash.
- Makes the information available through the existing user-initiated log/report sharing flow.

Reports are shared only when the user chooses to share them. The diagnostics do not automatically upload reports, and they are not intended to collect ROMs, save files or shader-cache contents.

See [the RP5 crash-diagnostics notes](docs/rp5-crash-diagnostics.md).

## Known limitations and open problems

- **Shader-related stutter remains an open issue.** First-run shader compilation and pipeline loading can cause noticeable slowdowns. Warm-cache behaviour also needs careful measurement.
- Performance is scene-dependent. Busy areas, effects, physics, traversal and other demanding moments can behave very differently.
- Results vary with graphics-driver version, emulator settings, shader-cache state, device temperature and background activity.
- A successful Android build confirms that the code compiled; it does not prove that a feature works correctly in a game session.
- This fork is experimental. Regressions, incomplete features and compatibility issues are possible.

No fixed FPS target, full-game-playability claim or universal driver recommendation is made by this project.

## Testing on the Retroid Pocket 5

Real-device testing is essential. When comparing builds, keep the test as consistent as possible:

1. Use the same RP5, game version, graphics driver and emulator settings.
2. Record whether the shader cache is cold, partially populated or warm.
3. Test the same route or gameplay situation, rather than comparing unrelated scenes.
4. Separate first-run shader-compilation behaviour from repeat runs.
5. Note device temperature and allow the device to return to a similar starting condition between comparisons.
6. Record average behaviour as well as short FPS lows, frame-time spikes, freezes, audio issues and recovery time.
7. Include the tested APK's commit or build identifier.

A short repeatable test is more useful than an isolated peak-FPS result. The project includes a [TOTK performance test protocol](docs/rp5-totk-performance-test-protocol.md) to help keep comparisons consistent.

### Sending useful feedback

When reporting a problem or test result, include as many of these details as possible:

- RP5 model and Android version.
- TOTK version and the in-game location or activity.
- Eden Extended build/commit and APK source.
- Graphics driver and emulator settings.
- Shader-cache state (first run or repeat run).
- Approximate device temperature, if available.
- What happened, how often it happened and whether restarting or revisiting the area changes it.
- An app log or crash report, shared manually through Eden's log-sharing option.

Please avoid posting copyrighted game files, firmware, encryption keys or personal data in issues.

## Builds and installation

Experimental Android APKs are produced by the repository's GitHub Actions workflow.

- Open the [Actions page](https://github.com/RetroidPocketFive/eden-extended-rp5/actions/workflows/rp5-test-apk.yml) and select a successful run for the branch you want to test.
- Download the APK artifact attached to that run.
- Install it on the Retroid Pocket 5 using Android's normal APK installation flow. Android may ask you to allow installation from the app used to open the APK.
- Keep a backup of important save data before testing experimental builds.

The workflow currently builds the Android `arm64-v8a` target as `Mainline RelWithDebInfo`. These are development/test builds, not a promise of release-grade stability. Artifact availability and retention are controlled by GitHub Actions; check the workflow run for the exact commit and build result.

## Building from source

This fork follows the Eden Android build system. For general prerequisites and setup, consult the [Eden build instructions](https://git.eden-emu.dev/eden-emu/eden).

The RP5 test workflow currently uses JDK 17, Android SDK platform 36, NDK 28.2.13676358 and SDK CMake 3.31.6. From `src/android`, the relevant Gradle tasks are:

```sh
./gradlew assembleMainlineRelease
./gradlew assembleMainlineRelWithDebInfo
```

The APK is produced under:

```
src/android/app/build/outputs/apk/mainline/
```

For the exact CI configuration, see [the RP5 test APK workflow](.github/workflows/rp5-test-apk.yml).

## Development approach

The active project baseline is the `rp5-development` branch. Work is developed incrementally and should be evaluated on the target handheld before being treated as a confirmed improvement.

The project favours:

- Small, reviewable changes tied to a specific RP5/TOTK problem.
- Instrumentation before speculative optimisation.
- Repeatable before/after testing on the device.
- Clear separation between measured results, hypotheses and unverified changes.
- Keeping experimental work isolated until it has been reviewed and tested.

This is a deliberately narrow project. General compatibility across the Android ecosystem is not its primary objective, and changes may have different effects on other games, devices or drivers.

## Upstream, trademarks and licensing

Eden Extended is based on the Eden Nintendo Switch emulator and incorporates work from the yuzu Emulator Project. It is distributed under the GNU General Public License v3.0 or later. See [LICENSE.txt](LICENSE.txt) and the `LICENSES/` directory for licensing information covering bundled third-party components.

Copyright in the upstream emulator belongs to its respective contributors and projects. Copyright notices for changes specific to this fork are included in the relevant source files.

Nintendo, The Legend of Zelda, Tears of the Kingdom, Retroid and Eden are names and/or marks belonging to their respective owners. This is an independent, unofficial project and is not affiliated with or endorsed by those owners.

This repository does not provide Nintendo games, firmware or encryption keys. Users are responsible for supplying and using their own legally obtained game content and system files in accordance with applicable law.
