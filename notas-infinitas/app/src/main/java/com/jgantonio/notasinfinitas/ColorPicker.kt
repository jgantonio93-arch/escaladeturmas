package com.jgantonio.notasinfinitas

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.jgantonio.notasinfinitas.Ui.circle
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.vertical

/** Seletor de cor personalizada: quadrado de saturação/brilho, barra de matiz e código hex. */
object ColorPicker {

    fun show(context: Context, initial: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val preview = View(context)
        val hex = EditText(context).apply {
            filters = arrayOf(InputFilter.LengthFilter(7))
            isSingleLine = true
            textSize = 15f
        }
        lateinit var sv: SatValView
        lateinit var hue: HueBar
        var updatingText = false

        fun refresh(fromText: Boolean = false) {
            val c = Color.HSVToColor(hsv)
            preview.background = context.circle(c, Ui.BORDER, 1f)
            sv.hue = hsv[0]
            sv.invalidate()
            hue.invalidate()
            if (!fromText) {
                updatingText = true
                hex.setText(String.format("#%06X", c and 0xFFFFFF))
                updatingText = false
            }
        }

        sv = SatValView(context, hsv) { refresh() }
        hue = HueBar(context, hsv) { refresh() }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingText) return
                val t = s.toString().trim()
                if (t.length == 7 && t.startsWith("#")) {
                    try {
                        Color.colorToHSV(Color.parseColor(t), hsv)
                        refresh(fromText = true)
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
        })

        val root = context.vertical(16f)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(200f)))
        root.addView(hue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(28f)).apply {
            topMargin = context.dpi(14f)
        })
        val row = context.horizontal().apply { setPadding(0, context.dpi(14f), 0, 0) }
        row.addView(preview, LinearLayout.LayoutParams(context.dpi(36f), context.dpi(36f)))
        row.addView(context.label("  Hex "))
        row.addView(hex, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        refresh()

        AlertDialog.Builder(context)
            .setTitle("Cor personalizada")
            .setView(root)
            .setPositiveButton("Usar cor") { _, _ -> onPick(Color.HSVToColor(hsv)) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private class SatValView(context: Context, val hsv: FloatArray, val onChange: () -> Unit) : View(context) {
        var hue = hsv[0]
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
            color = Color.WHITE
        }
        private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(1f)
            color = Color.parseColor("#55000000")
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            val sat = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
            val v = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
            paint.shader = ComposeShader(v, sat, PorterDuff.Mode.MULTIPLY)
            canvas.drawRoundRect(rect, context.dp(10f), context.dp(10f), paint)
            val x = hsv[1] * width
            val y = (1f - hsv[2]) * height
            canvas.drawCircle(x, y, context.dp(9f), ring)
            canvas.drawCircle(x, y, context.dp(10.5f), ringOuter)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            hsv[1] = (e.x / width).coerceIn(0f, 1f)
            hsv[2] = 1f - (e.y / height).coerceIn(0f, 1f)
            onChange()
            return true
        }
    }

    private class HueBar(context: Context, val hsv: FloatArray, val onChange: () -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
            color = Color.WHITE
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
            val x = hsv[0] / 360f * width
            canvas.drawCircle(x.coerceIn(height / 2f, width - height / 2f), height / 2f, height / 2f - context.dp(2f), marker)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            hsv[0] = (e.x / width).coerceIn(0f, 1f) * 359.9f
            onChange()
            return true
        }
    }
}
