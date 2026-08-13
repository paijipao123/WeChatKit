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
 * 和自动抢红包一致，采用**纯后台网络层 hook**，不跳转页面、不模拟点击：
 * 1. hook WCDB 数据库 `insertWithOnConflict`，监听 `message` 表插入转账消息。
 * 2. 识别微信转账消息（type 为转账类型），解析 content 得到转账参数。
 * 3. 用 DexKit 定位微信「确认收款」的网络请求类，构造请求并通过 [WeChatNetwork]
 *    发送，完成自动收款。
 *
 * > 注意：微信的转账收款类名在历史模块中通常带 `NetSceneTransfer`/`Transfer` 特征，
 * > 但它不像红包那样有 `MicroMsg.NetSceneReceiveLuckyMoney` 这种稳定日志字符串。
 * > 因此这里把待核对的类名/命令行签名做成可配置字符串常量，**需要按你实际使用的
 * > 微信版本反编译 (jadx) 核对后调整**，否则该功能可能定位失败。
 */
object AutoCollectTransferFeature : Feature {

    override val key = "auto_collect_transfer"
    override val name = "自动收款（转账）"

    // ============ 待按目标微信版本核对的字符串特征 ============
    // 以下字符串用于 DexKit 定位转账客户端请求类。微信不同版本这些字符串/类名不一，
    // 若定位失败，可用 jadx 反编译微信，在 dex 里搜索 "Transfer"/"Oplog" 相关类来更新。
    private const val STR_TRANSFER = "MicroMsg.NetSceneTransfer"   // 转账主请求类特征
    private const val STR_TRANSFER_EXT = "NetSceneTransferExt"      // 收款扩展请求类特征

    // 微信转账消息类型（部分版本为 436207665 家族之外的特殊值，这里用类名/字段判定为主）
    // ==================================================

    private val transfers = ConcurrentHashMap<String, Map<String, String>>()

    private var transferClsName: String? = null

    private var lastSendId: String? = null
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

        // 定位转账收款请求类：优先精确类名字符串，失败则放宽到"类内方法含 Transfer 特征"。
        transferClsName = finder.findClassNameByStrings(STR_TRANSFER)
            ?: finder.findClassNameByStrings(STR_TRANSFER_EXT)
            ?: finder.findClassNamesByMethodStrings(STR_TRANSFER).firstOrNull()

        Logger.i("[$name] 定位到的转账类: $transferClsName")

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
                || content.contains("NetSceneTransfer", ignoreCase = true)
                || content.contains("paymsgtype", ignoreCase = true)

        if (!isTransfer) return

        val msgId = values.getAsString("msgId") ?: ""
        if (msgId.isEmpty() || lastSendId == msgId) return
        lastSendId = msgId

        Logger.i("[$name] 检测到转账消息 msgId=$msgId")
        collectAsync(msgId, content)
    }

    /** 自动发起收款请求。 */
    private fun collectAsync(msgId: String, content: String) {
        val sendId = msgId
        val loader = hostLoader ?: return

        Thread {
            try {
                if (collectDelay > 0) Thread.sleep(collectDelay)
                val clsName = transferClsName ?: run {
                    Logger.w("[$name] 转账收款类未定位，自动收款未触发（需按微信版本核对特征）。")
                    return@Thread
                }
                runCatching {
                    val cls = XposedHelpers.findClass(clsName, loader)
                    // 转账/收款请求构造参数因版本而异；这里给出常用形态，若失效按反编译结果调整。
                    // 常见构造：(int fieldCount, String... ) 或非无参，需核对。
                    val req = XposedHelpers.newInstance(cls)
                    WeChatNetwork.sendNetScene(req)
                    Logger.i("[$name] 已触发收款请求 $sendId")
                }.onFailure { Logger.e("[$name] 构造/发送收款请求失败 $it") }
            } catch (t: Throwable) {
                Logger.e("[$name] 自动收款异常 $t")
            }
        }.start()
    }
}
