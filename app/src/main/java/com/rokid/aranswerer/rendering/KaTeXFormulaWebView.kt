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
 * 1. 资源全部内置于 assets/katex 目录 (包含 katex.min.js, katex.min.css, auto-render.min.js 与 20 种 woff2 矢量字体)；
 * 2. 零外部网络依赖，瞬间完成初始化；
 * 3. 完美兼容标准 LaTeX 行内 $...$ 与块级 $$...$$ 渲染。
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

        // 直接从本地 assets 加载全部离线资源
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
}
