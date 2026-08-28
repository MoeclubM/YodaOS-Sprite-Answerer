package com.rokid.aranswerer.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * 极简统一输入分发器 (触控板与外接戒指统一处理)
 * 1. 彻底去除所有单击进入拍照逻辑 (彻底解决触控板轻点误触)；
 * 2. 上滑 (SwipeBack / DPAD_UP / PAGE_UP / 184) -> 切换模型 (休眠态) / 向上滚动 (答案态)；
 * 3. 下滑 (SwipeForward / DPAD_DOWN / PAGE_DOWN / 183) -> 拍照 (休眠态) / 向下滚动 (答案态)；
 * 4. 触控板/镜腿长按 -> 拍照。
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
    private val DEBOUNCE_INTERVAL_MS = 350L

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
                // 下滑广播 -> 下滑操作
                "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD" -> {
                    if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                        lastHardwareSwipeTime = now
                        onSwipeDown()
                    }
                }
                // 上滑广播 -> 上滑操作
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

            // 1. 硬件下滑检测 (包含 Rokid 硬件序列 DPAD_RIGHT -> DPAD_DOWN, 单击 DPAD_DOWN, PAGE_DOWN, 键码 183)
            if ((prev == KeyEvent.KEYCODE_DPAD_RIGHT && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == 183) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onSwipeDown()
                }
                return true
            }

            // 2. 硬件上滑检测 (包含 Rokid 硬件序列 DPAD_LEFT -> DPAD_UP, 单击 DPAD_UP, PAGE_UP, 键码 184)
            if ((prev == KeyEvent.KEYCODE_DPAD_LEFT && keyCode == KeyEvent.KEYCODE_DPAD_UP) ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == 184) {
                if (now - lastHardwareSwipeTime > DEBOUNCE_INTERVAL_MS) {
                    lastHardwareSwipeTime = now
                    onSwipeUp()
                }
                return true
            }

            // 3. 镜腿物理长按键 KEYCODE_PROG_BLUE -> 拍照
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
