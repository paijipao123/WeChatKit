package com.wechathook.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块配置存储（基于 SharedPreferences）。
 *
 * 注意：LSPosed 模块的钩子运行在宿主（微信）进程里，无法直接使用模块自己的 Context，
 * 因此使用宿主 Context 获取的 SharedPreferences（以应用 ID 为文件名的文件也能被模块进程读到，
 * 但更通用、稳定的是直接存到模块自身的设置文件目录）。
 */
object Prefs {

    private const val FILE = "wechathook_prefs"

    private var prefs: SharedPreferences? = null

    /** 必须在模块加载时（handleLoadPackage）尽早调用一次，传入宿主 Context。 */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** 从模块自身 Context 初始化（用于设置页，与宿主进程共用同一份文件）。 */
    fun initWithModuleContext(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences =
        checkNotNull(prefs) { "Prefs 尚未初始化，请先调用 init()" }

    fun getBoolean(key: String, def: Boolean): Boolean = p().getBoolean(key, def)
    fun setBoolean(key: String, value: Boolean) {
        p().edit().putBoolean(key, value).apply()
    }

    fun getString(key: String, def: String): String = p().getString(key, def) ?: def
    fun setString(key: String, value: String) {
        p().edit().putString(key, value).apply()
    }

    fun getInt(key: String, def: Int): Int = p().getInt(key, def)
    fun setInt(key: String, value: Int) {
        p().edit().putInt(key, value).apply()
    }

    fun getLong(key: String, def: Long): Long = p().getLong(key, def)
    fun setLong(key: String, value: Long) {
        p().edit().putLong(key, value).apply()
    }
}
