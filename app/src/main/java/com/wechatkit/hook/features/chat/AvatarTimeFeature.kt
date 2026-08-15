package com.wechatkit.hook.features.chat

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.wechatkit.hook.core.DexKitFinder
import com.wechatkit.hook.core.Feature
import com.wechatkit.hook.core.Logger
import com.wechatkit.hook.core.Prefs
import com.wechatkit.hook.core.Reflect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 消息时间显示（两种模式）。
 *
 * - avatar 模式：时间显示在头像正下方（把头像包进垂直容器：头像 + 时间，wrapper 保留头像 id 不破坏锚定）；
 * - message 模式：时间显示在消息（气泡）下方（注入到气泡容器末尾，按消息方向左右对齐）。
 *
 * 时间文本取自微信消息 item 自带的 timeTV（格式与微信一致）。
 */
object AvatarTimeFeature : Feature {

    override val key = "avatar_time"
    override val name = "消息时间显示"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = false

    private const val AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"

    /** 显示模式：avatar=头像下方 / message=消息下方 */
    private fun mode(): String = Prefs.getString("avatar_time_mode", "avatar")

    /** avatar -> 头像下方时间 TextView（弱引用）。 */
    private val avatarTimeViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, TextView>())

    /** avatar -> 消息下方时间 TextView（弱引用）。 */
    private val msgTimeViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, TextView>())

    private val diagCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_avatar_time", false)) return
        Logger.i("[$name] 开始 Hook (mode=${mode()})")

        runCatching {
            val g0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.g0", classLoader)

            // create(View)：首次创建消息项时注入时间视图
            XposedBridge.hookAllMethods(g0, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.args[0] as? View ?: return
                        val avatar = findAvatar(view) ?: return
                        if (mode() == "avatar") {
                            ensureTimeWrapper(avatar)
                        } else {
                            ensureMessageTimeView(view, avatar)
                        }
                        if (diagCount.getAndIncrement() < 8) {
                            Logger.i("[$name] [DIAG] create, mode=${mode()}, avatar=${avatar.javaClass.simpleName}")
                        }
                    } catch (e: Throwable) {
                        if (diagCount.getAndIncrement() < 8) Logger.e("[$name] [DIAG] create 异常: $e")
                    }
                }
            })

            // setChattingItem：每次绑定刷新时间文本（复用场景）。用 g0.avatarIV 字段拿头像，
            // convertView 拿消息条根取时间（getMainContainerView 返回的是内容子 View，不可用）。
            XposedBridge.hookAllMethods(g0, "setChattingItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val avatar = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull() ?: return
                        val itemRoot = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "convertView") as? View
                        }.getOrNull()
                        val timeText = itemRoot?.let { getTimeText(it) } ?: ""

                        if (mode() == "avatar") {
                            val tv = avatarTimeViews[avatar] ?: return
                            tv.text = timeText
                            tv.visibility = if (timeText.isEmpty()) View.GONE else View.VISIBLE
                        } else {
                            val tv = msgTimeViews[avatar] ?: return
                            tv.text = timeText
                            tv.visibility = if (timeText.isEmpty()) View.GONE else View.VISIBLE
                            tv.gravity = if (isLeftAvatar(avatar)) Gravity.START else Gravity.END
                        }
                        if (diagCount.getAndIncrement() < 8) {
                            Logger.i("[$name] [DIAG] 时间文本='$timeText'")
                        }
                    } catch (e: Throwable) {
                        if (diagCount.getAndIncrement() < 8) Logger.e("[$name] [DIAG] setChattingItem 异常: $e")
                    }
                }
            })

            Logger.i("[$name] 已挂载 g0.create/setChattingItem")
        }.onFailure { Logger.e("[$name] hook 失败: $it") }
    }

    // ============ 模式 1：头像下方 ============

    /** 把头像包进垂直容器（头像 + 时间），保留头像 id 以免破坏微信的布局锚定。 */
    private fun ensureTimeWrapper(avatar: View) {
        if (avatarTimeViews.containsKey(avatar)) return

        val parent = avatar.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(avatar)
        if (idx < 0) return
        val lp = avatar.layoutParams
        val avatarId = avatar.id

        val w = if (lp != null && lp.width > 0) lp.width else ViewGroup.LayoutParams.WRAP_CONTENT
        val h = if (lp != null && lp.height > 0) lp.height else ViewGroup.LayoutParams.WRAP_CONTENT

        parent.removeView(avatar)

        val wrapper = LinearLayout(avatar.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (avatarId != View.NO_ID) id = avatarId
        }
        wrapper.addView(avatar, LinearLayout.LayoutParams(w, h))

        val tv = createTimeTextView(avatar)
        wrapper.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        parent.addView(wrapper, idx, lp)
        avatarTimeViews[avatar] = tv
    }

    // ============ 模式 2：消息下方 ============

    /** 在气泡容器末尾注入时间 TextView（气泡下方）。 */
    private fun ensureMessageTimeView(itemRoot: View, avatar: View) {
        if (msgTimeViews.containsKey(avatar)) return
        val bubble = findBubbleContainer(itemRoot, avatar) ?: return
        val tv = createTimeTextView(avatar)
        runCatching {
            bubble.addView(tv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            msgTimeViews[avatar] = tv
        }.onFailure { }
    }

    /** 找气泡容器：item 根里不含头像、宽度最大的 ViewGroup。 */
    private fun findBubbleContainer(itemRoot: View, avatar: View): ViewGroup? {
        val root = itemRoot as? ViewGroup ?: return null
        var candidate: ViewGroup? = null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            if (containsView(child, avatar)) continue  // 头像容器跳过
            if (candidate == null || child.width > candidate.width) candidate = child
        }
        return candidate
    }

    private fun containsView(container: View, target: View): Boolean {
        if (container === target) return true
        if (container !is ViewGroup) return false
        for (i in 0 until container.childCount) {
            if (containsView(container.getChildAt(i), target)) return true
        }
        return false
    }

    // ============ 工具 ============

    private fun createTimeTextView(avatar: View): TextView = TextView(avatar.context).apply {
        text = ""
        textSize = 10f
        setTextColor(0xFF8A8A8A.toInt())
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        setPadding(0, dp2px(avatar.context, 1), 0, 0)
    }

    private fun findAvatar(root: View): View? {
        var found: View? = null
        walkViews(root) { v ->
            if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                found = v
                true
            } else false
        }
        return found
    }

    private fun walkViews(root: View, visitor: (View) -> Boolean) {
        if (visitor(root)) return
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                walkViews(root.getChildAt(i), visitor)
            }
        }
    }

    /** 从头像窗口坐标判断消息方向（左=对方，右=自己）。 */
    private fun isLeftAvatar(avatar: View): Boolean {
        if (avatar.width <= 0 || !avatar.isAttachedToWindow) return true
        val loc = IntArray(2)
        runCatching { avatar.getLocationInWindow(loc) }.onFailure { return true }
        val cx = loc[0] + avatar.width / 2f
        val screenW = avatar.context.resources.displayMetrics.widthPixels
        return cx <= screenW / 2f
    }

    /** 从消息 item 的 tag(holder) 反射微信自带的时间 TextView，取格式化好的文本。 */
    private fun getTimeText(itemRoot: View): String {
        val tag = itemRoot.tag ?: return ""
        // 诊断：dump holder 字段（找 createTime 等消息数据字段）
        if (diagCount.get() < 5) {
            val sb = StringBuilder()
            tag.javaClass.declaredFields.take(30).forEach { sb.append(it.name).append(",") }
            Logger.i("[$name] [DIAG] holder=${tag.javaClass.simpleName} 字段: $sb")
        }
        return runCatching {
            val tv = Reflect.findFieldByName(tag, "timeTV") as? TextView
            tv?.text?.toString() ?: ""
        }.getOrDefault("")
    }

    private fun dp2px(ctx: android.content.Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()
}
