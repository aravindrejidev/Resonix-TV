// JNI bridge for com.resonix.tv.audio.DspNative  ->  resonix::dsp::DspEngine
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <new>
#include <string>

#include "dsp/dsp_engine.h"
#include "dsp/selftest.h"

using resonix::dsp::BandParams;
using resonix::dsp::BandType;
using resonix::dsp::DspEngine;

namespace {
inline DspEngine *engine(jlong handle) { return reinterpret_cast<DspEngine *>(handle); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_resonix_tv_audio_DspNative_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) DspEngine());
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_destroy(JNIEnv *, jobject, jlong h) {
    delete engine(h);
}

JNIEXPORT jboolean JNICALL Java_com_resonix_tv_audio_DspNative_configure(
        JNIEnv *, jobject, jlong h, jint sampleRate, jint channelCount, jboolean floatPcm) {
    DspEngine *e = engine(h);
    return (e && e->configure(sampleRate, channelCount, floatPcm == JNI_TRUE)) ? JNI_TRUE : JNI_FALSE;
}

// Returns the number of frames processed, or a negative error code.
JNIEXPORT jint JNICALL Java_com_resonix_tv_audio_DspNative_process(
        JNIEnv *env, jobject, jlong h, jobject input, jint inputOffset, jobject output,
        jint outputOffset, jint frames) {
    DspEngine *e = engine(h);
    if (!e || frames <= 0) return -1;

    auto *inBase = static_cast<uint8_t *>(env->GetDirectBufferAddress(input));
    auto *outBase = static_cast<uint8_t *>(env->GetDirectBufferAddress(output));
    if (!inBase || !outBase) return -2;  // not direct buffers

    const int frameBytes = e->frameBytes();
    const int sampleBytes = e->isFloat() ? 4 : 2;
    const jlong bytes = static_cast<jlong>(frames) * frameBytes;
    if (inputOffset < 0 || outputOffset < 0) return -3;
    if (inputOffset % sampleBytes != 0 || outputOffset % sampleBytes != 0) return -4;
    if (inputOffset + bytes > env->GetDirectBufferCapacity(input)) return -3;
    if (outputOffset + bytes > env->GetDirectBufferCapacity(output)) return -3;

    if (e->isFloat()) {
        e->processFloat(reinterpret_cast<const float *>(inBase + inputOffset),
                        reinterpret_cast<float *>(outBase + outputOffset), frames);
    } else {
        e->processInt16(reinterpret_cast<const int16_t *>(inBase + inputOffset),
                        reinterpret_cast<int16_t *>(outBase + outputOffset), frames);
    }
    return frames;
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_reset(JNIEnv *, jobject, jlong h) {
    if (DspEngine *e = engine(h)) e->reset();
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setBypass(JNIEnv *, jobject, jlong h, jboolean v) {
    if (DspEngine *e = engine(h)) e->setBypass(v == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setMasterGainDb(JNIEnv *, jobject, jlong h, jfloat db) {
    if (DspEngine *e = engine(h)) e->setMasterGainDb(db);
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setChannelGainDb(JNIEnv *, jobject, jlong h, jint ch, jfloat db) {
    if (DspEngine *e = engine(h)) e->setChannelGainDb(ch, db);
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setChannelMute(JNIEnv *, jobject, jlong h, jint ch, jboolean v) {
    if (DspEngine *e = engine(h)) e->setChannelMute(ch, v == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setChannelSolo(JNIEnv *, jobject, jlong h, jint ch, jboolean v) {
    if (DspEngine *e = engine(h)) e->setChannelSolo(ch, v == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setEqEnabled(JNIEnv *, jobject, jlong h, jboolean v) {
    if (DspEngine *e = engine(h)) e->setEqEnabled(v == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setEqChannelMask(JNIEnv *, jobject, jlong h, jint mask) {
    if (DspEngine *e = engine(h)) e->setEqChannelMask(static_cast<uint32_t>(mask));
}

JNIEXPORT void JNICALL Java_com_resonix_tv_audio_DspNative_setEqBand(
        JNIEnv *, jobject, jlong h, jint index, jint type, jfloat freqHz, jfloat gainDb, jfloat q,
        jboolean enabled) {
    DspEngine *e = engine(h);
    if (!e) return;
    BandParams p;
    p.type = static_cast<BandType>(std::clamp<jint>(type, 0, 4));
    p.freqHz = freqHz;
    p.gainDb = gainDb;
    p.q = q;
    p.enabled = enabled == JNI_TRUE;
    e->setEqBand(index, p);
}

// Fills `out` with the linear peak of each channel since the last call; returns the channel count.
JNIEXPORT jint JNICALL Java_com_resonix_tv_audio_DspNative_getPeaks(JNIEnv *env, jobject, jlong h, jfloatArray out) {
    DspEngine *e = engine(h);
    if (!e || !out) return 0;
    float tmp[resonix::dsp::kMaxChannels];
    const int n = std::min<int>(e->readPeaks(tmp, resonix::dsp::kMaxChannels), env->GetArrayLength(out));
    env->SetFloatArrayRegion(out, 0, n, tmp);
    return n;
}

// Runs the built-in DSP self-test on this very device; first line = summary.
JNIEXPORT jstring JNICALL Java_com_resonix_tv_audio_DspNative_selfTest(JNIEnv *env, jobject) {
    const std::string report = resonix::dsp::runSelfTest(3.0);
    return env->NewStringUTF(report.c_str());
}

}  // extern "C"
