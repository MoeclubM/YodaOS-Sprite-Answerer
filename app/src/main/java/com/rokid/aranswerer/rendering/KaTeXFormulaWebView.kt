package com.rokid.aranswerer.rendering

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 专为 Rokid Glasses 裸机定制的 100% 全离线 KaTeX 数学与 Markdown 渲染器
 * 1. 采用本地 assets 离线加载；
 * 2. 增强多通道硬件与 JS 滚动，直接驱动 pageUp / pageDown / scrollBy / JS scrollTop；
 * 3. 完美兼容标准 LaTeX 纤细分数线与公式排版。
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
     * 强力多通道滚动调度：同时触发 pageUp/pageDown、原生 scrollBy 与 JS manualScroll
     */
    fun smoothScroll(deltaY: Int) {
        post {
            // 1. 调用 JS 内部的 manualScroll 与 window.scrollBy
            evaluateJavascript("if (typeof window.manualScroll === 'function') { window.manualScroll($deltaY); } else { window.scrollBy(0, $deltaY); }", null)
            
            // 2. 原生 WebView 滚动
            scrollBy(0, deltaY)
            
            // 3. 原生 pageUp / pageDown 辅助触发
            if (deltaY > 0) {
                pageDown(false)
            } else if (deltaY < 0) {
                pageUp(false)
            }
        }
    }
}
