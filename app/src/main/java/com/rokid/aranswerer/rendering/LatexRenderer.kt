package com.rokid.aranswerer.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

object LatexRenderer {
    val SYMBOLS = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\theta" to "θ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\sigma" to "σ", "\\omega" to "ω",
        "\\Omega" to "Ω", "\\Delta" to "Δ", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫",
        "\\iint" to "∬", "\\partial" to "∂", "\\nabla" to "∇", "\\infty" to "∞", "\\pm" to "±",
        "\\times" to "×", "\\div" to "÷", "\\cdot" to "·", "\\leq" to "≤", "\\le" to "≤",
        "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈",
        "\\leftarrow" to "←", "\\rightarrow" to "→", "\\to" to "→", "\\in" to "∈",
        "\\subset" to "⊂", "\\cap" to "∩", "\\cup" to "∪", "\\sqrt" to "√", "\\lim" to "lim"
    )

    private val subMap = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'x' to 'ₓ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'n' to 'ₙ', 'm' to 'ₘ')
    private val supMap = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ')

    fun renderMarkdownLatex(context: Context, raw: String, textColor: Int = 0xff00ff66.toInt(), textSizeSp: Float = 14f): CharSequence {
        val ssb = SpannableStringBuilder()
        val lines = raw.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                ssb.append("\n")
                continue
            }

            if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4) {
                val formula = trimmed.substring(2, trimmed.length - 2).trim()
                val bmp = renderCanvasFormulaBitmap(context, formula, textColor, textSizeSp * 1.25f)
                val start = ssb.length
                ssb.append("[FORMULA]\n")
                ssb.setSpan(ImageSpan(context, bmp, ImageSpan.ALIGN_BASELINE), start, start + 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val parsedLine = formatInlineLatex(trimmed)
                ssb.append(parsedLine).append("\n")
            }
        }
        return ssb
    }

    private fun formatInlineLatex(text: String): String {
        var res = text

        // 符号置换
        for ((k, v) in SYMBOLS) {
            res = res.replace(k, v)
        }

        // 分式替换 \frac{a}{b} -> (a)/(b)
        val fracRegex = Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}")
        res = fracRegex.replace(res) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }

        // 下标 _x -> ₓ
        val subRegex = Regex("_\\{?([0-9a-zA-Z+-=()]+)\\}?")
        res = subRegex.replace(res) { match ->
            match.groupValues[1].map { subMap[it] ?: it }.joinToString("")
        }

        // 上标 ^x -> ˣ
        val supRegex = Regex("\\^\\{?([0-9a-zA-Z+-=()]+)\\}?")
        res = supRegex.replace(res) { match ->
            match.groupValues[1].map { supMap[it] ?: it }.joinToString("")
        }

        return res.replace("$", "").replace("**", "")
    }

    // 采用 Canvas 2D 像素级绘制标准 LaTeX 居中公式块
    private fun renderCanvasFormulaBitmap(context: Context, formula: String, textColor: Int, textSizeSp: Float): Bitmap {
        val density = context.resources.displayMetrics.density
        val textSizePx = textSizeSp * density

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSizePx
            typeface = Typeface.create("serif", Typeface.ITALIC)
        }

        val text = formatInlineLatex(formula)
        val textWidth = paint.measureText(text)
        val fontMetrics = paint.fontMetrics
        val height = (fontMetrics.bottom - fontMetrics.top + 10 * density).toInt()
        val width = Math.max(1, (textWidth + 24 * density).toInt())

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val y = -fontMetrics.top + 5 * density
        canvas.drawText(text, 12 * density, y, paint)
        return bmp
    }
}
