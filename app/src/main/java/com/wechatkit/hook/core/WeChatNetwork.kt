package com.wechatkit.hook.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 微信网络请求发送工具。
 *
 * 微信 8.0.71 的网络架构（已通过 dex 反编译确认）：
 * NetScene 请求对象（如红包 Receive 类）继承链:
 *   i6 (ReceiveLuckyMoney) -> z5 -> l5 -> wallet_core.model.d1 -> modelbase.m1
 * 基类 `com.tencent.mm.modelbase.m1` 定义了:
 *   - dispatcher(): com.tencent.mm.network.s    // 获取网络分发器
 *   - doScene(dispatcher, callback): int        // 发包, callback 是 modelbase.u0
 * 回调接口 `com.tencent.mm.modelbase.u0`:
 *   - onSceneEnd(int errType, int errCode, String errMsg, NetScene scene)
 *
 * 因此发包方式：构造请求对象 -> 调 dispatcher() -> 用动态代理实现 u0 回调 ->
 * 调 doScene(dispatcher, callback)。
 *
 * 不再依赖旧的 NetSceneQueue 机制（8.0.71 已移除集中队列发包）。
 */
object WeChatNetwork {

    private var dispatcherMethod: Method? = null
    private var doSceneMethod: Method? = null
    private var u0Class: Class<*>? = null

    /** 旧版架构：获取 NetSceneQueue 实例的静态 getter。 */
    private var netSceneQueueGetter: Method? = null

    /** 从微信自身发包流程中捕获的 dispatcher 实例。 */
    @Volatile
    private var capturedDispatcher: Any? = null

    /**
     * 初始化微信网络层：解析 NetScene 基类的 dispatcher/doScene 方法。
     * 同时 hook dispatch 方法捕获真实 dispatcher（微信发包时自动获取）。
     */
    fun init(classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        if (doSceneMethod != null) return true
        return try {
            // 1. 定位 NetScene 基类 (modelbase.m1 / NetSceneBase):
            //    日志 TAG "MicroMsg.NetSceneBase" (已通过 dex 反编译确认)
            //    多组特征候选，兼容不同版本
            val baseClassNames = finder.findClassNamesByStrings("MicroMsg.NetSceneBase")
                .ifEmpty { finder.findClassNamesByStrings("NetSceneBase") }
            val baseClassName = baseClassNames.firstOrNull()
            if (baseClassName == null) {
                // 旧版微信：可能没有 NetSceneBase TAG，尝试老架构 NetSceneQueue
                Logger.w("WeChatNetwork: 未定位到 NetScene 基类，尝试旧版架构")
                return initLegacy(classLoader, finder)
            }
            val baseClazz = de.robv.android.xposed.XposedHelpers.findClass(baseClassName, classLoader)
            Logger.i("WeChatNetwork: 找到 NetScene 基类: $baseClassName")

            // 2. 找 doScene 方法
            val doScene = baseClazz.declaredMethods.firstOrNull { m ->
                m.name == "doScene" && m.parameterCount >= 1 &&
                    m.parameterTypes[0].name.contains("network.s")
            } ?: run {
                baseClazz.declaredMethods.firstOrNull { it.name == "doScene" }
            }
            if (doScene == null) {
                Logger.w("WeChatNetwork: 未找到 doScene 方法")
                return false
            }
            doSceneMethod = doScene.apply { isAccessible = true }

            // 3. 找 dispatcher() 方法 (无参, 返回 network.s)
            dispatcherMethod = baseClazz.declaredMethods.firstOrNull { m ->
                m.name == "dispatcher" && m.parameterCount == 0
            }?.apply { isAccessible = true }

            // 4. 解析回调接口 u0 (onSceneEnd 接口)
            u0Class = runCatching {
                classLoader.loadClass("com.tencent.mm.modelbase.u0")
            }.getOrNull()

            // 5. 关键：hook dispatch 方法，捕获微信发包时使用的真实 dispatcher
            runCatching {
                val dispatch = baseClazz.declaredMethods.firstOrNull { m ->
                    m.name == "dispatch" && m.parameterCount >= 1 &&
                        m.parameterTypes[0].name.contains("network.s")
                }
                if (dispatch != null) {
                    de.robv.android.xposed.XposedBridge.hookMethod(dispatch, object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val disp = param.args[0]
                                if (disp != null) capturedDispatcher = disp
                            } catch (_: Throwable) {}
                        }
                    })
                    Logger.i("WeChatNetwork: 已 hook dispatch 捕获 dispatcher")
                }
            }

            Logger.i("WeChatNetwork: 初始化成功, base=$baseClassName, doScene=${doScene.name}")
            true
        } catch (t: Throwable) {
            Logger.e("WeChatNetwork: 初始化失败 $t")
            false
        }
    }

    /** 旧版微信网络层（NetSceneQueue 架构，8.0.x 之前）。 */
    private fun initLegacy(classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        return try {
            // 定位 NetSceneQueue 类：含 "doScene failed" 特征（队列类打印自己的日志）
            val queueNames = finder.findClassNamesByStrings("doScene failed")
                .ifEmpty { finder.findClassNamesByStrings("MicroMsg.NetSceneQueue") }
            val queueClassName = queueNames.firstOrNull()
            if (queueClassName == null) {
                Logger.w("WeChatNetwork: 旧版架构定位失败")
                return false
            }
            val clazz = de.robv.android.xposed.XposedHelpers.findClass(queueClassName, classLoader)
            Logger.i("WeChatNetwork: 找到 NetSceneQueue 类: $queueClassName")

            // doScene(NetScene) 方法
            val send = clazz.declaredMethods.firstOrNull { m ->
                m.name == "doScene" && m.parameterCount == 1
            }?.apply { isAccessible = true }
            if (send == null) {
                Logger.w("WeChatNetwork: 旧版 doScene 未找到")
                return false
            }
            // 旧版模式：sendNetScene 直接调 queue.doScene(netScene)
            doSceneMethod = send
            // 用反射调用队列静态 getter
            val getter = clazz.declaredMethods.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 && m.returnType == clazz
            }?.apply { isAccessible = true }
            if (getter == null) {
                Logger.w("WeChatNetwork: 旧版队列 getter 未找到")
                return false
            }
            netSceneQueueGetter = getter
            legacyMode = true
            Logger.i("WeChatNetwork: 旧版架构初始化成功, queue=$queueClassName")
            true
        } catch (t: Throwable) {
            Logger.e("WeChatNetwork: 旧版初始化失败 $t")
            false
        }
    }

    /** 旧版模式标志：sendNetScene 走 queue.doScene(netScene)。 */
    private var legacyMode = false

    /** 发送一个 NetScene 网络请求对象。 */
    fun sendNetScene(netScene: Any): Boolean {
        // 旧版架构：直接 queue.doScene(netScene)
        if (legacyMode) {
            return runCatching {
                val send = doSceneMethod ?: return false
                val queue = netSceneQueueGetter?.invoke(null) ?: return false
                val ok = send.invoke(queue, netScene) as? Boolean ?: false
                Logger.i("WeChatNetwork: (旧版) doScene -> $netScene 结果=$ok")
                ok
            }.getOrElse { Logger.e("WeChatNetwork: 旧版发送失败", it); false }
        }
        return runCatching {
            val doScene = doSceneMethod ?: run {
                Logger.e("WeChatNetwork: 网络层未初始化")
                return false
            }

            // 1. 获取 dispatcher：优先用捕获的真实 dispatcher，其次调 dispatcher() 方法
            val dispatcher = capturedDispatcher ?: run {
                if (dispatcherMethod != null) {
                    runCatching { dispatcherMethod!!.invoke(netScene) }.getOrNull()
                } else null
            }
            if (dispatcher == null) {
                Logger.e("WeChatNetwork: 无法获取 dispatcher (等待微信发包后自动捕获)")
                return false
            }

            // 2. 构造回调 (u0 接口动态代理)
            val callback = createCallback()
            if (callback == null) {
                Logger.e("WeChatNetwork: 无法创建回调")
                return false
            }

            // 3. 发包
            val result = when {
                doScene.parameterCount == 2 ->
                    doScene.invoke(netScene, dispatcher, callback)
                doScene.parameterCount == 1 ->
                    doScene.invoke(netScene, dispatcher)
                else ->
                    doScene.invoke(netScene)
            }
            Logger.i("WeChatNetwork: doScene -> $netScene 结果=$result")
            true
        }.getOrElse {
            Logger.e("WeChatNetwork: 发送失败", it); false
        }
    }

    /** 创建 u0 回调接口的动态代理。 */
    private fun createCallback(): Any? {
        val u0 = u0Class ?: return null
        return try {
            Proxy.newProxyInstance(
                u0.classLoader,
                arrayOf(u0)
            ) { proxy, method, args ->
                when (method.name) {
                    "onSceneEnd" -> {
                        // 记录结果
                        Logger.i("WeChatNetwork: onSceneEnd errType=${args?.getOrNull(0)} errCode=${args?.getOrNull(1)}")
                        null
                    }
                    else -> null
                }
            }
        } catch (t: Throwable) {
            Logger.e("WeChatNetwork: 创建回调失败 $t")
            null
        }
    }
}
