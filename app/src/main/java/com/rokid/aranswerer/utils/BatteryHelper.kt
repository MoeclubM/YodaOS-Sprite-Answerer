package com.rokid.aranswerer.utils
import android.content.Context
import android.os.BatteryManager
object BatteryHelper { fun level(context: Context): Int = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0 }
