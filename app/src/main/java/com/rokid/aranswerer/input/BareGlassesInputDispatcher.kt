package com.rokid.aranswerer.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * 完整对齐 Rokid-Assist 的眼镜系统级输入与双指广播拦截器
 * 支持：
 * 1. 触控板长按 / 镜腿长按 -> 拍照
 * 2. 双指长按广播 ACTION_SETTINGS_KEY / KEYCODE_SETTINGS -> 打开设置
 * 3. 双指双击 / 双指滑动 -> 扩展手势
 */
class BareGlassesInputDispatcher(
    private val context: Context,
    private val onTriggerCapture: () -> Unit,
    private val onSingleTapAction: () -> Unit,
    private val onScrollAction: (Int) -> Unit,
    private val onOpenSettings: () -> Unit
) {
    private var lastKeyCode = -1
    private var lastEventTime = 0L

    private var lastHardwareSwipeTime = 0L
    private val DEBOUNCE_INTERVAL_MS = 400L

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            // 终止广播继续向下传递
            if (isOrderedBroadcast) {
                try {
                    abortBroadcast()
                } catch (_: Throwable) {}
            }

            val now = System.currentTimeMillis()
            when (intent.action) {
                // 1. 双指长按广播 (Rokid 系统设置键) -> 拦截并打开 AR 设置面板
                "com.android.action.ACTION_SETTINGS_KEY" -> {
                    onOpenSettings()
                }
                // 2. 双指双击
                "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP" -> {
                    onOpenSettings()
                }
                // 3. 触控板长按 / 镜腿长按进入拍照
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
                "com.android.action.ACTION_AI_START" -> {
                    onTriggerCapture()
                }
                // 4. 双指滑动 (400ms 消抖)
                "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD" -> {
                    if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                        lastHardwareSwipeTime = now
                        onScrollAction(160)
                    }
                }
                "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK" -> {
                    if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                        lastHardwareSwipeTime = now
                        onScrollAction(-160)
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
            addAction("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
            addAction("com.android.action.ACTION_AI_START")
            addAction("com.android.action.ACTION_SETTINGS_KEY")
            addAction("com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP")
            addAction("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD")
            addAction("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK")
        }
        try {
            ContextCompat.registerReceiver(
                context,
                broadcastReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Throwable) {}
    }

    fun stop() {
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (_: Throwable) {}
    }

    /**
     * 处理 Activity 的 dispatchKeyEvent
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val eventTime = event.eventTime
            val keyCode = event.keyCode
            val prev = if (eventTime - lastEventTime <= 500L) lastKeyCode else -1
            lastKeyCode = keyCode
            lastEventTime = eventTime

            val now = System.currentTimeMillis()

            // 1. 双指长按物理键 KEYCODE_SETTINGS (或双指点击 KEYCODE_NOTIFICATION: 83)
            if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_NOTIFICATION) {
                onOpenSettings()
                return true
            }

            // 2. 滑动序列检测
            if ((prev == KeyEvent.KEYCODE_DPAD_RIGHT && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == 183) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onScrollAction(160)
                }
                return true
            }

            if ((prev == KeyEvent.KEYCODE_DPAD_LEFT && keyCode == KeyEvent.KEYCODE_DPAD_UP) ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == 184) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onScrollAction(-160)
                }
                return true
            }

            // 3. 长按物理按键 KEYCODE_PROG_BLUE
            if (keyCode == KeyEvent.KEYCODE_PROG_BLUE || event.isLongPress) {
                onTriggerCapture()
                return true
            }

            // 4. 单击
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                onSingleTapAction()
                return true
            }
        }
        return false
    }

    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            return dispatchKeyEvent(event)
        }
        return false
    }
}
