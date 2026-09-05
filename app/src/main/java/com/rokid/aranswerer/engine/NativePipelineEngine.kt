package com.rokid.aranswerer.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.rokid.aranswerer.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
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

/** 400/404 类错误重试同模型无意义,直接进下一个模型。 */
private class NonRetryableException(message: String) : RuntimeException(message)

object NativePipelineEngine {
    private const val TAG = "NativePipelineEngine"

    // 严选 4 款 100% 原生多模态旗舰模型
    val AVAILABLE_MODELS = listOf(
        "gemini-3.8-flash",
        "deepseek-v4-flash-vision-exp",
        "muse-spark-1.3",
        "GLM-5.3-Flash"
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

    private fun chatEndpoint(rawBase: String): String {
        val base = rawBase.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v4") || base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/chat/completions"
        }
    }

    private fun getProviderConfig(context: Context, modelName: String): ProviderConfig {
        val model = modelName.ifBlank { currentModel }
        val lower = model.lowercase()

        val customDeepSeekKey = ConfigManager.getDeepSeekApiKey(context).trim()
        val customZhipuKey = ConfigManager.getZhipuApiKey(context).trim()
        val primaryKey = ConfigManager.getPrimaryApiKey(context).trim()

        // 按实际配置路由:
        // - GLM-5.3-Flash -> 优先智谱官方,官方 key 为空才回落 Primary 网关。
        // - deepseek-v4-flash-vision-exp -> 优先 DeepSeek 官方(vision 已发布),无 key 才回落网关。
        // - muse-spark-1.3 / gemini-3.8-flash -> 只走 Primary 网关(newapi),官方直连不认识这些名字。
        val isGlm = lower.contains("glm")
        val isDeepSeek = lower.contains("deepseek")
        if (isGlm && customZhipuKey.isNotEmpty()) {
            return ProviderConfig(
                endpoint = chatEndpoint(ConfigManager.getZhipuApiBase(context)),
                key = customZhipuKey,
                model = model
            )
        }
        if (isDeepSeek && customDeepSeekKey.isNotEmpty()) {
            return ProviderConfig(
                endpoint = chatEndpoint(ConfigManager.getDeepSeekApiBase(context)),
                key = customDeepSeekKey,
                model = model
            )
        }

        val primaryBase = ConfigManager.getPrimaryApiBase(context)
        return ProviderConfig(endpoint = chatEndpoint(primaryBase), key = primaryKey, model = model)
    }

    private data class ProviderConfig(
        val endpoint: String,
        val key: String,
        val model: String
    )

    private class ToolCallAcc(var id: String, var name: String, val args: StringBuilder)

    /**
     * 眼镜 sensor 横装,实拍像素固定偏转 90°(显示器竖立、字横躺)。
     * 佩戴方向固定,因此固定逆时针转 90°,输出 3024x4032 竖构图,再 q85 重编码。
     * 不做任何自适应判断,方向恒定。
     */
    private fun compressForModel(jpegBytes: ByteArray, quality: Int = 85): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
            val matrix = android.graphics.Matrix().apply { postRotate(270f) }
            val upright = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            upright.recycle()
            val result = out.toByteArray()
            Log.d(TAG, "Image rotated+compressed: ${jpegBytes.size} -> ${result.size} bytes (fixed 270deg CCW, q$quality)")
            if (result.isEmpty()) jpegBytes else result
        } catch (e: Exception) {
            Log.w(TAG, "Image compress failed, use original: ${e.message}")
            jpegBytes
        }
    }

    private suspend fun streamMessages(
        context: Context,
        messages: JSONArray,
        tools: JSONArray? = null,
        timeoutMs: Long = 60000L,
        maxAttempts: Int = 2,
        maxTokens: Int? = null,
        reasoningEffort: String? = null,
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

            for (attempt in 1..maxAttempts) {
                var conn: HttpURLConnection? = null
                try {
                    Log.d(TAG, "Requesting model ${provider.model} (attempt $attempt)...")
                    // 整体超时由 timeoutMs 控制(Stage1 45s,Stage2/3 60s);
                    // readTimeout 60s 只兜底 OS 层阻塞,避免慢模型首字延迟被误杀。
                    val result = withTimeout(timeoutMs) {
                        val body = JSONObject().apply {
                            put("model", provider.model)
                            put("messages", messages)
                            put("stream", true)
                            // 官方 DeepSeek 支持 tool_calls(见 api-docs DeepSeek-V3.2+);智谱官方与网关亦兼容,
                            // 因此 tools 一律透传,不再按厂商剔除。
                            if (tools != null && tools.length() > 0) {
                                put("tools", tools)
                            }
                            if (maxTokens != null && maxTokens > 0) {
                                put("max_tokens", maxTokens)
                            }
                            // Stage1 低思考强度:网关/DeepSeek/智谱均接受 reasoning_effort=low,
                            // 不支持的厂商会忽略该字段(已实测 200)。
                            if (!reasoningEffort.isNullOrEmpty()) {
                                put("reasoning_effort", reasoningEffort)
                            }
                        }

                        val payload = body.toString().toByteArray(Charsets.UTF_8)
                        val url = URL(provider.endpoint)
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 15000
                            // readTimeout 兜底 OS 层阻塞;整体超时由外层 withTimeout 控制,到点直接 fallback。
                            readTimeout = 60000
                            doOutput = true
                            doInput = true
                            // 发固定长度避免 chunked 上传:网关对 chunked 大 body 易提前 client_gone。
                            setFixedLengthStreamingMode(payload.size)
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("Accept", "text/event-stream")
                            setRequestProperty("Cache-Control", "no-cache")
                            setRequestProperty("Connection", "close")
                            setRequestProperty("Authorization", "Bearer ${provider.key}")
                        }

                        conn!!.outputStream.use { os ->
                            os.write(payload)
                            os.flush()
                        }

                        val code = conn!!.responseCode
                        if (code !in 200..299) {
                            val errBody = try {
                                conn!!.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            } catch (_: Exception) { "" }
                            // 400/404(模型名不对/图片格式被拒)重试同模型无意义,直接进下一个模型。
                            if (code == 400 || code == 404) {
                                Log.w(TAG, "Model ${provider.model} HTTP $code, skip retry: $errBody")
                                throw NonRetryableException("HTTP $code: $errBody")
                            }
                            throw RuntimeException("HTTP $code: $errBody")
                        }

                        // 用 BufferedReader 按行读 SSE:旧写法逐字节 read() 攒 buffer,弱网下
                        // 长时间无回调,体感就是 Stage1 卡死;按行读 + 空流即判错,停滞直接走 fallback。
                        // 注意:网关偶发把多条 SSE 打在一个 TCP 包里,readLine 逐行返回不受影响;
                        // 若网关把整流攒成一个超大行,readLine 会等整行齐才返回,属网关行为,外层 timeoutMs 兜底。
                        val reader = BufferedReader(InputStreamReader(conn!!.inputStream, Charsets.UTF_8), 8192)
                        val contentAcc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        val toolCallMap = mutableMapOf<Int, ToolCallAcc>()
                        var lastTokenAt = 0L

                        while (true) {
                            // readLine 阻塞等待网关首字;配合 readTimeout 兜底。
                            // BufferedReader 内部已有缓冲,弱网下不会像裸 read() 那样长时间空转。
                            val line: String? = try {
                                reader.readLine()
                            } catch (e: java.net.SocketTimeoutException) {
                                throw RuntimeException("流停滞超过60s无字节(client_gone/网关超时),中断重试")
                            }
                            if (line == null) break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                            if (!trimmed.startsWith("data:")) continue
                            val dataStr = trimmed.substring(5).trim()
                            if (dataStr.isEmpty() || dataStr.contains("[DONE]")) {
                                if (dataStr.contains("[DONE]")) break
                                continue
                            }
                            try {
                                val chunk = JSONObject(dataStr)
                                // 先看 usage 包:网关按量计费,超限/欠费时只发 usage 不发 choices,旧逻辑会误判空流。
                                // 这里只记录不中断,真正的空流判定仍在流末统一做。
                                val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                                // 兼容 stream/non-stream 与 message/delta 两种形态;兼容智谱的 message 形态。
                                val delta = choice?.optJSONObject("delta")
                                    ?: choice?.optJSONObject("message")
                                // 兼容部分网关把 tool_calls 放在 message 层而非 delta 层。
                                val topToolCalls = choice?.optJSONObject("message")?.optJSONArray("tool_calls")
                                if (delta == null) {
                                    // 错误包形态:{"error": {...}} 直接抛出去走 fallback,不在 Stage1 干等。
                                    val errObj = chunk.optJSONObject("error")
                                    if (errObj != null) throw RuntimeException("网关错误: $errObj")
                                    continue
                                }

                                val c = delta.optString("content", "")
                                if (c.isNotEmpty() && c != "null") {
                                    contentAcc.append(c)
                                    // 节流回调 UI:每 300ms 推一次,避免每 token 切一次主线程阻塞流读取。
                                    val now = System.currentTimeMillis()
                                    if (onToken != null && now - lastTokenAt > 300) {
                                        lastTokenAt = now
                                        onToken(contentAcc.toString())
                                    }
                                }

                                val r = delta.optString("reasoning_content", "")
                                if (r.isNotEmpty() && r != "null") {
                                    reasoningAcc.append(r)
                                }
                                // 兼容智谱 / GLM 系把推理放在 reasoning_content 或 message.reasoning_content 的情况已在上式覆盖。

                                val toolCallsArr = delta.optJSONArray("tool_calls") ?: topToolCalls
                                if (toolCallsArr != null) {
                                    for (i in 0 until toolCallsArr.length()) {
                                        val tcObj = toolCallsArr.optJSONObject(i) ?: continue
                                        val idx = tcObj.optInt("index", i)
                                        val id = tcObj.optString("id", "")
                                        val fnObj = tcObj.optJSONObject("function")
                                        // 后续分片 id/name 常为空,必须保留首次非空值,旧 Triple 写法会丢失。
                                        val fnName = fnObj?.optString("name", "") ?: ""
                                        val fnArgsDelta = fnObj?.optString("arguments", "") ?: ""

                                        val existing = toolCallMap.getOrPut(idx) {
                                            ToolCallAcc(id, fnName, StringBuilder())
                                        }
                                        if (existing.id.isEmpty() && id.isNotEmpty()) existing.id = id
                                        if (existing.name.isEmpty() && fnName.isNotEmpty()) existing.name = fnName
                                        existing.args.append(fnArgsDelta)
                                    }
                                }
                            } catch (e: RuntimeException) {
                                // 网关 error 包直接向上传走 fallback,不吞掉。
                                throw e
                            } catch (_: Exception) {
                                // 单个 SSE 脏行跳过,不中断整流。
                            }
                        }

                        try { reader.close() } catch (_: Exception) {}

                        val finalContent = contentAcc.toString().trim()
                        val finalReasoning = reasoningAcc.toString().trim()

                        // 流正常结束但一字未吐:视为网关 client_gone/空包,直接抛错走下一个模型,
                        // 而不是把空串当成功返回让 Stage1 在下游空转。
                        if (finalContent.isEmpty() && finalReasoning.isEmpty() && toolCallMap.isEmpty()) {
                            throw RuntimeException("网关返回空流(疑似client_gone),切换模型重试")
                        }
                        if (onToken != null && finalContent.isNotEmpty()) {
                            onToken(finalContent)
                        }

                        val resultText = if (finalContent.isNotEmpty()) {
                            finalContent
                        } else {
                            finalReasoning
                        }

                        val finalToolCalls = toolCallMap.values.map {
                            ToolCallInfo(it.id, it.name, it.args.toString())
                        }
                        StreamChatResult(resultText, finalReasoning, finalToolCalls)
                    }

                    currentModel = targetModel
                    Log.d(TAG, "Request success with model ${provider.model}")
                    return@withContext result
                } catch (e: Exception) {
                    Log.w(TAG, "Model ${provider.model} attempt $attempt error: ${e.message}")
                    lastErr = e
                    // NonRetryable(400/404)直接跳下一个模型,不浪费第二次 attempt。
                    if (e is NonRetryableException) break
                    if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "Model ${provider.model} overall timeout ${timeoutMs}ms, fallback")
                        break
                    }
                    if (attempt < maxAttempts) {
                        delay(500)
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
                    "deepseek-v4-flash-vision-exp" -> "DeepSeek"
                    "muse-spark-1.3" -> "MuseSpark"
                    "GLM-5.3-Flash" -> "GLM-5.3-Flash"
                    else -> nextModel
                }
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
            // 增量解析只认已闭合的 {...}:旧正则的非贪婪 content 会在流式半截 JSON 上跨项吞题,
            // 导致 Stage1 进度条乱跳。半截的等下个 onToken 再认。
            // 先剥掉 <think> 思考块(含流式未闭合的半截):思考内容里的引号/题号不能算题,
            // 否则 GLM 这类长思考模型会被误解析出上百道“假题”(曾出现 137 题爆炸)。
            var clean = rawStreamText.replace(Regex("(?s)<think>.*?</think>"), "")
            val openIdx = clean.lastIndexOf("<think>")
            if (openIdx != -1) {
                clean = clean.substring(0, openIdx)
            }
            val jsonObjectPattern = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*?)\"[^}]*?\"has_image\"\\s*:\\s*(true|false)[^}]*?\\}", Pattern.DOTALL)
            val matcher = jsonObjectPattern.matcher(clean)
            var count = 0
            while (matcher.find()) {
                // 题目数熔断:正常一拍最多十几道,超过 20 直接截断,防止思考残留或模型复读撑爆 Stage2。
                if (count >= 20) {
                    Log.w(TAG, "Stage1 incremental question count fused at 20")
                    break
                }
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
        onStage3StreamToken: suspend (String) -> Unit,
        onStage3Enter: (suspend () -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        // 相机已取竖构图 1080x1920,不做 EXIF 旋转,直接按 500KB 目标压缩。
        val compressed = compressForModel(jpegBytes)
        val base64Image = Base64.encodeToString(compressed, Base64.NO_WRAP)
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
        // Stage1 只做提取:限 45s + max_tokens 4096 + reasoning_effort low,到点没回直接 fallback 下一个模型,
        // 不再一个慢模型上干等 60s×2 次 + 串行 4 个模型。
        // 4096 给推理链留足空间:1024 会把 thinking 截断导致空流误判 fallback。
        val stage1Result = streamMessages(
            context, stage1Messages,
            timeoutMs = 45000L, maxAttempts = 1, maxTokens = 4096, reasoningEffort = "low",
            onToken = { streamAcc ->
                val partial = parseIncrementalQuestions(streamAcc)
                if (partial.size > lastDispatchedCount) {
                    lastDispatchedCount = partial.size
                    withContext(Dispatchers.Main) {
                        onStage1QuestionsUpdate(partial)
                    }
                }
            }
        )

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
        // 先通知 UI 切走 Stage2 状态(清三列√与计数),再等排版首字,中间不再挂旧状态。
        if (onStage3Enter != null) {
            withContext(Dispatchers.Main) {
                onStage3Enter()
            }
        }
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
            val finalResult = streamMessages(context, stage3Messages, timeoutMs = 45000L, maxAttempts = 1)
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
        var lastAssistantText = ""

        while (turn < maxTurns) {
            turn++
            // Stage2 单轮同样单次尝试:4 个模型的 fallback 链本身就是 4 次机会,
            // 再每模型重试只会把单题最坏耗时撑到 8 分钟。
            val chatResult = streamMessages(context, messages, TOOLS_SCHEMA, timeoutMs = 60000L, maxAttempts = 1)
            if (chatResult.content.isNotEmpty()) {
                lastAssistantText = chatResult.content
            }

            if (chatResult.toolCalls.isNotEmpty()) {
                val assistantMsg = JSONObject().apply {
                    put("role", "assistant")
                    // content 为空时直接省略该 key:显式 put null 会被部分网关拒收 400。
                    if (chatResult.content.isNotEmpty()) {
                        put("content", chatResult.content)
                    }
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

                    // 工具名/id 为空的分片直接丢弃:回填给网关会 400,还会污染下一轮。
                    if (tc.name.isBlank()) {
                        Log.w(TAG, "Drop tool_call with blank name (id=${tc.id})")
                        continue
                    }
                    val toolResult = executeLocalTool(tc.name, tc.arguments)
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", tc.id.ifEmpty { "call_${turn}_${totalToolCalls}" })
                        put("content", toolResult)
                    })
                }
                continue
            }

            if (chatResult.content.isNotEmpty()) {
                return@withContext Pair(chatResult.content, totalToolCalls)
            }
            // 既无 tool_calls 也无正文(如纯 reasoning 就截断):记下已有的 reasoning 继续下一轮,
            // 而不是空转 3 轮后跌入下面的兜底。
            if (chatResult.reasoning.isNotEmpty()) {
                lastAssistantText = chatResult.reasoning
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", "继续给出最终解答。")
                })
                continue
            }
        }

        // 3 轮无终答:返回最后一次助手正文(可能含部分 tool 结果),而不是把“继续给出最终解答”
        // 这种过程提示词当成答案喂给 Stage3。
        val fallback = lastAssistantText.ifBlank { "解答完成" }
        return@withContext Pair(fallback, totalToolCalls)
    }

    private fun executeLocalTool(name: String, argsJson: String): String {
        return try {
            val obj = JSONObject(argsJson)
            when (name) {
                "math_eval" -> {
                    val expr = obj.optString("expr", "")
                    if (expr.isBlank()) "未提供表达式，无法计算"
                    else ToolRegistry.executeCalculate(expr)
                }
                "search_knowledge" -> {
                    val q = obj.optString("query", "")
                    if (q.isBlank()) "未提供检索关键词，无法检索知识库"
                    else KnowledgeBase.formatKnowledgeResult(q)
                }
                "web_search" -> {
                    "本地暂不支持联网搜索，请基于已有知识与题目条件继续推导"
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
            // 先清掉 <think> / ```json 围栏等推理残留,再取最外层 [...]。
            // 含流式未闭合的半截 <think>:思考内容里的行不能算题,否则 GLM 长思考会炸出上百道“假题”。
            s = s.replace(Regex("(?s)<think>.*?</think>"), "")
            val openThink = s.lastIndexOf("<think>")
            if (openThink != -1) {
                s = s.substring(0, openThink)
            }
            s = s.replace("```json", "").replace("```", "").trim()
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
                // has_image 兼容 "true"/1/yes 等字符串形态,避免全部误判 false 导致 Stage2 丢图。
                val hasImg = when (val v = item.opt("has_image")) {
                    is Boolean -> v
                    is Number -> v.toInt() != 0
                    is String -> v.equals("true", ignoreCase = true) || v == "1" || v.equals("yes", ignoreCase = true)
                    else -> false
                }
                if (content.isNotEmpty()) {
                    list.add(ExtractedQuestion(id, content, hasImg, i))
                }
            }
        } catch (_: Exception) {
            // JSON 整体解析失败(如 max_tokens 截断):先尝试按已闭合的 {...} 逐项抢救,
            // 抢救无果才按行兜底,且兜底出来的题一律 has_image=true 走带图解答,不丢信息。
            try {
                val itemPattern = Pattern.compile("\\{[^{}]*\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])+)\"[^{}]*\\}", Pattern.DOTALL)
                val m = itemPattern.matcher(raw)
                var idx = 0
                while (m.find()) {
                    val content = m.group(1)?.replace("\\n", " ")?.replace("\\\"", "\"")?.trim() ?: ""
                    if (content.length > 5) {
                        list.add(ExtractedQuestion("${idx + 1}", content, true, idx))
                        idx++
                    }
                }
            } catch (_: Exception) {}
            if (list.isEmpty()) {
                val lines = raw.lines().filter { it.isNotBlank() }
                lines.forEachIndexed { index, line ->
                    list.add(ExtractedQuestion("${index + 1}", line, true, index))
                }
            }
        }
        return list
    }
}
