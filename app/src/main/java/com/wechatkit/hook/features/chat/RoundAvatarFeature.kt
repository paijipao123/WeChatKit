package com.wechatkit.hook.features.chat

import com.wechatkit.hook.core.DexKitFinder
import com.wechatkit.hook.core.Feature
import com.wechatkit.hook.core.Logger
import com.wechatkit.hook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 圆形头像（全局）。
 *
 * 微信头像 Drawable 统一为 `com.tencent.mm.pluginsdk.ui.x`：
 * - 字段 `s`（float）= 圆角半径比例，绘制时 `radius = s * 宽`（0.5 = 正圆）；
 * - `draw(Canvas)` 内按 s 画圆角矩形/圆形。
 *
 * 为了"全局生效、不局限于聊天"且不被微信覆盖：
 * 1. hook `x` 构造 → 强制 s = 圆角因子；
 * 2. hook `x.draw`（每次绘制前）→ 强制 s = 圆角因子，任何头像（聊天/会话列表/资料页/群成员）
 *    绘制时都会被压成圆；
 * 3. 保留头像加载入口 `pluginsdk.ui.u` 的 float 参数修改（双保险）。
 */
object RoundAvatarFeature : Feature {

    override val key = "round_avatar"
    override val name = "圆形头像"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = true

    /** 圆角因子（0.1~0.5，0.5 为正圆）。UI 滑块存整数 1~5，这里除以 10。 */
    private val radius: Float
        get() = (Prefs.getInt("round_avatar_radius", 5).coerceIn(1, 5)) / 10f

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_round_avatar", false)) return
        Logger.i("[$name] 开始 Hook (radius=$radius)")
        if (finder == null) return

        // ---- 1. 头像 Drawable 类 x：构造 + 绘制强制圆角（全局） ----
        runCatching {
            val xCls = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.x", classLoader)

            XposedBridge.hookAllConstructors(xCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        XposedHelpers.setFloatField(param.thisObject, "s", radius)
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("[$name] x 构造强制圆角已挂载")

            XposedBridge.hookAllMethods(xCls, "draw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        XposedHelpers.setFloatField(param.thisObject, "s", radius)
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("[$name] x.draw 强制圆角已挂载（全局）")
        }.onFailure { Logger.e("[$name] x 类 hook 失败: $it") }

        // ---- 2. 头像加载入口 u#b 的 float 参数（双保险） ----
        if (finder != null) {
            val methods = runCatching {
                finder.findMethodsByStrings(
                    classLoader,
                    declaredClassName = "com.tencent.mm.pluginsdk.ui.u",
                    onlyPackages = listOf("com.tencent.mm"),
                    strings = arrayOf("MicroMsg.AvatarDrawable")
                )
            }.getOrDefault(emptyList())
            methods.take(4).forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.size > 2 && param.args[2] is Float) {
                                param.args[2] = radius
                            }
                        }
                    })
                    Logger.i("[$name] 加载入口已生效: ${method.declaringClass.name}#${method.name}")
                }.onFailure { }
            }
        }
    }
}
