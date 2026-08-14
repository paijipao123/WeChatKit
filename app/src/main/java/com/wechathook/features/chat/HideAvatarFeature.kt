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
 * 关键技巧（参考 WeKit/WAuxiliary）：
 * 微信消息项里带头像的容器是 `com.tencent.mm.ui.base.MaskLayout`（固定 52dp）。
 * 如果简单地把整行头像设成 GONE，在 RelativeLayout 布局的消息里，右侧气泡会被
 * 向左吸附导致出现一大片空隙。正确做法是：**保留 MaskLayout 但把它的宽度收紧为 0**——
 * 锚点不失效、气泡位置紧凑，视觉上如同没有头像。
 *
 * 实现（多层策略，适配 8.0.x 不同版本）：
 * 1. hook 消息 item 的 onBindView（DexKit 特征定位），after 中从 holder 反射找
 *    `avatarIV` 字段（微信 8.0.7x 消息 item 的标准头像字段名），收窄其父容器宽度；
 * 2. 遍历 View 树找 MaskLayout+ImageView 的头像容器收窄（兜底）；
 * 3. 直接 hook MaskLayout.setMask/onMeasure（最终兜底）。
 */
object HideAvatarFeature : Feature {

    override val key = "hide_avatar"
    override val name = "隐藏消息头像（紧凑）"

    /** 记忆每个 MaskLayout 的原始宽度，便于在需要恢复时还原。 */
    private val originalWidths = WeakHashMap<View, Int>()

    private val maskLayoutClass = "com.tencent.mm.ui.base.MaskLayout"

    override fun defaultEnabled() = true

    override fun isProcessSafe() = false

    private val enable: Boolean
        get() = Prefs.getBoolean("hide_avatar_enable", true)

    // 是否隐藏自己发出的消息的头像（默认只隐藏对方）
    private val hideOutgoing: Boolean
        get() = Prefs.getBoolean("hide_avatar_outgoing", false)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return
        if (finder == null) return

        Logger.i("[$name] 开始 Hook")

        val targetClassNames = finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
        if (targetClassNames.isEmpty()) {
            Logger.w("[$name] 未定位到消息 item 类，尝试按类名兜底查找。")
            val looser = finder.findClassNamesByStrings("MvvmChattingItem", "onBindView")
            if (looser.isEmpty()) {
                Logger.e("[$name] 无法定位消息 item，该功能不可用。")
                return
            }
            hookClass(looser.first(), classLoader)
        } else {
            Logger.i("[$name] 定位到 ${targetClassNames.size} 个消息 item 类: $targetClassNames")
            targetClassNames.forEach { hookClass(it, classLoader) }
        }

        hookMaskLayoutFallback(classLoader)
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
            // 第一个参数常用作消息项 holder
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
        // 从 holder 字段树里找 View 类型字段
        return Reflect.findFieldByType(holder, View::class.java) as? View
    }

    private fun hideAvatarIn(container: View) {
        var hiddenCount = 0

        // 策略 A：找 avatarIV 字段对应的 ImageView（微信标准字段名）
        runCatching {
            val avatarIv = findAvatarImageView(container)
            if (avatarIv != null) {
                hideAvatarImageView(avatarIv)
                hiddenCount++
            }
        }

        // 策略 B：遍历 View 树找 MaskLayout+ImageView 头像容器
        Views.walk(container) { v ->
            val clsName = v.javaClass.name
            if (clsName == maskLayoutClass) {
                val containsAvatar = (v as? ViewGroup)?.let { vg ->
                    var has = false
                    for (i in 0 until vg.childCount) {
                        if (vg.getChildAt(i) is ImageView) { has = true; break }
                    }
                    has
                } ?: false
                if (containsAvatar) {
                    hideMaskLayout(v)
                    hiddenCount++
                }
            }
            false
        }

        if (hiddenCount > 0) {
            Logger.i("[$name] 已隐藏 $hiddenCount 个头像")
        }
    }

    /** 递归遍历 container 的字段树，找 avatarIV 字段的 ImageView。 */
    private fun findAvatarImageView(container: View): ImageView? {
        // 直接找 container 的 avatarIV 字段
        runCatching {
            val v = XposedHelpers.getObjectField(container, "avatarIV")
            if (v is ImageView) return v
        } catch (_: Throwable) {}

        // 遍历子 View 的字段
        if (container is ViewGroup) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                runCatching {
                    val v = XposedHelpers.getObjectField(child, "avatarIV")
                    if (v is ImageView) return v
                } catch (_: Throwable) {}
                findAvatarImageView(child)?.let { return it }
            }
        }
        return null
    }

    /** 隐藏单个头像 ImageView：收窄其 MaskLayout 父容器宽度 + 隐藏自身。 */
    private fun hideAvatarImageView(avatarIv: ImageView) {
        val parent = avatarIv.parent
        if (parent is View && parent.javaClass.name == maskLayoutClass) {
            hideMaskLayout(parent)
        } else {
            avatarIv.visibility = View.GONE
            avatarIv.layoutParams?.let { lp ->
                lp.width = 0
                lp.height = 0
                avatarIv.layoutParams = lp
            }
        }
    }

    private fun hideMaskLayout(mask: View) {
        val lp = mask.layoutParams
        val orig = originalWidths[mask] ?: lp.width
        originalWidths[mask] = orig
        lp.width = 0
        mask.layoutParams = lp
        // 隐藏内部头像
        (mask as? ViewGroup)?.let { vg ->
            for (i in 0 until vg.childCount) {
                vg.getChildAt(i).visibility = View.GONE
            }
        }
    }

    /**
     * 兜底：直接 hook MaskLayout，把带头像 ImageView 的 MaskLayout 宽度设为 0。
     */
    private fun hookMaskLayoutFallback(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(maskLayoutClass, classLoader)
            // setMask / onMeasure 都挂上
            XposedBridge.hookAllMethods(clazz, "setMask", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ths = param.thisObject as? View ?: return
                    if (hasAvatarChild(ths)) hideMaskLayout(ths)
                }
            })
            XposedBridge.hookAllMethods(clazz, "onMeasure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ths = param.thisObject as? View ?: return
                    if (hasAvatarChild(ths)) {
                        val lp = ths.layoutParams
                        lp.width = 0
                        ths.layoutParams = lp
                    }
                }
            })
            Logger.i("[$name] 已挂载 MaskLayout 兜底 Hook (setMask/onMeasure)")
        }.onFailure { Logger.e("[$name] MaskLayout 兜底 Hook 失败: $it") }
    }

    private fun hasAvatarChild(v: View): Boolean {
        if (v !is ViewGroup) return false
        for (i in 0 until v.childCount) {
            if (v.getChildAt(i) is ImageView) return true
        }
        return false
    }
}
