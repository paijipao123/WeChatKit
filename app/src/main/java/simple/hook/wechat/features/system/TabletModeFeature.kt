package simple.hook.wechat.features.system

import simple.hook.wechat.core.DexKitFinder
import simple.hook.wechat.core.Feature
import simple.hook.wechat.core.Logger
import simple.hook.wechat.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 平板模式。
 *
 * 强制微信把当前设备识别为平板，从而：
 * 1. 两台设备同号：本机显示"平板登录"二维码，另一台手机上的微信扫码，两端同时在线；
 * 2. 同机双开：把本模块打包成修补版（NPatch/LSPatch 改包，签名不同可与原版共存），
 *    修补版显示平板登录码，原版微信扫码，同一台手机两个微信同时在线。
 *
 * 实现（参考 WeKit ForceTabletMode，特征已按 8.0.76 dex 核实）：
 * - `com.tencent.mm.ui.bk.B()`：微信 isTablet 判断 → 强制 true（核心，微信走平板 UI）；
 * - `isRoyoleFoldableDevice`：折叠屏/平板设备判断 → 强制 true；
 * - `MicroMsg.CgiCheckLoginAsPad`（e01.k1）：平板登录确认 CGI → 放行；
 * - `loginAsOtherDeviceBtn`（x61.h0）：登录界面"其他设备登录"按钮 → 可见；
 * - SystemProperties 平板属性伪装（增强，尽力而为）。
 *
 * 警告：平板模式属于账号风控敏感操作，双端同时在线请谨慎。
 */
object TabletModeFeature : Feature {

    override val key = "tablet_mode"
    override val name = "平板模式"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = true

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_tablet_mode", false)) return
        Logger.i("[$name] 开始 Hook")
        if (finder == null) return

        // ---- 1. isTablet 判断 → true（核心，微信走平板 UI/登录流程） ----
        hookMethodCandidates(finder, classLoader, "isTablet",
            declared = "com.tencent.mm.ui.bk",
            strings = arrayOf("Lenovo TB-9707F")) { param ->
            param.setResult(true)
        }

        // ---- 2. 折叠屏设备判断 → true（辅助） ----
        hookMethodCandidates(finder, classLoader, "isFoldable",
            declared = null,
            strings = arrayOf("isRoyoleFoldableDevice")) { param ->
            param.setResult(true)
        }

        // ---- 3. 平板登录确认 CGI（扫码登录后客户端确认） ----
        hookMethodCandidates(finder, classLoader, "checkLoginAsPad",
            declared = "e01.k1",
            strings = arrayOf("MicroMsg.CgiCheckLoginAsPad")) { param ->
            param.setResult(true)
        }

        // ---- 4. 登录界面"其他设备登录"按钮可见 ----
        hookMethodCandidates(finder, classLoader, "loginOtherDeviceBtn",
            declared = null,
            strings = arrayOf("loginAsOtherDeviceBtn")) { param ->
            runCatching {
                val v = param.args.getOrNull(0) as? android.view.View
                if (v != null && v.visibility != android.view.View.VISIBLE) {
                    v.visibility = android.view.View.VISIBLE
                }
            }
        }

        // ---- 5. 系统属性伪装（增强；boot 类 hook 在当前环境可能无效，尽力而为） ----
        runCatching {
            XposedBridge.hookAllMethods(
                Class.forName("android.os.SystemProperties"), "get",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val key = param.args.getOrNull(0) as? String ?: return
                            if (key.equals("ro.build.characteristics", ignoreCase = true)) {
                                param.setResult("tablet")
                            }
                        }
                    }
                })
            Logger.i("[$name] SystemProperties 平板属性伪装已挂载")
        }.onFailure { }
    }

    /** 按"精确类名+字符串"优先、"纯字符串"兜底定位方法并 hook。 */
    private fun hookMethodCandidates(
        finder: DexKitFinder,
        classLoader: ClassLoader,
        tag: String,
        declared: String?,
        strings: Array<String>,
        block: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        val m1 = if (declared != null && declared.isNotEmpty()) {
            runCatching {
                finder.findMethodsByStrings(
                    classLoader,
                    declaredClassName = declared,
                        strings = strings
                )
            }.getOrDefault(emptyList())
        } else emptyList()

        val m2 = if (m1.isEmpty()) {
            runCatching {
                finder.findMethodsByStrings(
                    classLoader,
                        strings = strings
                )
            }.getOrDefault(emptyList())
        } else emptyList()

        val methods = (m1 + m2).distinctBy { it.toString() }
        if (methods.isEmpty()) {
            Logger.w("[$name] 未定位 $tag")
            return
        }
        methods.take(3).forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            block(param)
                        } catch (_: Throwable) {}
                    }
                })
                Logger.i("[$name] $tag 已生效: ${method.declaringClass.name}#${method.name}")
            }.onFailure { Logger.e("[$name] $tag hook 失败: $it") }
        }
    }
}
