package com.rokid.aranswerer.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * 完整对齐本项目的输入分发器
 * 1. 触控板轻点 (KEYCODE_DPAD_CENTER / KEYCODE_ENTER) 仅在答案态 (state=3) 下用于退出休眠，休眠态 (state=0) 下绝对不触发拍照！
 * 2. 触控板进入拍照唯一途径：触控板长按 400ms (ACTION_SPRITE_BUTTON_LONG_PRESS / ACTION_AI_START / 触摸 400ms)；
 * 3. 蓝牙智能戒指 (R08_5703 专属媒体键 KEY_PLAYPAUSE / HEADSETHOOK / 0x000c00cd) 在休眠态下点击进入拍照！
 */
class BareGlassesInputDispatcher(
    private val context: Context,
    private val onTriggerCapture: () -> Unit,
    private val onRingClick: () -> Unit,
    private val onTouchpadSingleTap: () -> Unit,
    private val onScrollAction: (Int) -> Unit
) {
    private var lastKeyCode = -1
    private var lastEventTime = 0L

    private var lastHardwareSwipeTime = 0L
    private val DEBOUNCE_INTERVAL_MS = 400L

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            if (isOrderedBroadcast) {
                try {
                    abortBroadcast()
                } catch (_: Throwable) {}
            }

            val now = System.currentTimeMillis()
            when (intent.action) {
                // 触控板长按 / 镜腿长按进入拍照
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
                "com.android.action.ACTION_AI_START" -> {
                    onTriggerCapture()
                }
                // 双指滑动
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
            addAction("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
            addAction("com.android.action.ACTION_AI_START")
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

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val eventTime = event.eventTime
            val keyCode = event.keyCode
            val prev = if (eventTime - lastEventTime <= 500L) lastKeyCode else -1
            lastKeyCode = keyCode
            lastEventTime = eventTime

            val now = System.currentTimeMillis()

            // 1. 蓝牙智能戒指专有硬件键 (KEY_PLAYPAUSE / KEYCODE_HEADSETHOOK / 0x000c00cd / 游戏手柄键)
            // 这一类事件是真实的外部按键，允许在休眠态下一键唤醒拍照
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
                event.scanCode == 0x000c00cd ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                keyCode == KeyEvent.KEYCODE_CAMERA ||
                keyCode == KeyEvent.KEYCODE_SPACE) {
                onRingClick()
                return true
            }

            // 2. 眼镜触控板硬件单击 (DPAD_CENTER / ENTER)
            // 关键区别：触控板轻点绝不进入拍照，仅在查看答案 (state=3) 时用于退出休眠！
            if (keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                onTouchpadSingleTap()
                return true
            }

            // 3. 滑动序列检测 -> 切换模型 / 翻页
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

            // 4. 镜腿物理长按键 KEYCODE_PROG_BLUE
            if (keyCode == KeyEvent.KEYCODE_PROG_BLUE || event.isLongPress) {
                onTriggerCapture()
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
