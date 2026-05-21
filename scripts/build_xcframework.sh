#!/usr/bin/env bash
#
# Build llama.xcframework for the MiniCPM-V iOS demo and install it under
# MiniCPM-V-demo/thirdparty/. The repository intentionally does *not* track
# the compiled framework (~189 MB) — this script is the one-shot way to put
# it back wherever a fresh checkout / fresh submodule bump needs it.
#
# Default MINIMAL_MODE=ios builds exactly what the demo's pbxproj links:
#   - ios-arm64                       (real iPhone / iPad)
#   - ios-arm64_x86_64-simulator      (Xcode Simulator)
# Takes ~2-3 min on a modern M-series Mac.
#
# Other valid MINIMAL_MODE values (set via env var):
#   ios-device — only iphoneos arm64 (skip simulator slice)
#   ios-sim    — only iphonesimulator slice (skip device slice)
#   macos      — only native macOS slice (mainly a build-script sanity check)
#   all        — full multi-platform: iOS + macOS + tvOS + xrOS (~25 min)
#
# Usage:
#   ./scripts/build_xcframework.sh                   # default MINIMAL_MODE=ios
#   MINIMAL_MODE=ios-sim ./scripts/build_xcframework.sh
#   MINIMAL_MODE=all     ./scripts/build_xcframework.sh
#
# Re-run when:
#   - The llama.cpp submodule pointer has been bumped in the parent repo
#     (`git submodule status` shows a different commit than your last build).
#   - You edited any source under llama.cpp/ that affects the framework.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
SUBMODULE_DIR="${REPO_ROOT}/llama.cpp"
DEST_DIR="${REPO_ROOT}/MiniCPM-V-demo/thirdparty"
BUILT="${SUBMODULE_DIR}/build-apple/llama.xcframework"

: "${MINIMAL_MODE:=ios}"
export MINIMAL_MODE

# ---- preflight: submodule initialised ----
if [[ ! -f "${SUBMODULE_DIR}/build-xcframework.sh" ]]; then
    cat >&2 <<EOF
Error: llama.cpp submodule is not initialised at:
       ${SUBMODULE_DIR}
       Run this first (shallow + single-branch, see README):
           git submodule update --init --recursive --depth 1 --single-branch
EOF
    exit 1
fi

# ---- preflight: cmake >= 3.28 ----
if ! command -v cmake >/dev/null 2>&1; then
    echo "Error: cmake is not installed.  brew install cmake" >&2
    exit 1
fi
CMAKE_VER="$(cmake --version | head -n1 | awk '{print $3}')"
CMAKE_MAJOR="${CMAKE_VER%%.*}"
CMAKE_REST="${CMAKE_VER#*.}"
CMAKE_MINOR="${CMAKE_REST%%.*}"
: "${CMAKE_MAJOR:=0}"
: "${CMAKE_MINOR:=0}"
if (( CMAKE_MAJOR < 3 || (CMAKE_MAJOR == 3 && CMAKE_MINOR < 28) )); then
    echo "Error: cmake ${CMAKE_VER} is too old; need >= 3.28 (brew upgrade cmake)" >&2
    exit 1
fi

# ---- preflight: xcrun ----
if ! command -v xcrun >/dev/null 2>&1; then
    echo "Error: xcrun not found.  Install Xcode + Command Line Tools." >&2
    exit 1
fi

echo "==> Building llama.xcframework"
echo "    MINIMAL_MODE=${MINIMAL_MODE}"
echo "    submodule:   ${SUBMODULE_DIR}  ($(cd "${SUBMODULE_DIR}" && git rev-parse --short HEAD))"
echo "    install to:  ${DEST_DIR}/llama.xcframework"
echo "    cmake:       ${CMAKE_VER}"
echo

cd "${SUBMODULE_DIR}"
./build-xcframework.sh

if [[ ! -d "${BUILT}" ]]; then
    echo "Error: expected build output not found at ${BUILT}" >&2
    exit 1
fi

mkdir -p "${DEST_DIR}"
rm -rf "${DEST_DIR}/llama.xcframework"
cp -R "${BUILT}" "${DEST_DIR}/llama.xcframework"

SIZE="$(du -sh "${DEST_DIR}/llama.xcframework" | awk '{print $1}')"
echo
echo "✓ llama.xcframework installed:"
echo "    ${DEST_DIR}/llama.xcframework   (${SIZE})"
echo
echo "Next: open MiniCPM-V-demo/MiniCPM-V-demo.xcodeproj in Xcode and Build (⌘B)."
