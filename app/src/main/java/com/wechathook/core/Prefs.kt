package com.wechathook.core

import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * 模块配置存储。
 *
 * LSPosed 模块的 hook 运行在宿主（微信）进程，而设置页运行在模块自己的进程，
 * 两者的 data 目录不同且互相无权限访问（SELinux 隔离），SharedPreferences 无法
 * 跨进程共享。因此把配置写到**外部存储的公共位置**：
 * `/storage/emulated/0/WeChatKit/wechathook_prefs.xml`
 * - 模块设置页（模块进程）：可写（模块声明了存储权限）
 * - 微信进程：可读（微信有 READ/WRITE_EXTERNAL_STORAGE 权限）
 *
 * 读取时多路径探测（sdcard 优先，回退模块私有目录），保证兼容。
 *
 * 文件格式与 Android SharedPreferences 一致（`map` 根节点 + `string`/`boolean`/`long`/`int` 子节点）。
 */
object Prefs {

    const val FILE_NAME = "wechathook_prefs"

    /** 跨进程共享路径（sdcard，微信可读）。 */
    private fun sharedFile(): File =
        File("/storage/emulated/0/WeChatKit/$FILE_NAME.xml")

    /** 模块私有路径（设置页可用，微信不可读，作为回退）。 */
    private fun privateFile(): File =
        File("/data/user/0/com.wechathook/shared_prefs/$FILE_NAME.xml")

    /** 优先 sdcard 共享路径；不可写时回退私有路径。 */
    private fun prefsFile(): File {
        val shared = sharedFile()
        return try {
            shared.parentFile?.mkdirs()
            if (shared.exists() || shared.createNewFile()) shared else privateFile()
        } catch (_: Throwable) {
            privateFile()
        }
    }

    private val cache = HashMap<String, Any?>()

    /** hook 进程里主动读取一次，把文件内容载入内存缓存。 */
    @Synchronized
    fun reload() {
        cache.clear()
        val file = prefsFile()
        if (file.exists()) {
            loadFrom(file)
            // 若读到了 sdcard 文件，说明共享路径可用
            if (cache.isNotEmpty() || file == sharedFile()) {
                migrateLegacy()
                return
            }
        }
        // 回退：尝试读私有路径（老版本数据）
        val private = privateFile()
        if (private.exists() && private != file) {
            loadFrom(private)
            // 迁移到 sdcard 共享路径
            if (cache.isNotEmpty()) {
                save()
            }
        }
    }

    private fun loadFrom(file: File) {
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
                        "boolean" -> cache[name] = raw == "true"
                        "long" -> cache[name] = raw.toLongOrNull()
                        "int" -> cache[name] = raw.toIntOrNull()
                        else -> cache[name] = raw
                    }
                    name = null; type = null
                }
                event = f.next()
            }
        } catch (_: Throwable) {}
    }

    /** 把老版本私有目录的数据并入 sdcard 共享文件（若两者都有效）。 */
    private fun migrateLegacy() {
        val private = privateFile()
        if (!private.exists()) return
        val legacy = HashMap<String, Any?>()
        try {
            val f = XmlPullParserFactory.newInstance().newPullParser()
            f.setInput(InputStreamReader(private.inputStream(), Charsets.UTF_8))
            var name: String? = null
            var type: String? = null
            var event = f.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && f.name == "string") {
                    name = f.getAttributeValue(null, "name")
                    type = f.getAttributeValue(null, "type")
                } else if (event == XmlPullParser.TEXT && name != null) {
                    when (type) {
                        "boolean" -> legacy[name] = f.text == "true"
                        else -> legacy[name] = f.text
                    }
                    name = null; type = null
                }
                event = f.next()
            }
        } catch (_: Throwable) {}
        var changed = false
        for ((k, v) in legacy) {
            if (!cache.containsKey(k)) {
                cache[k] = v
                changed = true
            }
        }
        if (changed) save()
    }

    /** 持久化当前缓存到 XML 文件。 */
    @Synchronized
    private fun save() {
        runCatching {
            val file = prefsFile()
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { os ->
                val utf8 = Charsets.UTF_8
                os.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n".toByteArray(utf8))
                os.write("<map>\n".toByteArray(utf8))
                for ((k, v) in cache.entries.sortedBy { it.key }) {
                    when (v) {
                        is Boolean -> os.write("    <string name=\"$k\" type=\"boolean\">$v</string>\n".toByteArray(utf8))
                        is Long -> os.write("    <string name=\"$k\" type=\"long\">$v</string>\n".toByteArray(utf8))
                        is Int -> os.write("    <string name=\"$k\" type=\"int\">$v</string>\n".toByteArray(utf8))
                        else -> os.write("    <string name=\"$k\">${escape(v?.toString() ?: "")}</string>\n".toByteArray(utf8))
                    }
                }
                os.write("</map>\n".toByteArray(utf8))
            }
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    // ---- 读写入口 ----

    private fun ensureLoaded() {
        if (cache.isEmpty() && !prefsFile().exists()) {
            reload()
        } else if (cache.isEmpty()) {
            reload()
        }
        // 若文件存在且未加载，首次触发 reload
        if (cache.isEmpty() && prefsFile().exists()) reload()
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
