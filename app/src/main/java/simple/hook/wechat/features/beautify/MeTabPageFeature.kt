package simple.hook.wechat.features.beautify

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import simple.hook.wechat.core.DexKitFinder
import simple.hook.wechat.core.Feature
import simple.hook.wechat.core.Logger
import simple.hook.wechat.core.Prefs
import simple.hook.wechat.core.Views
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「我」页面精简（移植自 WeKit `HideMeTabPageItems`）。
 *
 * ## 实现原理
 *
 * 微信「我」页面（`com.tencent.mm.ui.MoreTabUI`）是一个列表，每行是一个入口：
 * 朋友圈 / 视频号 / 卡包 / 表情 等。**行内的部分没有稳定 id**，
 * 但**每行的标题文字是稳定且用户可见的**，因此按文字识别目标行是最可靠的方式。
 *
 * 流程：
 * 1. hook `MoreTabUI.onViewCreated`，拿到页面 root；
 * 2. 在 root 上装 `OnGlobalLayoutListener`——因为列表是异步填充的，
 *    首次回调时行可能还不存在，必须每次布局后重新扫描；
 * 3. 找到标题文字匹配的行，隐藏其**行容器**（不是 TextView 本身）。
 *
 * ## 隐藏方式（关键，直接决定视觉是否正确）
 *
 * 不能只 `GONE` TextView——行容器仍占位，会留下一块空白。WeKit 的做法是：
 * - `isGone = true` + `minimumHeight = 0` + `padding` 清零
 * - `layoutParams.height = 1` + margin 清零
 * 之所以用 height=1 而不是 0，是因为部分 `LinearLayout` 容器在高度恰为 0 时
 * 仍会因 `divider`/`minHeight` 残留 1px，直接给 1 更干净；
 * - 最后隐藏**前一个分隔线**（微信的行间分割线是独立 View，不隐藏会留下孤立横线）。
 *
 * ## 兼容性
 *
 * 不同微信版本「作品」入口的文案不同（视频号 / 视频号和公众号 / 作品），
 * 「卡包」也可能是「小店与卡包」，因此每项匹配**一组候选文案**。
 */
object MeTabPageFeature : Feature {

    override val key = "me_tab_slim"
    override val name = "「我」页面精简"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    /** 「我」页面 Activity（8.0.7x 固定类名）。 */
    private const val CLS_MORE_TAB_UI = "com.tencent.mm.ui.MoreTabUI"

    /** 已装过布局监听的 root，避免重复注册。 */
    private val installedRoots = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    )

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        Logger.i("[$name] 开始 Hook")

        // 优先用固定类名（8.0.7x 稳定）；失败再用 DexKit 特征兜底。
        val hooked = hookByClassName(classLoader)
        if (!hooked && finder != null) {
            hookByFeature(classLoader, finder)
        }

        if (!hooked) {
            Logger.w("[$name] 未能定位「我」页面，功能可能不生效")
        }
    }

    /** 主策略：按固定类名 hook MoreTabUI.onViewCreated。 */
    private fun hookByClassName(classLoader: ClassLoader): Boolean {
        return runCatching {
            val clazz = XposedHelpers.findClass(CLS_MORE_TAB_UI, classLoader)
            XposedBridge.hookAllMethods(clazz, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val root = param.args.getOrNull(0) as? ViewGroup ?: return
                        installLayoutListener(root)
                        root.post { applyEntryRules(root) }
                    } catch (t: Throwable) {
                        Logger.w("[$name] onViewCreated 处理异常: $t")
                    }
                }
            })
            Logger.i("[$name] 已 Hook $CLS_MORE_TAB_UI#onViewCreated")
            true
        }.onFailure {
            Logger.w("[$name] 固定类名 Hook 失败，将尝试 DexKit: $it")
            false
        }.getOrDefault(false)
    }

    /** 兜底：DexKit 按页面特征字符串定位。 */
    private fun hookByFeature(classLoader: ClassLoader, finder: DexKitFinder) {
        runCatching {
            val names = finder.findClassNamesByStrings("MicroMsg.MoreTabUI")
                .ifEmpty { finder.findClassNamesByStrings("MoreTabUI") }
            names.take(2).forEach { name ->
                runCatching {
                    val clazz = XposedHelpers.findClass(name, classLoader)
                    XposedBridge.hookAllMethods(clazz, "onViewCreated", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val root = param.args.getOrNull(0) as? ViewGroup ?: return
                                installLayoutListener(root)
                                root.post { applyEntryRules(root) }
                            } catch (_: Throwable) {}
                        }
                    })
                    Logger.i("[$name] 已通过 DexKit 定位并 Hook: $name")
                }
            }
        }.onFailure { Logger.e("[$name] DexKit 兜底失败: $it") }
    }

    /**
     * 安装布局监听。
     *
     * 「我」页面的行是异步填充的（且用户可能下拉/滚动触发重绘），
     * 首次 `post` 时行可能还不存在，因此每次布局后都重新扫描一遍。
     * 每行都做了幂等判断，重复执行无副作用。
     */
    private fun installLayoutListener(root: ViewGroup) {
        if (!installedRoots.add(root)) return
        runCatching {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                try {
                    applyEntryRules(root)
                } catch (_: Throwable) {}
            }
        }.onFailure { Logger.e("[$name] 安装布局监听失败: $it") }
    }

    /** 扫描页面，隐藏被选中的入口行。 */
    private fun applyEntryRules(root: ViewGroup) {
        val targets = selectedTargets
        if (targets.isEmpty()) return

        var hidden = 0
        Views.walk(root) { v ->
            if (v is TextView) {
                val text = v.text?.toString()?.trim().orEmpty()
                if (text in targets) {
                    if (hideContainerFor(v)) hidden++
                }
            }
            false
        }
        // 布局回调每次都会触发，只在真正隐藏了新行时打日志，避免刷屏
        if (hidden > 0) {
            Logger.i("[$name] 本次隐藏 $hidden 个入口")
        }
    }

    /** 用户勾选的项目 → 对应的宿主行标题文案。 */
    private val selectedTargets: Set<String>
        get() {
            val targets = linkedSetOf<String>()
            if (Prefs.getBoolean("me_hide_moments", false)) {
                targets += "朋友圈"
            }
            if (Prefs.getBoolean("me_hide_finder", false)) {
                // 不同微信版本文案不同
                targets += "视频号"
                targets += "视频号和公众号"
                targets += "作品"
            }
            if (Prefs.getBoolean("me_hide_cards", false)) {
                targets += "卡包"
                targets += "小店与卡包"
            }
            if (Prefs.getBoolean("me_hide_emoji", false)) {
                targets += "表情"
            }
            return targets
        }

    /**
     * 隐藏标题所在的行容器。
     *
     * 用 height=1 + margin/padding 清零 + minimumHeight=0 的组合，
     * 而不是简单 GONE——保证不残留空白，同时避免某些容器在高度为 0 时
     * 因 divider/minHeight 残留 1px 细线。
     *
     * @return true=本次确实执行了隐藏（用于日志计数）
     */
    private fun hideContainerFor(labelView: View): Boolean {
        val container = findRowContainer(labelView) ?: return false

        // 幂等：已处理过就跳过，避免每次布局都重复设置 layoutParams 触发重排
        if (container.visibility == View.GONE && container.layoutParams?.height == 1) return false

        container.visibility = View.GONE
        container.minimumHeight = 0
        container.setPadding(0, 0, 0, 0)
        container.layoutParams?.let { params ->
            params.height = 1
            if (params is ViewGroup.MarginLayoutParams) {
                params.setMargins(0, 0, 0, 0)
            }
            container.layoutParams = params
        }

        hidePreviousDivider(container)
        (container.parent as? View)?.requestLayout()
        return true
    }

    /** 向上找该标题所属的行容器（列表项的直接子 View）。 */
    private fun findRowContainer(view: View): View? {
        var current = view
        var guard = 0
        while (guard < 10) {
            val parent = current.parent
            if (parent !is ViewGroup) return null
            // 到达列表本身：上一层的 current 就是行容器
            if (parent is android.widget.ListView ||
                parent.javaClass.name == "androidx.recyclerview.widget.RecyclerView"
            ) {
                return current
            }
            current = parent
            guard++
        }
        return null
    }

    /**
     * 隐藏行容器前面的分隔线。
     *
     * 微信列表的分隔线是独立 View，贴在行与行之间。行被隐藏后它会变成一条孤立横线。
     * 判定方式：前一个兄弟不是 TextView（文字行说明是内容而非分隔线），
     * 且高度小于 30dp（排除正常内容行）。
     */
    private fun hidePreviousDivider(container: View) {
        val parent = container.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(container)
        if (index <= 0) return

        val previous = parent.getChildAt(index - 1)
        if (previous is TextView) return

        val density = previous.resources.displayMetrics.density
        val measuredHeight = previous.height.takeIf { it > 0 } ?: return
        if (measuredHeight >= 30f * density) return

        previous.visibility = View.GONE
        previous.layoutParams?.let { params ->
            params.height = 1
            previous.layoutParams = params
        }
    }
}
