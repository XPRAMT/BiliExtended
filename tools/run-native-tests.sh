#!/usr/bin/env bash
# Host-side tests of the pure native decision code (no NDK needed): g++ or clang++ on the PATH.
set -euo pipefail
cd "$(dirname "$0")/.."
CXX="${CXX:-g++}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
for t in test_handoff_gate test_cadence_lock; do
    "$CXX" -std=c++17 -Wall -Wextra -Werror -I app/src/main/cpp "tools/$t.cpp" -o "$out/$t"
    "$out/$t"
done
