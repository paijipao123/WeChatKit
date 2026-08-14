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

    // 微信类名（已按 8.0.76 dex 验证）
    private const val CLS_MAIN_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
    private const val CLS_SETTINGS_UI = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
    private const val CLS_MM_ACTIVITY = "com.tencent.mm.ui.MMActivity"
    private const val CLS_ICON_PREFERENCE = "com.tencent.mm.ui.base.preference.IconPreference"
    private const val CLS_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference"

    /** 已绑定标志 */
    private val bound = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 在微信主进程调用一次。 */
    fun hook(classLoader: ClassLoader) {
        if (bound.getAndSet(true)) return

        // 1. 新版设置页 (8.0.67+): MainSettingsUI 标题栏菜单注入
        hookNewSettings(classLoader)

        // 2. 旧版设置页兼容: SettingsUI PreferenceScreen 注入
        hookLegacySettings(classLoader)

        Logger.i("[$TAG] 设置页注入器已挂载")
    }

    // ============ 新版: MainSettingsUI 菜单注入 ============

    private fun hookNewSettings(classLoader: ClassLoader) {
        try {
            // 验证 MainSettingsUI 存在
            XposedHelpers.findClass(CLS_MAIN_SETTINGS_UI, classLoader)
            val clsMMActivity = XposedHelpers.findClass(CLS_MM_ACTIVITY, classLoader)

            // hook MMActivity.onCreateOptionsMenu: 在设置页菜单注入入口
            XposedBridge.hookAllMethods(clsMMActivity, "onCreateOptionsMenu", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject
                        if (activity.javaClass.name != CLS_MAIN_SETTINGS_UI) return
                        val menu = param.args[0] as? Menu ?: return
                        if (menu.findItem(MENU_ID_WEKIT) == null) {
                            menu.add(0, MENU_ID_WEKIT, 0, TITLE_ENTRY)
                            Logger.i("[$TAG] 已注入新版设置菜单入口")
                        }
                    } catch (_: Throwable) {}
                }
            })

            // hook MMActivity.onOptionsItemSelected: 处理点击
            XposedBridge.hookAllMethods(clsMMActivity, "onOptionsItemSelected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.javaClass.name != CLS_MAIN_SETTINGS_UI) return
                        val item = param.args[0] as? MenuItem ?: return
                        if (item.itemId == MENU_ID_WEKIT) {
                            openModuleSettings(activity)
                            param.result = true
                        }
                    } catch (_: Throwable) {}
                }
            })

            Logger.i("[$TAG] 新版设置页注入成功 (MainSettingsUI)")
        } catch (t: Throwable) {
            Logger.w("[$TAG] 新版设置页注入失败: $t")
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
                            param.result = true
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
