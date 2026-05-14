#!/usr/bin/env bash
#
# Build & run a native (macOS) smoke-test for the demo's MBMtmd C bridge.
# This exists so we can validate the bridge on a desktop tool-chain without
# spinning up the iOS Simulator.  The test driver lives in scripts/bridge_test.mm
# and links against the libllama / libmtmd / libggml dylibs produced by
#   `llama.cpp/build-cli-test/`
# (which itself is set up with the same flags the iOS xcframework build uses).
#
# Usage:
#   scripts/build_bridge_test.sh [model.gguf] [mmproj.gguf] [image] [prompt]
#
# All four positional args are optional and default to the local v4.6 GGUF +
# the upstream tools/mtmd/test-1.jpeg + a generic "describe this image" prompt.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_DIR="${REPO_ROOT}/llama.cpp"
BUILD_DIR="${LLAMA_DIR}/build-cli-test"
BIN_DIR="${BUILD_DIR}/bin"
BRIDGE_DIR="${REPO_ROOT}/MiniCPM-V-demo/MTMDWrapper/Bridge"
SCRIPT_DIR="${REPO_ROOT}/scripts"
TMP_DIR="$(mktemp -d -t mb_bridge_test.XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

if [[ ! -d "${BIN_DIR}" ]]; then
    echo "[build_bridge_test] ${BIN_DIR} not found." >&2
    echo "  Run this once first:" >&2
    echo "    cd ${LLAMA_DIR} && cmake -B build-cli-test -G Ninja -DGGML_METAL=ON \\" >&2
    echo "      -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF \\" >&2
    echo "      -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF && \\" >&2
    echo "      cmake --build build-cli-test --target llama-mtmd-cli -j 8" >&2
    exit 1
fi

# The bridge source uses `#include <llama/llama.h>` style (correct for the
# iOS framework consumer).  When linking against the raw build directory we
# need plain `<llama.h>` — rewrite a private copy.
TEST_BRIDGE_SRC="${TMP_DIR}/MBMtmd-test.mm"
sed \
    -e 's|<llama/llama.h>|<llama.h>|' \
    -e 's|<llama/mtmd.h>|<mtmd.h>|' \
    -e 's|<llama/mtmd-helper.h>|<mtmd-helper.h>|' \
    "${BRIDGE_DIR}/MBMtmd.mm" > "${TEST_BRIDGE_SRC}"

OUT="${TMP_DIR}/bridge_test"

echo "[build_bridge_test] compiling..."
clang++ \
    -std=c++20 -fobjc-arc -x objective-c++ \
    -I "${LLAMA_DIR}/include" \
    -I "${LLAMA_DIR}/tools/mtmd" \
    -I "${LLAMA_DIR}/ggml/include" \
    -I "${BRIDGE_DIR}" \
    -L "${BIN_DIR}" \
    -Wl,-rpath,"${BIN_DIR}" \
    -lllama -lmtmd \
    -lggml -lggml-base -lggml-cpu -lggml-blas -lggml-metal \
    -framework Foundation -framework Accelerate -framework Metal \
    "${TEST_BRIDGE_SRC}" "${SCRIPT_DIR}/bridge_test.mm" \
    -o "${OUT}"

# Defaults — tweak via positional args if you want to point at a different
# model, mmproj or test image.
DEFAULT_MODEL="/Users/tianchi/model/Release/MiniCPM-V-4.6/hf/MiniCPM-V-4.6-gguf/MiniCPM-V-4_6-Q4_K_M.gguf"
DEFAULT_MMPROJ="/Users/tianchi/model/Release/MiniCPM-V-4.6/hf/MiniCPM-V-4.6-gguf/mmproj-model-f16.gguf"
DEFAULT_IMAGE="${LLAMA_DIR}/tools/mtmd/test-1.jpeg"
DEFAULT_PROMPT="Please describe this image in one sentence."

MODEL="${1:-$DEFAULT_MODEL}"
MMPROJ="${2:-$DEFAULT_MMPROJ}"
IMAGE="${3:-$DEFAULT_IMAGE}"
PROMPT="${4:-$DEFAULT_PROMPT}"

echo "[build_bridge_test] running..."
echo "  model:  ${MODEL}"
echo "  mmproj: ${MMPROJ}"
echo "  image:  ${IMAGE}"
echo "  prompt: ${PROMPT}"
echo

"${OUT}" "${MODEL}" "${MMPROJ}" "${IMAGE}" "${PROMPT}"
