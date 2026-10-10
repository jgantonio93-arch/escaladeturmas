package com.jgantonio.notasinfinitas

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Tudo que pode estar na tela infinita. Os elementos são imutáveis: editar significa
 * trocar um elemento por outro (isso deixa desfazer/refazer simples e seguro).
 * Coordenadas sempre no "mundo".
 */
sealed class Element {
    abstract val bounds: RectF

    /** Cópia com escala [s] em torno de (ox, oy) seguida de deslocamento (dx, dy). */
    abstract fun transformed(s: Float, ox: Float, oy: Float, dx: Float, dy: Float): Element

    abstract fun insideLasso(lasso: Lasso): Boolean

    protected fun t(v: Float, s: Float, o: Float, d: Float) = (v - o) * s + o + d
}

class StrokeElement(
    val type: BrushType,
    val color: Int,
    val alpha: Int,
    val xs: FloatArray,
    val ys: FloatArray,
    val ws: FloatArray,
) : Element() {

    val n get() = xs.size

    val uniform: Boolean = ws.isNotEmpty() && ws.all { abs(it - ws[0]) < 0.01f }

    override val bounds: RectF = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r ->
        for (i in xs.indices) {
            val h = ws[i] / 2f + 1f
            r.union(xs[i] - h, ys[i] - h, xs[i] + h, ys[i] + h)
        }
        if (xs.isEmpty()) r.set(0f, 0f, 0f, 0f)
    }

    private var cachedPath: Path? = null
    val path: Path
        get() = cachedPath ?: StrokeRenderer.buildPath(xs, ys, ws, n, uniform).also { cachedPath = it }

    fun withColor(c: Int) = StrokeElement(type, c, alpha, xs, ys, ws)

    override fun transformed(s: Float, ox: Float, oy: Float, dx: Float, dy: Float) = StrokeElement(
        type, color, alpha,
        FloatArray(n) { t(xs[it], s, ox, dx) },
        FloatArray(n) { t(ys[it], s, oy, dy) },
        FloatArray(n) { ws[it] * s },
    )

    fun hits(px: Float, py: Float, radius: Float): Boolean {
        val m = (ws.maxOrNull() ?: 0f) / 2f + radius
        if (px < bounds.left - radius || px > bounds.right + radius ||
            py < bounds.top - radius || py > bounds.bottom + radius
        ) return false
        if (n == 1) return hypot(px - xs[0], py - ys[0]) <= m
        for (i in 1 until n) {
            val reach = max(ws[i - 1], ws[i]) / 2f + radius
            if (Geometry.segmentDist(px, py, xs[i - 1], ys[i - 1], xs[i], ys[i]) <= reach) return true
        }
        return false
    }

    /**
     * Borracha parcial: devolve os pedaços que sobram depois de apagar um círculo,
     * ou null se o círculo não encosta no traço.
     */
    fun eraseCircle(px: Float, py: Float, radius: Float): List<StrokeElement>? {
        if (!hits(px, py, radius)) return null
        // Densifica para não "pular" o círculo em segmentos longos (ex.: linhas retas).
        val step = max(0.5f, radius / 3f)
        val dx = ArrayList<Float>(); val dy = ArrayList<Float>(); val dw = ArrayList<Float>()
        for (i in 0 until n) {
            if (i > 0) {
                val len = hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
                val parts = ceil(len / step).toInt()
                for (k in 1 until parts) {
                    val f = k.toFloat() / parts
                    dx.add(xs[i - 1] + (xs[i] - xs[i - 1]) * f)
                    dy.add(ys[i - 1] + (ys[i] - ys[i - 1]) * f)
                    dw.add(ws[i - 1] + (ws[i] - ws[i - 1]) * f)
                }
            }
            dx.add(xs[i]); dy.add(ys[i]); dw.add(ws[i])
        }
        val pieces = ArrayList<StrokeElement>()
        var start = -1
        fun flush(end: Int) {
            if (start >= 0 && end - start >= 2) {
                pieces.add(StrokeElement(
                    type, color, alpha,
                    FloatArray(end - start) { dx[start + it] },
                    FloatArray(end - start) { dy[start + it] },
                    FloatArray(end - start) { dw[start + it] },
                ))
            }
            start = -1
        }
        for (i in dx.indices) {
            val inside = hypot(dx[i] - px, dy[i] - py) <= radius + dw[i] / 4f
            if (inside) flush(i) else if (start < 0) start = i
        }
        flush(dx.size)
        return pieces
    }

    override fun insideLasso(lasso: Lasso): Boolean {
        if (!RectF.intersects(bounds, lasso.bounds)) return false
        val stepI = max(1, n / 40)
        var inside = 0
        var total = 0
        var i = 0
        while (i < n) {
            total++
            if (lasso.contains(xs[i], ys[i])) inside++
            i += stepI
        }
        return inside * 2 >= total
    }
}

class TextElement(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
) : Element() {

    val lines: List<String> = text.split('\n')
    val lineHeight get() = size * 1.3f

    override val bounds: RectF = run {
        paint.textSize = size
        val w = lines.maxOf { paint.measureText(it) }
        RectF(x, y, x + max(w, size), y + lines.size * lineHeight)
    }

    fun withColor(c: Int) = TextElement(text, x, y, size, c)

    override fun transformed(s: Float, ox: Float, oy: Float, dx: Float, dy: Float) =
        TextElement(text, t(x, s, ox, dx), t(y, s, oy, dy), size * s, color)

    override fun insideLasso(lasso: Lasso) = lasso.contains(bounds.centerX(), bounds.centerY())

    companion object {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }
}

class ImageElement(val file: String, val rect: RectF) : Element() {
    override val bounds: RectF get() = rect

    override fun transformed(s: Float, ox: Float, oy: Float, dx: Float, dy: Float) = ImageElement(
        file,
        RectF(t(rect.left, s, ox, dx), t(rect.top, s, oy, dy), t(rect.right, s, ox, dx), t(rect.bottom, s, oy, dy)),
    )

    override fun insideLasso(lasso: Lasso) = lasso.contains(rect.centerX(), rect.centerY())
}

/** Polígono do laço de seleção, em coordenadas do mundo. */
class Lasso(private val xs: FloatArray, private val ys: FloatArray) {
    // (RectF.union(x, y) não serve aqui: com o retângulo "vazio" inicial ele só ajusta um lado.)
    val bounds = RectF(
        xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f,
        xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f,
    )

    fun contains(px: Float, py: Float): Boolean {
        if (!bounds.contains(px, py)) return false
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > py) != (ys[j] > py) &&
                px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]
            ) inside = !inside
            j = i
        }
        return inside
    }
}

object Geometry {
    fun segmentDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

// ---- Desfazer / refazer ----------------------------------------------------

sealed class Op {
    class Insert(val index: Int, val element: Element) : Op()
    class Remove(val index: Int, val element: Element) : Op()
}

/** Uma ação do usuário = sequência de operações. Desfazer aplica o inverso, de trás para frente. */
class EditAction(val ops: List<Op>) {
    fun redo(list: MutableList<Element>) {
        for (op in ops) when (op) {
            is Op.Insert -> list.add(op.index.coerceIn(0, list.size), op.element)
            is Op.Remove -> list.remove(op.element)
        }
    }

    fun undo(list: MutableList<Element>) {
        for (op in ops.asReversed()) when (op) {
            is Op.Insert -> list.remove(op.element)
            is Op.Remove -> list.add(op.index.coerceIn(0, list.size), op.element)
        }
    }
}
