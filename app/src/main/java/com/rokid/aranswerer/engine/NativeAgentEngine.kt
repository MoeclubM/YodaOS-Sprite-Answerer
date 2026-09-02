package com.rokid.aranswerer.engine

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
object NativeAgentEngine {
    private val API_BASE = com.rokid.aranswerer.AppSecrets.PRIMARY_API_BASE
    private val API_KEY = com.rokid.aranswerer.AppSecrets.PRIMARY_API_KEY
    private const val PRIMARY_MODEL = "gemini-3.8-flash"
    private val FALLBACK_MODELS = listOf("gpt-5.6-luna", "muse-spark-1.2")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun solveImage(jpegBytes: ByteArray, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        val models = listOf(PRIMARY_MODEL) + FALLBACK_MODELS
        var lastErr: Exception? = null

        for ((idx, model) in models.withIndex()) {
            try {
                withContext(Dispatchers.Main) {
                    onStatus("解题中 (${model})...")
                }

                val sysPrompt = "你是一个全能学科专家与答题助手。请直接给出题目答案与必要拿分步骤，简明扼要，公式使用 LaTeX 格式。"
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", sysPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "请解答图片中的题目。")
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", dataUrl)
                                    })
                                })
                            })
                        })
                    })
                }

                val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${API_BASE.trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .post(body)
                    .build()

                val resp = client.newCall(req).execute()
                val respText = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: $respText")
                }

                val json = JSONObject(respText)
                val choices = json.optJSONArray("choices")
                val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()

                if (!content.isNullOrEmpty()) {
                    return@withContext content
                } else {
                    throw RuntimeException("Empty response from $model")
                }
            } catch (e: Exception) {
                lastErr = e
                withContext(Dispatchers.Main) {
                    onStatus("切换备用模型 (${idx + 1}/${models.size})...")
                }
            }
        }
        throw lastErr ?: RuntimeException("所有模型请求失败")
    }
}
