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
 * 两者的 data 目录不同，Android 的 SharedPreferences 无法跨进程共享同名文件。
 * 因此这里直接读写**模块自身安装目录**下的 XML 文件
 * （`/data/data/com.wechathook/shared_prefs/wechathook_prefs.xml`），
 * hook 进程与设置页都访问这一绝对路径，从而实现配置互通。
 *
 * 文件格式与 Android SharedPreferences 一致（`map` 根节点 + `string`/`boolean`/`long`/`int` 子节点），
 * 这样即使将来改用 getSharedPreferences 也能互读。
 */
object Prefs {

    const val FILE_NAME = "wechathook_prefs"

    private fun prefsFile(): File {
        // 模块 applicationId 固定为 com.wechathook；多用户/dev 模式下用规范路径。
        val dataDir = "/data/user/0/com.wechathook"
        val dir = File("$dataDir/shared_prefs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$FILE_NAME.xml")
    }

    private val cache = HashMap<String, Any?>()

    /** hook 进程里主动读取一次，把文件内容载入内存缓存。 */
    @Synchronized
    fun reload() {
        cache.clear()
        val file = prefsFile()
        if (!file.exists()) return
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
