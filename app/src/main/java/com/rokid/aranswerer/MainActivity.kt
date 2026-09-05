package com.rokid.aranswerer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rokid.aranswerer.camera.NativeCamera2Helper
import com.rokid.aranswerer.engine.NativePipelineEngine
import com.rokid.aranswerer.input.BareGlassesInputDispatcher
import com.rokid.aranswerer.rendering.KaTeXFormulaWebView
import com.rokid.aranswerer.ui.settings.SettingsDialog
import com.rokid.aranswerer.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var safeContent: FrameLayout
    private lateinit var batteryStepView: TextView
    private lateinit var status: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var previewCard: FrameLayout
    private lateinit var textureView: TextureView
    
    private var katexWebView: KaTeXFormulaWebView? = null
    private var stageTextView: TextView? = null
    private var cameraHelper: NativeCamera2Helper? = null

    private var state = 0 // 0 sleep, 1 preview, 2 solving, 3 answer
    private var currentStep = 0 // 当前阶段 step (0=休眠, 1=提取, 2=解答, 3=排版答案)

    private var input: BareGlassesInputDispatcher? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector

    private var startX = 0f
    private var startY = 0f

    private var currentModelIndex = 0

    private var lastModelSwitchTime = 0L
    private val MODEL_SWITCH_DEBOUNCE_MS = 350L

    private val longPressToCaptureRunnable = Runnable {
        if (state == 0) {
            Log.d("ARAnswerer", "Touchpad 400ms LongPress confirmed -> enterPreview")
            enterPreview()
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        window.setFormat(PixelFormat.OPAQUE)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        safeContent = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(safeContent, FrameLayout.LayoutParams(-1, -1))

        val density = resources.displayMetrics.density
        val previewW = (280 * density).toInt()
        val previewH = (180 * density).toInt()

        previewCard = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        textureView = TextureView(this).apply { alpha = 0.35f }
        previewCard.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        safeContent.addView(previewCard, FrameLayout.LayoutParams(previewW, previewH).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = (45 * density).toInt() })

        contentContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.VISIBLE
        }
        safeContent.addView(contentContainer, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = (36 * density).toInt()
            leftMargin = (6 * density).toInt()
            rightMargin = (6 * density).toInt()
            bottomMargin = (12 * density).toInt()
        })

        stageTextView = TextView(this).apply {
            setTextColor(0xff00ff66.toInt())
            textSize = 13.5f
            setLineSpacing(3f, 1.25f)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        contentContainer.addView(stageTextView, FrameLayout.LayoutParams(-1, -1))

        katexWebView = KaTeXFormulaWebView(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        contentContainer.addView(katexWebView, FrameLayout.LayoutParams(-1, -1))

        // 1. 左上角：暗色电量 + 当前 Step 显示 (如 "52 1" 或 "60 2")
        batteryStepView = TextView(this).apply {
            setTextColor(0x7700ff66.toInt())
            textSize = 13f
            setPadding(16, 12, 24, 16)
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        safeContent.addView(batteryStepView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        updateBatteryStepDisplay()
        
        // 2. 状态栏
        status = TextView(this).apply {
            setTextColor(0xff00ff66.toInt())
            textSize = 13f
            setSingleLine(true)
            setPadding(80, 12, 16, 12)
            visibility = View.GONE
        }
        safeContent.addView(status, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP or Gravity.START })

        NativePipelineEngine.onModelFallbackHint = { fallbackModelName ->
            runOnUiThread {
                showModelFallbackNotification(fallbackModelName)
            }
        }

        // 3. 触控手势探测器：严格单向纯净判定
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val deltaY = if (e1 != null) e2.y - e1.y else 0f
                val deltaX = if (e1 != null) e2.x - e1.x else 0f

                // 优先以垂直物理位移为准
                if (abs(deltaY) > abs(deltaX)) {
                    if (deltaY < -20) {
                        // 向上滑动 -> 切模型
                        handleSwipeUp()
                        return true
                    } else if (deltaY > 20) {
                        // 向下滑动 -> 进拍摄
                        handleSwipeDown()
                        return true
                    }
                } else {
                    // 水平方向
                    if (deltaX > 20) {
                        handleSwipeUp()
                        return true
                    } else if (deltaX < -20) {
                        handleSwipeDown()
                        return true
                    }
                }
                return false
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (state == 3) {
                    katexWebView?.smoothScroll(distanceY.toInt())
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (state != 0) {
                    goSleep()
                    return true
                }
                return false
            }
        })

        setContentView(root)
        goSleep()

        // 4. 硬件输入分发器
        input = BareGlassesInputDispatcher(
            context = this,
            onTriggerCapture = {
                if (state == 0) enterPreview()
            },
            onSwipeUp = {
                Log.d("ARAnswerer", "Hardware Swipe Up triggered: state=$state")
                handleSwipeUp()
            },
            onSwipeDown = {
                Log.d("ARAnswerer", "Hardware Swipe Down triggered: state=$state")
                handleSwipeDown()
            }
        ).also { it.start() }

        AudioHelper.forceMute(this)
        WifiHelper.autoConnect(this)
        checkBatteryOptimizationSilently()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
        }
        try { startService(Intent(this, KeepAliveService::class.java)) } catch (_: Exception) {}
    }

    private fun checkBatteryOptimizationSilently() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getSharedPreferences("ar_answerer_config", Context.MODE_PRIVATE)
                val hasPrompted = prefs.getBoolean("has_prompted_battery_opt", false)
                if (!hasPrompted) {
                    prefs.edit().putBoolean("has_prompted_battery_opt", true).apply()
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.w("ARAnswerer", "Battery optimization check skipped: ${e.message}")
        }
    }

    private fun updateBatteryStepDisplay(step: Int? = null) {
        if (step != null) {
            currentStep = step
        }
        val level = BatteryHelper.level(this)
        batteryStepView.text = if (currentStep > 0) "$level $currentStep" else "$level"
    }

    private fun showModelFallbackNotification(modelName: String) {
        status.visibility = View.VISIBLE
        status.text = "切换至 $modelName"
        handler.removeCallbacks(hideModelStatusRunnable)
        handler.postDelayed(hideModelStatusRunnable, 1000)
    }

    /**
     * 上滑操作：休眠态切模型，答案态向上翻页
     */
    private fun handleSwipeUp() {
        if (state == 0) {
            Log.d("ARAnswerer", "handleSwipeUp -> switchModel(1)")
            switchModel(1)
        } else if (state == 3) {
            Log.d("ARAnswerer", "handleSwipeUp -> Answer Scroll Up (-180px)")
            katexWebView?.smoothScroll(-180)
        }
    }

    /**
     * 下滑操作：休眠态进拍摄，取景态提前抓拍，答案态向下翻页
     */
    private fun handleSwipeDown() {
        if (state == 0) {
            Log.d("ARAnswerer", "handleSwipeDown -> enterPreview")
            enterPreview()
        } else if (state == 1) {
            Log.d("ARAnswerer", "handleSwipeDown -> triggerHardwareCapture")
            triggerHardwareCapture()
        } else if (state == 3) {
            Log.d("ARAnswerer", "handleSwipeDown -> Answer Scroll Down (+180px)")
            katexWebView?.smoothScroll(180)
        }
    }

    private fun openSettingsDialog() {
        try {
            SettingsDialog(this).show()
        } catch (e: Exception) {
            Log.e("ARAnswerer", "openSettingsDialog error", e)
        }
    }

    private fun switchModel(direction: Int) {
        val now = System.currentTimeMillis()
        if (now - lastModelSwitchTime < MODEL_SWITCH_DEBOUNCE_MS) {
            return
        }
        lastModelSwitchTime = now

        val models = NativePipelineEngine.AVAILABLE_MODELS
        currentModelIndex = (currentModelIndex + direction + models.size) % models.size
        val chosen = models[currentModelIndex]
        NativePipelineEngine.currentModel = chosen

        val displayName = when (chosen) {
            "gemini-3.8-flash" -> "Gemini"
            "deepseek-v4-flash-vision-exp" -> "DeepSeek"
            "muse-spark-1.3" -> "MuseSpark"
            "GLM-5.3-Flash" -> "GLM-5.3-Flash"
            else -> chosen
        }

        status.visibility = View.VISIBLE
        status.text = displayName
        handler.removeCallbacks(hideModelStatusRunnable)
        handler.postDelayed(hideModelStatusRunnable, 2000)
    }

    private val hideModelStatusRunnable = Runnable {
        if (state == 0) {
            status.visibility = View.GONE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val settingsTouchAreaWidth = 90 * density
        val settingsTouchAreaHeight = 70 * density

        if (ev.x >= (screenW - settingsTouchAreaWidth) && ev.y <= settingsTouchAreaHeight) {
            if (ev.action == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(longPressToCaptureRunnable)
                openSettingsDialog()
                return true
            } else if (ev.action == MotionEvent.ACTION_DOWN) {
                return true
            }
        }

        gestureDetector.onTouchEvent(ev)

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                if (state == 0) {
                    handler.postDelayed(longPressToCaptureRunnable, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == 0 && (abs(ev.x - startX) > 25 || abs(ev.y - startY) > 25)) {
                    handler.removeCallbacks(longPressToCaptureRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressToCaptureRunnable)
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == 202 || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (state != 0) {
                    goSleep()
                    return true
                }
            }
        }
        if (input?.dispatchKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(c: Int, e: KeyEvent?): Boolean {
        if (input?.handleKeyDown(c, e) == true) return true
        return super.onKeyDown(c, e)
    }

    private fun enterPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
            return
        }
        state = 1
        updateBatteryStepDisplay(step = 1)
        contentContainer.visibility = View.GONE
        previewCard.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "取景中..."

        cameraHelper?.stop()
        cameraHelper = NativeCamera2Helper(
            context = this,
            textureView = textureView,
            onFrameCaptured = { bytes ->
                runOnUiThread {
                    if (state != 1) {
                        Log.d("ARAnswerer", "Drop late/duplicate frame: state=$state")
                    } else if (bytes.isNotEmpty()) solve(bytes) else goSleep()
                }
            },
            onError = { err ->
                Log.e("ARAnswerer", "camera error: $err")
                runOnUiThread {
                    status.text = err
                    goSleep()
                }
            }
        ).also { it.start() }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (state == 1) {
                status.text = "正在拍照..."
                cameraHelper?.takePicture()
            }
        }, 3000)
    }

    private fun triggerHardwareCapture() {
        if (state == 1) {
            handler.removeCallbacksAndMessages(null)
            status.text = "正在拍照..."
            cameraHelper?.takePicture()
        }
    }

    private fun formatSingleLineTruncated(id: String, content: String, maxChars: Int = 19): String {
        val clean = content.replace("\\s+".toRegex(), " ").trim()
        val prefix = "$id. "
        val budget = maxChars - prefix.length
        val body = if (clean.length > budget && budget > 0) clean.substring(0, budget - 1) + "…" else clean
        return prefix + body
    }

    private fun solve(bytes: ByteArray) {
        state = 2
        updateBatteryStepDisplay(step = 1)
        previewCard.visibility = View.GONE
        
        lifecycleScope.launch(Dispatchers.IO) {
            cameraHelper?.stop()
            cameraHelper = null
        }

        status.visibility = View.GONE
        status.text = ""
        contentContainer.visibility = View.VISIBLE
        stageTextView?.visibility = View.VISIBLE
        katexWebView?.visibility = View.GONE
        stageTextView?.text = ""

        lifecycleScope.launch {
            try {
                val result = NativePipelineEngine.runThreeStagePipeline(
                    this@MainActivity,
                    bytes,
                    onStage1QuestionsUpdate = { qs ->
                        withContext(Dispatchers.Main) {
                            updateBatteryStepDisplay(step = 1)
                            status.visibility = View.GONE
                            stageTextView?.visibility = View.VISIBLE
                            katexWebView?.visibility = View.GONE
                            stageTextView?.text = qs.joinToString("\n") { formatSingleLineTruncated(it.id, it.content) }
                        }
                    },
                    onStage2TripleColumnUpdate = { ss, topStatusText ->
                        withContext(Dispatchers.Main) {
                            updateBatteryStepDisplay(step = 2)
                            status.visibility = View.VISIBLE
                            status.text = topStatusText
                            stageTextView?.visibility = View.VISIBLE
                            katexWebView?.visibility = View.GONE
                            stageTextView?.text = ss.sortedBy { it.originalOrder }.chunked(3).joinToString("\n") { row ->
                                row.joinToString("  ") { "[${it.id}:${if (it.toolCount > 0) "T" + it.toolCount else ""}${if (it.isDone) "√" else "..."}]" }
                            }
                        }
                    },
                    onStage3StreamToken = { streamText ->
                        withContext(Dispatchers.Main) {
                            updateBatteryStepDisplay(step = 3)
                            status.visibility = View.GONE
                            stageTextView?.visibility = View.GONE
                            katexWebView?.visibility = View.VISIBLE
                            katexWebView?.setMarkdownText(streamText)
                        }
                    }
                )
                state = 3
                updateBatteryStepDisplay(step = 3)
                status.visibility = View.GONE
                stageTextView?.visibility = View.GONE
                katexWebView?.visibility = View.VISIBLE
                katexWebView?.setMarkdownText(result)
            } catch (e: Exception) {
                state = 3
                updateBatteryStepDisplay(step = 3)
                status.visibility = View.VISIBLE
                status.text = "解题失败: ${e.message}"
            }
        }
    }

    private fun ensureKatexWebViewLoaded(): KaTeXFormulaWebView {
        if (katexWebView == null) {
            contentContainer.removeAllViews()
            katexWebView = KaTeXFormulaWebView(this).apply {
                setBackgroundColor(Color.BLACK)
            }
            contentContainer.addView(katexWebView, FrameLayout.LayoutParams(-1, -1))
        } else if (katexWebView?.parent == null) {
            contentContainer.removeAllViews()
            contentContainer.addView(katexWebView, FrameLayout.LayoutParams(-1, -1))
        }
        return katexWebView!!
    }

    private fun goSleep() {
        state = 0
        updateBatteryStepDisplay(step = 0)
        handler.removeCallbacksAndMessages(null)
        status.visibility = View.GONE
        contentContainer.visibility = View.GONE
        previewCard.visibility = View.GONE
        stageTextView?.visibility = View.GONE
        katexWebView?.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            cameraHelper?.stop()
            cameraHelper = null
        }
    }

    override fun onBackPressed() {
        if (state != 0) {
            goSleep()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        input?.stop()
        cameraHelper?.stop()
        katexWebView?.destroy()
        katexWebView = null
        super.onDestroy()
    }
}
