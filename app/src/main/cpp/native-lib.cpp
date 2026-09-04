#include <jni.h>

#include "AudioEngine.h"

// JNI fino: converte tipos e delega. Nenhuma lógica de áudio aqui.

namespace {
cadentia::AudioEngine* engine(jlong handle) {
    return reinterpret_cast<cadentia::AudioEngine*>(handle);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new cadentia::AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete engine(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeStart(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeStop(JNIEnv*, jobject, jlong handle) {
    engine(handle)->stop();
}

JNIEXPORT jlong JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeNowFrames(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->nowFrames();
}

JNIEXPORT jint JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSampleRate(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeFramesPerBurst(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->framesPerBurst();
}

JNIEXPORT jint JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeXRunCount(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->xrunCount();
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSetVoiceMix(JNIEnv*, jobject, jlong handle, jlong voiceTag, jfloat gain, jfloat pan) {
    engine(handle)->setVoiceMix(voiceTag, gain, pan);
}

JNIEXPORT jint JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeRestartCount(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->restarts();
}

JNIEXPORT jint JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeBufferSizeInFrames(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->bufferSizeInFrames();
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeRegisterBuffer(
        JNIEnv* env, jobject, jlong handle, jint id, jfloatArray interleaved, jint channels) {
    const jsize len = env->GetArrayLength(interleaved);
    const jint ch = channels == 1 ? 1 : 2;
    jfloat* data = env->GetFloatArrayElements(interleaved, nullptr);
    engine(handle)->registerBuffer(id, data, len / ch, ch);
    env->ReleaseFloatArrayElements(interleaved, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeReleaseBuffer(
        JNIEnv*, jobject, jlong handle, jint id) {
    engine(handle)->releaseBuffer(id);
}

JNIEXPORT jlong JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSchedule(
        JNIEnv*, jobject, jlong handle, jint bufferId, jlong atFrame, jfloat gain, jfloat pan, jfloat rate) {
    return engine(handle)->schedule(bufferId, atFrame, gain, pan, rate);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSetVoiceRate(
        JNIEnv*, jobject, jlong handle, jlong voiceTag, jfloat rate) {
    engine(handle)->setVoiceRate(voiceTag, rate);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSetDrive(
        JNIEnv*, jobject, jlong handle, jboolean enabled, jfloat amount) {
    engine(handle)->setDrive(enabled == JNI_TRUE, amount);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSetMasterGain(
        JNIEnv*, jobject, jlong handle, jfloat gain) {
    engine(handle)->setMasterGain(gain);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeDamp(
        JNIEnv*, jobject, jlong handle, jlong voiceTag, jfloat overSeconds) {
    engine(handle)->damp(voiceTag, overSeconds);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeDampAll(
        JNIEnv*, jobject, jlong handle, jfloat overSeconds) {
    engine(handle)->dampAll(overSeconds);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSetReverb(
        JNIEnv*, jobject, jlong handle, jboolean enabled, jfloat mix) {
    engine(handle)->setReverb(enabled == JNI_TRUE, mix);
}

JNIEXPORT void JNICALL
Java_com_levelhard_cadentia_audio_AudioEngineBridge_nativeSetDelay(
        JNIEnv*, jobject, jlong handle, jboolean enabled, jfloat timeMs, jfloat feedback, jfloat mix) {
    engine(handle)->setDelay(enabled == JNI_TRUE, timeMs, feedback, mix);
}

} // extern "C"
