package com.example.newdrawingapp

import android.util.Log

/**
 * Sony DPT-RP1 DHW（Direct Hardware Write）API 封装。
 *
 * 问题：libSystemUtil.so 内的 JNI 函数使用 Sony 自己的包名前缀
 *        （如 Java_com_sony_digitalpaper_SystemUtil_nativeSetDhwState），
 *        直接在 Kotlin 声明 external 函数会导致 JNI 命名不匹配。
 *
 * 方案：我们自己的 NDK 库 libsony_dhw.so 用 dlopen/dlsym 搜索
 *        libSystemUtil.so 中的真实符号，通过函数指针调用，
 *        完全绕过 JNI 包名约束。
 */
class SonySystemUtil private constructor() {

    // ── 调用 libsony_dhw.so 中的桥接函数（包名完全匹配，无歧义） ─────────
    private external fun nativeDhwInit(): Boolean
    private external fun nativeDhwSetState(enabled: Boolean)
    private external fun nativeDhwAddArea(
        left: Int, top: Int, right: Int, bottom: Int,
        penWidth: Int, portrait: Boolean
    ): Int
    private external fun nativeDhwRemoveArea(index: Int): Int
    private external fun nativeDhwGetState(): Boolean

    // ── 友好 API ──────────────────────────────────────────────────────────

    private var dhwAreaIndex = -1

    /** ioctl 成功时 index>=0；为 -1 时不得再 setDhwState(true)，否则会出现 Bad address 后笔迹异常 */
    fun isDhwAreaReady(): Boolean = dhwAreaIndex >= 0

    fun addDhwArea(left: Int, top: Int, right: Int, bottom: Int, penWidth: Int, portrait: Boolean = false) {
        try {
            // 先移除旧区域（如果有）
            if (dhwAreaIndex >= 0) {
                nativeDhwRemoveArea(dhwAreaIndex)
            }
            dhwAreaIndex = nativeDhwAddArea(left, top, right, bottom, penWidth, portrait)
            if (dhwAreaIndex >= 0) {
                Log.d(TAG, "DHW 区域已注册 ($left,$top,$right,$bottom) width=$penWidth portrait=$portrait → index=$dhwAreaIndex")
            } else {
                Log.w(TAG, "DHW 区域注册失败 ($left,$top,$right,$bottom) → index=$dhwAreaIndex（ioctl 可能 Bad address）")
            }
        } catch (e: Exception) {
            dhwAreaIndex = -1
            Log.w(TAG, "addDhwArea 失败: ${e.message}")
        }
    }

    fun setDhwState(enabled: Boolean) {
        try { nativeDhwSetState(enabled) } catch (_: Exception) {}
    }

    // ── 单例 ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SonySystemUtil"

        @Volatile private var instance: SonySystemUtil? = null
        @Volatile private var loadAttempted = false

        init {
            try {
                System.loadLibrary("sony_dhw")   // 加载我们自己的 NDK 桥接库
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libsony_dhw.so 未找到: ${e.message}")
            }
        }

        fun getInstance(): SonySystemUtil? {
            if (loadAttempted) return instance
            synchronized(this) {
                if (!loadAttempted) {
                    loadAttempted = true
                    instance = tryInit()
                }
            }
            return instance
        }

        private fun tryInit(): SonySystemUtil? {
            return try {
                val util = SonySystemUtil()
                if (util.nativeDhwInit()) {
                    Log.i(TAG, "DHW 硬件加速已启用")
                    util
                } else {
                    Log.i(TAG, "DHW 符号未找到，使用标准渲染")
                    null
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "libsony_dhw.so 不可用: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "DHW 初始化异常: ${e.message}")
                null
            }
        }
    }
}
