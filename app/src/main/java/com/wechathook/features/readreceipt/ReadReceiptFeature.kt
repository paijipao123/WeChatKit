package com.wechathook.features.readreceipt

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.MessageViewHub
import com.wechathook.core.Prefs
import com.wechathook.core.ReflectFieldWalker
import de.robv.android.xposed.XposedHelpers
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 已读回执（配合 read-receipt-tracker 像素埋点服务）。
 *
 * 原理：用户部署 `read-receipt-tracker` 服务后，模块在我们发出的文本消息 View 上
 * 叠加显示"已读 X 人"。每个消息用 `sha256(wxId + content + createTime)` 作为追踪 id，
 * 向服务器注册（幂等），并定期轮询 `/count?wxId=&id=` 获取已读数更新显示。
 *
 * 需要用户在设置中填写已读服务的 URL（如 `http://192.168.1.10:8080`）。
 * 该服务由独立仓库 read-receipt-tracker 提供。
 */
object ReadReceiptFeature : Feature {

    override val key = "read_receipt"
    override val name = "已读回执（read-receipt-tracker）"

    private const val TAG = "ReadReceipt"

    // 从消息 View / holder 读取到的"已读"统计缓存：label View -> 消息token
    private val viewTokens = ConcurrentHashMap<View, String>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val enable: Boolean get() = Prefs.getBoolean("read_receipt_enable", true)

    private val server: String get() = Prefs.getString("read_receipt_server", "").trimEnd('/')

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return
        if (finder == null) return
        if (server.isEmpty()) {
            Logger.w("[$name] 未配置已读服务地址，请在设置中填写。")
            return
        }

        Logger.i("[$name] 已读服务: $server")

        MessageViewHub.addListener { holder, view ->
            // 避免对每个 View 重复叠加
            if (viewTokens.containsKey(view)) return@addListener
            handleMessageView(holder, view)
        }

        val bound = MessageViewHub.ensureBound(classLoader, finder)
        Logger.i("[$name] 消息 View 监听绑定=${bound}")
    }

    private fun handleMessageView(holder: Any, view: View) {
        try {
            // 只处理自己发送的消息（isSend == 1）& 文本消息
            val msgInfo = findMsgInfo(holder)

            // 提取 talker / content / createTime
            val talker = reflectString(msgInfo, "talker") ?: return
            val content = reflectString(msgInfo, "content") ?: return
            val createTime = reflectLong(msgInfo, "createTime")
            // 若消息不是文本（图片/语音），content 为空则跳过
            if (content.isBlank()) return

            val msgId = computeId(talker, content, createTime)
            viewTokens[view] = msgId

            // 注册（幂等）
            registerMessage(talker, content, createTime)

            // 轮询已读数并叠加显示
            attachCountLabel(view, talker, msgId)
        } catch (t: Throwable) {
            // 单条消息处理失败忽略
        }
    }

    // ---------- UI：叠加"已读 X 人" ----------

    private fun attachCountLabel(root: View, wxId: String, msgId: String) {
        runCatching {
            val label = TextView(root.context).apply {
                text = "…"
                textSize = 11f
                setTextColor(0xFF999999.toInt())
            }
            // 挂到消息气泡底部
            val overlay = (root as? ViewGroup) ?: return@attachCountLabel
            overlay.addView(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            viewTokens[root] = msgId

            // 启动轮询
            schedulePoll(root, wxId, msgId)
        }
    }

    private fun schedulePoll(label: View, wxId: String, msgId: String) {
        val interval = 5000L
        val r = object : Runnable {
            override fun run() {
                if (label.isAttachedToWindow.not()) return
                val count = fetchCount(wxId, msgId)
                if (count != null) {
                    (label as? TextView)?.text = "已读 $count 人"
                }
                mainHandler.postDelayed(this, interval)
            }
        }
        mainHandler.postDelayed(r, 3000)
    }

    // ---------- HTTP ----------

    private fun computeId(wxId: String, content: String, createTime: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(wxId.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(content.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(createTime.toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun registerMessage(wxId: String, content: String, createTime: Long, retries: Int = 0) {
        Thread {
            runCatching {
                val json = org.json.JSONObject()
                    .put("wxId", wxId)
                    .put("content", content)
                    .put("createTime", createTime)
                val body = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"),
                    json.toString()
                )
                val req = Request.Builder().url("$server/register").post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) Logger.w("[$name] register 失败 HTTP ${resp.code}")
                }
            }.onFailure { Logger.w("[$name] register 请求异常 $it") }
        }.start()
    }

    private fun fetchCount(wxId: String, id: String): Int? {
        return runCatching {
            val url = "$server/count?wxId=$wxId&id=$id"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseCount(body)
            }
        }.getOrNull()
    }

    private fun parseCount(body: String): Int? {
        return runCatching {
            val json = org.json.JSONObject(body)
            if (json.has("count")) json.getInt("count")
            else if (json.has("data")) json.getJSONObject("data").optInt("count", -1).let { if (it < 0) null else it }
            else null
        }.getOrNull()
    }

    // ---------- 消息字段反射 ----------

    private fun findMsgInfo(holder: Any): Any? {
        // holder 通常持有微信消息对象。遍历 holder 类继承树里的所有字段，
        // 找第一个"同时有 content(String) 和 createTime(时间戳)"的对象，作为消息信息。
        return ReflectFieldWalker.find(
            holder,
            signature = { obj -> hasField(obj, "talker") && hasField(obj, "createTime") }
        )
    }

    private fun hasField(obj: Any, name: String): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            runCatching { if (c!!.getDeclaredField(name) != null) return true }.onFailure {}
            c = c.superclass
        }
        return false
    }

    private fun reflectString(obj: Any?, field: String): String? {
        if (obj == null) return null
        return runCatching { XposedHelpers.getObjectField(obj, field) as? String }.getOrNull()
    }
    private fun reflectLong(obj: Any?, field: String): Long {
        if (obj == null) return 0L
        return runCatching {
            when (val v = XposedHelpers.getObjectField(obj, field)) {
                is Long -> v
                is Int -> v.toLong()
                else -> 0L
            }
        }.getOrDefault(0L)
    }
}
