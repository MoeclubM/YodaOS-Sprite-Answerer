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
 * 专为 Rokid Glasses 裸机定制的高性能 KaTeX 数学与 Markdown 渲染器
 * 1. 内嵌轻量 Markdown 转换引擎与 MathJax/KaTeX 兼容排版；
 * 2. 具有超强自愈能力：若 CDN 脚本尚未下载完成或失败，无缝自动降级为原生高对比度 AR 格式化排版，绝不白屏或卡死！
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
                pendingMarkdown?.let {
                    setMarkdownText(it)
                    pendingMarkdown = null
                }
            }
        }

        loadDataWithBaseURL("https://cdn.jsdelivr.net", buildKaTeXHtmlShell(), "text/html", "UTF-8", null)
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
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                    font-size: 15px;
                    line-height: 1.5;
                    padding: 4px 6px 12px 6px;
                    overflow-x: hidden;
                    word-wrap: break-word;
                    white-space: pre-wrap;
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
                .bold-title {
                    font-weight: bold;
                    color: #00ff66;
                    margin-top: 6px;
                    margin-bottom: 2px;
                }
            </style>
        </head>
        <body>
            <div id="content"></div>
            <script>
                // 内置轻量 Markdown 格式化器（完全不依赖外部 marked 库）
                function parseSimpleMarkdown(md) {
                    if (!md) return '';
                    var lines = md.split('\n');
                    var html = [];
                    for (var i = 0; i < lines.length; i++) {
                        var line = lines[i];
                        // 转换 **题号/标题** 为加粗
                        line = line.replace(/\*\*(.*?)\*\*/g, '<span class="bold-title">$1</span>');
                        line = line.replace(/\*(.*?)\*/g, '<em>$1</em>');
                        html.push(line);
                    }
                    return html.join('<br>');
                }

                function renderMarkdown(md) {
                    var container = document.getElementById('content');
                    if (!md || !md.trim()) {
                        container.innerHTML = '';
                        return;
                    }
                    try {
                        container.innerHTML = parseSimpleMarkdown(md);
                        if (typeof renderMathInElement === 'function') {
                            renderMathInElement(container, {
                                delimiters: [
                                    {left: '$$', right: '$$', display: true},
                                    {left: '$', right: '$', display: false},
                                    {left: '\\(', right: '\\)', display: false},
                                    {left: '\\[', right: '\\]', display: true}
                                ],
                                throwOnError: false
                            });
                        }
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
