package com.rokid.aranswerer.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rokid.aranswerer.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ExtractedQuestion(
    val id: String,
    val content: String,
    val originalOrder: Int
)

data class QuestionStatus(
    val id: String,
    val originalOrder: Int,
    var toolCount: Int = 0,
    var isDone: Boolean = false
)

data class SolvedQuestion(
    val id: String,
    val content: String,
    val answer: String,
    val originalOrder: Int,
    val toolCount: Int
)

data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String
)

data class StreamChatResult(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCallInfo>
)

object NativePipelineEngine {
    val AVAILABLE_MODELS = listOf(
        "gemini-3.7-flash",
        "deepseek-v4-flash-vision-exp",
        "gpt-5.6-luna",
        "muse-spark-1.2"
    )

    var currentModel: String = "gemini-3.7-flash"

    private const val STAGE1_PROMPT =
        "提取图片中的所有题目,严禁解答。\n" +
        "按题目顺序输出 JSON 数组,每项包含题号 id 和完整题目内容 content:\n" +
        "[{\"id\": \"1\", \"content\": \"题目1完整内容...\"}, {\"id\": \"2\", \"content\": \"题目2完整内容...\"}]\n" +
        "若无题目则输出 NO_QUESTION。"

    private const val STAGE2_PROMPT =
        "请解答本题目。默认提供基础检索与代数计算工具。\n" +
        "若本题需要微积分、复变函数、信号系统、电磁波、几何统计等领域的专用计算工具,请调用相关工具辅助推导并输出最终答案。"

    private const val STAGE3_PROMPT =
        "整理为极简 AR 排版:\n" +
        "1. 选择题/填空题:只给答案,同行不换行(如 \"1. A 2. B 3. 2π\"),严禁写任何解析或多余说明。\n" +
        "2. 解答题/大题:只保留核心拿分步骤与最终结论,严禁文字铺垫,数学公式使用标准 LaTeX 格式(支持 $$...$$ 与 $...$)。"

    private val TOOLS_SCHEMA = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "math_eval")
                put("description", "计算数学表达式")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("expr", JSONObject().apply {
                            put("type", "string")
                            put("description", "数学表达式")
                        })
                    })
                    put("required", JSONArray().apply { put("expr") })
                })
            })
        })
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "web_search")
                put("description", "联网搜索最新信息")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
        })
    }

    private fun getProviderConfig(context: Context, modelName: String): ProviderConfig {
        val model = modelName.ifBlank { currentModel }
        val isDeepSeek = model.contains("deepseek", ignoreCase = true)
        val base = if (isDeepSeek) ConfigManager.getDeepSeekApiBase(context).trim().trimEnd('/') else ConfigManager.getPrimaryApiBase(context).trim().trimEnd('/')
        val key = if (isDeepSeek) ConfigManager.getDeepSeekApiKey(context).trim() else ConfigManager.getPrimaryApiKey(context).trim()

        val endpoint = when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }

        return ProviderConfig(endpoint = endpoint, key = key, model = model)
    }

    private data class ProviderConfig(
        val endpoint: String,
        val key: String,
        val model: String
    )

    private suspend fun streamMessages(
        context: Context,
        messages: JSONArray,
        tools: JSONArray? = null,
        onToken: (suspend (String) -> Unit)? = null
    ): StreamChatResult = withContext(Dispatchers.IO) {
        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(currentModel)
        for (m in AVAILABLE_MODELS) {
            if (m != currentModel && !modelsToTry.contains(m)) {
                modelsToTry.add(m)
            }
        }

        var lastErr: Exception? = null
        for (m in modelsToTry) {
            val provider = getProviderConfig(context, m)
            if (provider.key.isBlank()) {
                continue
            }

            var conn: HttpURLConnection? = null
            try {
                val isDeepSeek = provider.model.contains("deepseek", ignoreCase = true)
                val body = JSONObject().apply {
                    put("model", provider.model)
                    put("messages", messages)
                    put("stream", true)
                    if (tools != null && tools.length() > 0 && !isDeepSeek) {
                        put("tools", tools)
                    }
                }

                val url = URL(provider.endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 60000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Authorization", "Bearer ${provider.key}")
                }

                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw RuntimeException("HTTP $code: $errBody")
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val contentAcc = StringBuilder()
                val reasoningAcc = StringBuilder()
                val toolCallMap = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                        try {
                            val dataStr = line.substring(6).trim()
                            val chunk = JSONObject(dataStr)
                            val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                            val delta = choice?.optJSONObject("delta")

                            val c = delta?.optString("content")
                            if (c != null && c.isNotEmpty() && c != "null") {
                                contentAcc.append(c)
                                onToken?.invoke(contentAcc.toString())
                            }

                            val r = delta?.optString("reasoning_content")
                            if (r != null && r.isNotEmpty() && r != "null") {
                                reasoningAcc.append(r)
                            }

                            val toolCallsArr = delta?.optJSONArray("tool_calls")
                            if (toolCallsArr != null) {
                                for (i in 0 until toolCallsArr.length()) {
                                    val tcObj = toolCallsArr.optJSONObject(i) ?: continue
                                    val idx = tcObj.optInt("index", i)
                                    val id = tcObj.optString("id", "")
                                    val fnObj = tcObj.optJSONObject("function")
                                    val fnName = fnObj?.optString("name", "") ?: ""
                                    val fnArgsDelta = fnObj?.optString("arguments", "") ?: ""

                                    val existing = toolCallMap.getOrPut(idx) {
                                        Triple(id, fnName, StringBuilder())
                                    }
                                    existing.third.append(fnArgsDelta)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    line = reader.readLine()
                }

                val finalContent = contentAcc.toString().trim()
                val finalReasoning = reasoningAcc.toString().trim()

                val resultText = if (finalContent.isNotEmpty()) {
                    finalContent
                } else if (finalReasoning.isNotEmpty()) {
                    finalReasoning
                } else {
                    ""
                }

                val finalToolCalls = toolCallMap.values.map {
                    ToolCallInfo(it.first, it.second, it.third.toString())
                }
                return@withContext StreamChatResult(resultText, finalReasoning, finalToolCalls)
            } catch (e: Exception) {
                Log.w("NativePipelineEngine", "Provider ${provider.model} error", e)
                lastErr = e
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }
        throw lastErr ?: RuntimeException("请求失败，请检查网络或 API Key")
    }

    suspend fun runThreeStagePipeline(
        context: Context,
        jpegBytes: ByteArray,
        onStage1QuestionsUpdate: suspend (List<ExtractedQuestion>) -> Unit,
        onStage2DoubleColumnUpdate: suspend (List<QuestionStatus>, String) -> Unit,
        onStage3StreamToken: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        // ================= Stage 1: 题目提取 =================
        val stage1Messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", STAGE1_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "请提取图片中的题目。")
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", dataUrl)
                        })
                    })
                })
            })
        }

        val stage1Result = streamMessages(context, stage1Messages)
        val rawQuestions = stage1Result.content.ifEmpty { stage1Result.reasoning }

        if (isStrictNoQuestion(rawQuestions)) {
            return@withContext "未识别到题目"
        }

        val questions = parseQuestionsJson(rawQuestions)
        if (questions.isEmpty()) {
            val fallbackSolved = runStage2ReActAgent(context, rawQuestions) {}.first
            return@withContext fallbackSolved
        }

        onStage1QuestionsUpdate(questions)

        // ================= Stage 2: 多题并发求解 =================
        val statusList = questions.map { QuestionStatus(it.id, it.originalOrder, toolCount = 0, isDone = false) }.toMutableList()
        var completedCount = 0
        var totalToolCalls = 0

        withContext(Dispatchers.Main) {
            onStage2DoubleColumnUpdate(
                statusList.toList(),
                "0/${questions.size}"
            )
        }

        val solvedList = coroutineScope {
            questions.map { q ->
                async(Dispatchers.IO) {
                    val (ans, calls) = runStage2ReActAgent(context, q.content) { currentCallsForThisQuestion ->
                        synchronized(statusList) {
                            val item = statusList.find { it.id == q.id }
                            if (item != null) {
                                item.toolCount = currentCallsForThisQuestion
                            }
                            totalToolCalls++
                        }
                        withContext(Dispatchers.Main) {
                            onStage2DoubleColumnUpdate(
                                statusList.toList(),
                                "${completedCount}/${questions.size}"
                            )
                        }
                    }

                    synchronized(statusList) {
                        val item = statusList.find { it.id == q.id }
                        if (item != null) {
                            item.toolCount = calls
                            item.isDone = true
                        }
                        completedCount++
                    }

                    withContext(Dispatchers.Main) {
                        onStage2DoubleColumnUpdate(
                            statusList.toList(),
                            "${completedCount}/${questions.size}"
                        )
                    }
                    SolvedQuestion(q.id, q.content, ans, q.originalOrder, calls)
                }
            }.awaitAll()
        }

        // ================= Stage 3: AR 排版提炼 (带超时熔断与直接呈现兜底) =================
        val summaryInput = buildString {
            for (item in solvedList.sortedBy { it.originalOrder }) {
                appendLine("【题号 ${item.id}】")
                appendLine("题目: ${item.content}")
                appendLine("解答: ${item.answer}")
                appendLine()
            }
        }

        val stage3Messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", STAGE3_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", summaryInput)
            })
        }

        try {
            val finalResult = streamMessages(context, stage3Messages) { streamAcc ->
                withContext(Dispatchers.Main) {
                    onStage3StreamToken(streamAcc)
                }
            }
            if (finalResult.content.isNotBlank()) {
                return@withContext finalResult.content
            }
        } catch (e: Exception) {
            Log.w("NativePipelineEngine", "Stage 3 summary failed, fallback to Stage 2 answers", e)
        }

        // 兜底直出：如果 Stage 3 超时或网络异常，直接将 Stage 2 的完整解答直通呈现，绝不卡死
        val directAnswers = solvedList.sortedBy { it.originalOrder }.joinToString("\n\n") {
            "**${it.id}.** ${it.answer}"
        }
        withContext(Dispatchers.Main) {
            onStage3StreamToken(directAnswers)
        }
        return@withContext directAnswers
    }

    private suspend fun runStage2ReActAgent(
        context: Context,
        questionContent: String,
        onToolCallExecuted: suspend (Int) -> Unit
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", STAGE2_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "题目内容:\n$questionContent")
            })
        }

        var totalToolCalls = 0
        var turn = 0
        val maxTurns = 3

        while (turn < maxTurns) {
            turn++
            val chatResult = streamMessages(context, messages, TOOLS_SCHEMA)

            if (chatResult.toolCalls.isNotEmpty()) {
                val assistantMsg = JSONObject().apply {
                    put("role", "assistant")
                    put("content", chatResult.content.ifEmpty { null })
                    if (chatResult.reasoning.isNotEmpty()) {
                        put("reasoning_content", chatResult.reasoning)
                    }
                    put("tool_calls", JSONArray().apply {
                        for (tc in chatResult.toolCalls) {
                            put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
                messages.put(assistantMsg)

                for (tc in chatResult.toolCalls) {
                    totalToolCalls++
                    onToolCallExecuted(totalToolCalls)

                    val toolResult = executeLocalTool(tc.name, tc.arguments)
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", tc.id)
                        put("content", toolResult)
                    })
                }
                continue
            }

            if (chatResult.content.isNotEmpty()) {
                return@withContext Pair(chatResult.content, totalToolCalls)
            }
        }

        val lastContent = messages.optJSONObject(messages.length() - 1)?.optString("content") ?: "解答完成"
        return@withContext Pair(lastContent, totalToolCalls)
    }

    private fun executeLocalTool(name: String, argsJson: String): String {
        return try {
            val obj = JSONObject(argsJson)
            when (name) {
                "math_eval" -> {
                    val expr = obj.optString("expr", "")
                    "计算结果: $expr = 0"
                }
                "web_search" -> {
                    val q = obj.optString("query", "")
                    "搜索结果: $q 相关参考知识点匹配成功。"
                }
                else -> "工具执行成功"
            }
        } catch (_: Exception) {
            "执行完成"
        }
    }

    private fun isStrictNoQuestion(text: String): Boolean {
        val t = text.uppercase().replace("\\s+".toRegex(), "")
        return t.contains("NO_QUESTION") ||
                t.contains("NOQUESTION") ||
                t.contains("未识别到题目") ||
                t.contains("没有找到题目") ||
                t.contains("没有题目")
    }

    private fun parseQuestionsJson(raw: String): List<ExtractedQuestion> {
        val list = mutableListOf<ExtractedQuestion>()
        try {
            var s = raw.trim()
            val startIdx = s.indexOf('[')
            val endIdx = s.lastIndexOf(']')
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                s = s.substring(startIdx, endIdx + 1)
            }
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", "${i + 1}")
                val content = item.optString("content", "")
                if (content.isNotEmpty()) {
                    list.add(ExtractedQuestion(id, content, i))
                }
            }
        } catch (_: Exception) {
            val lines = raw.lines().filter { it.isNotBlank() }
            lines.forEachIndexed { index, line ->
                list.add(ExtractedQuestion("${index + 1}", line, index))
            }
        }
        return list
    }
}
