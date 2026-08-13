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
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口。
 *
 * LSPosed 会为作用域内每个进程（微信主进程及所有子进程）调用一次 [handleLoadPackage]。
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"

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
            lpparam.applicationContext?.let { Prefs.init(it) }
        } catch (t: Throwable) {
            Logger.w("初始化 Prefs 失败: $t")
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
            // 子进程只处理安全的纯数据库/UI 功能（去头像在主进程做）。
            enabledFeatures
                .filterNot { it is HideAvatarFeature }
                .filter { it.isProcessSafe() }
                .forEach { feature ->
                    runCatching { feature.hook(lpparam.classLoader, null) }
                        .onFailure { Logger.e("子进程加载 ${feature.name} 失败", it) }
                }
            return
        }

        // 主进程：用 DexKitFinder 包裹，在其生命期内完成微信内部符号定位并注册 hook。
        Logger.i("主进程：开始加载功能 ${enabledFeatures.map { it.name }}")
        DexKitFinder.with(lpparam.classLoader) { finder ->
            enabledFeatures.forEach { feature ->
                runCatching { feature.hook(lpparam.classLoader, finder) }
                    .onFailure { Logger.e("加载功能 ${feature.name} 失败: $it", it) }
            }
        }
    }
}
