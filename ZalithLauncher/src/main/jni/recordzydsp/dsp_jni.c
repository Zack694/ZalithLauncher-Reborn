/*
 * JNI bridge for RecordZy voice DSP.
 *
 * Wraps Xiph RNNoise (BSD) so the recorder can denoise the push-to-talk mic:
 * removes background noise (fans, keyboard, hiss, ambient) while keeping speech,
 * and returns a voice-activity probability that can drive gating.
 *
 * RNNoise works on fixed 480-sample (10 ms @ 48 kHz) mono frames of 16-bit PCM
 * represented as float in the int16 amplitude range.
 */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "rnnoise.h"

#define RZ_MAX_FRAME 960 /* safety cap; rnnoise frame is 480 */

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_RnnoiseDenoiser_nativeFrameSize(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jint) rnnoise_get_frame_size();
}

JNIEXPORT jlong JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_RnnoiseDenoiser_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    DenoiseState *st = rnnoise_create(NULL); /* NULL => bundled default model */
    return (jlong) (intptr_t) st;
}

/*
 * Denoise one mono frame in place. `frame` must be exactly the RNNoise frame
 * size (480). Returns the voice-activity probability [0..1].
 */
JNIEXPORT jfloat JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_RnnoiseDenoiser_nativeProcess(JNIEnv *env, jclass clazz,
                                                                             jlong handle, jshortArray frame) {
    (void) clazz;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    if (st == NULL || frame == NULL) return 0.0f;

    int fs = rnnoise_get_frame_size();
    if (fs <= 0 || fs > RZ_MAX_FRAME) return 0.0f;

    jsize n = (*env)->GetArrayLength(env, frame);
    if (n < fs) return 0.0f;

    jshort *s = (*env)->GetShortArrayElements(env, frame, NULL);
    if (s == NULL) return 0.0f;

    float buf[RZ_MAX_FRAME];
    for (int i = 0; i < fs; i++) buf[i] = (float) s[i];

    float vad = rnnoise_process_frame(st, buf, buf);

    for (int i = 0; i < fs; i++) {
        float v = buf[i];
        int iv = (int) (v >= 0.0f ? v + 0.5f : v - 0.5f);
        if (iv > 32767) iv = 32767;
        else if (iv < -32768) iv = -32768;
        s[i] = (jshort) iv;
    }
    (*env)->ReleaseShortArrayElements(env, frame, s, 0); /* commit */
    return vad;
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_RnnoiseDenoiser_nativeDestroy(JNIEnv *env, jclass clazz,
                                                                             jlong handle) {
    (void) env; (void) clazz;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    if (st != NULL) rnnoise_destroy(st);
}
