#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <opus.h>
extern "C" { #include "fvad.h" }

struct VadHandle { Fvad* vad; int mode; };

extern "C" JNIEXPORT jlong JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeInitVad(JNIEnv*,jobject,jint mode){
  VadHandle* h=(VadHandle*)calloc(1,sizeof(VadHandle)); if(!h)return 0; h->vad=fvad_new(); if(!h->vad){free(h);return 0;}
  h->mode=(mode>=0&&mode<=3)?mode:3; if(fvad_set_sample_rate(h->vad,16000)<0||fvad_set_mode(h->vad,h->mode)<0){fvad_free(h->vad);free(h);return 0;} return (jlong)(intptr_t)h;
}
extern "C" JNIEXPORT jint JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeIsSpeechBytes(JNIEnv* env,jobject,jlong handle,jbyteArray data,jint offset,jint length){
  VadHandle* h=(VadHandle*)(intptr_t)handle; if(!h||!h->vad||offset<0||length<=0)return -1; jsize total=env->GetArrayLength(data); if(offset+length>total)return -1; int samples=length/2; if(samples!=160&&samples!=320&&samples!=480)return -1;
  jbyte* bytes=env->GetByteArrayElements(data,nullptr); if(!bytes)return -1; int r=fvad_process(h->vad,(const int16_t*)(bytes+offset),samples); env->ReleaseByteArrayElements(data,bytes,JNI_ABORT); return r;
}
extern "C" JNIEXPORT void JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeResetVad(JNIEnv*,jobject,jlong handle){VadHandle* h=(VadHandle*)(intptr_t)handle;if(h&&h->vad){fvad_reset(h->vad);fvad_set_sample_rate(h->vad,16000);fvad_set_mode(h->vad,h->mode);}}
extern "C" JNIEXPORT void JNICALL Java_io_nannyu_voicesatellite_r1_audio_VadDetector_nativeFreeVad(JNIEnv*,jobject,jlong handle){VadHandle* h=(VadHandle*)(intptr_t)handle;if(h){if(h->vad)fvad_free(h->vad);free(h);}}

extern "C" JNIEXPORT jlong JNICALL Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeInitEncoder(JNIEnv*,jobject,jint rate,jint channels,jint application){int err=0;OpusEncoder* e=opus_encoder_create(rate,channels,application,&err);if(!e||err!=OPUS_OK)return 0;opus_encoder_ctl(e,OPUS_SET_BITRATE(64000));opus_encoder_ctl(e,OPUS_SET_COMPLEXITY(5));opus_encoder_ctl(e,OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));return (jlong)(intptr_t)e;}
extern "C" JNIEXPORT jint JNICALL Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeEncodeBytes(JNIEnv* env,jobject,jlong handle,jbyteArray in,jint inSize,jbyteArray out,jint outSize){OpusEncoder* e=(OpusEncoder*)(intptr_t)handle;if(!e)return -1;jbyte* p=env->GetByteArrayElements(in,nullptr);jbyte* q=env->GetByteArrayElements(out,nullptr);int r=opus_encode(e,(opus_int16*)p,inSize/2,(unsigned char*)q,outSize);env->ReleaseByteArrayElements(in,p,JNI_ABORT);env->ReleaseByteArrayElements(out,q,0);return r;}
extern "C" JNIEXPORT void JNICALL Java_io_nannyu_voicesatellite_r1_audio_OpusEncoder_nativeReleaseEncoder(JNIEnv*,jobject,jlong handle){OpusEncoder* e=(OpusEncoder*)(intptr_t)handle;if(e)opus_encoder_destroy(e);}

extern "C" JNIEXPORT jlong JNICALL Java_io_nannyu_voicesatellite_r1_audio_OpusDecoder_nativeInitDecoder(JNIEnv*,jobject,jint rate,jint channels){int err=0;OpusDecoder* d=opus_decoder_create(rate,channels,&err);return (!d||err!=OPUS_OK)?0:(jlong)(intptr_t)d;}
extern "C" JNIEXPORT jint JNICALL Java_io_nannyu_voicesatellite_r1_audio_OpusDecoder_nativeDecodeBytes(JNIEnv* env,jobject,jlong handle,jbyteArray in,jint inSize,jbyteArray out,jint outSize){OpusDecoder* d=(OpusDecoder*)(intptr_t)handle;if(!d)return -1;jbyte* p=env->GetByteArrayElements(in,nullptr);jbyte* q=env->GetByteArrayElements(out,nullptr);int r=opus_decode(d,(unsigned char*)p,inSize,(opus_int16*)q,outSize/2,0);env->ReleaseByteArrayElements(in,p,JNI_ABORT);env->ReleaseByteArrayElements(out,q,0);return r<0?r:r*2;}
extern "C" JNIEXPORT void JNICALL Java_io_nannyu_voicesatellite_r1_audio_OpusDecoder_nativeReleaseDecoder(JNIEnv*,jobject,jlong handle){OpusDecoder* d=(OpusDecoder*)(intptr_t)handle;if(d)opus_decoder_destroy(d);}
