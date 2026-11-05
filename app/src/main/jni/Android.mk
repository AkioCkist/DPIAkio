LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE    := dpibridge
LOCAL_SRC_FILES := dpibridge.c
LOCAL_CFLAGS    := -O2 -Wall -fvisibility=hidden
LOCAL_LDFLAGS   += -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
