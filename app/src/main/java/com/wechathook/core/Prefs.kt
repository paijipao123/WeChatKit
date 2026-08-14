package com.wechathook.core

import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * 模块配置存储。
 *
 * LSPosed 模块的 hook 运行在宿主（微信）进程，而设置页运行在模块自己的进程。
 * 跨进程配置共享采用**双路径双写**策略：
 *
 * 1. 模块私有目录 `/data/user/0/com.wechathook/shared_prefs/wechathook_prefs.xml`
 *    —— 设置页写入后立即 chmod 644（目录 711），使微信进程可以读取
 *    （Android SELinux 允许读取其他应用 world-readable 的 app_data_file）。
 * 2. 外部存储 `/storage/emulated/0/WeChatKit/wechathook_prefs.xml`
 *    —— 若模块拿到存储权限则同时写入，作为跨进程共享的辅助通道。
 *
 * 微信进程读取时：私有目录优先，sdcard 兜底；任意一个读到即用。
 *
 * 文件格式与 Android SharedPreferences 一致（`map` 根节点 + `string`/`boolean`/`long`/`int` 子节点）。
 */
object Prefs {

    const val FILE_NAME = "wechathook_prefs"

    /** 模块私有路径（设置页写入后 chmod 644 供微信进程读取）。 */
    private fun privateFile(): File =
        File("/data/user/0/com.wechathook/shared_prefs/$FILE_NAME.xml")

    /** 跨进程共享路径（sdcard，辅助通道）。 */
    private fun sharedFile(): File =
        File("/storage/emulated/0/WeChatKit/$FILE_NAME.xml")

    /** 读优先：私有目录 → sdcard。 */
    private fun readFile(): File? {
        val p = privateFile()
        if (p.exists()) return p
        val s = sharedFile()
        if (s.exists()) return s
        return null
    }

    /** 写优先：私有目录（保证可写），再尝试 sdcard。 */
    private fun writeFile(): File {
        val p = privateFile()
        return try {
            p.parentFile?.mkdirs()
            if (p.exists() || p.createNewFile()) p else sharedFile()
        } catch (_: Throwable) {
            sharedFile()
        }
    }

    private val cache = HashMap<String, Any?>()

    /** hook 进程里主动读取一次，把文件内容载入内存缓存。 */
    @Synchronized
    fun reload() {
        // 不直接 clear：先读入临时 map，成功才替换，失败保留旧值
        val tmp = HashMap<String, Any?>()
        // 1. 终极方案：微信进程用自己的 context 读微信自己的 prefs
        //    （容器侧 root 同步器把模块配置复制到微信 shared_prefs/wechathook_config.xml，
        //     微信读自己的文件，无任何 SELinux 限制）
        if (tryLoadViaHostPrefsInto(tmp)) {
            cache.clear(); cache.putAll(tmp)
            lastLoadedMtime = System.currentTimeMillis()
            return
        }

        // 2. XSharedPreferences（LSPosed 框架读取模块 prefs）
        if (tryLoadViaXSharedPreferencesInto(tmp)) {
            cache.clear(); cache.putAll(tmp)
            lastLoadedMtime = System.currentTimeMillis()
            return
        }

        // 3. 回退：直接读模块私有目录文件（chmod 644 后，部分环境可读）
        val file = readFile()
        if (file != null && file.exists()) {
            loadFromInto(file, tmp)
            if (tmp.isNotEmpty()) {
                cache.clear(); cache.putAll(tmp)
                lastLoadedMtime = file.lastModified()
                return
            }
        }
        // 全部失败：保留原 cache（不清空），避免开关状态丢失
    }

    private fun tryLoadViaHostPrefsInto(target: HashMap<String, Any?>): Boolean {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
                ?: return false
            val prefs = app.getSharedPreferences("wechathook_config", android.content.Context.MODE_PRIVATE)
            val all = prefs.all
            if (all.isEmpty()) return false
            for ((k, v) in all) {
                target[k] = v
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 通过 LSPosed 的 XSharedPreferences 读取模块配置。 */
    private fun tryLoadViaXSharedPreferencesInto(target: HashMap<String, Any?>): Boolean {
        return try {
            val prefs = de.robv.android.xposed.XSharedPreferences("com.wechathook", FILE_NAME)
            try {
                prefs.makeWorldReadable()
            } catch (_: Throwable) {}
            val all = prefs.all ?: return false
            if (all.isEmpty()) return false
            for ((k, v) in all) {
                target[k] = v
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun loadFromInto(file: File, target: HashMap<String, Any?>) {
        try {
            val f = XmlPullParserFactory.newInstance().newPullParser()
            f.setInput(InputStreamReader(file.inputStream(), Charsets.UTF_8))
            var name: String? = null
            var type: String? = null
            var event = f.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && f.name == "string") {
                    val n = f.getAttributeValue(null, "name")
                    val t = f.getAttributeValue(null, "type")
                    name = n; type = t
                } else if (event == XmlPullParser.TEXT && name != null) {
                    val raw = f.text
                    when (type) {
                        "boolean" -> target[name] = raw == "true"
                        "long" -> target[name] = raw.toLongOrNull()
                        "int" -> target[name] = raw.toIntOrNull()
                        else -> target[name] = raw
                    }
                    name = null; type = null
                }
                event = f.next()
            }
        } catch (_: Throwable) {}
    }

    /** 持久化当前缓存到 XML 文件（标准 SharedPreferences 格式，XSharedPreferences 可解析）。 */
    @Synchronized
    private fun save() {
        runCatching {
            val file = writeFile()
            file.parentFile?.mkdirs()
            // 原子写：先写临时文件再 rename，避免读取方读到半截 XML
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { os ->
                val utf8 = Charsets.UTF_8
                os.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n".toByteArray(utf8))
                os.write("<map>\n".toByteArray(utf8))
                for ((k, v) in cache.entries.sortedBy { it.key }) {
                    val line = when (v) {
                        is Boolean -> "    <boolean name=\"$k\" value=\"$v\" />\n"
                        is Long -> "    <long name=\"$k\" value=\"$v\" />\n"
                        is Int -> "    <int name=\"$k\" value=\"$v\" />\n"
                        else -> "    <string name=\"$k\">${escape(v?.toString() ?: "")}</string>\n"
                    }
                    os.write(line.toByteArray(utf8))
                }
                os.write("</map>\n".toByteArray(utf8))
            }
            // rename 覆盖
            if (tmp.exists()) {
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
            // 更新 mtime 记录，避免下次 ensureLoaded 误判变化
            lastLoadedMtime = file.lastModified()

            // 关键：让微信进程能读模块私有目录的配置
            // 整条目录链路放行: /data/user/0/com.wechathook (711), shared_prefs (711)
            // 文件 644（全局可读）
            runCatching {
                val base = "/data/user/0/com.wechathook"
                val cmds = arrayOf(
                    arrayOf("chmod", "711", base),
                    arrayOf("chmod", "711", "$base/shared_prefs"),
                    arrayOf("chmod", "644", file.absolutePath)
                )
                for (c in cmds) {
                    try {
                        java.lang.Runtime.getRuntime().exec(c).waitFor()
                    } catch (_: Throwable) {}
                }
            }
            // Java API 兜底
            runCatching {
                file.setReadable(true, false)
                file.parentFile?.setExecutable(true, false)
            }

            // 同时写 sdcard 辅助副本（若可写）
            runCatching {
                val shared = sharedFile()
                shared.parentFile?.mkdirs()
                file.copyTo(shared, overwrite = true)
            }
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    // ---- 读写入口 ----

    /** 上次加载时文件的修改时间，用于检测配置变化（热更新）。 */
    private var lastLoadedMtime: Long = -1L

    /** 上次检测时间（限频，避免频繁 stat）。 */
    private var lastCheckTime: Long = 0L

    /**
     * 确保配置已加载；若文件被外部修改（设置页改开关），自动重新加载，
     * 使红包/转账等"触发时读配置"的功能**开关即时生效**（无需重启微信）。
     */
    private fun ensureLoaded() {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < 2000) {
            // 限频：2 秒内不重复检查
            if (cache.isNotEmpty()) return
        }
        lastCheckTime = now

        val file = readFile()
        val mtime = file?.lastModified() ?: -1L
        if (cache.isEmpty() || (mtime != -1L && mtime != lastLoadedMtime)) {
            reload()
            lastLoadedMtime = mtime
        }
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        ensureLoaded()
        return (cache[key] as? Boolean) ?: def
    }

    fun setBoolean(key: String, value: Boolean) {
        ensureLoaded()
        cache[key] = value
        save()
    }

    fun getString(key: String, def: String): String {
        ensureLoaded()
        return when (val v = cache[key]) {
            is String -> v
            else -> def
        }
    }

    fun setString(key: String, value: String) {
        ensureLoaded()
        cache[key] = value
        save()
    }

    fun getInt(key: String, def: Int): Int {
        ensureLoaded()
        return when (val v = cache[key]) {
            is Int -> v
            is Long -> v.toInt()
            else -> def
        }
    }

    fun setInt(key: String, value: Int) {
        ensureLoaded()
        cache[key] = value
        save()
    }

    fun getLong(key: String, def: Long): Long {
        ensureLoaded()
        return when (val v = cache[key]) {
            is Long -> v
            is Int -> v.toLong()
            else -> def
        }
    }

    fun setLong(key: String, value: Long) {
        ensureLoaded()
        cache[key] = value
        save()
    }
}
