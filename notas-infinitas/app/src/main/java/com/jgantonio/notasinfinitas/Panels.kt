package com.jgantonio.notasinfinitas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jgantonio.notasinfinitas.Ui.buttonRow
import com.jgantonio.notasinfinitas.Ui.confirm
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.icon
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.pill
import com.jgantonio.notasinfinitas.Ui.primaryButton
import com.jgantonio.notasinfinitas.Ui.rounded
import com.jgantonio.notasinfinitas.Ui.secondaryButton
import com.jgantonio.notasinfinitas.Ui.sectionLabel
import com.jgantonio.notasinfinitas.Ui.segmented
import com.jgantonio.notasinfinitas.Ui.sheet
import com.jgantonio.notasinfinitas.Ui.switchRow
import com.jgantonio.notasinfinitas.Ui.textField
import com.jgantonio.notasinfinitas.Ui.vertical
import com.jgantonio.notasinfinitas.Ui.wrapRow

/** Cartão flutuante usado pelas bandejas (caneta e borracha). */
private fun Context.trayCard(): LinearLayout = vertical().apply {
    setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f))
    background = rounded(Ui.SURFACE, 26f)
    elevation = dp(14f)
    isClickable = true // não deixa o toque "vazar" para a tela de desenho
}

/**
 * Bandeja de canetas (como a do Samsung Notes): fileira de canetas desenhadas,
 * prévia do traço, espessura, opacidade, cores e favoritas.
 */
@SuppressLint("ViewConstructor")
class PenTray(
    context: Context,
    private val prefs: PenPrefs,
    private var current: PenSettings,
    private val onChange: (PenSettings) -> Unit,
    private val onFavoritesChanged: () -> Unit,
) : LinearLayout(context) {

    private val card = context.trayCard()

    init {
        orientation = VERTICAL
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        render()
    }

    private fun apply(p: PenSettings, rebuild: Boolean) {
        current = p
        prefs.savePen(p)
        prefs.currentType = p.type
        onChange(p)
        if (rebuild) render()
    }

    private fun render() {
        val c = context
        card.removeAllViews()

        card.addView(PenRackView(c, { t -> if (t == current.type) current.color else prefs.pen(t).color }) { t ->
            apply(prefs.pen(t), rebuild = true)
        }.apply { selected = current.type })

        val preview = PenPreview(c, current)
        card.addView(preview, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(64f)).apply {
            topMargin = c.dpi(4f)
        })

        // Espessura
        val sizeValue = c.label("${current.size.toInt()}", 14f, Ui.INK, bold = true)
        val opValue = c.label("${current.opacity}%", 14f, Ui.INK, bold = true)
        lateinit var opacity: OpacitySlider
        val size = SizeSlider(c, PenSettings.MIN_SIZE, PenSettings.MAX_SIZE, current.size) { v ->
            current = current.copy(size = Math.round(v).toFloat())
            prefs.savePen(current)
            onChange(current)
            sizeValue.text = "${current.size.toInt()}"
            preview.pen = current
        }
        card.addView(sliderRow("Espessura", size, sizeValue))
        opacity = OpacitySlider(c, 5f, 100f, current.opacity.toFloat()) { v ->
            current = current.copy(opacity = Math.round(v))
            prefs.savePen(current)
            onChange(current)
            opValue.text = "${current.opacity}%"
            preview.pen = current
        }.apply { color = current.color }
        card.addView(sliderRow("Opacidade", opacity, opValue))

        // Cores
        card.addView(c.sectionLabel("Cores"))
        val palette = if (current.type == BrushType.HIGHLIGHTER) PenPrefs.HIGHLIGHT_COLORS + PenPrefs.INK_COLORS
        else PenPrefs.INK_COLORS
        val dots = ArrayList<View>()
        val all = (palette + prefs.customColors).distinct().let { if (current.color in it) it else it + current.color }
        for (col in all) {
            dots.add(ColorDot(c, col, col == current.color) { apply(current.copy(color = col), rebuild = true) }.withSize(c, 38f))
        }
        dots.add(RainbowButton(c) {
            ColorPicker.show(c, current.color) { picked ->
                prefs.addCustomColor(picked)
                apply(current.copy(color = picked), rebuild = true)
            }
        }.withSize(c, 38f))
        val perRow = maxOf(6, ((resources.displayMetrics.widthPixels.coerceAtMost(c.dpi(440f)) - c.dpi(48f)) / c.dpi(42f)))
        card.addView(c.wrapRow(dots, perRow))

        // Ações
        val actions = c.horizontal().apply { setPadding(0, c.dpi(12f), 0, 0) }
        val shapes = toggleChip(Icon.SHAPES, "Formas automáticas", prefs.autoShapes) { on ->
            prefs.autoShapes = on
            onChange(current)
        }
        actions.addView(shapes)
        actions.addView(View(c), LayoutParams(0, 1, 1f))
        actions.addView(toggleChip(Icon.STAR, "Favoritar", current in prefs.favorites) { on ->
            prefs.favorites = if (on) (prefs.favorites + current).takeLast(PenPrefs.MAX_FAVORITES)
            else prefs.favorites.filter { it != current }
            onFavoritesChanged()
            render()
        })
        card.addView(actions)

        // Favoritas
        val favs = prefs.favorites
        if (favs.isNotEmpty()) {
            card.addView(c.sectionLabel("Favoritas"))
            val row = c.horizontal()
            for (f in favs.asReversed()) row.addView(favoriteChip(c, f, f == current) { apply(f, rebuild = true) })
            card.addView(HorizontalScrollView(c).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
    }

    private fun sliderRow(name: String, slider: View, value: TextView): View {
        val c = context
        val row = c.horizontal().apply { setPadding(0, c.dpi(6f), 0, 0) }
        row.addView(c.label(name, 13f), LayoutParams(c.dpi(78f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(slider, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value.gravity = Gravity.END
        row.addView(value, LayoutParams(c.dpi(42f), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun toggleChip(icon: Icon, text: String, on: Boolean, onToggle: (Boolean) -> Unit): TextView {
        val c = context
        var state = on
        return TextView(c).apply {
            this.text = text
            textSize = 13f
            typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
            minHeight = c.dpi(38f)
            setPadding(c.dpi(12f), 0, c.dpi(14f), 0)
            compoundDrawablePadding = c.dpi(6f)
            fun style() {
                val col = if (state) Ui.ACCENT else Ui.INK
                setTextColor(col)
                setCompoundDrawablesRelativeWithIntrinsicBounds(c.icon(icon, col, 18f), null, null, null)
                background = Ui.ripple(c.pill(if (state) Ui.ACCENT_SOFT else Ui.FIELD, 0), c.pill(Color.WHITE, 0))
            }
            style()
            setOnClickListener {
                state = !state
                style()
                onToggle(state)
            }
        }
    }

    companion object {
        fun favoriteChip(c: Context, f: PenSettings, selected: Boolean, onClick: () -> Unit) = TextView(c).apply {
            text = "${f.size.toInt()}"
            textSize = 13f
            typeface = Ui.MEDIUM
            setTextColor(if (selected) Ui.ACCENT else Ui.INK)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = c.dpi(40f)
            setPadding(c.dpi(8f), 0, c.dpi(14f), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(PenGlyphDrawable(f.type, f.color, c.dpi(28f), c.dp(1f)), null, null, null)
            compoundDrawablePadding = c.dpi(4f)
            background = Ui.ripple(c.pill(if (selected) Ui.ACCENT_SOFT else Ui.FIELD, 0), c.pill(Color.WHITE, 0))
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = c.dpi(8f)
            }
        }
    }
}

/** Bandeja da borracha: modo, tamanho e opções. */
@SuppressLint("ViewConstructor")
class EraserTray(context: Context, prefs: PenPrefs, onChange: () -> Unit, onClearAll: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val c = context
        val card = c.trayCard()
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(Ui.run { c.title("Borracha", 17f) }.apply { setPadding(0, 0, 0, c.dpi(12f)) })
        card.addView(c.segmented(listOf("Traço inteiro", "Por área"), if (prefs.eraserArea) 1 else 0) { i ->
            prefs.eraserArea = i == 1
            onChange()
        })

        val value = c.label("${prefs.eraserSize.toInt()}", 14f, Ui.INK, bold = true)
        val row = c.horizontal().apply { setPadding(0, c.dpi(12f), 0, 0) }
        row.addView(c.label("Tamanho", 13f), LayoutParams(c.dpi(78f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(SizeSlider(c, 4f, 60f, prefs.eraserSize) { v ->
            prefs.eraserSize = Math.round(v).toFloat()
            value.text = "${prefs.eraserSize.toInt()}"
            onChange()
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value.gravity = Gravity.END
        row.addView(value, LayoutParams(c.dpi(42f), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row)

        card.addView(c.switchRow(Icon.ERASER, "Apagar só marca-texto", prefs.eraserHighlighterOnly) {
            prefs.eraserHighlighterOnly = it; onChange()
        })
        card.addView(c.switchRow(Icon.EDIT, "Botão da S Pen apaga", prefs.spenButtonErases) {
            prefs.spenButtonErases = it; onChange()
        })
        card.addView(TextView(c).apply {
            text = "Apagar tudo"
            textSize = 15f
            typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Ui.DANGER)
            minHeight = c.dpi(46f)
            setCompoundDrawablesRelativeWithIntrinsicBounds(c.icon(Icon.TRASH, Ui.DANGER, 20f), null, null, null)
            compoundDrawablePadding = c.dpi(8f)
            setPadding(c.dpi(16f), 0, c.dpi(18f), 0)
            background = Ui.ripple(c.pill(Color.parseColor("#FDECEC"), 0), c.pill(Color.WHITE, 0))
            setOnClickListener {
                c.confirm("Apagar tudo?", "Todo o conteúdo desta nota será apagado. Dá para desfazer em seguida.", "Apagar") {
                    onClearAll()
                }
            }
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = c.dpi(8f)
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }
}

fun <T : View> T.withSize(c: Context, sizeDp: Float, marginDp: Float = 2f): T {
    layoutParams = LinearLayout.LayoutParams(c.dpi(sizeDp), c.dpi(sizeDp)).apply {
        val m = c.dpi(marginDp)
        setMargins(m, m, m, m)
    }
    return this
}

object Panels {

    val PAPER_COLORS = listOf(
        "Branco" to "#FFFFFF",
        "Creme" to "#FFF8E7",
        "Cinza" to "#F1F3F5",
        "Verde" to "#EEF6EC",
        "Azul" to "#EDF3FB",
        "Escuro" to "#1E2228",
    ).map { it.first to Color.parseColor(it.second) }

    /** Plano de fundo: modelos com miniatura + cor do papel. */
    fun showBackground(context: Context, style: PageStyle, paper: Int, onChange: (PageStyle, Int) -> Unit) {
        var curStyle = style
        var curPaper = paper
        context.sheet("Plano de fundo") { box, _ ->
            val tiles = ArrayList<PageStyleTile>()
            val styles = context.horizontal()
            for (s in PageStyle.entries) {
                val col = context.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val tile = PageStyleTile(context, s, curPaper, s == curStyle) {
                    curStyle = s
                    tiles.forEach { it.checked = it.style == s }
                    onChange(curStyle, curPaper)
                }
                tiles.add(tile)
                col.addView(tile, LinearLayout.LayoutParams(context.dpi(70f), context.dpi(90f)))
                col.addView(context.label(s.label, 12f).apply { setPadding(0, context.dpi(6f), 0, 0) })
                styles.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(styles)
            box.addView(context.sectionLabel("Cor do papel"))
            val colors = context.horizontal()
            val dots = ArrayList<ColorDot>()
            for ((name, col) in PAPER_COLORS) {
                val item = context.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val dot = ColorDot(context, col, col == curPaper) {
                    curPaper = col
                    dots.forEach { it.checked = it.color == col }
                    tiles.forEach { it.paper = col; it.invalidate() }
                    onChange(curStyle, curPaper)
                }
                dots.add(dot)
                item.addView(dot, LinearLayout.LayoutParams(context.dpi(42f), context.dpi(42f)))
                item.addView(context.label(name, 11f).apply { setPadding(0, context.dpi(4f), 0, 0) })
                colors.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(colors)
        }
    }

    private val TEXT_SIZES = listOf("Pequeno" to 14f, "Médio" to 20f, "Grande" to 30f, "Título" to 44f)

    /** Caixa para digitar texto. [onDone] recebe texto e tamanho (já em unidades do mundo). */
    fun showText(
        context: Context,
        existing: TextElement?,
        density: Float,
        onDone: (String, Float) -> Unit,
        onDelete: (() -> Unit)?,
    ) {
        context.sheet(if (existing == null) "Inserir texto" else "Editar texto") { box, dialog ->
            val field = context.textField(existing?.text ?: "", "Digite aqui…", multiLine = true)
            box.addView(field)
            var size = existing?.let { it.size / density } ?: TEXT_SIZES[1].second
            val start = TEXT_SIZES.indexOfFirst { kotlin.math.abs(it.second - size) < 0.5f }.coerceAtLeast(0)
            box.addView(context.segmented(TEXT_SIZES.map { it.first }, start) { i -> size = TEXT_SIZES[i].second }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dpi(12f)
            })
            val buttons = mutableListOf<View>()
            if (onDelete != null) {
                buttons.add(context.secondaryButton("Excluir") { dialog.dismiss(); onDelete() }.apply { setTextColor(Ui.DANGER) })
            }
            buttons.add(context.secondaryButton("Cancelar") { dialog.dismiss() })
            buttons.add(context.primaryButton("Pronto") {
                dialog.dismiss()
                val t = field.text.toString().trimEnd()
                if (t.isNotBlank() || existing != null) onDone(t, size * density)
            })
            box.addView(context.buttonRow(*buttons.toTypedArray()))
            field.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** Escolher uma cor (usado para recolorir a seleção). */
    fun showColorChoice(context: Context, prefs: PenPrefs, current: Int, onPick: (Int) -> Unit) {
        context.sheet("Mudar cor") { box, dialog ->
            val dots = ArrayList<View>()
            for (col in (PenPrefs.INK_COLORS + prefs.customColors).distinct()) {
                dots.add(ColorDot(context, col, col == current) { dialog.dismiss(); onPick(col) }.withSize(context, 42f, 3f))
            }
            dots.add(RainbowButton(context) {
                dialog.dismiss()
                ColorPicker.show(context, current) { c ->
                    prefs.addCustomColor(c)
                    onPick(c)
                }
            }.withSize(context, 42f, 3f))
            box.addView(context.wrapRow(dots, 7))
        }
    }

    /** Ícone + texto numa linha (usado no topo de alguns painéis). */
    fun headerRow(context: Context, drawable: android.graphics.drawable.Drawable, text: String, sub: String?): View {
        val row = context.horizontal().apply { setPadding(0, 0, 0, context.dpi(10f)) }
        row.addView(ImageView(context).apply { setImageDrawable(drawable) },
            LinearLayout.LayoutParams(context.dpi(44f), context.dpi(36f)).apply { marginEnd = context.dpi(12f) })
        val col = context.vertical()
        col.addView(context.label(text, 16f, Ui.INK, bold = true))
        if (sub != null) col.addView(context.label(sub, 13f))
        row.addView(col)
        return row
    }
}
