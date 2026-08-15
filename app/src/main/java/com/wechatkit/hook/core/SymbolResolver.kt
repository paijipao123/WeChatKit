package com.wechatkit.hook.core

import de.robv.android.xposed.XposedHelpers

/**
 * 多路符号解析器：实现"从最新版向下兼容"的核心。
 *
 * 微信每个版本的内部类名/方法名都可能变化（混淆），但稳定的特征字符串
 * （日志 TAG、cgi 路径、协议名等）往往跨版本保留。本工具为每个需要定位的
 * 符号提供**候选链**，逐个尝试，第一个命中的即用：
 *
 * 1. 首选 DexKit 特征字符串定位（最可靠，跨版本）；
 * 2. 回退硬编码类名（老版本已知类名）；
 * 3. 再回退更宽松的特征。
 *
 * 同时提供微信版本检测，便于按版本差异化适配与调试。
 */
object SymbolResolver {

    /** 微信包名 */
    private const val PKG_WECHAT = "com.tencent.mm"

    /** 缓存的微信版本名（如 "8.0.71"）。 */
    @Volatile
    var wechatVersionName: String = "unknown"
        private set

    /** 缓存的微信版本号（如 2650xxx）。 */
    @Volatile
    var wechatVersionCode: Long = 0
        private set

    /** 是否已检测版本。 */
    private val versionChecked = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 检测微信版本（在微信进程内调用，用宿主 context）。 */
    fun detectWechatVersion() {
        if (versionChecked.getAndSet(true)) return
        try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
                ?: return
            val info = app.packageManager.getPackageInfo(PKG_WECHAT, 0)
            wechatVersionName = info.versionName ?: "unknown"
            wechatVersionCode = info.versionCode.toLong()
            Logger.i("检测到微信版本: $wechatVersionName (code=$wechatVersionCode)")
        } catch (t: Throwable) {
            Logger.w("微信版本检测失败: $t")
        }
    }

    /**
     * 通过 DexKit 按特征字符串找类，多个特征组逐个尝试。
     * @return 第一个命中的类名；全部失败返回 null
     */
    fun findClassByFeatureGroups(
        finder: DexKitFinder,
        vararg featureGroups: Array<out String>
    ): String? {
        for (group in featureGroups) {
            val names = finder.findClassNamesByStrings(*group)
            if (names.isNotEmpty()) {
                Logger.i("SymbolResolver: 特征组 ${group.joinToString("/")} -> ${names.first()}")
                return names.first()
            }
        }
        return null
    }

    /**
     * 在多个候选类名中找第一个真实存在的类（硬编码类名回退）。
     * @return 类名或 null
     */
    fun findExistingClass(
        classLoader: ClassLoader,
        vararg candidateNames: String
    ): String? {
        for (name in candidateNames) {
            try {
                XposedHelpers.findClass(name, classLoader)
                Logger.i("SymbolResolver: 候选类命中: $name")
                return name
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * 综合定位：先 DexKit 特征组，再硬编码候选类名。
     */
    fun resolveClass(
        classLoader: ClassLoader,
        finder: DexKitFinder?,
        featureGroups: Array<Array<out String>>,
        candidateNames: Array<out String>
    ): String? {
        // 1. DexKit 特征组
        if (finder != null) {
            for (group in featureGroups) {
                val names = finder.findClassNamesByStrings(*group)
                if (names.isNotEmpty()) {
                    Logger.i("SymbolResolver: 特征定位 ${group.joinToString("/")} -> ${names.first()}")
                    return names.first()
                }
            }
        }
        // 2. 硬编码候选
        return findExistingClass(classLoader, *candidateNames)
    }
}
