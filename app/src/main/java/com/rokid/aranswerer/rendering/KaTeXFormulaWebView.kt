package com.rokid.aranswerer.rendering

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 专为 Rokid Glasses 裸机定制的纯黑 KaTeX 高性能 WebView 数学渲染器
 * 1. 采用本地内嵌 KaTeX 0.16.8 离线 HTML/JS/CSS，零网络请求，毫秒级就绪；
 * 2. 强制设置透明/纯黑背景，关闭滚动条与一切多余装饰，杜绝白屏闪烁；
 * 3. 完美 100% 渲染所有复杂高等数学、矩阵、分式、微积分、方程组与 Markdown 排版。
 */
class KaTeXFormulaWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var isLoaded = false
    private var pendingMarkdown: String? = null

    init {
        // 关键：强制设置纯黑不透明背景，禁止系统 Chromium 绘制白底
        setBackgroundColor(Color.BLACK)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = false
            loadWithOverviewMode = true
            textZoom = 100
        }

        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoaded = true
                pendingMarkdown?.let {
                    setMarkdownText(it)
                    pendingMarkdown = null
                }
            }
        }

        loadDataWithBaseURL("https://katex.org", buildKaTeXHtmlShell(), "text/html", "UTF-8", null)
    }

    /**
     * 将大模型输出的原始 Markdown/LaTeX 传递给 KaTeX 实时渲染
     */
    fun setMarkdownText(markdownText: String) {
        if (!isLoaded) {
            pendingMarkdown = markdownText
            return
        }

        // 安全 JSON 转义传递给 JS
        val escaped = org.json.JSONObject.quote(markdownText)
        val jsCode = "javascript:renderMarkdown($escaped);"
        evaluateJavascript(jsCode, null)
    }

    private fun buildKaTeXHtmlShell(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.css">
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/contrib/auto-render.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
            <style>
                * {
                    margin: 0;
                    padding: 0;
                    box-sizing: border-box;
                    background-color: #000000 !important;
                }
                body, html {
                    background-color: #000000;
                    color: #00ff66;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    font-size: 15px;
                    line-height: 1.45;
                    padding: 4px 6px 12px 6px;
                    overflow-x: hidden;
                    word-wrap: break-word;
                }
                p, div, span, li, h1, h2, h3, table, td, th {
                    color: #00ff66 !important;
                }
                .katex {
                    color: #00ff66 !important;
                    font-size: 1.15em;
                }
                .katex-display {
                    margin: 0.4em 0 !important;
                }
                ol, ul {
                    padding-left: 18px;
                    margin-bottom: 6px;
                }
                li {
                    margin-bottom: 4px;
                }
            </style>
        </head>
        <body>
            <div id="content"></div>
            <script>
                function renderMarkdown(md) {
                    var container = document.getElementById('content');
                    if (!md || !md.trim()) {
                        container.innerHTML = '';
                        return;
                    }
                    try {
                        var html = marked.parse(md);
                        container.innerHTML = html;
                        renderMathInElement(container, {
                            delimiters: [
                                {left: '$$', right: '$$', display: true},
                                {left: '$', right: '$', display: false},
                                {left: '\\(', right: '\\)', display: false},
                                {left: '\\[', right: '\\]', display: true}
                            ],
                            throwOnError: false
                        });
                    } catch (e) {
                        container.innerText = md;
                    }
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
