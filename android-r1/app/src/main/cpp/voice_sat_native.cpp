#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
extern "C" {
#include "fvad.h"
}

// VAD JNI is kept separate from opus_jni.cpp so host E2E uses the production codec source.
struct VadHandle { Fvad* vad; int mode; };
extern "C" JNIEXPORT jlong JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeInitVad(JNIEnv*,jobject,jint mode){VadHandle* h=(VadHandle*)calloc(1,sizeof(VadHandle));if(!h)return 0;h->vad=fvad_new();if(!h->vad){free(h);return 0;}h->mode=(mode>=0&&mode<=3)?mode:3;if(fvad_set_sample_rate(h->vad,16000)<0||fvad_set_mode(h->vad,h->mode)<0){fvad_free(h->vad);free(h);return 0;}return (jlong)(intptr_t)h;}
extern "C" JNIEXPORT jint JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeIsSpeechBytes(JNIEnv* env,jobject,jlong handle,jbyteArray data,jint offset,jint length){VadHandle* h=(VadHandle*)(intptr_t)handle;if(!h||!h->vad||!data||offset<0||length<=0)return -1;jsize total=env->GetArrayLength(data);if(length>total||offset>total-length)return -1;int samples=length/2;if(samples!=160&&samples!=320&&samples!=480)return -1;jbyte* bytes=env->GetByteArrayElements(data,nullptr);if(!bytes)return -1;int r=fvad_process(h->vad,(const int16_t*)(bytes+offset),samples);env->ReleaseByteArrayElements(data,bytes,JNI_ABORT);return r;}
extern "C" JNIEXPORT void JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeResetVad(JNIEnv*,jobject,jlong handle){VadHandle* h=(VadHandle*)(intptr_t)handle;if(h&&h->vad){fvad_reset(h->vad);fvad_set_sample_rate(h->vad,16000);fvad_set_mode(h->vad,h->mode);}}
extern "C" JNIEXPORT void JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeFreeVad(JNIEnv*,jobject,jlong handle){VadHandle* h=(VadHandle*)(intptr_t)handle;if(h){if(h->vad)fvad_free(h->vad);free(h);}}
