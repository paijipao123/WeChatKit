package com.wechatkit.hook.features.chat

import com.wechatkit.hook.core.DexKitFinder
import com.wechatkit.hook.core.Feature
import com.wechatkit.hook.core.Logger
import com.wechatkit.hook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 圆形头像。
 *
 * 微信头像统一通过 `com.tencent.mm.pluginsdk.ui.u.b(ImageView, String, float, boolean)`
 * 加载（特征串 "MicroMsg.AvatarDrawable"），第 3 个 float 参数是头像圆角因子：
 * 0.5 = 正圆，0.1 = 接近直角。hook 它把圆角因子改成配置值即可全局生效。
 *
 * 参考：WeKit RoundAvatars。
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

        // 主方法：头像加载（参数 2 为圆角因子）
        val methods = runCatching {
            finder.findMethodsByStrings(
                classLoader,
                declaredClassName = "com.tencent.mm.pluginsdk.ui.u",
                onlyPackages = listOf("com.tencent.mm"),
                strings = arrayOf("MicroMsg.AvatarDrawable")
            )
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            Logger.w("[$name] 未定位到头像加载方法")
            return
        }

        methods.take(4).forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // args: (ImageView, String, float, boolean)
                        if (param.args.size > 2 && param.args[2] is Float) {
                            param.args[2] = radius
                        }
                    }
                })
                Logger.i("[$name] 已生效: ${method.declaringClass.name}#${method.name}")
            }.onFailure { Logger.e("[$name] hook 失败: $it") }
        }
    }
}
