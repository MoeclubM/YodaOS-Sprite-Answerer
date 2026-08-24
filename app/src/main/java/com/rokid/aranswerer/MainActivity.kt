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
    private lateinit var battery: TextView
    private lateinit var status: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var previewCard: FrameLayout
    private lateinit var textureView: TextureView
    
    private var katexWebView: KaTeXFormulaWebView? = null
    private var cameraHelper: NativeCamera2Helper? = null

    private var state = 0 // 0 sleep, 1 preview, 2 solving, 3 answer
    private var isEasyMode = false // 是否为单轮秒出简单模式 (双指长按进入)
    private var input: BareGlassesInputDispatcher? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector

    private var startX = 0f
    private var startY = 0f
    private var touchDownTime = 0L

    private var currentModelIndex = 0

    private var lastModelSwitchTime = 0L
    private val MODEL_SWITCH_DEBOUNCE_MS = 400L

    private val longPressToCaptureRunnable = Runnable {
        if (state == 0) {
            // 单指长按：进入专业三阶段 Agent 模式
            enterPreview(easyMode = false)
        }
    }

    private val twoFingerLongPressRunnable = Runnable {
        if (state == 0) {
            // 双指长按：进入单轮秒出简单模式 (Easy-Answerer)
            Log.d("ARAnswerer", "Two-finger long press -> enterPreview Easy Mode")
            enterPreview(easyMode = true)
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        window.setFormat(PixelFormat.OPAQUE)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.attributes = window.attributes.apply { screenBrightness = 0.25f }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        safeContent = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(safeContent, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = 214 })

        val density = resources.displayMetrics.density
        val previewW = (280 * density).toInt()
        val previewH = (180 * density).toInt()

        previewCard = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        textureView = TextureView(this)
        previewCard.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        safeContent.addView(previewCard, FrameLayout.LayoutParams(previewW, previewH).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = (45 * density).toInt() })

        contentContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        safeContent.addView(contentContainer, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = (44 * density).toInt()
            leftMargin = (10 * density).toInt()
            rightMargin = (10 * density).toInt()
        })

        // 1. 电量左上角常驻：点击电量区域打开设置面板
        battery = TextView(this).apply {
            setTextColor(0xff00ff66.toInt())
            textSize = 14f
            text = "${BatteryHelper.level(this@MainActivity)}%"
            setPadding(16, 12, 24, 16)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                openSettingsDialog()
            }
        }
        safeContent.addView(battery, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        
        // 2. 状态栏：极简排版
        status = TextView(this).apply {
            setTextColor(0xff00ff66.toInt())
            textSize = 13f
            setSingleLine(true)
            setPadding(80, 12, 16, 12)
            visibility = View.GONE
        }
        safeContent.addView(status, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP or Gravity.START })

        // 触控手势探测器
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (state == 0) {
                    if (vx < -120 || vy > 120) switchModel(1)
                    else if (vx > 120 || vy < -120) switchModel(-1)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (state == 1 || state == 2 || state == 3) {
                    goSleep()
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (state == 3) {
                    goSleep()
                    return true
                }
                return false
            }
        })

        setContentView(root)
        root.requestFocus()
        goSleep()

        // 输入分发器：支持单指长按、双指长按简单模式、滑动切模型
        input = BareGlassesInputDispatcher(
            context = this,
            onTriggerCapture = {
                // 单指长按广播 -> 触发专业三阶段 Agent 拍摄
                if (state == 0) enterPreview(easyMode = false)
            },
            onSingleTapAction = {
                if (state == 3) goSleep()
            },
            onScrollAction = { deltaY ->
                if (state == 0) {
                    switchModel(if (deltaY > 0) 1 else -1)
                } else if (state == 3) {
                    katexWebView?.scrollBy(0, deltaY)
                }
            },
            onOpenSettings = {
                // 双指长按广播 (ACTION_SETTINGS_KEY) -> 触发单轮简单模式 (Easy-Answerer)
                if (state == 0) {
                    runOnUiThread { enterPreview(easyMode = true) }
                }
            }
        ).also { it.start() }

        AudioHelper.forceMute(this); WifiHelper.autoConnect(this)
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
        try { if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
        try { startService(Intent(this, KeepAliveService::class.java)) } catch (_: Exception) {}
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
            "gemini-3.7-flash" -> "Gemini"
            "deepseek-v4-flash-vision-exp" -> "DeepSeek"
            "gpt-5.6-luna" -> "Luna"
            "muse-spark-1.2" -> "MuseSpark"
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
        val batteryTouchAreaWidth = 140 * density
        val batteryTouchAreaHeight = 60 * density

        // 点击电量区域 -> 打开设置面板
        if (ev.x <= batteryTouchAreaWidth && ev.y <= batteryTouchAreaHeight) {
            if (ev.action == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(longPressToCaptureRunnable)
                handler.removeCallbacks(twoFingerLongPressRunnable)
                openSettingsDialog()
                return true
            } else if (ev.action == MotionEvent.ACTION_DOWN) {
                return true
            }
        }

        // 双指长按手势检测 -> 进入简单模式 (Easy-Answerer)
        if (ev.pointerCount >= 2) {
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                handler.removeCallbacks(longPressToCaptureRunnable)
                if (state == 0) {
                    handler.postDelayed(twoFingerLongPressRunnable, 400)
                }
                return true
            }
        }

        gestureDetector.onTouchEvent(ev)
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                touchDownTime = System.currentTimeMillis()
                if (state == 0 && ev.pointerCount == 1) {
                    handler.postDelayed(longPressToCaptureRunnable, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == 0 && (abs(ev.x - startX) > 25 || abs(ev.y - startY) > 25)) {
                    handler.removeCallbacks(longPressToCaptureRunnable)
                    handler.removeCallbacks(twoFingerLongPressRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressToCaptureRunnable)
                handler.removeCallbacks(twoFingerLongPressRunnable)
            }
        }
        return true
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

    /**
     * 进入拍摄取景模式 (easyMode=true 为简单秒出模式，easyMode=false 为三阶段 Agent 模式)
     */
    private fun enterPreview(easyMode: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
            return
        }
        state = 1
        isEasyMode = easyMode
        contentContainer.visibility = View.GONE
        previewCard.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = if (easyMode) "简单秒出模式 (取景中)..." else "Agent 深度模式 (取景中)..."

        cameraHelper?.stop()
        cameraHelper = NativeCamera2Helper(
            context = this,
            textureView = textureView,
            onFrameCaptured = { bytes ->
                runOnUiThread {
                    if (bytes.isNotEmpty()) solve(bytes, isEasyMode) else goSleep()
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

    private fun solve(bytes: ByteArray, easyMode: Boolean) {
        state = 2
        previewCard.visibility = View.GONE
        
        lifecycleScope.launch(Dispatchers.IO) {
            cameraHelper?.stop()
            cameraHelper = null
        }

        status.visibility = View.VISIBLE
        contentContainer.visibility = View.VISIBLE
        contentContainer.removeAllViews()

        if (easyMode) {
            // ================= 简单秒出模式 (Easy-Answerer) =================
            status.text = "正在秒出答案..."
            ensureKatexWebViewLoaded().setMarkdownText("")

            lifecycleScope.launch {
                try {
                    val result = NativePipelineEngine.runEasyModePipeline(
                        this@MainActivity,
                        bytes,
                        onStreamToken = { streamAcc ->
                            withContext(Dispatchers.Main) {
                                status.visibility = View.GONE
                                ensureKatexWebViewLoaded().setMarkdownText(streamAcc)
                            }
                        }
                    )
                    state = 3
                    status.visibility = View.GONE
                    ensureKatexWebViewLoaded().setMarkdownText(result)
                } catch (e: Exception) {
                    state = 3
                    status.visibility = View.VISIBLE
                    status.text = "秒出失败: ${e.message}"
                }
            }
        } else {
            // ================= 三阶段 Agent 模式 (Agent-Answerer) =================
            status.text = ""
            val stageTextView = TextView(this).apply {
                setTextColor(0xff00ff66.toInt())
                textSize = 13f
                setLineSpacing(4f, 1.2f)
                setBackgroundColor(Color.BLACK)
            }
            contentContainer.addView(stageTextView, FrameLayout.LayoutParams(-1, -1))

            lifecycleScope.launch {
                try {
                    val result = NativePipelineEngine.runThreeStagePipeline(
                        this@MainActivity,
                        bytes,
                        onStage1QuestionsUpdate = { qs ->
                            withContext(Dispatchers.Main) {
                                stageTextView.text = qs.joinToString("\n") { "${it.id}. ${it.content.trim()}" }
                            }
                        },
                        onStage2DoubleColumnUpdate = { ss, topStatusText ->
                            withContext(Dispatchers.Main) {
                                status.text = topStatusText
                                stageTextView.text = ss.sortedBy { it.originalOrder }.chunked(2).joinToString("\n") { row -> row.joinToString("  ") { "[${it.id}][T:${it.toolCount}]${if (it.isDone) "√" else "..."}" } }
                            }
                        },
                        onStage3StreamToken = { streamText ->
                            withContext(Dispatchers.Main) {
                                status.visibility = View.GONE
                                ensureKatexWebViewLoaded().setMarkdownText(streamText)
                            }
                        }
                    )
                    state = 3
                    status.visibility = View.GONE
                    ensureKatexWebViewLoaded().setMarkdownText(result)
                } catch (e: Exception) {
                    state = 3
                    status.visibility = View.VISIBLE
                    status.text = "解题失败: ${e.message}"
                }
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
        }
        return katexWebView!!
    }

    private fun goSleep() {
        state = 0
        handler.removeCallbacksAndMessages(null)
        status.visibility = View.GONE
        contentContainer.visibility = View.GONE
        previewCard.visibility = View.GONE
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

    override fun onResume() {
        super.onResume()
        battery.text = "${BatteryHelper.level(this)}%"
    }

    override fun onDestroy() {
        input?.stop()
        cameraHelper?.stop()
        katexWebView?.destroy()
        katexWebView = null
        super.onDestroy()
    }
}
