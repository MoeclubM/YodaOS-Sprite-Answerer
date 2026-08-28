package com.rokid.aranswerer.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * 裸机眼镜 WLAN 自动开启与连接助手
 */
object WifiHelper {

    fun autoConnect(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return

            // 1. 如果 WLAN 未开启，强制打开 WLAN
            if (!wifiManager.isWifiEnabled) {
                Log.d("WifiHelper", "WiFi is disabled, turning on WiFi...")
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            }

            // 2. 尝试触发系统已保存 WiFi 的自动重连与扫描
            @Suppress("DEPRECATION")
            wifiManager.reconnect()
        } catch (e: Exception) {
            Log.e("WifiHelper", "autoConnect error", e)
        }
    }
}
