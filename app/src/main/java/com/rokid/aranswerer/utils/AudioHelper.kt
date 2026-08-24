package com.rokid.aranswerer.utils
import android.content.Context
import android.media.AudioManager
object AudioHelper { fun forceMute(context: Context) { context.getSystemService(AudioManager::class.java)?.let { it.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0); it.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) } } }
