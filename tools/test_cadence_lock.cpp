// Host test of cadence_lock.h (tools/run-native-tests.sh).  Simulates the scheduler: a frame every D = content / speed seconds of audio-derived ideal time with
// correlated + white noise (the measured clock noise is ~14 ms raw, a few ms after the Java smoothing), a 120.285 Hz panel, and checks the displayed pattern.
#include "cadence_lock.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

namespace {

using namespace cadence_lock;

constexpr int64_t kP = 8'313'500;      // 1 / 120.285 Hz in ns (rounded)

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

struct Result {
    int frames = 0, keptFrames = 0, indexParityFlips = 0, stepZero = 0, stepOne = 0, stepTwo = 0, stepOther = 0, slotMin = 99, slotMax = 0, slotZero = 0;
    uint64_t relocks = 0, repeats = 0, skipSlots = 0, relockStale = 0, relockAudio = 0;
    double maxAbsDevMs = 0, seconds = 0, repeatsPerSecond = 0, finalEmaMs = 0;
    int repeatsAfterSettle = 0;
    int lateDevFrames = 0;      // kept frames more than settleFrames after a persistent jump whose filtered drift is still beyond 2 VSyncs (the offset not absorbed yet; the beat alone never gets there)
};

// srcFps: source frame rate, speed: playback rate, noiseMs: sigma of the AR(1) noise of the ideal time, dropEvery: IJK drops one source frame every N frames (0 = never)
// jumpEvery / jumpMs / jumpLen: every jumpEvery frames the audio-derived ideal time jumps by jumpMs for jumpLen frames and comes back -- one bad IJK position sample
// held until the next one (measured 2026-11-11, phone, 24 fps x 10: a sample 230 ms of content ahead moved the ideal time by -48..-57 ms for ~100 ms, twice per 15 s)
Result simulate(double srcFps, double speed, double noiseMs, double seconds, int dropEvery, unsigned seed, int stallEvery = 0, int audioLossEvery = 0,
                int jumpEvery = 0, double jumpMs = 0.0, int jumpLen = 0, int settleFrames = 0, int rampFrames = 0, bool warmRef = false) {
    Result r;
    Params p;
    p.periodNs = kP;
    p.anchorNs = 123'456'789;
    p.warmReference = warmRef;
    State s;
    std::mt19937 rng(seed);
    std::normal_distribution<double> white(0.0, 1.0);
    const int64_t contentNs = static_cast<int64_t>(std::llround(1e9 / srcFps));
    const double dNs = contentNs / speed;                          // display interval of one source frame
    double ar = 0.0;
    int64_t srcIdx = 0;                                            // source frame number
    double t = 5.0e9;                                              // ideal time of the current frame
    std::vector<int64_t> keptIdx, keptSlot;
    const int total = static_cast<int>(seconds * srcFps * speed);
    for (int i = 0; i < total; ++i) {
        int64_t adv = 1;
        if (dropEvery > 0 && i > 0 && i % dropEvery == 0) adv = 2;  // IJK dropped a frame upstream
        t += dNs * static_cast<double>(adv);
        srcIdx += adv;
        ar = 0.995 * ar + 0.1 * noiseMs * white(rng);              // AR(1), stationary sigma ~ noiseMs
        double jump = (jumpEvery > 0 && i > jumpEvery && (i % jumpEvery) < jumpLen) ? jumpMs : 0.0;
        if (rampFrames > 0) jump = i > jumpEvery ? jumpMs * std::min(1.0, static_cast<double>(i - jumpEvery) / rampFrames) : 0.0;   // a ramp that then stays (start transient)
        const int64_t ideal = static_cast<int64_t>(t + (ar + 0.3 * noiseMs * white(rng) + jump) * 1e6);
        int64_t now = ideal - 31'000'000;
        if (stallEvery > 0 && i > 0 && i % stallEvery == 0) now += 400'000'000;     // the decoder stalled: the commit is 0.4 s late
        const bool usable = !(audioLossEvery > 0 && (i % audioLossEvery) < 40);          // 40 frames (0.17 s at 238 fps) without an audio-derived time
        const Mode m = classify(contentNs, speed, kP);
        const Decision d = decide(s, m, p, ideal, srcIdx, now);
        const uint64_t repBefore = s.repeats;
        commit(s, m, p, d, ideal, now, usable);
        if (settleFrames > 0 && i > jumpEvery + settleFrames && s.repeats > repBefore) r.repeatsAfterSettle++;
        ++r.frames;
        if (d.active && d.keep) {
            keptIdx.push_back(srcIdx);
            keptSlot.push_back((d.snappedNs - p.anchorNs + kP / 2) / kP);
            const double dev = std::fabs(s.ema - s.eRef) / 1e6;          // the filtered drift the algorithm bounds (the raw clock noise itself is not part of it)
            if (s.have && s.warm >= p.warmFrames && dev > r.maxAbsDevMs) r.maxAbsDevMs = dev;
            if (settleFrames > 0 && jumpEvery > 0 && i > jumpEvery + settleFrames && s.warm >= p.warmFrames && dev > 2.0 * kP / 1e6) r.lateDevFrames++;
        }
    }
    r.seconds = total / (srcFps * speed);
    r.finalEmaMs = s.ema / 1e6;
    r.relocks = s.relocks; r.repeats = s.repeats; r.skipSlots = s.skipSlots;
    r.relockStale = s.relockStale; r.relockAudio = s.relockAudio;
    r.keptFrames = static_cast<int>(keptIdx.size());
    for (size_t i = 1; i < keptIdx.size(); ++i) {
        const int64_t step = keptIdx[i] - keptIdx[i - 1];
        if (step == 0) r.stepZero++; else if (step == 1) r.stepOne++; else if (step == 2) r.stepTwo++; else r.stepOther++;
        if ((keptIdx[i] % 2) != (keptIdx[i - 1] % 2)) r.indexParityFlips++;
        const int slot = static_cast<int>(keptSlot[i] - keptSlot[i - 1]);
        if (slot < r.slotMin) r.slotMin = slot;
        if (slot > r.slotMax) r.slotMax = slot;
        if (slot == 0) r.slotZero++;
    }
    r.repeatsPerSecond = r.repeats / r.seconds;
    return r;
}

}  // namespace

int main() {
    // classify
    check(classify(8'333'333, 1.99, kP).kind == Kind::kDense && classify(8'333'333, 1.99, kP).n == 2, "120 fps x 1.99 is dense, keep every 2nd");
    check(classify(8'333'333, 2.0, kP).kind == Kind::kDense, "120 fps x 2.0 is dense");
    check(classify(8'333'333, 0.5, kP).kind == Kind::kSparse && classify(8'333'333, 0.5, kP).n == 2, "120 fps x 0.5 is sparse, 2 VSyncs");
    check(classify(16'666'667, 1.99, kP).kind == Kind::kSparse && classify(16'666'667, 1.99, kP).n == 1, "60 fps x 1.99 is sparse, 1 VSync");
    check(classify(8'333'333, 1.5, kP).kind == Kind::kNone, "120 fps x 1.5 (1.33 frames per VSync) is not a whole-number case");
    check(classify(0, 2.0, kP).kind == Kind::kNone && classify(8'333'333, 0.0, kP).kind == Kind::kNone, "unknown duration / speed: none");

    // 120 fps x 1.99, noisy audio clock
    for (unsigned seed = 1; seed <= 3; ++seed) {
        const Result r = simulate(120.0, 1.99, 4.0, 40.0, 0, seed);
        std::printf("dense 120x1.99 seed %u: kept %d, index steps 2:%d 1:%d 0:%d other:%d, parity flips %d, slot steps %d..%d, repeats %.2f/s, relocks %llu, max dev %.1f ms\n",
                    seed, r.keptFrames, r.stepTwo, r.stepOne, r.stepZero, r.stepOther, r.indexParityFlips, r.slotMin, r.slotMax, r.repeatsPerSecond,
                    static_cast<unsigned long long>(r.relocks), r.maxAbsDevMs);
        check(r.indexParityFlips == 0, "dense: the displayed source index never flips parity");
        check(r.stepOne == 0, "dense: no two consecutive source frames are displayed");
        check(r.slotMin >= 1 && r.slotMax <= 2, "dense: kept frames take consecutive VSyncs, a slot is left empty only for a repeat");
        check(r.repeatsPerSecond > 0.6 && r.repeatsPerSecond < 1.2, "dense: about 0.9 repeat slots per second (the beat of 238.8 fps against 120.285 Hz)");
        check(r.relocks == 0, "dense: no re-lock in steady state");
        check(r.maxAbsDevMs < 12.5, "dense: the filtered drift stays within a VSync and a half");
    }
    // exactly 2.0x: beat 0.285 / 120.285 -> about one repeat per 3.5 s
    {
        const Result r = simulate(120.0, 2.0, 4.0, 40.0, 0, 7);
        std::printf("dense 120x2.0: parity flips %d, repeats %.2f/s, relocks %llu\n", r.indexParityFlips, r.repeatsPerSecond, static_cast<unsigned long long>(r.relocks));
        check(r.indexParityFlips == 0 && r.stepOne == 0, "dense 2.0: no parity flip");
        check(r.repeatsPerSecond > 0.15 && r.repeatsPerSecond < 0.45, "dense 2.0: about one repeat slot per 3.5 s");
    }
    // frames dropped upstream: the kept set keeps its parity
    {
        const Result r = simulate(120.0, 1.99, 3.0, 30.0, 97, 11);
        std::printf("dense with upstream drops: parity flips %d, other steps %d\n", r.indexParityFlips, r.stepOther);
        check(r.indexParityFlips == 0, "dense with upstream drops: parity of the kept set unchanged");
    }
    // 0.5x of 120 fps: 2 VSyncs per frame, one 3-slot interval per ~3.5 s
    for (unsigned seed = 1; seed <= 3; ++seed) {
        const Result r = simulate(120.0, 0.5, 4.0, 40.0, 0, seed);
        std::printf("sparse 120x0.5 seed %u: kept %d, slot steps %d..%d, repeats %.2f/s, relocks %llu, max dev %.1f ms\n", seed, r.keptFrames, r.slotMin, r.slotMax,
                    r.repeatsPerSecond, static_cast<unsigned long long>(r.relocks), r.maxAbsDevMs);
        check(r.slotMin == 2, "sparse 0.5x: never shorter than 2 VSyncs");
        check(r.slotMax <= 3, "sparse 0.5x: never longer than 3 VSyncs");
        check(r.stepOne == r.keptFrames - 1, "sparse 0.5x: every source frame shown, in order");
        check(r.repeatsPerSecond > 0.15 && r.repeatsPerSecond < 0.45, "sparse 0.5x: one 3-slot interval per ~3.5 s");
        check(r.relocks == 0, "sparse 0.5x: no re-lock");
    }
    // 60 fps x 1.99: 1 VSync per frame
    {
        const Result r = simulate(60.0, 1.99, 4.0, 40.0, 0, 5);
        std::printf("sparse 60x1.99: slot steps %d..%d, repeats %.2f/s, relocks %llu\n", r.slotMin, r.slotMax, r.repeatsPerSecond, static_cast<unsigned long long>(r.relocks));
        check(r.slotMin == 1 && r.slotMax <= 2 && r.stepOne == r.keptFrames - 1, "sparse 60x1.99: every frame, 1 VSync apart (2 only for a repeat)");
    }
    // disturbances: decoder stalls and short audio-clock dropouts must not move the kept set (parity of the displayed index)
    {
        const Result r = simulate(120.0, 1.99, 4.0, 40.0, 0, 21, 1500, 0);
        std::printf("dense 120x1.99 with stalls every 1500 frames: parity flips %d, re-plans (stale) %llu\n", r.indexParityFlips, static_cast<unsigned long long>(r.relockStale));
        check(r.indexParityFlips == 0, "dense with decoder stalls: the kept set keeps its parity across the re-plans");
        check(r.relockStale > 5, "the simulated stalls were seen");
        const Result a = simulate(120.0, 1.99, 4.0, 40.0, 0, 22, 0, 900);
        std::printf("dense 120x1.99 with audio dropouts every 900 frames: parity flips %d, audio re-plans %llu, relocks %llu\n", a.indexParityFlips,
                    static_cast<unsigned long long>(a.relockAudio), static_cast<unsigned long long>(a.relocks));
        check(a.indexParityFlips == 0, "dense with audio dropouts: no parity flip");
        check(a.relocks == 0, "short audio dropouts (< 1 s) do not end the lock");
        const Result b = simulate(120.0, 0.5, 4.0, 40.0, 0, 23, 900, 700);
        std::printf("sparse 120x0.5 with stalls and dropouts: slot steps %d..%d, relocks %llu\n", b.slotMin, b.slotMax, static_cast<unsigned long long>(b.relocks));
    }
    // content slightly FASTER than the panel (121 fps x 2.0 -> 242 fps): a kept frame is dropped now and then, the parity of the kept set stays
    {
        const Result r = simulate(121.0, 2.0, 4.0, 40.0, 0, 9);
        std::printf("dense 121x2.0 (content faster): parity flips %d, skip slots %llu, repeats %llu, relocks %llu\n", r.indexParityFlips,
                    static_cast<unsigned long long>(r.skipSlots), static_cast<unsigned long long>(r.repeats), static_cast<unsigned long long>(r.relocks));
        check(r.indexParityFlips == 0, "dense, content faster than the panel: parity of the kept set unchanged");
        check(r.skipSlots > 0 && r.repeats == 0, "dense, content faster than the panel: kept frames are dropped, none repeated");
    }
    // 24 fps x 10 (240 fps, every 2nd frame kept) with single bad position samples: the steering must not chase them (each chase dropped 4 kept frames in 0.3 s = 4 index steps of 4)
    for (unsigned seed = 31; seed <= 33; ++seed) {
        const Result r = simulate(24.0, 10.0, 4.0, 40.0, 0, seed, 0, 0, 1800, -57.0, 24);
        std::printf("dense 24x10 with -57 ms / 100 ms clock jumps every 7.5 s seed %u: parity flips %d, steps of 4: %d, skip slots %llu, repeats %.2f/s, relocks %llu\n", seed,
                    r.indexParityFlips, r.stepOther, static_cast<unsigned long long>(r.skipSlots), r.repeatsPerSecond, static_cast<unsigned long long>(r.relocks));
        check(r.indexParityFlips == 0, "dense 24x10 with clock jumps: no parity flip");
        check(r.skipSlots == 0 && r.stepOther == 0, "dense 24x10 with clock jumps: a single bad position sample drops no kept frame");
    }
    // a REAL offset (the clock moved for good, e.g. a start transient after the warm-up) must still be followed quickly: 60 fps x 0.5 (30 kept frames/s), +30 ms from 3 s on (below the 4-VSync re-plan)
    for (unsigned seed = 41; seed <= 43; ++seed) {
        const Result r = simulate(60.0, 0.5, 4.0, 20.0, 0, seed, 0, 0, 90, 30.0, 1 << 30, 45);
        std::printf("sparse 60x0.5 with a persistent +30 ms offset at 3 s seed %u: repeats %llu, drift still beyond 2 VSyncs 1.5 s after it: %d frames, relocks %llu\n", seed,
                    static_cast<unsigned long long>(r.repeats), r.lateDevFrames, static_cast<unsigned long long>(r.relocks));
        check(r.lateDevFrames == 0, "a persistent clock offset is absorbed within 1.5 s (no slow catch-up through the steady state)");
    }
    // start transient after a resume from pause (phone, 4K60 x 0.5, 2026-11-11): the picture becomes 300 ms early over the first 1.5 s after the lock, then stays.  Old warm-up
    // reference: dozens of repeats long after it and a lasting offset; reference 0: absorbed (re-plan) and back in sync, only beat repeats afterwards
    for (int old = 1; old >= 0; --old) {
        const Result r = simulate(60.0, 0.5, 4.0, 20.0, 0, 51, 0, 0, 15, 300.0, 1 << 30, 90, 45, old == 1);
        std::printf("sparse 60x0.5 start transient (+300 ms over 1.5 s), %s reference: repeats %llu, repeats later than 3 s after it %d, relocks %llu, final error %.1f ms\n",
                    old ? "warm-up" : "zero", static_cast<unsigned long long>(r.repeats), r.repeatsAfterSettle, static_cast<unsigned long long>(r.relocks), r.finalEmaMs);
        if (!old) {
            check(std::fabs(r.finalEmaMs) < 8.0, "zero reference: the start offset does not stay (the picture ends in sync)");
            check(r.repeatsAfterSettle <= 6, "zero reference: only beat repeats (about 0.3/s) once the transient is over");
        }
    }
    // a stall / stale commit unlocks, then re-locks after lockAfterFrames frames
    {
        Params p; p.periodNs = kP; p.anchorNs = 0;
        State s; const Mode m = classify(8'333'333, 1.99, kP);
        int64_t now = 1'000'000'000;
        int64_t idx = 100;
        for (int i = 0; i < 12; ++i) { Decision d = decide(s, m, p, now, idx, now); commit(s, m, p, d, now, now, true); now += 4'000'000; ++idx; }
        check(s.locked, "locks after 10 usable frames");
        const int phase = s.phase;
        now += 500'000'000;                                    // the decoder stalled for 0.5 s
        { Decision d = decide(s, m, p, now, idx, now); commit(s, m, p, d, now, now, true); ++idx; }
        check(!s.locked && s.relocks == 1 && s.relockStale == 1, "a stale commit unlocks");
        check(s.phase == phase, "the kept-frame phase survives the unlock");
        Decision dn = decide(s, m, p, now, idx, now);
        check(!dn.active, "unlocked: the caller's own logic decides");
        for (int i = 0; i < 12; ++i) { Decision d = decide(s, m, p, now, idx, now); commit(s, m, p, d, now, now, true); now += 4'000'000; ++idx; }
        check(s.locked && s.phase == phase, "re-locks with the same phase");
        Decision d2 = decide(s, m, p, now, idx, now); commit(s, m, p, d2, now, now, false);
        check(s.locked, "one frame without an audio-derived time does not end the lock");
    }

    if (failures == 0) {
        std::printf("cadence_lock: all checks passed\n");
        return 0;
    }
    return 1;
}
