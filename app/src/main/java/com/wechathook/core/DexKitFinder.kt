package com.wechathook.core

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Method

/**
 * DexKit 封装：按字符串特征在宿主(微信) dex 中动态定位类。
 *
 * 核心思路（与 WeKit/WAuxiliary 一致）：不硬编码微信内部混淆类名，而是通过
 * 稳定的特征字符串（如网络请求名 "MicroMsg.NetSceneXXX"、日志模板等）在 dex 中
 * 检索出目标类/方法，再配合 XposedHelpers 完成 Hook。这样能在微信小版本升级
 * 导致混淆变化时，仍有一定自适应能力。
 */
class DexKitFinder internal constructor(
    private val bridge: DexKitBridge
) {

    companion object {

        private val nativeLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 加载 DexKit 的 native 库 (libdexkit.so)。
         *
         * LSPosed 把模块注入宿主进程时，模块 APK 里的 .so 不一定在默认 native 搜索路径中，
         * 直接 create bridge 会抛 UnsatisfiedLinkError。这里分两步：
         * 1. 先试 System.loadLibrary("dexkit")（LSPosed 若已暴露 native 路径则直接成功）；
         * 2. 失败则从模块 APK 中提取 lib/<abi>/libdexkit.so 到宿主可写缓存目录后 System.load。
         */
        @JvmStatic
        fun loadNativeLibrary(): Boolean {
            if (nativeLoaded.get()) return true
            return try {
                System.loadLibrary("dexkit")
                nativeLoaded.set(true)
                true
            } catch (e1: Throwable) {
                try {
                    extractAndLoadDexKit()
                } catch (e2: Throwable) {
                    Logger.w("DexKitFinder: 加载 libdexkit.so 失败: $e2")
                    false
                }
            }
        }

        private fun extractAndLoadDexKit(): Boolean {
            // 1. 从 DexKitBridge 类的 CodeSource 拿模块 APK 路径
            val apkPath = try {
                DexKitBridge::class.java.protectionDomain
                    ?.codeSource?.location?.toURI()?.path
                    ?: return false
            } catch (_: Throwable) {
                return false
            }

            // 2. 选择 ABI 对应的 so 条目名
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return false
            val entryName = "lib/$abi/libdexkit.so"

            // 3. 解压到宿主缓存目录
            val outDir = hostCacheDir() ?: return false
            val outFile = java.io.File(outDir, "libdexkit.so")
            try {
                java.util.zip.ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return false
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                outFile.setExecutable(true)
                System.load(outFile.absolutePath)
                nativeLoaded.set(true)
                Logger.i("DexKitFinder: 已从 $apkPath 提取并加载 libdexkit.so")
                true
            } catch (t: Throwable) {
                Logger.w("DexKitFinder: 提取/加载 so 失败: $t")
                false
            }
        }

        /** 拿到宿主进程可写目录（优先 cacheDir，退回 /data/local/tmp）。 */
        private fun hostCacheDir(): java.io.File? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
                app?.cacheDir
            } catch (_: Throwable) {
                null
            } ?: run {
                val tmp = java.io.File("/data/local/tmp")
                if (tmp.exists() || tmp.mkdirs()) tmp else null
            }
        }

        /**
         * 创建并使用 DexKitBridge。
         * 微信的 dex 可能经过 MemoryDex 加载，[useMemoryDexFile] 设为 true 可同时检查内存 dex。
         */
        @JvmStatic
        fun with(
            classLoader: ClassLoader,
            useMemoryDexFile: Boolean = true,
            block: (DexKitFinder) -> Unit
        ) {
            if (!loadNativeLibrary()) {
                Logger.w("DexKitFinder: native 库加载失败，跳过 DexKit 相关功能")
                return
            }
            runCatching {
                DexKitBridge.create(classLoader, useMemoryDexFile)
            }.onSuccess { bridge ->
                try {
                    bridge.use { DexKitFinder(it).run(block) }
                } catch (t: Throwable) {
                    Logger.w("DexKitFinder: 执行块异常: $t")
                }
            }.onFailure {
                Logger.w("DexKitFinder: 创建 DexKitBridge 失败: $it")
            }
        }
    }

    private fun run(block: (DexKitFinder) -> Unit) {
        block(this)
    }

    /** 查找包含指定特征字符串全部/任一的类，返回匹配类的完整名字列表。 */
    fun findClassNamesByStrings(vararg strings: String, onlyPackages: List<String>? = null): List<String> {
        if (strings.isEmpty()) return emptyList()
        return runCatching {
            val s = strings.toSet()
            bridge.findClass {
                if (onlyPackages != null) {
                    val pkgs = onlyPackages
                    searchPackages(pkgs)
                }
                matcher {
                    usingStrings(s)
                }
            }.mapNotNull { cn -> runCatching { cn.name }.getOrNull() }
        }.getOrElse { emptyList() }
    }

    /** 查找包含任一特征字符串的单个类名（取第一个匹配）。 */
    fun findClassNameByStrings(vararg strings: String, onlyPackages: List<String>? = null): String? =
        findClassNamesByStrings(*strings, onlyPackages = onlyPackages).firstOrNull()

    /**
     * 查找方法：按特征字符串定位并实例化。
     * @param classLoader 用于把 MethodData 实例化
     * @param declaredClassName 若非空，限定声明类（精确名）
     * @param onlyPackages 限定搜索包前缀
     * @param strings 方法内使用的特征字符串
     * @param paramCount 若提供，限定参数个数
     */
    fun findMethodsByStrings(
        classLoader: ClassLoader,
        declaredClassName: String? = null,
        onlyPackages: List<String>? = null,
        vararg strings: String,
        paramCount: Int? = null
    ): List<Method> {
        return runCatching {
            val st = strings.toSet()
            bridge.findMethod {
                if (onlyPackages != null) {
                    val pkgs = onlyPackages
                    searchPackages(pkgs)
                }
                if (declaredClassName != null && declaredClassName.isNotEmpty()) {
                    val clsName = declaredClassName
                    searchInClass(findClassDataByName(clsName)?.let { listOf(it) } ?: emptyList())
                }
                matcher {
                    if (declaredClassName != null && declaredClassName.isNotEmpty()) {
                        declaredClass(declaredClassName)
                    }
                    if (st.isNotEmpty()) usingStrings(st)
                    if (paramCount != null) paramCount(paramCount)
                }
            }.mapNotNull { md ->
                runCatching { md.getMethodInstance(classLoader) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun findClassDataByName(name: String): ClassData? {
        return runCatching {
            bridge.findClass {
                matcher { className(name) }
            }.firstOrNull()
        }.getOrNull()
    }

    /**
     * 按"类内声明方法使用指定特征字符串"定位类。
     * 用于定位 NetSceneQueue 等网络队列类。
     */
    fun findClassNamesByMethodStrings(
        vararg methodStrings: String,
        methodParamCount: Int? = null
    ): List<String> {
        return runCatching {
            val ms = methodStrings.toSet()
            bridge.findClass {
                matcher {
                    methods {
                        add {
                            if (methodParamCount != null) paramCount(methodParamCount)
                            if (ms.isNotEmpty()) usingStrings(ms)
                        }
                    }
                }
            }.mapNotNull { runCatching { it.name }.getOrNull() }
        }.getOrElse { emptyList() }
    }
}
