package com.rokid.aranswerer.utils

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * WLAN 自动开启与连接助手 (兼容 Android 12 眼镜系统与 Android 10+ 现代手机)
 */
object WifiHelper {

    fun autoConnect(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return

            // 1. 如果 WLAN 未开启，尝试开启 WLAN
            if (!wifiManager.isWifiEnabled) {
                Log.d("WifiHelper", "WiFi is disabled, attempting to turn on WiFi...")
                
                // 在 Android Q (API 29) 及以下或系统签名/裸机眼镜上，wifiManager.setWifiEnabled 直接生效
                @Suppress("DEPRECATION")
                val success = wifiManager.setWifiEnabled(true)

                // 2. 如果是现代 Android (API 29+) 且非系统应用调用 setWifiEnabled 失败，调用系统 Panel 打开 WiFi
                if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(panelIntent)
                    } catch (_: Exception) {
                        try {
                            val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(wifiIntent)
                        } catch (_: Exception) {}
                    }
                }
            } else {
                // 2. 如果 WiFi 已开，触发自动重连
                @Suppress("DEPRECATION")
                wifiManager.reconnect()
            }
        } catch (e: Exception) {
            Log.e("WifiHelper", "autoConnect error", e)
        }
    }
}
