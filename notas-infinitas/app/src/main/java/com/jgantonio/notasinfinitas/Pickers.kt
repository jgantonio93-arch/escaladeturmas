package com.jgantonio.notasinfinitas

import android.content.Context
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.icon
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.ripple
import com.jgantonio.notasinfinitas.Ui.rounded
import com.jgantonio.notasinfinitas.Ui.sheet

object Pickers {

    /**
     * Escolher pasta em árvore. [exclude] esconde uma pasta e tudo dentro dela
     * (para não mover uma pasta para dentro dela mesma). [current] aparece marcada.
     */
    fun folder(
        context: Context,
        title: String,
        allowRoot: Boolean,
        exclude: String?,
        current: String?,
        onPick: (String?) -> Unit,
    ) {
        val tree = Library.tree().filter { (f, _) -> exclude == null || (f.id != exclude && !Library.isInside(f.id, exclude)) }
        context.sheet(title) { box, dialog ->
            fun row(depth: Int, glyph: android.graphics.drawable.Drawable, name: String, id: String?) {
                val isCurrent = id == current
                val r = context.horizontal().apply {
                    minimumHeight = context.dpi(52f)
                    setPadding(context.dpi(6f + depth * 22f), 0, context.dpi(6f), 0)
                    background = ripple(null, context.rounded(Color.WHITE, 14f))
                    setOnClickListener {
                        dialog.dismiss()
                        if (!isCurrent) onPick(id)
                    }
                }
                r.addView(ImageView(context).apply { setImageDrawable(glyph) },
                    LinearLayout.LayoutParams(context.dpi(28f), context.dpi(22f)).apply { marginEnd = context.dpi(14f) })
                r.addView(context.label(name, 16f, if (isCurrent) Ui.MUTED else Ui.INK),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (isCurrent) {
                    r.addView(ImageView(context).apply { setImageDrawable(context.icon(Icon.CHECK, Ui.ACCENT, 20f)) },
                        LinearLayout.LayoutParams(context.dpi(24f), context.dpi(24f)))
                }
                box.addView(r)
            }
            if (allowRoot) row(0, context.icon(Icon.FOLDER, Ui.MUTED, 22f), "Página inicial", null)
            for ((f, depth) in tree) row(depth, FolderGlyphDrawable(f.color), f.name, f.id)
            if (tree.isEmpty() && !allowRoot) box.addView(context.label("Nenhuma pasta disponível.", 15f))
        }
    }
}
