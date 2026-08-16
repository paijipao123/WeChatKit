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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 消息时间显示（两种模式）。参考 WeKit MessageTimeEnhancements 方案：
 *
 * - hook 消息绑定方法 onBindView（特征字符串 "MicroMsg.MvvmChattingItem"+"[onBindView]"）；
 * - args[0] = holder（含 timeTV/avatarIV/convertView 字段）；
 * - args[2] = msgId；thisObject 上取 ChattingDataAdapter 字段，getItem(msgId) 得 MsgInfo；
 * - MsgInfo.field_createTime（秒）即发送时间；
 * - message 模式：直接改微信自带 timeTV（居中时间条）文本并强制可见；
 * - avatar 模式：把头像包进垂直容器，头像下方注入时间 TextView。
 */
object AvatarTimeFeature : Feature {

    override val key = "avatar_time"
    override val name = "消息时间显示"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = true

    private const val AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"

    /** 显示模式：avatar=头像下方 / message=消息下方(微信时间条) */
    private fun mode(): String = Prefs.getString("avatar_time_mode", "avatar")

    /** avatar -> 头像下方时间 TextView（弱引用）。 */
    private val avatarTimeViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, TextView>())

    private val diagCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_avatar_time", false)) return
        Logger.i("[$name] 开始 Hook (mode=${mode()})")
        if (finder == null) return

        // hook onBindView（WeKit 同款：特征字符串定位）
        val methods = runCatching {
            finder.findMethodsByStrings(
                classLoader,
                onlyPackages = listOf("com.tencent.mm"),
                strings = arrayOf("MicroMsg.MvvmChattingItem", "[onBindView]")
            )
        }.getOrDefault(emptyList())
        if (methods.isEmpty()) {
            Logger.e("[$name] 未找到 onBindView 方法")
            return
        }
        methods.take(3).forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            handleOnBindView(param, classLoader)
                        } catch (t: Throwable) {
                            if (diagCount.getAndIncrement() < 8) Logger.e("[$name] onBindView 处理异常: $t")
                        }
                    }
                })
                Logger.i("[$name] 已 Hook onBindView: ${method.declaringClass.name}#${method.name}")
            }.onFailure { Logger.e("[$name] onBindView Hook 失败: $it") }
        }
    }

    // ============ 核心：onBindView 处理 ============

    private fun handleOnBindView(param: XC_MethodHook.MethodHookParam, classLoader: ClassLoader) {
        val holder = param.args.getOrNull(0) ?: return

        // 1) 从 thisObject 找 ChattingDataAdapter，getItem(msgId) 拿 MsgInfo
        val adapterCls = runCatching {
            classLoader.loadClass("com.tencent.mm.ui.chatting.adapter.ChattingDataAdapter")
        }.getOrNull()
        val msgId = (param.args.getOrNull(2) as? Int) ?: -1
        val msgInfo = adapterCls?.let { cls ->
            val adapter = findFieldOfType(param.thisObject, cls)
            adapter?.let { a ->
                runCatching {
                    val m = a.javaClass.methods.firstOrNull {
                        it.name == "getItem" && it.parameterTypes.size == 1
                    }
                    m?.invoke(a, msgId)
                }.getOrNull()
            }
        }
        val createTimeSec = msgInfo?.let { readCreateTimeSec(it) } ?: 0L

        if (diagCount.getAndIncrement() < 8) {
            Logger.i("[$name] [DIAG] msgId=$msgId msgInfo=${msgInfo?.javaClass?.name} createTime=$createTimeSec")
        }
        if (createTimeSec <= 0) return

        val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(createTimeSec * 1000))

        if (mode() == "message") {
            // 直接用微信自带时间条 timeTV
            applyTimeTV(holder, timeText)
        } else {
            // 头像下方注入
            val holderView = findFieldOfType(holder, View::class.java) as? View ?: return
            ensureAvatarTime(holderView, timeText)
        }
    }

    /** message 模式：改微信自带 timeTV。 */
    private fun applyTimeTV(holder: Any, timeText: String) {
        val tv = findFieldByNameInHierarchy(holder, "timeTV") as? TextView ?: return
        tv.text = timeText
        tv.visibility = View.VISIBLE
        // 微信可能在后续把时间条隐藏，post 一次兜底
        tv.post { tv.visibility = View.VISIBLE }
        if (diagCount.get() < 8) Logger.i("[$name] [DIAG] timeTV -> '$timeText' visible")
    }

    /** avatar 模式：头像下方注入时间 TextView。 */
    private fun ensureAvatarTime(holderView: View, timeText: String) {
        var avatar: View? = null
        walk(holderView) { v ->
            if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                avatar = v
                true
            } else false
        }
        val av = avatar ?: return
        var tv = avatarTimeViews[av]
        if (tv == null || tv.parent == null) {
            tv = injectTimeBelowAvatar(av) ?: return
            avatarTimeViews[av] = tv
        }
        tv.text = timeText
        tv.visibility = View.VISIBLE
    }

    /** 把头像包进垂直容器(头像+时间)。 */
    private fun injectTimeBelowAvatar(avatar: View): TextView? {
        val parent = avatar.parent as? ViewGroup ?: return null
        val idx = parent.indexOfChild(avatar)
        if (idx < 0) return null
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

        val tv = TextView(avatar.context).apply {
            text = ""
            textSize = 10f
            setTextColor(0xFF8A8A8A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 2, 0, 0)
        }
        wrapper.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        runCatching { parent.addView(wrapper, idx, lp) }.onFailure { return null }
        return tv
    }

    // ============ 反射工具 ============

    private fun walk(root: View, visitor: (View) -> Boolean) {
        if (visitor(root)) return
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) walk(root.getChildAt(i), visitor)
        }
    }

    /** 在对象(含父类)上找指定名字的字段值。 */
    private fun findFieldByNameInHierarchy(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.name != name) continue
                return runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /** 在对象(含父类)上找指定类型的字段值。 */
    private fun findFieldOfType(obj: Any, type: Class<*>): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (!type.isAssignableFrom(f.type)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                if (v != null) return v
            }
            c = c.superclass
        }
        return null
    }

    /** 读 MsgInfo 的 field_createTime（秒）。 */
    private fun readCreateTimeSec(mi: Any): Long = runCatching {
        var c: Class<*>? = mi.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.name == "field_createTime" && f.type == java.lang.Long.TYPE) {
                    f.isAccessible = true
                    val v = f.getLong(mi)
                    if (v in 1_000_000_000L..2_500_000_000L) return v
                    if (v in 1_000_000_000_000L..2_500_000_000_000L) return v / 1000
                }
            }
            c = c.superclass
        }
        0L
    }.getOrDefault(0L)
}
