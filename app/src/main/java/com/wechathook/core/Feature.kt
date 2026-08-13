package com.wechathook.core

/**
 * 功能模块统一接口。
 *
 * 每个功能实现此接口，在 [HookEntry] 的 handleLoadPackage 中按配置决定是否 [hook]。
 */
interface Feature {

    /** 功能键名（用于持久化开关配置）。 */
    val key: String

    /** 功能展示名。 */
    val name: String

    /**
     * 执行 Hook。
     * @param classLoader 宿主（微信）的 ClassLoader
     * @param finder DexKit 查找器；仅主进程提供非空实例，子进程可能为 null
     */
    fun hook(classLoader: ClassLoader, finder: DexKitFinder?)

    /** 该功能是否默认开启。 */
    fun defaultEnabled(): Boolean = false

    /**
     * 该功能是否能安全运行在微信子进程（如 :tools）。
     * 依赖 DexKit 主进程桥接或 UI 的功能应返回 false。
     */
    fun isProcessSafe(): Boolean = false
}
