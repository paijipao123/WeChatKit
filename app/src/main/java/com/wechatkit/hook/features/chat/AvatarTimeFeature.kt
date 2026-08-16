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
                            if (diagCount.get() < 4) Logger.i("[$name] [DIAG] 视图树: ${dumpTree(view)}")
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
                        // 从 holder(tag) 找 MsgInfo 读 field_createTime（holder 复用故不缓存）
                        val tag = itemRoot?.tag ?: param.thisObject
                        val ts = findMsgCreateTimeMs(tag)
                        val timeText = if (ts > 0) {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(ts))
                        } else ""
                        if (diagCount.getAndIncrement() < 12) {
                            Logger.i("[$name] [DIAG] 时间文本='$timeText'")
                            if (diagCount.get() < 4) {
                                Logger.i("[$name] [DIAG] holder 详情: ${dumpHolder(tag)}")
                                Logger.i("[$name] [DIAG] g0 控制器详情: ${dumpController(param.thisObject)}")
                            }
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

    /** dump g0 消息项控制器实例：找 MsgInfo(dm.*) 引用。 */
    private fun dumpController(g0: Any): String {
        val sb = StringBuilder(g0.javaClass.name)
        try {
            var clazz: Class<*>? = g0.javaClass
            var count = 0
            while (clazz != null && clazz != Any::class.java && count < 60) {
                for (f in clazz.declaredFields) {
                    if (++count > 60) break
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(g0)
                        val desc = when (v) {
                            null -> "null"
                            is String -> "Str(${v.take(8)})"
                            is Number -> "${v.javaClass.simpleName}($v)"
                            is View -> "V:${v.javaClass.simpleName}"
                            else -> v.javaClass.name.substringAfterLast('.')
                        }
                        sb.append(f.name).append(":").append(f.type.simpleName).append("=").append(desc)
                        if (v != null && v !is String && v !is Number && v !is View) {
                            val ct = readCreateTimeSec(v)
                            if (ct > 0) sb.append("[createTime=").append(ct).append("]")
                        }
                        sb.append(" | ")
                    } catch (_: Throwable) {}
                }
                clazz = clazz.superclass
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }

    /** dump holder 所有字段类型和值摘要，并尝试对每个对象字段读 field_createTime。 */
    private fun dumpHolder(tag: Any): String {
        val sb = StringBuilder("${tag.javaClass.name} ")
        try {
            var clazz: Class<*>? = tag.javaClass
            var count = 0
            while (clazz != null && clazz != Any::class.java && count < 50) {
                for (f in clazz.declaredFields) {
                    if (++count > 50) break
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(tag)
                        val desc = when (v) {
                            null -> "null"
                            is String -> "Str(${v.take(8)})"
                            is Number -> "${v.javaClass.simpleName}($v)"
                            is View -> "V:${v.javaClass.simpleName}"
                            else -> v.javaClass.name.substringAfterLast('.')
                        }
                        sb.append(f.name).append(":").append(f.type.simpleName).append("=").append(desc)
                        // 对象字段：尝试直接读 field_createTime
                        if (v != null && v !is String && v !is Number && v !is View) {
                            val ct = readCreateTimeSec(v)
                            if (ct > 0) sb.append("[createTime=").append(ct).append("]")
                        }
                        sb.append(" | ")
                    } catch (_: Throwable) {}
                }
                clazz = clazz.superclass
            }
        } catch (_: Throwable) {}
        // dump holder 所有无参方法(含父类)的返回值类名(找 getMsgInfo 类方法)
        runCatching {
            sb.append("\n[methods] ")
            var cm: Class<*>? = tag.javaClass
            var nm = 0
            while (cm != null && cm != Any::class.java && nm < 60) {
                for (fm in cm.declaredMethods) {
                    if (++nm > 60) break
                    if (java.lang.reflect.Modifier.isStatic(fm.modifiers)) continue
                    if (fm.parameterCount != 0) continue
                    try {
                        fm.isAccessible = true
                        val rv = fm.invoke(tag)
                        val rd = rv?.javaClass?.name?.substringAfterLast('.') ?: "null"
                        sb.append(fm.name).append("->").append(rd).append(" | ")
                    } catch (_: Throwable) {}
                }
                cm = cm.superclass
            }
        }
        // dump j:y 和 l:ArrayList 实例内容
        runCatching {
            var cl: Class<in Any>? = tag.javaClass
            while (cl != null && cl != Any::class.java) {
                val clazz = cl
                for (f in clazz.declaredFields) {
                    if (f.name != "j" && f.name != "l") continue
                    f.isAccessible = true
                    val v = f.get(tag) ?: continue
                    when (v) {
                        is java.util.List<*> -> {
                            sb.append("\n[list ").append(f.name).append("] size=").append(v.size)
                            if (v.size > 0) {
                                val it = v[0]
                                sb.append(" 首项=").append(it?.javaClass?.name).append(": ")
                                if (it != null && it !is String && it !is Number) {
                                    var c4: Class<*>? = it.javaClass
                                    var n4 = 0
                                    while (c4 != null && c4 != Any::class.java && n4 < 30) {
                                        for (f4 in c4.declaredFields) {
                                            if (++n4 > 30) break
                                            if (java.lang.reflect.Modifier.isStatic(f4.modifiers)) continue
                                            try {
                                                f4.isAccessible = true
                                                val v4 = f4.get(it)
                                                val d4 = when (v4) {
                                                    null -> "null"
                                                    is String -> "Str(${v4.take(10)})"
                                                    is Number -> "${v4.javaClass.simpleName}($v4)"
                                                    else -> v4.javaClass.simpleName
                                                }
                                                sb.append(f4.name).append(":").append(f4.type.simpleName).append("=").append(d4).append(" | ")
                                            } catch (_: Throwable) {}
                                        }
                                        c4 = c4.superclass
                                    }
                                }
                            }
                        }
                        else -> {
                            sb.append("\n[").append(f.name).append("] ").append(v.javaClass.name).append(": ")
                            var c5: Class<*>? = v.javaClass
                            var n5 = 0
                            while (c5 != null && c5 != Any::class.java && n5 < 30) {
                                for (f5 in c5.declaredFields) {
                                    if (++n5 > 30) break
                                    if (java.lang.reflect.Modifier.isStatic(f5.modifiers)) continue
                                    try {
                                        f5.isAccessible = true
                                        val v5 = f5.get(v)
                                        val d5 = when (v5) {
                                            null -> "null"
                                            is String -> "Str(${v5.take(10)})"
                                            is Number -> "${v5.javaClass.simpleName}($v5)"
                                            else -> v5.javaClass.simpleName
                                        }
                                        sb.append(f5.name).append(":").append(f5.type.simpleName).append("=").append(d5).append(" | ")
                                    } catch (_: Throwable) {}
                                }
                                c5 = c5.superclass
                            }
                        }
                    }
                }
                cl = clazz.superclass
            }
        }
        // 深度 dump: chatHolder 与 chattingItem 实例的完整字段值(找时间戳)
        runCatching {
            var cl: Class<*>? = tag.javaClass
            while (cl != null && cl != Any::class.java) {
                val clazz: Class<*> = cl
                for (f in clazz.declaredFields) {
                    if (f.name != "chatHolder" && f.name != "chattingItem" && f.name != "quoteView") continue
                    f.isAccessible = true
                    val v = f.get(tag) ?: continue
                    if (v is String || v is Number || v is View) continue
                    sb.append("\n[").append(f.name).append("] ").append(v.javaClass.name).append(": ")
                    var c3: Class<*>? = v.javaClass
                    var n3 = 0
                    while (c3 != null && c3 != Any::class.java && n3 < 40) {
                        val c3c: Class<*> = c3
                        for (f3 in c3c.declaredFields) {
                            if (++n3 > 40) break
                            if (java.lang.reflect.Modifier.isStatic(f3.modifiers)) continue
                            try {
                                f3.isAccessible = true
                                val v3 = f3.get(v)
                                val d3 = when (v3) {
                                    null -> "null"
                                    is String -> "Str(${v3.take(10)})"
                                    is Number -> "${v3.javaClass.simpleName}($v3)"
                                    else -> v3.javaClass.simpleName
                                }
                                sb.append(f3.name).append(":").append(f3.type.simpleName).append("=").append(d3).append(" | ")
                            } catch (_: Throwable) {}
                        }
                        c3 = c3c.superclass
                    }
                }
                cl = clazz.superclass
            }
        }
        return sb.toString()
    }

    /** dump 视图树（前两层，含 LinearLayout 方向），用于定位气泡容器。 */
    private fun dumpTree(root: View): String {
        val sb = StringBuilder()
        fun dump(v: View, depth: Int) {
            if (depth > 2 || sb.length > 400) return
            sb.append("  ".repeat(depth))
            sb.append(v.javaClass.simpleName)
            if (v is LinearLayout) sb.append(if (v.orientation == LinearLayout.VERTICAL) "(V)" else "(H)")
            if (v.id != View.NO_ID) sb.append("#").append(java.lang.Integer.toHexString(v.id))
            sb.append("\n")
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (sb.length > 400) return
                    dump(v.getChildAt(i), depth + 1)
                }
            }
        }
        dump(root, 0)
        return sb.toString()
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

    /** 从消息 item 的 tag(holder) 递归找 MsgInfo 对象（类声明了 field_createTime 长整型字段），
     * 读 field_createTime 得秒级时间戳。参考 WeKit getMsgInfoFromTag 的思路：优先调
     * "无参且返回 MsgInfo" 的方法，否则遍历字段找。 */
    private fun findMsgCreateTimeMs(tag: Any): Long {
        // 1) holder 上无参方法返回 MsgInfo
        runCatching {
            for (m in tag.javaClass.declaredMethods) {
                if (m.parameterCount != 0) continue
                val ret = m.returnType
                if (!isMsgInfoClass(ret)) continue
                m.isAccessible = true
                val mi = m.invoke(tag) ?: continue
                val t = readCreateTimeSec(mi)
                if (t > 0) return t * 1000
            }
        }
        // 2) 递归遍历字段找 MsgInfo
        return findMsgInfoInFields(tag, 0, java.util.HashSet())
    }

    private fun isMsgInfoClass(c: Class<*>): Boolean {
        var cl: Class<*>? = c
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (f.name == "field_createTime" && f.type == java.lang.Long.TYPE) return true
            }
            cl = cl.superclass
        }
        return false
    }

    private fun readCreateTimeSec(mi: Any): Long = runCatching {
        var cl: Class<*>? = mi.javaClass
        while (cl != null && cl != Any::class.java) {
            for (f in cl.declaredFields) {
                if (f.name == "field_createTime" && f.type == java.lang.Long.TYPE) {
                    f.isAccessible = true
                    val v = f.getLong(mi)
                    if (v in 1_000_000_000L..2_500_000_000L) return v
                    if (v in 1_000_000_000_000L..2_500_000_000_000L) return v / 1000
                }
            }
            cl = cl.superclass
        }
        0L
    }.getOrDefault(0L)

    private fun findMsgInfoInFields(obj: Any?, depth: Int, visited: java.util.HashSet<Int>): Long {
        if (obj == null || depth > 4 || visited.size > 800) return 0
        if (obj is String || obj is Number || obj is Boolean || obj is Char ||
            obj is android.graphics.drawable.Drawable || obj is View || obj is android.app.Activity
        ) return 0
        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return 0

        var clazz: Class<*>? = obj.javaClass
        var checked = 0
        while (clazz != null && clazz != Any::class.java && checked < 40) {
            for (f in clazz.declaredFields) {
                if (++checked > 400) return 0
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val t = f.type
                    if (t.isPrimitive || t.isArray || t.name.startsWith("java.") ||
                        t.name.startsWith("android.") || t.name.startsWith("kotlin.")
                    ) continue
                    val child = f.get(obj) ?: continue
                    if (isMsgInfoClass(child.javaClass)) {
                        val s = readCreateTimeSec(child)
                        if (s > 0) return s * 1000
                    }
                    val r = findMsgInfoInFields(child, depth + 1, visited)
                    if (r > 0) return r
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
        }
        return 0
    }

    private fun dp2px(ctx: android.content.Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()
}
