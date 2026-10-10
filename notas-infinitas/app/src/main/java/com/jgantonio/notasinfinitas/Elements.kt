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

    /** Cópia com a transformação aplicada (escala, rotação e deslocamento). */
    abstract fun transformed(t: Transform): Element

    abstract fun insideLasso(lasso: Lasso): Boolean

    /** Elementos travados não são selecionados pelo laço, movidos ou apagados. */
    open val locked: Boolean get() = false
}

/**
 * p' = R(rotation) * ((p - origem) * escala) + origem + (dx, dy).
 * [rotation] em graus, sentido horário (como no Canvas).
 */
class Transform(
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
) {
    private val rad = Math.toRadians(rotation.toDouble())
    private val cos = kotlin.math.cos(rad).toFloat()
    private val sin = kotlin.math.sin(rad).toFloat()

    val isIdentity get() = scale == 1f && rotation == 0f && dx == 0f && dy == 0f

    fun x(px: Float, py: Float): Float {
        val ux = (px - ox) * scale
        val uy = (py - oy) * scale
        return ux * cos - uy * sin + ox + dx
    }

    fun y(px: Float, py: Float): Float {
        val ux = (px - ox) * scale
        val uy = (py - oy) * scale
        return ux * sin + uy * cos + oy + dy
    }

    /** Aplica a mesma transformação a um Canvas (para a prévia durante o gesto). */
    fun applyTo(c: android.graphics.Canvas) {
        c.translate(ox + dx, oy + dy)
        c.rotate(rotation)
        c.scale(scale, scale)
        c.translate(-ox, -oy)
    }

    companion object {
        fun translate(dx: Float, dy: Float) = Transform(dx = dx, dy = dy)
    }
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

    override fun transformed(t: Transform) = StrokeElement(
        type, color, alpha,
        FloatArray(n) { t.x(xs[it], ys[it]) },
        FloatArray(n) { t.y(xs[it], ys[it]) },
        FloatArray(n) { ws[it] * t.scale },
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

    override fun transformed(t: Transform): TextElement {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val ncx = t.x(cx, cy)
        val ncy = t.y(cx, cy)
        return TextElement(text, ncx - bounds.width() * t.scale / 2f, ncy - bounds.height() * t.scale / 2f, size * t.scale, color)
    }

    override fun insideLasso(lasso: Lasso) = lasso.contains(bounds.centerX(), bounds.centerY())

    companion object {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }
}

enum class ImageFilter(val label: String) {
    NONE("Original"), MONO("P&B"), SEPIA("Sépia"), VIVID("Vívido"),
    WARM("Quente"), COOL("Frio"), FADE("Desbotado"), NOIR("Noir"), INVERT("Negativo"),
}

enum class ImageMask(val label: String) { RECT("Reto"), ROUNDED("Arredondado"), ELLIPSE("Círculo"), FREE("Livre") }

/**
 * Imagem na tela. [rect] é o quadro visível (já recortado), sem rotação, em coordenadas
 * do mundo; a imagem gira em torno do centro dele. [crop] é a parte da imagem original
 * que aparece, em frações (0..1). [freeMask] é um polígono (x, y alternados, em frações
 * da imagem original) para o recorte à mão livre.
 */
class ImageElement(
    val file: String,
    val rect: RectF,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: RectF = RectF(0f, 0f, 1f, 1f),
    val mask: ImageMask = ImageMask.RECT,
    val freeMask: FloatArray? = null,
    val opacity: Int = 255,
    val filter: ImageFilter = ImageFilter.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Int = android.graphics.Color.WHITE,
    val shadow: Boolean = false,
    override val locked: Boolean = false,
    /** Página de PDF (informativo: aparece como "Página N" na barra). */
    val pdfPage: Int = 0,
) : Element() {

    fun with(
        file: String = this.file,
        rect: RectF = this.rect,
        rotation: Float = this.rotation,
        flipH: Boolean = this.flipH,
        flipV: Boolean = this.flipV,
        crop: RectF = this.crop,
        mask: ImageMask = this.mask,
        freeMask: FloatArray? = this.freeMask,
        opacity: Int = this.opacity,
        filter: ImageFilter = this.filter,
        brightness: Float = this.brightness,
        contrast: Float = this.contrast,
        saturation: Float = this.saturation,
        borderWidth: Float = this.borderWidth,
        borderColor: Int = this.borderColor,
        shadow: Boolean = this.shadow,
        locked: Boolean = this.locked,
        pdfPage: Int = this.pdfPage,
    ) = ImageElement(file, RectF(rect), normalizeAngle(rotation), flipH, flipV, RectF(crop), mask, freeMask, opacity,
        filter, brightness, contrast, saturation, borderWidth, borderColor, shadow, locked, pdfPage)

    val centerX get() = rect.centerX()
    val centerY get() = rect.centerY()

    /** Os 4 cantos do quadro já girado (x0,y0,...,x3,y3): sup-esq, sup-dir, inf-dir, inf-esq. */
    fun corners(): FloatArray {
        val t = Transform(rotation = rotation, ox = centerX, oy = centerY)
        val xs = floatArrayOf(rect.left, rect.right, rect.right, rect.left)
        val ys = floatArrayOf(rect.top, rect.top, rect.bottom, rect.bottom)
        return FloatArray(8) { i -> if (i % 2 == 0) t.x(xs[i / 2], ys[i / 2]) else t.y(xs[i / 2], ys[i / 2]) }
    }

    override val bounds: RectF = run {
        val c = corners()
        val pad = borderWidth + 1f
        RectF(
            minOf(c[0], c[2], c[4], c[6]) - pad, minOf(c[1], c[3], c[5], c[7]) - pad,
            maxOf(c[0], c[2], c[4], c[6]) + pad, maxOf(c[1], c[3], c[5], c[7]) + pad,
        )
    }

    /** Converte um ponto do mundo para coordenadas locais (centro = 0,0, sem rotação). */
    fun toLocal(px: Float, py: Float): Pair<Float, Float> {
        val t = Transform(rotation = -rotation, ox = centerX, oy = centerY)
        return (t.x(px, py) - centerX) to (t.y(px, py) - centerY)
    }

    fun contains(px: Float, py: Float): Boolean {
        val (lx, ly) = toLocal(px, py)
        return kotlin.math.abs(lx) <= rect.width() / 2f && kotlin.math.abs(ly) <= rect.height() / 2f
    }

    /** Quadro da imagem original inteira em coordenadas locais (para o modo de recorte). */
    fun fullLocalFrame(): RectF {
        val fw = rect.width() / crop.width()
        val fh = rect.height() / crop.height()
        val left = -rect.width() / 2f - crop.left * fw
        val top = -rect.height() / 2f - crop.top * fh
        return RectF(left, top, left + fw, top + fh)
    }

    /** Nova imagem mostrando [newCrop], mantendo a parte da imagem no mesmo lugar do mundo. */
    fun withCrop(newCrop: RectF, newMask: ImageMask = mask, newFree: FloatArray? = freeMask): ImageElement {
        val full = fullLocalFrame()
        val l = full.left + newCrop.left * full.width()
        val t = full.top + newCrop.top * full.height()
        val r = full.left + newCrop.right * full.width()
        val b = full.top + newCrop.bottom * full.height()
        val lcx = (l + r) / 2f
        val lcy = (t + b) / 2f
        // Local -> mundo: gira o centro local e soma o centro atual
        val rot = Transform(rotation = rotation)
        val wcx = centerX + rot.x(lcx, lcy)
        val wcy = centerY + rot.y(lcx, lcy)
        val w = r - l
        val h = b - t
        return with(rect = RectF(wcx - w / 2f, wcy - h / 2f, wcx + w / 2f, wcy + h / 2f), crop = newCrop, mask = newMask, freeMask = newFree)
    }

    override fun transformed(t: Transform): ImageElement {
        val ncx = t.x(centerX, centerY)
        val ncy = t.y(centerX, centerY)
        val w = rect.width() * t.scale / 2f
        val h = rect.height() * t.scale / 2f
        return with(
            rect = RectF(ncx - w, ncy - h, ncx + w, ncy + h),
            rotation = rotation + t.rotation,
            borderWidth = borderWidth * t.scale,
        )
    }

    override fun insideLasso(lasso: Lasso) = !locked && lasso.contains(centerX, centerY)

    companion object {
        fun normalizeAngle(a: Float): Float {
            var r = a % 360f
            if (r > 180f) r -= 360f
            if (r <= -180f) r += 360f
            return r
        }
    }
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
