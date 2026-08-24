package com.rokid.aranswerer.ui.sleep
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.rokid.aranswerer.utils.BatteryHelper
class SleepScreen(context: Context) : FrameLayout(context) {
    private val battery = TextView(context).apply { setTextColor(0x66ffffff); setBackgroundColor(Color.TRANSPARENT); textSize = 8f; alpha = 0.55f; text = "${BatteryHelper.level(context)}%" }
    init { setBackgroundColor(Color.TRANSPARENT); addView(battery, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.END; setMargins(8,4,8,4) }) }
}
