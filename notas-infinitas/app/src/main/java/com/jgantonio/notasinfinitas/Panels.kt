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
import com.jgantonio.notasinfinitas.Ui.softShadow
import com.jgantonio.notasinfinitas.Ui.wrapRow
import kotlin.math.max

/** Cartão flutuante usado pelas bandejas (caneta e borracha). */
private fun Context.trayCard(): LinearLayout = vertical().apply {
    setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f))
    background = rounded(Ui.SURFACE, 26f, Color.parseColor("#E9EBEF"))
    softShadow(14f)
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
    /** Largura da bandeja (px), para as cores caberem quando a barra fica de lado. */
    private val widthPx: Int = 0,
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

        if (current.type == BrushType.HIGHLIGHTER) highlighterOptions(preview) else penSliders(preview)

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
        val avail = if (widthPx > 0) widthPx else resources.displayMetrics.widthPixels.coerceAtMost(c.dpi(440f))
        val perRow = maxOf(5, (avail - c.dpi(36f)) / c.dpi(42f))
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

    /** Espessura e opacidade (canetas comuns). */
    private fun penSliders(preview: PenPreview) {
        val c = context
        val sizeValue = c.label("${current.size.toInt()}", 14f, Ui.INK, bold = true)
        val opValue = c.label("${current.opacity}%", 14f, Ui.INK, bold = true)
        val size = SizeSlider(c, PenSettings.MIN_SIZE, PenSettings.MAX_SIZE, current.size) { v ->
            update(current.copy(size = Math.round(v).toFloat()), preview)
            sizeValue.text = "${current.size.toInt()}"
        }
        card.addView(sliderRow("Espessura", size, sizeValue))
        val opacity = OpacitySlider(c, 5f, 100f, current.opacity.toFloat()) { v ->
            update(current.copy(opacity = Math.round(v)), preview)
            opValue.text = "${current.opacity}%"
        }.apply { color = current.color }
        card.addView(sliderRow("Opacidade", opacity, opValue))
    }

    /**
     * Marca-texto: tamanho (altura da faixa), transparência, espessura da ponta chanfrada
     * e linhas retas — como no Samsung Notes.
     */
    private fun highlighterOptions(preview: PenPreview) {
        val c = context
        val sizeValue = c.label("${current.size.toInt()}", 14f, Ui.INK, bold = true)
        card.addView(sliderRow("Tamanho", SizeSlider(c, PenSettings.MIN_SIZE, PenSettings.MAX_SIZE, current.size) { v ->
            update(current.copy(size = Math.round(v).toFloat()), preview)
            sizeValue.text = "${current.size.toInt()}"
        }, sizeValue))

        val transValue = c.label("${100 - current.opacity}%", 14f, Ui.INK, bold = true)
        card.addView(sliderRow("Transparência", OpacitySlider(c, 0f, 95f, (100 - current.opacity).toFloat(), reversed = true) { v ->
            update(current.copy(opacity = 100 - Math.round(v)), preview)
            transValue.text = "${100 - current.opacity}%"
        }.apply { color = current.color }, transValue))

        val tipValue = c.label("${current.tip}%", 14f, Ui.INK, bold = true)
        card.addView(sliderRow("Espessura", SizeSlider(c, 10f, 100f, current.tip.toFloat()) { v ->
            update(current.copy(tip = Math.round(v)), preview)
            tipValue.text = "${current.tip}%"
        }, tipValue))

        card.addView(c.switchRow(Icon.STRAIGHT, "Endireitar linhas", current.straight) { on ->
            update(current.copy(straight = on), preview)
        })
    }

    private fun update(p: PenSettings, preview: PenPreview) {
        current = p
        prefs.savePen(current)
        onChange(current)
        preview.pen = current
    }

    private fun sliderRow(name: String, slider: View, value: TextView): View {
        val c = context
        val row = c.horizontal().apply { setPadding(0, c.dpi(6f), 0, 0) }
        row.addView(c.label(name, 13f), LayoutParams(c.dpi(92f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(slider, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value.gravity = Gravity.END
        value.fontFeatureSettings = "tnum"
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
        lateinit var hint: TextView
        fun hintText() = if (prefs.eraserArea) "Apaga só a parte do traço por onde a borracha passa."
        else "Apaga o traço inteiro ao encostar nele."
        card.addView(c.segmented(listOf("Borracha de traço", "Borracha de área"), if (prefs.eraserArea) 1 else 0) { i ->
            prefs.eraserArea = i == 1
            hint.text = hintText()
            onChange()
        })
        hint = c.label(hintText(), 13f).apply { setPadding(c.dpi(2f), c.dpi(8f), 0, 0) }
        card.addView(hint)

        val value = c.label("${prefs.eraserSize.toInt()}", 14f, Ui.INK, bold = true)
        val row = c.horizontal().apply { setPadding(0, c.dpi(12f), 0, 0) }
        row.addView(c.label("Tamanho", 13f), LayoutParams(c.dpi(92f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(SizeSlider(c, 4f, 60f, prefs.eraserSize) { v ->
            prefs.eraserSize = Math.round(v).toFloat()
            value.text = "${prefs.eraserSize.toInt()}"
            onChange()
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value.gravity = Gravity.END
        row.addView(value, LayoutParams(c.dpi(42f), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row)

        card.addView(c.switchRow(Icon.ERASER, "Apagar somente marca-texto", prefs.eraserHighlighterOnly) {
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

/** Bandeja da seleção: laço livre ou retângulo, e seleção parcial. */
@SuppressLint("ViewConstructor")
class SelectTray(context: Context, prefs: PenPrefs, onChange: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val c = context
        val card = c.trayCard()
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(Ui.run { c.title("Seleção", 17f) }.apply { setPadding(0, 0, 0, c.dpi(12f)) })
        card.addView(c.segmented(listOf("Laço", "Retângulo"), if (prefs.selectRect) 1 else 0) { i ->
            prefs.selectRect = i == 1
            onChange()
        })
        card.addView(c.switchRow(Icon.PARTIAL, "Incluir objetos parcialmente selecionados", prefs.selectPartial) {
            prefs.selectPartial = it
            onChange()
        })
        card.addView(c.label("Ligado, basta o laço encostar num item para ele entrar na seleção.", 13f).apply {
            setPadding(c.dpi(2f), 0, 0, 0)
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

    /**
     * Página: tela infinita ou folhas (verticais/horizontais, retrato/paisagem,
     * infinitas ou em número fixo), modelo (pontos, linhas...) e cor do papel.
     */
    fun showPage(context: Context, canvas: InfiniteCanvasView, prefs: PenPrefs, onChanged: () -> Unit) {
        val l0 = canvas.pageLayout
        var paged = l0.hasPages
        var horizontal = l0.horizontal
        var landscape = l0.landscape
        var endless = l0.endless
        var count = if (l0.hasPages && !l0.endless) l0.count else max(1, canvas.pageCount)
        var curStyle = canvas.pageStyle
        var curPaper = canvas.paperColor

        context.sheet("Página") { box, _ ->
            val pageOptions = context.vertical()
            val countRow = context.horizontal()
            lateinit var countLabel: TextView
            val modeTiles = ArrayList<Pair<Int, LinearLayout>>()

            fun apply() {
                canvas.setPageLayout(paged, horizontal, landscape, endless, count)
                prefs.defaultPageLayout = canvas.pageLayout.encodePref()
                onChanged()
            }

            fun styleModes() {
                val sel = if (!paged) 0 else if (horizontal) 2 else 1
                for ((i, tile) in modeTiles) {
                    val on = i == sel
                    val box2 = tile.getChildAt(0) as ImageView
                    box2.background = context.rounded(if (on) Ui.ACCENT_SOFT else Ui.FIELD, 18f, if (on) Ui.ACCENT else 0, 1.5f)
                    (box2.drawable as? IconDrawable)?.color = if (on) Ui.ACCENT else Ui.INK
                    box2.invalidate()
                    (tile.getChildAt(1) as TextView).setTextColor(if (on) Ui.ACCENT else Ui.INK)
                }
                pageOptions.visibility = if (paged) View.VISIBLE else View.GONE
                countRow.visibility = if (paged && !endless) View.VISIBLE else View.GONE
            }

            // Tipo de página
            val modes = context.horizontal()
            listOf(Icon.INFINITE to "Infinita", Icon.PAGES_V to "Folhas verticais", Icon.PAGES_H to "Folhas horizontais")
                .forEachIndexed { i, (icon, name) ->
                    val tile = context.vertical().apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        contentDescription = name
                        Ui.run { pressable(0.96f) }
                        setOnClickListener {
                            paged = i != 0
                            if (paged) horizontal = i == 2
                            styleModes()
                            apply()
                        }
                    }
                    tile.addView(ImageView(context).apply {
                        setImageDrawable(IconDrawable(icon, Ui.INK, context.dpi(30f), context.dp(1.8f)))
                        scaleType = ImageView.ScaleType.CENTER
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(64f)))
                    tile.addView(context.label(name, 13f, Ui.INK).apply {
                        gravity = Gravity.CENTER
                        typeface = Ui.MEDIUM
                        setPadding(0, context.dpi(6f), 0, 0)
                    })
                    modeTiles.add(i to tile)
                    modes.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        val m = context.dpi(4f)
                        setMargins(m, 0, m, 0)
                    })
                }
            box.addView(modes)

            // Opções das folhas
            pageOptions.addView(context.sectionLabel("Formato da folha"))
            pageOptions.addView(context.segmented(listOf("Retrato", "Paisagem"), if (landscape) 1 else 0) { i ->
                landscape = i == 1
                apply()
            })
            pageOptions.addView(context.switchRow(Icon.INFINITE, "Folhas infinitas", endless) { on ->
                endless = on
                if (!on) count = max(1, canvas.pageCount - 1).coerceAtLeast(canvas.usedPageRects().size)
                countLabel.text = "$count"
                styleModes()
                apply()
            }.apply { setPadding(0, context.dpi(6f), 0, 0) })
            pageOptions.addView(context.label("Uma folha nova aparece sozinha quando você escreve na última.", 13f))

            countRow.apply { setPadding(0, context.dpi(14f), 0, 0) }
            countRow.addView(context.label("Número de folhas", 15f, Ui.INK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun step(text: String, d: Int) = TextView(context).apply {
                this.text = text
                textSize = 20f
                typeface = Ui.MEDIUM
                gravity = Gravity.CENTER
                setTextColor(Ui.INK)
                contentDescription = if (d > 0) "Adicionar folha" else "Remover folha"
                background = Ui.ripple(context.pill(Ui.FIELD, 0), context.pill(Color.WHITE, 0))
                setOnClickListener {
                    count = (count + d).coerceIn(1, 9999)
                    countLabel.text = "$count"
                    apply()
                }
            }
            countRow.addView(step("−", -1), LinearLayout.LayoutParams(context.dpi(44f), context.dpi(44f)))
            countLabel = context.label("$count", 16f, Ui.INK, bold = true).apply {
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
            }
            countRow.addView(countLabel, LinearLayout.LayoutParams(context.dpi(52f), ViewGroup.LayoutParams.WRAP_CONTENT))
            countRow.addView(step("+", 1), LinearLayout.LayoutParams(context.dpi(44f), context.dpi(44f)))
            pageOptions.addView(countRow)
            box.addView(pageOptions)
            styleModes()

            // Modelo
            box.addView(context.sectionLabel("Modelo"))
            val tiles = ArrayList<PageStyleTile>()
            val styles = context.horizontal()
            for (st in PageStyle.entries) {
                val col = context.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val tile = PageStyleTile(context, st, curPaper, st == curStyle) {
                    curStyle = st
                    tiles.forEach { it.checked = it.style == st }
                    canvas.pageStyle = curStyle
                    onChanged()
                }
                tiles.add(tile)
                col.addView(tile, LinearLayout.LayoutParams(context.dpi(70f), context.dpi(90f)))
                col.addView(context.label(st.label, 12f).apply { setPadding(0, context.dpi(6f), 0, 0) })
                styles.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(styles)

            // Cor do papel
            box.addView(context.sectionLabel("Cor do papel"))
            val colors = context.horizontal()
            val dots = ArrayList<ColorDot>()
            for ((name, col) in PAPER_COLORS) {
                val item = context.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val dot = ColorDot(context, col, col == curPaper) {
                    curPaper = col
                    dots.forEach { it.checked = it.color == col }
                    tiles.forEach { it.paper = col; it.invalidate() }
                    canvas.paperColor = curPaper
                    onChanged()
                }
                dot.contentDescription = name
                dots.add(dot)
                item.addView(dot, LinearLayout.LayoutParams(context.dpi(42f), context.dpi(42f)))
                item.addView(context.label(name, 11f).apply { setPadding(0, context.dpi(4f), 0, 0) })
                colors.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(colors)
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
