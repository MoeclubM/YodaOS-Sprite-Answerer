package com.rokid.aranswerer

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置管理器
 * 1. 优先读取 SharedPreferences (用户在 AR 设置面板中保存的自定义值)
 * 2. 次选读取 LocalSecrets (本地开发私有预设，已被 .gitignore 保护)
 * 3. 兜底读取 AppSecrets (Git 公开模板，不带预设)
 */
object ConfigManager {
    private const val PREFS_NAME = "ar_answerer_config"

    private const val KEY_PRIMARY_BASE = "primary_api_base"
    private const val KEY_PRIMARY_KEY = "primary_api_key"
    private const val KEY_DEEPSEEK_BASE = "deepseek_api_base"
    private const val KEY_DEEPSEEK_KEY = "deepseek_api_key"

    const val DEFAULT_PRIMARY_BASE = "https://newapi.telecom.moe/v1"
    const val DEFAULT_DEEPSEEK_BASE = "https://api.deepseek.com"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPrimaryApiBase(context: Context): String {
        val custom = getPrefs(context).getString(KEY_PRIMARY_BASE, null)
        if (!custom.isNullOrEmpty()) return custom
        if (LocalSecrets.PRIMARY_API_BASE.isNotEmpty()) return LocalSecrets.PRIMARY_API_BASE
        return AppSecrets.PRIMARY_API_BASE.ifEmpty { DEFAULT_PRIMARY_BASE }
    }

    fun setPrimaryApiBase(context: Context, value: String) {
        getPrefs(context).edit().putString(KEY_PRIMARY_BASE, value.trim()).apply()
    }

    fun getPrimaryApiKey(context: Context): String {
        val custom = getPrefs(context).getString(KEY_PRIMARY_KEY, null)
        if (!custom.isNullOrEmpty()) return custom
        if (LocalSecrets.PRIMARY_API_KEY.isNotEmpty()) return LocalSecrets.PRIMARY_API_KEY
        return AppSecrets.PRIMARY_API_KEY
    }

    fun setPrimaryApiKey(context: Context, value: String) {
        getPrefs(context).edit().putString(KEY_PRIMARY_KEY, value.trim()).apply()
    }

    fun getDeepSeekApiBase(context: Context): String {
        val custom = getPrefs(context).getString(KEY_DEEPSEEK_BASE, null)
        if (!custom.isNullOrEmpty()) return custom
        if (LocalSecrets.DEEPSEEK_API_BASE.isNotEmpty()) return LocalSecrets.DEEPSEEK_API_BASE
        return AppSecrets.DEEPSEEK_API_BASE.ifEmpty { DEFAULT_DEEPSEEK_BASE }
    }

    fun setDeepSeekApiBase(context: Context, value: String) {
        getPrefs(context).edit().putString(KEY_DEEPSEEK_BASE, value.trim()).apply()
    }

    fun getDeepSeekApiKey(context: Context): String {
        val custom = getPrefs(context).getString(KEY_DEEPSEEK_KEY, null)
        if (!custom.isNullOrEmpty()) return custom
        if (LocalSecrets.DEEPSEEK_API_KEY.isNotEmpty()) return LocalSecrets.DEEPSEEK_API_KEY
        return AppSecrets.DEEPSEEK_API_KEY
    }

    fun setDeepSeekApiKey(context: Context, value: String) {
        getPrefs(context).edit().putString(KEY_DEEPSEEK_KEY, value.trim()).apply()
    }
}
