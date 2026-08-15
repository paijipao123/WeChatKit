package com.wechatkit.hook.ui

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
import com.wechatkit.hook.core.Prefs
import com.wechatkit.hook.features.chat.AntiRecallFeature
import com.wechatkit.hook.features.chat.HideAvatarFeature
import com.wechatkit.hook.features.money.AutoCollectTransferFeature
import com.wechatkit.hook.features.money.AutoRedPacketFeature
import com.wechatkit.hook.features.moments.AntiMomentsDeleteFeature

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
            "基础功能",
            listOf(
                SwitchItem("隐藏消息头像",
                    "齿轮里选择隐藏对象，可调消息间距",
                    "feat_${HideAvatarFeature.key}", HideAvatarFeature.defaultEnabled(),
                    params = listOf(
                        ParamItem("隐藏头像范围", "hide_avatar_mode",
                            type = "choice",
                            options = listOf("隐藏对方", "隐藏自己", "全部隐藏"),
                            optionValues = listOf("incoming", "outgoing", "all")),
                        ParamItem("消息上下间距", "chat_item_spacing",
                            type = "seekbar", min = 0, max = 30, step = 1,
                            supporting = "0 = 微信默认间距")
                    )),
                SwitchItem("聊天防撤回",
                    "对方撤回的消息保留显示",
                    "feat_${AntiRecallFeature.key}", AntiRecallFeature.defaultEnabled(),
                    params = listOf(
                        ParamItem("自定义撤回提示", "anti_recall_notice",
                            "例如: 「{sender}」撤回了一条消息",
                            "占位符: {sender} 发送者 / {time} 时间")
                    )),
                SwitchItem("圆形头像",
                    "头像渲染为圆形（可调圆角）",
                    "feat_${com.wechatkit.hook.features.chat.RoundAvatarFeature.key}",
                    com.wechatkit.hook.features.chat.RoundAvatarFeature.defaultEnabled(),
                    params = listOf(
                        ParamItem("圆角弧度", "round_avatar_radius",
                            type = "seekbar", min = 1, max = 5, step = 1,
                            defaultValue = 5,
                            supporting = "5 = 正圆，1 = 接近直角")
                    )),
                SwitchItem("消息时间显示",
                    "消息发送时间（头像下方/消息下方）",
                    "feat_${com.wechatkit.hook.features.chat.AvatarTimeFeature.key}",
                    com.wechatkit.hook.features.chat.AvatarTimeFeature.defaultEnabled(),
                    params = listOf(
                        ParamItem("显示位置", "avatar_time_mode",
                            type = "choice",
                            options = listOf("头像下方", "消息下方"),
                            optionValues = listOf("avatar", "message"))
                    )),
                SwitchItem("朋友圈防删",
                    "阻止他人删除朋友圈后消失",
                    "feat_${AntiMomentsDeleteFeature.key}", AntiMomentsDeleteFeature.defaultEnabled()),
                SwitchItem("签名绕过",
                    "嵌入版/改包环境登录签名校验绕过（正常环境勿开）",
                    "feat_${com.wechatkit.hook.features.system.SignatureBypassFeature.key}",
                    com.wechatkit.hook.features.system.SignatureBypassFeature.defaultEnabled()),
                SwitchItem("平板模式",
                    "强制识别为平板：同机双开或两设备同号登录",
                    "feat_${com.wechatkit.hook.features.system.TabletModeFeature.key}",
                    com.wechatkit.hook.features.system.TabletModeFeature.defaultEnabled()),
            )
        ))

        // ---- 红包转账卡片 ----
        root.addView(card(
            "红包 / 转账",
            listOf(
                SwitchItem("自动抢红包",
                    "后台自动拆包/开包（有封号风险）",
                    "feat_${AutoRedPacketFeature.key}", AutoRedPacketFeature.defaultEnabled(),
                    params = listOf(
                        ParamItem("拆包延迟（毫秒）", "auto_redpacket_delay", "默认 800"),
                        ParamItem("屏蔽名单", "blocked_talkers", "逗号分隔昵称/微信号")
                    )),
                SwitchItem("自动收转账",
                    "后台自动确认收款（有封号风险）",
                    "feat_${AutoCollectTransferFeature.key}", AutoCollectTransferFeature.defaultEnabled(),
                    params = listOf(
                        ParamItem("收款延迟（毫秒）", "auto_collect_delay", "默认 1000"),
                        ParamItem("屏蔽名单", "blocked_talkers", "逗号分隔昵称/微信号")
                    )),
            )
        ))

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
            text = "微信增强模块"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        head.addView(col)
        return head
    }

    /** 单个参数输入项（齿轮弹窗里的配置）。 */
    data class ParamItem(
        val label: String,
        val prefKey: String,
        val hint: String = "",
        val supporting: String = "",
        /** 控件类型：text 输入框 / choice 单选 / seekbar 滑块 */
        val type: String = "text",
        /** choice 的显示文案列表 */
        val options: List<String> = emptyList(),
        /** choice 对应的存储值（缺省用 options 本身） */
        val optionValues: List<String> = emptyList(),
        /** seekbar 范围 */
        val min: Int = 0,
        val max: Int = 100,
        val step: Int = 1,
        /** seekbar 默认值（未配置时显示/保存的初始值） */
        val defaultValue: Int = 0
    )

    data class SwitchItem(
        val title: String,
        val desc: String,
        val prefKey: String,
        val default: Boolean,
        val params: List<ParamItem> = emptyList()
    )

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
        val sw = MaterialSwitch(this).apply {
            isChecked = Prefs.getBoolean(item.prefKey, item.default)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBoolean(item.prefKey, checked)
            }
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 齿轮图标（可调参数的功能才有）
        if (item.params.isNotEmpty()) {
            val gear = TextView(this).apply {
                text = "⚙️"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(0xFF00897B.toInt())
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener { showParamDialog(item.title, item.params) }
            }
            row.addView(gear)
        }

        row.addView(sw)
        return row
    }

    /** 齿轮弹窗：调对应功能的数值参数（Material3 风格，大框包小框）。 */
    private fun showParamDialog(title: String, params: List<ParamItem>) {
        // 大框：浅色圆角容器，包住所有参数小框
        val bigCard = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFF5F5F5.toInt())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        params.forEach { p ->
            // 小框：白色圆角卡片，一个参数一个小框（label + 控件）
            val smallCard = MaterialCardView(this).apply {
                radius = dp(12).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            when (p.type) {
                "choice" -> inner.addView(choiceGroup(p))
                "seekbar" -> inner.addView(seekBarRow(p))
                else -> {
                    inner.addView(textInputLabel(p.label))
                    inner.addView(textInput(p.prefKey, p.hint, p.supporting))
                }
            }
            smallCard.addView(inner)
            col.addView(smallCard)
        }
        bigCard.addView(col)
        val scroll = ScrollView(this).apply { addView(bigCard) }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("完成", null)
            .show()
    }

    /** 单选组（Material 风格），选中即保存。 */
    private fun choiceGroup(p: ParamItem): LinearLayout {
        val values = if (p.optionValues.isNotEmpty()) p.optionValues else p.options
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(textInputLabel(p.label))
        val current = Prefs.getString(p.prefKey, values.firstOrNull() ?: "")
        val rg = android.widget.RadioGroup(this).apply { setPadding(0, dp(2), 0, 0) }
        p.options.forEachIndexed { i, label ->
            val rb = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                this.text = label
                textSize = 15f
                setTextColor(0xFF1F1F1F.toInt())
                setPadding(dp(4), dp(6), dp(4), dp(6))
                isChecked = (values.getOrNull(i) ?: label) == current
            }
            rb.setOnClickListener {
                Prefs.setString(p.prefKey, values.getOrNull(i) ?: label)
            }
            rg.addView(rb, android.widget.RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(rg)
        if (p.supporting.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = p.supporting
                textSize = 11f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(dp(4), dp(2), 0, 0)
            })
        }
        return col
    }

    /** 滑块行：SeekBar + 实时数值显示，拖动即保存。 */
    private fun seekBarRow(p: ParamItem): LinearLayout {
        val current = Prefs.getInt(p.prefKey, p.defaultValue).coerceIn(p.min, p.max)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        head.addView(TextView(this).apply {
            text = p.label
            textSize = 13f
            setTextColor(0xFF757575.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTv = TextView(this).apply {
            text = "$current"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF00897B.toInt())
        }
        head.addView(valueTv)
        col.addView(head)

        val sb = android.widget.SeekBar(this).apply {
            max = (p.max - p.min) / p.step
            progress = (current - p.min) / p.step
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = p.min + progress * p.step
                    valueTv.text = "$v"
                    Prefs.setInt(p.prefKey, v)
                }
                override fun onStartTrackingTouch(seek: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seek: android.widget.SeekBar?) {}
            })
        }
        col.addView(sb)
        if (p.supporting.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = p.supporting
                textSize = 11f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(dp(4), dp(2), 0, 0)
            })
        }
        return col
    }

    /** 参数输入卡片。 */
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
