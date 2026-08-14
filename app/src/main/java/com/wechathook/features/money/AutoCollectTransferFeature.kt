package com.wechathook.features.money

import android.content.ContentValues
import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Prefs
import com.wechathook.core.WeChatNetwork
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动收款（转账）。
 *
 * 纯后台网络层 hook：监听 message 表插入转账消息，构造微信「确认收款」请求
 * （`/cgi-bin/mmpay-bin/transferoperation`）并通过 [WeChatNetwork] 发送。
 *
 * 微信 8.0.71 的确认收款请求类（已通过 dex 反编译确认）：
 * `com.tencent.mm.plugin.remittance.model.n0`
 * - getUri -> "/cgi-bin/mmpay-bin/transferoperation"
 * - 构造 (String, String, int, String, String, int, String, String, int, String, Map, long, String, String)
 *   —— 参考 RemittanceDetailUI.y7: 第二个 String 参数为 "confirm"（确认收款操作）
 * - 继承 tenpay.model.o（有 doScene，走 NetSceneBase 发包机制）
 *
 * > 风控警告：自动收转账属于高风险操作。
 */
object AutoCollectTransferFeature : Feature {

    override val key = "auto_collect_transfer"
    override val name = "自动收款（转账）"

    /** 确认收款 cgi 特征（用于 DexKit 定位请求类）。 */
    private const val STR_CONFIRM_URI = "/cgi-bin/mmpay-bin/transferoperation"

    private var transferClsName: String? = null

    private var lastMsgId: String? = null
    private var hostLoader: ClassLoader? = null

    private val enable: Boolean get() = Prefs.getBoolean("auto_collect_transfer_enable", true)

    /** 收到转账后延迟自动收款的默认毫秒数（可配）。 */
    private val collectDelay: Long
        get() = Prefs.getString("auto_collect_delay", "1000").toLongOrNull() ?: 1000L

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return
        if (finder == null) return

        hostLoader = classLoader
        Logger.i("[$name] 开始 Hook")

        val netOk = WeChatNetwork.init(classLoader, finder)
        if (!netOk) {
            Logger.w("[$name] 微信网络层初始化失败，功能可能无法发包。")
        }

        // 定位确认收款请求类：按 cgi URI 特征
        transferClsName = finder.findClassNameByStrings(STR_CONFIRM_URI)
            ?: finder.findClassNameByStrings("transferoperation")
        Logger.i("[$name] 定位到的转账收款类: $transferClsName")

        // 监听转账消息入库并触发自动收款
        hookDatabaseInsert(classLoader)
    }

    private fun hookDatabaseInsert(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.compat.SQLiteDatabase"
        )
        candidates.forEach { clsName ->
            runCatching {
                val clazz = XposedHelpers.findClass(clsName, classLoader)
                XposedBridge.hookAllMethods(clazz, "insertWithOnConflict", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val table = param.args[0] as? String ?: return
                            if (table != "message") return
                            val values = param.args[2] as? ContentValues ?: return
                            checkTransferInsert(values)
                        } catch (_: Throwable) {}
                    }
                })
                Logger.i("[$name] 已监听 $clsName 消息插入")
            }.onFailure { Logger.e("[$name] hook $clsName insert 失败 $it") }
        }
    }

    private fun checkTransferInsert(values: ContentValues) {
        // 必须是自己没发过的消息
        if (values.getAsInteger("isSend") == 1) return

        val content = values.getAsString("content") ?: return

        // 转账消息的 content 通常是 XML，内含转账扩展标记
        val isTransfer = content.contains("transcationid", ignoreCase = true)
                || content.contains("transferid", ignoreCase = true)
                || content.contains("wcpayinfo", ignoreCase = true)
                || content.contains("paymsgtype", ignoreCase = true)

        if (!isTransfer) return

        val msgId = values.getAsString("msgId") ?: ""
        if (msgId.isEmpty() || lastMsgId == msgId) return
        lastMsgId = msgId

        Logger.i("[$name] 检测到转账消息 msgId=$msgId")
        collectAsync(msgId, content)
    }

    /** 自动发起确认收款请求。 */
    private fun collectAsync(msgId: String, content: String) {
        val loader = hostLoader ?: return

        Thread {
            try {
                if (collectDelay > 0) Thread.sleep(collectDelay)
                val clsName = transferClsName ?: run {
                    Logger.w("[$name] 转账收款类未定位，自动收款未触发。")
                    return@Thread
                }
                runCatching {
                    val cls = XposedHelpers.findClass(clsName, loader)
                    // 从转账 XML 提取 transferid（转账单号）
                    val transferId = extractXmlParam(content, "transferid")
                        .ifEmpty { extractXmlParam(content, "transcationid") }
                    Logger.i("[$name] 转账单号: $transferId")

                    // n0 构造 (String, String, int, String, String, int, String, String, int, String, Map, long, String, String)
                    // 参考 RemittanceDetailUI.y7: 第2个String="confirm"(确认收款)
                    val req = try {
                        XposedHelpers.newInstance(
                            cls,
                            transferId,          // transferid
                            "",                  // (l1)
                            0,                   // (x1) int
                            "confirm",           // 操作类型 = 确认收款
                            "",                  // (y0)
                            0,                   // (p0) int
                            "",                  // (G1)
                            "",                  // (C1)
                            0,                   // (V1) int
                            "",                  // (W1)
                            java.util.HashMap<String, Any?>(),  // (X1) Map
                            0L,                  // long
                            "",                  // (A1)
                            ""                   // (G1)
                        )
                    } catch (e1: Throwable) {
                        // 构造参数不匹配时回退：无参构造（若存在）
                        Logger.w("[$name] 带参构造失败($e1)，尝试无参")
                        XposedHelpers.newInstance(cls)
                    }
                    WeChatNetwork.sendNetScene(req)
                    Logger.i("[$name] 已触发确认收款请求 $msgId")
                }.onFailure { Logger.e("[$name] 构造/发送收款请求失败 $it") }
            } catch (t: Throwable) {
                Logger.e("[$name] 自动收款异常 $t")
            }
        }.start()
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        val cdata = Regex("<$tag><!\\[CDATA\\[(.*?)]]></$tag>").find(xml)
            ?: Regex("<$tag>(.*?)</$tag>").find(xml)
        return cdata?.groupValues?.get(1) ?: ""
    }
}
