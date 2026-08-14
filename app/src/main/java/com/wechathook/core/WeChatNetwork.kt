package com.wechathook.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信网络请求发送工具。
 *
 * 微信的网络请求（NetScene 系列，如红包、转账的请求类）通过一个队列管理器
 * `NetSceneQueue` 统一发送（调用其 `doScene(NetSceneBase)`）。
 *
 * 微信 8.0.71 的访问方式（已按 dex 核实）：
 * - 旧版: MMCore.getNetSceneQueue() 静态方法
 * - 新版: kernel().network().getNetSceneQueue() 链式调用
 * - NetSceneQueue 类含日志 TAG "MicroMsg.NetSceneQueue"（classes12/15/6）
 *
 * 本工具多路定位：
 * 1. 用 DexKit 找含 "MicroMsg.NetSceneQueue" TAG 的类（即 NetSceneQueue 类本身）
 * 2. 找它的 doScene 方法（单参数、返回 boolean、参数含 NetScene）
 * 3. 找获取队列实例的 getter（多路：静态 getter / kernel 链 / MMCore）
 */
object WeChatNetwork {

    private var netSceneQueueGetter: Method? = null
    private var doSceneMethod: Method? = null
    private var queueInstance: Any? = null

    /**
     * 初始化微信网络层（在主进程、拿到 DexKitFinder 时调用一次）。
     * @return 是否初始化成功
     */
    fun init(classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        if (doSceneMethod != null && (netSceneQueueGetter != null || queueInstance != null)) return true
        return try {
            // 1. 定位 NetSceneQueue 类：
            //    a) 首选: 含 "doScene failed" 的类（真正的队列类会打印自己的 doScene 失败日志）
            //    b) 回退: 含 "MicroMsg.NetSceneQueue" TAG 的类
            var queueClassNames = finder.findClassNamesByStrings("doScene failed")
            if (queueClassNames.isEmpty()) {
                queueClassNames = finder.findClassNamesByStrings("MicroMsg.NetSceneQueue")
            }
            val queueClassName = queueClassNames.firstOrNull()
            if (queueClassName == null) {
                Logger.w("WeChatNetwork: 未定位到 NetSceneQueue 类")
                false
            } else {
                val clazz = de.robv.android.xposed.XposedHelpers.findClass(queueClassName, classLoader)
                Logger.i("WeChatNetwork: 找到 NetSceneQueue 类: $queueClassName")

                // 2. 找 doScene 方法：单参数、参数可接收 NetScene 子类、返回 boolean
                val send = clazz.declaredMethods.firstOrNull { m ->
                    Modifier.isPublic(m.modifiers) &&
                        (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.java) &&
                        m.parameterCount == 1 &&
                        m.parameterTypes[0].name.contains("NetScene")
                }
                doSceneMethod = send?.apply { isAccessible = true }
                if (doSceneMethod == null) {
                    Logger.w("WeChatNetwork: 未找到 doScene 发送方法")
                    false
                } else {
                    // 3. 多路获取队列实例
                    if (resolveQueueGetter(clazz, classLoader, finder)) {
                        Logger.i("WeChatNetwork: 初始化成功, queue=$queueClassName, doScene=${send?.name}")
                        true
                    } else {
                        Logger.w("WeChatNetwork: 未找到队列实例获取方式")
                        false
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e("WeChatNetwork: 初始化失败 $t")
            false
        }
    }

    /** 多路解析"获取 NetSceneQueue 实例"的途径。 */
    private fun resolveQueueGetter(queueClass: Class<*>, classLoader: ClassLoader, finder: DexKitFinder): Boolean {
        // 路 A：NetSceneQueue 类自身的静态无参方法，返回自身（如 getInstance）
        try {
            val getter = queueClass.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == queueClass
            }
            if (getter != null) {
                getter.isAccessible = true
                netSceneQueueGetter = getter
                Logger.i("WeChatNetwork: 通过静态 getter 获取队列: ${getter.name}")
                return true
            }
        } catch (_: Throwable) {}

        // 路 B：找含 "getNetSceneQueue" 特征字符串的类/方法（kernel/network 链的入口）
        try {
            // 找含 "kernel().network().getNetSceneQueue()" 字符串的类
            val holderNames = finder.findClassNamesByStrings("getNetSceneQueue")
            for (holderName in holderNames.take(3)) {
                try {
                    val holder = de.robv.android.xposed.XposedHelpers.findClass(holderName, classLoader)
                    // 找返回 NetSceneQueue 的无参方法
                    val getter = holder.declaredMethods.firstOrNull { m ->
                        m.parameterCount == 0 && m.returnType == queueClass
                    }
                    if (getter != null) {
                        getter.isAccessible = true
                        // 需要拿到 holder 实例：尝试静态方法或单例
                        if (Modifier.isStatic(getter.modifiers)) {
                            netSceneQueueGetter = getter
                        } else {
                            // 尝试通过 holder 的静态方法获取实例（如 getInstance/getXXX）
                            val instGetter = holder.declaredMethods.firstOrNull { m ->
                                Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == holder
                            }
                            if (instGetter != null) {
                                instGetter.isAccessible = true
                                val holderInst = instGetter.invoke(null)
                                if (holderInst != null) {
                                    // 缓存: 用 holderInst 直接调 getter
                                    val getterCopy = getter
                                    queueInstance = runCatching { getterCopy.invoke(holderInst) }.getOrNull()
                                    if (queueInstance != null) {
                                        Logger.i("WeChatNetwork: 通过 $holderName#$instGetter.name -> ${getter.name} 获取队列")
                                        return true
                                    }
                                }
                            }
                        }
                        if (netSceneQueueGetter != null) {
                            Logger.i("WeChatNetwork: 通过静态方法 ${holderName}#${getter.name} 获取队列")
                            return true
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 路 C：找含 "MMCore.getNetSceneQueue().doScene" 特征的类（MMCore 等价物）
        try {
            val mmCoreNames = finder.findClassNamesByStrings("MMCore.getNetSceneQueue().doScene")
            for (name in mmCoreNames.take(3)) {
                try {
                    val holder = de.robv.android.xposed.XposedHelpers.findClass(name, classLoader)
                    val getter = holder.declaredMethods.firstOrNull { m ->
                        Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == queueClass
                    }
                    if (getter != null) {
                        getter.isAccessible = true
                        netSceneQueueGetter = getter
                        Logger.i("WeChatNetwork: 通过 MMCore 等价类 $name#${getter.name} 获取队列")
                        return true
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        return false
    }

    /** 发送一个 NetScene 网络请求对象。 */
    fun sendNetScene(netScene: Any): Boolean {
        return runCatching {
            val send = doSceneMethod ?: run {
                Logger.e("WeChatNetwork: 网络层未初始化")
                return false
            }
            val queue = when {
                queueInstance != null -> queueInstance
                netSceneQueueGetter != null -> netSceneQueueGetter!!.invoke(null)
                else -> null
            } ?: run {
                Logger.e("WeChatNetwork: 无法获取 NetSceneQueue 实例")
                return false
            }
            val ok = send.invoke(queue, netScene) as? Boolean ?: false
            Logger.i("WeChatNetwork: doScene -> $netScene 结果=$ok")
            ok
        }.getOrElse {
            Logger.e("WeChatNetwork: 发送失败", it); false
        }
    }
}
