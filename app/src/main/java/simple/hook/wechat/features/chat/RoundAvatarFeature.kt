package simple.hook.wechat.features.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import simple.hook.wechat.core.DexKitFinder
import simple.hook.wechat.core.Feature
import simple.hook.wechat.core.Logger
import simple.hook.wechat.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlin.math.min

/**
 * 圆形头像（全局）。
 *
 * 微信 8.0.76 实测：消息头像 drawable 是 `ta5.d`（包 `ta5` 下的独立类 d，
 * dex 描述符 `Lta5/d;`，非内部类、非 pluginsdk.ui.x），
 * 该类无圆角逻辑，直接画位图；圆角由外层 MaskLayout 遮罩（setMaskBitmap/setMaskDrawable）完成。
 *
 * 三层方案：
 * 1. hook `ta5.d.draw` → Canvas 圆形 clipPath 强制裁剪（任何场景绘制头像位图都会被切圆，
 *    不依赖微信自己的圆角路径）；save/restore 成对；
 * 2. hook `ta5.d` 构造 → 诊断第二个 int 参数（疑似圆角半径）；
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

    /** ta5.d 构造诊断计数。 */
    private val ctorDiagCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** MaskLayout 遮罩诊断计数。 */
    private val maskDiagCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** x.draw 诊断计数。 */
    private val xDrawDiagCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 我们自己 save 过的 Canvas -> saveCount（before/after 配对 restoreToCount）。 */
    private val canvasSaves =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Canvas, Int>())

    /** 给头像 View 设置圆形 outline + clipToOutline（View 级圆形裁剪，硬件加速下可靠）。
     * 用 tag 标记我们已设置过，避免重复；微信若重置 outline，tag 一并被清则重新设置。 */
    private fun applyRoundOutline(v: android.view.View) {
        val ourTag = 0x7F0D0001
        if (v.getTag(ourTag) == true) return
        try {
            v.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    if (view.width <= 0 || view.height <= 0) return
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            v.clipToOutline = true
            v.setTag(ourTag, true)
            // 尺寸变化时刷新 outline
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (v.width > 0 && v.height > 0) v.invalidateOutline()
            }
        } catch (_: Throwable) {}
    }

    /** 在视图树中查找 ChattingAvatarImageView 实例。 */
    private fun findAvatarIn(root: android.view.View): android.view.View? {
        if (root.javaClass.name == "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView") return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findAvatarIn(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_round_avatar", false)) return
        Logger.i("[$name] 开始 Hook (radius=$radius)")
        if (finder == null) return

        // ---- 0. 头像 View 圆形裁剪（g0.create/setChattingItem 拿头像实例直接设置，
        // 绕开构造 hook 不触发的问题；outline + clipToOutline 硬件加速下可靠） ----
        runCatching {
            val g0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.g0", classLoader)

            XposedBridge.hookAllMethods(g0, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val root = param.args.getOrNull(0) as? android.view.View ?: return
                        val avatar = findAvatarIn(root) ?: return
                        applyRoundOutline(avatar)
                        if (ctorDiagCount.getAndIncrement() < 10) {
                            Logger.i("[$name] [DIAG] create 中设置圆形 outline w=${avatar.width} h=${avatar.height}")
                        }
                    } catch (_: Throwable) {}
                }
            })

            XposedBridge.hookAllMethods(g0, "setChattingItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val avatar = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? android.view.View
                        }.getOrNull() ?: return
                        applyRoundOutline(avatar)
                        if (ctorDiagCount.getAndIncrement() < 20) {
                            Logger.i("[$name] [DIAG] setChattingItem 后强制圆形 outline w=${avatar.width} h=${avatar.height}")
                        }
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$name] g0.create/setChattingItem 圆形 outline 已挂载")
        }.onFailure { Logger.e("[$name] g0 圆形 outline hook 失败: $it") }

        // ---- 0.5 头像 View 构造 + setImageDrawable 双保险（若走得到） ----
        runCatching {
            val avatarCls = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView", classLoader)
            XposedBridge.hookAllConstructors(avatarCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? android.view.View ?: return
                        applyRoundOutline(v)
                        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            applyRoundOutline(v)
                        }
                        if (ctorDiagCount.getAndIncrement() < 20) {
                            Logger.i("[$name] [DIAG] ChattingAvatarImageView 构造: 圆形 outline 已设置")
                        }
                    } catch (_: Throwable) {}
                }
            })

            // 每次设置头像图片后强制圆形 outline（微信绑定数据时可能覆盖）
            XposedBridge.hookAllMethods(avatarCls, "setImageDrawable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? android.view.View ?: return
                        applyRoundOutline(v)
                        if (ctorDiagCount.getAndIncrement() < 30) {
                            Logger.i("[$name] [DIAG] setImageDrawable 后强制圆形 outline w=${v.width} h=${v.height}")
                        }
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$name] ChattingAvatarImageView 构造 + setImageDrawable 兜底已挂载")
        }.onFailure { Logger.e("[$name] 头像 View 构造 hook 失败: $it") }

        // ---- 0.2 头像 Drawable tn1.e：微信头像圆角的真正控制点（参考 WeKit RoundAvatars） ----
        // 8.0.76: 类 tn1.e 构造 <init>(LifecycleScope, String, F, Z, String, I, i) float 在 args[2]；
        //         方法 c(tn1/e, LifecycleScope, String, F, Z, String, I, Object) float 在 args[3]。
        // 微信用它渲染所有头像的圆角，改这个参数 = 全局圆角生效。
        runCatching {
            val tn1e = XposedHelpers.findClass("tn1.e", classLoader)

            XposedBridge.hookAllConstructors(tn1e, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.args.size > 2 && param.args[2] is Float) {
                            param.args[2] = radius
                            if (ctorDiagCount.getAndIncrement() < 30) {
                                Logger.i("[$name] [DIAG] tn1.e 构造圆角参数改为 $radius")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })

            XposedBridge.hookAllMethods(tn1e, "c", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.args.size > 3 && param.args[3] is Float) {
                            param.args[3] = radius
                            if (ctorDiagCount.getAndIncrement() < 30) {
                                Logger.i("[$name] [DIAG] tn1.e.c 圆角参数改为 $radius")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("[$name] tn1.e 圆角参数 hook 已挂载（参考 WeKit 方案）")
        }.onFailure { Logger.e("[$name] tn1.e hook 失败: $it") }

        // ---- 1. 消息头像 drawable：ta5.d（包 ta5 下的独立类 d，非内部类） ----
        runCatching {
            val ta5d = XposedHelpers.findClass("ta5.d", classLoader)
            XposedBridge.hookAllConstructors(ta5d, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (ctorDiagCount.getAndIncrement() < 12) {
                        val intArg = param.args.getOrNull(1)
                        // intArg 是资源 ID(0x7F0B...)，打印对应资源名定位遮罩形状
                        var resName = ""
                        if (intArg is Int) {
                            resName = runCatching {
                                val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.app.Application
                                app?.resources?.getResourceName(intArg) ?: ""
                            }.getOrDefault("")
                        }
                        Logger.i("[$name] [DIAG] ta5.d 构造: intArg=$intArg res=$resName cls=${param.thisObject.javaClass.name}")
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
                        val sc = canvas.save()
                        runCatching { canvas.clipPath(path) }
                        canvasSaves[canvas] = sc
                        if (drawDiagCount.getAndIncrement() < 20) {
                            Logger.i("[$name] [DIAG] ta5.d.draw 裁剪 半径=$r bounds=${b.width()}x${b.height()} hw=${canvas.isHardwareAccelerated}")
                        }
                    } catch (_: Throwable) {}
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val canvas = param.args.getOrNull(0) as? Canvas ?: return
                        val sc = canvasSaves.remove(canvas) ?: return
                        runCatching { canvas.restoreToCount(sc) }
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("[$name] ta5.d.draw 圆形裁剪已挂载")
        }.onFailure { Logger.e("[$name] ta5.d hook 失败: $it") }

        // ---- 0.5 MaskLayout 遮罩诊断 ----
        runCatching {
            val maskLayout = XposedHelpers.findClass("com.tencent.mm.ui.base.MaskLayout", classLoader)
            XposedBridge.hookAllMethods(maskLayout, "setMaskBitmap", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (maskDiagCount.getAndIncrement() < 10) {
                        val bmp = param.args.getOrNull(0) as? Bitmap
                        Logger.i("[$name] [DIAG] MaskLayout.setMaskBitmap: ${bmp?.width}x${bmp?.height}")
                    }
                }
            })
            XposedBridge.hookAllMethods(maskLayout, "setMaskDrawable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (maskDiagCount.getAndIncrement() < 10) {
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
                        if (xDrawDiagCount.getAndIncrement() < 10) {
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
