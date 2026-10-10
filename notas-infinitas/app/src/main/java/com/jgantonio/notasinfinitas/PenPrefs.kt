package com.jgantonio.notasinfinitas

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Lembra as configurações de cada pincel, favoritos e cores, como o Samsung Notes. */
class PenPrefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("canetas", Context.MODE_PRIVATE)

    fun pen(type: BrushType): PenSettings =
        sp.getString("pen_${type.name}", null)?.let(PenSettings::decode)
            ?: PenSettings.default(type, if (type == BrushType.HIGHLIGHTER) HIGHLIGHT_COLORS[0] else INK_COLORS[0])

    fun savePen(p: PenSettings) = sp.edit().putString("pen_${p.type.name}", p.encode()).apply()

    var currentType: BrushType
        get() = BrushType.byName(sp.getString("current", null)) ?: BrushType.PEN
        set(v) = sp.edit().putString("current", v.name).apply()

    var favorites: List<PenSettings>
        get() = sp.getString("favorites", "")!!.split(';').mapNotNull { if (it.isBlank()) null else PenSettings.decode(it) }
        set(v) = sp.edit().putString("favorites", v.joinToString(";") { it.encode() }).apply()

    var customColors: List<Int>
        get() = sp.getString("custom_colors", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = sp.edit().putString("custom_colors", v.take(MAX_CUSTOM).joinToString(",")).apply()

    fun addCustomColor(c: Int) {
        customColors = listOf(c) + customColors.filter { it != c }
    }

    var eraserArea: Boolean
        get() = sp.getBoolean("eraser_area", false)
        set(v) = sp.edit().putBoolean("eraser_area", v).apply()

    var eraserSize: Float
        get() = sp.getFloat("eraser_size", 14f)
        set(v) = sp.edit().putFloat("eraser_size", v).apply()

    var eraserHighlighterOnly: Boolean
        get() = sp.getBoolean("eraser_hl", false)
        set(v) = sp.edit().putBoolean("eraser_hl", v).apply()

    var fingerDraws: Boolean
        get() = sp.getBoolean("finger_draws", false)
        set(v) = sp.edit().putBoolean("finger_draws", v).apply()

    var autoShapes: Boolean
        get() = sp.getBoolean("auto_shapes", false)
        set(v) = sp.edit().putBoolean("auto_shapes", v).apply()

    /** Tinta em front buffer (latência mínima). Pode ser desligada se algum aparelho não se der bem. */
    var lowLatency: Boolean
        get() = sp.getBoolean("low_latency", true)
        set(v) = sp.edit().putBoolean("low_latency", v).apply()

    var spenButtonErases: Boolean
        get() = sp.getBoolean("spen_button_erases", true)
        set(v) = sp.edit().putBoolean("spen_button_erases", v).apply()

    companion object {
        const val MAX_FAVORITES = 10
        const val MAX_CUSTOM = 12

        val INK_COLORS = listOf(
            "#1B1B1F", "#5F6368", "#FFFFFF", "#D7263D", "#F27405", "#F2C230",
            "#1E9E5A", "#1A9FB5", "#1F5FD1", "#0B2A6B", "#8E44AD", "#E84A8A", "#6D4C41",
        ).map(Color::parseColor)

        val HIGHLIGHT_COLORS = listOf(
            "#FFE600", "#9BF26B", "#FF8AD8", "#6BD5FF", "#FFB347", "#C59BFF",
        ).map(Color::parseColor)
    }
}
