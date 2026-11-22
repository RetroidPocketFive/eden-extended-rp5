// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// RP5 diagnostics: shader/pipeline stall accounting, frame times and a per-session summary for
// comparing GPU drivers. Header-only. Everything here is gated by Settings::values.rp5_diagnostics
// (off by default): when off, hooks reduce to one cheap setting read and no clock calls.
//
// Log lines (grep "RP5"):
//   "RP5 stall: ..."          single stall >= 1 ms
//   "RP5 stall summary (...)" every 5 s, interval counters
//   "RP5 session: ..."        every 60 s, cumulative since the first frame (driver, settings,
//                             frame-time percentiles, stall totals) - the line to compare runs

#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <utility>

#include "common/logging.h"
#include "common/settings.h"

namespace Vulkan::Rp5Diag {

[[nodiscard]] inline bool Enabled() noexcept {
    return Settings::values.rp5_diagnostics.GetValue();
}

// Which context the current thread is building a pipeline in ("worker" = pipeline worker pool,
// "inline" = built on the calling thread, e.g. during disk-cache load).
inline thread_local const char* tl_context = "unknown";

struct Counter {
    // Interval counters (reset every 5 s report).
    std::atomic<uint64_t> count{0};
    std::atomic<uint64_t> total_us{0};
    std::atomic<uint64_t> max_us{0};
    // Session counters (never reset).
    std::atomic<uint64_t> s_count{0};
    std::atomic<uint64_t> s_total_us{0};
    std::atomic<uint64_t> s_max_us{0};

    static void UpdateMax(std::atomic<uint64_t>& m, uint64_t us) noexcept {
        uint64_t prev = m.load(std::memory_order_relaxed);
        while (us > prev && !m.compare_exchange_weak(prev, us, std::memory_order_relaxed)) {
        }
    }

    void Add(uint64_t us) noexcept {
        count.fetch_add(1, std::memory_order_relaxed);
        total_us.fetch_add(us, std::memory_order_relaxed);
        UpdateMax(max_us, us);
        s_count.fetch_add(1, std::memory_order_relaxed);
        s_total_us.fetch_add(us, std::memory_order_relaxed);
        UpdateMax(s_max_us, us);
    }
};

struct Snapshot {
    uint64_t count, total_us, max_us;
};

inline Snapshot Take(Counter& c) noexcept {
    return {c.count.exchange(0, std::memory_order_relaxed),
            c.total_us.exchange(0, std::memory_order_relaxed),
            c.max_us.exchange(0, std::memory_order_relaxed)};
}

inline Snapshot Session(const Counter& c) noexcept {
    return {c.s_count.load(std::memory_order_relaxed),
            c.s_total_us.load(std::memory_order_relaxed),
            c.s_max_us.load(std::memory_order_relaxed)};
}

inline Counter g_translate_live;  // shader translate+SPIR-V on the GPU/emulation thread (frame-blocking)
inline Counter g_build_worker;    // driver pipeline build on the worker pool (not frame-blocking)
inline Counter g_build_inline;    // driver pipeline build inline (disk-load / no workers)
inline Counter g_stall;           // scheduler thread actually blocked waiting for a pipeline
inline Counter g_stall_small;     // subset of g_stall: draws with <=6 indices/vertices (async exempt)
inline std::atomic<uint64_t> g_stall_queued{0};        // draws that needed a not-yet-built pipeline
inline std::atomic<uint64_t> g_async_skipped{0};       // draws skipped by async shaders
inline std::atomic<uint64_t> g_async_exempt_small{0};  // unbuilt draws exempted (<=6 idx/vtx)
inline std::atomic<uint64_t> g_s_async_skipped{0};
inline std::atomic<uint64_t> g_s_async_exempt_small{0};
inline std::atomic<uint64_t> g_s_stall_queued{0};
// Draw size seen by the most recent BuiltPipeline() on this (GPU) thread.
inline thread_local uint32_t tl_index_count = 0;
inline thread_local uint32_t tl_vertex_count = 0;

// ---- Frame times (presented frames), 0.5 ms bins up to 200 ms, last bin = overflow ----
constexpr size_t FRAME_BINS = 401;
constexpr uint64_t FRAME_BIN_US = 500;
inline std::array<std::atomic<uint32_t>, FRAME_BINS> g_frame_bins{};
inline std::atomic<uint64_t> g_frames{0};
inline std::atomic<uint64_t> g_frame_sum_us{0};
inline std::atomic<uint64_t> g_frame_max_us{0};
inline std::atomic<uint64_t> g_frames_over_50ms{0};
inline std::atomic<uint64_t> g_frames_over_100ms{0};
inline std::atomic<int64_t> g_last_frame_ns{0};
inline std::atomic<int64_t> g_session_start_ns{0};

// ---- Device identity, set once from RendererVulkan::Report() ----
inline std::string g_device_info;
inline std::atomic<bool> g_device_info_ready{false};

inline void SetDeviceInfo(std::string info) {
    g_device_info = std::move(info);
    g_device_info_ready.store(true, std::memory_order_release);
}

inline std::atomic<int64_t> g_last_report_ns{0};
inline std::atomic<int64_t> g_last_session_report_ns{0};

constexpr int64_t REPORT_INTERVAL_NS = 5'000'000'000LL;
constexpr int64_t SESSION_INTERVAL_NS = 60'000'000'000LL;

[[nodiscard]] inline int64_t NowNs() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// Frame time (ms) at the given percentile (0..1) from the histogram.
[[nodiscard]] inline double FramePercentileMs(double p) noexcept {
    uint64_t total = 0;
    for (const auto& b : g_frame_bins) {
        total += b.load(std::memory_order_relaxed);
    }
    if (total == 0) {
        return 0.0;
    }
    const uint64_t target = static_cast<uint64_t>(static_cast<double>(total) * p) + 1;
    uint64_t cum = 0;
    for (size_t i = 0; i < FRAME_BINS; ++i) {
        cum += g_frame_bins[i].load(std::memory_order_relaxed);
        if (cum >= target) {
            return static_cast<double>((i + 1) * FRAME_BIN_US) / 1000.0;
        }
    }
    return static_cast<double>(FRAME_BINS * FRAME_BIN_US) / 1000.0;
}

inline void ReportInterval(int64_t now, int64_t last) {
    const auto tl = Take(g_translate_live);
    const auto bw = Take(g_build_worker);
    const auto bi = Take(g_build_inline);
    const auto st = Take(g_stall);
    const auto ss = Take(g_stall_small);
    const auto queued = g_stall_queued.exchange(0, std::memory_order_relaxed);
    const auto skipped = g_async_skipped.exchange(0, std::memory_order_relaxed);
    const auto exempt = g_async_exempt_small.exchange(0, std::memory_order_relaxed);
    g_s_stall_queued.fetch_add(queued, std::memory_order_relaxed);
    g_s_async_skipped.fetch_add(skipped, std::memory_order_relaxed);
    g_s_async_exempt_small.fetch_add(exempt, std::memory_order_relaxed);
    if (tl.count + bw.count + bi.count + st.count + queued + skipped + exempt == 0) {
        return;
    }
    const double secs = static_cast<double>(now - last) / 1e9;
    LOG_WARNING(Render_Vulkan,
                "RP5 stall summary ({:.1f}s): live_translate[gpu-thread] n={} total={:.1f}ms "
                "max={:.1f}ms | build[worker] n={} total={:.1f}ms max={:.1f}ms | "
                "build[inline] n={} total={:.1f}ms max={:.1f}ms | "
                "STALL[scheduler waited] n={} total={:.1f}ms max={:.1f}ms (draws needing unbuilt "
                "pipeline: {}) | of which small-draw stalls[<=6 idx/vtx] n={} total={:.1f}ms "
                "max={:.1f}ms | async: skipped={} exempt_small={}",
                secs, tl.count, tl.total_us / 1000.0, tl.max_us / 1000.0, bw.count,
                bw.total_us / 1000.0, bw.max_us / 1000.0, bi.count, bi.total_us / 1000.0,
                bi.max_us / 1000.0, st.count, st.total_us / 1000.0, st.max_us / 1000.0, queued,
                ss.count, ss.total_us / 1000.0, ss.max_us / 1000.0, skipped, exempt);
}

inline void ReportSession(int64_t now) {
    const uint64_t frames = g_frames.load(std::memory_order_relaxed);
    if (frames == 0) {
        return;
    }
    const int64_t start = g_session_start_ns.load(std::memory_order_relaxed);
    const double secs = static_cast<double>(now - start) / 1e9;
    const double avg_ms =
        static_cast<double>(g_frame_sum_us.load(std::memory_order_relaxed)) / 1000.0 /
        static_cast<double>(frames);
    const auto tl = Session(g_translate_live);
    const auto bw = Session(g_build_worker);
    const auto bi = Session(g_build_inline);
    const auto st = Session(g_stall);
    const auto ss = Session(g_stall_small);
    const std::string info =
        g_device_info_ready.load(std::memory_order_acquire) ? g_device_info : std::string("?");
    LOG_WARNING(
        Render_Vulkan,
        "RP5 session: label=\"{}\" device=\"{}\" settings[async_shaders={} dyna_state={} "
        "vertex_input_dyn={} gpu_accuracy={}] t={:.0f}s frames={} avg_fps={:.1f} "
        "frame_ms[avg={:.1f} p50={:.1f} p95={:.1f} p99={:.1f} max={:.1f}] over50ms={} over100ms={} | "
        "stalls n={} total={:.0f}ms max={:.0f}ms small_draw n={} total={:.0f}ms | "
        "builds worker n={} total={:.0f}ms max={:.0f}ms inline n={} total={:.0f}ms | "
        "live_translate n={} total={:.0f}ms max={:.0f}ms | async skipped={} exempt_small={} "
        "unbuilt_draws={}",
        Settings::values.rp5_diag_label.GetValue(), info,
        Settings::values.use_asynchronous_shaders.GetValue(),
        static_cast<int>(Settings::values.dyna_state.GetValue()),
        Settings::values.vertex_input_dynamic_state.GetValue(),
        static_cast<int>(Settings::values.gpu_accuracy.GetValue()), secs, frames,
        secs > 0.0 ? static_cast<double>(frames) / secs : 0.0, avg_ms, FramePercentileMs(0.50),
        FramePercentileMs(0.95), FramePercentileMs(0.99),
        static_cast<double>(g_frame_max_us.load(std::memory_order_relaxed)) / 1000.0,
        g_frames_over_50ms.load(std::memory_order_relaxed),
        g_frames_over_100ms.load(std::memory_order_relaxed), st.count, st.total_us / 1000.0,
        st.max_us / 1000.0, ss.count, ss.total_us / 1000.0, bw.count, bw.total_us / 1000.0,
        bw.max_us / 1000.0, bi.count, bi.total_us / 1000.0, tl.count, tl.total_us / 1000.0,
        tl.max_us / 1000.0, g_s_async_skipped.load(std::memory_order_relaxed),
        g_s_async_exempt_small.load(std::memory_order_relaxed),
        g_s_stall_queued.load(std::memory_order_relaxed));
}

// Cheap, thread-safe: emits the 5 s interval line and the 60 s session line when due.
inline void MaybeReport() {
    if (!Enabled()) {
        return;
    }
    const int64_t now = NowNs();
    int64_t last = g_last_report_ns.load(std::memory_order_relaxed);
    if (last == 0) {
        g_last_report_ns.compare_exchange_strong(last, now, std::memory_order_relaxed);
    } else if (now - last >= REPORT_INTERVAL_NS &&
               g_last_report_ns.compare_exchange_strong(last, now, std::memory_order_relaxed)) {
        ReportInterval(now, last);
    }
    int64_t last_session = g_last_session_report_ns.load(std::memory_order_relaxed);
    if (last_session == 0) {
        g_last_session_report_ns.compare_exchange_strong(last_session, now,
                                                         std::memory_order_relaxed);
    } else if (now - last_session >= SESSION_INTERVAL_NS &&
               g_last_session_report_ns.compare_exchange_strong(last_session, now,
                                                                std::memory_order_relaxed)) {
        ReportSession(now);
    }
}

// Call once per presented frame (RendererVulkan::Composite).
inline void FrameTick() noexcept {
    if (!Enabled()) {
        g_last_frame_ns.store(0, std::memory_order_relaxed);
        return;
    }
    const int64_t now = NowNs();
    int64_t start = 0;
    g_session_start_ns.compare_exchange_strong(start, now, std::memory_order_relaxed);
    const int64_t last = g_last_frame_ns.exchange(now, std::memory_order_relaxed);
    if (last != 0) {
        const uint64_t dt_us = static_cast<uint64_t>((now - last) / 1000);
        // Ignore gaps of 2 s or more (pause, app in background, loading screens of the host).
        if (dt_us < 2'000'000) {
            g_frames.fetch_add(1, std::memory_order_relaxed);
            g_frame_sum_us.fetch_add(dt_us, std::memory_order_relaxed);
            Counter::UpdateMax(g_frame_max_us, dt_us);
            const size_t bin = static_cast<size_t>(dt_us / FRAME_BIN_US);
            g_frame_bins[bin < FRAME_BINS ? bin : FRAME_BINS - 1].fetch_add(
                1, std::memory_order_relaxed);
            if (dt_us > 50'000) {
                g_frames_over_50ms.fetch_add(1, std::memory_order_relaxed);
            }
            if (dt_us > 100'000) {
                g_frames_over_100ms.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }
    MaybeReport();
}

} // namespace Vulkan::Rp5Diag
