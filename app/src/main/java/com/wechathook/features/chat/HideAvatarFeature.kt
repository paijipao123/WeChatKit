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
import com.wechathook.core.SymbolResolver
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

    /** 另一个头像类（部分界面使用）。 */
    private const val AVATAR_VIEW_CLASS2 = "com.tencent.mm.ui.chatting.view.AvatarImageView"

    private val maskLayoutClass = "com.tencent.mm.ui.base.MaskLayout"

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

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        val m = mode()
        if (m == "off" && itemSpacing <= 0) return

        Logger.i("[$name] 开始 Hook (mode=$m, spacing=$itemSpacing dp)")
        SymbolResolver.detectWechatVersion()
        Logger.i("[$name] 微信版本: ${SymbolResolver.wechatVersionName}")

        // ---- 主策略：动态定位微信头像 View 类 ----
        // 8.0.7x: ChattingAvatarImageView (X2C 布局)
        // 老版本: AvatarImageView / MaskLayout
        val avatarClass = SymbolResolver.resolveClass(
            classLoader, finder,
            featureGroups = arrayOf(
                arrayOf("MicroMsg.ChattingAvatarImageView"),
                arrayOf("ChattingAvatarImageView")
            ),
            candidateNames = arrayOf(
                "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView",
                "com.tencent.mm.ui.chatting.view.AvatarImageView"
            )
        )
        if (avatarClass != null) {
            hookAvatarClassByName(avatarClass, classLoader, "头像类")
        }

        // ---- 主策略2：hook 消息项 View 控制器 (viewitems 包, 持有 avatarIV) ----
        // 8.0.7x: viewitems.g0；其他版本类名可能不同，用 avatarIV 字段特征兜底
        val controllerClass = SymbolResolver.findExistingClass(
            classLoader,
            "com.tencent.mm.ui.chatting.viewitems.g0",
            "com.tencent.mm.ui.chatting.viewitems.cb0",   // 旧版可能的控制器
            "com.tencent.mm.ui.chatting.viewitems.az"     // 更旧版本
        )
        if (controllerClass != null) {
            hookChatItemControllerByName(controllerClass, classLoader)
        } else {
            // 兜底：通过字段特征找持有 avatarIV 的类
            if (finder != null) {
                val holders = finder.findClassNamesByStrings("avatarIV")
                holders.take(3).forEach { holder ->
                    runCatching {
                        hookChatItemControllerByName(holder, classLoader)
                    }.onFailure { Logger.e("[$name] 控制器兜底 Hook $holder 失败: $it") }
                }
            }
        }

        // ---- 辅助 1：hook 消息 item 的绑定方法（关键！） ----
        // 微信把绑定方法名混淆了（类 ve5.g 无 onBindView 方法），必须按特征字符串
        // 定位方法本身再 hook（参考 WeKit 的 WeChatMessageViewApi），不能"类名+onBindView"。
        if (finder != null) {
            val bindMethods = finder.findMethodsByStrings(
                classLoader,
                onlyPackages = listOf("com.tencent.mm"),
                strings = arrayOf("MicroMsg.MvvmChattingItem", "[onBindView]")
            )
            if (bindMethods.isNotEmpty()) {
                bindMethods.take(3).forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    handleOnBindView(param)
                                } catch (_: Throwable) {}
                            }
                        })
                        Logger.i("[$name] 已 Hook 消息绑定方法: ${method.declaringClass.name}#${method.name}")
                    }.onFailure { Logger.e("[$name] 消息绑定方法 Hook 失败: $it") }
                }
            } else {
                // 兜底：类名+onBindView
                val targetClassNames = finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
                if (targetClassNames.isNotEmpty()) {
                    targetClassNames.forEach { hookClass(it, classLoader) }
                } else {
                    finder.findClassNamesByStrings("MvvmChattingItem", "onBindView").firstOrNull()
                        ?.let { hookClass(it, classLoader) }
                }
            }
        }

        // ---- 辅助 2：MaskLayout 兜底（兼容旧版本微信） ----
        hookMaskLayoutFallback(classLoader)
    }

    /**
     * 消息绑定回调（onBindView 已触发）：找到消息条里的头像，
     * 按模式处理（all 直接隐藏 / 单向布局完成后按方向判断），并应用消息间距。
     */
    private fun handleOnBindView(param: XC_MethodHook.MethodHookParam) {
        val holder = param.args[0] ?: return
        val holderView = Reflect.findFieldByType(holder, View::class.java) as? View ?: return
        applyItemSpacing(holderView)
        val m = mode()
        if (m == "off") return

        var avatar: View? = null
        Views.walk(holderView) { v ->
            if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                avatar = v
                true
            } else false
        }
        val av = avatar ?: return
        if (dirLogCount.getAndIncrement() < 40) {
            Logger.i("[$name] [DIR] onBindView holder=${holder.javaClass.name} avatar=${av.javaClass.simpleName}")
        }
        // 诊断：打印 holder 字段（前 3 次），用于定位 isSend 等方向字段
        if (dirLogCount.get() < 8) {
            val sb = StringBuilder()
            holder.javaClass.declaredFields.take(30).forEach { sb.append(it.name).append(",") }
            Logger.i("[$name] [DIR] holder 字段: $sb")
        }
        if (m == "all") {
            hideAvatarView(av)
        } else {
            scheduleDirectionalCheck(av)
        }
    }

    /** 按名称 hook 头像类。 */
    private fun hookAvatarClassByName(className: String, classLoader: ClassLoader, tag: String) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            Logger.i("[$name] 找到$tag: $className")

            // 关键坑1：ChattingAvatarImageView 未声明 onLayout/onDraw（继承自
            // AvatarPatTipImageView/ImageView/View），hookAllMethods(类, 方法名) 只 hook
            // 该类自己声明的方法 —— 对这些继承方法全是空操作！
            // 关键坑2：父链上某一层（如 AvatarPatTipImageView）可能 override 了
            // onLayout/onDraw，只 hook View/ImageView 层也会漏。
            // 因此：沿整条父链，对每一层都 hook onLayout/onDraw，回调里按类名过滤。

            val layoutHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (v.javaClass.name != className) return
                        applyDirectionalHide(v)
                    } catch (_: Throwable) {}
                }
            }
            val drawHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (v.javaClass.name != className) return
                        applyDirectionalHide(v)
                    } catch (_: Throwable) {}
                }
            }

            var curCls: Class<*>? = clazz
            var depth = 0
            var layoutHooked = 0
            var drawHooked = 0
            while (curCls != null && depth < 10) {
                runCatching {
                    layoutHooked += XposedBridge.hookAllMethods(curCls, "onLayout", layoutHook).size
                }.onFailure { }
                runCatching {
                    drawHooked += XposedBridge.hookAllMethods(curCls, "onDraw", drawHook).size
                }.onFailure { }
                curCls = curCls.superclass
                depth++
            }
            Logger.i("[$name] 父链方向判断已挂载 (onLayout x$layoutHooked, onDraw x$drawHooked)")

            // ---- 构造后：mode=all 时立即隐藏（无坐标无法判断方向） ----
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? View ?: return
                            if (mode() == "all") hideAvatarView(v)
                        } catch (_: Throwable) {}
                    }
                })
            }.onFailure { }

            // ---- onVisibilityChanged（ChattingAvatarImageView 自己声明的方法，hook 必生效）：
            //     可见性变化（如微信重新 bind 置为可见）时注册布局回调按方向压回 ----
            runCatching {
                XposedBridge.hookAllMethods(clazz, "onVisibilityChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? View ?: return
                            val m = mode()
                            if (m == "all") {
                                hideAvatarView(v)
                            } else if (m != "off" && v.visibility == View.VISIBLE) {
                                scheduleDirectionalCheck(v)
                            }
                        } catch (_: Throwable) {}
                    }
                })
            }.onFailure { }

            Logger.i("[$name] 已 Hook $tag: $className")
        }.onFailure { Logger.e("[$name] $tag Hook 失败: $it") }
    }

    /** 方向判断 DEBUG 日志计数（限频，独立于触发日志）。 */
    private val dirLogCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** 布局回调触发日志计数（限频）。 */
    private val dirTriggerCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 注册 ViewTreeObserver 布局回调：布局完成后（头像有坐标）按方向判断隐藏。
     * 判断完成（或明确无需处理）后移除监听；条件未就绪（未布局/未 attach）则保留监听下次再试。
     */
    private fun scheduleDirectionalCheck(v: View) {
        runCatching {
            val obs = v.viewTreeObserver
            obs.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (dirTriggerCount.getAndIncrement() < 20) {
                        Logger.i("[$name] [DIR] 布局回调触发 on ${v.javaClass.name} w=${v.width}")
                    }
                    val done = applyDirectionalHide(v)
                    if (done) {
                        runCatching {
                            v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }.onFailure { }
                    }
                }
            })
        }.onFailure { }
    }

    /**
     * 方向感知隐藏：头像已布局（有坐标）时，
     * 按头像中心相对消息条（或根视图）的水平位置判断方向（左=对方，右=自己），
     * 再按模式决定是否隐藏；同时把消息间距应用到 item 根。
     * @return true=已判断完成；false=条件未就绪（调用方可保留监听重试）
     */
    private fun applyDirectionalHide(v: View): Boolean {
        val m = mode()
        if (m == "off") return true
        if (v.width <= 0) return false
        if (!v.isAttachedToWindow) return false

        val itemRoot = findItemRoot(v)
        if (itemRoot != null) {
            applyItemSpacing(itemRoot)
        }
        if (m == "all") {
            hideAvatarView(v)
            return true
        }

        // 头像中心相对参照宽度（itemRoot 或根视图）的水平位置：左半=对方，右半=自己
        val vLoc = IntArray(2)
        runCatching { v.getLocationInWindow(vLoc) }.onFailure { return false }
        val cx = vLoc[0] + v.width / 2f
        val refW: Int
        val isLeft: Boolean
        if (itemRoot != null && itemRoot.width > 0) {
            val rLoc = IntArray(2)
            runCatching { itemRoot.getLocationInWindow(rLoc) }.onFailure { return false }
            refW = itemRoot.width
            isLeft = (cx - rLoc[0]) <= refW / 2f
        } else {
            // 找不到 item 根：用根视图宽度（屏幕）判断左右
            refW = v.rootView?.width ?: 0
            if (refW <= 0) return false
            isLeft = cx <= refW / 2f
        }
        if (dirLogCount.getAndIncrement() < 40) {
            Logger.i("[$name] [DIR] m=$m vW=${v.width} refW=$refW vX=${vLoc[0]} cx=$cx isLeft=$isLeft itemRoot=${itemRoot?.javaClass?.name}")
        }
        val hide = if (m == "incoming") isLeft else !isLeft
        if (hide) hideAvatarView(v)
        return true
    }

    /** 向上找消息 item 根 View（RecyclerView 的直接子 View）。 */
    private fun findItemRoot(v: View): View? {
        var cur: android.view.ViewParent? = v.parent
        var guard = 0
        while (cur is View && guard < 10) {
            val curView = cur as View
            val pp = curView.parent
            if (pp is androidx.recyclerview.widget.RecyclerView) return curView
            cur = pp
            guard++
        }
        return null
    }

    /** 把配置的消息间距（dp）应用到 item 根 View 的上下 margin。 */
    private fun applyItemSpacing(itemRoot: View) {
        val sp = itemSpacing
        if (sp <= 0) return
        runCatching {
            val lp = itemRoot.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val px = (sp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
                lp.topMargin = px
                lp.bottomMargin = px
                itemRoot.layoutParams = lp
            }
        }
    }

    /** 直接 hook 微信头像 View 类。 */
    private fun hideAvatarView(v: View) {
        // 每次调用都强制隐藏（不短路）——RecyclerView 复用同一 View 时，
        // 微信可能把它重新设为 VISIBLE 并重新绑定，必须反复压回。
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

    /** Hook 第二个头像类（AvatarImageView）。 */
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
            // 消息间距（独立于头像模式，绑定阶段应用一次即可）
            applyItemSpacing(holderView)
            // 调试：前 5 次打印消息 View 树结构（帮助定位头像真实位置）
            if (debugDumpCount.getAndIncrement() < 5) {
                dumpViewTree(holderView)
            }
            val m = mode()
            if (m == "all") {
                hideAvatarIn(holderView)
            } else if (m != "off") {
                // incoming/outgoing：onBindView 时 item 尚未布局（无坐标），
                // 对消息条里的头像注册布局回调，布局完成后按方向判断。
                Views.walk(holderView) { v ->
                    if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                        scheduleDirectionalCheck(v)
                    }
                    false
                }
            }
        } catch (t: Throwable) {
            Logger.e("[$name] applyHide 异常: $t")
        }
    }

    private val debugDumpCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 打印消息 View 树（调试用）。 */
    private fun dumpViewTree(root: View) {
        val sb = StringBuilder()
        var count = 0
        Views.walk(root) { v ->
            if (count < 40) {
                sb.append(v.javaClass.name).append(" | ")
            }
            count++
            false
        }
        Logger.i("[$name] [DEBUG] 消息View树($count): $sb")
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
                    if (mode() != "all") return
                    val ths = param.thisObject as? View ?: return
                    hideAvatarView(ths)
                }
            })
            XposedBridge.hookAllMethods(clazz, "onMeasure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (mode() != "all") return
                    val ths = param.thisObject as? View ?: return
                    hideAvatarView(ths)
                }
            })
            Logger.i("[$name] 已挂载 MaskLayout 兜底 Hook")
        }.onFailure { Logger.e("[$name] MaskLayout 兜底 Hook 失败: $it") }
    }

    /** Hook 消息项 View 控制器 (viewitems 包, 持有 avatarIV 字段)。 */
    private fun hookChatItemControllerByName(className: String, classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            Logger.i("[$name] 找到消息项控制器: $className")

            // create(View): 每次消息项创建 View 时, 隐藏其中的头像
            XposedBridge.hookAllMethods(clazz, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val m = mode()
                        if (m == "off") return
                        val view = param.args[0] as? View ?: return
                        val avatarIv = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull()
                        if (m == "all") {
                            if (avatarIv != null) {
                                hideAvatarView(avatarIv)
                            } else {
                                Views.walk(view) { v ->
                                    if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                                        hideAvatarView(v)
                                    }
                                    false
                                }
                            }
                        } else {
                            // incoming/outgoing：注册布局回调，布局完成后按方向判断
                            val target = avatarIv ?: run {
                                var found: View? = null
                                Views.walk(view) { v ->
                                    if (v.javaClass.name == AVATAR_VIEW_CLASS) { found = v; true } else false
                                }
                                found
                            }
                            if (target != null) scheduleDirectionalCheck(target)
                        }
                    } catch (_: Throwable) {}
                }
            })

            // setChattingItem: 每次绑定消息时也处理
            XposedBridge.hookAllMethods(clazz, "setChattingItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val m = mode()
                        if (m == "off") return
                        val view = runCatching {
                            (param.thisObject as? Any)?.let { obj ->
                                XposedHelpers.callMethod(obj, "getMainContainerView") as? View
                            }
                        }.getOrNull() ?: return
                        val avatarIv = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "avatarIV") as? View
                        }.getOrNull()
                        if (m == "all") {
                            if (avatarIv != null) {
                                hideAvatarView(avatarIv)
                            } else {
                                Views.walk(view) { v ->
                                    if (v.javaClass.name == AVATAR_VIEW_CLASS) {
                                        hideAvatarView(v)
                                    }
                                    false
                                }
                            }
                        } else {
                            val target = avatarIv ?: run {
                                var found: View? = null
                                Views.walk(view) { v ->
                                    if (v.javaClass.name == AVATAR_VIEW_CLASS) { found = v; true } else false
                                }
                                found
                            }
                            if (target != null) scheduleDirectionalCheck(target)
                        }
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$name] 已 Hook 消息项控制器 (create/setChattingItem)")
        }.onFailure { Logger.e("[$name] 消息项控制器 Hook 失败: $it") }
    }
}
