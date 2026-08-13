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
 * 朋友圈防删（防他人删除 / 防被检测）。
 *
 * 原理（参考 WeKit/WAuxiliary）：微信把朋友圈信息存在 SQLite 表 `SnsInfo` 中。
 * 当对方/自己删除某条朋友圈时，微信会对该记录执行 `update`，把 `sourceType`
 * 标记为已删除（或直接清洗 content）。我们在数据库 update 的 before 阶段拦截，
 * 当目标是 `SnsInfo` 且 values 里带有删除标记时，重置 `sourceType` 为 0，从而
 * 阻止删除痕迹、保留原内容。
 *
 * Hook 点：微信使用 WCDB 数据库，相关类为
 * `com.tencent.wcdb.database.SQLiteDatabase` / `com.tencent.wcdb.compat.SQLiteDatabase`，
 * 方法 `updateWithOnConflict(String, ContentValues, String, Array<String>, Int)`。
 */
object AntiMomentsDeleteFeature : Feature {

    override val key = "anti_moments_delete"
    override val name = "朋友圈防删"

    /** 朋友圈表名 */
    private const val TBL_SNS = "SnsInfo"

    /** 正常朋友圈的 sourceType 值（=0 表示普通发表） */
    private const val SOURCE_NORMAL = 0

    private val enable: Boolean get() = Prefs.getBoolean("anti_moments_delete_enable", true)

    /** 是否在拦截到删除时，在 content 中注入"已拦截"水印（可选） */
    private val injectMarker: Boolean get() = Prefs.getBoolean("anti_moments_delete_marker", false)

    override fun defaultEnabled() = true

    // 朋友圈数据库在微信主进程；子进程一般不做数据库写入，标记为安全即可
    override fun isProcessSafe() = true

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!enable) return

        Logger.i("[$name] 开始 Hook")

        val candidates = listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.compat.SQLiteDatabase"
        )

        var hooked = false
        candidates.forEach { className ->
            runCatching {
                hookDatabaseUpdate(className, classLoader)
                hooked = true
                Logger.i("[$name] 已监听 $className#updateWithOnConflict")
            }.onFailure { Logger.e("[$name] Hook $className 失败: $it") }
        }

        if (!hooked) {
            // 兜底：尝试用 DexKit 找出 WCDB 的 SQLiteDatabase（按方法签名特征不易，仅尝试常见类名）
            Logger.w("[$name] 未找到标准 WCDB 类，本功能可能不可用（微信版本异常）。")
        }
    }

    private fun hookDatabaseUpdate(className: String, classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(className, classLoader)

        // 主方法签名
        runCatching {
            XposedBridge.hookAllMethods(clazz, "updateWithOnConflict", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val table = param.args[0] as? String ?: return
                    if (table != TBL_SNS) return
                    val values = param.args[1] as? ContentValues ?: return
                    handleSnsUpdate(values)
                }
            })
        }
    }

    private fun handleSnsUpdate(values: ContentValues) {
        try {
            val type = values.getAsInteger("type") ?: return
            // 仅处理朋友圈类型（与 WeKit 一致：type 为有效的朋友圈内容类型）
            if (!isSnsContentType(type)) return

            val sourceType = values.getAsInteger("sourceType")
            // 微信删除朋友圈会 update sourceType 为非 0（删除/迁移标记）
            if (sourceType != null && sourceType != SOURCE_NORMAL) {
                Logger.i("[$name] 拦截到朋友圈删除（sourceType=$sourceType），已阻止。")
                // 清除删除标记
                values.remove("sourceType")

                // 可选：注入"已拦截删除"水印，让删除后内容仍可辨识
                if (injectMarker) {
                    val content = values.getAsByteArray("content")
                    if (content != null) {
                        // 简单附加标记（由于 content 为 protobuf，不强行破坏，仅记录日志）
                        Logger.i("[$name] (水印注入在此版本保持轻量，未写入 protobuf 以防损坏。)")
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e("[$name] 处理 SnsInfo update 异常: $t")
        }
    }

    private fun isSnsContentType(type: Int): Boolean {
        // 朋友圈常见的 type 值集合（取自 WeKit MomentsContentType 的思路，做宽松判断）
        // 简化：SnsInfo 的 type 通常落在 [1, 1000] 区间，且非系统保留值。
        return type in 1..1000
    }
}
