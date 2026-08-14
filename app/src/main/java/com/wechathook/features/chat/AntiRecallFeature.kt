package com.wechathook.features.chat

import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 聊天防撤回。
 *
 * 实现思路（参考 WeKit/WAuxiliary 的 WeXmlParserApi + AntiMessageRecall）：
 * 微信收到撤回消息时，会解析 sysmsg XML（`MicroMsg.SDK.XmlParser`，8.0.7x 的
 * 实际类为 com.tencent.mm.sdk.platformtools.aa），解析结果 map 中撤回类型标记为
 * `.sysmsg.$type == "revokemsg"`。我们在该解析方法的 after 阶段，把 type 置空，
 * 使微信不把它当撤回消息处理，原消息保留显示。
 *
 * 关键点（已按 8.0.71 的 dex 字符串核实）：
 * - 定位方式：按方法内使用的特征字符串 "MicroMsg.SDK.XmlParser" + "[ %s ]" 定位，
 *   而不是按方法名（混淆后方法名不稳定）；
 * - 类型 key：`.sysmsg.$type`（带 $），不是 `.sysmsg.type`。
 */
object AntiRecallFeature : Feature {

    override val key = "anti_recall"
    override val name = "聊天防撤回"

    private val enable: Boolean get() = Prefs.getBoolean("anti_recall_enable", true)

    override fun defaultEnabled() = true

    override fun isProcessSafe() = false

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return
        if (finder == null) return

        Logger.i("[$name] 开始 Hook")

        // ---- 策略 1：XmlParser 解析结果拦截（主策略） ----
        val parserMethods = finder.findMethodsByStrings(
            classLoader,
            onlyPackages = listOf("com.tencent.mm.sdk.platformtools"),
            strings = arrayOf("MicroMsg.SDK.XmlParser", "[ %s ]")
        )
        if (parserMethods.isNotEmpty()) {
            parserMethods.take(3).forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            interceptRevoke(param)
                        }
                    })
                    Logger.i("[$name] XmlParser 拦截已生效: ${method.declaringClass.name}#${method.name}")
                }.onFailure { Logger.e("[$name] Hook XmlParser 失败: $it") }
            }
        } else {
            // 兜底：定位类后 hook 所有 parse 相关方法
            Logger.w("[$name] 未按字符串定位到 XmlParser 方法，尝试类级兜底。")
            val parserClasses = finder.findClassNamesByStrings("MicroMsg.SDK.XmlParser")
            if (parserClasses.isNotEmpty()) {
                parserClasses.take(2).forEach { clsName ->
                    runCatching {
                        val clazz = XposedHelpers.findClass(clsName, classLoader)
                        XposedBridge.hookAllMethods(clazz, "parse", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                interceptRevoke(param)
                            }
                        })
                        XposedBridge.hookAllMethods(clazz, "parseFromXML", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                interceptRevoke(param)
                            }
                        })
                        Logger.i("[$name] XmlParser 类级兜底已生效: $clsName")
                    }.onFailure { Logger.e("[$name] XmlParser 类级兜底失败: $it") }
                }
            } else {
                Logger.e("[$name] 无法定位 XmlParser，策略1不可用。")
            }
        }

        // ---- 策略 2：doRevokeMsg 阻断（辅助） ----
        // 特征字符串随版本变化，多组候选
        val revokeCandidates = arrayOf(
            arrayOf("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s"),
            arrayOf("doRevokeMsg"),
            arrayOf("revokemsg", "newmsgid")
        )
        var revokeFound = false
        for (cand in revokeCandidates) {
            val revokeMethods = finder.findMethodsByStrings(
                classLoader,
                onlyPackages = listOf("com.tencent.mm"),
                strings = cand
            )
            if (revokeMethods.isNotEmpty()) {
                revokeFound = true
                revokeMethods.take(3).forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.setResult(null)
                            }
                        })
                        Logger.i("[$name] doRevokeMsg 阻断已生效: ${method.declaringClass.name}")
                    }.onFailure { Logger.e("[$name] Hook doRevokeMsg 失败: $it") }
                }
                break
            }
        }
        if (!revokeFound) {
            Logger.w("[$name] 未定位到 doRevokeMsg，策略2不可用。")
        }
    }

    /** 拦截撤回：保留原消息，并按配置决定是否显示自定义撤回提示。 */
    private fun interceptRevoke(param: XC_MethodHook.MethodHookParam) {
        try {
            val result = param.result as? MutableMap<*, *> ?: return
            @Suppress("UNCHECKED_CAST")
            val map = result as MutableMap<String, Any?>

            val sysType = map[".sysmsg.\$type"] as? String ?: return
            if (!sysType.equals("revokemsg", ignoreCase = true)) return

            // 提取撤回信息，用于自定义提示
            val replaceMsg = map[".sysmsg.revokemsg.replacemsg"] as? String ?: ""
            val session = map[".sysmsg.revokemsg.session"] as? String ?: ""

            // 从 replacemsg 提取发送者（格式如「张三」或 "张三"）
            val sender = extractSender(replaceMsg)

            val noticeTemplate = Prefs.getString("anti_recall_notice", "")
            if (noticeTemplate.isNotBlank()) {
                // 配置了自定义提示：
                // 保留 revokemsg 类型让微信走"撤回处理"流程（它会用 replacemsg 在聊天界面
                // 插入一条系统提示），但把 newmsgid 指向一个不存在的消息 id —— 微信按此 id
                // 查不到原消息，就不会删除/改写原消息，从而同时做到"原消息保留 + 自定义提示显示"。
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val finalText = noticeTemplate
                    .replace("{sender}", sender)
                    .replace("{time}", time)
                map[".sysmsg.revokemsg.newmsgid"] = "99999999999999"
                map[".sysmsg.revokemsg.replacemsg"] = finalText
                Logger.i("[$name] 撤回已拦截（保留原消息），自定义提示: $finalText")
            } else {
                // 未配置提示：静默防撤回 —— 清掉撤回类型标记，微信不把它当撤回处理，原消息保留
                Logger.i("[$name] 检测到撤回消息，已拦截（保留原消息）")
                map[".sysmsg.\$type"] = null
                map[".sysmsg.revokemsg.newmsgid"] = null
            }
        } catch (t: Throwable) {
            // 单次解析失败不影响
        }
    }

    /** 从 replacemsg（如「张三」撤回了一条消息）提取发送者名。 */
    private fun extractSender(replaceMsg: String): String {
        if (replaceMsg.isEmpty()) return ""
        // 匹配「」或 "" 内的名字
        val m = Regex("""[「"]([^」"]+)[」"]""").find(replaceMsg)
        return m?.groupValues?.get(1) ?: ""
    }
}
