package simple.hook.wechat.core

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 公共的消息 View 生命周期监听。
 *
 * 微信消息列表使用 MVVM 聊天项（`MicroMsg.MvvmChattingItem`），每次绑定调用
 * `onBindView`。这里统一 hook 该点（DexKit 特征定位），把每个消息 View 分发给
 * 已注册的监听器，供去头像 / 已读回执等功能复用，避免各自重复定位。
 */
object MessageViewHub {

    fun interface Listener {
        /** @param holder 消息项 holder；@param view 根 View */
        fun onMessageView(holder: Any, view: View)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val hookBound = java.util.concurrent.atomic.AtomicBoolean(false)

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 在指定 classLoader 上绑定一次（幂等）。
     * @return 是否成功定位并绑定
     */
    fun ensureBound(classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        if (hookBound.get()) return true

        val classes = finder.findClassNamesByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
            .ifEmpty { finder.findClassNamesByStrings("MvvmChattingItem", "onBindView") }

        if (classes.isEmpty()) return false

        classes.forEach { clsName ->
            runCatching {
                val clazz = XposedHelpers.findClass(clsName, classLoader)
                XposedBridge.hookAllMethods(clazz, "onBindView", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.args[0] ?: return
                        val view = holderToView(holder) ?: return
                        listeners.forEach { l ->
                            runCatching { l.onMessageView(holder, view) }
                                .onFailure { Logger.w("MessageViewHub: 监听器异常 $it") }
                        }
                    }
                })
            }.onFailure { Logger.e("MessageViewHub: Hook $clsName 失败 $it") }
        }

        hookBound.set(true)
        return true
    }

    private fun holderToView(holder: Any): View? {
        when (holder) {
            is View -> return holder
            else -> {
                // 尝试从 holder 字段里提取 View 类型字段
                val v = Reflect.findFieldByType(holder, View::class.java) as? View
                if (v != null) return v
                // 尝试遍历字段树找 View 子类实例
                return null
            }
        }
    }
}
