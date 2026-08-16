package simple.hook.wechat.features.chat

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import simple.hook.wechat.core.DexKitFinder
import simple.hook.wechat.core.Feature
import simple.hook.wechat.core.Logger
import simple.hook.wechat.core.Prefs
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

    /** item 根 -> 气泡下方时间 TextView（弱引用）。 */
    private val msgTimeViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, TextView>())

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
            Logger.e("[$name] 未找到 onBindView 方法(字符串定位失败), 走类名 fallback")
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

        // fallback: 类名+onBindView（与 HideAvatarFeature 同款）
        if (methods.isEmpty()) {
            val clsNames = runCatching {
                finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
            }.getOrDefault(emptyList())
            val target = clsNames.firstOrNull()
                ?: runCatching { finder.findClassNamesByStrings("MvvmChattingItem", "onBindView").firstOrNull() }.getOrNull()
            if (target != null) {
                runCatching {
                    val clazz = classLoader.loadClass(target)
                    XposedBridge.hookAllMethods(clazz, "onBindView", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                handleOnBindView(param, classLoader)
                            } catch (t: Throwable) {
                                if (diagCount.getAndIncrement() < 8) Logger.e("[$name] onBindView 处理异常: $t")
                            }
                        }
                    })
                    Logger.i("[$name] 已 Hook $target#onBindView (fallback)")
                }.onFailure { Logger.e("[$name] fallback Hook $target 失败: $it") }
            } else {
                Logger.e("[$name] fallback 也未找到 onBindView 类")
            }
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
            // WA 式：保留微信原生 timeTV 不动，在气泡下方注入无背景时间 TextView
            val holderView = findFieldByNameInHierarchy(holder, "convertView") as? View ?: return
            ensureMsgTimeBelowBubble(holderView, timeText)
        } else {
            // 头像下方注入
            val holderView = findFieldByNameInHierarchy(holder, "convertView") as? View ?: return
            ensureAvatarTime(holderView, timeText)
        }
    }

    /** message 模式（WA 式）：气泡下方无背景时间。item 根是 RelativeLayout。 */
    private fun ensureMsgTimeBelowBubble(holderView: View, timeText: String) {
        val root = holderView as? ViewGroup ?: return
        var avatar: View? = null
        walk(root) { v ->
            if (v.javaClass.name == AVATAR_VIEW_CLASS) { avatar = v; true } else false
        }
        val bubble = findBubbleContainer(root, avatar)

        var tv = msgTimeViews[root]
        if (tv == null || tv.parent == null) {
            tv = TextView(root.context).apply {
                textSize = 10f
                setTextColor(0xFF8A8A8A.toInt())
                // 无背景板
            }
            runCatching { root.addView(tv) }.onFailure { return }
            msgTimeViews[root] = tv
            if (diagCount.get() < 8) {
                Logger.i("[$name] [DIAG] 注入气泡下时间 root=${root.javaClass.name} bubble=${bubble?.javaClass?.simpleName}#${bubble?.id?.let { java.lang.Integer.toHexString(it) }}")
            }
        }
        tv.text = timeText
        tv.visibility = View.VISIBLE

        val lp = tv.layoutParams as? android.widget.RelativeLayout.LayoutParams ?: return
        if (bubble != null && bubble.id != View.NO_ID) {
            lp.addRule(android.widget.RelativeLayout.BELOW, bubble.id)
        }
        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
        if (isLeftAvatar(avatar)) {
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
            tv.gravity = Gravity.START
        } else {
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            tv.gravity = Gravity.END
        }
        val d = root.context.resources.displayMetrics.density
        lp.topMargin = (d * 2).toInt()
        tv.layoutParams = lp
    }

    /** 找气泡容器：item 根里不含头像的最大 ViewGroup。 */
    private fun findBubbleContainer(root: ViewGroup, avatar: View?): ViewGroup? {
        var candidate: ViewGroup? = null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            if (avatar != null && containsView(child, avatar)) continue
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

    /** 从头像窗口坐标判断消息方向（左=对方，右=自己）。 */
    private fun isLeftAvatar(avatar: View?): Boolean {
        if (avatar == null || avatar.width <= 0 || !avatar.isAttachedToWindow) return true
        val loc = IntArray(2)
        runCatching { avatar.getLocationInWindow(loc) }.onFailure { return true }
        val cx = loc[0] + avatar.width / 2f
        val screenW = avatar.context.resources.displayMetrics.widthPixels
        return cx <= screenW / 2f
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
