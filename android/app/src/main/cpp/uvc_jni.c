#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <turbojpeg.h>
#include <unistd.h>

#include "yuv.h"

#define TAG "mobdroid-uvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define FIRST_FRAME_GRACE_NS 3000000000LL
#define MAX_DESCRIPTOR_INTS 8192

typedef struct {
    libusb_context *usb;
    uvc_context_t *uvc;
    uvc_device_handle_t *devh;
    tjhandle jpeg;
    ANativeWindow *window;
    pthread_t eventThread;
    int eventThreadStarted;
    atomic_bool eventsStop;
    int streaming;
    int width;
    int height;
    int threadNamed;
    unsigned framesLogged;
    uint64_t framesOk;
    uint64_t framesBad;
    atomic_bool stopping;
    _Atomic int64_t lastFrameNs;
} Camera;

static int64_t nowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void throwNew(JNIEnv *env, const char *className, const char *format, ...) {
    char message[192];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    jclass type = (*env)->FindClass(env, className);
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
        (*env)->DeleteLocalRef(env, type);
    }
}

static int setGeometry(Camera *c, int width, int height) {
    if (width == c->width && height == c->height) {
        return 1;
    }
    if (ANativeWindow_setBuffersGeometry(c->window, width, height, WINDOW_FORMAT_RGBA_8888) != 0) {
        return 0;
    }
    c->width = width;
    c->height = height;
    return 1;
}

static int fits(const ANativeWindow_Buffer *buffer, int width, int height) {
    return buffer->width == width && buffer->height == height &&
           (buffer->format == WINDOW_FORMAT_RGBA_8888 || buffer->format == WINDOW_FORMAT_RGBX_8888);
}

static int renderMjpeg(Camera *c, const uvc_frame_t *frame) {
    if (tj3DecompressHeader(c->jpeg, frame->data, frame->data_bytes) != 0) {
        return 0;
    }
    int width = tj3Get(c->jpeg, TJPARAM_JPEGWIDTH);
    int height = tj3Get(c->jpeg, TJPARAM_JPEGHEIGHT);
    if (width <= 0 || height <= 0 || !setGeometry(c, width, height)) {
        return 0;
    }
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(c->window, &buffer, NULL) != 0) {
        return 0;
    }
    int ok = fits(&buffer, width, height) &&
             tj3Decompress8(c->jpeg, frame->data, frame->data_bytes, buffer.bits, buffer.stride * 4,
                            TJPF_RGBA) == 0;
    ANativeWindow_unlockAndPost(c->window);
    return ok;
}

static int renderYuyv(Camera *c, const uvc_frame_t *frame) {
    int width = (int)frame->width;
    int height = (int)frame->height;
    if (width <= 0 || height <= 0 || (width & 1) != 0 || frame->data_bytes < (size_t)width * height * 2 ||
        !setGeometry(c, width, height)) {
        return 0;
    }
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(c->window, &buffer, NULL) != 0) {
        return 0;
    }
    int ok = fits(&buffer, width, height);
    if (ok) {
        yuyvToRgba(frame->data, (size_t)width * 2, buffer.bits, (size_t)buffer.stride * 4, width, height);
    }
    ANativeWindow_unlockAndPost(c->window);
    return ok;
}

static void onFrame(uvc_frame_t *frame, void *user) {
    Camera *c = user;
    if (atomic_load_explicit(&c->stopping, memory_order_relaxed)) {
        return;
    }
    if (!c->threadNamed) {
        pthread_setname_np(pthread_self(), "uvc-frames");
        c->threadNamed = 1;
    }
    int ok = 0;
    if (frame->frame_format == UVC_FRAME_FORMAT_MJPEG) {
        ok = renderMjpeg(c, frame);
        if (!ok && c->framesLogged < 5) {
            c->framesLogged++;
            LOGW("MJPEG frame dropped: %s", tj3GetErrorStr(c->jpeg));
        }
    } else if (frame->frame_format == UVC_FRAME_FORMAT_YUYV) {
        ok = renderYuyv(c, frame);
    }
    if (ok) {
        c->framesOk++;
        atomic_store_explicit(&c->lastFrameNs, nowNs(), memory_order_relaxed);
    } else {
        c->framesBad++;
    }
}

static void *eventLoop(void *arg) {
    Camera *c = arg;
    pthread_setname_np(pthread_self(), "uvc-usb");
    while (!atomic_load_explicit(&c->eventsStop, memory_order_acquire)) {
        int result = libusb_handle_events(c->usb);
        if (result < 0 && result != LIBUSB_ERROR_INTERRUPTED) {
            usleep(10000);
        }
    }
    return NULL;
}

static void stopStream(Camera *c) {
    if (!c->streaming) {
        return;
    }
    atomic_store_explicit(&c->stopping, 1, memory_order_relaxed);
    uvc_stop_streaming(c->devh);
    ANativeWindow_release(c->window);
    c->window = NULL;
    c->streaming = 0;
    LOGI("stopped: %llu frames shown, %llu dropped", (unsigned long long)c->framesOk,
         (unsigned long long)c->framesBad);
}

static void destroy(Camera *c) {
    stopStream(c);
    if (c->devh != NULL) {
        uvc_close(c->devh);
    }
    if (c->uvc != NULL) {
        uvc_exit(c->uvc);
    }
    if (c->eventThreadStarted) {
        atomic_store_explicit(&c->eventsStop, 1, memory_order_release);
        libusb_interrupt_event_handler(c->usb);
        pthread_join(c->eventThread, NULL);
    }
    if (c->usb != NULL) {
        libusb_exit(c->usb);
    }
    if (c->jpeg != NULL) {
        tj3Destroy(c->jpeg);
    }
    free(c);
}

JNIEXPORT jlong JNICALL Java_app_mobdroid_uvc_UvcNative_open(JNIEnv *env, jobject thiz, jint fd) {
    Camera *c = calloc(1, sizeof(Camera));
    if (c == NULL) {
        throwNew(env, "java/io/IOException", "Out of memory");
        return 0;
    }
    struct libusb_init_option option = {.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY};
    int usbResult = libusb_init_context(&c->usb, &option, 1);
    if (usbResult != 0) {
        c->usb = NULL;
        throwNew(env, "java/io/IOException", "libusb init failed: %s", libusb_strerror(usbResult));
        destroy(c);
        return 0;
    }
    uvc_error_t uvcResult = uvc_init(&c->uvc, c->usb);
    if (uvcResult != UVC_SUCCESS) {
        c->uvc = NULL;
        throwNew(env, "java/io/IOException", "libuvc init failed: %s", uvc_strerror(uvcResult));
        destroy(c);
        return 0;
    }
    if (pthread_create(&c->eventThread, NULL, eventLoop, c) != 0) {
        throwNew(env, "java/io/IOException", "Cannot start the USB event thread");
        destroy(c);
        return 0;
    }
    c->eventThreadStarted = 1;
    uvcResult = uvc_wrap(fd, c->uvc, &c->devh);
    if (uvcResult != UVC_SUCCESS) {
        c->devh = NULL;
        throwNew(env, "java/io/IOException", "Cannot open the UVC device: %s", uvc_strerror(uvcResult));
        destroy(c);
        return 0;
    }
    c->jpeg = tj3Init(TJINIT_DECOMPRESS);
    if (c->jpeg == NULL) {
        throwNew(env, "java/io/IOException", "Cannot create the JPEG decoder");
        destroy(c);
        return 0;
    }
    tj3Set(c->jpeg, TJPARAM_FASTUPSAMPLE, 1);
    tj3Set(c->jpeg, TJPARAM_FASTDCT, 1);
    tj3Set(c->jpeg, TJPARAM_MAXPIXELS, 64 * 1024 * 1024);
    LOGI("opened");
    return (jlong)(uintptr_t)c;
}

JNIEXPORT jintArray JNICALL Java_app_mobdroid_uvc_UvcNative_frameDescriptors(JNIEnv *env, jobject thiz,
                                                                            jlong handle) {
    Camera *c = (Camera *)(uintptr_t)handle;
    jint *out = malloc(sizeof(jint) * MAX_DESCRIPTOR_INTS);
    if (out == NULL) {
        throwNew(env, "java/io/IOException", "Out of memory");
        return NULL;
    }
    int n = 0;
    uvc_device_info_t *info = c->devh->info;
    for (uvc_streaming_interface_t *si = info != NULL ? info->stream_ifs : NULL; si != NULL; si = si->next) {
        for (uvc_format_desc_t *format = si->format_descs; format != NULL; format = format->next) {
            enum uvc_frame_format type = uvc_frame_format_for_guid(format->guidFormat);
            if (type != UVC_FRAME_FORMAT_MJPEG && type != UVC_FRAME_FORMAT_YUYV) {
                continue;
            }
            for (uvc_frame_desc_t *frame = format->frame_descs; frame != NULL; frame = frame->next) {
                int count = 0;
                while (frame->intervals != NULL && frame->intervals[count] != 0) {
                    count++;
                }
                if (n + 4 + (count > 0 ? count : 3) > MAX_DESCRIPTOR_INTS) {
                    break;
                }
                out[n++] = type == UVC_FRAME_FORMAT_MJPEG;
                out[n++] = frame->wWidth;
                out[n++] = frame->wHeight;
                out[n++] = count;
                if (count > 0) {
                    for (int i = 0; i < count; i++) {
                        out[n++] = (jint)frame->intervals[i];
                    }
                } else {
                    out[n++] = (jint)frame->dwMinFrameInterval;
                    out[n++] = (jint)frame->dwMaxFrameInterval;
                    out[n++] = (jint)frame->dwFrameIntervalStep;
                }
            }
        }
    }
    jintArray result = (*env)->NewIntArray(env, n);
    if (result != NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, n, out);
    }
    free(out);
    return result;
}

JNIEXPORT void JNICALL Java_app_mobdroid_uvc_UvcNative_start(JNIEnv *env, jobject thiz, jlong handle,
                                                             jboolean mjpeg, jint width, jint height, jint fps,
                                                             jobject surface) {
    Camera *c = (Camera *)(uintptr_t)handle;
    if (c->streaming) {
        throwNew(env, "java/lang/IllegalStateException", "Already streaming");
        return;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        throwNew(env, "java/lang/IllegalArgumentException", "Invalid surface");
        return;
    }
    if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) != 0) {
        ANativeWindow_release(window);
        throwNew(env, "java/io/IOException", "The surface rejected %dx%d buffers", width, height);
        return;
    }
    uvc_stream_ctrl_t ctrl;
    uvc_error_t result = uvc_get_stream_ctrl_format_size(
        c->devh, &ctrl, mjpeg ? UVC_FRAME_FORMAT_MJPEG : UVC_FRAME_FORMAT_YUYV, width, height, fps);
    if (result != UVC_SUCCESS) {
        ANativeWindow_release(window);
        throwNew(env, "java/io/IOException", "The camera rejected %dx%d@%d %s: %s", width, height, fps,
                 mjpeg ? "MJPEG" : "YUYV", uvc_strerror(result));
        return;
    }
    if (ctrl.dwMaxPayloadTransferSize > 1024 * 1024) {
        ANativeWindow_release(window);
        throwNew(env, "java/io/IOException", "The camera reported a %u byte payload size",
                 ctrl.dwMaxPayloadTransferSize);
        return;
    }
    uint64_t frameLimit = (uint64_t)width * height * 4;
    if (frameLimit > 64 * 1024 * 1024) {
        frameLimit = 64 * 1024 * 1024;
    }
    if (ctrl.dwMaxVideoFrameSize == 0 || ctrl.dwMaxVideoFrameSize > frameLimit) {
        ctrl.dwMaxVideoFrameSize = (uint32_t)frameLimit;
    }
    c->window = window;
    c->width = width;
    c->height = height;
    c->threadNamed = 0;
    c->framesLogged = 0;
    c->framesOk = 0;
    c->framesBad = 0;
    atomic_store_explicit(&c->stopping, 0, memory_order_relaxed);
    atomic_store_explicit(&c->lastFrameNs, nowNs() + FIRST_FRAME_GRACE_NS, memory_order_relaxed);
    result = uvc_start_streaming(c->devh, &ctrl, onFrame, c, 0);
    if (result != UVC_SUCCESS) {
        ANativeWindow_release(window);
        c->window = NULL;
        throwNew(env, "java/io/IOException", "Cannot start streaming: %s", uvc_strerror(result));
        return;
    }
    c->streaming = 1;
    LOGI("streaming %dx%d %s, interval %u, frame %u bytes, payload %u bytes", width, height,
         mjpeg ? "MJPEG" : "YUYV", ctrl.dwFrameInterval, ctrl.dwMaxVideoFrameSize,
         ctrl.dwMaxPayloadTransferSize);
}

JNIEXPORT void JNICALL Java_app_mobdroid_uvc_UvcNative_stop(JNIEnv *env, jobject thiz, jlong handle) {
    stopStream((Camera *)(uintptr_t)handle);
}

JNIEXPORT jlong JNICALL Java_app_mobdroid_uvc_UvcNative_idleMs(JNIEnv *env, jobject thiz, jlong handle) {
    Camera *c = (Camera *)(uintptr_t)handle;
    return (nowNs() - atomic_load_explicit(&c->lastFrameNs, memory_order_relaxed)) / 1000000LL;
}

JNIEXPORT void JNICALL Java_app_mobdroid_uvc_UvcNative_close(JNIEnv *env, jobject thiz, jlong handle) {
    destroy((Camera *)(uintptr_t)handle);
    LOGI("closed");
}
