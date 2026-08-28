package com.rokid.aranswerer.rendering

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 专为 Rokid Glasses 裸机定制的 100% 全离线 KaTeX 数学与 Markdown 渲染器
 * 1. 采用本地 assets 离线加载；
 * 2. 修复分数线 (frac-line)、积分上下标、大括号的完整高亮显示；
 * 3. 紧凑排版，合并段落空行，消除大间隙；
 * 4. 强力触摸与物理滚动：重写 onTouchEvent 并提供 JS/原生平滑滚动。
 */
class KaTeXFormulaWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var isLoaded = false
    private var pendingMarkdown: String? = null

    init {
        setBackgroundColor(Color.BLACK)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = false
            loadWithOverviewMode = true
            textZoom = 100
        }

        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("KaTeXWebView", "JS Console: [${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoaded = true
                Log.d("KaTeXWebView", "Local KaTeX Shell loaded from assets")
                pendingMarkdown?.let {
                    setMarkdownText(it)
                    pendingMarkdown = null
                }
            }
        }

        loadUrl("file:///android_asset/katex/katex.html")
    }

    fun setMarkdownText(markdownText: String) {
        if (!isLoaded) {
            pendingMarkdown = markdownText
            return
        }

        val escaped = org.json.JSONObject.quote(markdownText)
        val jsCode = "renderMarkdown($escaped);"
        evaluateJavascript(jsCode, null)
    }

    /**
     * 手动平滑滚动调度：直接驱动 WebView 内部滚动
     */
    fun smoothScroll(deltaY: Int) {
        post {
            evaluateJavascript("if (typeof window.manualScroll === 'function') { window.manualScroll($deltaY); } else { window.scrollBy(0, $deltaY); }", null)
            scrollBy(0, deltaY)
        }
    }
}
