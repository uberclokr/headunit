// JNI bridge for the in-app RTL-SDR driver. Thin by design: one native call
// per librtlsdr entry point, all state lives in the rtlsdr_dev_t handle that
// Kotlin carries as an opaque jlong.
#include <jni.h>
#include <stdint.h>
#include <android/log.h>
#include <rtl-sdr.h>

// Helm patch in librtlsdr.c — registers the UsbManager fd for the next open.
extern void rtlsdr_set_android_fd(int fd);

#define TAG "HelmRtl"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define DEV(h) ((rtlsdr_dev_t *)(intptr_t)(h))

JNIEXPORT jlong JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_open(JNIEnv *env, jclass cls, jint fd)
{
    rtlsdr_dev_t *dev = NULL;
    rtlsdr_set_android_fd(fd);
    int r = rtlsdr_open(&dev, 0);
    if (r < 0) {
        LOGE("rtlsdr_open(fd=%d) failed: %d", fd, r);
        return 0;
    }
    return (jlong)(intptr_t)dev;
}

JNIEXPORT void JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_close(JNIEnv *env, jclass cls, jlong h)
{
    if (h) rtlsdr_close(DEV(h));
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_setFrequency(JNIEnv *env, jclass cls, jlong h, jlong hz)
{
    return rtlsdr_set_center_freq(DEV(h), (uint32_t)hz);
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_setDirectSampling(JNIEnv *env, jclass cls, jlong h, jint on)
{
    return rtlsdr_set_direct_sampling(DEV(h), (int)on);
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_setSampleRate(JNIEnv *env, jclass cls, jlong h, jint sps)
{
    return rtlsdr_set_sample_rate(DEV(h), (uint32_t)sps);
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_setTunerGainMode(JNIEnv *env, jclass cls, jlong h, jint manual)
{
    return rtlsdr_set_tuner_gain_mode(DEV(h), manual);
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_setAgcMode(JNIEnv *env, jclass cls, jlong h, jint on)
{
    return rtlsdr_set_agc_mode(DEV(h), on);
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_resetBuffer(JNIEnv *env, jclass cls, jlong h)
{
    return rtlsdr_reset_buffer(DEV(h));
}

// Reads up to len bytes into buf[off..]; returns bytes read or -1.
JNIEXPORT jint JNICALL
Java_com_xterra_helm_sdr_RtlSdrNative_readSync(JNIEnv *env, jclass cls,
                                               jlong h, jbyteArray arr, jint off, jint len)
{
    jbyte *buf = (*env)->GetByteArrayElements(env, arr, NULL);
    if (!buf) return -1;
    int n_read = 0;
    int r = rtlsdr_read_sync(DEV(h), buf + off, len, &n_read);
    (*env)->ReleaseByteArrayElements(env, arr, buf, 0);
    return (r < 0) ? -1 : n_read;
}
