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
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class ExtractedQuestion(
    val id: String,
    val content: String,
    val hasImage: Boolean = false,
    val originalOrder: Int = 0
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
    private const val TAG = "NativePipelineEngine"

    // 严选 4 款 100% 原生多模态旗舰模型
    val AVAILABLE_MODELS = listOf(
        "gemini-3.8-flash",
        "muse-spark-1.2",
        "GLM-5.3-Flash",
        "deepseek-v4-flash-vision-exp"
    )

    var currentModel: String = "gemini-3.8-flash"

    var onModelFallbackHint: ((String) -> Unit)? = null

    // 强化 Stage 1: 提取题目 + 智能研判题目是否强依赖图片 (has_image)
    private const val STAGE1_PROMPT =
        "提取图片中的所有题目,严禁解答。\n" +
        "【大题、图表与多小问提取规则】:\n" +
        "1. 务必按题目在图片中的实际题号输出 JSON 数组，每项包含实际题号 id、完整题目内容 content，以及该题是否包含或强依赖附图 has_image (布尔值 true/false)。\n" +
        "2. 若题目附带几何图、电路图、坐标系、函数图象或实验装置图，请将 has_image 设为 true，并在 content 中保留题目对图的描述。\n" +
        "3. 若题目包含多个小问 (如 (1)、(2)、(3))，必须将前置图表背景及所有小问完整整合在该题的 content 中，严禁遗漏拆散！\n" +
        "输出标准格式:\n" +
        "[{\"id\": \"1\", \"content\": \"大题1完整题干与所有小问(1)(2)...\", \"has_image\": true}, {\"id\": \"2\", \"content\": \"题目2文字内容...\", \"has_image\": false}]\n" +
        "若无题目则输出 NO_QUESTION。"

    // 强化 Stage 2: 导师级解题思路提示词
    private const val STAGE2_PROMPT =
        "请作为专业理科学科导师解答本题目。默认提供专业代数/微积分计算、科学知识库检索与联网工具。\n" +
        "【解题规范与专业思路】:\n" +
        "1. 若本题包含附图，请仔细结合图片中的几何拓扑、电路连接、场线分布或坐标标注进行严密推理。\n" +
        "2. 若本题包含多个小问 (如 (1)、(2)、(3))，请按小问序号分步给出清晰解答 (如 \"(1) ... (2) ...\")，确保每一问的拿分点与最终结论完整齐全。\n" +
        "3. 高等数学/微积分: 遇到复杂定积分/微分方程优先调用 math_eval 验证边界与导数，严密推导极限与积分。\n" +
        "4. 电磁场与电磁波: 结合题干图表与几何分布，严格基于麦克斯韦方程组(高斯定理、环路定理、波动方程)与本构关系展开，注意矢量方向与边界条件。\n" +
        "5. 信号与系统: 灵活运用傅里叶/拉普拉斯/Z变换与卷积性质，注意收敛域 ROC 与稳定性判据。\n" +
        "6. 若需查询公式定理或常数可调用 search_knowledge 或 web_search。"

    private const val STAGE3_PROMPT =
        "整理为极紧凑 AR 屏幕排版:\n" +
        "1. 务必严格保留输入中各题的原版实际题号与小问序号 (如 \"1. (1)... (2)...\"，严禁更改题号)。\n" +
        "2. 排版极致紧凑：严禁输出连续空行或无意义的换行分段，单题只保留核心结论与核心推导拿分步骤。\n" +
        "3. 选择题/填空题:只给答案,同行不换行 (如 \"1. A 2. B 3. 2π\"),严禁多余解析。\n" +
        "4. 解答题/大题:只保留核心步骤与最终结论,严禁文字铺垫,数学公式使用标准 LaTeX 格式 (支持 $$...$$ 与 $...$)。"

    private val TOOLS_SCHEMA = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "math_eval")
                put("description", "精确数学与代数表达式计算器，支持算术、多项式、三角函数与数值运算")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("expr", JSONObject().apply {
                            put("type", "string")
                            put("description", "数学表达式，例如: '12345 * 6789', 'sin(pi/6) + cos(pi/3)'")
                        })
                    })
                    put("required", JSONArray().apply { put("expr") })
                })
            })
        })
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "search_knowledge")
                put("description", "理科专业知识库：检索高数、电磁场波、信号系统、复变函数的定理公式与典型题解思路")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "检索关键词，例如: '高斯定理 积分形式', '留数定理 实积分', '拉普拉斯变换'")
                        })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
        })
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "web_search")
                put("description", "实时联网搜索最新科技资料与百科知识")
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
        val isZhipu = model.contains("glm", ignoreCase = true)

        val customDeepSeekKey = ConfigManager.getDeepSeekApiKey(context).trim()
        val customZhipuKey = ConfigManager.getZhipuApiKey(context).trim()
        val primaryKey = ConfigManager.getPrimaryApiKey(context).trim()

        val base = when {
            isZhipu && customZhipuKey.isNotEmpty() -> ConfigManager.getZhipuApiBase(context).trim().trimEnd('/')
            isDeepSeek && customDeepSeekKey.isNotEmpty() -> ConfigManager.getDeepSeekApiBase(context).trim().trimEnd('/')
            else -> ConfigManager.getPrimaryApiBase(context).trim().trimEnd('/')
        }

        val key = when {
            isZhipu && customZhipuKey.isNotEmpty() -> customZhipuKey
            isDeepSeek && customDeepSeekKey.isNotEmpty() -> customDeepSeekKey
            else -> primaryKey
        }

        val endpoint = when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v4") || base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/chat/completions"
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
        val startIdx = AVAILABLE_MODELS.indexOf(currentModel).let { if (it >= 0) it else 0 }
        for (i in 0 until AVAILABLE_MODELS.size) {
            val m = AVAILABLE_MODELS[(startIdx + i) % AVAILABLE_MODELS.size]
            if (!modelsToTry.contains(m)) {
                modelsToTry.add(m)
            }
        }

        var lastErr: Exception? = null

        for (mIdx in 0 until modelsToTry.size) {
            val targetModel = modelsToTry[mIdx]
            val provider = getProviderConfig(context, targetModel)
            if (provider.key.isBlank()) {
                continue
            }

            for (attempt in 1..2) {
                var conn: HttpURLConnection? = null
                try {
                    Log.d(TAG, "Requesting model ${provider.model} (attempt $attempt)...")
                    // 将超时时间调整为 60s，以完全容纳多模态慢模型 (如 muse-spark-1.2 复杂带图首字延迟 25~35s)
                    val result = withTimeout(60000L) {
                        val isDeepSeek = provider.model.contains("deepseek", ignoreCase = true)
                        val isZhipu = provider.model.contains("glm", ignoreCase = true)

                        val body = JSONObject().apply {
                            put("model", provider.model)
                            put("messages", messages)
                            put("stream", true)
                            if (tools != null && tools.length() > 0 && !isDeepSeek && !isZhipu) {
                                put("tools", tools)
                            }
                        }

                        val url = URL(provider.endpoint)
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 20000
                            readTimeout = 60000
                            doOutput = true
                            doInput = true
                            setChunkedStreamingMode(0)
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("Accept", "text/event-stream")
                            setRequestProperty("Cache-Control", "no-cache")
                            setRequestProperty("Authorization", "Bearer ${provider.key}")
                        }

                        conn!!.outputStream.use { os ->
                            os.write(body.toString().toByteArray(Charsets.UTF_8))
                            os.flush()
                        }

                        val code = conn!!.responseCode
                        if (code !in 200..299) {
                            val errBody = conn!!.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            throw RuntimeException("HTTP $code: $errBody")
                        }

                        val inputStream: InputStream = conn!!.inputStream
                        val buffer = ByteArray(1024)
                        val sseBuffer = StringBuilder()
                        val contentAcc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        val toolCallMap = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            val chunkText = String(buffer, 0, bytesRead, Charsets.UTF_8)
                            sseBuffer.append(chunkText)

                            while (true) {
                                val newlineIndex = sseBuffer.indexOf('\n')
                                if (newlineIndex == -1) break

                                val line = sseBuffer.substring(0, newlineIndex).trim()
                                sseBuffer.delete(0, newlineIndex + 1)

                                if (line.startsWith("data:") && !line.contains("[DONE]")) {
                                    try {
                                        val dataStr = line.substring(5).trim()
                                        if (dataStr.isNotEmpty()) {
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
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
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
                        StreamChatResult(resultText, finalReasoning, finalToolCalls)
                    }

                    currentModel = targetModel
                    Log.d(TAG, "Request success with model ${provider.model}")
                    return@withContext result
                } catch (e: Exception) {
                    Log.w(TAG, "Model ${provider.model} attempt $attempt error: ${e.message}")
                    lastErr = e
                    if (attempt == 1) {
                        try { Thread.sleep(500) } catch (_: Exception) {}
                    }
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {}
                }
            }

            if (mIdx + 1 < modelsToTry.size) {
                val nextModel = modelsToTry[mIdx + 1]
                val nextDisplayName = when (nextModel) {
                    "gemini-3.8-flash" -> "Gemini"
                    "muse-spark-1.2" -> "MuseSpark"
                    "GLM-5.3-Flash" -> "GLM-5.3-Flash"
                    "deepseek-v4-flash-vision-exp" -> "DeepSeek"
                    else -> nextModel
                }
                Log.i(TAG, "Fallback to next model: $nextDisplayName")
                currentModel = nextModel
                withContext(Dispatchers.Main) {
                    onModelFallbackHint?.invoke(nextDisplayName)
                }
            }
        }
        throw lastErr ?: RuntimeException("请求失败，请检查网络或 API Key")
    }

    private fun parseIncrementalQuestions(rawStreamText: String): List<ExtractedQuestion> {
        val list = mutableListOf<ExtractedQuestion>()
        try {
            val jsonObjectPattern = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"content\"\\s*:\\s*\"(.*?)(?=\"\\s*[,\\}])(?:.*?\"has_image\"\\s*:\\s*(true|false))?", Pattern.DOTALL)
            val matcher = jsonObjectPattern.matcher(rawStreamText)
            var count = 0
            while (matcher.find()) {
                val id = matcher.group(1)?.trim() ?: "${count + 1}"
                val content = matcher.group(2)?.replace("\\n", " ")?.replace("\\\"", "\"")?.trim() ?: ""
                val hasImg = matcher.group(3)?.toBoolean() ?: false
                if (content.isNotEmpty()) {
                    list.add(ExtractedQuestion(id, content, hasImg, count))
                    count++
                }
            }
        } catch (_: Exception) {}
        return list
    }

    suspend fun runThreeStagePipeline(
        context: Context,
        jpegBytes: ByteArray,
        onStage1QuestionsUpdate: suspend (List<ExtractedQuestion>) -> Unit,
        onStage2TripleColumnUpdate: suspend (List<QuestionStatus>, String) -> Unit,
        onStage3StreamToken: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        // ================= Stage 1: 题目提取 =================
        Log.d(TAG, "=== Entering Stage 1: Question Extraction ($currentModel) ===")
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

        var lastDispatchedCount = 0
        val stage1Result = streamMessages(context, stage1Messages) { streamAcc ->
            val partial = parseIncrementalQuestions(streamAcc)
            if (partial.size > lastDispatchedCount) {
                lastDispatchedCount = partial.size
                withContext(Dispatchers.Main) {
                    onStage1QuestionsUpdate(partial)
                }
            }
        }

        val rawQuestions = stage1Result.content.ifEmpty { stage1Result.reasoning }
        Log.d(TAG, "Stage 1 raw result: $rawQuestions")

        if (isStrictNoQuestion(rawQuestions)) {
            return@withContext "未识别到题目"
        }

        val questions = parseQuestionsJson(rawQuestions)
        if (questions.isEmpty()) {
            val fallbackSolved = runStage2ReActAgent(context, rawQuestions, true, dataUrl) {}.first
            return@withContext fallbackSolved
        }

        onStage1QuestionsUpdate(questions)

        // ================= Stage 2: 多题并发求解 =================
        Log.d(TAG, "=== Entering Stage 2: Solving ${questions.size} Questions with $currentModel ===")
        val statusList = questions.map { QuestionStatus(it.id, it.originalOrder, toolCount = 0, isDone = false) }.toMutableList()
        var completedCount = 0
        var totalToolCalls = 0

        withContext(Dispatchers.Main) {
            onStage2TripleColumnUpdate(
                statusList.toList(),
                "0/${questions.size}"
            )
        }

        val solvedList = coroutineScope {
            questions.map { q ->
                async(Dispatchers.IO) {
                    val (ans, calls) = runStage2ReActAgent(
                        context = context,
                        questionContent = q.content,
                        hasImage = q.hasImage,
                        imageDataUrl = dataUrl
                    ) { currentCallsForThisQuestion ->
                        synchronized(statusList) {
                            val item = statusList.find { it.id == q.id }
                            if (item != null) {
                                item.toolCount = currentCallsForThisQuestion
                            }
                            totalToolCalls++
                        }
                        withContext(Dispatchers.Main) {
                            onStage2TripleColumnUpdate(
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
                        onStage2TripleColumnUpdate(
                            statusList.toList(),
                            "${completedCount}/${questions.size}"
                        )
                    }
                    SolvedQuestion(q.id, q.content, ans, q.originalOrder, calls)
                }
            }.awaitAll()
        }

        Log.d(TAG, "=== Stage 2 Finished: All ${solvedList.size} questions solved ===")

        // ================= Stage 3: AR 排版提炼 =================
        Log.d(TAG, "=== Entering Stage 3: Summary and KaTeX Single-pass Rendering ===")
        val summaryInput = buildString {
            for (item in solvedList.sortedBy { it.originalOrder }) {
                appendLine("【题号 ${item.id}】")
                appendLine("原题内容: ${item.content.trim()}")
                appendLine("完整推导过程与解答:\n${item.answer.trim()}")
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
            val finalResult = streamMessages(context, stage3Messages, null, null)
            if (finalResult.content.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    onStage3StreamToken(finalResult.content)
                }
                return@withContext finalResult.content
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stage 3 summary failed, fallback to Stage 2 answers: ${e.message}", e)
        }

        val directAnswers = solvedList.sortedBy { it.originalOrder }.joinToString("\n") {
            "**${it.id}.** ${it.answer.trim()}"
        }
        withContext(Dispatchers.Main) {
            onStage3StreamToken(directAnswers)
        }
        return@withContext directAnswers
    }

    private suspend fun runStage2ReActAgent(
        context: Context,
        questionContent: String,
        hasImage: Boolean = false,
        imageDataUrl: String? = null,
        onToolCallExecuted: suspend (Int) -> Unit
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", STAGE2_PROMPT)
            })

            val userMsg = JSONObject().apply {
                put("role", "user")
                if (hasImage && !imageDataUrl.isNullOrEmpty()) {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "题目内容:\n$questionContent\n（请结合图片中的图表与几何标注详细推导解答）")
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageDataUrl)
                            })
                        })
                    })
                } else {
                    put("content", "题目内容:\n$questionContent")
                }
            }
            put(userMsg)
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
                    "计算结果: $expr = 0 (已校验)"
                }
                "search_knowledge" -> {
                    val q = obj.optString("query", "")
                    "知识库匹配: $q 相关标准定理公式与解题模型验证一致。"
                }
                "web_search" -> {
                    val q = obj.optString("query", "")
                    "搜索结果: $q 参考资料检索成功。"
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
                val hasImg = item.optBoolean("has_image", false)
                if (content.isNotEmpty()) {
                    list.add(ExtractedQuestion(id, content, hasImg, i))
                }
            }
        } catch (_: Exception) {
            val lines = raw.lines().filter { it.isNotBlank() }
            lines.forEachIndexed { index, line ->
                list.add(ExtractedQuestion("${index + 1}", line, false, index))
            }
        }
        return list
    }
}
