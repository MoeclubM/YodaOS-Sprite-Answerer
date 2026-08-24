package com.rokid.aranswerer.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

/**
 * 纯原生 Canvas 矢量级 LaTeX & Markdown 渲染引擎
 * 零 WebView 内存开销，零 OOM 杀进程风险，专为 Rokid Glasses 裸机定制
 */
object NativeMathRenderer {

    val SYMBOLS = mapOf(
        "\\varepsilon" to "ε", "\\epsilon" to "ε", "\\alpha" to "α", "\\beta" to "β",
        "\\gamma" to "γ", "\\delta" to "δ", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
        "\\vartheta" to "θ", "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ",
        "\\varrho" to "ϱ", "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
        "\\phi" to "φ", "\\varphi" to "ϕ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ",
        "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ", "\\Phi" to "Φ", "\\Psi" to "Ψ",
        "\\Omega" to "Ω", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\iint" to "∬",
        "\\partial" to "∂", "\\nabla" to "∇", "\\infty" to "∞", "\\pm" to "±", "\\times" to "×",
        "\\div" to "÷", "\\cdot" to "·", "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥",
        "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\leftarrow" to "←",
        "\\rightarrow" to "→", "\\to" to "→", "\\in" to "∈", "\\subset" to "⊂", "\\cap" to "∩",
        "\\cup" to "∪", "\\quad" to " ", "\\qquad" to "  ", "\\," to " ", "\\;" to " ",
        "\\left" to "", "\\right" to "", "\\big" to "", "\\Big" to "", "\\text" to "",
        "\\mathbf" to "", "\\mathrm" to "", "\\boldsymbol" to ""
    )

    private val SUB_MAP = mapOf(
        "0" to "₀", "1" to "₁", "2" to "₂", "3" to "₃", "4" to "₄", "5" to "₅", "6" to "₆", "7" to "₇", "8" to "₈", "9" to "₉",
        "+" to "₊", "-" to "₋", "=" to "₌", "(" to "₍", ")" to "₎", "a" to "ₐ", "b" to "ᵦ", "c" to "𝒸", "d" to "𝒹", "e" to "ₑ",
        "f" to "𝒻", "g" to "₉", "h" to "ₕ", "i" to "ᵢ", "j" to "ⱼ", "k" to "ₖ", "l" to "ₗ", "m" to "ₘ", "n" to "ₙ", "o" to "ₒ",
        "p" to "ₚ", "r" to "ᵣ", "s" to "ₛ", "t" to "ₜ", "u" to "ᵤ", "v" to "ᵥ", "x" to "ₓ", "y" to "ᵧ", "z" to "z",
        "A" to "ₐ", "B" to "ᵦ", "C" to "𝒸", "D" to "𝒹", "E" to "ₑ", "F" to "𝒻", "H" to "ₕ", "I" to "ᵢ", "J" to "ⱼ", "K" to "ₖ",
        "L" to "ₗ", "M" to "ₘ", "N" to "ₙ", "O" to "ₒ", "P" to "ₚ", "R" to "ᵣ", "S" to "ₛ", "T" to "ₜ", "U" to "ᵤ", "V" to "ᵥ",
        "X" to "ₓ", "Y" to "ᵧ", "Z" to "z", "α" to "ᵅ", "β" to "ᵦ", "γ" to "ᵧ", "ρ" to "ᵨ", "φ" to "ᵩ", "χ" to "ᵪ"
    )

    private val SUP_MAP = mapOf(
        "0" to "⁰", "1" to "¹", "2" to "²", "3" to "³", "4" to "⁴", "5" to "⁵", "6" to "⁶", "7" to "⁷", "8" to "⁸", "9" to "⁹",
        "+" to "⁺", "-" to "⁻", "=" to "⁼", "(" to "⁽", ")" to "⁾", "a" to "ᵃ", "b" to "ᵇ", "c" to "ᶜ", "d" to "ᵈ", "e" to "ᵉ",
        "f" to "ᶠ", "g" to "ᵍ", "h" to "ʰ", "i" to "ⁱ", "j" to "ʲ", "k" to "ᵏ", "l" to "ˡ", "m" to "ᵐ", "n" to "ⁿ", "o" to "ᵒ",
        "p" to "ᵖ", "r" to "ʳ", "s" to "ˢ", "t" to "ᵗ", "u" to "ᵘ", "v" to "ᵛ", "w" to "ʷ", "x" to "ˣ", "y" to "ʸ", "z" to "ᶻ",
        "A" to "ᴬ", "B" to "ᴮ", "C" to "ᶜ", "D" to "ᴰ", "E" to "ᴱ", "F" to "ᶠ", "G" to "ᴳ", "H" to "ᴴ", "I" to "ᴵ", "J" to "ᴶ",
        "K" to "ᴷ", "L" to "ᴸ", "M" to "ᴹ", "N" to "ᴺ", "O" to "ᴼ", "P" to "ᴾ", "R" to "ᴿ", "S" to "ˢ", "T" to "ᵀ", "U" to "ᵁ",
        "V" to "ⱽ", "W" to "ᵂ", "X" to "ˣ", "Y" to "ʸ", "Z" to "ᶻ", "*" to "﹡", "\'" to "′", "′" to "′", "″" to "″",
        "α" to "ᵅ", "β" to "ᵝ", "γ" to "ᵞ", "δ" to "ᵟ", "ε" to "ᵋ", "θ" to "ᶿ", "π" to "ᵖ", "φ" to "ᵠ", "ω" to "ʷ", "μ" to "ᵐ"
    )

    fun render(context: Context, rawMarkdown: String, textColor: Int = 0xff00ff66.toInt(), textSizeSp: Float = 14f): CharSequence {
        val ssb = SpannableStringBuilder()

        val cleaned = rawMarkdown
            .replace("\r\n", "\n")
            .replace(Regex("(?m)^(\\d+\\.\\s*)\\n+\\$\\$"), "$1\n\$\$")
            .replace(Regex("\\n{3,}"), "\n\n")

        val blockPattern = Regex("\\$\\$([\\s\\S]*?)\\$\\$")
        var lastEnd = 0

        for (match in blockPattern.findAll(cleaned)) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > lastEnd) {
                val textBefore = cleaned.substring(lastEnd, start)
                appendFormattedText(ssb, textBefore)
            }

            val formulaBody = match.groupValues[1].replace("\n", " ").trim()
            if (formulaBody.isNotEmpty()) {
                val bmp = drawFormulaBlock(context, formulaBody, textColor, textSizeSp * 1.3f)
                val spanStart = ssb.length
                ssb.append("[FORMULA]")
                ssb.setSpan(ImageSpan(context, bmp, ImageSpan.ALIGN_BASELINE), spanStart, spanStart + 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append("\n")
            }

            lastEnd = end
        }

        if (lastEnd < cleaned.length) {
            val remainingText = cleaned.substring(lastEnd)
            appendFormattedText(ssb, remainingText)
        }

        return ssb.toString().trimEnd().let { ssb.subSequence(0, it.length) }
    }

    private fun appendFormattedText(ssb: SpannableStringBuilder, text: String) {
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (ssb.isNotEmpty() && !ssb.endsWith("\n\n")) {
                    ssb.append("\n")
                }
                continue
            }
            ssb.append(formatInline(trimmed)).append("\n")
        }
    }

    fun formatInline(text: String): String {
        var res = text

        val sortedSymbols = SYMBOLS.entries.sortedByDescending { it.key.length }
        for ((k, v) in sortedSymbols) {
            res = res.replace(k, v)
        }

        // 分式替换
        val fracRegex = Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}")
        res = fracRegex.replace(res) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }

        // 根号
        val sqrtRegex = Regex("\\\\sqrt\\{([^}]+)\\}")
        res = sqrtRegex.replace(res) { "√(${it.groupValues[1]})" }

        // 上标
        val supBraceRegex = Regex("\\^\\{([^}]+)\\}")
        res = supBraceRegex.replace(res) { match ->
            match.groupValues[1].map { ch -> SUP_MAP[ch.toString()] ?: ch.toString() }.joinToString("")
        }
        val supSingleRegex = Regex("\\^([0-9a-zA-Z+-=()])")
        res = supSingleRegex.replace(res) { match ->
            val s = match.groupValues[1]
            SUP_MAP[s] ?: s
        }

        // 下标
        val subBraceRegex = Regex("_\\{([^}]+)\\}")
        res = subBraceRegex.replace(res) { match ->
            match.groupValues[1].map { ch -> SUB_MAP[ch.toString()] ?: ch.toString() }.joinToString("")
        }
        val subSingleRegex = Regex("_([0-9a-zA-Z+-=()])")
        res = subSingleRegex.replace(res) { match ->
            val s = match.groupValues[1]
            SUB_MAP[s] ?: s
        }

        return res.replace("$", "").replace("**", "").replace("`", "")
    }

    private fun drawFormulaBlock(context: Context, formula: String, textColor: Int, textSizeSp: Float): Bitmap {
        val density = context.resources.displayMetrics.density
        val textSizePx = textSizeSp * density

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSizePx
            typeface = Typeface.create("serif", Typeface.ITALIC)
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            strokeWidth = 1.5f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val fracMatch = Regex("^(.*?)\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}(.*)$").find(formula)
        if (fracMatch != null) {
            val prefix = formatInline(fracMatch.groupValues[1])
            val num = formatInline(fracMatch.groupValues[2])
            val den = formatInline(fracMatch.groupValues[3])
            val suffix = formatInline(fracMatch.groupValues[4])

            val numWidth = textPaint.measureText(num)
            val denWidth = textPaint.measureText(den)
            val fracWidth = Math.max(numWidth, denWidth) + 16 * density
            val prefixWidth = textPaint.measureText(prefix)
            val suffixWidth = textPaint.measureText(suffix)
            val totalWidth = (prefixWidth + fracWidth + suffixWidth + 16 * density).toInt()

            val fontMetrics = textPaint.fontMetrics
            val lineH = fontMetrics.bottom - fontMetrics.top
            val totalHeight = (lineH * 2.0f + 12 * density).toInt()

            val bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT)

            var curX = 4 * density
            val centerY = totalHeight / 2f

            if (prefix.isNotEmpty()) {
                canvas.drawText(prefix, curX, centerY - (fontMetrics.top + fontMetrics.bottom) / 2, textPaint)
                curX += prefixWidth + 4 * density
            }

            val numX = curX + (fracWidth - numWidth) / 2
            canvas.drawText(num, numX, centerY - 4 * density, textPaint)

            canvas.drawLine(curX, centerY, curX + fracWidth, centerY, strokePaint)

            val denX = curX + (fracWidth - denWidth) / 2
            canvas.drawText(den, denX, centerY + lineH * 0.75f, textPaint)
            curX += fracWidth + 4 * density

            if (suffix.isNotEmpty()) {
                canvas.drawText(suffix, curX, centerY - (fontMetrics.top + fontMetrics.bottom) / 2, textPaint)
            }
            return bmp
        }

        val sqrtMatch = Regex("^(.*?)\\\\sqrt\\{([^}]+)\\}(.*)$").find(formula)
        if (sqrtMatch != null) {
            val prefix = formatInline(sqrtMatch.groupValues[1])
            val inner = formatInline(sqrtMatch.groupValues[2])
            val suffix = formatInline(sqrtMatch.groupValues[3])

            val innerWidth = textPaint.measureText(inner)
            val prefixWidth = textPaint.measureText(prefix)
            val suffixWidth = textPaint.measureText(suffix)
            val sqrtWidth = innerWidth + 18 * density
            val totalWidth = (prefixWidth + sqrtWidth + suffixWidth + 16 * density).toInt()

            val fontMetrics = textPaint.fontMetrics
            val lineH = fontMetrics.bottom - fontMetrics.top
            val totalHeight = (lineH + 12 * density).toInt()

            val bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT)

            var curX = 4 * density
            val baselineY = -fontMetrics.top + 6 * density

            if (prefix.isNotEmpty()) {
                canvas.drawText(prefix, curX, baselineY, textPaint)
                curX += prefixWidth + 4 * density
            }

            val path = Path().apply {
                moveTo(curX, baselineY - 4 * density)
                lineTo(curX + 3 * density, baselineY)
                lineTo(curX + 6 * density, 4 * density)
                lineTo(curX + sqrtWidth, 4 * density)
            }
            canvas.drawPath(path, strokePaint)

            canvas.drawText(inner, curX + 9 * density, baselineY, textPaint)
            curX += sqrtWidth + 4 * density

            if (suffix.isNotEmpty()) {
                canvas.drawText(suffix, curX, baselineY, textPaint)
            }
            return bmp
        }

        val cleanText = formatInline(formula)
        val textWidth = textPaint.measureText(cleanText)
        val fontMetrics = textPaint.fontMetrics
        val totalWidth = Math.max(1, (textWidth + 16 * density).toInt())
        val totalHeight = (fontMetrics.bottom - fontMetrics.top + 10 * density).toInt()

        val bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val y = -fontMetrics.top + 5 * density
        canvas.drawText(cleanText, 4 * density, y, textPaint)
        return bmp
    }
}
