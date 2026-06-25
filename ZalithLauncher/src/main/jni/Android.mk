LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

# include $(HERE_PATH)/crash_dump/libbase/Android.mk
# include $(HERE_PATH)/crash_dump/libbacktrace/Android.mk
# include $(HERE_PATH)/crash_dump/debuggerd/Android.mk


LOCAL_PATH := $(HERE_PATH)

$(call import-module,prefab/bytehook)
LOCAL_PATH := $(HERE_PATH)

# ShadowHook only ships armeabi-v7a / arm64-v8a; skip it on x86/x86_64.
RECORDZY_AUDIO_ABI := $(filter $(TARGET_ARCH_ABI),armeabi-v7a arm64-v8a)
ifneq ($(RECORDZY_AUDIO_ABI),)
$(call import-module,prefab/shadowhook)
LOCAL_PATH := $(HERE_PATH)
endif


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := pojavexec
LOCAL_SHARED_LIBRARIES := driver_helper
LOCAL_CFLAGS += -rdynamic
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/br_loader.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    ctxbridges/virgl_bridge.c \
    environ/environ.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c \
    java_exec_hooks.c \
    lwjgl_dlopen_hook.c

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
LOCAL_LDLIBS += -lEGL -lGLESv2
endif
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_LDLIBS := -ldl -llog
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_SRC_FILES := exit_hook.c
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := driver_helper
LOCAL_SRC_FILES := \
    driver_helper/driver_helper.c \
    driver_helper/nsbypass.c
LOCAL_CFLAGS += -g -rdynamic

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
LOCAL_LDLIBS += -lEGL -lGLESv2
endif
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := linkerhook
LOCAL_SRC_FILES := \
    linkerhook/linkerhook.cpp \
    linkerhook/linkerns.c
LOCAL_LDFLAGS := -z global
include $(BUILD_SHARED_LIBRARY)


# RecordZy audio tap: inline-hooks OpenAL-soft's mixer to capture game audio.
# Only built for arm ABIs (ShadowHook has no x86/x86_64 library). On other ABIs
# librecordzytap.so is absent and the Java side falls back to video-only.
ifneq ($(RECORDZY_AUDIO_ABI),)
include $(CLEAR_VARS)
LOCAL_MODULE := recordzytap
LOCAL_LDLIBS := -ldl -llog
LOCAL_SHARED_LIBRARIES := shadowhook
LOCAL_SRC_FILES := recordzytap/recordzy_audiotap.cpp
LOCAL_CPPFLAGS += -std=c++17
include $(BUILD_SHARED_LIBRARY)
endif


include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec_awt
LOCAL_SRC_FILES := \
    awt_bridge.c
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := awt_headless
include $(BUILD_SHARED_LIBRARY)


LOCAL_PATH := $(HERE_PATH)/awt_xawt
include $(CLEAR_VARS)
LOCAL_MODULE := awt_xawt
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SHARED_LIBRARIES := awt_headless
LOCAL_SRC_FILES := xawt_fake.c
include $(BUILD_SHARED_LIBRARY)


# delete fake libs after linked
$(info $(shell (rm $(HERE_PATH)/../jniLibs/*/libawt_headless.so)))

