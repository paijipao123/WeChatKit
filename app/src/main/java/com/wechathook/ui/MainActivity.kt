package com.wechathook.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.wechathook.core.Prefs
import com.wechathook.core.SymbolResolver
import com.wechathook.features.chat.AntiRecallFeature
import com.wechathook.features.chat.HideAvatarFeature
import com.wechathook.features.money.AutoCollectTransferFeature
import com.wechathook.features.money.AutoRedPacketFeature
import com.wechathook.features.moments.AntiMomentsDeleteFeature
import com.wechathook.features.readreceipt.ReadReceiptFeature

/**
 * 模块设置页（Material3 风格）。
 *
 * 使用 MaterialCardView 卡片分组 + MaterialSwitch 开关，
 * 提供现代化观感。支持从模块 App 或微信设置入口打开。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求存储权限（配置存于 /sdcard/WeChatKit/，需要读写外部存储）
        requestStoragePermission()

        // 读取模块配置
        Prefs.reload()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // ---- 头部 ----
        root.addView(header())

        // ---- 基础功能卡片 ----
        root.addView(card(
            "💬 基础功能",
            listOf(
                SwitchItem("隐藏消息头像（紧凑）",
                    "只去左右头像，气泡间距与微信原版一致",
                    "feat_${HideAvatarFeature.key}", HideAvatarFeature.defaultEnabled()),
                SwitchItem("聊天防撤回",
                    "对方撤回的消息保留显示",
                    "feat_${AntiRecallFeature.key}", AntiRecallFeature.defaultEnabled()),
                SwitchItem("朋友圈防删",
                    "阻止他人删除朋友圈后消失",
                    "feat_${AntiMomentsDeleteFeature.key}", AntiMomentsDeleteFeature.defaultEnabled()),
            )
        ))

        // ---- 红包转账卡片 ----
        root.addView(card(
            "🧧 红包 / 转账",
            listOf(
                SwitchItem("自动抢红包",
                    "后台自动拆包/开包（有封号风险）",
                    "feat_${AutoRedPacketFeature.key}", AutoRedPacketFeature.defaultEnabled()),
                SwitchItem("自动收款（转账）",
                    "后台自动确认收款（有封号风险）",
                    "feat_${AutoCollectTransferFeature.key}", AutoCollectTransferFeature.defaultEnabled()),
            )
        ))

        // ---- 已读回执卡片 ----
        root.addView(card(
            "👁 已读回执",
            listOf(
                SwitchItem("已读回执",
                    "配合 read-receipt-tracker 服务显示\"已读 X 人\"",
                    "feat_${ReadReceiptFeature.key}", ReadReceiptFeature.defaultEnabled()),
            )
        ))

        // ---- 参数设置卡片 ----
        root.addView(paramCard())

        // ---- 底部操作 ----
        root.addView(restartButton())

        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    // ============ UI 组件 ============

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 头部：标题 + 版本。 */
    private fun header(): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(20), dp(8), dp(16))
        }
        // 图标色块
        val icon = TextView(this).apply {
            text = "W"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.color.transparent)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF00897B.toInt())
            }
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(16)
            }
        }
        head.addView(icon)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "WeChatKit"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F1F1F.toInt())
        })
        col.addView(TextView(this).apply {
            text = "微信增强模块 · v${SymbolResolver.wechatVersionName}"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        head.addView(col)
        return head
    }

    data class SwitchItem(val title: String, val desc: String, val prefKey: String, val default: Boolean)

    /** 分组卡片。 */
    private fun card(title: String, items: List<SwitchItem>): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF00897B.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        items.forEachIndexed { idx, item ->
            if (idx > 0) {
                col.addView(divider())
            }
            col.addView(switchRow(item))
        }
        card.addView(col)
        return card
    }

    /** 开关行。 */
    private fun switchRow(item: SwitchItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            setTextColor(0xFF1F1F1F.toInt())
        })
        textCol.addView(TextView(this).apply {
            text = item.desc
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        val sw = MaterialSwitch(this).apply {
            isChecked = Prefs.getBoolean(item.prefKey, item.default)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBoolean(item.prefKey, checked)
            }
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return row
    }

    /** 参数输入卡片。 */
    private fun paramCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        col.addView(TextView(this).apply {
            text = "⚙️ 参数设置"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF00897B.toInt())
            setPadding(0, 0, 0, dp(8))
        })

        // 自定义撤回提示
        col.addView(textInputLabel("自定义撤回提示"))
        col.addView(textInput("anti_recall_notice", "例如: 「{sender}」撤回了一条消息（已拦截）",
            "支持占位符: {sender} 发送者, {content} 消息内容, {time} 撤回时间"))

        // 已读回执服务器
        col.addView(textInputLabel("已读回执服务器地址"))
        col.addView(textInput("read_receipt_server", "例如 http://192.168.1.10:8080"))

        // 拆包延迟
        col.addView(textInputLabel("拆包延迟 (毫秒)"))
        col.addView(textInput("auto_redpacket_delay", "默认 800"))

        // 屏蔽名单（红包/转账）
        col.addView(textInputLabel("屏蔽名单（自动抢红包/转账跳过的人）"))
        col.addView(textInput("blocked_talkers", "用逗号分隔昵称或微信号, 例如: 张三,李四",
            "输入后重启微信生效"))

        card.addView(col)
        return card
    }

    private fun textInputLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF757575.toInt())
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun textInput(prefKey: String, hint: String, supporting: String = ""): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val til = com.google.android.material.textfield.TextInputLayout(this).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat())
            isHintEnabled = false
            setPadding(0, 0, 0, 0)
        }
        val et = com.google.android.material.textfield.TextInputEditText(this).apply {
            this.hint = hint
            textSize = 14f
            setText(Prefs.getString(prefKey, ""))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    Prefs.setString(prefKey, s?.toString() ?: "")
                }
            })
        }
        til.addView(et)
        wrapper.addView(til)
        if (supporting.isNotEmpty()) {
            wrapper.addView(TextView(this).apply {
                text = supporting
                textSize = 11f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(dp(4), dp(2), 0, 0)
            })
        }
        return wrapper
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(0xFFE0E0E0.toInt())
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        return v
    }

    /** 重启微信按钮。 */
    private fun restartButton(): MaterialButton {
        return MaterialButton(this).apply {
            text = "保存并重启微信（使开关生效）"
            textSize = 15f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)).apply { topMargin = dp(4) }
            setOnClickListener {
                Prefs.reload()
                try {
                    val p = java.lang.Runtime.getRuntime().exec(
                        arrayOf("am", "force-stop", "com.tencent.mm")
                    )
                    p.waitFor()
                    android.widget.Toast.makeText(this@MainActivity,
                        "已重启微信，请重新打开", android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "重启失败: $e", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ============ 权限 ============

    private fun isAllFilesAccessGranted(): Boolean {
        return try {
            android.os.Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            false
        }
    }

    private fun requestStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (!isAllFilesAccessGranted()) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    try {
                        startActivity(intent)
                    } catch (_: Throwable) {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            } else if (android.os.Build.VERSION.SDK_INT >= 23) {
                val perms = arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                requestPermissions(perms, 100)
            }
        } catch (_: Throwable) {}
    }
}
