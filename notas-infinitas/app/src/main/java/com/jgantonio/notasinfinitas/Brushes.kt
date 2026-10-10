package com.jgantonio.notasinfinitas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Tipos de pincel, inspirados nos do Samsung Notes. */
enum class BrushType(
    val label: String,
    val shortLabel: String,
    val icon: String,
    val defaultSize: Float,
    val defaultOpacity: Int,
) {
    FOUNTAIN("Caneta-tinteiro", "Tinteiro", "🖋️", 5f, 100),
    PEN("Caneta", "Caneta", "🖊️", 4f, 100),
    PENCIL("Lápis", "Lápis", "✏️", 4f, 90),
    CALLIGRAPHY("Caligrafia", "Caligrafia", "✒️", 8f, 100),
    BRUSH("Pincel", "Pincel", "🖌️", 10f, 100),
    HIGHLIGHTER("Marca-texto", "Marca-texto", "🖍️", 20f, 45);

    companion object {
        fun byName(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** Configuração de uma caneta: tipo, cor, espessura (1..50) e opacidade (5..100%). */
data class PenSettings(val type: BrushType, val color: Int, val size: Float, val opacity: Int) {
    val alpha: Int get() = (opacity * 255 / 100).coerceIn(12, 255)

    fun encode() = "${type.name},$color,$size,$opacity"

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 50f

        fun default(type: BrushType, color: Int) = PenSettings(type, color, type.defaultSize, type.defaultOpacity)

        fun decode(s: String): PenSettings? {
            val p = s.split(',')
            if (p.size != 4) return null
            val type = BrushType.byName(p[0]) ?: return null
            return PenSettings(
                type,
                p[1].toIntOrNull() ?: return null,
                (p[2].toFloatOrNull() ?: return null).coerceIn(MIN_SIZE, MAX_SIZE),
                (p[3].toIntOrNull() ?: return null).coerceIn(5, 100),
            )
        }
    }
}

/**
 * Constrói um traço a partir dos pontos da caneta, calculando a espessura de cada
 * ponto conforme o pincel (pressão, velocidade e direção).
 *
 * [unit] converte o "tamanho" da caneta para unidades do mundo.
 */
class StrokeBuilder(val pen: PenSettings, unit: Float) {
    private val base = pen.size * unit
    var xs = FloatArray(128); private set
    var ys = FloatArray(128); private set
    var ws = FloatArray(128); private set
    var n = 0; private set
    private var smoothed = -1f

    fun add(x: Float, y: Float, pressure: Float, minDist: Float) {
        val p = pressure.coerceIn(0.05f, 1f)
        var dx = 0f
        var dy = 0f
        if (n > 0) {
            dx = x - xs[n - 1]
            dy = y - ys[n - 1]
            if (dx * dx + dy * dy < minDist * minDist) return
        }
        val speed = hypot(dx, dy)
        val target = when (pen.type) {
            BrushType.PEN -> base * (0.8f + 0.2f * p)
            BrushType.FOUNTAIN -> {
                val slow = 1.15f - min(0.45f, speed / (base * 6f + 1f) * 0.45f)
                base * (0.3f + 0.9f * p) * slow
            }
            BrushType.PENCIL -> base * (0.55f + 0.45f * p)
            BrushType.CALLIGRAPHY -> {
                // Bico chato inclinado a 45°: fino numa diagonal, largo na outra.
                val angle = if (n > 0) atan2(dy, dx) else (PI / 4).toFloat()
                val nib = abs(sin(angle - (PI / 4).toFloat()))
                base * (0.15f + 0.85f * nib) * (0.7f + 0.3f * p)
            }
            BrushType.BRUSH -> base * (0.08f + 1.5f * p.pow(1.5f))
            BrushType.HIGHLIGHTER -> base
        }
        val k = when (pen.type) {
            BrushType.HIGHLIGHTER -> 1f
            BrushType.BRUSH -> 0.25f
            BrushType.CALLIGRAPHY -> 0.45f
            else -> 0.4f
        }
        val w = if (smoothed < 0f) target else smoothed + (target - smoothed) * k
        smoothed = w
        if (n == xs.size) {
            xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2); ws = ws.copyOf(n * 2)
        }
        xs[n] = x; ys[n] = y; ws[n] = max(0.3f, w)
        n++
    }

    fun build(): StrokeElement {
        val w = ws.copyOf(n)
        // Afinamento nas pontas para pincel e caneta-tinteiro.
        val taper = when (pen.type) {
            BrushType.BRUSH -> 8
            BrushType.FOUNTAIN -> 4
            else -> 0
        }
        if (taper > 0 && n > taper * 2) {
            for (i in 0 until taper) {
                val f = 0.25f + 0.75f * (i + 1) / (taper + 1)
                if (pen.type == BrushType.BRUSH) w[i] *= f
                w[n - 1 - i] *= f
            }
        }
        return StrokeElement(pen.type, pen.color, pen.alpha, xs.copyOf(n), ys.copyOf(n), w)
    }
}

/** Desenha traços. Traços de espessura variável viram um contorno preenchido. */
object StrokeRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** Textura granulada do lápis (bitmap só de alfa: pega a cor da Paint). */
    private val pencilShader: Shader by lazy {
        val size = 96
        val rnd = Random(7)
        val pixels = IntArray(size * size) {
            val a = if (rnd.nextFloat() < 0.18f) 60 + rnd.nextInt(80) else 170 + rnd.nextInt(86)
            a shl 24
        }
        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            .extractAlpha()
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun buildPath(xs: FloatArray, ys: FloatArray, ws: FloatArray, n: Int, uniform: Boolean): Path {
        val path = Path()
        if (n == 0) return path
        if (uniform) {
            path.moveTo(xs[0], ys[0])
            if (n == 1) path.lineTo(xs[0] + 0.01f, ys[0])
            for (i in 1 until n) path.lineTo(xs[i], ys[i])
            return path
        }
        if (n == 1) {
            path.addCircle(xs[0], ys[0], ws[0] / 2f, Path.Direction.CW)
            return path
        }
        val lx = FloatArray(n); val ly = FloatArray(n)
        val rx = FloatArray(n); val ry = FloatArray(n)
        var startAngle = 0f
        var endAngle = 0f
        for (i in 0 until n) {
            val i0 = max(0, i - 1)
            val i1 = min(n - 1, i + 1)
            var tx = xs[i1] - xs[i0]
            var ty = ys[i1] - ys[i0]
            val len = hypot(tx, ty)
            if (len < 1e-4f) { tx = 1f; ty = 0f } else { tx /= len; ty /= len }
            if (i == 0) startAngle = Math.toDegrees(atan2(ty, tx).toDouble()).toFloat()
            if (i == n - 1) endAngle = Math.toDegrees(atan2(ty, tx).toDouble()).toFloat()
            val h = ws[i] / 2f
            lx[i] = xs[i] - ty * h; ly[i] = ys[i] + tx * h
            rx[i] = xs[i] + ty * h; ry[i] = ys[i] - tx * h
        }
        path.moveTo(lx[0], ly[0])
        for (i in 1 until n - 1) {
            path.quadTo(lx[i], ly[i], (lx[i] + lx[i + 1]) / 2f, (ly[i] + ly[i + 1]) / 2f)
        }
        path.lineTo(lx[n - 1], ly[n - 1])
        val he = ws[n - 1] / 2f
        path.arcTo(RectF(xs[n - 1] - he, ys[n - 1] - he, xs[n - 1] + he, ys[n - 1] + he), endAngle + 90f, -180f)
        for (i in n - 2 downTo 1) {
            path.quadTo(rx[i], ry[i], (rx[i] + rx[i - 1]) / 2f, (ry[i] + ry[i - 1]) / 2f)
        }
        path.lineTo(rx[0], ry[0])
        val hs = ws[0] / 2f
        path.arcTo(RectF(xs[0] - hs, ys[0] - hs, xs[0] + hs, ys[0] + hs), startAngle - 90f, -180f)
        path.close()
        return path
    }

    /** Desenha um traço já em coordenadas do mundo (o canvas já deve estar transformado). */
    fun draw(
        canvas: Canvas,
        type: BrushType,
        color: Int,
        alpha: Int,
        path: Path,
        uniform: Boolean,
        uniformWidth: Float,
        bounds: RectF,
    ) {
        val layered = alpha < 255
        if (layered) canvas.saveLayerAlpha(bounds, alpha)
        val paint = if (uniform) linePaint else fillPaint
        paint.color = color or (0xFF shl 24)
        paint.shader = if (type == BrushType.PENCIL) pencilShader else null
        if (uniform) {
            linePaint.strokeWidth = uniformWidth
            linePaint.strokeCap = if (type == BrushType.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        }
        canvas.drawPath(path, paint)
        paint.shader = null
        if (layered) canvas.restore()
    }

    fun drawElement(canvas: Canvas, s: StrokeElement) {
        draw(canvas, s.type, s.color, s.alpha, s.path, s.uniform, s.ws.firstOrNull() ?: 1f, s.bounds)
    }

    fun drawBuilder(canvas: Canvas, b: StrokeBuilder) {
        if (b.n == 0) return
        val uniform = b.pen.type == BrushType.HIGHLIGHTER
        val path = buildPath(b.xs, b.ys, b.ws, b.n, uniform)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val pad = (b.ws.maxOrNull() ?: 1f) + 2f
        bounds.inset(-pad, -pad)
        draw(canvas, b.pen.type, b.pen.color, b.pen.alpha, path, uniform, b.ws[0], bounds)
    }
}
