cmake_minimum_required(VERSION 3.22.1)
project(resonix_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- Oboe (Prefab AAR, built against c++_shared) -----------------------------------
find_package(oboe REQUIRED CONFIG)

# ---- Our native library --------------------------------------------------------------
# Every .cpp below this folder is picked up automatically (jni/, audio/, dsp/ ...),
# so adding a source file never requires editing this script.
file(GLOB_RECURSE RESONIX_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")

add_library(resonix_native SHARED ${RESONIX_SOURCES})

target_include_directories(resonix_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Real-time audio code must be optimised even in debug APKs, otherwise weak TV SoCs glitch.
target_compile_options(resonix_native PRIVATE
    -Wall -Wextra -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(resonix_native PRIVATE -Wl,--gc-sections)

target_link_libraries(resonix_native PRIVATE oboe::oboe log android)

# ---- Optional: our own FFmpeg build (module 3) ---------------------------------------
# CI builds static FFmpeg libs into <project>/third_party/ffmpeg/<ABI>/{include,lib}.
# Until they exist, the library simply builds without FFmpeg.
set(RESONIX_FFMPEG_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ffmpeg/${ANDROID_ABI}")

if(EXISTS "${RESONIX_FFMPEG_ROOT}/lib/libavcodec.a")
    message(STATUS "ResonixTV: FFmpeg found for ${ANDROID_ABI}")
    target_include_directories(resonix_native PRIVATE "${RESONIX_FFMPEG_ROOT}/include")
    target_compile_definitions(resonix_native PRIVATE RESONIX_HAS_FFMPEG=1)
    target_link_libraries(resonix_native PRIVATE
        -Wl,--start-group
        "${RESONIX_FFMPEG_ROOT}/lib/libavformat.a"
        "${RESONIX_FFMPEG_ROOT}/lib/libavcodec.a"
        "${RESONIX_FFMPEG_ROOT}/lib/libswresample.a"
        "${RESONIX_FFMPEG_ROOT}/lib/libavutil.a"
        -Wl,--end-group
        z m)
else()
    message(STATUS "ResonixTV: FFmpeg not found for ${ANDROID_ABI} - building without it")
endif()
