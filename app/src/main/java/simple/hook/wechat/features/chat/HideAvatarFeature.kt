package simple.hook.wechat.features.chat

import android.view.View
import android.view.ViewGroup
import simple.hook.wechat.core.DexKitFinder
import simple.hook.wechat.core.Feature
import simple.hook.wechat.core.Logger
import simple.hook.wechat.core.Views
import simple.hook.wechat.core.Reflect
import simple.hook.wechat.core.Prefs
import simple.hook.wechat.core.SymbolResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * 隐藏聊天消息头像（采用 WeKit 的 MaskLayout 零宽方案）。
 *
 * ## 核心原理（移植自 WeKit `HideMessagesAvatars`）
 *
 * 微信消息头像被包在 `com.tencent.mm.ui.base.MaskLayout`（52dp 固定尺寸）里。
 * 在 **RelativeLayout 风格的消息行**（文本 / 图片 / 语音）中，气泡等兄弟 View 通过
 * `layout_toRightOf` / `toLeftOf` **锚定在这个 MaskLayout 上**。
 *
 * 因此直接 `GONE` 头像或 MaskLayout 会让锚点塌陷到边界，**整条气泡横向位移**——
 * 这正是"隐藏头像后气泡错位"的根因。
 *
 * WeKit 的解法：**保持 MaskLayout 可见，但把宽度收为 0**。
 * 锚点依然有效（View 未 GONE），但头像占位消失，气泡自然贴边。
 * 在 LinearLayout 风格的行（如收到的视频）里，这与 GONE 表现一致。
 *
 * ## 关键细节
 *
 * 1. **微信每次 bind 都会重置头像的 visibility，但不会重置宽度**。
 *    所以必须记住原始宽度，并在"这一行应显示头像"时恢复，否则
 *    RecyclerView 复用同一 View 会让本该显示头像的行也变成 0 宽。
 * 2. 用 `WeakHashMap` 以 MaskLayout 自身为 key 记忆原始宽度，随 View 回收自动释放。
 * 3. 方向由消息自身的 `isSend` 决定，在 bind 阶段即可拿到，
 *    无需 onLayout/onDraw 按坐标反复探测，更稳定也更省性能。
 *
 * ## 兜底
 *
 * 部分版本/消息类型（如自己发出的视频）头像不是 MaskLayout，而是消息行的直接子 View。
 * 此时直接 `GONE`——该行是 LinearLayout，GONE 会正确塌陷。
 */
object HideAvatarFeature : Feature {

    override val key = "hide_avatar"
    override val name = "隐藏消息头像（紧凑）"

    /** 微信 8.0.7x 消息头像类（固定名）。 */
    private const val AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"

    /** 部分界面使用的另一个头像类。 */
    private const val AVATAR_VIEW_CLASS2 = "com.tencent.mm.ui.chatting.view.AvatarImageView"

    /** 头像包装容器：锚点所在，必须保留 View 而只把宽度归零。 */
    private const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"

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

    /**
     * 每个 MaskLayout 的原始宽度。key 为 MaskLayout 自身。
     *
     * 微信每次 bind 会把头像 visibility 重置为 VISIBLE，但**不会**重置我们改过的宽度；
     * 因此必须自己记住原值，才能在"这一行应显示头像"时恢复。
     */
    private val originalMaskWidths = WeakHashMap<View, Int>()

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        val m = mode()
        if (m == "off" && itemSpacing <= 0) return

        Logger.i("[$name] 开始 Hook (mode=$m, spacing=$itemSpacing dp)")
        SymbolResolver.detectWechatVersion()
        Logger.i("[$name] 微信版本: ${SymbolResolver.wechatVersionName}")

        // ---- 主入口：消息绑定点 ----
        // 微信把绑定方法名混淆了（类 ve5.g 无 onBindView 方法），必须按特征字符串
        // 定位方法本身再 hook，不能"类名+onBindView"。
        var bound = false
        if (finder != null) {
            val bindMethods = finder.findMethodsByStrings(
                classLoader,
                strings = arrayOf("MicroMsg.MvvmChattingItem", "[onBindView]")
            )
            if (bindMethods.isNotEmpty()) {
                bindMethods.take(3).forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    handleBind(param)
                                } catch (t: Throwable) {
                                    Logger.w("[$name] bind 处理异常: $t")
                                }
                            }
                        })
                        Logger.i("[$name] 已 Hook 消息绑定方法: ${method.declaringClass.name}#${method.name}")
                        bound = true
                    }.onFailure { Logger.e("[$name] 消息绑定方法 Hook 失败: $it") }
                }
            }
        }

        // 兜底：类名 + onBindView
        if (!bound && finder != null) {
            val classNames = finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
                .ifEmpty { finder.findClassNamesByStrings("MvvmChattingItem", "onBindView") }
            classNames.forEach { hookBindByName(it, classLoader) }
        }
        if (!bound) {
            Logger.w("[$name] 未能通过特征定位绑定方法，将依赖头像类兜底")
        }

        // ---- 兜底：头像自身的可见性变化 ----
        // 微信重新 bind 时会把它置回 VISIBLE，这里按模式压回。
        // 这条兜底拿不到消息方向，因此只在 mode=all 时生效，
        // 避免把本该显示的头像误隐藏。
        hookAvatarVisibilityFallback(classLoader)

        Logger.i("[$name] Hook 完成")
    }

    // =========================================================================
    // 消息绑定处理
    // =========================================================================

    /**
     * 消息 bind 回调：找到该行的头像，按方向决定隐藏或恢复，并应用消息间距。
     *
     * 与 WeKit 一致：方向来自消息自身的发送标记，而不是靠坐标猜左右。
     */
    private fun handleBind(param: XC_MethodHook.MethodHookParam) {
        val holder = param.args.getOrNull(0) ?: return
        val row = holderToView(holder) ?: return

        applyItemSpacing(row)

        val m = mode()
        if (m == "off") return

        val avatar = findAvatarIn(row) ?: return
        if (shouldHide(holder, avatar, m)) hideAvatar(avatar) else restoreAvatar(avatar)
    }

    /**
     * 判断该行头像是否应隐藏。
     *
     * 优先用消息的发送标记判断方向；取不到时退回"头像在行内左/右半区"的几何判断。
     * 方向未知时**保持显示**，避免误隐藏。
     */
    private fun shouldHide(holder: Any, avatar: View, mode: String): Boolean {
        if (mode == "all") return true

        val isSelf = isSelfSender(holder, avatar) ?: return false
        return when (mode) {
            "incoming" -> !isSelf   // 只隐藏对方
            "outgoing" -> isSelf    // 只隐藏自己
            else -> false
        }
    }

    /**
     * 判断消息是否为自己发送。
     *
     * 依次尝试：
     * 1. holder 上常见的方向字段（isSend / isSelf 等布尔）；
     * 2. holder 内嵌消息数据对象上的同名字段；
     * 3. 几何兜底：自己在右、对方在左（头像中心相对消息行的水平位置）。
     *
     * @return true=自己发送，false=对方发送，null=无法判断
     */
    private fun isSelfSender(holder: Any, avatar: View): Boolean? {
        // 1 & 2: 反射找布尔方向字段
        readSendFlag(holder)?.let { return it }
        val data = runCatching { Reflect.findFieldByType(holder, Any::class.java) }.getOrNull()
        if (data != null && data !== holder) {
            readSendFlag(data)?.let { return it }
        }

        // 3: 几何兜底——头像中心落在消息行右半区 = 自己
        val row = findRowContainer(avatar) ?: return null
        if (row.width <= 0 || avatar.width <= 0) return null

        val rowLoc = IntArray(2)
        val avLoc = IntArray(2)
        runCatching {
            row.getLocationInWindow(rowLoc)
            avatar.getLocationInWindow(avLoc)
        }.onFailure { return null }

        val avCenterX = avLoc[0] + avatar.width / 2f
        return (avCenterX - rowLoc[0]) > row.width / 2f
    }

    /** 在对象上找"是否自己发送"的布尔字段。 */
    private fun readSendFlag(obj: Any): Boolean? {
        val fields = runCatching { obj.javaClass.declaredFields }.getOrNull() ?: return null
        for (f in fields) {
            if (f.type != Boolean::class.javaPrimitiveType && f.type != java.lang.Boolean::class.java) continue
            val n = f.name.lowercase()
            if (n == "issend" || n == "isself" || n == "send" || n.endsWith("issend")) {
                runCatching {
                    f.isAccessible = true
                    return f.getBoolean(obj)
                }
            }
        }
        return null
    }

    // =========================================================================
    // 隐藏 / 恢复（WeKit 的零宽 MaskLayout 核心）
    // =========================================================================

    /**
     * 隐藏头像。
     *
     * 若头像被 MaskLayout 包裹（RelativeLayout 行的锚点）：
     *   **保持 MaskLayout 可见，只把宽度收为 0**，锚点不塌陷，气泡不错位。
     * 否则头像就是消息行的直接子 View（LinearLayout 行）：
     *   直接 GONE，LinearLayout 会正确塌陷。
     */
    private fun hideAvatar(avatar: View) {
        val parent = avatar.parent as? ViewGroup

        if (parent != null && parent.javaClass.name == MASK_LAYOUT_CLASS) {
            val lp = parent.layoutParams
            if (lp != null && lp.width != 0) {
                // 记住原始宽度（仅首次），供恢复使用
                if (!originalMaskWidths.containsKey(parent)) {
                    originalMaskWidths[parent] = lp.width
                }
                lp.width = 0
                parent.layoutParams = lp
            }
            // 头像本身也 GONE；MaskLayout 保持 VISIBLE 以维持锚点
            if (avatar.visibility != View.GONE) avatar.visibility = View.GONE
        } else {
            // LinearLayout 行：GONE 会正确塌陷，微信下次 bind 会自行恢复 visibility
            if (avatar.visibility != View.GONE) avatar.visibility = View.GONE
        }
    }

    /**
     * 恢复头像显示。
     *
     * 只需还原被我们改过的 MaskLayout 宽度；头像 visibility 由微信自己管理——
     * 微信每次 bind 都会重置它。
     */
    private fun restoreAvatar(avatar: View) {
        val parent = avatar.parent as? ViewGroup ?: return
        if (parent.javaClass.name != MASK_LAYOUT_CLASS) return

        val orig = originalMaskWidths.remove(parent) ?: return
        val lp = parent.layoutParams ?: return
        if (lp.width != orig) {
            lp.width = orig
            parent.layoutParams = lp
        }
    }

    // =========================================================================
    // 兜底
    // =========================================================================

    /**
     * 兜底：hook 头像 View 的可见性变化。
     *
     * 微信重新 bind 时会把头像设回 VISIBLE（但不重置宽度，所以宽度方案本身不受影响）。
     * 仅在 mode=all 时压回——这里拿不到消息方向，
     * 对 incoming/outgoing 只能依赖 bind 钩子。
     */
    private fun hookAvatarVisibilityFallback(classLoader: ClassLoader) {
        for (name in listOf(AVATAR_VIEW_CLASS, AVATAR_VIEW_CLASS2)) {
            runCatching {
                val clazz = XposedHelpers.findClass(name, classLoader)
                XposedBridge.hookAllMethods(clazz, "onVisibilityChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (mode() != "all") return
                            val v = param.thisObject as? View ?: return
                            if (v.visibility == View.VISIBLE) hideAvatar(v)
                        } catch (_: Throwable) {}
                    }
                })
                Logger.i("[$name] 已挂载头像可见性兜底: $name")
            }.onFailure { /* 该版本可能没有这个类，忽略 */ }
        }
    }

    private fun hookBindByName(className: String, classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            XposedBridge.hookAllMethods(clazz, "onBindView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        handleBind(param)
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("[$name] 已 Hook $className#onBindView")
        }.onFailure { Logger.e("[$name] Hook $className 失败: $it") }
    }

    // =========================================================================
    // View 工具
    // =========================================================================

    private fun holderToView(holder: Any): View? {
        if (holder is View) return holder
        return Reflect.findFieldByType(holder, View::class.java) as? View
    }

    /** 在消息行里找头像：先按已知类名，再用 avatarIV 字段兜底。 */
    private fun findAvatarIn(row: View): View? {
        var found: View? = null
        Views.walk(row) { v ->
            val n = v.javaClass.name
            if (n == AVATAR_VIEW_CLASS || n == AVATAR_VIEW_CLASS2) {
                found = v
                true
            } else false
        }
        if (found != null) return found

        // 字段兜底：行内名为 avatarIV 的 View
        runCatching {
            val v = XposedHelpers.getObjectField(row, "avatarIV")
            if (v is View) return v
        }
        return findAvatarFieldRecursive(row)
    }

    private fun findAvatarFieldRecursive(root: View): View? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                try {
                    val v = XposedHelpers.getObjectField(child, "avatarIV")
                    if (v is View) return v
                } catch (_: Throwable) {}
                findAvatarFieldRecursive(child)?.let { return it }
            }
        }
        return null
    }

    /** 向上找消息行根（RecyclerView/ListView 的直接子 View）。 */
    private fun findRowContainer(v: View): View? {
        var cur: android.view.ViewParent? = v.parent
        var guard = 0
        while (cur is View && guard < 10) {
            val curView = cur as View
            val pp = curView.parent
            if (pp is androidx.recyclerview.widget.RecyclerView || pp is android.widget.ListView) return curView
            cur = pp
            guard++
        }
        return null
    }

    /** 应用消息上下间距（值不变时不重设，避免无谓重布局）。 */
    private fun applyItemSpacing(row: View) {
        val sp = itemSpacing
        if (sp <= 0) return
        runCatching {
            val lp = row.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val px = (sp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
                if (lp.topMargin != px || lp.bottomMargin != px) {
                    lp.topMargin = px
                    lp.bottomMargin = px
                    row.layoutParams = lp
                }
            }
        }
    }
}
