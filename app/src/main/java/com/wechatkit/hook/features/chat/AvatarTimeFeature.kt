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

    /** g0(holder) -> createTime(ms) 缓存：同一绑定对象不重复递归反射。 */
    private val timeCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Long>())

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
            // convertView 拿消息条根；时间优先取微信 timeTV，为空时从消息数据递归找 createTime。
            XposedBridge.hookAllMethods(g0, "setChattingItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val avatar = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull() ?: return
                        val itemRoot = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "convertView") as? View
                        }.getOrNull()
                        var timeText = itemRoot?.let { getTimeText(it) } ?: ""
                        if (timeText.isEmpty()) {
                            // timeTV 大多为空：从 g0 的消息数据递归找 createTime（秒/毫秒时间戳）并格式化
                            var ts = timeCache[param.thisObject] ?: 0
                            if (ts == 0L) {
                                ts = findCreateTimeMs(param.thisObject, 0, java.util.HashSet())
                                if (ts > 0) timeCache[param.thisObject] = ts
                            }
                            if (ts > 0) {
                                timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(ts))
                            }
                        }
                        if (diagCount.getAndIncrement() < 8) {
                            Logger.i("[$name] [DIAG] 时间文本='$timeText'")
                        }

                        if (mode() == "avatar") {
                            // 微信绑定数据可能重建 item 内部结构，把我们的 wrapper 移除；
                            // 若 tv 不在视图树里，重新注入
                            var tv = avatarTimeViews[avatar]
                            if (tv != null && tv.parent == null) {
                                avatarTimeViews.remove(avatar)
                                tv = null
                            }
                            if (tv == null && itemRoot != null) {
                                ensureTimeWrapper(avatar)
                                tv = avatarTimeViews[avatar]
                            }
                            val t = tv ?: return
                            t.text = timeText
                            t.visibility = if (timeText.isEmpty()) View.GONE else View.VISIBLE
                        } else {
                            // 同理：tv 不在视图树则重新注入气泡容器
                            var tv = msgTimeViews[avatar]
                            if (tv != null && tv.parent == null) {
                                msgTimeViews.remove(avatar)
                                tv = null
                            }
                            if (tv == null && itemRoot != null) {
                                ensureMessageTimeView(itemRoot, avatar)
                                tv = msgTimeViews[avatar]
                            }
                            val t = tv ?: return
                            t.text = timeText
                            t.visibility = if (timeText.isEmpty()) View.GONE else View.VISIBLE
                            t.gravity = if (isLeftAvatar(avatar)) Gravity.START else Gravity.END
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

    /** 在气泡容器下方注入时间 TextView。优先注入到气泡容器的父容器（垂直 LinearLayout
     * 时排在气泡之后）；父容器不合适则回退注入气泡容器末尾。 */
    private fun ensureMessageTimeView(itemRoot: View, avatar: View) {
        if (msgTimeViews.containsKey(avatar)) return
        val bubble = findBubbleContainer(itemRoot, avatar) ?: return
        val tv = createTimeTextView(avatar)
        val lp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        runCatching {
            val parent = bubble.parent as? ViewGroup
            val injected = if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
                // 垂直容器：插到气泡之后 = 气泡正下方
                val idx = parent.indexOfChild(bubble)
                parent.addView(tv, idx + 1, lp)
                true
            } else {
                bubble.addView(tv, lp)
                false
            }
            msgTimeViews[avatar] = tv
            if (diagCount.getAndIncrement() < 8) {
                val target = tv.parent?.javaClass?.name ?: "null"
                Logger.i("[$name] [DIAG] 注入消息时间: 气泡=${bubble.javaClass.name} 注入位置=${if (injected) "气泡下方" else "气泡内"} 父=$target")
            }
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
        // create 时布局未完成，child.width 可能全是 0：退化为"第一个不含头像的 ViewGroup"
        if (candidate == null) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) as? ViewGroup ?: continue
                if (containsView(child, avatar)) continue
                candidate = child
                break
            }
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

    /**
     * 从消息对象递归查找 createTime 时间戳：
     * - 秒级（约 1.5e9 ~ 2.5e9，当前时间 2026 年 ≈ 1.77e9）→ 乘 1000 转毫秒；
     * - 毫秒级（约 1.5e12 ~ 2.5e12）→ 直接返回。
     * 找不到返回 0。限制深度与访问数量防止卡顿。
     */
    private fun findCreateTimeMs(obj: Any?, depth: Int, visited: java.util.HashSet<Int>): Long {
        if (obj == null || depth > 5 || visited.size > 2000) return 0
        if (obj is String || obj is Number || obj is Boolean || obj is Char ||
            obj is android.graphics.drawable.Drawable || obj is View || obj is android.app.Activity
        ) return 0
        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return 0

        var clazz: Class<*>? = obj.javaClass
        var checked = 0
        while (clazz != null && clazz != Any::class.java && checked < 40) {
            for (f in clazz.declaredFields) {
                if (++checked > 600) return 0
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val t = f.type
                    if (t == java.lang.Long::class.javaPrimitiveType) {
                        val v = f.getLong(obj)
                        if (v in 1_500_000_000L..2_500_000_000L) return v * 1000
                        if (v in 1_500_000_000_000L..2_500_000_000_000L) return v
                    } else if (t == java.lang.Long::class.java) {
                        val v = f.get(obj) as? Long ?: 0
                        if (v in 1_500_000_000L..2_500_000_000L) return v * 1000
                        if (v in 1_500_000_000_000L..2_500_000_000_000L) return v
                    } else if (t == java.lang.Integer::class.javaPrimitiveType) {
                        val v = f.getInt(obj).toLong()
                        if (v in 1_500_000_000L..2_500_000_000L) return v * 1000
                    } else if (!t.isPrimitive && !t.isArray && !t.name.startsWith("java.") &&
                        !t.name.startsWith("android.") && !t.name.startsWith("kotlin.")
                    ) {
                        val child = f.get(obj) ?: continue
                        val r = findCreateTimeMs(child, depth + 1, visited)
                        if (r > 0) return r
                    }
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
        }
        return 0
    }
}
