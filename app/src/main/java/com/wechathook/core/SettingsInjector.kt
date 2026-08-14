package com.wechathook.core

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 微信设置页注入器：把模块入口藏进微信自己的设置页。
 *
 * 参考 WeKit 的 WeSettingInjector，适配 8.0.71 / 8.0.76：
 * - 新版 (8.0.67+, MainSettingsUI)：hook MMActivity.onCreateOptionsMenu，
 *   在设置页标题栏菜单注入 "WeChatKit 设置" 入口，点击打开模块设置页；
 * - 旧版 (8.0.71 也有 SettingsUI)：hook SettingsUI.initView，
 *   往 PreferenceScreen 插入 IconPreference 条目，点击打开模块设置页。
 *
 * 点击入口后跨包启动模块自己的 MainActivity（不依赖微信 UI 框架）。
 */
object SettingsInjector {

    private const val TAG = "SettingsInjector"

    private const val MENU_ID_WEKIT = 0x5A5A11  // 自定义菜单 ID

    private const val KEY_ENTRY = "wechathook_settings_entry"
    private const val TITLE_ENTRY = "WeChatKit 设置"

    // 微信类名（已按 8.0.71/8.0.76 dex 验证）
    private const val CLS_MAIN_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
    private const val CLS_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
    private const val CLS_MM_ACTIVITY = "com.tencent.mm.ui.MMActivity"
    // 8.0.71/8.0.76: MainSettingsUI 走 MVVM 架构 (BaseSettingPrefUI->BaseSettingUI->BaseMvvmActivity->VASActivity),
    // VASActivity override 了 onCreateOptionsMenu/onPrepareOptionsMenu, 直接 hook MMActivity 的方法不会触发
    private const val CLS_VAS_ACTIVITY = "com.tencent.mm.ui.vas.VASActivity"
    // MainSettingsUI 继承 BaseSettingPrefUI（它声明了 onCreate），页面创建必经，作为浮动按钮的可靠注入点
    private const val CLS_BASE_SETTING_PREF_UI = "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
    private const val CLS_ICON_PREFERENCE = "com.tencent.mm.ui.base.preference.IconPreference"
    private const val CLS_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference"

    /** 已绑定标志 */
    private val bound = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 已注入浮动按钮的 Activity（按实例去重，避免退出重进后按钮丢失） */
    private val injectedActivities = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    )

    /** 在微信主进程调用一次。 */
    fun hook(classLoader: ClassLoader) {
        if (bound.getAndSet(true)) return

        // 1. 新版设置页 (8.0.67+): MainSettingsUI 菜单/浮动按钮注入
        hookNewSettings(classLoader)

        // 2. 旧版设置页兼容: SettingsUI PreferenceScreen 注入
        hookLegacySettings(classLoader)

        Logger.i("[$TAG] 设置页注入器已挂载")
    }

    // ============ 新版: MainSettingsUI 注入 ============

    private fun hookNewSettings(classLoader: ClassLoader) {
        try {
            val clsMainSettingsUI = XposedHelpers.findClass(CLS_MAIN_SETTINGS_UI, classLoader)

            // ---- 方案 A: 标题栏菜单注入（多路: VASActivity 覆盖了菜单方法, MMActivity 兜底） ----
            val menuClsNames = listOf(CLS_VAS_ACTIVITY, CLS_MM_ACTIVITY)
            for (clsName in menuClsNames) {
                val cls = runCatching { XposedHelpers.findClass(clsName, classLoader) }.getOrNull() ?: continue
                runCatching {
                    XposedBridge.hookAllMethods(cls, "onCreateOptionsMenu", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = param.thisObject as? Activity ?: return
                                if (activity.javaClass.name != CLS_MAIN_SETTINGS_UI) return
                                val menu = param.args[0] as? Menu ?: return
                                if (menu.findItem(MENU_ID_WEKIT) == null) {
                                    menu.add(0, MENU_ID_WEKIT, 0, TITLE_ENTRY)
                                    Logger.i("[$TAG] 已注入设置菜单入口 (${clsName})")
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                }.onFailure { }
                runCatching {
                    XposedBridge.hookAllMethods(cls, "onPrepareOptionsMenu", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = param.thisObject as? Activity ?: return
                                if (activity.javaClass.name != CLS_MAIN_SETTINGS_UI) return
                                val menu = param.args[0] as? Menu ?: return
                                if (menu.findItem(MENU_ID_WEKIT) == null) {
                                    menu.add(0, MENU_ID_WEKIT, 0, TITLE_ENTRY)
                                    Logger.i("[$TAG] 已注入设置菜单入口 (${clsName}/prepare)")
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                }.onFailure { }
                runCatching {
                    XposedBridge.hookAllMethods(cls, "onOptionsItemSelected", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = param.thisObject as? Activity ?: return
                                if (activity.javaClass.name != CLS_MAIN_SETTINGS_UI) return
                                val item = param.args[0] as? MenuItem ?: return
                                if (item.itemId == MENU_ID_WEKIT) {
                                    openModuleSettings(activity)
                                    param.setResult(true)
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                }.onFailure { }
            }
            Logger.i("[$TAG] 新版设置页菜单注入已挂载")

            // ---- 方案 B: 浮动按钮兜底（MVVM 设置页若无系统菜单, 直接注入可见按钮） ----
            // 两个注入点：BaseSettingPrefUI.onCreate（页面创建必经，最可靠）+ superImportUIComponents（兼容）
            val clsBasePrefUI = runCatching { XposedHelpers.findClass(CLS_BASE_SETTING_PREF_UI, classLoader) }.getOrNull()
            if (clsBasePrefUI != null) {
                runCatching {
                    XposedBridge.hookAllMethods(clsBasePrefUI, "onCreate", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = param.thisObject as? Activity ?: return
                                if (activity.javaClass.name != CLS_MAIN_SETTINGS_UI) return
                                injectFloatingButton(activity)
                            } catch (_: Throwable) {}
                        }
                    })
                    Logger.i("[$TAG] 新版设置页 onCreate 注入点已挂载")
                }.onFailure { }
            }
            runCatching {
                XposedBridge.hookAllMethods(clsMainSettingsUI, "superImportUIComponents", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as? Activity ?: return
                            injectFloatingButton(activity)
                        } catch (_: Throwable) {}
                    }
                })
                Logger.i("[$TAG] 新版设置页浮动按钮兜底已挂载")
            }.onFailure { }
        } catch (t: Throwable) {
            Logger.w("[$TAG] 新版设置页注入失败: $t")
        }
    }

    /** 往设置页右上角注入一个悬浮入口按钮（不依赖微信菜单系统）。 */
    private fun injectFloatingButton(activity: Activity) {
        if (!injectedActivities.add(activity)) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val decor = activity.window?.decorView as? android.widget.FrameLayout ?: return@post
                val btn = android.widget.TextView(activity)
                btn.text = "⚙️"
                btn.textSize = 16f
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.gravity = android.view.Gravity.CENTER
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xE6008577.toInt())
                }
                btn.background = bg
                val pad = (10 * activity.resources.displayMetrics.density).toInt()
                btn.setPadding(pad, pad, pad, pad)
                btn.setOnClickListener { openModuleSettings(activity) }
                val lp = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP or android.view.Gravity.END
                )
                val m = (8 * activity.resources.displayMetrics.density).toInt()
                lp.setMargins(0, m, m, 0)
                decor.addView(btn, lp)
                Logger.i("[$TAG] 已注入设置页浮动按钮")
            } catch (t: Throwable) {
                Logger.w("[$TAG] 注入浮动按钮失败: $t")
            }
        }
    }

    // ============ 旧版: SettingsUI PreferenceScreen 注入 ============

    private fun hookLegacySettings(classLoader: ClassLoader) {
        try {
            val clsSettingsUI = XposedHelpers.findClass(CLS_SETTINGS_UI, classLoader)

            // hook initView: 往 PreferenceScreen 插入条目
            XposedBridge.hookAllMethods(clsSettingsUI, "initView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return

                        // 构造 IconPreference
                        val clsIconPref = XposedHelpers.findClass(CLS_ICON_PREFERENCE, classLoader)
                        val pref = XposedHelpers.newInstance(clsIconPref, activity)
                        XposedHelpers.callMethod(pref, "setKey", KEY_ENTRY)
                        XposedHelpers.callMethod(pref, "setTitle", TITLE_ENTRY)

                        // 添加到 PreferenceScreen
                        val prefScreen = XposedHelpers.callMethod(activity, "getPreferenceScreen")
                        val addPref = prefScreen.javaClass.declaredMethods.firstOrNull { m ->
                            m.parameterCount == 2 &&
                                m.parameterTypes[0].name == CLS_PREFERENCE &&
                                m.parameterTypes[1] == Int::class.javaPrimitiveType
                        }
                        if (addPref != null) {
                            addPref.isAccessible = true
                            addPref.invoke(prefScreen, pref, 0)
                            Logger.i("[$TAG] 已注入旧版设置条目")
                        }
                    } catch (_: Throwable) {}
                }
            })

            // hook onPreferenceTreeClick: 处理点击
            XposedBridge.hookAllMethods(clsSettingsUI, "onPreferenceTreeClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        val preference = param.args.getOrNull(1) ?: return
                        val key = runCatching {
                            XposedHelpers.callMethod(preference, "getKey") as? String
                        }.getOrNull()
                        if (key == KEY_ENTRY) {
                            openModuleSettings(activity)
                            param.setResult(true)
                        }
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$TAG] 旧版设置页注入成功 (SettingsUI)")
        } catch (t: Throwable) {
            Logger.w("[$TAG] 旧版设置页注入失败(可能不存在, 忽略): $t")
        }
    }

    /** 跨包打开模块自己的设置页。 */
    private fun openModuleSettings(activity: Activity) {
        try {
            val intent = android.content.Intent()
            intent.setClassName("com.wechathook", "com.wechathook.ui.MainActivity")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            Logger.i("[$TAG] 已打开模块设置页")
        } catch (t: Throwable) {
            Logger.e("[$TAG] 打开设置页失败: $t")
        }
    }
}
