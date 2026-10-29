LOCAL_PATH := $(call my-dir)
PLAYER_PATH := $(LOCAL_PATH)
CORE_DIR := $(PLAYER_PATH)/../../../../vendor/snes9x2005/source
LIBRETRO_DIR := $(PLAYER_PATH)/../../../../vendor/snes9x2005
LOAD_FROM_MEMORY := 1
USE_BLARGG_APU := 1
FLAGS :=
include $(LIBRETRO_DIR)/Makefile.common

include $(CLEAR_VARS)
LOCAL_MODULE := snescore
LOCAL_SRC_FILES := $(SOURCES_C)
LOCAL_CFLAGS := $(FLAGS) -O3 -ffast-math -fcommon -Wno-error=incompatible-pointer-types -Wno-error=implicit-function-declaration
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_PATH := $(PLAYER_PATH)
LOCAL_MODULE := snesplayer
LOCAL_SRC_FILES := player.cpp
LOCAL_C_INCLUDES := $(LIBRETRO_DIR)/libretro-common/include
LOCAL_CPPFLAGS := -std=c++17 -O3 -Wall -Wextra
LOCAL_SHARED_LIBRARIES := snescore
LOCAL_LDLIBS := -landroid -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
