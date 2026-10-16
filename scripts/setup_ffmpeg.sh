#!/usr/bin/env bash
# ResonixTV module 3b - FFmpeg audio decoders for ExoPlayer.
#
# Fetches the official Media3 "decoder_ffmpeg" extension (same tag as the Media3 version in
# gradle/libs.versions.toml) and cross-compiles FFmpeg for Android with Media3's own build
# script. LGPL only (no --enable-gpl): audio decoders, nothing else.
#
# Result (both folders are git-ignored):
#   third_party/media3-ffmpeg/jni/                       JNI sources, FFmpeg headers, static libs
#   app/src/main/java/androidx/media3/decoder/ffmpeg/    Java sources of the extension
# app/src/main/cpp/CMakeLists.txt picks the first folder up automatically.
#
# Usage:  NDK_PATH=$ANDROID_HOME/ndk/28.2.13676358 scripts/setup_ffmpeg.sh
# Optional env: MEDIA3_VERSION FFMPEG_REF DECODERS ANDROID_API HOST_PLATFORM FORCE=1 PROJECT_DIR
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

MEDIA3_VERSION="${MEDIA3_VERSION:-$(sed -n 's/^media3 = "\([^"]*\)".*/\1/p' "$PROJECT_DIR/gradle/libs.versions.toml" 2>/dev/null | head -n1)}"
MEDIA3_REPO="${MEDIA3_REPO:-https://github.com/androidx/media.git}"
FFMPEG_REPO="${FFMPEG_REPO:-https://github.com/FFmpeg/FFmpeg.git}"
FFMPEG_REF="${FFMPEG_REF:-}"          # empty = the branch recommended by Media3's README
ANDROID_API="${ANDROID_API:-26}"      # = minSdk of the app
HOST_PLATFORM="${HOST_PLATFORM:-linux-x86_64}"
DECODERS="${DECODERS:-ac3 eac3 dca truehd mlp flac alac opus vorbis mp3 aac pcm_mulaw pcm_alaw}"
NDK_PATH="${NDK_PATH:-${ANDROID_NDK_HOME:-}}"
FORCE="${FORCE:-0}"

log() { printf '[setup_ffmpeg] %s\n' "$*"; }
die() { printf '[setup_ffmpeg] ERROR: %s\n' "$*" >&2; exit 1; }

[ -n "$MEDIA3_VERSION" ] || die "cannot read the Media3 version from gradle/libs.versions.toml"
[ -d "$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM" ] ||
    die "NDK not found at '$NDK_PATH' (set NDK_PATH to the NDK folder)"

DEST="$PROJECT_DIR/third_party/media3-ffmpeg"
JAVA_DEST="$PROJECT_DIR/app/src/main/java/androidx/media3/decoder/ffmpeg"
STAMP="media3=$MEDIA3_VERSION api=$ANDROID_API decoders=$DECODERS"

if [ "$FORCE" != "1" ] && [ -f "$DEST/STAMP" ] && [ -d "$JAVA_DEST" ] &&
   [ -f "$DEST/jni/ffmpeg/android-libs/arm64-v8a/libavcodec.a" ] &&
   [ "$(head -n1 "$DEST/STAMP")" = "$STAMP" ]; then
    log "already up to date ($STAMP) - nothing to do"
    exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log "cloning Media3 $MEDIA3_VERSION"
git clone --quiet --depth 1 --branch "$MEDIA3_VERSION" "$MEDIA3_REPO" "$WORK/media"

MODULE="$WORK/media/libraries/decoder_ffmpeg/src/main"
README="$WORK/media/libraries/decoder_ffmpeg/README.md"
[ -f "$MODULE/jni/build_ffmpeg.sh" ] || die "Media3 $MEDIA3_VERSION has no decoder_ffmpeg/jni/build_ffmpeg.sh"

if [ -z "$FFMPEG_REF" ]; then
    FFMPEG_REF="$(grep -oE 'release/[0-9]+\.[0-9]+' "$README" 2>/dev/null | head -n1 || true)"
    FFMPEG_REF="${FFMPEG_REF:-release/6.0}"
fi
log "cloning FFmpeg $FFMPEG_REF"
git clone --quiet --depth 1 --branch "$FFMPEG_REF" "$FFMPEG_REPO" "$MODULE/jni/ffmpeg"

log "building FFmpeg decoders: $DECODERS"
read -r -a DECODER_LIST <<< "$DECODERS"
(
    cd "$MODULE/jni"
    chmod +x build_ffmpeg.sh
    ./build_ffmpeg.sh "$MODULE" "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_API" "${DECODER_LIST[@]}"
)

LIBS="$MODULE/jni/ffmpeg/android-libs"
BUILT=0
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    if [ -f "$LIBS/$abi/libavcodec.a" ]; then
        BUILT=$((BUILT + 1))
        log "  built: $abi"
    fi
done
[ -f "$LIBS/arm64-v8a/libavcodec.a" ] || die "FFmpeg build finished but arm64-v8a libraries are missing"

JAVA_SRC="$MODULE/java/androidx/media3/decoder/ffmpeg"
[ -d "$JAVA_SRC" ] || die "Java sources of the extension not found at $JAVA_SRC"

# The JNI build only needs FFmpeg's headers and the static libraries - drop everything else.
find "$MODULE/jni/ffmpeg" -type f ! -name '*.h' ! -path '*/android-libs/*' -delete
find "$MODULE/jni/ffmpeg" -type d -empty -delete

rm -rf "$DEST" "$JAVA_DEST"
mkdir -p "$DEST" "$JAVA_DEST"
cp -a "$MODULE/jni" "$DEST/jni"
cp "$JAVA_SRC"/*.java "$JAVA_DEST/"
printf '%s\nffmpeg=%s abis=%s\n' "$STAMP" "$FFMPEG_REF" "$BUILT" > "$DEST/STAMP"

log "done: $BUILT ABI(s), $(du -sh "$DEST" | cut -f1) in third_party/media3-ffmpeg, $(ls "$JAVA_DEST" | wc -l) Java files"
