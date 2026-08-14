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

    private val maskLayoutClass = "com.tencent.mm.ui.base.MaskLayout"

    /** 已隐藏的 View（避免重复处理）。 */
    private val hiddenViews = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun defaultEnabled() = true

    override fun isProcessSafe() = false

    private val enable: Boolean
        get() = Prefs.getBoolean("hide_avatar_enable", true)

    // 是否隐藏自己发出的消息的头像（默认只隐藏对方）
    private val hideOutgoing: Boolean
        get() = Prefs.getBoolean("hide_avatar_outgoing", false)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return

        Logger.i("[$name] 开始 Hook")

        // ---- 主策略：直接 hook ChattingAvatarImageView ----
        hookAvatarViewClass(classLoader)

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

    /** 直接 hook 微信头像 View 类。 */
    private fun hookAvatarViewClass(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(AVATAR_VIEW_CLASS, classLoader)
            Logger.i("[$name] 找到头像类: $AVATAR_VIEW_CLASS")

            // onMeasure: 收为 0 尺寸
            XposedBridge.hookAllMethods(clazz, "onMeasure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        hideAvatarView(v)
                    } catch (_: Throwable) {}
                }
            })

            // onDraw/onAttachedToWindow: 兜底 GONE
            XposedBridge.hookAllMethods(clazz, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        hideAvatarView(v)
                    } catch (_: Throwable) {}
                }
            })

            // setImageBitmap / setVisibility 等绑定头像时也处理
            XposedBridge.hookAllMethods(clazz, "setVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (v.visibility == View.VISIBLE) hideAvatarView(v)
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$name] 已 Hook 头像类 (onMeasure/onAttachedToWindow/setVisibility)")
        }.onFailure { Logger.e("[$name] 头像类 Hook 失败: $it") }
    }

    /** 隐藏单个头像 View：收窄自身 + 父容器宽度，保持气泡锚点紧凑。 */
    private fun hideAvatarView(v: View) {
        if (hiddenViews.contains(v)) return
        hiddenViews.add(v)

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
            hideAvatarIn(holderView)
        } catch (t: Throwable) {
            Logger.e("[$name] applyHide 异常: $t")
        }
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
}
