// 交出幀門檻 / handoff gate: may the frame being scheduled be handed to the compositor now?
// Pure decision (no globals, no clock) so the rule is host-testable: tools/test_handoff_gate.cpp.
//
// The rule (user, 2026-11-06): consecutive handoffs are never closer than pace - 1 ms on the wall
// clock, pace = the content's display interval D when it is slower than the panel (D > P + 1 ms),
// otherwise the measured VSync period P.  A frame that arrives sooner is HELD until then and
// re-evaluated by the IJK refresh thread; it is not excess (it belongs to a later slot).  Excess
// frames of denser content (two frames snapped to one VSync) are skipped by the caller's
// same-VSync rule, not here.  denseSkip = the literal reading "skip an early frame of denser
// content": kept for A/B (`debug.biliextended.handoff` = "skip").
#pragma once

#include <cstdint>

namespace handoff_gate {

enum class Verdict { kPass, kHold, kSkip };

struct Decision {
    Verdict verdict = Verdict::kPass;
    uint64_t wakeNs = 0;    // kHold: earliest handoff time = lastHandoff + pace - allowance
    uint64_t paceNs = 0;    // interval the gate paced on (0: gate not applicable)
    bool denser = false;    // D < P - band: content denser than the panel
};

// periodNs: measured P; outside [minPeriodNs, maxPeriodNs] = unknown -> kPass.
// displayDurationNs: D = one content frame in display time (content duration / speed), 0 = unknown.
// lastHandoffNs: wall time of the last frame actually handed off (0 = none yet -> kPass).
inline Decision decide(uint64_t periodNs, int64_t displayDurationNs, uint64_t lastHandoffNs,
                       uint64_t nowNs, uint64_t minPeriodNs, uint64_t maxPeriodNs,
                       int64_t bandNs, uint64_t allowanceNs, bool denseSkip) {
    Decision d;
    if (periodNs < minPeriodNs || periodNs > maxPeriodNs || lastHandoffNs == 0
            || nowNs <= lastHandoffNs) {
        return d;
    }
    const int64_t P = static_cast<int64_t>(periodNs);
    const int64_t D = displayDurationNs;
    uint64_t pace = periodNs;
    if (D > 0 && D > P + bandNs) {
        // Slower than the panel.  If D is (nearly) a whole number of panel periods, pace on D -- that is the same
        // grid.  If it is a fraction of a period (40 fps on a 48 Hz panel = 1.2 P) pacing on D walks off the grid and
        // the compositor shows 25 ms intervals, so pace on one period and let the display timeline snap each frame to
        // its nearest tick, which produces the ideal 1,1,1,1,2 pattern.
        const int64_t periods = (D + P / 2) / P;
        const int64_t whole = periods * P;
        if (periods >= 1 && whole >= D - bandNs && whole <= D + bandNs) {
            pace = static_cast<uint64_t>(D);   // whole number of periods: keep the exact content interval
        }
    } else if (D > 0 && D < P - bandNs) {
        d.denser = true;                    // denser than the panel: keepers follow the panel
    }
    d.paceNs = pace;
    const uint64_t minGap = pace > allowanceNs ? pace - allowanceNs : pace;
    if (nowNs - lastHandoffNs >= minGap) {
        return d;
    }
    if (d.denser && denseSkip) {
        d.verdict = Verdict::kSkip;
    } else {
        d.verdict = Verdict::kHold;
        d.wakeNs = lastHandoffNs + minGap;
    }
    return d;
}

}  // namespace handoff_gate
