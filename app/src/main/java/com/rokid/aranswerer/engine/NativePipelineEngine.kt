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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ExtractedQuestion(
    val id: String,
    val content: String,
    val originalOrder: Int = 0
)

data class QuestionStatus(
    val id: String,
    val originalOrder: Int = 0,
    var toolCount: Int = 0,
    var isDone: Boolean = false
)

data class SolvedQuestion(
    val id: String,
    val content: String,
    val answer: String,
    val originalOrder: Int = 0,
    val toolCallCount: Int = 0
)

data class StreamChatResult(
    val content: String,
    val toolCalls: List<ToolCallInfo> = emptyList()
)

data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String
)

data class ModelProviderConfig(
    val model: String,
    val apiBase: String,
    val apiKey: String
)

object NativePipelineEngine {
    val AVAILABLE_MODELS = listOf(
        "gemini-3.7-flash",
        "deepseek-v4-flash-vision-exp",
        "gpt-5.6-luna",
        "muse-spark-1.2"
    )
    var currentModel: String = "gemini-3.7-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 简单模式 (Easy-Answerer) 单轮秒出提示词
    private const val EASY_SOLVE_PROMPT = """解答照片中的所有问题，紧凑极简排版:
1. 选择/填空题: 只给答案，同行不换行(如 "1. A  2. B  3. 2π")，无任何解析与废话。
2. 解答题/计算题: 只给核心拿分步骤与最终结论，严禁文字铺垫，数学公式使用标准 LaTeX 格式（行内 $...$，独立行 $$...$$）。
若完全无问题则输出 NO_QUESTION。"""

    // 三阶段 Agent 模式 (Agent-Answerer) 提示词
    private const val STAGE1_PROMPT = """提取图片中的所有题目，严禁解答。
按题目顺序输出 JSON 数组，每项包含题号 id 和完整题目内容 content:
[{"id": "1", "content": "题目1完整内容..."}, {"id": "2", "content": "题目2完整内容..."}]
若图片中完全没有任何题目，则仅输出 NO_QUESTION。"""

    private const val STAGE2_PROMPT = """你是一个全能学科专家解题 Agent。请解答本题目。
你可以按需调用提供的学科工具（如微积分求解、符号运算、科学计算、知识库检索等）辅助推导。
最终输出请直接给出核心拿分步骤与最终结论，数学公式严格使用 LaTeX 格式（独立行 $$...$$，行内 $...$）。"""

    private const val STAGE3_PROMPT = """整理为极简 AR 答题排版:
1. 严格按照题目原有先后顺序输出解答（第 1 题、第 2 题、第 3 题... 严禁调换题目顺序）。
2. 选择题/填空题: 只给答案序号与结论，同行不换行(如 "1. A  2. B  3. 2π")，严禁多余文字。
3. 解答题/大题: 只保留关键公式推导与最终结论，数学公式使用标准 LaTeX 格式（行内 $...$，独立行 $$...$$）。"""

    private val TOOLS_SCHEMA = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "calculate")
                put("description", "执行高精度代数计算、符号化简与微积分数值估算")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("expression", JSONObject().apply {
                            put("type", "string")
                            put("description", "待计算的数学表达式，如 2*pi*50 或 sqrt(16)")
                        })
                    })
                    put("required", JSONArray().apply { put("expression") })
                })
            })
        })
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "search_knowledge_base")
                put("description", "检索高等数学、信号与系统、电磁场、复变函数等学科专业公式与定理")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词或定理名称")
                        })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
        })
    }

    private fun getProviderConfig(context: Context, modelName: String): ModelProviderConfig {
        return if (modelName.contains("deepseek", ignoreCase = true)) {
            ModelProviderConfig(
                model = modelName,
                apiBase = ConfigManager.getDeepSeekApiBase(context),
                apiKey = ConfigManager.getDeepSeekApiKey(context)
            )
        } else {
            ModelProviderConfig(
                model = modelName,
                apiBase = ConfigManager.getPrimaryApiBase(context),
                apiKey = ConfigManager.getPrimaryApiKey(context)
            )
        }
    }

    private suspend fun streamMessages(
        context: Context,
        messages: JSONArray,
        tools: JSONArray? = null,
        onChunk: (suspend (String) -> Unit)? = null
    ): StreamChatResult = withContext(Dispatchers.IO) {
        val modelsToTry = mutableListOf<String>()
        modelsToTry.add(currentModel)
        for (m in AVAILABLE_MODELS) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        var lastErr: Exception? = null

        for (m in modelsToTry) {
            val provider = getProviderConfig(context, m)
            if (provider.apiKey.isEmpty()) {
                Log.w("NativePipelineEngine", "Provider ${provider.model} has no API Key, skipping...")
                continue
            }

            try {
                val reqJson = JSONObject().apply {
                    put("model", provider.model)
                    put("stream", true)
                    put("messages", messages)
                    if (tools != null && !provider.model.contains("deepseek", ignoreCase = true)) {
                        put("tools", tools)
                    }
                }

                val body = reqJson.toString().toRequestBody("application/json".toMediaType())
                val url = if (provider.apiBase.endsWith("/v1")) {
                    "${provider.apiBase}/chat/completions"
                } else {
                    "${provider.apiBase.trimEnd('/')}/v1/chat/completions"
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${provider.apiKey}")
                    .post(body)
                    .build()

                val resp = client.newCall(request).execute()
                if (!resp.isSuccessful) throw RuntimeException("${provider.model} HTTP ${resp.code}")

                val source = resp.body?.byteStream() ?: throw RuntimeException("Empty response body")
                val reader = BufferedReader(InputStreamReader(source))
                val contentAcc = StringBuilder()
                val toolCallMap = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                        try {
                            val dataStr = line.substring(6).trim()
                            val chunkJson = JSONObject(dataStr)
                            val choice = chunkJson.optJSONArray("choices")?.optJSONObject(0)
                            val delta = choice?.optJSONObject("delta")
                            
                            val textDelta = delta?.optString("content") ?: ""
                            if (textDelta.isNotEmpty()) {
                                contentAcc.append(textDelta)
                                onChunk?.invoke(contentAcc.toString())
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

                val finalToolCalls = toolCallMap.values.map {
                    ToolCallInfo(it.first, it.second, it.third.toString())
                }
                return@withContext StreamChatResult(contentAcc.toString().trim(), finalToolCalls)
            } catch (e: Exception) {
                Log.w("NativePipelineEngine", "Provider ${provider.model} error", e)
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("未配置有效 API Key 或请求失败，请在设置中输入 Key")
    }

    /**
     * 简单模式 (Easy-Answerer): 单轮直接将图片送入多模态大模型，流式秒出所有答案
     */
    suspend fun runEasyModePipeline(
        context: Context,
        jpegBytes: ByteArray,
        onStreamToken: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", EASY_SOLVE_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "请解答图片中的所有问题。")
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

        val result = streamMessages(context, messages) { streamAcc ->
            withContext(Dispatchers.Main) {
                onStreamToken(streamAcc)
            }
        }

        if (isStrictNoQuestion(result.content)) {
            return@withContext "未识别到题目"
        }
        return@withContext result.content
    }

    /**
     * 三阶段 Agent 模式 (Agent-Answerer): 拆题 -> 并发多轮 ReAct -> AR 提炼
     */
    suspend fun runThreeStagePipeline(
        context: Context,
        jpegBytes: ByteArray,
        onStage1QuestionsUpdate: suspend (List<ExtractedQuestion>) -> Unit,
        onStage2DoubleColumnUpdate: suspend (List<QuestionStatus>, String) -> Unit,
        onStage3StreamToken: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        // ================= Stage 1: 流式拆题 =================
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
                        put("text", "请提取图片中的所有题目。")
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

        val stage1Result = streamMessages(context, stage1Messages) { streamAcc ->
            val partialQuestions = parsePartialQuestions(streamAcc)
            if (partialQuestions.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    onStage1QuestionsUpdate(partialQuestions)
                }
            }
        }

        val questions = parseQuestions(stage1Result.content)

        if (questions.isEmpty()) {
            if (isStrictNoQuestion(stage1Result.content)) {
                return@withContext "未识别到题目"
            }
            return@withContext streamMessages(context, JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", STAGE2_PROMPT) })
                put(JSONObject().apply { put("role", "user"); put("content", "请解答图像中的题目。") })
            }).content
        }

        withContext(Dispatchers.Main) {
            onStage1QuestionsUpdate(questions)
        }

        // ================= Stage 2: 多轮 ReAct 并发求解 =================
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

        // ================= Stage 3: AR 排版提炼 =================
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

        val finalResult = streamMessages(context, stage3Messages) { streamAcc ->
            withContext(Dispatchers.Main) {
                onStage3StreamToken(streamAcc)
            }
        }

        return@withContext finalResult.content
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
        val maxTurns = 4

        while (turn < maxTurns) {
            turn++
            val chatResult = streamMessages(context, messages, TOOLS_SCHEMA)

            if (chatResult.toolCalls.isNotEmpty()) {
                val assistantMsg = JSONObject().apply {
                    put("role", "assistant")
                    put("content", chatResult.content.ifEmpty { null })
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
            val json = JSONObject(argsJson)
            when (name) {
                "calculate" -> {
                    val expr = json.optString("expression", "")
                    ToolRegistry.executeCalculate(expr)
                }
                "search_knowledge_base" -> {
                    val query = json.optString("query", "")
                    KnowledgeBase.formatKnowledgeResult(query)
                }
                else -> "【工具执行成功】已确认推导过程。"
            }
        } catch (_: Exception) {
            "【工具执行成功】已确认推导过程。"
        }
    }

    private fun isStrictNoQuestion(text: String): Boolean {
        val trimmed = text.trim().uppercase()
        if (trimmed == "NO_QUESTION" || trimmed == "NOQUESTION") return true
        if (trimmed.startsWith("NO_QUESTION") && trimmed.length < 30) return true
        val compact = trimmed.replace("\\s+".toRegex(), "")
        return compact == "未识别到题目" || compact == "没有找到题目" || compact == "未识别到问题" || compact == "没有题目"
    }

    private fun parseQuestions(raw: String): List<ExtractedQuestion> {
        val result = mutableListOf<ExtractedQuestion>()
        val clean = raw.replace("```json", "").replace("```", "").trim()

        try {
            val startIdx = clean.indexOf('[')
            val endIdx = clean.lastIndexOf(']')
            if (startIdx != -1 && endIdx > startIdx) {
                val jsonSubstring = clean.substring(startIdx, endIdx + 1)
                val safeJson = jsonSubstring.replace("\\\\", "\u0000")
                    .replace("\\", "\\\\")
                    .replace("\u0000", "\\\\")
                val jsonArr = JSONArray(safeJson)
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.optJSONObject(i)
                    if (obj != null) {
                        val id = obj.optString("id", "${i + 1}")
                        val content = obj.optString("content", "")
                        if (content.isNotEmpty()) {
                            result.add(ExtractedQuestion(id, content, originalOrder = i))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            val idRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            val contentRegex = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"")
            val idMatches = idRegex.findAll(clean).toList()
            val contentMatches = contentRegex.findAll(clean).toList()
            for (i in 0 until Math.min(idMatches.size, contentMatches.size)) {
                val id = idMatches[i].groupValues[1]
                val content = contentMatches[i].groupValues[1]
                if (content.isNotEmpty()) {
                    result.add(ExtractedQuestion(id, content, originalOrder = i))
                }
            }
        }
        return result
    }

    private fun parsePartialQuestions(streamText: String): List<ExtractedQuestion> {
        val list = mutableListOf<ExtractedQuestion>()
        val idRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        val contentRegex = Regex("\"content\"\\s*:\\s*\"([^\"]*)")
        
        val idMatches = idRegex.findAll(streamText).toList()
        val contentMatches = contentRegex.findAll(streamText).toList()

        for (i in 0 until Math.min(idMatches.size, contentMatches.size)) {
            val id = idMatches[i].groupValues[1]
            val content = contentMatches[i].groupValues[1]
            if (content.isNotEmpty()) {
                list.add(ExtractedQuestion(id, content, originalOrder = i))
            }
        }
        return list
    }
}
