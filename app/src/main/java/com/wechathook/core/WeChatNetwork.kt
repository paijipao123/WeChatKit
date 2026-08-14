package com.wechathook.core

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

    /**
     * 初始化微信网络层：解析 NetScene 基类的 dispatcher/doScene 方法。
     * 通过红包 Receive 类的继承链向上找基类 m1（含 doScene 方法）。
     */
    fun init(classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        if (doSceneMethod != null) return true
        return try {
            // 1. 定位 NetScene 基类 (modelbase.m1 / NetSceneBase):
            //    日志 TAG "MicroMsg.NetSceneBase" (已通过 dex 反编译确认)
            val baseClassNames = finder.findClassNamesByStrings("MicroMsg.NetSceneBase")
            val baseClassName = baseClassNames.firstOrNull()
            if (baseClassName == null) {
                Logger.w("WeChatNetwork: 未定位到网络基类")
                return false
            }
            val baseClazz = de.robv.android.xposed.XposedHelpers.findClass(baseClassName, classLoader)
            Logger.i("WeChatNetwork: 找到 NetScene 基类: $baseClassName")

            // 2. 找 doScene 方法: 参数含 network.s (dispatcher) 的 doScene
            val doScene = baseClazz.declaredMethods.firstOrNull { m ->
                m.name == "doScene" && m.parameterCount >= 1 &&
                    m.parameterTypes[0].name.contains("network.s")
            } ?: run {
                // 回退: 任意 doScene 方法
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

            Logger.i("WeChatNetwork: 初始化成功, base=$baseClassName, doScene=${doScene.name}, dispatcher=${dispatcherMethod?.name}")
            true
        } catch (t: Throwable) {
            Logger.e("WeChatNetwork: 初始化失败 $t")
            false
        }
    }

    /** 发送一个 NetScene 网络请求对象。 */
    fun sendNetScene(netScene: Any): Boolean {
        return runCatching {
            val doScene = doSceneMethod ?: run {
                Logger.e("WeChatNetwork: 网络层未初始化")
                return false
            }

            // 1. 获取 dispatcher
            val dispatcher = if (dispatcherMethod != null) {
                runCatching { dispatcherMethod!!.invoke(netScene) }.getOrNull()
            } else {
                null
            }
            if (dispatcher == null) {
                Logger.e("WeChatNetwork: 无法获取 dispatcher")
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
