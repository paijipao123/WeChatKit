package com.wechathook.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信网络请求发送工具。
 *
 * 微信的网络请求（NetScene 系列，如红包、转账的请求类）通过一个队列管理器
 * `NetSceneQueue` 统一发送（调用其 `doScene(NetSceneBase)`）。本工具负责：
 * 1. 用 DexKit 定位 NetSceneQueue 类与其获取实例的静态 getter；
 * 2. 找到 doScene 方法并缓存；
 * 3. 暴露 [sendNetScene] 供红包/转账功能发包。
 *
 * 参考 WeKit/WAuxiliary 的 WeNetworkApi 实现思路，改用标准 API 与 DexKitFinder。
 */
object WeChatNetwork {

    private var netSceneQueueGetter: Method? = null
    private var doSceneMethod: Method? = null

    /**
     * 初始化微信网络层（在主进程、拿到 DexKitFinder 时调用一次）。
     * @return 是否初始化成功
     */
    fun init(classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        runCatching {
            if (doSceneMethod != null) return true

            // 1. 定位 NetSceneQueue 类：含参数数=4且引用 NetSceneObserverOwner 字符串的方法
            val queueClassNames = finder.findClassNamesByMethodStrings(
                "MicroMsg.Mvvm.NetSceneObserverOwner", methodParamCount = 4
            )
            val queueClassName = queueClassNames.firstOrNull() ?: run {
                Logger.w("WeChatNetwork: 未定位到 NetSceneQueue 类")
                return false
            }

            // 2. 定位返回该类的静态无参 getter（单例访问入口）
            val clazz = de.robv.android.xposed.XposedHelpers.findClass(queueClassName, classLoader)

            val getter = clazz.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    m.returnType == clazz
            }
            netSceneQueueGetter = getter?.apply { isAccessible = true }
            if (netSceneQueueGetter == null) {
                Logger.w("WeChatNetwork: 未找到 NetSceneQueue getter")
                return false
            }

            // 3. 定位 doScene 方法：单参数、参数可接收 NetScene 子类、返回 boolean
            val send = clazz.declaredMethods.firstOrNull { m ->
                Modifier.isPublic(m.modifiers) &&
                    (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.java) &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0].name.contains("NetScene")
            }
            doSceneMethod = send?.apply { isAccessible = true }
            if (doSceneMethod == null) {
                Logger.w("WeChatNetwork: 未找到 doScene 发送方法")
                return false
            }
            Logger.i("WeChatNetwork: 初始化成功, queue=$queueClassName")
            true
        }.onFailure { Logger.e("WeChatNetwork: 初始化失败 $it"); false }.getOrDefault(false)
    }

    /** 发送一个 NetScene 网络请求对象。 */
    fun sendNetScene(netScene: Any): Boolean {
        return runCatching {
            val getter = netSceneQueueGetter ?: run {
                Logger.e("WeChatNetwork: 网络层未初始化")
                return false
            }
            val queue = getter.invoke(null) ?: return false
            val send = doSceneMethod ?: return false
            val ok = send.invoke(queue, netScene) as? Boolean ?: false
            Logger.i("WeChatNetwork: doScene -> $netScene 结果=$ok")
            ok
        }.getOrElse {
            Logger.e("WeChatNetwork: 发送失败", it); false
        }
    }
}
