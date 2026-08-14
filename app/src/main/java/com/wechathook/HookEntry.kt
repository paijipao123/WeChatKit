package com.wechathook

import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Prefs
import com.wechathook.features.chat.AntiRecallFeature
import com.wechathook.features.chat.HideAvatarFeature
import com.wechathook.features.money.AutoCollectTransferFeature
import com.wechathook.features.money.AutoRedPacketFeature
import com.wechathook.features.moments.AntiMomentsDeleteFeature
import com.wechathook.features.readreceipt.ReadReceiptFeature
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口。
 *
 * LSPosed 会为作用域内每个进程（微信主进程及所有子进程）调用一次 [handleLoadPackage]。
 *
 * 注意：handleLoadPackage 在 Application 创建之前执行，此时宿主进程还没有可用的
 * Context（ActivityThread.currentApplication() 为 null），而 DexKit 的 native 库
 * 需要从模块 APK 提取（需要 PackageManager 查询模块包路径）。因此：
 * - 不依赖 DexKit 的功能（朋友圈防删）在 handleLoadPackage 直接 hook；
 * - 依赖 DexKit 的功能延迟到微信 Application.onCreate 之后再加载。
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"

        /** 依赖 DexKit 的功能是否已加载（防止重复）。 */
        private val dexLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 所有功能实例。 */
        val FEATURES: List<Feature> = listOf(
            HideAvatarFeature,
            AntiRecallFeature,
            AntiMomentsDeleteFeature,
            ReadReceiptFeature,
            AutoRedPacketFeature,
            AutoCollectTransferFeature,
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG_WECHAT) return

        try {
            Prefs.reload()
        } catch (t: Throwable) {
            Logger.w("加载 Prefs 失败: $t")
        }

        Logger.i("已加载到进程: ${lpparam.processName}")

        // 关键：微信使用 Tinker 热修复，补丁在 Application.onCreate 阶段才应用完成。
        // 所有微信类（wcdb、UI、业务类）都可能被补丁替换，因此这里不立即 hook 任何类，
        // 统一延迟到 Application.onCreate 之后、用真实 classLoader（补丁版）进行 hook。
        hookApplicationOnCreate(lpparam)
    }

    /** Hook 微信 Application.onCreate，在其后用真实 classLoader 加载所有功能。 */
    private fun hookApplicationOnCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 微信不同版本的主 Application 类名可能不同（8.0.71 已不是 MMApplication），
        // 直接 hook 基类 android.app.Application.onCreate：所有 Application 子类都会触发。
        runCatching {
            XposedBridge.hookAllMethods(android.app.Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    loadFeatures(lpparam)
                }
            })
            Logger.i("已挂载 Application.onCreate 延迟加载")
        }.onFailure {
            Logger.e("挂载 Application.onCreate 失败: $it")
            // 兜底：直接尝试加载（若 ActivityThread 已有 app 也能成功）
            loadFeatures(lpparam)
        }
    }

    private fun loadFeatures(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 防止重复加载（onCreate 可能被调用多次）
        if (dexLoaded.get()) return
        dexLoaded.set(true)

        val isMain = lpparam.processName == PKG_WECHAT

        // 关键：微信使用 Tinker 热修复。补丁应用后，微信的类由 Tinker classLoader 加载，
        // 而 lpparam.classLoader 是原始 PathClassLoader —— 用它 hook 挂到的是"旧" Class 对象，
        // 运行时微信用的是补丁版 Class，导致 hook 全部失效（表现为功能全无）。
        // 因此必须用 Application 的实际 classLoader（补丁应用后即 Tinker classLoader）来 hook。
        val realLoader = try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
            app?.classLoader ?: lpparam.classLoader
        } catch (_: Throwable) {
            lpparam.classLoader
        }
        Logger.i("类加载器: real=${realLoader.javaClass.name}, lpparam=${lpparam.classLoader.javaClass.name}")

        // Application 已创建，此时能读到宿主（微信）prefs 里的真实配置
        try {
            Prefs.reload()
        } catch (t: Throwable) {
            Logger.w("延迟加载 Prefs 失败: $t")
        }
        com.wechathook.core.SymbolResolver.detectWechatVersion()

        val enabled = FEATURES.filter { feature ->
            Prefs.getBoolean("feat_${feature.key}", feature.defaultEnabled())
        }
        Logger.i("${if (isMain) "主进程" else "子进程"} 启用功能: ${enabled.map { it.name }}")

        if (!isMain) {
            // 子进程只处理安全的纯数据库功能
            enabled
                .filterNot { it is HideAvatarFeature }
                .filter { it.isProcessSafe() }
                .forEach { feature ->
                    runCatching { feature.hook(realLoader, null) }
                        .onFailure { Logger.e("子进程加载 ${feature.name} 失败", it) }
                }
            return
        }

        // 主进程：挂载微信设置页注入器（把模块入口藏进微信设置）
        runCatching {
            com.wechathook.core.SettingsInjector.hook(realLoader)
        }.onFailure { Logger.e("设置页注入器挂载失败: $it") }

        // 非 DexKit 功能（朋友圈防删）不依赖 DexKit，直接 hook
        enabled.filterNot { it.needsDexKit() }.forEach { feature ->
            runCatching { feature.hook(realLoader, null) }
                .onFailure { Logger.e("加载功能 ${feature.name} 失败: $it", it) }
        }

        // 依赖 DexKit 的功能
        DexKitFinder.with(realLoader) { finder ->
            enabled.filter { it.needsDexKit() }.forEach { feature ->
                runCatching { feature.hook(realLoader, finder) }
                    .onFailure { Logger.e("加载功能 ${feature.name} 失败: $it", it) }
            }
        }
    }
}
