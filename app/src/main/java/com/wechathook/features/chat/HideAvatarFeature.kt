package com.wechathook.features.chat

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Views
import com.wechathook.core.Reflect
import com.wechathook.core.Prefs
import com.wechathook.core.SymbolResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * 隐藏聊天消息头像，同时保持消息气泡紧凑、左右间距与微信原版一致。
 *
 * 微信 8.0.7x 的头像结构（已按 8.0.71 的 dex 核实）：
 * - 头像 View 类：`com.tencent.mm.ui.chatting.view.ChattingAvatarImageView`
 *   （固定类名，多个 dex 引用；消息头像统一用它，不再使用旧的 MaskLayout）
 * - 布局：X2C 布局 `chatting_item_avatar_from_x2c` / `_to_x2c`
 *
 * 因此主策略改为直接 hook ChattingAvatarImageView（最可靠）：
 * 1. hook onMeasure：把尺寸收为 0（保锚点，气泡紧凑）；
 * 2. hook onAttachedToWindow：直接把自身 GONE（兜底）；
 * 3. 保留 onBindView 遍历 + MaskLayout 兜底（兼容旧版本微信）。
 */
object HideAvatarFeature : Feature {

    override val key = "hide_avatar"
    override val name = "隐藏消息头像（紧凑）"

    /** 微信 8.0.7x 消息头像类（固定名）。 */
    private const val AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"

    /** 另一个头像类（部分界面使用）。 */
    private const val AVATAR_VIEW_CLASS2 = "com.tencent.mm.ui.chatting.view.AvatarImageView"

    private val maskLayoutClass = "com.tencent.mm.ui.base.MaskLayout"

    override fun defaultEnabled() = true

    override fun isProcessSafe() = false

    /**
     * 隐藏范围模式：
     * - "incoming" 只隐藏对方（默认）
     * - "outgoing" 只隐藏自己
     * - "all"      全部隐藏
     * - "off"      关闭
     * 兼容旧配置 hide_avatar_enable / hide_avatar_outgoing。
     */
    private fun mode(): String {
        if (!Prefs.getBoolean("feat_hide_avatar", true)) return "off"
        val m = Prefs.getString("hide_avatar_mode", "")
        if (m.isNotEmpty()) return m
        return when {
            !Prefs.getBoolean("hide_avatar_enable", true) -> "off"
            Prefs.getBoolean("hide_avatar_outgoing", false) -> "all"
            else -> "incoming"
        }
    }

    /** 消息上下间距（dp），0 = 不调整。 */
    private val itemSpacing: Int
        get() = Prefs.getInt("chat_item_spacing", 0).coerceIn(0, 60)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        val m = mode()
        if (m == "off" && itemSpacing <= 0) return

        Logger.i("[$name] 开始 Hook (mode=$m, spacing=$itemSpacing dp)")
        SymbolResolver.detectWechatVersion()
        Logger.i("[$name] 微信版本: ${SymbolResolver.wechatVersionName}")

        // ---- 主策略：动态定位微信头像 View 类 ----
        // 8.0.7x: ChattingAvatarImageView (X2C 布局)
        // 老版本: AvatarImageView / MaskLayout
        val avatarClass = SymbolResolver.resolveClass(
            classLoader, finder,
            featureGroups = arrayOf(
                arrayOf("MicroMsg.ChattingAvatarImageView"),
                arrayOf("ChattingAvatarImageView")
            ),
            candidateNames = arrayOf(
                "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView",
                "com.tencent.mm.ui.chatting.view.AvatarImageView"
            )
        )
        if (avatarClass != null) {
            hookAvatarClassByName(avatarClass, classLoader, "头像类")
        }

        // ---- 主策略2：hook 消息项 View 控制器 (viewitems 包, 持有 avatarIV) ----
        // 8.0.7x: viewitems.g0；其他版本类名可能不同，用 avatarIV 字段特征兜底
        val controllerClass = SymbolResolver.findExistingClass(
            classLoader,
            "com.tencent.mm.ui.chatting.viewitems.g0",
            "com.tencent.mm.ui.chatting.viewitems.cb0",   // 旧版可能的控制器
            "com.tencent.mm.ui.chatting.viewitems.az"     // 更旧版本
        )
        if (controllerClass != null) {
            hookChatItemControllerByName(controllerClass, classLoader)
        } else {
            // 兜底：通过字段特征找持有 avatarIV 的类
            if (finder != null) {
                val holders = finder.findClassNamesByStrings("avatarIV")
                holders.take(3).forEach { holder ->
                    runCatching {
                        hookChatItemControllerByName(holder, classLoader)
                    }.onFailure { Logger.e("[$name] 控制器兜底 Hook $holder 失败: $it") }
                }
            }
        }

        // ---- 辅助 1：消息 item onBindView 遍历隐藏 ----
        if (finder != null) {
            val targetClassNames = finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
            if (targetClassNames.isEmpty()) {
                val looser = finder.findClassNamesByStrings("MvvmChattingItem", "onBindView")
                if (looser.isNotEmpty()) hookClass(looser.first(), classLoader)
            } else {
                targetClassNames.forEach { hookClass(it, classLoader) }
            }
        }

        // ---- 辅助 2：MaskLayout 兜底（兼容旧版本微信） ----
        hookMaskLayoutFallback(classLoader)
    }

    /** 按名称 hook 头像类（复用 [hookAvatarViewClass] 的逻辑）。 */
    private fun hookAvatarClassByName(className: String, classLoader: ClassLoader, tag: String) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            Logger.i("[$name] 找到$tag: $className")

            // 构造/测量/设图等"无坐标"时机无法判断方向：
            // 仅在 mode=all 时直接隐藏，其余模式交由 onLayout 按左右位置判断。
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? View ?: return
                            if (mode() == "all") hideAvatarView(v)
                        } catch (_: Throwable) {}
                    }
                })
            }
            XposedBridge.hookAllMethods(clazz, "onMeasure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (mode() == "all") {
                            hideAvatarView(v)
                            v.visibility = View.GONE
                            param.setResult(null)
                            runCatching {
                                val m = View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                                m.isAccessible = true
                                m.invoke(v, 0, 0)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(clazz, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (mode() == "all") hideAvatarView(v)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(clazz, "setVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (mode() == "all") hideAvatarView(v)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(clazz, "setImageBitmap", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (mode() == "all") {
                            hideAvatarView(v)
                            param.setResult(null)
                        }
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(clazz, "setImageResource", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (mode() == "all") hideAvatarView(v)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(clazz, "onDraw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        // onDraw 时头像必有有效坐标：作为方向判断的兜底
                        // （view 被微信重新 setVisibility(VISIBLE) 后，onLayout 不一定触发，onDraw 一定触发）
                        applyDirectionalHide(v)
                    } catch (_: Throwable) {}
                }
            })

            // 核心：onLayout 时头像已有坐标 —— 按左右位置判断方向，
            // 支持"只隐藏对方 / 只隐藏自己 / 全部隐藏"，同时应用消息间距。
            XposedBridge.hookAllMethods(clazz, "onLayout", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        applyDirectionalHide(v)
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$name] 已 Hook $tag: $className")
        }.onFailure { Logger.e("[$name] $tag Hook 失败: $it") }
    }

    /** 方向判断 DEBUG 日志计数（限频）。 */
    private val dirLogCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 方向感知隐藏：头像已布局（有坐标）时，
     * 按头像中心相对消息 item 的水平位置判断方向（左=对方，右=自己），
     * 再按模式决定是否隐藏；同时把消息间距应用到 item 根。
     */
    private fun applyDirectionalHide(v: View) {
        val m = mode()
        if (m == "off") return

        val itemRoot = findItemRoot(v)
        if (itemRoot != null) {
            applyItemSpacing(itemRoot)
            if (m == "all") {
                hideAvatarView(v)
                return
            }
            if (itemRoot.width <= 0 || v.width <= 0) return
            if (!v.isAttachedToWindow) return

            // 用窗口绝对坐标计算头像中心相对消息 item 根的水平位置（不受内部嵌套容器影响）
            val vLoc = IntArray(2)
            val rLoc = IntArray(2)
            runCatching {
                v.getLocationInWindow(vLoc)
                itemRoot.getLocationInWindow(rLoc)
            }.onFailure { return }
            val cx = vLoc[0] + v.width / 2f - rLoc[0]
            val isLeft = cx <= itemRoot.width / 2f
            if (dirLogCount.getAndIncrement() < 40) {
                Logger.i("[$name] [DIR] m=$m vW=${v.width} rootW=${itemRoot.width} vX=${vLoc[0]} rX=${rLoc[0]} cx=$cx isLeft=$isLeft")
            }
            val hide = if (m == "incoming") isLeft else !isLeft
            if (hide) hideAvatarView(v)
        } else {
            // 找不到 item 根：保守隐藏（避免漏掉头像）
            if (m == "all") hideAvatarView(v)
        }
    }

    /** 向上找消息 item 根 View（RecyclerView 的直接子 View）。 */
    private fun findItemRoot(v: View): View? {
        var cur: android.view.ViewParent? = v.parent
        var guard = 0
        while (cur is View && guard < 10) {
            val curView = cur as View
            val pp = curView.parent
            if (pp is androidx.recyclerview.widget.RecyclerView) return curView
            cur = pp
            guard++
        }
        return null
    }

    /** 把配置的消息间距（dp）应用到 item 根 View 的上下 margin。 */
    private fun applyItemSpacing(itemRoot: View) {
        val sp = itemSpacing
        if (sp <= 0) return
        runCatching {
            val lp = itemRoot.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val px = (sp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
                lp.topMargin = px
                lp.bottomMargin = px
                itemRoot.layoutParams = lp
            }
        }
    }

    /** 直接 hook 微信头像 View 类。 */
    private fun hideAvatarView(v: View) {
        // 每次调用都强制隐藏（不短路）——RecyclerView 复用同一 View 时，
        // 微信可能把它重新设为 VISIBLE 并重新绑定，必须反复压回。
        // 1) 自身 GONE + 尺寸 0
        v.visibility = View.GONE
        runCatching {
            val lp = v.layoutParams
            lp.width = 0
            lp.height = 0
            v.layoutParams = lp
        }

        // 2) 收窄父容器（若父容器是头像专用容器，如 MaskLayout 或类似布局）
        runCatching {
            val parent = v.parent
            if (parent is View) {
                val parentLp = parent.layoutParams
                if (parentLp.width > 0) {
                    parentLp.width = 0
                    parent.layoutParams = parentLp
                }
            }
        }

        Logger.i("[$name] 已隐藏头像: ${v.javaClass.name}")
    }

    /** Hook 第二个头像类（AvatarImageView）。 */
    private fun hookClass(className: String, classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            XposedBridge.hookAllMethods(clazz, "onBindView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyHide(param)
                }
            })
            Logger.i("[$name] 已 Hook $className#onBindView")
        }.onFailure { Logger.e("[$name] Hook $className 失败: $it") }
    }

    private fun applyHide(param: XC_MethodHook.MethodHookParam) {
        try {
            val holder = param.args[0] ?: return
            val holderView = holderToView(holder)
            if (holderView == null) {
                Logger.w("[$name] holder 中未找到 View (holder=${holder.javaClass.name})")
                return
            }
            // 消息间距（独立于头像模式，绑定阶段应用一次即可）
            applyItemSpacing(holderView)
            // 调试：前 5 次打印消息 View 树结构（帮助定位头像真实位置）
            if (debugDumpCount.getAndIncrement() < 5) {
                dumpViewTree(holderView)
            }
            // 全部隐藏模式：绑定阶段直接隐藏；仅隐藏对方/自己交给 onLayout 方向判断
            if (mode() == "all") {
                hideAvatarIn(holderView)
            }
        } catch (t: Throwable) {
            Logger.e("[$name] applyHide 异常: $t")
        }
    }

    private val debugDumpCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 打印消息 View 树（调试用）。 */
    private fun dumpViewTree(root: View) {
        val sb = StringBuilder()
        var count = 0
        Views.walk(root) { v ->
            if (count < 40) {
                sb.append(v.javaClass.name).append(" | ")
            }
            count++
            false
        }
        Logger.i("[$name] [DEBUG] 消息View树($count): $sb")
    }

    private fun holderToView(holder: Any): View? {
        if (holder is View) return holder
        return Reflect.findFieldByType(holder, View::class.java) as? View
    }

    private fun hideAvatarIn(container: View) {
        var hiddenCount = 0

        // 遍历 View 树找 ChattingAvatarImageView 或 MaskLayout
        Views.walk(container) { v ->
            val clsName = v.javaClass.name
            if (clsName == AVATAR_VIEW_CLASS || clsName == maskLayoutClass) {
                hideAvatarView(v)
                hiddenCount++
            }
            false
        }

        // avatarIV 字段兜底
        runCatching {
            val avatarIv = findAvatarImageView(container)
            if (avatarIv != null) {
                hideAvatarView(avatarIv)
                hiddenCount++
            }
        }

        if (hiddenCount > 0) {
            Logger.i("[$name] onBindView 遍历隐藏 $hiddenCount 个头像")
        }
    }

    /** 递归遍历找 avatarIV 字段的 ImageView。 */
    private fun findAvatarImageView(container: View): ImageView? {
        try {
            val v = XposedHelpers.getObjectField(container, "avatarIV")
            if (v is ImageView) return v
        } catch (_: Throwable) {}

        if (container is ViewGroup) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                try {
                    val v = XposedHelpers.getObjectField(child, "avatarIV")
                    if (v is ImageView) return v
                } catch (_: Throwable) {}
                findAvatarImageView(child)?.let { return it }
            }
        }
        return null
    }

    /** 兜底：hook MaskLayout（兼容旧版微信头像结构）。 */
    private fun hookMaskLayoutFallback(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(maskLayoutClass, classLoader)
            XposedBridge.hookAllMethods(clazz, "setMask", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ths = param.thisObject as? View ?: return
                    hideAvatarView(ths)
                }
            })
            XposedBridge.hookAllMethods(clazz, "onMeasure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ths = param.thisObject as? View ?: return
                    hideAvatarView(ths)
                }
            })
            Logger.i("[$name] 已挂载 MaskLayout 兜底 Hook")
        }.onFailure { Logger.e("[$name] MaskLayout 兜底 Hook 失败: $it") }
    }

    /** Hook 消息项 View 控制器 (viewitems 包, 持有 avatarIV 字段)。 */
    private fun hookChatItemControllerByName(className: String, classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            Logger.i("[$name] 找到消息项控制器: $className")

            // create(View): 每次消息项创建 View 时, 隐藏其中的头像
            XposedBridge.hookAllMethods(clazz, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.args[0] as? View ?: return
                        val avatarIv = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull()
                        if (avatarIv != null) {
                            hideAvatarView(avatarIv)
                        } else {
                            Views.walk(view) { v ->
                                if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                                    hideAvatarView(v)
                                }
                                false
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })

            // setChattingItem: 每次绑定消息时也处理
            XposedBridge.hookAllMethods(clazz, "setChattingItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = runCatching {
                            (param.thisObject as? Any)?.let { obj ->
                                XposedHelpers.callMethod(obj, "getMainContainerView") as? View
                            }
                        }.getOrNull() ?: return
                        val avatarIv = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull()
                        if (avatarIv != null) {
                            hideAvatarView(avatarIv)
                        } else {
                            Views.walk(view) { v ->
                                if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                                    hideAvatarView(v)
                                }
                                false
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$name] 已 Hook 消息项控制器 (create/setChattingItem)")
        }.onFailure { Logger.e("[$name] 消息项控制器 Hook 失败: $it") }
    }
}
