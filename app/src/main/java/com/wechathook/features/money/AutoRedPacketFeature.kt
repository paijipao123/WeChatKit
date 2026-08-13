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
import kotlin.random.Random

/**
 * 自动抢红包（后台 hook，不跳转页面、不模拟点击）。
 *
 * 流程（参考 WeKit WeRedPacketAuto）：
 * 1. hook WCDB 数据库 `insertWithOnConflict`，监听 `message` 表插入红包消息。
 * 2. 解析红包 XML（content）里的 nativeurl 得到 sendid / msgtype / channelid。
 * 3. 用 DexKit 定位微信 `NetSceneReceiveLuckyMoney`（特征 `MicroMsg.NetSceneReceiveLuckyMoney`），
 *    反射构造请求并通过微信网络队列发送（拆红包）。
 * 4. 当收到拆包成功的网络回调（onGYNetEnd）后，定位 `NetSceneOpenLuckyMoney` 构造开包请求发送。
 *
 * *警告：此功能有账号风控风险。*
 */
object AutoRedPacketFeature : Feature {

    override val key = "auto_redpacket"
    override val name = "自动抢红包"

    private const val TYPE_LUCKY_MONEY = 436207665          // 普通红包
    private const val TYPE_LUCKY_MONEY_EXCLUSIVE = 469762097 // 专属红包

    private const val STR_RECEIVE = "MicroMsg.NetSceneReceiveLuckyMoney"
    private const val STR_OPEN = "MicroMsg.NetSceneOpenLuckyMoney"

    /** {sendid -> 已收到的请求参数}，用于拆包成功后开包。 */
    private val redPackets = ConcurrentHashMap<String, Map<String, String>>()

    // DexKit 定位到的关键类
    private var receiveClsName: String? = null
    private var openClsName: String? = null

    private var lastSendId: String? = null

    private val enable: Boolean get() = Prefs.getBoolean("auto_redpacket_enable", true)

    /** Hook 时缓存宿主 classLoader，供后台线程反射红包类用。 */
    private var hostLoader: ClassLoader? = null

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return
        if (finder == null) return

        hostLoader = classLoader
        Logger.i("[$name] 开始 Hook")

        // 初始化网络层
        val netOk = WeChatNetwork.init(classLoader, finder)
        if (!netOk) {
            Logger.w("[$name] 微信网络层初始化失败，功能可能无法发包。")
        }

        // 定位红包请求类
        receiveClsName = finder.findClassNameByStrings(STR_RECEIVE)
        openClsName = finder.findClassNameByStrings(STR_OPEN)
        Logger.i("[$name] Receive 类=$receiveClsName, Open 类=$openClsName")

        // 1. 监听红包消息入库
        hookDatabaseInsert(classLoader)

        // 2. hook 拆包成功后开包的网络回调
        hookOpenCallback(classLoader, finder)
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
                            checkRedPacketInsert(values)
                        } catch (_: Throwable) {}
                    }
                })
                Logger.i("[$name] 已监听 $clsName 消息插入")
            }.onFailure { Logger.e("[$name] hook $clsName insert 失败 $it") }
        }
    }

    private fun checkRedPacketInsert(values: ContentValues) {
        val type = values.getAsInteger("type") ?: return
        if (type != TYPE_LUCKY_MONEY && type != TYPE_LUCKY_MONEY_EXCLUSIVE) return
        // 只抢别人发的红包
        if (values.getAsInteger("isSend") == 1) return

        Logger.i("[$name] 检测到红包消息 type=$type")
        dispatchRedPacket(values)
    }

    private fun dispatchRedPacket(values: ContentValues) {
        try {
            var content = values.getAsString("content") ?: return
            val talker = values.getAsString("talker") ?: ""

            if (!content.startsWith("<") && content.contains(":")) {
                content = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(content, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val sendId = queryParam(nativeUrl, "sendid")
            val msgType = queryParam(nativeUrl, "msgtype").toIntOrNull() ?: 1
            val channelId = queryParam(nativeUrl, "channelid").toIntOrNull() ?: 1
            val headImg = extractXmlParam(content, "headimgurl")
            val nickName = extractXmlParam(content, "sendertitle")

            if (sendId.isEmpty() || lastSendId == sendId) return
            lastSendId = sendId

            redPackets[sendId] = mapOf(
                "sendId" to sendId, "nativeUrl" to nativeUrl,
                "msgType" to msgType.toString(), "channelId" to channelId.toString(),
                "talker" to talker, "headImg" to headImg, "nickName" to nickName
            )

            Logger.i("[$name] 红包 sendId=$sendId")

            // 随机/固定延迟后拆包
            val baseDelay = Prefs.getString("auto_redpacket_delay", "800").toLongOrNull() ?: 800L
            val delay = maxOf(0L, baseDelay + Random.nextLong(-300, 300))

            Thread {
                try {
                    if (delay > 0) Thread.sleep(delay)
                    sendReceive(sendId, msgType, channelId, nativeUrl, talker)
                } catch (e: Throwable) {
                    Logger.e("[$name] 拆包失败 $e")
                }
            }.start()
        } catch (t: Throwable) {
            Logger.e("[$name] 解析红包数据异常 $t")
        }
    }

    /** 发送拆红包请求（NetSceneReceiveLuckyMoney）。 */
    private fun sendReceive(sendId: String, msgType: Int, channelId: Int, nativeUrl: String, talker: String) {
        val clsName = receiveClsName ?: run { Logger.w("[$name] Receive 类未定位"); return }
        val loader = hostLoader ?: return
        runCatching {
            // ReceiveLuckyMoney 构造函数特征：(msgType, channelId, sendId, nativeUrl, ...)
            val clazz = XposedHelpers.findClass(clsName, loader)
            val request = XposedHelpers.newInstance(
                clazz, msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker
            )
            WeChatNetwork.sendNetScene(request)
            Logger.i("[$name] 拆包请求已发送 $sendId")
        }.onFailure { Logger.e("[$name] 构造拆包请求失败 $it") }
    }

    /** hook 拆包成功回调，随后开包。 */
    private fun hookOpenCallback(classLoader: ClassLoader, finder: DexKitFinder) {
        val recvCls = receiveClsName ?: return
        runCatching {
            // 拆包响应回调 onGYNetEnd(int, int, JSONObject)
            val methods = finder.findMethodsByStrings(
                classLoader,
                declaredClassName = recvCls,
                onlyPackages = listOf("com.tencent.mm"),
                strings = arrayOf("MicroMsg.NetSceneReceiveLuckyMoney"),
                paramCount = null
            )
            // 直接 hook 该类的 onGYNetEnd 方法
            val clazz = XposedHelpers.findClass(recvCls, classLoader)
            XposedBridge.hookAllMethods(clazz, "onGYNetEnd", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { handleReceiveEnd(param) }
                        .onFailure { Logger.e("[$name] 拆包回调异常 $it") }
                }
            })
            Logger.i("[$name] 已 hook onGYNetEnd")
        }.onFailure { Logger.e("[$name] hook onGYNetEnd 失败 $it") }
    }

    private fun handleReceiveEnd(param: XC_MethodHook.MethodHookParam) {
        // 从回调参数里取 sendId 并不直观；这里使用最近一次拆包 sendId 开包。
        val info = redPackets.entries.lastOrNull()?.value ?: return
        val sendId = info["sendId"] ?: return
        val msgType = info["msgType"]?.toIntOrNull() ?: 1
        val channelId = info["channelId"]?.toIntOrNull() ?: 1
        val nativeUrl = info["nativeUrl"] ?: return
        val headImg = info["headImg"] ?: ""
        val nickName = info["nickName"] ?: ""
        val talker = info["talker"] ?: ""

        Thread {
            runCatching {
                val open = openClsName ?: return@runCatching
                val loader = hostLoader ?: return@runCatching
                val cls = XposedHelpers.findClass(open, loader)
                // OpenLuckyMoney 构造：(msgType, channelId, sendId, nativeUrl, headImg, nickName, talker, ver, timingIdentifier, ...)
                val request = XposedHelpers.newInstance(
                    cls, msgType, channelId, sendId, nativeUrl,
                    headImg, nickName, talker, "v1.0", "", ""
                )
                WeChatNetwork.sendNetScene(request)
                redPackets.remove(sendId)
                Logger.i("[$name] 开包请求已发送 $sendId")
            }.onFailure { Logger.e("[$name] 开包失败 $it") }
        }.start()
    }

    // ---------- 工具 ----------

    private fun extractXmlParam(xml: String, tag: String): String {
        val cdata = Regex("<$tag><!\\[CDATA\\[(.*?)]]></$tag>").find(xml)
            ?: Regex("<$tag>(.*?)</$tag>").find(xml)
        return cdata?.groupValues?.get(1) ?: ""
    }

    private fun queryParam(url: String, key: String): String {
        return runCatching {
            val uri = android.net.Uri.parse(url)
            uri.getQueryParameter(key) ?: ""
        }.getOrDefault("")
    }
}
