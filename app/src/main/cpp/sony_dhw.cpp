/**
 * sony_dhw.cpp
 *
 * Sony DPT-RP1 libSystemUtil.so DHW 桥接。
 *
 * 通过 ELF 符号分析发现，库使用 RegisterNatives + C++ mangled names，
 * 而非 Java_pkg_Class_method 命名规范。
 * 真实导出符号：
 *   _Z11setDhwStateP7_JNIEnvP8_jobjecth
 *   _Z10addDhwAreaP7_JNIEnvP8_jobjectiiiiih
 *   _Z11getDhwStateP7_JNIEnvP8_jobject
 *   _Z13removeDhwAreaP7_JNIEnvP8_jobjecti
 * 直接用 dlsym 按 mangled name 获取函数指针即可。
 */

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <elf.h>
#include <stdint.h>
#include <stdlib.h>

#define TAG  "SonyDHW_C"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// jboolean 在 C++ 层是 unsigned char (h)
typedef void     (*FnSetState)(JNIEnv*, jobject, unsigned char);
typedef jint     (*FnAddArea) (JNIEnv*, jobject, int, int, int, int, int, unsigned char);
typedef jboolean (*FnGetState)(JNIEnv*, jobject);
typedef jint     (*FnRemArea) (JNIEnv*, jobject, int);

static FnSetState  g_setDhwState = nullptr;
static FnAddArea   g_addDhwArea  = nullptr;
static FnGetState  g_getDhwState = nullptr;
static FnRemArea   g_remDhwArea  = nullptr;
static bool        g_initDone    = false;

// ── 按 mangled name 直接查找符号 ─────────────────────────────────────────
static bool tryFindByMangledNames(void* handle) {
    g_setDhwState = (FnSetState)dlsym(handle, "_Z11setDhwStateP7_JNIEnvP8_jobjecth");
    if (!g_setDhwState) {
        // 再用 RTLD_DEFAULT 在全局符号表里找（以防 handle 搜索范围不够）
        g_setDhwState = (FnSetState)dlsym(RTLD_DEFAULT, "_Z11setDhwStateP7_JNIEnvP8_jobjecth");
    }
    if (!g_setDhwState) return false;

    g_addDhwArea  = (FnAddArea) dlsym(handle, "_Z10addDhwAreaP7_JNIEnvP8_jobjectiiiiih");
    g_getDhwState = (FnGetState)dlsym(handle, "_Z11getDhwStateP7_JNIEnvP8_jobject");
    g_remDhwArea  = (FnRemArea) dlsym(handle, "_Z13removeDhwAreaP7_JNIEnvP8_jobjecti");

    if (!g_addDhwArea) g_addDhwArea  = (FnAddArea) dlsym(RTLD_DEFAULT, "_Z10addDhwAreaP7_JNIEnvP8_jobjectiiiiih");
    if (!g_getDhwState) g_getDhwState = (FnGetState)dlsym(RTLD_DEFAULT, "_Z11getDhwStateP7_JNIEnvP8_jobject");
    if (!g_remDhwArea)  g_remDhwArea  = (FnRemArea) dlsym(RTLD_DEFAULT, "_Z13removeDhwAreaP7_JNIEnvP8_jobjecti");

    LOGI("DHW found: set=%p add=%p get=%p rem=%p",
         (void*)g_setDhwState, (void*)g_addDhwArea,
         (void*)g_getDhwState, (void*)g_remDhwArea);
    return true;
}

// ── 公开 JNI 入口 ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_newdrawingapp_SonySystemUtil_nativeDhwInit(JNIEnv* env, jobject thiz) {
    if (g_initDone) return (g_setDhwState != nullptr) ? JNI_TRUE : JNI_FALSE;
    g_initDone = true;

    void* handle = dlopen("libSystemUtil.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }
    LOGI("dlopen OK, trying mangled names...");

    if (tryFindByMangledNames(handle)) {
        LOGI("DHW 初始化成功");
        return JNI_TRUE;
    }

    LOGE("DHW mangled symbols not found");
    dlclose(handle);
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_newdrawingapp_SonySystemUtil_nativeDhwSetState(JNIEnv* env, jobject thiz, jboolean enabled) {
    if (g_setDhwState) g_setDhwState(env, thiz, (unsigned char)enabled);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_newdrawingapp_SonySystemUtil_nativeDhwAddArea(
        JNIEnv* env, jobject thiz,
        jint left, jint top, jint right, jint bottom,
        jint penWidth, jboolean portrait) {
    if (g_addDhwArea) return g_addDhwArea(env, thiz,
                                          left, top, right, bottom,
                                          penWidth, (unsigned char)portrait);
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_newdrawingapp_SonySystemUtil_nativeDhwRemoveArea(JNIEnv* env, jobject thiz, jint index) {
    if (g_remDhwArea) return g_remDhwArea(env, thiz, index);
    return -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_newdrawingapp_SonySystemUtil_nativeDhwGetState(JNIEnv* env, jobject thiz) {
    if (g_getDhwState) return g_getDhwState(env, thiz);
    return JNI_FALSE;
}
