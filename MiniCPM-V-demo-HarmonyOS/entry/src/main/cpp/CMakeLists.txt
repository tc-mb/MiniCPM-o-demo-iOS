# Mirrors MiniCPM-V-demo-Android/app/src/main/cpp/CMakeLists.txt so that the
# HarmonyOS port can share the exact same llama.cpp + mtmd source tree as the
# iOS and Android demos. Differences vs Android:
#  * uses HarmonyOS hilog instead of Android's <android/log.h>
#  * does not link the "android" or "log" system libraries; links libhilog_ndk.z
#  * OpenMP is left off in the first iteration; KleidiAI stays on for arm64

cmake_minimum_required(VERSION 3.22.1)

project("entry" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# OHOS_ARCH is provided by the ohos-ndk toolchain (e.g. "arm64-v8a", "x86_64").
# We mirror the Android demo's per-arch defaults exactly.
if(DEFINED OHOS_ARCH)
    message(STATUS "Detected OHOS arch: ${OHOS_ARCH}")
    if(OHOS_ARCH STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        if(NOT DEFINED GGML_CPU_KLEIDIAI)
            set(GGML_CPU_KLEIDIAI ON)
        endif()
    elseif(OHOS_ARCH STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
    else()
        message(WARNING "Unsupported OHOS_ARCH: ${OHOS_ARCH}, defaulting to generic CPU")
        set(GGML_SYSTEM_ARCH "")
        set(GGML_CPU_KLEIDIAI OFF)
    endif()
endif()

# llama.cpp source lives at the repository root so it is shared with the
# iOS and Android demos. Layout:
#   MiniCPM-V-Apps/
#     ├── llama.cpp/
#     ├── MiniCPM-V-demo-Android/
#     ├── MiniCPM-V-demo/                         (iOS)
#     └── MiniCPM-V-demo-HarmonyOS/
#         └── entry/src/main/cpp/CMakeLists.txt   <-- this file
#
# Going up five levels from this file lands us at the repository root.
if(NOT DEFINED LLAMA_SRC)
    set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp)
endif()
get_filename_component(LLAMA_SRC ${LLAMA_SRC} ABSOLUTE)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_SRC}. "
        "Run `git submodule update --init` from the repository root.")
endif()
message(STATUS "Using llama.cpp source at: ${LLAMA_SRC}")

add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/build-llama)

if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()

add_subdirectory(${LLAMA_SRC}/tools/mtmd ${CMAKE_BINARY_DIR}/build-mtmd)

# The .so name MUST match the dependency declared in oh-package.json5
# ("libentry.so") with the leading "lib" stripped, otherwise
# `import nativeModule from 'libentry.so'` will fail at runtime.
add_library(entry SHARED
        napi_init.cpp
        llama_napi.cpp)

target_compile_definitions(entry PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
)

target_include_directories(entry PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd
        # llama-common/peg-parser pulls in <nlohmann/json_fwd.hpp> via chat.h.
        # The vendored nlohmann headers live under llama.cpp/vendor.
        ${LLAMA_SRC}/vendor)

target_link_libraries(entry PRIVATE
        llama
        # Upstream master renamed the common target from `common` to
        # `llama-common` to avoid colliding with downstream projects that
        # already export a `common` target. Stay in sync.
        llama-common
        mtmd
        libace_napi.z.so
        libhilog_ndk.z.so)
