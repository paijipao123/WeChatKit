package simple.hook.wechat.core

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

    /**
     * 该功能是否依赖 DexKit 动态定位。
     * 依赖 DexKit 的功能只在 DexKit 可用（主进程且 native 库加载成功）时运行；
     * 不依赖的（如朋友圈防删，直接 hook 固定类名）即使在 DexKit 失败时也可运行。
     */
    fun needsDexKit(): Boolean = true
}
