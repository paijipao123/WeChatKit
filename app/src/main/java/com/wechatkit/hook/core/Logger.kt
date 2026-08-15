package com.wechatkit.hook.core

import de.robv.android.xposed.XposedBridge

/**
 * 日志工具：统一输出带 TAG 的日志到 LSPosed 日志。
 *
 * 用 [LogWriter] 这个接口封装一层，便于在不加载 LSPosed 的单元环境里也能打印。
 */
object Logger {
    const val TAG = "WeChatKit"

    @JvmStatic
    fun i(msg: String) {
        XposedBridge.log("[$TAG] [I] $msg")
    }

    @JvmStatic
    fun w(msg: String) {
        XposedBridge.log("[$TAG] [W] $msg")
    }

    @JvmStatic
    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("[$TAG] [E] $msg" + (t?.let { " | " + it.toString() } ?: ""))
    }
}
