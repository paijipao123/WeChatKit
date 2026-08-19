package simple.hook.wechat.features.chat

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import simple.hook.wechat.core.DexKitFinder
import simple.hook.wechat.core.Feature
import simple.hook.wechat.core.Logger
import simple.hook.wechat.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息时间显示。
 *
 * 参考 WeKit2 MessageTimeEnhancements 方案：
 * - hook onBindView（特征字符串 "MicroMsg.MvvmChattingItem"+"[onBindView]"）；
 * - args[0] = holder；holder.tag 包含 timeTV 字段；
 * - args[2] = msgId；通过 ChattingDataAdapter.getItem(msgId) 获取 MsgInfo；
 * - MsgInfo.field_createTime（秒）即发送时间；
 *
 * 两种模式：
 * - avatar 模式：头像下方注入时间 TextView（保 MastLayout 圆形遮罩）
 * - message 模式：直接修改微信原生 timeTV 的文本样式
 */
object AvatarTimeFeature : Feature {

    override val key = "avatar_time"
    override val name = "消息时间显示"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = true

    private const val AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"

    /** 显示模式：avatar=头像下方 / message=直接改原生timeTV */
    private fun mode(): String = Prefs.getString("avatar_time_mode", "avatar")

    /** 时间格式 */
    private fun timeFormat(): String = Prefs.getString("avatar_time_format", "HH:mm")

    /** 字体大小 sp */
    private fun textSize(): Float = Prefs.getInt("avatar_time_size", 11).toFloat()

    /** avatar 模式：头像 -> 时间 TextView（弱引用）。 */
    private val avatarTimeViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, TextView>())

    /** message 模式：item 根 -> 是否已处理过（防止重复）。 */
    private val processedItems = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())

    private val diagCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_avatar_time", false)) return
        Logger.i("[$name] 开始 Hook (mode=${mode()}, format=${timeFormat()})")
        if (finder == null) return

        // hook onBindView（WeKit2 同款：特征字符串定位）
        val methods = runCatching {
            finder.findMethodsByStrings(
                classLoader,
                strings = arrayOf("MicroMsg.MvvmChattingItem", "[onBindView]")
            )
        }.getOrDefault(emptyList())

        if (methods.isNotEmpty()) {
            methods.take(3).forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                handleOnBindView(param, classLoader)
                            } catch (t: Throwable) {
                                if (diagCount.getAndIncrement() < 5) {
                                    Logger.e("[$name] onBindView 处理异常: $t")
                                }
                            }
                        }
                    })
                    Logger.i("[$name] 已 Hook onBindView: ${method.declaringClass.name}#${method.name}")
                }.onFailure { Logger.e("[$name] onBindView Hook 失败: $it") }
            }
        } else {
            // fallback: 类名搜索
            val clsNames = runCatching {
                finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
            }.getOrDefault(emptyList())
            val target = clsNames.firstOrNull()
                ?: runCatching {
                    finder.findClassNamesByStrings("MvvmChattingItem", "onBindView").firstOrNull()
                }.getOrNull()
            if (target != null) {
                runCatching {
                    val clazz = classLoader.loadClass(target)
                    XposedBridge.hookAllMethods(clazz, "onBindView", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                handleOnBindView(param, classLoader)
                            } catch (t: Throwable) {
                                if (diagCount.getAndIncrement() < 5) {
                                    Logger.e("[$name] onBindView fallback 处理异常: $t")
                                }
                            }
                        }
                    })
                    Logger.i("[$name] 已 Hook $target#onBindView (fallback)")
                }.onFailure { Logger.e("[$name] fallback Hook $target 失败: $it") }
            } else {
                Logger.e("[$name] 未找到 onBindView 方法")
            }
        }
    }

    // ============ 核心：onBindView 处理 ============

    private fun handleOnBindView(param: XC_MethodHook.MethodHookParam, classLoader: ClassLoader) {
        val holder = param.args.getOrNull(0) ?: return

        // 从 holder 获取 View（convertView / 根 View）
        val holderView = findFieldByNameInHierarchy(holder, "convertView") as? View
            ?: (holder as? View)
            ?: findFieldOfType(holder, View::class.java) as? View
            ?: return

        // 获取 msgId (args[2])
        val msgId = (param.args.getOrNull(2) as? Int) ?: -1
        if (msgId < 0) {
            if (diagCount.get() < 3) Logger.i("[$name] [DIAG] msgId 无效: $msgId")
            return
        }

        // 获取 createTime
        val createTimeSec = findCreateTimeFromHolder(holder, msgId, classLoader)
        if (createTimeSec <= 0) {
            if (diagCount.get() < 3) Logger.i("[$name] [DIAG] createTime 获取失败")
            return
        }

        val timeText = SimpleDateFormat(timeFormat(), Locale.getDefault())
            .format(Date(createTimeSec * 1000))

        if (diagCount.get() < 5) {
            Logger.i("[$name] [DIAG] msgId=$msgId createTime=$createTimeSec timeText=$timeText")
        }

        if (mode() == "message") {
            handleMessageMode(holderView, holder, timeText)
        } else {
            handleAvatarMode(holderView, timeText)
        }
    }

    /**
     * message 模式：直接修改微信原生 timeTV
     * 参考 WeKit2：从 view.tag 获取 timeTV 字段
     */
    private fun handleMessageMode(holderView: View, holder: Any, timeText: String) {
        // 尝试从 view.tag 获取 timeTV（WeKit2 方案）
        val timeTV = findTimeTVFromView(holderView)
            ?: findTimeTVFromHolder(holder)

        if (timeTV != null) {
            // 直接修改原生 timeTV
            timeTV.text = timeText
            timeTV.visibility = View.VISIBLE
            timeTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize())

            // 调整布局参数：消息居左/居右
            val lp = timeTV.layoutParams as? RelativeLayout.LayoutParams
            if (lp != null) {
                val isLeft = isLeftMessage(holder)
                lp.removeRule(RelativeLayout.CENTER_HORIZONTAL)
                if (isLeft) {
                    lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                    lp.marginStart = dpToPx(holderView.context, 12)
                    lp.marginEnd = 0
                    timeTV.gravity = Gravity.START
                } else {
                    lp.addRule(RelativeLayout.ALIGN_PARENT_END)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                    lp.marginEnd = dpToPx(holderView.context, 12)
                    lp.marginStart = 0
                    timeTV.gravity = Gravity.END
                }
                timeTV.layoutParams = lp
            }
            if (diagCount.get() < 5) {
                Logger.i("[$name] [DIAG] message 模式已更新 timeTV text=$timeText")
            }
        } else {
            // 兜底：在气泡下方注入时间（原来的方式）
            if (diagCount.get() < 3) {
                Logger.i("[$name] [DIAG] message 模式未找到 timeTV，尝试注入方式")
            }
            ensureMsgTimeBelowBubble(holderView, timeText)
        }
    }

    /**
     * avatar 模式：头像下方注入时间 TextView
     */
    private fun handleAvatarMode(holderView: View, timeText: String) {
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

    // ============ 查找 timeTV ============

    /** 从 holderView 查找 timeTV（通过遍历或 tag）。 */
    private fun findTimeTVFromView(holderView: View): TextView? {
        // 先尝试从 tag 获取
        val tag = holderView.tag
        if (tag != null) {
            val timeTV = findFieldByNameInHierarchy(tag, "timeTV") as? TextView
            if (timeTV != null) return timeTV
        }

        // 兜底：遍历 View 树找 TextView（通常 id 为 timeTV 或类似）
        var found: TextView? = null
        walk(holderView) { v ->
            if (v is TextView && v.id != View.NO_ID) {
                val name = holderView.context.resources.getResourceEntryName(v.id)
                if (name.contains("time", ignoreCase = true)) {
                    found = v
                    return@walk true
                }
            }
            false
        }
        return found
    }

    /** 从 holder 对象查找 timeTV。 */
    private fun findTimeTVFromHolder(holder: Any): TextView? {
        // 从 holder 的 tag 或字段中找
        val tag = holder.let {
            if (it is View) it.tag else findFieldByNameInHierarchy(it, "tag")
        }
        if (tag != null && tag is View) {
            return findTimeTVFromView(tag)
        }
        // 直接从 holder 字段找
        return findFieldByNameInHierarchy(holder, "timeTV") as? TextView
    }

    // ============ 获取 createTime ============

    /** 从 holder 和 msgId 获取 createTime。 */
    private fun findCreateTimeFromHolder(holder: Any, msgId: Int, classLoader: ClassLoader): Long {
        // 1. 直接从 holder 参数中找 MsgInfo（args 里的对象）
        val holderView = findFieldByNameInHierarchy(holder, "convertView") as? View
            ?: (holder as? View)

        if (holderView != null) {
            // 尝试从 holderView 关联的对象获取
            val createTime = findCreateTimeInViewGraph(holderView, 0, java.util.HashSet())
            if (createTime > 0) return createTime
        }

        // 2. 通过 ChattingDataAdapter.getItem(msgId) 获取
        val adapter = findFieldOfAdapterLike(holder)
        if (adapter != null) {
            val msgInfo = runCatching {
                // 尝试 getItem(int)
                val getItemMethod = adapter.javaClass.methods.firstOrNull {
                    it.name == "getItem" && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Integer.TYPE
                }
                getItemMethod?.invoke(adapter, msgId)
            }.getOrNull()

            if (msgInfo != null) {
                val ct = readCreateTimeSec(msgInfo)
                if (ct > 0) {
                    if (diagCount.get() < 5) {
                        Logger.i("[$name] [DIAG] getItem($msgId) -> ct=$ct")
                    }
                    return ct
                }
            }
        }

        // 3. 递归在 holder 对象图里找
        return findCreateTimeInObjectGraph(holder, 0, java.util.HashSet())
    }

    /** 在对象图里递归找 field_createTime。 */
    private fun findCreateTimeInObjectGraph(obj: Any?, depth: Int, visited: java.util.HashSet<Int>): Long {
        if (obj == null || depth > 3 || visited.size > 200) return 0
        if (obj is String || obj is Number || obj is Boolean || obj is Char ||
            obj is android.graphics.drawable.Drawable || obj is View || obj is android.app.Activity
        ) return 0
        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return 0

        // 直接检查是否是 MsgInfo
        val ct = readCreateTimeSec(obj)
        if (ct > 0) return ct

        // 遍历字段
        var c: Class<*>? = obj.javaClass
        var checked = 0
        while (c != null && c != Any::class.java && checked < 30) {
            for (f in c.declaredFields) {
                if (++checked > 150) return 0
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val t = f.type
                    if (!t.isPrimitive && !t.isArray && !t.name.startsWith("java.") &&
                        !t.name.startsWith("android.") && !t.name.startsWith("kotlin.")
                    ) {
                        val child = f.get(obj) ?: continue
                        val result = findCreateTimeInObjectGraph(child, depth + 1, visited)
                        if (result > 0) return result
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return 0
    }

    /** 在 View 对象图里找 field_createTime（用于从 View 找关联的 MsgInfo）。 */
    private fun findCreateTimeInViewGraph(view: View, depth: Int, visited: java.util.HashSet<Int>): Long {
        if (view == null || depth > 3 || visited.size > 100) return 0
        val id = System.identityHashCode(view)
        if (!visited.add(id)) return 0

        // 检查 tag
        val tag = view.tag
        if (tag != null && tag !is View) {
            val ct = readCreateTimeSec(tag)
            if (ct > 0) return ct
        }

        // 检查 holder 对象
        val holder = view.tag
        if (holder != null && holder !is View) {
            val ct = findCreateTimeInObjectGraph(holder, 0, java.util.HashSet())
            if (ct > 0) return ct
        }

        // 递归检查子 View
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val ct = findCreateTimeInViewGraph(child, depth + 1, visited)
                if (ct > 0) return ct
            }
        }
        return 0
    }

    /** 读 MsgInfo 的 field_createTime（秒）。 */
    private fun readCreateTimeSec(mi: Any): Long = runCatching {
        var c: Class<*>? = mi.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.name == "field_createTime" && f.type == java.lang.Long.TYPE) {
                    f.isAccessible = true
                    val v = f.getLong(mi)
                    // 秒级时间戳范围
                    if (v in 1_000_000_000L..2_500_000_000L) return v
                    // 毫秒级时间戳范围
                    if (v in 1_000_000_000_000L..2_500_000_000_000L) return v / 1000
                }
            }
            c = c.superclass
        }
        0L
    }.getOrDefault(0L)

    // ============ 辅助方法 ============

    /** 在对象上找"像 adapter"的字段（有 getItem 方法）。 */
    private fun findFieldOfAdapterLike(obj: Any): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                if (v is String || v is Number || v is View) continue
                val hasGetItem = runCatching {
                    v.javaClass.methods.any { it.name == "getItem" && it.parameterTypes.size == 1 }
                }.getOrDefault(false)
                if (hasGetItem) return v
            }
            c = c.superclass
        }
        return null
    }

    /** 判断是左/右侧消息（通过 holder 的 isSend 字段）。 */
    private fun isLeftMessage(holder: Any): Boolean {
        // 尝试找 isSend 字段
        val isSend = runCatching {
            var c: Class<*>? = holder.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.name == "isSend" || f.name == "field_isSend") {
                        f.isAccessible = true
                        return@runCatching when (val v = f.get(holder)) {
                            is Int -> v == 0  // 0 = 收到的消息（左侧）
                            is Boolean -> !v
                            else -> true
                        }
                    }
                }
                c = c.superclass
            }
            true // 默认返回左侧
        }.getOrDefault(true)

        return isSend
    }

    /** message 模式兜底：气泡下方注入时间。 */
    private fun ensureMsgTimeBelowBubble(holderView: View, timeText: String) {
        val root = holderView as? ViewGroup ?: return

        var tv = processedItems[root]?.let { null } // 暂时不用这个 map

        // 找气泡容器
        var avatar: View? = null
        walk(root) { v ->
            if (v.javaClass.name == AVATAR_VIEW_CLASS) { avatar = v; true } else false
        }
        val bubble = findBubbleContainer(root, avatar)

        // 创建或复用时间 TextView
        val existingTv = findTimeTextView(root)
        val timeTv = if (existingTv != null) {
            existingTv
        } else {
            TextView(root.context).apply {
                textSize = 10f
                setTextColor(0xFF8A8A8A.toInt())
            }.also {
                runCatching { root.addView(it) }.onFailure { return }
            }
        }

        timeTv.text = timeText
        timeTv.visibility = View.VISIBLE

        // 必须用 RecyclerView.LayoutParams(RecyclerView 强转)
        // 微信是自定义 RecyclerView,子 View LP 必须是它自己的内部类(androidx/rv LP 不能转)。
        // 从 itemView.layoutParams 反射创建同类型 LP。

        // 微信 RecyclerView(ChattingRecyclerView)子 View LP 必须用它内部类,强转闪退。
        // 解法:加 TextView 到 **RV 树之外的祖先**(MMPullDownView/ListView 等)。
        var anc: android.view.ViewParent = root.parent
        while (anc is androidx.recyclerview.widget.RecyclerView) anc = anc.parent ?: break
        val rvOutParent = (anc as? android.view.ViewGroup) ?: return
        val densityVal = root.context.resources.displayMetrics.density
        val m = densityVal.toInt()
        // 反射构造(WRAP_CONTENT, WRAP_CONTENT) — 任何 LP 父类都接受
        val lp: android.view.ViewGroup.LayoutParams = try {
            val ctor = rvOutParent.javaClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            ctor.newInstance(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ) as android.view.ViewGroup.LayoutParams
        } catch (_: Throwable) {
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // 用 MagLayoutParams 反射 setMargins(避免 lp.topMargin 不存在问题)
        try {
            val mlp = lp.javaClass
            mlp.getMethod("setMargins",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(lp, m * 12, m * 60, m * 12, 0)
        } catch (_: Throwable) {}
        timeTv.layoutParams = lp
        rvOutParent.addView(timeTv)
    }

    /** 查找已有的时间 TextView（避免重复注入）。 */
    private fun findTimeTextView(root: ViewGroup): TextView? {
        var found: TextView? = null
        walk(root) { v ->
            if (v is TextView && v.textSize <= 11f &&
                (v.currentTextColor and 0xFF000000.toInt()) == 0x8A8A8A.toInt()
            ) {
                // 简单判断：字体小、灰色
                found = v
                true
            } else false
        }
        return found
    }

    /** 找气泡容器。 */
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

    /** 从头像窗口坐标判断消息方向。 */
    private fun isLeftAvatar(avatar: View?): Boolean {
        if (avatar == null || avatar.width <= 0 || !avatar.isAttachedToWindow) return true
        val loc = IntArray(2)
        runCatching { avatar.getLocationInWindow(loc) }.onFailure { return true }
        val cx = loc[0] + avatar.width / 2f
        val screenW = avatar.context.resources.displayMetrics.widthPixels
        return cx <= screenW / 2f
    }

    /** 头像下方注入时间。 */
    private fun injectTimeBelowAvatar(avatar: View): TextView? {
        val maskParent = avatar.parent
        val target = if (maskParent != null &&
            maskParent.javaClass.name.contains("MaskLayout")
        ) maskParent as? ViewGroup else avatar
        val parent = target?.parent as? ViewGroup ?: return null
        val idx = parent.indexOfChild(target)
        if (idx < 0) return null
        val lp = target.layoutParams
        val targetId = target.id
        val w = if (lp != null && lp.width > 0) lp.width else ViewGroup.LayoutParams.WRAP_CONTENT
        val h = if (lp != null && lp.height > 0) lp.height else ViewGroup.LayoutParams.WRAP_CONTENT

        parent.removeView(target)
        val wrapper = LinearLayout(target.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (targetId != View.NO_ID) id = targetId
        }
        wrapper.addView(target, LinearLayout.LayoutParams(w, h))

        val tv = TextView(target.context).apply {
            text = ""
            textSize = textSize()
            setTextColor(0xFF8A8A8A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpToPx(target.context, 2), 0, 0)
        }
        wrapper.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        runCatching { parent.addView(wrapper, idx, lp) }.onFailure { return null }
        return tv
    }

    // ============ 工具方法 ============

    private fun walk(root: View, visitor: (View) -> Boolean) {
        if (visitor(root)) return
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) walk(root.getChildAt(i), visitor)
        }
    }

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

    private fun dpToPx(context: android.content.Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
