package simple.hook.wechat.core

/**
 * Dex 搜索结果缓存（微信进程 shared_prefs）。
 *
 * 参考 WeKit DexCacheManager：搜索到的类名/方法签名缓存下来，
 * 后续启动直接读缓存定位，不再每次实时查询。
 */
object DexCache {

    private const val PREFS_NAME = "wechathook_dex_cache"

    /** 查询 key -> 结果（类名或 类名|方法名|参数, 多条用 \u0001 分隔）。 */
    fun get(key: String): String? {
        return runCatching {
            val app = android.app.ActivityThread.currentApplication()
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(key, null)
        }.getOrNull()
    }

    fun put(key: String, value: String) {
        runCatching {
            val app = android.app.ActivityThread.currentApplication()
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        }
    }

    fun clear() {
        runCatching {
            val app = android.app.ActivityThread.currentApplication()
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    /** 查询 key 生成：类型 + 字符串列表 + 限定类。 */
    fun keyForClass(strings: Array<out String>): String =
        "cls:" + strings.sorted().joinToString("|")

    fun keyForMethod(strings: Array<out String>, declared: String?): String =
        "mtd:" + (declared ?: "*") + ":" + strings.sorted().joinToString("|")
}
