# RP5 root-cause diagnostics

## Objective

Collect enough correlated evidence to explain *why* a frame stalls, a Vulkan operation fails, or a visual artifact appears on the Retroid Pocket 5. This is diagnostic instrumentation, not telemetry upload: reports stay on-device until the user explicitly exports them.

The primary target is the Snapdragon 865 / Adreno 650, with separate identification of Qualcomm's proprietary Vulkan driver and Mesa Turnip. Do not assume a metric is available on every driver.

## Current foundation

The fork already has:

- `GPU::Logging::GPULogger`, a bounded Vulkan-call ring buffer, optional memory-allocation tracking, shader/pipeline state strings, and a GPU device-lost dump.
- Android sharing of the most recent GPU dump.
- Core performance statistics exposed to Android through `NativeLibrary.getPerfStats()`.
- A desktop performance overlay with FPS and frame-time samples.

Important gaps:

- The Vulkan call logger can write synchronously to a file for every captured call. Verbose/all logging must not become the default gameplay profiler.
- The existing GPU call ring buffer is not a causal timeline: it does not consistently mark frame boundaries or pair begin/end events with durations.
- The current snapshot does not establish whether a slow frame was CPU-bound, GPU-bound, blocked on synchronization, compiling a shader, or thermally throttled.
- A screenshot alone does not identify the render pass, pipeline, shader, texture, or driver state that produced the artifact.

## Design principles

1. **Local-first and opt-in export.** Never upload reports automatically. Ask before including screenshots or game-specific metadata.
2. **Bounded memory.** Use fixed-capacity rings and counters; never grow a per-frame log indefinitely.
3. **Low overhead by default.** Record timestamps, numeric IDs, durations, and counters. Format strings and write files only when exporting or handling an error.
4. **Monotonic time.** Use one monotonic clock for event ordering and durations. Include wall-clock time only in the report header.
5. **Correlate events.** Every event should carry a session ID and, where meaningful, a frame sequence number, thread ID, subsystem, event code, and duration.
6. **Separate observation from diagnosis.** Store raw evidence and confidence. Do not label a frame GPU-bound merely because it was slow.
7. **Driver capability awareness.** Record which timing/counter features are supported, unavailable, or disabled. Never block gameplay waiting for optional driver data.
8. **Privacy by construction.** Exclude ROM paths, user names, account details, save contents, keys, and unrelated Android logs.

## Event model

Use a compact event record rather than formatted strings on hot paths:

- monotonic timestamp (microseconds or nanoseconds, consistently chosen);
- event type and stable reason code;
- frame sequence number (zero when not associated with a frame);
- thread identifier;
- duration (zero for instant events);
- two or three numeric payload fields;
- optional object identifier (pipeline, shader hash, allocation ID), never a raw pointer in exported reports.

Initial event types:

| Event | Start/end or sample | Root-cause question |
|---|---|---|
| Frame | begin/end | Which frames exceeded the frame budget? |
| CPU phase | begin/end | Was time spent emulating, submitting commands, compiling, or waiting? |
| Shader | compile begin/end/fail | Did compilation coincide with the hitch? |
| Pipeline | create begin/end/fail/cache hit | Was a new pipeline created or a cache lookup missed? |
| Vulkan submit | submit/complete | Was work queued faster than it completed? |
| Wait | wait begin/end | Which fence/semaphore/queue wait blocked progress? |
| Allocation | allocate/free/fail | Did memory pressure or allocation churn precede failure? |
| GPU fault | device lost / driver error | What was the last known GPU work and state? |
| Thermal | periodic sample / status transition | Did throttling correlate with a sustained slowdown? |
| Visual issue | user marker | What was happening when the user saw corruption? |

The first implementation should add frame sequence IDs and a low-cost event ring. Instrument only a few high-value boundaries initially: frame timing, shader compilation, graphics/compute pipeline creation, queue submission/completion, and explicit waits. Add more only when the first reports show a gap.

## Performance measurements

For each reporting interval and session, calculate from raw frame durations:

- frame count and elapsed duration;
- median, p90, p95, p99 and maximum frame time;
- average FPS and 1% low FPS, with the calculation method documented;
- count of frames exceeding 16.7 ms, 33.3 ms, and the active target frame budget;
- longest consecutive hitch sequence.

Do not infer GPU execution time from CPU frame time. Prefer non-blocking Vulkan timestamp queries where the device supports them; read results several frames later and discard unavailable results. If unsupported, report GPU timing as unavailable rather than inserting a queue-idle wait.

## Root-cause classification

The report should present evidence-backed hypotheses, not a single unqualified cause.

- **Shader/pipeline hitch:** frame spike overlaps shader compilation or pipeline creation; report duration, cache hit/miss, shader hash and pipeline ID.
- **CPU-bound:** CPU-side frame work is over budget while GPU completion is not lagging, where GPU timing is available.
- **GPU-bound:** GPU timestamp duration exceeds budget or queue backlog grows. Do not infer this from FPS alone.
- **Synchronization stall:** a named fence/semaphore wait consumes a significant part of the frame; include wait duration and submission sequence.
- **Memory pressure:** allocation failure, high tracked usage, or rapid allocation churn precedes the event. Label estimates and allocator coverage.
- **Thermal/performance-state correlation:** frame-time degradation coincides with Android thermal status changes or accessible frequency changes. Treat correlation as evidence, not proof.
- **Unknown:** retain raw events when available evidence cannot distinguish causes.

## Android device context

At session start, record:

- app version, build commit, Android API level and device model;
- Vulkan API version, physical-device name/vendor/device IDs, driver version and driver identity;
- selected CPU backend, renderer, resolution scale and relevant graphics settings;
- supported diagnostic capabilities (timestamp queries, pipeline executable properties, memory budget extension, debug utils).

Sample at a modest cadence (initially 5–10 seconds) and record unavailable values explicitly:

- Android thermal status;
- battery temperature and charging state;
- process PSS/RSS or available app memory metrics;
- system available memory;
- CPU/GPU frequencies or utilization only where a supported API exposes them.

Do not poll sysfs paths as a required feature: access and meaning vary by Android build and vendor. Do not claim GPU utilization or GPU clocks when the platform does not expose them.

## Visual glitch report

Add a user-triggered marker, not continuous screenshot capture. The report flow should let the user select a category (black squares, missing textures, flicker, lighting/shadows, skybox, other), optionally capture the current frame, and attach a short pre-event and post-event window from the in-memory event ring.

Record the active game title ID only if the user consents to include it. Include renderer settings, driver identity, recent shader/pipeline events, resource allocation counters and the frame-time window. A screenshot must be clearly marked as user-supplied evidence, not a diagnosis.

## Diagnostic bundle

The export should be one ZIP with:

- `manifest.json`: schema version, build commit, session ID, capture times and included files;
- `device.json`: device, OS, GPU, Vulkan driver and capability data;
- `settings.json`: relevant renderer settings, with sensitive values excluded;
- `frames.csv`: frame sequence, monotonic timestamp, frame duration and available phase timings;
- `events.jsonl`: chronological structured events;
- `memory.csv`: sampled process/system memory and tracked GPU allocation counters;
- `thermal.csv`: thermal status and temperature samples where available;
- `gpu_dump.txt`: existing GPU dump when present;
- `issue.png`: optional user-approved screenshot.

The bundle should include a schema version and units for every numeric field. Export must work without network access.

## Implemented instrumentation

- Graphics and compute pipeline cache misses measure the CPU-side pipeline setup path with a monotonic clock.
- Graphics shader stages and compute shaders measure CPU translation / SPIR-V emission / Vulkan shader-module creation time, with shader hash, stage (where applicable), and SPIR-V size.
- These records enter the GPU logger's recent-call ring and are retained at Standard log level. A later GPU device-loss dump can therefore show the most recent shader compilation and pipeline cache-miss activity.
- The measurements do **not** claim to be GPU execution time or asynchronous graphics-pipeline completion time. Those require separate completion events and/or Vulkan timestamp queries.
- Events are emitted only when a shader is prepared or a pipeline cache miss occurs, not on every draw. The logger's existing configuration controls whether GPU logging is active.

## Implementation phases

### Phase 1 — low-overhead timeline

- Add a bounded structured event recorder with a fixed capacity and overwrite/drop counter.
- Add frame sequence IDs and capture frame duration from the existing performance-statistics path.
- Add explicit shader and pipeline begin/end/failure events at existing compilation/creation boundaries.
- Include the event timeline and recorder-drop count in GPU crash/device-lost dumps.
- Add native unit tests for wraparound, ordering, duration pairing, and concurrent producers.

### Phase 2 — Android context and export

- Capture device/driver/build/settings metadata once per session.
- Sample thermal and memory state every 5–10 seconds where APIs permit.
- Export a ZIP using the Android document picker; do not upload automatically.
- Reuse the existing GPU dump sharing path rather than creating a second sharing mechanism.

### Phase 3 — GPU timing and synchronization

- Audit Vulkan timestamp-query support on Qualcomm and Turnip.
- Add asynchronous, non-blocking GPU timestamp collection if supported.
- Instrument submission completion and explicit waits.
- Add pipeline-cache hit/miss and compilation duration statistics.

### Phase 4 — visual issue reports and benchmark

- Add a user-triggered graphics issue marker with optional screenshot.
- Define repeatable TOTK test scenes and a benchmark report.
- Compare builds using identical settings and test sequences; retain raw frame-time distributions.

## Acceptance criteria

- Disabled diagnostics have negligible hot-path overhead.
- Default diagnostics do not perform synchronous file I/O per draw or per frame.
- Event storage is bounded and reports overwritten/dropped events.
- No instrumentation introduces device-wide waits or queue-idle calls during normal play.
- Unsupported metrics are reported as unavailable, never fabricated or inferred.
- Native tests cover event ordering, wraparound, concurrency, and export serialization.
- A report can distinguish shader/pipeline creation, CPU work, synchronization waits, GPU execution (when supported), memory pressure and thermal correlation—or explicitly state that evidence is insufficient.
- Reports remain local until the user chooses to export them.
