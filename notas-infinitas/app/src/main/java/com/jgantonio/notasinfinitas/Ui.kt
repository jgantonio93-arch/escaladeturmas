package com.jgantonio.notasinfinitas

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pequenos utilitários para montar a interface em código (sem XML). */
object Ui {
    val INK = Color.parseColor("#1B1B1F")
    val MUTED = Color.parseColor("#5F6B7A")
    val ACCENT = Color.parseColor("#1F5FD1")
    val ACCENT_SOFT = Color.parseColor("#DCE7FA")
    val BORDER = Color.parseColor("#DDE1E7")
    val SURFACE = Color.parseColor("#F4F5F7")

    fun Context.dp(v: Float) = v * resources.displayMetrics.density
    fun Context.dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

    fun Context.pill(fill: Int, stroke: Int = BORDER, radiusDp: Float = 18f) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp)
        setColor(fill)
        setStroke(dpi(1f), stroke)
    }

    fun Context.circle(fill: Int, stroke: Int, strokeDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dpi(strokeDp), stroke)
    }

    fun Context.chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = dpi(38f)
        setPadding(dpi(12f), 0, dpi(12f), 0)
        setTextColor(INK)
        background = pill(Color.WHITE)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dpi(2f), 0, dpi(2f), 0) }
    }

    fun TextView.styleChip(selected: Boolean, enabled: Boolean = true) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
        background = context.pill(if (selected) ACCENT_SOFT else Color.WHITE, if (selected) ACCENT else BORDER)
        setTextColor(if (selected) Color.parseColor("#123E8C") else INK)
    }

    fun Context.label(text: String, sizeSp: Float = 13f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun Context.divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#D5DAE1"))
        layoutParams = LinearLayout.LayoutParams(dpi(1f), dpi(26f)).apply {
            setMargins(dpi(6f), 0, dpi(6f), 0)
        }
    }

    fun Context.vertical(paddingDp: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dpi(paddingDp)
        setPadding(p, p, p, p)
    }

    fun Context.horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Linha de itens que quebra automaticamente (como um "flow layout"). */
    fun Context.wrapRow(children: List<View>, perRow: Int): LinearLayout {
        val col = vertical()
        var row: LinearLayout? = null
        children.forEachIndexed { i, v ->
            if (i % perRow == 0) {
                row = horizontal().also { col.addView(it) }
            }
            row!!.addView(v)
        }
        return col
    }

    /** Painel que sobe de baixo da tela, com cantos arredondados. */
    fun Context.bottomSheet(content: View): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dp(20f), dp(20f), dp(20f), dp(20f), 0f, 0f, 0f, 0f)
            }
            addView(content)
        }
        val holder = FrameLayout(this)
        holder.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(holder)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            val maxW = minOf(resources.displayMetrics.widthPixels, dpi(560f))
            setLayout(maxW, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.25f)
        }
        return dialog
    }

    /** Diálogo com um campo de texto. */
    fun Context.promptText(title: String, initial: String, hint: String = "", onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(input)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }

    fun Context.confirm(title: String, message: String, action: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onOk() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun Context.choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, i -> onPick(i) }
            .show()
    }
}
