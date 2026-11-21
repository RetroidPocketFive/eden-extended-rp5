// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// RP5 diagnostics: shader/pipeline stall accounting. Header-only, no behaviour changes.
// Log lines are prefixed "RP5 stall" / "RP5 stall summary" so they are easy to grep.

#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>

#include "common/logging.h"

namespace Vulkan::Rp5Diag {

// Which context the current thread is building a pipeline in ("worker" = pipeline worker pool,
// "inline" = built on the calling thread, e.g. during disk-cache load).
inline thread_local const char* tl_context = "unknown";

struct Counter {
    std::atomic<uint64_t> count{0};
    std::atomic<uint64_t> total_us{0};
    std::atomic<uint64_t> max_us{0};

    void Add(uint64_t us) noexcept {
        count.fetch_add(1, std::memory_order_relaxed);
        total_us.fetch_add(us, std::memory_order_relaxed);
        uint64_t prev = max_us.load(std::memory_order_relaxed);
        while (us > prev && !max_us.compare_exchange_weak(prev, us, std::memory_order_relaxed)) {
        }
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

inline Counter g_translate_live;  // shader translate+SPIR-V on the GPU/emulation thread (frame-blocking)
inline Counter g_build_worker;    // driver pipeline build on the worker pool (not frame-blocking)
inline Counter g_build_inline;    // driver pipeline build inline (disk-load / no workers)
inline Counter g_stall;           // scheduler thread actually blocked waiting for a pipeline
inline std::atomic<uint64_t> g_stall_queued{0};  // draws that needed a not-yet-built pipeline
inline std::atomic<int64_t> g_last_report_ns{0};

constexpr int64_t REPORT_INTERVAL_NS = 5'000'000'000LL;

inline void MaybeReport() noexcept {
    const int64_t now = std::chrono::duration_cast<std::chrono::nanoseconds>(
                            std::chrono::steady_clock::now().time_since_epoch())
                            .count();
    int64_t last = g_last_report_ns.load(std::memory_order_relaxed);
    if (last == 0) {
        g_last_report_ns.compare_exchange_strong(last, now, std::memory_order_relaxed);
        return;
    }
    if (now - last < REPORT_INTERVAL_NS ||
        !g_last_report_ns.compare_exchange_strong(last, now, std::memory_order_relaxed)) {
        return;
    }
    const auto tl = Take(g_translate_live);
    const auto bw = Take(g_build_worker);
    const auto bi = Take(g_build_inline);
    const auto st = Take(g_stall);
    const auto queued = g_stall_queued.exchange(0, std::memory_order_relaxed);
    if (tl.count + bw.count + bi.count + st.count + queued == 0) {
        return;
    }
    const double secs = static_cast<double>(now - last) / 1e9;
    LOG_WARNING(Render_Vulkan,
                "RP5 stall summary ({:.1f}s): live_translate[gpu-thread] n={} total={:.1f}ms "
                "max={:.1f}ms | build[worker] n={} total={:.1f}ms max={:.1f}ms | "
                "build[inline] n={} total={:.1f}ms max={:.1f}ms | "
                "STALL[scheduler waited] n={} total={:.1f}ms max={:.1f}ms (draws needing unbuilt "
                "pipeline: {})",
                secs, tl.count, tl.total_us / 1000.0, tl.max_us / 1000.0, bw.count,
                bw.total_us / 1000.0, bw.max_us / 1000.0, bi.count, bi.total_us / 1000.0,
                bi.max_us / 1000.0, st.count, st.total_us / 1000.0, st.max_us / 1000.0, queued);
}

} // namespace Vulkan::Rp5Diag
