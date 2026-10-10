package com.jgantonio.notasinfinitas

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.jgantonio.notasinfinitas.Ui.bottomSheet
import com.jgantonio.notasinfinitas.Ui.chip
import com.jgantonio.notasinfinitas.Ui.circle
import com.jgantonio.notasinfinitas.Ui.confirm
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.styleChip
import com.jgantonio.notasinfinitas.Ui.vertical
import com.jgantonio.notasinfinitas.Ui.wrapRow
import kotlin.math.PI
import kotlin.math.sin

object Panels {

    // ---- Caneta ----------------------------------------------------------------

    /**
     * Painel de canetas, como o do Samsung Notes: tipo de pincel, espessura,
     * opacidade, paleta + cor personalizada, formas automáticas e favoritos.
     */
    fun showPen(
        context: Context,
        prefs: PenPrefs,
        start: PenSettings,
        onChange: (PenSettings) -> Unit,
        onFavoritesChanged: () -> Unit,
    ) {
        var current = start
        val root = context.vertical(16f)
        val dialog = context.bottomSheet(root)

        fun update(p: PenSettings, rebuild: () -> Unit) {
            current = p
            prefs.savePen(p)
            prefs.currentType = p.type
            onChange(p)
            rebuild()
        }

        lateinit var render: () -> Unit
        render = {
            root.removeAllViews()
            root.addView(context.label("Canetas", 18f, Ui.INK, bold = true))

            // Tipos de pincel
            val types = context.horizontal()
            for (t in BrushType.entries) {
                val c = context.chip("${t.icon}\n${t.label}") {
                    update(prefs.pen(t), render)
                }
                c.textSize = 12f
                c.setPadding(context.dpi(10f), context.dpi(6f), context.dpi(10f), context.dpi(6f))
                c.styleChip(t == current.type)
                types.addView(c)
            }
            root.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, context.dpi(10f), 0, context.dpi(6f))
                addView(types)
            })

            // Prévia
            root.addView(PenPreview(context, current), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(70f),
            ))

            // Espessura
            val sizeLabel = context.label("")
            fun sizeText(p: PenSettings) { sizeLabel.text = "Espessura: ${p.size.toInt()}" }
            sizeText(current)
            root.addView(sizeLabel)
            root.addView(slider(context, 1, 50, current.size.toInt()) { v ->
                current = current.copy(size = v.toFloat())
                prefs.savePen(current)
                onChange(current)
                sizeText(current)
                (root.getChildAt(2) as? PenPreview)?.set(current)
            })

            // Opacidade
            val opLabel = context.label("")
            fun opText(p: PenSettings) { opLabel.text = "Opacidade: ${p.opacity}%" }
            opText(current)
            root.addView(opLabel)
            root.addView(slider(context, 5, 100, current.opacity) { v ->
                current = current.copy(opacity = v)
                prefs.savePen(current)
                onChange(current)
                opText(current)
                (root.getChildAt(2) as? PenPreview)?.set(current)
            })

            // Cores
            root.addView(context.label("Cores").apply { setPadding(0, context.dpi(8f), 0, context.dpi(4f)) })
            val palette = if (current.type == BrushType.HIGHLIGHTER) {
                PenPrefs.HIGHLIGHT_COLORS + PenPrefs.INK_COLORS
            } else {
                PenPrefs.INK_COLORS
            }
            val swatches = ArrayList<View>()
            for (c in palette + prefs.customColors) {
                swatches.add(swatch(context, c, c == current.color) {
                    update(current.copy(color = c), render)
                })
            }
            swatches.add(TextView(context).apply {
                text = "+"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Ui.INK)
                background = context.circle(Color.WHITE, Ui.BORDER, 1f)
                setOnClickListener {
                    ColorPicker.show(context, current.color) { picked ->
                        prefs.addCustomColor(picked)
                        update(current.copy(color = picked), render)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(context.dpi(34f), context.dpi(34f)).apply {
                    setMargins(context.dpi(4f), context.dpi(4f), context.dpi(4f), context.dpi(4f))
                }
            })
            root.addView(context.wrapRow(swatches, 9))

            // Opções
            root.addView(CheckBox(context).apply {
                text = "Formas automáticas (linha, círculo, retângulo, triângulo…)"
                isChecked = prefs.autoShapes
                setOnCheckedChangeListener { _, v ->
                    prefs.autoShapes = v
                    onChange(current)
                }
            })

            val favs = context.horizontal().apply { setPadding(0, context.dpi(6f), 0, 0) }
            favs.addView(context.chip("★ Adicionar aos favoritos") {
                val list = prefs.favorites
                if (current !in list) {
                    prefs.favorites = (list + current).takeLast(PenPrefs.MAX_FAVORITES)
                    onFavoritesChanged()
                }
                dialog.dismiss()
            })
            root.addView(favs)
            Unit
        }
        render()
        dialog.show()
    }

    private fun slider(context: Context, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) =
        SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = value
            setPadding(context.dpi(8f), context.dpi(10f), context.dpi(8f), context.dpi(10f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }

    fun swatch(context: Context, color: Int, selected: Boolean, onClick: () -> Unit) = View(context).apply {
        val light = Color.luminance(color) > 0.85f
        background = context.circle(
            color,
            if (selected) Ui.ACCENT else if (light) Ui.BORDER else Color.WHITE,
            if (selected) 3f else 1f,
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(context.dpi(34f), context.dpi(34f)).apply {
            setMargins(context.dpi(4f), context.dpi(4f), context.dpi(4f), context.dpi(4f))
        }
    }

    /** Amostra de traço com a caneta atual. */
    class PenPreview(context: Context, private var pen: PenSettings) : View(context) {
        fun set(p: PenSettings) {
            pen = p
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val b = StrokeBuilder(pen, context.resources.displayMetrics.density * 0.5f)
            val w = width.toFloat()
            val h = height.toFloat()
            val margin = context.dp(24f)
            val steps = 80
            for (i in 0..steps) {
                val f = i.toFloat() / steps
                val x = margin + f * (w - 2 * margin)
                val y = h / 2 + sin(f * 2 * PI).toFloat() * h * 0.25f
                val p = 0.35f + 0.65f * sin(f * PI).toFloat()
                b.add(x, y, p, 0.5f)
            }
            StrokeRenderer.drawElement(canvas, b.build())
        }
    }

    // ---- Borracha ---------------------------------------------------------------

    fun showEraser(context: Context, prefs: PenPrefs, onChange: () -> Unit, onClearAll: () -> Unit) {
        val root = context.vertical(16f)
        val dialog = context.bottomSheet(root)

        lateinit var render: () -> Unit
        render = {
            root.removeAllViews()
            root.addView(context.label("Borracha", 18f, Ui.INK, bold = true))
            val modes = context.horizontal().apply { setPadding(0, context.dpi(10f), 0, context.dpi(6f)) }
            val whole = context.chip("Apagar traço inteiro") { prefs.eraserArea = false; onChange(); render() }
            val area = context.chip("Apagar por área") { prefs.eraserArea = true; onChange(); render() }
            whole.styleChip(!prefs.eraserArea)
            area.styleChip(prefs.eraserArea)
            modes.addView(whole)
            modes.addView(area)
            root.addView(modes)

            val sizeLabel = context.label("Tamanho: ${prefs.eraserSize.toInt()}")
            root.addView(sizeLabel)
            root.addView(slider(context, 4, 60, prefs.eraserSize.toInt()) { v ->
                prefs.eraserSize = v.toFloat()
                sizeLabel.text = "Tamanho: $v"
                onChange()
            })
            root.addView(CheckBox(context).apply {
                text = "Apagar só marca-texto"
                isChecked = prefs.eraserHighlighterOnly
                setOnCheckedChangeListener { _, v -> prefs.eraserHighlighterOnly = v; onChange() }
            })
            root.addView(CheckBox(context).apply {
                text = "Botão da S Pen apaga (segure e passe)"
                isChecked = prefs.spenButtonErases
                setOnCheckedChangeListener { _, v -> prefs.spenButtonErases = v; onChange() }
            })
            root.addView(context.horizontal().apply {
                setPadding(0, context.dpi(8f), 0, 0)
                addView(context.chip("🗑 Apagar tudo") {
                    context.confirm("Apagar tudo?", "Todo o conteúdo da nota será apagado. Dá para desfazer em seguida.", "Apagar") {
                        onClearAll()
                        dialog.dismiss()
                    }
                })
            })
            Unit
        }
        render()
        dialog.show()
    }

    // ---- Plano de fundo -----------------------------------------------------------

    val PAPER_COLORS = listOf(
        "Branco" to "#FFFFFF",
        "Creme" to "#FFF8E7",
        "Cinza" to "#F1F3F5",
        "Verde" to "#EEF6EC",
        "Azul" to "#EDF3FB",
        "Escuro" to "#1E2228",
    ).map { it.first to Color.parseColor(it.second) }

    fun showBackground(context: Context, style: PageStyle, paper: Int, onChange: (PageStyle, Int) -> Unit) {
        var curStyle = style
        var curPaper = paper
        val root = context.vertical(16f)
        val dialog = context.bottomSheet(root)
        lateinit var render: () -> Unit
        render = {
            root.removeAllViews()
            root.addView(context.label("Plano de fundo", 18f, Ui.INK, bold = true))
            root.addView(context.label("Modelo").apply { setPadding(0, context.dpi(10f), 0, context.dpi(4f)) })
            val styles = context.horizontal()
            for (s in PageStyle.entries) {
                styles.addView(context.chip(s.label) {
                    curStyle = s
                    onChange(curStyle, curPaper)
                    render()
                }.apply { styleChip(s == curStyle) })
            }
            root.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(styles)
            })
            root.addView(context.label("Cor do papel").apply { setPadding(0, context.dpi(12f), 0, context.dpi(4f)) })
            val colors = context.horizontal()
            for ((name, c) in PAPER_COLORS) {
                val item = context.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                item.addView(swatch(context, c, c == curPaper) {
                    curPaper = c
                    onChange(curStyle, curPaper)
                    render()
                })
                item.addView(context.label(name, 11f))
                colors.addView(item)
            }
            root.addView(colors)
            Unit
        }
        render()
        dialog.show()
    }

    // ---- Texto ---------------------------------------------------------------------

    private val TEXT_SIZES = listOf("Pequeno" to 14f, "Médio" to 20f, "Grande" to 30f, "Título" to 44f)

    /** Caixa para digitar texto. [onDone] recebe texto e tamanho (em "sp" do mundo). */
    fun showText(
        context: Context,
        existing: TextElement?,
        density: Float,
        onDone: (String, Float) -> Unit,
        onDelete: (() -> Unit)?,
    ) {
        val input = EditText(context).apply {
            setText(existing?.text ?: "")
            hint = "Digite o texto"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        var size = existing?.let { it.size / density } ?: TEXT_SIZES[1].second
        val sizes = context.horizontal()
        val chips = ArrayList<Pair<TextView, Float>>()
        fun restyle() = chips.forEach { (c, s) -> c.styleChip(kotlin.math.abs(s - size) < 0.5f) }
        for ((name, s) in TEXT_SIZES) {
            val c = context.chip(name) { size = s; restyle() }
            chips.add(c to s)
            sizes.addView(c)
        }
        restyle()
        val box = context.vertical().apply {
            setPadding(context.dpi(20f), context.dpi(8f), context.dpi(20f), 0)
            addView(input)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, context.dpi(8f), 0, 0)
                addView(sizes)
            })
        }
        val b = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Inserir texto" else "Editar texto")
            .setView(FrameLayout(context).apply { addView(box) })
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trimEnd()
                if (t.isNotBlank() || existing != null) onDone(t, size * density)
            }
            .setNegativeButton("Cancelar", null)
        if (onDelete != null) b.setNeutralButton("Excluir") { _, _ -> onDelete() }
        val d = b.create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }
}
