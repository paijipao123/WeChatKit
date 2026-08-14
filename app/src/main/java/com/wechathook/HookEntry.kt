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

        // 加载配置（读取模块自身 data 目录下的 prefs 文件）
        try {
            Prefs.reload()
        } catch (t: Throwable) {
            Logger.w("加载 Prefs 失败: $t")
        }

        Logger.i("已加载到进程: ${lpparam.processName}")
        val isMain = lpparam.processName == PKG_WECHAT

        val enabledFeatures = FEATURES.filter { feature ->
            Prefs.getBoolean("feat_${feature.key}", feature.defaultEnabled())
        }

        if (enabledFeatures.isEmpty()) {
            Logger.i("没有已启用的功能，跳过 Hook。")
            return
        }

        if (!isMain) {
            // 子进程只处理安全的纯数据库功能
            enabledFeatures
                .filterNot { it is HideAvatarFeature }
                .filter { it.isProcessSafe() }
                .forEach { feature ->
                    runCatching { feature.hook(lpparam.classLoader, null) }
                        .onFailure { Logger.e("子进程加载 ${feature.name} 失败", it) }
                }
            return
        }

        Logger.i("主进程：开始加载功能 ${enabledFeatures.map { it.name }}")

        // 1) 不依赖 DexKit 的功能立即加载
        enabledFeatures.filterNot { it.needsDexKit() }.forEach { feature ->
            runCatching { feature.hook(lpparam.classLoader, null) }
                .onFailure { Logger.e("加载功能 ${feature.name} 失败: $it", it) }
        }

        // 2) 依赖 DexKit 的功能延迟到 Application.onCreate 后（确保 Context 可用）
        val dexFeatures = enabledFeatures.filter { it.needsDexKit() }
        if (dexFeatures.isEmpty()) return

        hookApplicationOnCreate(lpparam, dexFeatures)
    }

    /** Hook 微信 Application.onCreate，在其后加载依赖 DexKit 的功能。 */
    private fun hookApplicationOnCreate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        dexFeatures: List<Feature>
    ) {
        runCatching {
            // 微信主 Application 类（8.0.x 均为 com.tencent.mm.app.MMApplication，
            // 失败时退化为 hook 所有 Application 子类的 onCreate）
            val appClass = XposedHelpers.findClass(
                "com.tencent.mm.app.MMApplication", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    loadDexFeatures(lpparam, dexFeatures)
                }
            })
            Logger.i("已挂载 MMApplication.onCreate 延迟加载")
        }.onFailure {
            Logger.e("挂载 Application.onCreate 失败: $it")
            // 兜底：直接尝试加载（若 ActivityThread 已有 app 也能成功）
            loadDexFeatures(lpparam, dexFeatures)
        }
    }

    private fun loadDexFeatures(
        lpparam: XC_LoadPackage.LoadPackageParam,
        dexFeatures: List<Feature>
    ) {
        // 防止重复加载（onCreate 可能被调用多次）
        if (dexLoaded.get()) return
        dexLoaded.set(true)

        DexKitFinder.with(lpparam.classLoader) { finder ->
            dexFeatures.forEach { feature ->
                runCatching { feature.hook(lpparam.classLoader, finder) }
                    .onFailure { Logger.e("加载功能 ${feature.name} 失败: $it", it) }
            }
        }
    }
}
