package com.rokid.aranswerer.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * 完整对齐眼镜底层驱动事件与手势分发器
 * 1. 拦截 Rokid 系统滑动广播：ACTION_TWO_FINGER_SWIPE_FORWARD / ACTION_TWO_FINGER_SWIPE_BACK
 * 2. 拦截触控板单指滑动底层键码：
 *    - 下滑 / 前滑：KEYCODE_DPAD_DOWN (20), KEYCODE_DPAD_RIGHT (22), KEYCODE_PAGE_DOWN (93), 183
 *    - 上滑 / 后滑：KEYCODE_DPAD_UP (19), KEYCODE_DPAD_LEFT (21), KEYCODE_PAGE_UP (92), 184
 * 3. 拦截单指滑动组合序列检测 (SwipeDetector)
 */
class BareGlassesInputDispatcher(
    private val context: Context,
    private val onTriggerCapture: () -> Unit,
    private val onSwipeUp: () -> Unit,
    private val onSwipeDown: () -> Unit
) {
    private var lastKeyCode = -1
    private var lastEventTime = 0L

    private var lastHardwareSwipeTime = 0L
    private val DEBOUNCE_INTERVAL_MS = 250L

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
                // 触控板长按 / 镜腿长按 -> 拍照
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
                "com.android.action.ACTION_AI_START" -> {
                    onTriggerCapture()
                }
                // 下滑 / 前滑广播
                "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD" -> {
                    if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                        lastHardwareSwipeTime = now
                        onSwipeDown()
                    }
                }
                // 上滑 / 后滑广播
                "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK" -> {
                    if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                        lastHardwareSwipeTime = now
                        onSwipeUp()
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

            // 1. 硬件滑动序列检测 (Rokid 触控板核心驱动：前滑发送 22->20，后滑发送 21->19)
            if (prev == KeyEvent.KEYCODE_DPAD_RIGHT && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onSwipeDown()
                }
                return true
            }

            if (prev == KeyEvent.KEYCODE_DPAD_LEFT && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onSwipeUp()
                }
                return true
            }

            // 2. 单按键滑动检测 (部分系统版本或外接设备直接上报单个方向键/翻页键)
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == 183) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onSwipeDown()
                }
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == 184) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onSwipeUp()
                }
                return true
            }

            // 3. 镜腿物理长按键 KEYCODE_PROG_BLUE
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
