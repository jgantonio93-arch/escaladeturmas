package com.jgantonio.notasinfinitas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jgantonio.notasinfinitas.Ui.buttonRow
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.primaryButton
import com.jgantonio.notasinfinitas.Ui.rounded
import com.jgantonio.notasinfinitas.Ui.secondaryButton
import com.jgantonio.notasinfinitas.Ui.sectionLabel
import com.jgantonio.notasinfinitas.Ui.segmented
import com.jgantonio.notasinfinitas.Ui.sheet
import com.jgantonio.notasinfinitas.Ui.switchRow
import com.jgantonio.notasinfinitas.Ui.vertical
import kotlin.math.roundToInt

/** Painel "Ajustes da imagem": filtros, luz/cor, opacidade, cantos, moldura e sombra. */
object ImageTools {

    fun showAdjustments(context: Context, canvas: InfiniteCanvasView) {
        val start = canvas.selectedImage ?: return
        canvas.beginDraft()
        val thumb: Bitmap? = canvas.images.bitmap(start, 160f, sync = true)?.let { b ->
            val k = 150f / maxOf(b.width, b.height)
            Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
        }

        val dialog = context.sheet("Ajustes da imagem") { box, dialog ->
            fun cur() = canvas.selectedImage ?: start

            // Filtros
            box.addView(context.sectionLabel("Filtros").apply { setPadding(0, 0, 0, context.dpi(8f)) })
            val tiles = ArrayList<Pair<LinearLayout, ImageFilter>>()
            fun styleTiles() = tiles.forEach { (t, f) ->
                val sel = cur().filter == f
                (t.getChildAt(0) as ImageView).background = context.rounded(Color.WHITE, 14f, if (sel) Ui.ACCENT else Color.TRANSPARENT, 2.5f)
                (t.getChildAt(1) as TextView).setTextColor(if (sel) Ui.ACCENT else Ui.MUTED)
            }
            val row = context.horizontal()
            for (f in ImageFilter.entries) {
                val t = context.vertical().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(context.dpi(4f), 0, context.dpi(4f), 0)
                    setOnClickListener {
                        canvas.updateDraft { it.with(filter = f) }
                        styleTiles()
                    }
                }
                t.addView(ImageView(context).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    colorFilter = if (f == ImageFilter.NONE) null else ColorMatrixColorFilter(ImageRenderer.matrix(f, 0f, 0f, 0f))
                    setPadding(context.dpi(3f), context.dpi(3f), context.dpi(3f), context.dpi(3f))
                    clipToOutline = true
                }, LinearLayout.LayoutParams(context.dpi(68f), context.dpi(68f)))
                t.addView(context.label(f.label, 12f).apply { setPadding(0, context.dpi(4f), 0, 0) })
                tiles.add(t to f)
                row.addView(t)
            }
            styleTiles()
            box.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })

            // Luz e cor
            box.addView(context.sectionLabel("Luz e cor"))
            fun sliderRow(name: String, slider: FancySlider, value: TextView) = context.horizontal().apply {
                addView(context.label(name, 13f), LinearLayout.LayoutParams(context.dpi(84f), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                value.gravity = Gravity.END
                addView(value, LinearLayout.LayoutParams(context.dpi(44f), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            fun pct(v: Float) = "${(v * 100).roundToInt()}"
            val bVal = context.label(pct(start.brightness), 13f, Ui.INK, bold = true)
            val cVal = context.label(pct(start.contrast), 13f, Ui.INK, bold = true)
            val sVal = context.label(pct(start.saturation), 13f, Ui.INK, bold = true)
            val oVal = context.label("${start.opacity * 100 / 255}%", 13f, Ui.INK, bold = true)
            val bS = CenterSlider(context, 1f, start.brightness) { v -> canvas.updateDraft { it.with(brightness = v) }; bVal.text = pct(v) }
            val cS = CenterSlider(context, 1f, start.contrast) { v -> canvas.updateDraft { it.with(contrast = v) }; cVal.text = pct(v) }
            val sS = CenterSlider(context, 1f, start.saturation) { v -> canvas.updateDraft { it.with(saturation = v) }; sVal.text = pct(v) }
            val oS = OpacitySlider(context, 5f, 100f, start.opacity * 100f / 255f) { v ->
                canvas.updateDraft { it.with(opacity = (v * 2.55f).roundToInt().coerceIn(13, 255)) }
                oVal.text = "${v.roundToInt()}%"
            }.apply { color = Color.parseColor("#5F6B7A") }
            box.addView(sliderRow("Brilho", bS, bVal))
            box.addView(sliderRow("Contraste", cS, cVal))
            box.addView(sliderRow("Saturação", sS, sVal))
            box.addView(sliderRow("Opacidade", oS, oVal))

            // Cantos
            box.addView(context.sectionLabel("Formato"))
            val shapes = listOf(ImageMask.RECT, ImageMask.ROUNDED, ImageMask.ELLIPSE)
            val startShape = shapes.indexOf(start.mask).coerceAtLeast(0)
            box.addView(context.segmented(listOf("Reto", "Arredondado", "Círculo"), startShape) { i ->
                canvas.updateDraft { it.with(mask = shapes[i], freeMask = null) }
            })

            // Moldura
            box.addView(context.sectionLabel("Moldura"))
            val wVal = context.label("${start.borderWidth.roundToInt()}", 13f, Ui.INK, bold = true)
            box.addView(sliderRow("Espessura", SizeSlider(context, 0f, 40f, start.borderWidth) { v ->
                canvas.updateDraft { it.with(borderWidth = v.roundToInt().toFloat()) }
                wVal.text = "${v.roundToInt()}"
            }, wVal))
            val frameColors = listOf("#FFFFFF", "#1B1B1F", "#D7263D", "#F2C230", "#1E9E5A", "#1F5FD1", "#8E44AD", "#E84A8A").map(Color::parseColor)
            val dots = ArrayList<ColorDot>()
            val dotRow = context.horizontal().apply { setPadding(0, context.dpi(6f), 0, 0) }
            for (col in frameColors) {
                val d = ColorDot(context, col, col == start.borderColor) {
                    canvas.updateDraft { it.with(borderColor = col, borderWidth = if (it.borderWidth > 0f) it.borderWidth else 8f) }
                    dots.forEach { x -> x.checked = x.color == col }
                    wVal.text = "${cur().borderWidth.roundToInt()}"
                }
                dots.add(d)
                dotRow.addView(d, LinearLayout.LayoutParams(0, context.dpi(34f), 1f).apply { setMargins(context.dpi(2f), 0, context.dpi(2f), 0) })
            }
            box.addView(dotRow)

            box.addView(context.switchRow(Icon.PAGE, "Sombra", start.shadow) { on -> canvas.updateDraft { it.with(shadow = on) } })

            box.addView(context.buttonRow(
                context.secondaryButton("Redefinir") {
                    canvas.updateDraft {
                        it.with(filter = ImageFilter.NONE, brightness = 0f, contrast = 0f, saturation = 0f, opacity = 255,
                            borderWidth = 0f, shadow = false, mask = if (it.mask == ImageMask.FREE) ImageMask.FREE else ImageMask.RECT)
                    }
                    dialog.dismiss()
                },
                context.primaryButton("Pronto") { dialog.dismiss() },
            ))
        }
        dialog.setOnDismissListener { canvas.finishDraft() }
    }
}
