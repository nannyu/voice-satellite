#include <jni.h>
#include <stdint.h>
#include <new>
#include <vector>
#include <opus.h>

namespace {
struct Encoder { OpusEncoder* state; int channels; };
struct Decoder { OpusDecoder* state; int channels; };
bool range(JNIEnv* env, jbyteArray data, jint length) {
    return data && length > 0 && length <= env->GetArrayLength(data);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeInitEncoder(
        JNIEnv*, jobject, jint rate, jint channels, jint application) {
    if (channels != 1 && channels != 2) return 0;
    int error = 0;
    OpusEncoder* state = opus_encoder_create(rate, channels, application, &error);
    if (!state || error != OPUS_OK) { if (state) opus_encoder_destroy(state); return 0; }
    if (opus_encoder_ctl(state, OPUS_SET_BITRATE(24000)) != OPUS_OK
            || opus_encoder_ctl(state, OPUS_SET_COMPLEXITY(5)) != OPUS_OK
            || opus_encoder_ctl(state, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK) {
        opus_encoder_destroy(state); return 0;
    }
    Encoder* handle = new (std::nothrow) Encoder{state, channels};
    if (!handle) { opus_encoder_destroy(state); return 0; }
    return (jlong)(intptr_t)handle;
}
extern "C" JNIEXPORT jint JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeLookahead(JNIEnv*, jobject, jlong raw) {
    Encoder* handle = (Encoder*)(intptr_t)raw;
    if (!handle) return OPUS_BAD_ARG;
    opus_int32 samples = 0;
    int error = opus_encoder_ctl(handle->state, OPUS_GET_LOOKAHEAD(&samples));
    return error == OPUS_OK ? samples : error;
}
extern "C" JNIEXPORT jint JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeEncodeBytes(
        JNIEnv* env, jobject, jlong raw, jbyteArray input, jint inputSize, jbyteArray output, jint outputSize) {
    Encoder* handle = (Encoder*)(intptr_t)raw;
    if (!handle || !range(env, input, inputSize) || !range(env, output, outputSize)
            || inputSize > 23040 || outputSize > 4000 || inputSize % (2 * handle->channels))
        return OPUS_BAD_ARG;
    // Explicit little-endian conversion avoids unaligned access and channel-size mistakes.
    std::vector<jbyte> bytes(inputSize);
    std::vector<opus_int16> pcm(inputSize / 2);
    std::vector<unsigned char> packet(outputSize);
    env->GetByteArrayRegion(input, 0, inputSize, bytes.data());
    if (env->ExceptionCheck()) return OPUS_BAD_ARG;
    for (int i = 0; i < inputSize / 2; ++i)
        pcm[i] = (opus_int16)((uint8_t)bytes[2*i] | ((uint16_t)(uint8_t)bytes[2*i+1] << 8));
    int count = opus_encode(handle->state, pcm.data(), inputSize / (2 * handle->channels),
                            packet.data(), outputSize);
    if (count > 0) env->SetByteArrayRegion(output, 0, count, (const jbyte*)packet.data());
    return count;
}
extern "C" JNIEXPORT void JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeReleaseEncoder(JNIEnv*, jobject, jlong raw) {
    Encoder* handle = (Encoder*)(intptr_t)raw;
    if (handle) { opus_encoder_destroy(handle->state); delete handle; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusDecoder_nativeInitDecoder(
        JNIEnv*, jobject, jint rate, jint channels) {
    if (channels != 1 && channels != 2) return 0;
    int error = 0;
    OpusDecoder* state = opus_decoder_create(rate, channels, &error);
    if (!state || error != OPUS_OK) { if (state) opus_decoder_destroy(state); return 0; }
    Decoder* handle = new (std::nothrow) Decoder{state, channels};
    if (!handle) { opus_decoder_destroy(state); return 0; }
    return (jlong)(intptr_t)handle;
}
extern "C" JNIEXPORT jint JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusDecoder_nativeDecodeBytes(
        JNIEnv* env, jobject, jlong raw, jbyteArray input, jint inputSize, jbyteArray output, jint outputSize) {
    Decoder* handle = (Decoder*)(intptr_t)raw;
    if (!handle || !range(env, input, inputSize) || !range(env, output, outputSize)
            || inputSize > 4000 || outputSize > 23040 || outputSize % (2 * handle->channels))
        return OPUS_BAD_ARG;
    std::vector<jbyte> packet(inputSize);
    std::vector<opus_int16> pcm(outputSize / 2);
    env->GetByteArrayRegion(input, 0, inputSize, packet.data());
    if (env->ExceptionCheck()) return OPUS_BAD_ARG;
    int samples = opus_decode(handle->state, (const unsigned char*)packet.data(), inputSize,
                              pcm.data(), outputSize / (2 * handle->channels), 0);
    if (samples <= 0) return samples;
    int count = samples * handle->channels * 2;
    std::vector<jbyte> bytes(count);
    for (int i = 0; i < count / 2; ++i) {
        uint16_t value = (uint16_t)pcm[i];
        bytes[2*i] = (jbyte)(value & 255); bytes[2*i+1] = (jbyte)(value >> 8);
    }
    env->SetByteArrayRegion(output, 0, count, bytes.data());
    return count;
}
extern "C" JNIEXPORT void JNICALL
Java_io_nannyu_voicesatellite_r1_audio_OpusDecoder_nativeReleaseDecoder(JNIEnv*, jobject, jlong raw) {
    Decoder* handle = (Decoder*)(intptr_t)raw;
    if (handle) { opus_decoder_destroy(handle->state); delete handle; }
}
