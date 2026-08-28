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

        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
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
                Log.d("KaTeXWebView", "KaTeX Shell Loaded -> isLoaded=true")
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
            Log.d("KaTeXWebView", "setMarkdownText pending: length=${markdownText.length}")
            pendingMarkdown = markdownText
            return
        }

        val escaped = org.json.JSONObject.quote(markdownText)
        val jsCode = "renderMarkdown($escaped);"
        evaluateJavascript(jsCode) { res ->
            Log.d("KaTeXWebView", "renderMarkdown evaluated: $res")
        }
    }

    fun smoothScroll(deltaY: Int) {
        post {
            evaluateJavascript("if (typeof window.manualScroll === 'function') { window.manualScroll($deltaY); }") { res ->
                Log.d("KaTeXWebView", "smoothScroll($deltaY) evaluated: $res")
            }
        }
    }
}
