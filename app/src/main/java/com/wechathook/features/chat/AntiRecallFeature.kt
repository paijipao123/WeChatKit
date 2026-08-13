package com.wechathook.features.chat

import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 聊天防撤回。
 *
 * 采用两层策略（均通过 DexKit 特征定位，尽量跨版本可用）：
 *
 * 1. 主动作（主流方案）：监控微信解析 sysmsg XML 的方法（`MicroMsg.SDK.XmlParser`），
 *    返回的结果 map 里若含 `revokemsg`，则把消息类型标记清空，使微信不把它当撤回消息处理，
 *    原消息保留显示。
 *
 * 2. 辅助（阻断撤回处理）：定位微信 `doRevokeMsg`（特征字符串
 *    `"doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s"`），在 before 阶段直接阻断成空返回，
 *    阻止本地构造撤回指令。两层都做能大幅提升有效性，且任一失效不影响另一层。
 */
object AntiRecallFeature : Feature {

    override val key = "anti_recall"
    override val name = "聊天防撤回"

    private val enable: Boolean get() = Prefs.getBoolean("anti_recall_enable", true)
    private val notifyAsSystem: Boolean get() = Prefs.getBoolean("anti_recall_notify", true)

    override fun defaultEnabled() = true

    override fun isProcessSafe() = false

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return
        if (finder == null) return

        Logger.i("[$name] 开始 Hook")

        // ---- 策略 1：XmlParser 撤回拦截 ----
        val parserClasses = finder.findClassNamesByStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
        if (parserClasses.isNotEmpty()) {
            parserClasses.forEach { clsName ->
                runCatching {
                    hookXmlParser(clsName, classLoader)
                    Logger.i("[$name] XmlParser 拦截已生效: $clsName")
                }.onFailure { Logger.e("[$name] Hook XmlParser 失败: $it") }
            }
        } else {
            Logger.w("[$name] 未定位到 XmlParser，策略1不可用。")
        }

        // ---- 策略 2：doRevokeMsg 阻断 ----
        val revokeMethods = finder.findMethodsByStrings(
            classLoader,
            onlyPackages = listOf("com.tencent.mm"),
            strings = arrayOf("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
        )
        if (revokeMethods.isNotEmpty()) {
            revokeMethods.take(3).forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    })
                    Logger.i("[$name] doRevokeMsg 阻断已生效: ${method.declaringClass.name}")
                }.onFailure { Logger.e("[$name] Hook doRevokeMsg 失败: $it") }
            }
        } else {
            Logger.w("[$name] 未定位到 doRevokeMsg，策略2不可用。")
        }
    }

    private fun hookXmlParser(clsName: String, classLoader: ClassLoader) {
        val clazz = de.robv.android.xposed.XposedHelpers.findClass(clsName, classLoader)
        // XmlParser 的解析方法多为静态、参数通常是 (String xml, String rootTag)，返回 MutableMap
        XposedBridge.hookAllMethods(clazz, "parse", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val result = param.result as? MutableMap<*, *> ?: return
                    @Suppress("UNCHECKED_CAST")
                    val map = result as MutableMap<String, Any?>
                    val sysType = map[".sysmsg.type"] as? String ?: return
                    if (!sysType.equals("revokemsg", ignoreCase = true)) return

                    Logger.i("[$name] 检测到撤回消息，已拦截（保留原消息）")
                    // 清空撤回标记，微信将不把它视为撤回消息
                    map[".sysmsg.type"] = null
                } catch (t: Throwable) {
                    // 单次解析失败不影响
                }
            }
        })

        // 部分版本方法名可能不是 parse，兜底 hook 名字含 "parse" 的方法
        val clazz2 = clazz
        XposedBridge.hookAllMethods(clazz2, "parseFromXML", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val result = param.result as? MutableMap<*, *> ?: return
                    @Suppress("UNCHECKED_CAST")
                    val map = result as MutableMap<String, Any?>
                    if ((map[".sysmsg.type"] as? String)?.equals("revokemsg", true) == true) {
                        map[".sysmsg.type"] = null
                    }
                } catch (_: Throwable) {}
            }
        })
    }
}
