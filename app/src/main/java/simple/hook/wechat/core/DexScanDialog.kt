package simple.hook.wechat.core

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * 功能适配扫描弹窗（微信进程内）。
 *
 * 参考 WeKit DexResolver：开始搜索 → 逐个搜索项 DexKit 定位 → 进度条 →
 * 完成后列出失败项 → 提示重启微信。结果写入 DexCache，后续启动直接读缓存。
 */
object DexScanDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(activity: Activity) {
        val ctx = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }

        val statusTv = TextView(ctx).apply {
            text = "搜索功能所需的类与方法（共 ${DexScanService.allItems().size} 项）"
            textSize = 15f
            setTextColor(Color.BLACK)
        }
        root.addView(statusTv)

        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = DexScanService.allItems().size
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))

        val currentTv = TextView(ctx).apply {
            text = "未开始"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(currentTv)

        val failedTv = TextView(ctx).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFFC62828.toInt())
        }
        val scroll = ScrollView(ctx).apply {
            addView(failedTv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(130)))

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("功能适配")
            .setView(root)
            .setNegativeButton("关闭") { d, _ -> d.dismiss() }
            .setPositiveButton("开始搜索", null)
            .create()
        dialog.show()

        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btn.setOnClickListener {
            btn.isEnabled = false
            statusTv.text = "搜索中..."
            val failures = StringBuilder()
            val failedList = mutableListOf<String>()
            Thread {
                try {
                    val app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? android.app.Application
                    val cl = app?.classLoader ?: return@Thread
                    DexScanService.scanAll(
                        cl,
                        onProgress = { done, total, cur ->
                            mainHandler.post {
                                progress.progress = done
                                progress.max = total
                                currentTv.text = "$cur ($done/$total)"
                            }
                        },
                        onItem = { r ->
                            if (!r.ok) {
                                failedList.add("✗ ${r.name}\n   ${r.detail}")
                                mainHandler.post {
                                    failedTv.text = failedList.joinToString("\n")
                                }
                            }
                        }
                    )
                    mainHandler.post {
                        if (failedList.isEmpty()) {
                            statusTv.text = "搜索完成! 全部命中。"
                            currentTv.text = "请重启微信使功能生效"
                        } else {
                            statusTv.text = "搜索完成, ${failedList.size} 项失败(不影响其他功能)"
                            currentTv.text = ""
                        }
                        btn.text = "重启微信"
                        btn.isEnabled = true
                        btn.setOnClickListener {
                            // 杀微信主进程, 让用户重新打开
                            runCatching {
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }
                        dialog.setOnDismissListener {
                            runCatching {
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    mainHandler.post {
                        statusTv.text = "搜索失败: ${t.message}"
                        btn.text = "关闭"
                        btn.isEnabled = true
                        btn.setOnClickListener { dialog.dismiss() }
                    }
                }
            }.start()
        }
    }

    private fun dp(v: Int): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
