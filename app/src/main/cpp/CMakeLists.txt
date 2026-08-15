# Native build for the on-device LLM (llama.cpp) bridge.
#
# This expects llama.cpp to be checked out as a git submodule at
#   app/src/main/cpp/llama.cpp
# (see docs/local-llm.md). If that directory is missing, the build is skipped
# gracefully so the rest of the app still compiles.

cmake_minimum_required(VERSION 3.22.1)
project(dicio_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(WARNING
        "llama.cpp submodule not found at ${LLAMA_DIR}. "
        "Building a stub library instead. Run: "
        "git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp "
        "(see docs/local-llm.md).")
    # Stub so that System.loadLibrary(\"dicio_llm\") still resolves; every native
    # method throws UnsatisfiedLinkError-equivalent at the Kotlin level because
    # the JNI symbols are absent, and LlamaCppEngine reports \"native lib missing\".
    add_library(dicio_llm SHARED llama-jni-stub.cpp)
    target_link_libraries(dicio_llm android log)
    return()
endif()

# --- llama.cpp configuration -------------------------------------------------
# We only need the library, not the tools/examples/tests/server.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# OpenMP is frequently unavailable / problematic on the Android NDK toolchain.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
# Keep the build lean; NEON is enabled automatically for arm64-v8a.
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama.cpp EXCLUDE_FROM_ALL)

# --- JNI bridge --------------------------------------------------------------
add_library(dicio_llm SHARED llama-jni.cpp)

target_include_directories(dicio_llm PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/common")

target_link_libraries(dicio_llm
    llama
    common
    android
    log)
