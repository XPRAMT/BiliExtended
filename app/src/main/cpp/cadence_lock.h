// Phase-locked cadence for content that does not match the panel 1:1 (user priority 2026-11-07: "節奏穩定性最優先, 奇偶翻轉壓到物理限制").
// Pure decision logic (no globals, no clock) so it is host-testable: tools/test_cadence_lock.cpp.
//
// Why: scheduleMedia3 snaps EVERY frame to its nearest VSync.  120 fps content at 2x is one frame per P/2, so every second frame sits exactly on the midpoint between two
// VSyncs and its slot is decided by a few ms of jitter: the module itself stamped two consecutive source frames on consecutive VSyncs 3 % of the time (and none for the
// next 2) -- parity flips of the displayed source index (measured 2026-11-07: 17-27 flips per 5 s, steps of 1 and 3 instead of 2).  0.5x has the same problem
// (frames 2 VSyncs apart, 94 % exact, 6 % one slot early or late).
//
// What: a deterministic decimation (dense) / slot pattern (sparse) that does not look at the jitter:
//   dense  (display interval D < P, 1/(D/P) ~ m, e.g. 120 fps x 2.0 -> m = 2): a frame is KEPT iff its ABSOLUTE content index % m == phase.  The phase is chosen once (at the
//          first lock after a rate change) and survives every re-lock, stall and audio-clock dropout -- a re-lock only re-plans the VSyncs, it never moves the kept set.  Kept
//          frames take consecutive VSyncs; the others are skipped (release(false)).
//   sparse (D >= P, D/P ~ k, e.g. 0.5x -> k = 2; 60 fps x 2 -> k = 1): every frame is kept, k VSyncs after the previous one.
// The panel (120.285 Hz) and the content (F = frames/s x speed) beat: every so often one slot has to carry NO new frame (a frame shown twice, index step 0, parity
// unchanged) -- that is the physical limit; it is inserted when the smoothed drift (audio-derived ideal time minus the planned slot time) has grown by more than half a
// VSync.  If the content is the faster one the opposite happens: one kept frame is dropped (the kept set keeps its parity).  The audio clock therefore only steers the
// phase slowly (EMA), it never decides which frame takes which slot, and a missing audio sample freezes the steering instead of ending the lock.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace cadence_lock {

enum class Kind { kNone, kDense, kSparse };

struct Mode {
    Kind kind = Kind::kNone;
    int n = 0;          // dense: keep every n-th frame; sparse: VSyncs per frame
};

// contentDurationNs: one content frame (estimator), speed: playback rate, periodNs: measured VSync period.
inline Mode classify(int64_t contentDurationNs, double speed, int64_t periodNs) {
    Mode m;
    if (contentDurationNs <= 0 || speed <= 0.0 || periodNs <= 0) return m;
    const double rho = (static_cast<double>(contentDurationNs) / speed) / static_cast<double>(periodNs);   // display interval of one frame in VSync periods
    if (rho < 0.75) {
        const double inv = 1.0 / rho;
        const int n = static_cast<int>(std::lround(inv));
        if (n >= 2 && std::fabs(inv - n) < 0.06 * n) { m.kind = Kind::kDense; m.n = n; }
    } else {
        const int k = static_cast<int>(std::lround(rho));
        if (k >= 1 && std::fabs(rho - k) < 0.03 * k) { m.kind = Kind::kSparse; m.n = k; }
    }
    return m;
}

struct State {
    bool locked = false;
    bool have = false;            // a kept frame exists in this lock
    int stable = 0;               // consecutive committed frames with a usable mode (before the lock)
    int phase = -1;               // dense: absolute content index % n of the kept frames (-1: not chosen yet); survives re-locks
    int64_t idx = 0;              // absolute content index of the last committed frame
    int64_t lastKeptIdx = 0;
    int64_t lastSnapped = 0;      // grid time of the last kept frame
    double ema = 0.0, eRef = 0.0; // smoothed (ideal - snapped) and its reference (the mean over the warm-up frames after the lock)
    int warm = 0;                 // kept frames averaged into eRef so far; the drift logic starts when warm == Params::warmFrames
    double warmSum = 0.0;
    int64_t lastCommitNs = 0, lastUsableNs = 0;
    uint64_t repeats = 0, skipSlots = 0, kept = 0, skipped = 0, relocks = 0;
    uint64_t relockStale = 0, relockInactive = 0, relockFar = 0, relockAudio = 0;
    // recent errors of the kept frames (grid time, ideal - snapped): the steering follows their MEDIAN over Params::medianWindowNs, see commit()
    static constexpr int kErrRing = 96;
    int64_t errAt[kErrRing] = {};
    double errVal[kErrRing] = {};
    int errHead = 0, errCount = 0;
};

struct Params {
    int64_t periodNs = 0;
    int64_t anchorNs = 0;                        // a sampled VSync time (the grid is anchor + k * period)
    int64_t marginNs = 1'000'000;                // extra drift beyond half a VSync before a repeat slot is inserted
    int64_t dropMarginNs = 3'000'000;            // ... and before a kept frame is dropped: wider, so that a repeat (which lands the drift at -P/2 + margin) cannot ping-pong into a drop
    int lockAfterFrames = 10;
    int warmFrames = 30;                         // kept frames after the lock whose mean error becomes the reference (a single noisy sample would fake a drift)
    double emaAlpha = 1.0 / 30.0;                // per kept frame: 0.25 s at 120 kept frames/s (24 fps x 10, 60 fps x 4), 1 s at 30 (60 fps x 0.5)
    // A single bad IJK position sample must not move the plan (2026-11-11, phone, petal x 10: a sample 230 ms of content ahead moved the ideal time by -48..-57 ms for the
    // ~100 ms until the next sample; the EMA crossed the drop threshold within 4 frames and dropped 4 kept frames in 0.3 s = index steps of 4 in the steady state).  The EMA is
    // fed with the MEDIAN of the errors of the last medianWindowNs instead of the raw error: a sample held for 100 ms is a minority of a 0.3 s window and moves the median by
    // nothing, a real offset (a start, a resync) takes over the window after 0.15 s and is followed at the old speed.  A slower time constant instead (module 198: 1.5 s) removed
    // the drops but left a start offset uncorrected for 6 s at 0.5x (15 long intervals in the steady state of 4K60 x 0.5 against 6).  0 = raw error (the old behaviour).
    // OFF (0) since module 200: on the phone (tc22, 2026-11-11) the median + fast mode dropped MORE kept frames than the plain 1.5 s EMA of module 198 (petal x 10: 8 steps of 4
    // in 4 rounds against 1; 4K60 x 4: 1 against 0) and did not remove the long intervals of 4K60 x 0.5 either (18 / 13 / 9 against 15).  Kept for experiments.
    int64_t medianWindowNs = 0;
    // Time constants of the EMA (on the median): slow while the median stays within bigDevPeriods VSyncs of the EMA, so the ordinary wander of the IJK clock (several ms over a
    // second) does not make a drop and a repeat ping-pong; fast once it is beyond, so a real offset is absorbed within about a second.  Host test: 1 s still dropped a kept frame in
    // 1 of 3 seeds of 24 fps x 10 with a 4 ms clock wander, 1.5 s none.  <= 0: emaAlpha per kept frame (the old behaviour).
    int64_t emaTauNs = 1'500'000'000;
    int64_t emaFastTauNs = 0;                   // OFF since module 200, see medianWindowNs
    double bigDevPeriods = 1.5;
    int64_t staleNs = 300'000'000;               // no committed frame for this long: unlock (the kept set / phase is kept)
    int64_t audioLossNs = 1'000'000'000;         // no usable audio-derived time for this long: unlock
    double farPeriods = 4.0;                     // |drift| beyond this many VSyncs: the clock jumped (a seek, a rate ramp): re-plan
    // The drift is measured against 0 (a frame whose snapped VSync is the nearest one of its audio-derived ideal time is in sync: the fixed latencies are added outside, in
    // scheduleMedia3), not against the mean error of the first warmFrames kept frames after the lock (2026-11-11).  The warm-up mean was meant to keep one noisy sample from
    // faking a drift, but it also took any offset that existed at the lock as "normal": after a resume from pause the picture starts 0.2-0.4 s before the sound (the audio
    // clock starts late), most of that went into the reference, the remainder was chased one slot at a time for 6-9 s (bursts of long intervals at 4K60 x 0.5, phone) and the
    // picture stayed 40-110 ms early for good (lock off: back to 0-30 ms).  Against 0 an offset beyond farPeriods re-plans, i.e. the next kept frame takes the VSync of its ideal
    // time at once (one wait instead of dozens of repeats), and the steady state converges to sync.  The warm-up still holds the decisions for warmFrames frames.  true = old.
    bool warmReference = false;
};

struct Decision {
    bool active = false;                         // the cadence decides this frame (otherwise the caller keeps its nearest-VSync logic)
    bool keep = true;
    bool repeatSlot = false;                     // this kept frame takes one VSync later than the previous one + its normal step (content slower than the panel)
    bool skipSlot = false;                       // one slot less: a kept frame dropped (dense) / a shorter step (sparse); the content is FASTER than the panel
    int64_t snappedNs = 0;
    int64_t idx = 0;                             // absolute content index of this frame
};

inline int64_t floorDiv(int64_t a, int64_t b) {
    int64_t q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

inline int64_t nearestGrid(int64_t t, const Params& p) {
    const int64_t k = floorDiv(t - p.anchorNs, p.periodNs);
    const int64_t before = p.anchorNs + k * p.periodNs, after = before + p.periodNs;
    return (after - t) < (t - before) ? after : before;
}

inline int64_t posMod(int64_t a, int64_t n) {
    const int64_t r = a % n;
    return r < 0 ? r + n : r;
}

// absIdx: the absolute content index of the frame being scheduled (content time of the frame / content frame duration).
inline Decision decide(const State& s, const Mode& m, const Params& p, int64_t idealNs, int64_t absIdx, int64_t nowNs) {
    Decision d;
    if (!s.locked || m.kind == Kind::kNone) return d;
    d.active = true;
    d.idx = absIdx;
    const int phase = (m.kind == Kind::kDense && s.phase >= 0) ? s.phase : static_cast<int>(posMod(absIdx, m.n > 0 ? m.n : 1));
    if (!s.have) {                                              // first kept frame of the lock: the nearest VSync of its ideal time
        if (m.kind == Kind::kDense && posMod(absIdx, m.n) != phase) {      // a lock that starts on a frame of the other parity waits for its own
            d.keep = false;
            d.snappedNs = 0;
            return d;
        }
        d.keep = true;
        d.snappedNs = nearestGrid(idealNs, p);
        return d;
    }
    const int64_t thr = p.periodNs / 2 + p.marginNs;
    const int64_t dropThr = p.periodNs / 2 + p.dropMarginNs;
    const double dev = s.ema - s.eRef;
    const bool warming = s.warm < p.warmFrames;
    const bool slow = !warming && dev > static_cast<double>(thr);          // the clock runs ahead of the plan: one slot without a new frame
    const bool fast = !warming && dev < -static_cast<double>(dropThr);     // the plan runs ahead of the clock: one slot less
    if (m.kind == Kind::kDense) {
        d.keep = posMod(absIdx, m.n) == phase;
        if (!d.keep) { d.snappedNs = s.lastSnapped; return d; }
        if (fast) {                                              // drop this kept frame: the kept set keeps its parity, the next kept frame follows the LAST one by one slot
            d.keep = false; d.skipSlot = true; d.snappedNs = s.lastSnapped; return d;
        }
        int64_t steps = (absIdx - s.lastKeptIdx) / m.n;         // VSyncs between the kept frames: 1 when nothing was dropped upstream
        if (steps < 1) steps = 1;
        d.repeatSlot = slow;
        d.snappedNs = nearestGrid(s.lastSnapped + (steps + (slow ? 1 : 0)) * p.periodNs, p);
    } else {
        d.keep = true;
        int64_t steps = (absIdx - s.idx) * m.n;
        if (steps < 1) steps = 1;
        if (fast && steps > 1) { steps -= 1; d.skipSlot = true; }
        d.repeatSlot = slow;
        d.snappedNs = nearestGrid(s.lastSnapped + (steps + (slow ? 1 : 0)) * p.periodNs, p);
    }
    // the plan fell far behind the clock (a stall): hand the frame back to the caller's logic; commit() re-plans
    if (d.snappedNs + 3 * p.periodNs < nowNs) d.active = false;
    return d;
}

// Re-plan from scratch: the kept-frame phase, the counters and the lock itself stay out of it only as far as listed.
inline void replan(State& s, int64_t nowNs) {
    State n;
    n.phase = s.phase;
    n.repeats = s.repeats; n.kept = s.kept; n.skipped = s.skipped; n.relocks = s.relocks; n.skipSlots = s.skipSlots;
    n.relockStale = s.relockStale; n.relockInactive = s.relockInactive; n.relockFar = s.relockFar; n.relockAudio = s.relockAudio;
    n.lastCommitNs = nowNs;
    n.lastUsableNs = s.lastUsableNs;
    s = n;
}

// Called once per committed frame (displayed or skipped).  idealNs: audio-derived ideal time of the frame; usable: the caller has an audio-derived time for this frame.
inline void commit(State& s, const Mode& m, const Params& p, const Decision& d, int64_t idealNs, int64_t nowNs, bool usable) {
    if (usable) s.lastUsableNs = nowNs;
    if (m.kind == Kind::kNone) {                                // no whole-number case (any more): the cadence is off, the phase is forgotten
        if (s.locked) s.relocks++;
        const uint64_t a = s.repeats, b = s.kept, c = s.skipped, e = s.relocks, f = s.skipSlots;
        s = State();
        s.repeats = a; s.kept = b; s.skipped = c; s.relocks = e; s.skipSlots = f;
        s.lastCommitNs = nowNs;
        return;
    }
    if (s.lastCommitNs != 0 && nowNs - s.lastCommitNs > p.staleNs) {       // the decoder stalled
        if (s.locked) { s.relocks++; s.relockStale++; }
        replan(s, nowNs);
    }
    s.lastCommitNs = nowNs;
    if (!s.locked) {
        if (usable && ++s.stable >= p.lockAfterFrames) { s.locked = true; s.have = false; }
        else if (!usable) s.stable = 0;
        return;
    }
    if (s.lastUsableNs != 0 && nowNs - s.lastUsableNs > p.audioLossNs) {   // no audio clock for a second: steering is blind, re-plan once it is back
        s.relocks++; s.relockAudio++;
        replan(s, nowNs);
        return;
    }
    if (!d.active) {                                            // the caller handled this frame (stall): start over
        s.relocks++; s.relockInactive++;
        replan(s, nowNs);
        return;
    }
    if (!s.have) {
        s.idx = d.idx;
        if (!d.keep) { s.skipped++; return; }                   // waiting for a frame of the kept phase
        if (m.kind == Kind::kDense && s.phase < 0) s.phase = static_cast<int>(posMod(d.idx, m.n));
        s.have = true;
        s.lastKeptIdx = d.idx;
        s.lastSnapped = d.snappedNs;
        s.warmSum = usable ? static_cast<double>(idealNs - d.snappedNs) : 0.0;
        s.warm = usable ? 1 : 0;
        s.ema = s.warmSum;
        s.eRef = p.warmReference ? s.warmSum : 0.0;
        s.kept++;
        return;
    }
    s.idx = d.idx;
    if (!d.keep) {
        s.skipped++;
        if (d.skipSlot) {                                       // a dropped kept frame (dense): the next kept frame is one slot after the LAST one, errors grow by a period
            s.lastKeptIdx = d.idx;
            s.ema += static_cast<double>(p.periodNs);
            for (int i = 0; i < s.errCount; ++i) s.errVal[i] += static_cast<double>(p.periodNs);
            s.skipSlots++;
        }
        return;
    }
    s.kept++;
    s.lastKeptIdx = d.idx;
    const int64_t keptGapNs = d.snappedNs - s.lastSnapped;    // grid time since the previous kept frame (one or two VSyncs)
    s.lastSnapped = d.snappedNs;
    // a repeat slot moved the plan one VSync later (errors are smaller by a period), a dropped slot one earlier: shift the average first, then average the new error
    if (d.repeatSlot) { s.ema -= static_cast<double>(p.periodNs); s.repeats++; for (int i = 0; i < s.errCount; ++i) s.errVal[i] -= static_cast<double>(p.periodNs); }
    if (d.skipSlot) { s.ema += static_cast<double>(p.periodNs); s.skipSlots++; for (int i = 0; i < s.errCount; ++i) s.errVal[i] += static_cast<double>(p.periodNs); }
    if (!usable) return;                                        // no audio-derived time: the steering keeps its last value
    const double err = static_cast<double>(idealNs - d.snappedNs);
    if (s.warm < p.warmFrames) {                                 // warm-up: the reference is the running mean of the first kept frames
        s.warmSum += err;
        s.warm++;
        s.ema = s.warmSum / s.warm;
        s.eRef = p.warmReference ? s.ema : 0.0;
        return;
    }
    double fed = err;
    if (p.medianWindowNs > 0) {
        s.errAt[s.errHead] = d.snappedNs;
        s.errVal[s.errHead] = err;
        s.errHead = (s.errHead + 1) % State::kErrRing;
        if (s.errCount < State::kErrRing) s.errCount++;
        double w[State::kErrRing];
        int n = 0;
        for (int i = 0; i < s.errCount; ++i) {
            const int j = (s.errHead - 1 - i + State::kErrRing) % State::kErrRing;
            if (d.snappedNs - s.errAt[j] > p.medianWindowNs) break;
            w[n++] = s.errVal[j];
        }
        std::nth_element(w, w + n / 2, w + n);
        fed = w[n / 2];
    }
    double alpha = p.emaAlpha;
    if (p.emaTauNs > 0) {
        const bool big = std::fabs(fed - s.ema) > p.bigDevPeriods * static_cast<double>(p.periodNs);
        const double tau = static_cast<double>(big && p.emaFastTauNs > 0 ? p.emaFastTauNs : p.emaTauNs);
        alpha = std::min(1.0, std::max(0.0, static_cast<double>(keptGapNs) / tau));
    }
    s.ema += alpha * (fed - s.ema);
    const double dev = s.ema - s.eRef;
    if (std::fabs(dev) > p.farPeriods * static_cast<double>(p.periodNs)) {  // far from the clock (a seek, an audio jump): re-plan, the phase stays
        s.relocks++; s.relockFar++;
        replan(s, nowNs);
    }
}

}  // namespace cadence_lock
