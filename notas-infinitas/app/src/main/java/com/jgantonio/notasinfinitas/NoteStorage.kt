package com.jgantonio.notasinfinitas

import android.graphics.Color
import android.graphics.RectF
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Posição da câmera sobre a tela infinita. */
data class ViewState(val offsetX: Float, val offsetY: Float, val scale: Float)

enum class PageStyle(val label: String) { BLANK("Em branco"), DOTS("Pontilhado"), LINES("Pautado"), GRID("Quadriculado") }

/**
 * Como a nota é organizada: tela infinita (padrão) ou folhas.
 *
 * Com folhas: [horizontal] põe as folhas lado a lado (senão, uma embaixo da outra),
 * [landscape] deita a folha, e [endless] cria uma folha nova sozinha sempre que se
 * escreve na última (senão a nota tem [count] folhas). [w]/[h] é o tamanho da folha
 * e [ox]/[oy] o canto da primeira, em coordenadas do mundo.
 */
data class PageLayout(
    val paged: Boolean = false,
    val horizontal: Boolean = false,
    val landscape: Boolean = false,
    val endless: Boolean = true,
    val count: Int = 1,
    val w: Float = 0f,
    val h: Float = 0f,
    val ox: Float = 0f,
    val oy: Float = 0f,
) {
    val hasPages get() = paged && w > 0f && h > 0f
    val gap get() = w.coerceAtMost(h) * 0.05f
    val stepX get() = if (horizontal) w + gap else 0f
    val stepY get() = if (horizontal) 0f else h + gap

    /** Folha [i] (a partir de 0) em coordenadas do mundo. */
    fun pageRect(i: Int, out: RectF = RectF()): RectF {
        val l = ox + i * stepX
        val t = oy + i * stepY
        return out.apply { set(l, t, l + w, t + h) }
    }

    /** Índice da folha onde fica a coordenada (pode passar do total). */
    fun indexAt(px: Float, py: Float): Int =
        if (horizontal) kotlin.math.floor((px - ox) / (w + gap)).toInt() else kotlin.math.floor((py - oy) / (h + gap)).toInt()

    /** Para lembrar o modo escolhido nas próximas notas (sem posição nem quantidade). */
    fun encodePref() = "${if (paged) 1 else 0},${if (horizontal) 1 else 0},${if (landscape) 1 else 0},${if (endless) 1 else 0}"

    companion object {
        fun decodePref(s: String?): PageLayout? {
            val p = s?.split(',') ?: return null
            if (p.size != 4) return null
            return PageLayout(paged = p[0] == "1", horizontal = p[1] == "1", landscape = p[2] == "1", endless = p[3] == "1")
        }
    }
}

class NoteData(
    val elements: List<Element>,
    val viewState: ViewState?,
    val style: PageStyle = PageStyle.DOTS,
    val paperColor: Int = Color.WHITE,
    val layout: PageLayout = PageLayout(),
)

/**
 * Formato binário da nota. Grava num arquivo temporário e renomeia, para não
 * corromper a nota se o app for fechado no meio da gravação.
 *
 * Versão 1: só traços (x, y, pressão) — formato do primeiro protótipo.
 * Versão 2: traços com pincel e espessura por ponto, textos, imagens e plano de fundo.
 * Versão 3: imagens com rotação, recorte, filtros, moldura, trava etc. (tipo 4).
 * Versão 4: textos com rotação, espelhamento, fonte, alinhamento e estilos por trecho (tipo 5).
 * Versão 5: modo de página (infinita ou folhas) logo depois da cor do papel.
 */
object NoteStorage {
    private const val MAGIC = 0x4E494E46 // "NINF"
    private const val VERSION = 5

    private const val TYPE_STROKE = 1
    private const val TYPE_TEXT = 2
    private const val TYPE_IMAGE = 3
    private const val TYPE_IMAGE_V3 = 4
    private const val TYPE_TEXT_V4 = 5

    fun save(file: File, data: NoteData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            val v = data.viewState ?: ViewState(0f, 0f, 1f)
            out.writeFloat(v.offsetX)
            out.writeFloat(v.offsetY)
            out.writeFloat(v.scale)
            out.writeInt(data.style.ordinal)
            out.writeInt(data.paperColor)
            val l = data.layout
            out.writeBoolean(l.paged); out.writeBoolean(l.horizontal); out.writeBoolean(l.landscape); out.writeBoolean(l.endless)
            out.writeInt(l.count)
            out.writeFloat(l.w); out.writeFloat(l.h); out.writeFloat(l.ox); out.writeFloat(l.oy)
            out.writeInt(data.elements.size)
            for (e in data.elements) when (e) {
                is StrokeElement -> {
                    out.writeByte(TYPE_STROKE)
                    out.writeInt(e.type.ordinal)
                    out.writeInt(e.color)
                    out.writeInt(e.alpha)
                    out.writeInt(e.n)
                    for (i in 0 until e.n) {
                        out.writeFloat(e.xs[i]); out.writeFloat(e.ys[i]); out.writeFloat(e.ws[i])
                    }
                }
                is TextElement -> {
                    out.writeByte(TYPE_TEXT_V4)
                    val bytes = e.text.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.writeFloat(e.x); out.writeFloat(e.y); out.writeFloat(e.size)
                    out.writeInt(e.color)
                    out.writeFloat(e.rotation)
                    out.writeBoolean(e.flipH); out.writeBoolean(e.flipV)
                    out.writeBoolean(e.bold); out.writeBoolean(e.italic)
                    out.writeBoolean(e.underline); out.writeBoolean(e.strike)
                    out.writeInt(e.align.ordinal); out.writeInt(e.font.ordinal)
                    out.writeInt(e.bgColor)
                    out.writeInt(e.spans.size)
                    for (sp in e.spans) {
                        out.writeInt(sp.start); out.writeInt(sp.end); out.writeInt(sp.type); out.writeInt(sp.value)
                    }
                }
                is ImageElement -> {
                    out.writeByte(TYPE_IMAGE_V3)
                    out.writeUTF(e.file)
                    out.writeFloat(e.rect.left); out.writeFloat(e.rect.top)
                    out.writeFloat(e.rect.right); out.writeFloat(e.rect.bottom)
                    out.writeFloat(e.rotation)
                    out.writeBoolean(e.flipH); out.writeBoolean(e.flipV)
                    out.writeFloat(e.crop.left); out.writeFloat(e.crop.top)
                    out.writeFloat(e.crop.right); out.writeFloat(e.crop.bottom)
                    out.writeInt(e.mask.ordinal)
                    val fm = e.freeMask
                    out.writeInt(fm?.size ?: 0)
                    fm?.forEach { out.writeFloat(it) }
                    out.writeInt(e.opacity)
                    out.writeInt(e.filter.ordinal)
                    out.writeFloat(e.brightness); out.writeFloat(e.contrast); out.writeFloat(e.saturation)
                    out.writeFloat(e.borderWidth); out.writeInt(e.borderColor)
                    out.writeBoolean(e.shadow); out.writeBoolean(e.locked)
                    out.writeInt(e.pdfPage)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(file: File): NoteData? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                when (input.readInt()) {
                    1 -> readV1(input)
                    2, 3, 4 -> readV2(input, false)
                    5 -> readV2(input, true)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readV1(input: DataInputStream): NoteData {
        val state = ViewState(input.readFloat(), input.readFloat(), input.readFloat())
        val count = input.readInt()
        val elements = ArrayList<Element>(count)
        repeat(count) {
            val color = input.readInt()
            val base = input.readFloat()
            val n = input.readInt()
            val xs = FloatArray(n); val ys = FloatArray(n); val ws = FloatArray(n)
            for (i in 0 until n) {
                xs[i] = input.readFloat(); ys[i] = input.readFloat()
                ws[i] = base * (0.35f + 0.65f * input.readFloat())
            }
            if (n > 0) elements.add(StrokeElement(BrushType.PEN, color, 255, xs, ys, ws))
        }
        return NoteData(elements, state)
    }

    private fun readV2(input: DataInputStream, hasLayout: Boolean): NoteData {
        val state = ViewState(input.readFloat(), input.readFloat(), input.readFloat())
        val style = PageStyle.entries.getOrElse(input.readInt()) { PageStyle.DOTS }
        val paper = input.readInt()
        val layout = if (hasLayout) PageLayout(
            paged = input.readBoolean(), horizontal = input.readBoolean(),
            landscape = input.readBoolean(), endless = input.readBoolean(),
            count = input.readInt().coerceIn(1, 9999),
            w = input.readFloat(), h = input.readFloat(), ox = input.readFloat(), oy = input.readFloat(),
        ) else PageLayout()
        val count = input.readInt()
        val elements = ArrayList<Element>(count)
        repeat(count) {
            when (input.readByte().toInt()) {
                TYPE_STROKE -> {
                    val type = BrushType.entries.getOrElse(input.readInt()) { BrushType.PEN }
                    val color = input.readInt()
                    val alpha = input.readInt()
                    val n = input.readInt()
                    val xs = FloatArray(n); val ys = FloatArray(n); val ws = FloatArray(n)
                    for (i in 0 until n) {
                        xs[i] = input.readFloat(); ys[i] = input.readFloat(); ws[i] = input.readFloat()
                    }
                    if (n > 0) elements.add(StrokeElement(type, color, alpha, xs, ys, ws))
                }
                TYPE_TEXT -> elements.add(TextElement(
                    ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8),
                    input.readFloat(), input.readFloat(), input.readFloat(), input.readInt(),
                ))
                TYPE_IMAGE -> elements.add(ImageElement(
                    input.readUTF(),
                    RectF(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()),
                ))
                TYPE_TEXT_V4 -> {
                    val text = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                    val x = input.readFloat(); val y = input.readFloat(); val size = input.readFloat()
                    val color = input.readInt()
                    val rotation = input.readFloat()
                    val flipH = input.readBoolean(); val flipV = input.readBoolean()
                    val bold = input.readBoolean(); val italic = input.readBoolean()
                    val underline = input.readBoolean(); val strike = input.readBoolean()
                    val align = TextAlign.entries.getOrElse(input.readInt()) { TextAlign.LEFT }
                    val font = TextFont.entries.getOrElse(input.readInt()) { TextFont.SANS }
                    val bg = input.readInt()
                    val spans = List(input.readInt()) { TextSpan(input.readInt(), input.readInt(), input.readInt(), input.readInt()) }
                    elements.add(TextElement(text, x, y, size, color, rotation, flipH, flipV, bold, italic, underline, strike, align, font, bg, spans))
                }
                TYPE_IMAGE_V3 -> {
                    val file = input.readUTF()
                    val rect = RectF(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                    val rotation = input.readFloat()
                    val flipH = input.readBoolean()
                    val flipV = input.readBoolean()
                    val crop = RectF(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                    val mask = ImageMask.entries.getOrElse(input.readInt()) { ImageMask.RECT }
                    val fmSize = input.readInt()
                    val fm = if (fmSize > 0) FloatArray(fmSize) { input.readFloat() } else null
                    elements.add(ImageElement(
                        file, rect, rotation, flipH, flipV, crop, mask, fm,
                        opacity = input.readInt(),
                        filter = ImageFilter.entries.getOrElse(input.readInt()) { ImageFilter.NONE },
                        brightness = input.readFloat(), contrast = input.readFloat(), saturation = input.readFloat(),
                        borderWidth = input.readFloat(), borderColor = input.readInt(),
                        shadow = input.readBoolean(), locked = input.readBoolean(),
                        pdfPage = input.readInt(),
                    ))
                }
                else -> throw IllegalStateException("tipo de elemento desconhecido")
            }
        }
        return NoteData(elements, state, style, paper, layout)
    }
}
