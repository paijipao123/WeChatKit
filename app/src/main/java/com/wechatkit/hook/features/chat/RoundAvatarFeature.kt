package com.wechatkit.hook.features.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import com.wechatkit.hook.core.DexKitFinder
import com.wechatkit.hook.core.Feature
import com.wechatkit.hook.core.Logger
import com.wechatkit.hook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlin.math.min

/**
 * 圆形头像（全局）。
 *
 * 微信 8.0.76 实测：消息头像 drawable 是 `ta5$d`（默认包，非 pluginsdk.ui.x），
 * 该类无圆角逻辑，直接画位图；圆角由外层 MaskLayout 遮罩（setMaskBitmap/setMaskDrawable）完成。
 *
 * 三层方案：
 * 1. hook `ta5$d.draw` → Canvas 圆形 clipPath 强制裁剪（任何场景绘制头像位图都会被切圆，
 *    不依赖微信自己的圆角路径）；save/restore 成对；
 * 2. hook `ta5$d` 构造 → 诊断第二个 int 参数（疑似圆角半径）；
 * 3. hook MaskLayout.setMaskBitmap/setMaskDrawable → 诊断遮罩路径（确认圆形遮罩是否生效）；
 * 4. 保留 x / u#b 双保险（会话列表等场景 drawable 可能是 x）。
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

    /** draw 诊断计数。 */
    private val drawDiagCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 我们自己 save 过的 Canvas -> 层数（before/after 配对 restore）。 */
    private val canvasSaves =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Canvas, Int>())

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_round_avatar", false)) return
        Logger.i("[$name] 开始 Hook (radius=$radius)")
        if (finder == null) return

        // ---- 0. 消息头像 drawable：ta5$d（默认包） ----
        runCatching {
            val ta5d = XposedHelpers.findClass("ta5\$d", classLoader)

            XposedBridge.hookAllConstructors(ta5d, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (drawDiagCount.getAndIncrement() < 10) {
                        val intArg = param.args.getOrNull(1)
                        Logger.i("[$name] [DIAG] ta5\$d 构造: args=${param.args.size}, intArg=$intArg, cls=${param.thisObject.javaClass.name}")
                    }
                }
            })

            XposedBridge.hookAllMethods(ta5d, "draw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val canvas = param.args.getOrNull(0) as? Canvas ?: return
                        val d = param.thisObject as? Drawable ?: return
                        val b = d.bounds
                        if (b.width() <= 0 || b.height() <= 0) return
                        val r = min(b.width(), b.height()) / 2f
                        val path = Path().apply {
                            addCircle(b.exactCenterX(), b.exactCenterY(), r, Path.Direction.CCW)
                        }
                        canvas.save()
                        runCatching { canvas.clipPath(path) }
                        canvasSaves[canvas] = (canvasSaves[canvas] ?: 0) + 1
                        if (drawDiagCount.getAndIncrement() < 10) {
                            Logger.i("[$name] [DIAG] ta5\$d.draw 裁剪 半径=$r bounds=${b.width()}x${b.height()} hw=${canvas.isHardwareAccelerated}")
                        }
                    } catch (_: Throwable) {}
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val canvas = param.args.getOrNull(0) as? Canvas ?: return
                        val n = canvasSaves.remove(canvas) ?: return
                        repeat(n) { runCatching { canvas.restore() } }
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("[$name] ta5\$d.draw 圆形裁剪已挂载")
        }.onFailure { Logger.e("[$name] ta5\$d hook 失败: $it") }

        // ---- 0.5 MaskLayout 遮罩诊断 ----
        runCatching {
            val maskLayout = XposedHelpers.findClass("com.tencent.mm.ui.base.MaskLayout", classLoader)
            XposedBridge.hookAllMethods(maskLayout, "setMaskBitmap", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (drawDiagCount.getAndIncrement() < 10) {
                        val bmp = param.args.getOrNull(0) as? Bitmap
                        Logger.i("[$name] [DIAG] MaskLayout.setMaskBitmap: ${bmp?.width}x${bmp?.height}")
                    }
                }
            })
            XposedBridge.hookAllMethods(maskLayout, "setMaskDrawable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (drawDiagCount.getAndIncrement() < 10) {
                        val d = param.args.getOrNull(0) as? Drawable
                        Logger.i("[$name] [DIAG] MaskLayout.setMaskDrawable: ${d?.javaClass?.name}")
                    }
                }
            })
            Logger.i("[$name] MaskLayout 遮罩诊断已挂载")
        }.onFailure { }

        // ---- 1. 头像 Drawable 类 x：构造 + 绘制强制圆角（会话列表等场景） ----
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
                        if (drawDiagCount.getAndIncrement() < 10) {
                            val s = XposedHelpers.getFloatField(param.thisObject, "s")
                            Logger.i("[$name] [DIAG] x.draw 触发, s=$s")
                        }
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
