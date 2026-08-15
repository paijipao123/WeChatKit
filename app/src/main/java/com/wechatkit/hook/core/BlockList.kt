package com.wechatkit.hook.core

/**
 * 屏蔽名单工具：自动抢红包 / 自动收转账时跳过指定的人。
 *
 * 配置键：blocked_talkers（逗号分隔的昵称或微信号）
 */
object BlockList {

    /** 缓存解析后的名单。 */
    @Volatile
    private var cached: Set<String>? = null
    private var cachedAt = 0L

    /** 当前是否启用屏蔽名单（配置非空即启用）。 */
    fun isEnabled(): Boolean = getList().isNotEmpty()

    /** 解析后的屏蔽名单（小写、去空白）。 */
    fun getList(): Set<String> {
        val now = System.currentTimeMillis()
        val c = cached
        if (c != null && now - cachedAt < 5000) return c
        val raw = Prefs.getString("blocked_talkers", "")
        val list = raw.split(",", "，", "\n", "、")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        cached = list
        cachedAt = now
        return list
    }

    /**
     * 判断某个 talker（微信号/昵称）是否在屏蔽名单中。
     * @param talker 微信 talker（通常为 wxid 或昵称）
     * @param nickname 已知昵称（可选）
     */
    fun isBlocked(talker: String?, nickname: String? = null): Boolean {
        val list = getList()
        if (list.isEmpty()) return false
        if (talker != null && list.contains(talker.trim().lowercase())) return true
        if (nickname != null && list.contains(nickname.trim().lowercase())) return true
        return false
    }
}
