package com.wechathook.features.moments

import android.content.ContentValues
import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 朋友圈防删。
 *
 * 原理（已按微信 8.0.71 的 dex 核实）：微信删除朋友圈时，会对 SQLite 表 `SnsInfo`
 * 执行 **DELETE**（如 `DELETE FROM SnsInfo where ...`，classes6.dex 中可见多个删除 SQL），
 * 而非 update 改 sourceType。因此：
 * - 主策略：hook WCDB 的 `delete(String table, String whereClause, String[] whereArgs)`，
 *   当目标是 `SnsInfo` 时阻止删除（setResult(0) 表示删除 0 行），保留本地记录；
 * - 兼容策略：保留 updateWithOnConflict 拦截（旧版本微信可能走 update）。
 *
 * 注意：该策略会同时阻止"自己删除朋友圈"（本地删除也会走同一条 delete 路径），
 * 如需自己删除仍生效，可在设置中关闭本功能后操作。
 */
object AntiMomentsDeleteFeature : Feature {

    override val key = "anti_moments_delete"
    override val name = "朋友圈防删"

    /** 朋友圈表名 */
    private const val TBL_SNS = "SnsInfo"

    private val enable: Boolean get() = Prefs.getBoolean("anti_moments_delete_enable", true)

    override fun defaultEnabled() = true

    // 直接 hook 固定类名 (WCDB SQLiteDatabase)，不依赖 DexKit 动态定位
    override fun isProcessSafe() = true

    override fun needsDexKit() = false

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return

        Logger.i("[$name] 开始 Hook")

        val candidates = listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.compat.SQLiteDatabase"
        )

        candidates.forEach { className ->
            runCatching {
                hookDatabaseDelete(className, classLoader)
                hookDatabaseUpdate(className, classLoader)
                Logger.i("[$name] 已监听 $className (delete/update)")
            }.onFailure { Logger.e("[$name] Hook $className 失败: $it") }
        }
    }

    /** hook delete：拦截 SnsInfo 表的删除。 */
    private fun hookDatabaseDelete(className: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(className, classLoader)
        XposedBridge.hookAllMethods(clazz, "delete", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // WCDB delete 签名: delete(String table, String whereClause, String[] whereArgs)
                    // 或 delete(String table, String whereClause, Object[] whereArgs)
                    val table = param.args[0] as? String ?: return
                    if (table != TBL_SNS) return

                    val whereClause = param.args.getOrNull(1) as? String
                    Logger.i("[$name] 拦截到 SnsInfo 删除! where=$whereClause")
                    // 阻止删除：返回 0（删除 0 行）
                    param.setResult(0)
                } catch (t: Throwable) {
                    Logger.e("[$name] delete 拦截异常: $t")
                }
            }
        })
    }

    /** 兼容：hook update（旧版本微信可能走 update 标记删除）。 */
    private fun hookDatabaseUpdate(className: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(className, classLoader)
        runCatching {
            XposedBridge.hookAllMethods(clazz, "updateWithOnConflict", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val table = param.args[0] as? String ?: return
                    if (table != TBL_SNS) return
                    val values = param.args[1] as? ContentValues ?: return
                    val sourceType = values.getAsInteger("sourceType")
                    if (sourceType != null && sourceType != 0) {
                        Logger.i("[$name] 拦截到 SnsInfo update 删除标记 (sourceType=$sourceType)")
                        values.remove("sourceType")
                    }
                }
            })
        }
    }
}
