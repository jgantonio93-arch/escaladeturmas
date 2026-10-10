package com.jgantonio.notasinfinitas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
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

/**
 * Configuração de uma caneta: tipo, cor, espessura (1..50) e opacidade (5..100%).
 * Só para o marca-texto: [tip] é a espessura da ponta chanfrada (10..100% da altura)
 * e [straight] desenha sempre linhas retas.
 */
data class PenSettings(
    val type: BrushType,
    val color: Int,
    val size: Float,
    val opacity: Int,
    val tip: Int = DEFAULT_TIP,
    val straight: Boolean = false,
) {
    val alpha: Int get() = (opacity * 255 / 100).coerceIn(12, 255)

    fun encode() = "${type.name},$color,$size,$opacity,$tip,${if (straight) 1 else 0}"

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 50f
        const val DEFAULT_TIP = 35

        fun default(type: BrushType, color: Int) = PenSettings(type, color, type.defaultSize, type.defaultOpacity)

        fun decode(s: String): PenSettings? {
            val p = s.split(',')
            if (p.size != 4 && p.size != 6) return null
            val type = BrushType.byName(p[0]) ?: return null
            return PenSettings(
                type,
                p[1].toIntOrNull() ?: return null,
                (p[2].toFloatOrNull() ?: return null).coerceIn(MIN_SIZE, MAX_SIZE),
                (p[3].toIntOrNull() ?: return null).coerceIn(5, 100),
                tip = (p.getOrNull(4)?.toIntOrNull() ?: DEFAULT_TIP).coerceIn(10, 100),
                straight = p.getOrNull(5) == "1",
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
    private val highlighter = pen.type == BrushType.HIGHLIGHTER
    private val straight = highlighter && pen.straight
    private val tipRatio = pen.tip.coerceIn(10, 100) / 100f
    // Direção suavizada (só o marca-texto usa: a ponta chanfrada muda a largura conforme a direção).
    private var dirX = 1f
    private var dirY = 0f

    fun add(x: Float, y: Float, pressure: Float, minDist: Float) {
        if (straight && n >= 1) {
            addStraight(x, y)
            return
        }
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
            BrushType.PENCIL -> base * (0.45f + 0.55f * p)
            BrushType.CALLIGRAPHY -> {
                // Bico chato inclinado a 45°: fino numa diagonal, largo na outra.
                val angle = if (n > 0) atan2(dy, dx) else (PI / 4).toFloat()
                val nib = abs(sin(angle - (PI / 4).toFloat()))
                base * (0.15f + 0.85f * nib) * (0.7f + 0.3f * p)
            }
            BrushType.BRUSH -> base * (0.08f + 1.5f * p.pow(1.5f))
            BrushType.HIGHLIGHTER -> {
                if (speed > 0f) {
                    dirX += (dx / speed - dirX) * 0.5f
                    dirY += (dy / speed - dirY) * 0.5f
                }
                chisel(dirX, dirY)
            }
        }
        val k = when (pen.type) {
            BrushType.HIGHLIGHTER -> 0.5f
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
        // O primeiro ponto não sabia a direção: assume a largura do segundo.
        if (highlighter && n == 1) ws[0] = ws[1]
        n++
    }

    /**
     * Largura da faixa de uma ponta chanfrada em pé (altura = tamanho, largura = [tipRatio])
     * andando na direção (dx, dy): a projeção do retângulo da ponta na normal do movimento.
     */
    private fun chisel(dx: Float, dy: Float): Float {
        val len = hypot(dx, dy)
        if (len < 1e-4f) return base
        return base * (abs(dx / len) + tipRatio * abs(dy / len))
    }

    /** Linha reta do primeiro ponto até a caneta; quase na horizontal, fica perfeitamente reta. */
    private fun addStraight(x: Float, y: Float) {
        var ty = y
        val dx = x - xs[0]
        if (abs(ty - ys[0]) < abs(dx) * 0.105f) ty = ys[0] // ~6°
        val w = chisel(dx, ty - ys[0])
        xs[1] = x; ys[1] = ty
        ws[0] = w; ws[1] = w
        n = 2
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

/**
 * Desenha traços. Traços de espessura variável viram um contorno preenchido.
 *
 * Opacidade: cada traço é UM desenho (contorno preenchido ou linha), então a cor
 * translúcida já sai certa direto na Paint, sem camada extra (saveLayer) por traço.
 * Isso deixa a tela bem mais leve quando há muito marca-texto e lápis.
 */
object StrokeRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Zoom atual da tela. O grão do lápis acompanha a tela (tamanho fixo em pixels),
     * como grafite em papel: não vira "purpurina" ao afastar nem manchas ao aproximar.
     */
    var zoom = 1f

    /** Grão do lápis: só alfa, quase sempre forte, com falhas finas como papel. */
    private val grain: IntArray by lazy {
        val rnd = Random(11)
        IntArray(GRAIN * GRAIN) {
            val r = rnd.nextFloat()
            when {
                r < 0.10f -> 95 + rnd.nextInt(55)
                r < 0.35f -> 175 + rnd.nextInt(40)
                else -> 220 + rnd.nextInt(36)
            }
        }
    }

    // Um shader por cor (bitmap colorido comum: funciona igual em todo aparelho).
    private class PencilInk(val shader: BitmapShader, var zoom: Float = -1f)
    private val pencilInks = object : LinkedHashMap<Int, PencilInk>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PencilInk>?) = size > 8
    }
    private val grainMatrix = Matrix()

    private fun pencilShader(color: Int): Shader {
        val rgb = color and 0xFFFFFF
        val ink = pencilInks.getOrPut(rgb) {
            val g = grain
            val pixels = IntArray(g.size) { (g[it] shl 24) or rgb }
            val bmp = Bitmap.createBitmap(pixels, GRAIN, GRAIN, Bitmap.Config.ARGB_8888)
            PencilInk(BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
        }
        val z = zoom.coerceAtLeast(0.01f)
        if (ink.zoom != z) {
            grainMatrix.setScale(1f / z, 1f / z)
            ink.shader.setLocalMatrix(grainMatrix)
            ink.zoom = z
        }
        return ink.shader
    }

    // Vetores reaproveitados (o desenho acontece sempre na thread principal):
    // nada de alocar memória a cada ponto do traço.
    private var lx = FloatArray(256)
    private var ly = FloatArray(256)
    private var rx = FloatArray(256)
    private var ry = FloatArray(256)
    private var px = FloatArray(256)
    private var py = FloatArray(256)
    private var pw = FloatArray(256)
    private val livePath = Path()

    private fun ensureScratch(n: Int) {
        if (lx.size >= n) return
        val size = maxOf(n, lx.size * 2)
        lx = FloatArray(size); ly = FloatArray(size); rx = FloatArray(size); ry = FloatArray(size)
    }

    private fun ensurePredScratch(n: Int) {
        if (px.size >= n) return
        val size = maxOf(n, px.size * 2)
        px = FloatArray(size); py = FloatArray(size); pw = FloatArray(size)
    }

    /**
     * Contorno do traço. [flatCaps] deixa as pontas retas (marca-texto, que tem ponta
     * chanfrada); os outros pincéis têm pontas redondas.
     */
    fun buildPath(
        xs: FloatArray, ys: FloatArray, ws: FloatArray, n: Int, uniform: Boolean,
        into: Path? = null, flatCaps: Boolean = false,
    ): Path {
        val path = into?.also { it.rewind() } ?: Path()
        if (n == 0) return path
        if (uniform) {
            path.moveTo(xs[0], ys[0])
            if (n == 1) path.lineTo(xs[0] + 0.01f, ys[0])
            for (i in 1 until n) path.lineTo(xs[i], ys[i])
            return path
        }
        if (n == 1) {
            val h = ws[0] / 2f
            if (flatCaps) path.addRect(xs[0] - h * 0.35f, ys[0] - h, xs[0] + h * 0.35f, ys[0] + h, Path.Direction.CW)
            else path.addCircle(xs[0], ys[0], h, Path.Direction.CW)
            return path
        }
        ensureScratch(n)
        val lx = lx; val ly = ly; val rx = rx; val ry = ry
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
        if (flatCaps) path.lineTo(rx[n - 1], ry[n - 1])
        else path.arcTo(xs[n - 1] - he, ys[n - 1] - he, xs[n - 1] + he, ys[n - 1] + he, endAngle + 90f, -180f, false)
        for (i in n - 2 downTo 1) {
            path.quadTo(rx[i], ry[i], (rx[i] + rx[i - 1]) / 2f, (ry[i] + ry[i - 1]) / 2f)
        }
        path.lineTo(rx[0], ry[0])
        val hs = ws[0] / 2f
        if (!flatCaps) path.arcTo(xs[0] - hs, ys[0] - hs, xs[0] + hs, ys[0] + hs, startAngle - 90f, -180f, false)
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
    ) {
        val paint = if (uniform) linePaint else fillPaint
        paint.color = color or (0xFF shl 24)
        paint.shader = if (type == BrushType.PENCIL) pencilShader(color) else null
        paint.alpha = alpha
        if (uniform) {
            linePaint.strokeWidth = uniformWidth
            linePaint.strokeCap = if (type == BrushType.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        }
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    fun drawElement(canvas: Canvas, s: StrokeElement) {
        draw(canvas, s.type, s.color, s.alpha, s.path, s.uniform, s.ws.firstOrNull() ?: 1f)
    }

    /**
     * Traço em andamento. Os pontos previstos ([predXs]/[predYs], Android 14+) entram no
     * mesmo contorno: a ponta prevista tem a mesma textura e transparência do traço.
     */
    fun drawBuilder(canvas: Canvas, b: StrokeBuilder, predXs: FloatArray? = null, predYs: FloatArray? = null, predN: Int = 0) {
        if (b.n == 0) return
        val flat = b.pen.type == BrushType.HIGHLIGHTER
        var xs = b.xs; var ys = b.ys; var ws = b.ws; var n = b.n
        if (predN > 0 && predXs != null && predYs != null && !(flat && b.pen.straight)) {
            ensurePredScratch(n + predN)
            System.arraycopy(b.xs, 0, px, 0, n)
            System.arraycopy(b.ys, 0, py, 0, n)
            System.arraycopy(b.ws, 0, pw, 0, n)
            val w = b.ws[n - 1]
            for (i in 0 until predN) {
                px[n + i] = predXs[i]; py[n + i] = predYs[i]; pw[n + i] = w
            }
            xs = px; ys = py; ws = pw; n += predN
        }
        // Mesmo critério do traço pronto (StrokeElement.uniform), para não "pular" ao soltar.
        var uniform = !flat
        if (uniform) for (i in 1 until n) if (abs(ws[i] - ws[0]) >= 0.01f) { uniform = false; break }
        val path = buildPath(xs, ys, ws, n, uniform, livePath, flatCaps = flat)
        draw(canvas, b.pen.type, b.pen.color, b.pen.alpha, path, uniform, ws[0])
    }

    private const val GRAIN = 64
}
