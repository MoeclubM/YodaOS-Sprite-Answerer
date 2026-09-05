package com.rokid.aranswerer.ui.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rokid.aranswerer.ConfigManager

/**
 * 专为 Rokid Glasses 480x640 屏幕定制的纯黑线框设置对话框
 */
class SettingsDialog(context: Context) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        val title = TextView(context).apply {
            text = "AR-Answerer 设置"
            setTextColor(0xff00ff66.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        fun makeLabel(t: String) = TextView(context).apply {
            text = t
            setTextColor(0xff00ff66.toInt())
            textSize = 12f
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }

        fun makeInput(initial: String) = EditText(context).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(0x66ffffff)
            textSize = 12f
            setBackgroundColor(0xff111111.toInt())
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        form.addView(makeLabel("主力 API Base URL:"))
        val primaryBaseInput = makeInput(ConfigManager.getPrimaryApiBase(context))
        form.addView(primaryBaseInput)

        form.addView(makeLabel("主力 API Key (sk-...):"))
        val primaryKeyInput = makeInput(ConfigManager.getPrimaryApiKey(context))
        form.addView(primaryKeyInput)

        form.addView(makeLabel("DeepSeek API Base URL:"))
        val deepseekBaseInput = makeInput(ConfigManager.getDeepSeekApiBase(context))
        form.addView(deepseekBaseInput)

        form.addView(makeLabel("DeepSeek API Key (sk-...):"))
        val deepseekKeyInput = makeInput(ConfigManager.getDeepSeekApiKey(context))
        form.addView(deepseekKeyInput)

        form.addView(makeLabel("智谱 API Base URL:"))
        val zhipuBaseInput = makeInput(ConfigManager.getZhipuApiBase(context))
        form.addView(zhipuBaseInput)

        form.addView(makeLabel("智谱 API Key:"))
        val zhipuKeyInput = makeInput(ConfigManager.getZhipuApiKey(context))
        form.addView(zhipuKeyInput)

        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (320 * density).toInt()))

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }

        val saveBtn = Button(context).apply {
            text = "保存"
            setTextColor(0xff000000.toInt())
            setBackgroundColor(0xff00ff66.toInt())
            setOnClickListener {
                ConfigManager.setPrimaryApiBase(context, primaryBaseInput.text.toString())
                ConfigManager.setPrimaryApiKey(context, primaryKeyInput.text.toString())
                ConfigManager.setDeepSeekApiBase(context, deepseekBaseInput.text.toString())
                ConfigManager.setDeepSeekApiKey(context, deepseekKeyInput.text.toString())
                ConfigManager.setZhipuApiBase(context, zhipuBaseInput.text.toString())
                ConfigManager.setZhipuApiKey(context, zhipuKeyInput.text.toString())
                Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
        val cancelBtn = Button(context).apply {
            text = "关闭"
            setTextColor(0xff00ff66.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }

        btnRow.addView(saveBtn)
        btnRow.addView(cancelBtn)
        root.addView(btnRow)

        setContentView(root)
        window?.setLayout((380 * density).toInt(), (440 * density).toInt())
    }
}
