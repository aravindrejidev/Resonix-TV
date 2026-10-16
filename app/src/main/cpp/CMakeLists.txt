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

# ---- Optional: FFmpeg audio decoders for ExoPlayer (module 3b) -------------------------
# scripts/setup_ffmpeg.sh fetches Media3's decoder_ffmpeg module and builds FFmpeg (LGPL,
# audio decoders only) into <project>/third_party/media3-ffmpeg/jni. That folder brings its own
# CMake target "ffmpegJNI". Without it the app still builds and ExoPlayer falls back to the
# device's MediaCodec decoders (no DTS / TrueHD on most TVs).
set(RESONIX_MEDIA3_FFMPEG_JNI
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/media3-ffmpeg/jni")

if(EXISTS "${RESONIX_MEDIA3_FFMPEG_JNI}/CMakeLists.txt" AND
   EXISTS "${RESONIX_MEDIA3_FFMPEG_JNI}/ffmpeg/android-libs/${ANDROID_ABI}/libavcodec.a")
    message(STATUS "ResonixTV: building FFmpeg decoder JNI for ${ANDROID_ABI}")
    add_subdirectory("${RESONIX_MEDIA3_FFMPEG_JNI}" "${CMAKE_CURRENT_BINARY_DIR}/media3_ffmpeg_jni")
else()
    message(STATUS "ResonixTV: FFmpeg decoder libs not found for ${ANDROID_ABI} - skipping")
endif()
