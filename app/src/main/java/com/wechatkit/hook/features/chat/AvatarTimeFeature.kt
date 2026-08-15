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
 * 头像下显示消息发送时间。
 *
 * 在每条消息的头像正下方注入一个小的时间 TextView，显示该消息的发送时间
 * （文本取自微信消息 item 自带的 timeTV，格式与微信一致：今天 HH:mm / 昨天 / 日期）。
 *
 * 实现：
 * - hook `g0.create`：把头像包进一个垂直 LinearLayout（头像 + 时间），
 *   wrapper 保留头像的 id，不破坏微信 RelativeLayout 的气泡锚定；
 * - hook `g0.setChattingItem`（每次绑定）：刷新时间文本（RecyclerView 复用场景）。
 */
object AvatarTimeFeature : Feature {

    override val key = "avatar_time"
    override val name = "头像下显示时间"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = false

    private const val AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"

    /** avatar -> 时间 TextView（弱引用，防泄漏；RecyclerView 复用场景）。 */
    private val timeViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, TextView>())

    /** 诊断日志计数。 */
    private val diagCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_avatar_time", false)) return
        Logger.i("[$name] 开始 Hook")

        runCatching {
            val g0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.g0", classLoader)

            // create(View)：首次创建消息项时把头像包进"头像 + 时间"垂直容器
            XposedBridge.hookAllMethods(g0, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.args[0] as? View ?: return
                        val avatar = findAvatar(view)
                        if (diagCount.getAndIncrement() < 8) {
                            Logger.i("[$name] [DIAG] create 触发, view=${view.javaClass.simpleName}, avatar=${avatar?.javaClass?.simpleName}")
                        }
                        if (avatar != null) ensureTimeWrapper(avatar)
                    } catch (e: Throwable) {
                        if (diagCount.getAndIncrement() < 8) Logger.e("[$name] [DIAG] create 异常: $e")
                    }
                }
            })

            // setChattingItem：每次绑定消息时刷新时间文本（复用场景时间会变）。
            // 注意：getMainContainerView 返回的是消息内容子 View（表情/文本等），不含头像，
            // 必须直接用 g0.avatarIV 字段拿头像、convertView 拿消息条根取时间。
            XposedBridge.hookAllMethods(g0, "setChattingItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val avatar = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull()
                        val tv = avatar?.let { timeViews[it] }
                        if (diagCount.getAndIncrement() < 8) {
                            Logger.i("[$name] [DIAG] setChattingItem, avatar=${avatar?.javaClass?.simpleName}, hasTv=${tv != null}")
                        }
                        if (avatar == null || tv == null) return
                        val itemRoot = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "convertView") as? View
                        }.getOrNull()
                        val timeText = itemRoot?.let { getTimeText(it) } ?: ""
                        if (diagCount.getAndIncrement() < 8) {
                            Logger.i("[$name] [DIAG] 时间文本='$timeText'")
                        }
                        tv.text = timeText
                        tv.visibility = if (timeText.isEmpty()) View.GONE else View.VISIBLE
                    } catch (e: Throwable) {
                        if (diagCount.getAndIncrement() < 8) Logger.e("[$name] [DIAG] setChattingItem 异常: $e")
                    }
                }
            })

            Logger.i("[$name] 已挂载 g0.create/setChattingItem")
        }.onFailure { Logger.e("[$name] hook 失败: $it") }
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

    /** 把头像包进垂直容器（头像 + 时间），保留头像 id 以免破坏微信的布局锚定。 */
    private fun ensureTimeWrapper(avatar: View) {
        // 已注入过（时间视图缓存存在）则跳过
        if (timeViews.containsKey(avatar)) return

        val parent = avatar.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(avatar)
        if (idx < 0) return
        val lp = avatar.layoutParams
        val avatarId = avatar.id

        // 头像尺寸（优先用原 layoutParams，避免变形）
        val w = if (lp != null && lp.width > 0) lp.width else ViewGroup.LayoutParams.WRAP_CONTENT
        val h = if (lp != null && lp.height > 0) lp.height else ViewGroup.LayoutParams.WRAP_CONTENT

        parent.removeView(avatar)

        val wrapper = LinearLayout(avatar.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (avatarId != View.NO_ID) id = avatarId
        }
        wrapper.addView(avatar, LinearLayout.LayoutParams(w, h))

        val tv = TextView(avatar.context).apply {
            text = ""
            textSize = 10f
            setTextColor(0xFF8A8A8A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp2px(avatar.context, 1), 0, 0)
        }
        wrapper.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        parent.addView(wrapper, idx, lp)
        timeViews[avatar] = tv
    }

    /** 从消息 item 的 tag(holder) 反射微信自带的时间 TextView，取格式化好的文本。 */
    private fun getTimeText(itemRoot: View): String {
        val tag = itemRoot.tag ?: return ""
        return runCatching {
            val tv = Reflect.findFieldByName(tag, "timeTV") as? TextView
            tv?.text?.toString() ?: ""
        }.getOrDefault("")
    }

    private fun dp2px(ctx: android.content.Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()
}
