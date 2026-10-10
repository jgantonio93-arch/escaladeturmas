package com.jgantonio.notasinfinitas

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.icon
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.pill
import com.jgantonio.notasinfinitas.Ui.ripple
import com.jgantonio.notasinfinitas.Ui.rounded
import com.jgantonio.notasinfinitas.Ui.sheet
import kotlin.math.roundToInt

/**
 * Edição de texto direto na tela, como no Samsung Notes: a caixa vira um campo
 * de digitação no lugar, com uma barra de formatação acima do teclado.
 * Com um trecho selecionado, negrito/itálico/cor etc. valem só para o trecho;
 * sem seleção, valem para a caixa inteira.
 */
class InlineTextEditor(
    private val activity: Activity,
    private val stage: FrameLayout,
    private val canvas: InfiniteCanvasView,
    private val defaultColor: () -> Int,
    private val onFinished: () -> Unit,
) {
    private val ctx: Context = activity
    private val density = ctx.resources.displayMetrics.density

    private var edit: EditText? = null
    private var bar: View? = null
    private var original: TextElement? = null

    // Estilo da caixa inteira
    private var anchorX = 0f
    private var anchorY = 0f
    private var size = 0f
    private var color = Color.BLACK
    private var bold = false
    private var italic = false
    private var underline = false
    private var strike = false
    private var align = TextAlign.LEFT
    private var font = TextFont.SANS
    private var bg = 0
    private var rotation = 0f
    private var flipH = false
    private var flipV = false

    private lateinit var sizeLabel: TextView
    private val styleButtons = HashMap<Int, TextView>()
    private lateinit var fontChip: TextView
    private lateinit var alignButton: ImageView
    private lateinit var colorDot: ColorDot

    val isActive get() = edit != null

    // ---- Início e fim ----------------------------------------------------------------------

    fun start(existing: TextElement?, worldX: Float, worldY: Float) {
        if (isActive) finish()
        original = existing
        if (existing != null) {
            anchorX = existing.x; anchorY = existing.y
            size = existing.size; color = existing.color
            bold = existing.bold; italic = existing.italic; underline = existing.underline; strike = existing.strike
            align = existing.align; font = existing.font; bg = existing.bgColor
            rotation = existing.rotation; flipH = existing.flipH; flipV = existing.flipV
        } else {
            size = lastSize.takeIf { it > 0f } ?: (22f * density)
            color = defaultColor()
            bold = false; italic = false; underline = false; strike = false
            align = TextAlign.LEFT; font = lastFont; bg = 0
            rotation = 0f; flipH = false; flipV = false
            // O toque marca o meio da primeira linha.
            anchorX = worldX
            anchorY = worldY - size * 0.65f
        }

        val e = EditText(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(18, 47, 107, 255))
                cornerRadius = 6f * density
                setStroke((1.5f * density).toInt(), Ui.ACCENT, 6f * density, 4f * density)
            }
            setPadding(PAD, PAD, PAD, PAD)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = false
            minWidth = (48 * density).toInt()
            setText(SpannableStringBuilder(existing?.spanned() ?: ""))
            setSelection(text.length)
        }
        edit = e
        applyBoxStyle()
        stage.addView(e, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        reposition()

        val b = buildBar()
        bar = b
        stage.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        refreshBar()

        canvas.editingText = existing
        canvas.touchInterceptor = { finish(); true }
        e.requestFocus()
        e.post { showKeyboard() }
    }

    /** Grava o texto na nota e fecha a edição. */
    fun finish() {
        val e = edit ?: return
        e.clearComposingText()
        val content = e.text
        val text = content.toString().trimEnd()
        val old = original
        edit = null
        if (text.isBlank()) {
            if (old != null) canvas.replaceText(old, null)
        } else {
            val el = TextElement(text, anchorX, anchorY, size, color, rotation, flipH, flipV, bold, italic, underline, strike,
                align, font, bg, spansOf(content, text.length))
            if (old != null) canvas.replaceText(old, el.keepingCenterOf(old)) else canvas.addText(el)
            lastSize = size
            lastFont = font
        }
        hideKeyboard(e)
        stage.removeView(e)
        bar?.let { stage.removeView(it) }
        bar = null
        original = null
        canvas.editingText = null
        canvas.touchInterceptor = null
        onFinished()
    }

    /** Acompanha zoom e rolagem da tela. */
    fun reposition() {
        val e = edit ?: return
        val z = canvas.zoom
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * z)
        val lp = e.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (canvas.screenX(anchorX) - PAD).roundToInt()
        lp.topMargin = (canvas.screenY(anchorY) - PAD).roundToInt()
        e.layoutParams = lp
    }

    /** Depois que o teclado abre, rola a nota para a caixa ficar visível. */
    fun keepVisible() {
        val e = edit ?: return
        if (e.width == 0) return
        val r = RectF(canvas.worldX(e.x), canvas.worldY(e.y), canvas.worldX(e.x + e.width), canvas.worldY(e.y + e.height))
        val barH = bar?.height ?: 0
        canvas.ensureVisible(r, 16f * density + barH)
        reposition()
    }

    // ---- Estilos ----------------------------------------------------------------------------

    private fun applyBoxStyle() {
        val e = edit ?: return
        e.typeface = font.typeface(bold, italic)
        e.setTextColor(color)
        e.paintFlags = (e.paintFlags and (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()) or
            (if (underline) Paint.UNDERLINE_TEXT_FLAG else 0) or (if (strike) Paint.STRIKE_THRU_TEXT_FLAG else 0)
        e.gravity = when (align) {
            TextAlign.LEFT -> Gravity.START
            TextAlign.CENTER -> Gravity.CENTER_HORIZONTAL
            TextAlign.RIGHT -> Gravity.END
        } or Gravity.TOP
        reposition()
    }

    private fun hasSelection(): Boolean {
        val e = edit ?: return false
        return e.selectionStart != e.selectionEnd
    }

    private fun matches(span: Any, type: Int, sp: Spanned): Boolean {
        if (sp.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0) return false
        return when (type) {
            TextSpan.BOLD -> span is StyleSpan && (span.style == android.graphics.Typeface.BOLD)
            TextSpan.ITALIC -> span is StyleSpan && (span.style == android.graphics.Typeface.ITALIC)
            TextSpan.UNDERLINE -> span.javaClass == UnderlineSpan::class.java
            TextSpan.STRIKE -> span is StrikethroughSpan
            TextSpan.COLOR -> span is ForegroundColorSpan
            TextSpan.HIGHLIGHT -> span is BackgroundColorSpan
            TextSpan.SIZE -> span is RelativeSizeSpan
            else -> false
        }
    }

    private fun newSpan(type: Int, value: Int): Any = when (type) {
        TextSpan.BOLD -> StyleSpan(android.graphics.Typeface.BOLD)
        TextSpan.ITALIC -> StyleSpan(android.graphics.Typeface.ITALIC)
        TextSpan.UNDERLINE -> UnderlineSpan()
        TextSpan.STRIKE -> StrikethroughSpan()
        TextSpan.COLOR -> ForegroundColorSpan(value)
        TextSpan.HIGHLIGHT -> BackgroundColorSpan(value)
        else -> RelativeSizeSpan(value / 100f)
    }

    private fun valueOf(span: Any): Int = when (span) {
        is ForegroundColorSpan -> span.foregroundColor
        is BackgroundColorSpan -> span.backgroundColor
        is RelativeSizeSpan -> (span.sizeChange * 100).roundToInt()
        else -> 0
    }

    /** Tira o estilo [type] do trecho [a, b), preservando o que estiver fora dele. */
    private fun clearRange(ed: Spannable, type: Int, a: Int, b: Int): Boolean {
        var removed = false
        for (sp in ed.getSpans(a, b, Any::class.java)) {
            if (!matches(sp, type, ed)) continue
            val s0 = ed.getSpanStart(sp)
            val e0 = ed.getSpanEnd(sp)
            if (e0 <= a || s0 >= b) continue
            val v = valueOf(sp)
            ed.removeSpan(sp)
            removed = true
            if (s0 < a) ed.setSpan(newSpan(type, v), s0, a, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            if (e0 > b) ed.setSpan(newSpan(type, v), b, e0, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        return removed
    }

    /** Liga/desliga negrito, itálico, sublinhado ou tachado. */
    private fun toggle(type: Int) {
        val e = edit ?: return
        if (hasSelection()) {
            val a = e.selectionStart
            val b = e.selectionEnd
            val ed = e.text
            if (!clearRange(ed, type, a, b)) ed.setSpan(newSpan(type, 0), a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        } else {
            when (type) {
                TextSpan.BOLD -> bold = !bold
                TextSpan.ITALIC -> italic = !italic
                TextSpan.UNDERLINE -> underline = !underline
                TextSpan.STRIKE -> strike = !strike
            }
            applyBoxStyle()
        }
        refreshBar()
    }

    private fun setColor(c: Int) {
        val e = edit ?: return
        if (hasSelection()) {
            val a = e.selectionStart
            val b = e.selectionEnd
            clearRange(e.text, TextSpan.COLOR, a, b)
            e.text.setSpan(ForegroundColorSpan(c), a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        } else {
            color = c
            clearRange(e.text, TextSpan.COLOR, 0, e.text.length)
            applyBoxStyle()
        }
        refreshBar()
    }

    private fun setHighlight(c: Int) {
        val e = edit ?: return
        if (hasSelection()) {
            val a = e.selectionStart
            val b = e.selectionEnd
            clearRange(e.text, TextSpan.HIGHLIGHT, a, b)
            if (c != 0) e.text.setSpan(BackgroundColorSpan(c), a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        } else {
            bg = c
        }
        refreshBar()
    }

    private fun changeSize(factor: Float) {
        val e = edit ?: return
        if (hasSelection()) {
            // Trecho: tamanho relativo ao da caixa
            val a = e.selectionStart
            val b = e.selectionEnd
            val current = e.text.getSpans(a, b, RelativeSizeSpan::class.java).firstOrNull()?.sizeChange ?: 1f
            clearRange(e.text, TextSpan.SIZE, a, b)
            val n = (current * factor).coerceIn(0.3f, 5f)
            if (kotlin.math.abs(n - 1f) > 0.02f) e.text.setSpan(RelativeSizeSpan(n), a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        } else {
            size = (size * factor).coerceIn(6f * density, 400f * density)
            reposition()
        }
        refreshBar()
    }

    // ---- Barra de formatação ---------------------------------------------------------------

    private fun styleButton(type: Int, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Ui.INK)
        when (type) {
            TextSpan.BOLD -> typeface = Ui.BOLD
            TextSpan.ITALIC -> typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC)
            TextSpan.UNDERLINE -> paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            TextSpan.STRIKE -> paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        setOnClickListener { toggle(type) }
        layoutParams = LinearLayout.LayoutParams(ctx.dpi(42f), ctx.dpi(42f))
        styleButtons[type] = this
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(ctx.dpi(1f), ctx.dpi(24f)).apply { setMargins(ctx.dpi(6f), 0, ctx.dpi(6f), 0) }
    }

    private fun buildBar(): View {
        val c = ctx
        val outer = c.horizontal().apply {
            setBackgroundColor(Ui.SURFACE)
            elevation = c.dp(12f)
            setPadding(c.dpi(6f), c.dpi(6f), c.dpi(8f), c.dpi(6f))
            isClickable = true
        }
        val row = c.horizontal()

        fontChip = TextView(c).apply {
            textSize = 14f
            setTextColor(Ui.INK)
            gravity = Gravity.CENTER
            minHeight = c.dpi(38f)
            setPadding(c.dpi(12f), 0, c.dpi(10f), 0)
            background = ripple(c.pill(Ui.FIELD, 0), c.pill(Color.WHITE, 0))
            setOnClickListener { pickFont() }
        }
        row.addView(fontChip)
        row.addView(divider())
        row.addView(TextView(c).apply {
            text = "A"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Ui.INK)
            setOnClickListener { changeSize(1f / 1.15f) }
        }, LinearLayout.LayoutParams(c.dpi(36f), c.dpi(42f)))
        sizeLabel = c.label("", 14f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }
        row.addView(sizeLabel, LinearLayout.LayoutParams(c.dpi(34f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(c).apply {
            text = "A"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Ui.INK)
            setOnClickListener { changeSize(1.15f) }
        }, LinearLayout.LayoutParams(c.dpi(36f), c.dpi(42f)))
        row.addView(divider())
        row.addView(styleButton(TextSpan.BOLD, "B"))
        row.addView(styleButton(TextSpan.ITALIC, "I"))
        row.addView(styleButton(TextSpan.UNDERLINE, "U"))
        row.addView(styleButton(TextSpan.STRIKE, "S"))
        row.addView(divider())
        alignButton = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener {
                align = TextAlign.entries[(align.ordinal + 1) % TextAlign.entries.size]
                applyBoxStyle()
                refreshBar()
            }
        }
        row.addView(alignButton, LinearLayout.LayoutParams(c.dpi(42f), c.dpi(42f)))
        colorDot = ColorDot(c, color, false) {
            Panels.showColorChoice(c, PenPrefs(c), color) { col -> setColor(col); refocus() }
        }
        row.addView(colorDot, LinearLayout.LayoutParams(c.dpi(30f), c.dpi(30f)).apply { setMargins(c.dpi(6f), 0, c.dpi(6f), 0) })
        row.addView(ImageView(c).apply {
            setImageDrawable(c.icon(Icon.MARKER, Ui.INK, 22f))
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { pickHighlight() }
        }, LinearLayout.LayoutParams(c.dpi(42f), c.dpi(42f)))

        outer.addView(HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(TextView(c).apply {
            text = "Concluir"
            textSize = 14f
            typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            minHeight = c.dpi(38f)
            setPadding(c.dpi(14f), 0, c.dpi(14f), 0)
            background = ripple(c.pill(Ui.ACCENT, 0), c.pill(Color.WHITE, 0), Color.parseColor("#33FFFFFF"))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = c.dpi(6f)
        })
        return outer
    }

    private fun refreshBar() {
        if (bar == null) return
        fontChip.text = font.label + "  ▾"
        fontChip.typeface = font.typeface(false, false)
        sizeLabel.text = "${(size / density).roundToInt()}"
        val state = mapOf(TextSpan.BOLD to bold, TextSpan.ITALIC to italic, TextSpan.UNDERLINE to underline, TextSpan.STRIKE to strike)
        for ((type, b) in styleButtons) {
            val on = state[type] == true
            b.background = if (on) ctx.rounded(Ui.ACCENT_SOFT, 10f) else ripple(null, ctx.rounded(Color.WHITE, 10f))
            b.setTextColor(if (on) Ui.ACCENT else Ui.INK)
        }
        alignButton.setImageDrawable(ctx.icon(when (align) {
            TextAlign.LEFT -> Icon.ALIGN_LEFT
            TextAlign.CENTER -> Icon.ALIGN_CENTER
            TextAlign.RIGHT -> Icon.ALIGN_RIGHT
        }, Ui.INK, 22f))
        colorDot.color = color
    }

    private fun refocus() {
        val e = edit ?: return
        e.requestFocus()
        e.post { showKeyboard() }
    }

    private fun pickFont() {
        ctx.sheet("Fonte") { box, dialog ->
            for (f in TextFont.entries) {
                val row = ctx.horizontal().apply {
                    minimumHeight = ctx.dpi(52f)
                    setPadding(ctx.dpi(6f), 0, ctx.dpi(6f), 0)
                    background = ripple(null, ctx.rounded(Color.WHITE, 14f))
                    setOnClickListener {
                        font = f
                        applyBoxStyle()
                        refreshBar()
                        dialog.dismiss()
                        refocus()
                    }
                }
                row.addView(TextView(ctx).apply {
                    text = f.label
                    textSize = 19f
                    typeface = f.typeface(false, false)
                    setTextColor(if (f == font) Ui.ACCENT else Ui.INK)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (f == font) row.addView(ImageView(ctx).apply { setImageDrawable(ctx.icon(Icon.CHECK, Ui.ACCENT, 20f)) })
                box.addView(row)
            }
        }
    }

    private fun pickHighlight() {
        val colors = listOf(0) + PenPrefs.HIGHLIGHT_COLORS.map { (it and 0x00FFFFFF) or (0x99 shl 24) }
        ctx.sheet(if (hasSelection()) "Destacar trecho" else "Fundo da caixa") { box, dialog ->
            val row = ctx.horizontal()
            for (col in colors) {
                val v: View = if (col == 0) TextView(ctx).apply {
                    text = "Nenhum"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Ui.MUTED)
                    background = ctx.rounded(Ui.FIELD, 100f)
                    setOnClickListener { setHighlight(0); dialog.dismiss(); refocus() }
                } else ColorDot(ctx, col, col == bg) { setHighlight(col); dialog.dismiss(); refocus() }
                row.addView(v, LinearLayout.LayoutParams(0, ctx.dpi(46f), 1f).apply { setMargins(ctx.dpi(3f), 0, ctx.dpi(3f), 0) })
            }
            box.addView(row)
        }
    }

    private fun showKeyboard() {
        val e = edit ?: return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(v: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    companion object {
        private const val PAD = 0
        private var lastSize = 0f
        private var lastFont = TextFont.SANS

        /** Converte os estilos de um texto editável para a lista guardada na nota. */
        fun spansOf(sp: Spanned, length: Int): List<TextSpan> {
            val out = ArrayList<TextSpan>()
            for (s in sp.getSpans(0, sp.length, Any::class.java)) {
                if (sp.getSpanFlags(s) and Spanned.SPAN_COMPOSING != 0) continue
                val a = sp.getSpanStart(s).coerceIn(0, length)
                val b = sp.getSpanEnd(s).coerceIn(0, length)
                if (a >= b) continue
                when {
                    s is StyleSpan && s.style == android.graphics.Typeface.BOLD -> out.add(TextSpan(a, b, TextSpan.BOLD))
                    s is StyleSpan && s.style == android.graphics.Typeface.ITALIC -> out.add(TextSpan(a, b, TextSpan.ITALIC))
                    s is StyleSpan && s.style == android.graphics.Typeface.BOLD_ITALIC -> {
                        out.add(TextSpan(a, b, TextSpan.BOLD)); out.add(TextSpan(a, b, TextSpan.ITALIC))
                    }
                    s.javaClass == UnderlineSpan::class.java -> out.add(TextSpan(a, b, TextSpan.UNDERLINE))
                    s is StrikethroughSpan -> out.add(TextSpan(a, b, TextSpan.STRIKE))
                    s is ForegroundColorSpan -> out.add(TextSpan(a, b, TextSpan.COLOR, s.foregroundColor))
                    s is BackgroundColorSpan -> out.add(TextSpan(a, b, TextSpan.HIGHLIGHT, s.backgroundColor))
                    s is RelativeSizeSpan -> out.add(TextSpan(a, b, TextSpan.SIZE, (s.sizeChange * 100).roundToInt()))
                }
            }
            return out
        }
    }
}
