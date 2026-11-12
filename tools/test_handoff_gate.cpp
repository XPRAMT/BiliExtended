// Host test of handoff_gate::decide (tools/run-native-tests.sh).  Times in ns.
#include "handoff_gate.h"

#include <cstdio>
#include <cstdlib>

namespace {

using handoff_gate::Verdict;

constexpr uint64_t kMs = 1'000'000ULL;
constexpr uint64_t kP120 = 8'333'333ULL;            // 120 Hz
constexpr uint64_t kMinP = 4 * kMs, kMaxP = 34 * kMs;
constexpr int64_t kBand = 1 * kMs;
constexpr uint64_t kAllow = 1 * kMs;
constexpr uint64_t kLast = 1'000 * kMs;

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

handoff_gate::Decision at(uint64_t gapNs, int64_t D, bool denseSkip, uint64_t P = kP120) {
    return handoff_gate::decide(P, D, kLast, kLast + gapNs, kMinP, kMaxP, kBand, kAllow, denseSkip);
}

}  // namespace

int main() {
    // Gate not applicable: unknown period, no handoff yet, clock not past the last handoff.
    check(handoff_gate::decide(0, 0, kLast, kLast + kMs, kMinP, kMaxP, kBand, kAllow, false).verdict
            == Verdict::kPass, "unknown period passes");
    check(handoff_gate::decide(kP120, 0, 0, kLast, kMinP, kMaxP, kBand, kAllow, false).verdict
            == Verdict::kPass, "first handoff passes");
    check(at(0, 0, false).verdict == Verdict::kPass, "now == lastHandoff passes");

    // D unknown: pace on P - 1 ms.
    check(at(7'500 * 1'000, 0, false).verdict == Verdict::kPass, "D unknown, 7.5 ms passes");
    {
        const auto d = at(7 * kMs, 0, false);
        check(d.verdict == Verdict::kHold, "D unknown, 7 ms holds");
        check(d.wakeNs == kLast + kP120 - kAllow, "D unknown, wake = last + P - 1 ms");
        check(d.paceNs == kP120 && !d.denser, "D unknown paces on P, not denser");
    }
    // Near 1:1 (D within +-1 ms of P): pace on P - 1 ms.
    check(at(7 * kMs, static_cast<int64_t>(kP120) + 500'000, false).verdict == Verdict::kHold,
            "D ~ P holds at 7 ms");
    check(at(7'400 * 1'000, static_cast<int64_t>(kP120) - 500'000, false).verdict == Verdict::kPass,
            "D ~ P passes at 7.4 ms");

    // Slower than the panel (60 fps at 1x on 120 Hz): pace on D - 1 ms, never skip.
    {
        const int64_t D60 = 16'666'667;
        const auto hold = at(10 * kMs, D60, true);
        check(hold.verdict == Verdict::kHold, "60 fps content, 10 ms holds (even with denseSkip)");
        check(hold.wakeNs == kLast + static_cast<uint64_t>(D60) - kAllow, "60 fps wake = last + D - 1 ms");
        check(!hold.denser, "60 fps content is not denser");
        check(at(16 * kMs, D60, false).verdict == Verdict::kPass, "60 fps content, 16 ms passes");
    }

    // Denser than the panel (30 fps at 7x = 210 fps, D = 4.76 ms): keepers pace on P - 1 ms.
    {
        const int64_t D7x = 4'761'905;
        const auto hold = at(2 * kMs, D7x, false);
        check(hold.verdict == Verdict::kHold && hold.denser, "dense, 2 ms: hold by default");
        check(hold.wakeNs == kLast + kP120 - kAllow, "dense hold wakes at last + P - 1 ms");
        check(at(2 * kMs, D7x, true).verdict == Verdict::kSkip, "dense, 2 ms: skip in literal mode");
        check(at(7'400 * 1'000, D7x, true).verdict == Verdict::kPass, "dense, 7.4 ms passes");
        check(at(7'400 * 1'000, D7x, true).denser, "dense flag reported on pass");
    }

    // Allowance never makes the gap negative: minGap = pace when allowance >= pace.
    {
        const auto d = handoff_gate::decide(5 * kMs, 0, kLast, kLast + 4'500 * 1'000, kMinP, kMaxP,
                kBand, 10 * kMs, false);
        check(d.verdict == Verdict::kHold && d.wakeNs == kLast + 5 * kMs,
                "allowance >= pace: minGap = pace");
    }

    if (failures == 0) {
        std::printf("handoff_gate: all checks passed\n");
        return 0;
    }
    return 1;
}
