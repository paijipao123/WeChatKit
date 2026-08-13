package com.wechathook.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.wechathook.core.Prefs
import com.wechathook.features.chat.AntiRecallFeature
import com.wechathook.features.chat.HideAvatarFeature
import com.wechathook.features.money.AutoCollectTransferFeature
import com.wechathook.features.money.AutoRedPacketFeature
import com.wechathook.features.moments.AntiMomentsDeleteFeature
import com.wechathook.features.readreceipt.ReadReceiptFeature

/**
 * 模块设置页。
 *
 * 在设置页里对每项功能做开关，并持久化到 SharedPreferences（微信宿主进程也读取同一文件，
 * 因此 hook 侧能感知到设置变化）。注意：由于 LSPosed 模块自身的 Context 与宿主的 getSharedPreferences
 * 指向不同，这里统一使用模块自己的 SharedPreferences 文件，并在 hook 侧通过宿主 Context 读取同名文件。
 *
 * 使用平台原生 [Activity]，不依赖 AppCompat，以免 LSPosed 模块资源链接出问题。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 用模块自身 Context 初始化 Prefs（使设置页读写生效）
        Prefs.initWithModuleContext(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        root.addView(title("WeChatKit 微信增强"))
        root.addView(subtitle("功能开关与配置（修改后请重启微信生效）"))

        // 各功能开关：key -> (标题, 说明, 默认开启)
        addSwitch(root, "feat_${HideAvatarFeature.key}", "隐藏消息头像（紧凑）",
            "只去左右头像，气泡间距与微信原版一致", HideAvatarFeature.defaultEnabled())
        addSwitch(root, "feat_${AntiRecallFeature.key}", "聊天防撤回",
            "对方撤回的消息保留显示", AntiRecallFeature.defaultEnabled())
        addSwitch(root, "feat_${AntiMomentsDeleteFeature.key}", "朋友圈防删",
            "阻止他人删除朋友圈后消失", AntiMomentsDeleteFeature.defaultEnabled())
        addSwitch(root, "feat_${ReadReceiptFeature.key}", "已读回执",
            "配合 read-receipt-tracker 服务显示\"已读 X 人\"（需填服务器地址）", ReadReceiptFeature.defaultEnabled())
        addSwitch(root, "feat_${AutoRedPacketFeature.key}", "自动抢红包",
            "后台 hook 自动拆包/开包（有封号风险）", AutoRedPacketFeature.defaultEnabled())
        addSwitch(root, "feat_${AutoCollectTransferFeature.key}", "自动收款（转账）",
            "后台 hook 自动确认收款（有封号风险）", AutoCollectTransferFeature.defaultEnabled())

        root.addView(section("已读回执服务器"))
        root.addView(textInput("read_receipt_server", "服务器地址",
            "例如 http://192.168.1.10:8080（read-receipt-tracker 服务）"))

        root.addView(section("自动抢红包"))
        root.addView(textInput("auto_redpacket_delay", "拆包延迟(ms)", "默认 800"))

        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding(0, 8, 0, 8)
    }
    private fun subtitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF888888.toInt())
        setPadding(0, 0, 0, 24)
    }
    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    private fun addSwitch(
        root: LinearLayout,
        prefKey: String,
        title: String,
        desc: String,
        default: Boolean
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }
        val tv = TextView(this).apply { this.text = title; textSize = 16f }
        val dv = TextView(this).apply {
            this.text = desc; textSize = 12f; setTextColor(0xFF666666.toInt())
        }
        textCol.addView(tv)
        textCol.addView(dv)

        val sw = Switch(this).apply {
            isChecked = Prefs.getBoolean(prefKey, default)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBoolean(prefKey, checked)
            }
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        root.addView(row)
    }

    private fun textInput(prefKey: String, label: String, hint: String): EditText {
        val et = EditText(this).apply {
            hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.getString(prefKey, ""))
            setPadding(0, 8, 0, 8)
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                Prefs.setString(prefKey, s?.toString() ?: "")
            }
        })
        return et
    }
}
