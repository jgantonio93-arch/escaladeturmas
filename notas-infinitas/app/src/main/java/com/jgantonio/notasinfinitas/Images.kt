package com.jgantonio.notasinfinitas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Carrega e desenha imagens. Os bitmaps são lidos em segundo plano, numa resolução
 * proporcional ao tamanho em que aparecem na tela (PDFs grandes não estouram a memória).
 */
class ImageRenderer(private val onLoaded: () -> Unit) {

    var dir: File? = null

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val loading = HashSet<String>()
    private val sizes = HashMap<String, Pair<Int, Int>>()
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val placeholder = Paint().apply { color = Color.parseColor("#EEF0F3") }

    /** Tamanho original da imagem (lido só o cabeçalho). */
    fun sourceSize(name: String): Pair<Int, Int>? {
        sizes[name]?.let { return it }
        val f = File(dir ?: return null, name)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        return (o.outWidth to o.outHeight).also { sizes[name] = it }
    }

    private fun sampleFor(name: String, screenW: Float): Int {
        val (w, _) = sourceSize(name) ?: return 1
        var s = 1
        while (w / (s * 2) >= screenW * 1.1f && s < 64) s *= 2
        return s
    }

    private fun decode(name: String, sample: Int): Bitmap? {
        val f = File(dir ?: return null, name)
        return try {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                if (name.endsWith(".jpg")) inPreferredConfig = Bitmap.Config.RGB_565
            })
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            null
        }
    }

    /**
     * Bitmap para mostrar [img] com [screenW] pixels de largura (quadro visível).
     * Se [sync], decodifica na hora (exportar, miniatura); senão agenda e devolve o
     * melhor que já estiver em cache.
     */
    fun bitmap(img: ImageElement, screenW: Float, sync: Boolean): Bitmap? {
        val fullScreenW = screenW / img.crop.width()
        val sample = sampleFor(img.file, fullScreenW)
        val key = "${img.file}@$sample"
        cache.get(key)?.let { return it }
        if (sync) return decode(img.file, sample)?.also { cache.put(key, it) }
        if (loading.add(key)) {
            decoder.execute {
                val b = decode(img.file, sample)
                main.post {
                    loading.remove(key)
                    if (b != null) cache.put(key, b)
                    onLoaded()
                }
            }
        }
        // Enquanto carrega, usa outra resolução que já estiver na memória.
        var s = 1
        while (s <= 64) {
            cache.get("${img.file}@$s")?.let { return it }
            s *= 2
        }
        return null
    }

    // ---- Cor -------------------------------------------------------------------------

    fun colorFilter(img: ImageElement): ColorMatrixColorFilter? {
        if (img.filter == ImageFilter.NONE && img.brightness == 0f && img.contrast == 0f && img.saturation == 0f) return null
        return ColorMatrixColorFilter(matrix(img.filter, img.brightness, img.contrast, img.saturation))
    }

    companion object {
        fun matrix(filter: ImageFilter, brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
            val m = ColorMatrix()
            when (filter) {
                ImageFilter.NONE -> Unit
                ImageFilter.MONO -> m.setSaturation(0f)
                ImageFilter.SEPIA -> {
                    m.setSaturation(0f)
                    m.postConcat(ColorMatrix(floatArrayOf(
                        1.07f, 0f, 0f, 0f, 18f,
                        0f, 0.95f, 0f, 0f, 6f,
                        0f, 0f, 0.78f, 0f, -12f,
                        0f, 0f, 0f, 1f, 0f,
                    )))
                }
                ImageFilter.VIVID -> {
                    m.setSaturation(1.55f)
                    m.postConcat(contrastMatrix(0.12f))
                }
                ImageFilter.WARM -> m.set(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 12f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.86f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f,
                ))
                ImageFilter.COOL -> m.set(floatArrayOf(
                    0.88f, 0f, 0f, 0f, -8f,
                    0f, 1f, 0f, 0f, 2f,
                    0f, 0f, 1.12f, 0f, 14f,
                    0f, 0f, 0f, 1f, 0f,
                ))
                ImageFilter.FADE -> {
                    m.setSaturation(0.6f)
                    m.postConcat(ColorMatrix(floatArrayOf(
                        0.82f, 0f, 0f, 0f, 38f,
                        0f, 0.82f, 0f, 0f, 38f,
                        0f, 0f, 0.82f, 0f, 42f,
                        0f, 0f, 0f, 1f, 0f,
                    )))
                }
                ImageFilter.NOIR -> {
                    m.setSaturation(0f)
                    m.postConcat(contrastMatrix(0.45f))
                }
                ImageFilter.INVERT -> m.set(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ))
            }
            if (saturation != 0f) m.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
            if (contrast != 0f) m.postConcat(contrastMatrix(contrast))
            if (brightness != 0f) {
                val b = brightness * 110f
                m.postConcat(ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            return m
        }

        /** Contraste de -1 (cinza) a +1 (forte). */
        private fun contrastMatrix(c: Float): ColorMatrix {
            val k = 1f + c
            val o = 128f * (1f - k)
            return ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, o,
                0f, k, 0f, 0f, o,
                0f, 0f, k, 0f, o,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
    }

    // ---- Desenho -----------------------------------------------------------------------

    /** Contorno da máscara em coordenadas locais (quadro [frame], imagem inteira em [full]). */
    private fun maskPath(img: ImageElement, frame: RectF, full: RectF): Path? = when (img.mask) {
        ImageMask.RECT -> null
        ImageMask.ROUNDED -> Path().apply {
            val r = minOf(frame.width(), frame.height()) * 0.12f
            addRoundRect(frame, r, r, Path.Direction.CW)
        }
        ImageMask.ELLIPSE -> Path().apply { addOval(frame, Path.Direction.CW) }
        ImageMask.FREE -> {
            val m = img.freeMask
            if (m == null || m.size < 6) null else Path().apply {
                moveTo(full.left + m[0] * full.width(), full.top + m[1] * full.height())
                var i = 2
                while (i + 1 < m.size) {
                    lineTo(full.left + m[i] * full.width(), full.top + m[i + 1] * full.height())
                    i += 2
                }
                close()
            }
        }
    }

    /**
     * Desenha a imagem num canvas já no espaço do mundo. [scale] é o zoom atual
     * (para escolher a resolução). Com [showFull], desenha a imagem inteira
     * (modo de recorte), ignorando recorte e máscara.
     */
    fun draw(canvas: Canvas, img: ImageElement, scale: Float, sync: Boolean, showFull: Boolean = false, fullAlpha: Int = 255) {
        val w = img.rect.width()
        val h = img.rect.height()
        val frame = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val full = img.fullLocalFrame()
        canvas.save()
        canvas.translate(img.centerX, img.centerY)
        canvas.rotate(img.rotation)
        canvas.scale(if (img.flipH) -1f else 1f, if (img.flipV) -1f else 1f)

        val bmp = bitmap(img, w * scale, sync)
        val mask = if (showFull) null else maskPath(img, frame, full)

        if (img.shadow && !showFull) {
            shadowPaint.setShadowLayer(max(4f, minOf(w, h) * 0.04f), 0f, minOf(w, h) * 0.02f, Color.argb(90, 0, 0, 0))
            if (mask != null) canvas.drawPath(mask, shadowPaint) else canvas.drawRect(frame, shadowPaint)
            shadowPaint.clearShadowLayer()
        }

        paint.alpha = if (showFull) fullAlpha else img.opacity
        paint.colorFilter = colorFilter(img)
        if (bmp == null) {
            canvas.drawRect(if (showFull) full else frame, placeholder)
        } else if (showFull) {
            canvas.drawBitmap(bmp, null, full, paint)
        } else {
            canvas.save()
            if (mask != null) canvas.clipPath(mask) else canvas.clipRect(frame)
            // Desenha a imagem inteira no quadro "full": o recorte é feito pelo clip.
            canvas.drawBitmap(bmp, null, full, paint)
            canvas.restore()
        }
        paint.colorFilter = null
        paint.alpha = 255

        if (img.borderWidth > 0f && !showFull) {
            borderPaint.color = img.borderColor
            borderPaint.strokeWidth = img.borderWidth
            if (mask != null) canvas.drawPath(mask, borderPaint) else canvas.drawRect(frame, borderPaint)
        }
        canvas.restore()
    }

    /** A imagem processada (recorte, filtros, máscara) como bitmap, para salvar na galeria. */
    fun export(img: ImageElement): Bitmap? {
        val (sw, sh) = sourceSize(img.file) ?: return null
        val outW = max(1, (sw * img.crop.width()).toInt())
        val outH = max(1, (sh * img.crop.height()).toInt())
        val s = minOf(1f, 4096f / max(outW, outH))
        val bmp = Bitmap.createBitmap(max(1, (outW * s).toInt()), max(1, (outH * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = bmp.width / img.rect.width()
        c.scale(k, k)
        c.translate(img.rect.width() / 2f - img.centerX, img.rect.height() / 2f - img.centerY)
        val unrotated = img.with(rotation = 0f, borderWidth = 0f, shadow = false)
        draw(c, unrotated, k, sync = true)
        return bmp
    }

    fun close() {
        decoder.shutdownNow()
        cache.evictAll()
    }
}
