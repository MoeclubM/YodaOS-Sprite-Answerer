package com.rokid.aranswerer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 普通轻量后台保活 Service (不使用有 5 秒强杀风险的 ForegroundService)
 * 依赖 Activity 的 FLAG_KEEP_SCREEN_ON 与系统 PARTIAL_WAKE_LOCK 保持唤醒
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "ARAnswerer::KeepAlive"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w("KeepAlive", "wakeLock fail ${e.message}")
        }
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
