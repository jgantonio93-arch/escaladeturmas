package com.conversor.pptxpdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.ColorUtils
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Conversor PPTX -> PDF 100% offline.
 *
 * Lê o pacote OOXML (zip) da apresentação e redesenha cada slide num
 * android.graphics.pdf.PdfDocument: fundos, formas, imagens, textos
 * (com herança de layout/mestre/tema), tabelas, SmartArt e gráficos básicos.
 * Unidades: 1 ponto PDF = 12700 EMU.
 */

private const val EMU_PER_PT = 12700f
private const val K = 4f        // supersampling do texto para precisão sub-ponto
private const val BASE = 10f    // tamanho base do TextPaint (escalado por RelativeSizeSpan)
private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

private fun emu(v: String?): Float = ((v?.toDoubleOrNull() ?: 0.0) / EMU_PER_PT).toFloat()

// ---------- utilidades de XML (independentes de prefixo de namespace) ----------

private val Element.ln: String get() = localName ?: nodeName.substringAfter(':')

private fun Element.kids(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element) out.add(n)
        n = n.nextSibling
    }
    return out
}

private fun Element.child(name: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n is Element && n.ln == name) return n
        n = n.nextSibling
    }
    return null
}

private fun Element.children(name: String): List<Element> = kids().filter { it.ln == name }

private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

/** Atributo com prefixo (ex.: r:embed, r:id). */
private fun Element.rAttr(name: String): String? {
    val a = attributes
    for (i in 0 until a.length) {
        val at = a.item(i)
        val nm = at.nodeName ?: continue
        if (nm.endsWith(":$name") && !nm.startsWith("xmlns")) return at.nodeValue
    }
    return null
}

private fun findDesc(el: Element, name: String): Element? {
    for (k in el.kids()) {
        if (k.ln == name) return k
        val r = findDesc(k, name)
        if (r != null) return r
    }
    return null
}

private fun allText(el: Element, tag: String, sb: StringBuilder = StringBuilder()): String {
    for (k in el.kids()) {
        if (k.ln == tag) sb.append(k.textContent ?: "") else allText(k, tag, sb)
    }
    return sb.toString()
}

private fun isTrue(v: String?) = v == "1" || v == "true" || v == "on"

// ---------- modelos ----------

internal sealed class Fill
internal class SolidFill(val color: Int) : Fill()
internal class GradFill(val stops: List<Pair<Float, Int>>, val angle: Float, val radial: Boolean) : Fill()
internal class ImageFill(val path: String, val src: RectF?, val alpha: Int) : Fill()
internal object NoFill : Fill()
internal object GroupFill : Fill()

internal class LineSpec(
    val color: Int, val width: Float, val dash: String?,
    val head: String?, val tail: String?, val cap: String?
)

internal class Theme(
    val colors: Map<String, Int>, val fills: List<Element>, val bgFills: List<Element>,
    val lines: List<Element>, val major: String, val minor: String, val path: String?
)

internal class Ctx(val theme: Theme, val clrMap: Map<String, String>)

internal class Env(
    val ctx: Ctx, val layoutTree: Element?, val masterTree: Element?,
    val titleStyle: Element?, val bodyStyle: Element?, val otherStyle: Element?,
    val presDefault: Element?, val slideNum: Int
)

internal class TextBox(val layout: StaticLayout, val width: Float, val height: Float)

internal class TPart(val fill: Fill?, val color: Int?, val bold: Boolean?)
internal class TStyle(val parts: Map<String, TPart>, val borders: Map<String, LineSpec?>)

internal class ChartSeries(val name: String, val vals: List<Double?>, val cats: List<String>, val color: Int, val el: Element)

internal class Xf(e: Element) {
    val x: Float
    val y: Float
    val w: Float
    val h: Float
    val rot: Float = (e.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
    val flipH: Boolean = isTrue(e.attr("flipH"))
    val flipV: Boolean = isTrue(e.attr("flipV"))
    val chX: Float
    val chY: Float
    val chW: Float
    val chH: Float

    init {
        val off = e.child("off")
        val ext = e.child("ext")
        x = emu(off?.attr("x")); y = emu(off?.attr("y"))
        w = emu(ext?.attr("cx")); h = emu(ext?.attr("cy"))
        val co = e.child("chOff")
        val ce = e.child("chExt")
        chX = emu(co?.attr("x")); chY = emu(co?.attr("y"))
        chW = emu(ce?.attr("cx")); chH = emu(ce?.attr("cy"))
    }
}

private val DEFAULT_THEME: Map<String, Int> = mapOf(
    "dk1" to Color.BLACK, "lt1" to Color.WHITE,
    "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
    "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(),
    "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(),
    "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(),
    "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt()
)
private val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
private val PRESET = mapOf(
    "black" to Color.BLACK, "white" to Color.WHITE, "red" to Color.RED,
    "green" to 0xFF008000.toInt(), "blue" to Color.BLUE, "yellow" to Color.YELLOW,
    "gray" to Color.GRAY, "grey" to Color.GRAY, "orange" to 0xFFFFA500.toInt(),
    "darkGray" to Color.DKGRAY, "lightGray" to Color.LTGRAY, "cyan" to Color.CYAN, "magenta" to Color.MAGENTA
)
private val LINE_PRESETS = setOf(
    "line", "straightConnector1", "bentConnector2", "bentConnector3", "bentConnector4",
    "bentConnector5", "curvedConnector2", "curvedConnector3", "curvedConnector4", "curvedConnector5"
)
private val CHART_TYPES = setOf(
    "barChart", "bar3DChart", "lineChart", "line3DChart", "pieChart", "pie3DChart",
    "doughnutChart", "ofPieChart", "areaChart", "area3DChart", "scatterChart", "radarChart"
)
private val CLR_KEYS = listOf(
    "bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3",
    "accent4", "accent5", "accent6", "hlink", "folHlink"
)

class PptxToPdfConverter(file: File) {

    private val zip = ZipFile(file)
    private val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    private val xmlCache = HashMap<String, Element?>()
    private val relCache = HashMap<String, List<Rel>>()
    private val themeCache = HashMap<String, Theme>()
    private var presPath = "ppt/presentation.xml"
    private var presDefault: Element? = null
    private val images = Images()

    private class Rel(val id: String, val type: String, val target: String)

    // ======================================================================
    // API pública
    // ======================================================================

    fun convert(out: File, progress: (Int, Int) -> Unit) {
        try {
            presPath = rels("").firstOrNull { it.type.endsWith("/officeDocument") }?.target
                ?: "ppt/presentation.xml"
            val pres = xml(presPath) ?: throw IllegalStateException("presentation.xml não encontrado")
            val sz = pres.child("sldSz")
            val sw = emu(sz?.attr("cx") ?: "12192000")
            val sh = emu(sz?.attr("cy") ?: "6858000")
            presDefault = pres.child("defaultTextStyle")

            val all = (pres.child("sldIdLst")?.children("sldId") ?: emptyList())
                .mapNotNull { it.rAttr("id")?.let { id -> relTarget(presPath, id) } }
            val visible = all.withIndex().filter { xml(it.value)?.attr("show") != "0" }
            if (visible.isEmpty()) throw IllegalStateException("Nenhum slide encontrado na apresentação")

            val doc = PdfDocument()
            try {
                for ((n, iv) in visible.withIndex()) {
                    val info = PdfDocument.PageInfo.Builder(
                        sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1), n + 1
                    ).create()
                    val page = doc.startPage(info)
                    try {
                        renderSlide(page.canvas, iv.value, iv.index + 1, sw, sh)
                    } catch (t: Throwable) {
                        // um slide problemático não interrompe a conversão
                    }
                    doc.finishPage(page)
                    xmlCache.remove(iv.value)
                    progress(n + 1, visible.size)
                }
                out.outputStream().use { doc.writeTo(it) }
            } finally {
                doc.close()
            }
        } finally {
            zip.close()
        }
    }

    // ======================================================================
    // Pacote / XML / relacionamentos
    // ======================================================================

    private fun xml(path: String): Element? {
        if (xmlCache.containsKey(path)) return xmlCache[path]
        val entry = zip.getEntry(path)
        val el = if (entry == null) null else try {
            zip.getInputStream(entry).use { dbf.newDocumentBuilder().parse(it).documentElement }
        } catch (e: Exception) {
            null
        }
        xmlCache[path] = el
        return el
    }

    private fun bytes(path: String): ByteArray? {
        val e = zip.getEntry(path) ?: return null
        return zip.getInputStream(e).use { it.readBytes() }
    }

    private fun rels(part: String): List<Rel> = relCache.getOrPut(part) {
        val dir = part.substringBeforeLast('/', "")
        val name = part.substringAfterLast('/')
        val relPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        val root = xml(relPath) ?: return@getOrPut emptyList()
        root.children("Relationship").mapNotNull { r ->
            if (r.attr("TargetMode").equals("External", true)) null
            else Rel(r.attr("Id") ?: "", r.attr("Type") ?: "", resolve(dir, r.attr("Target") ?: ""))
        }
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) }
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun relTarget(part: String, id: String): String? = rels(part).firstOrNull { it.id == id }?.target
    private fun relByType(part: String, suffix: String): String? =
        rels(part).firstOrNull { it.type.endsWith(suffix) }?.target

    // ======================================================================
    // Tema e cores
    // ======================================================================

    private fun loadTheme(path: String?): Theme {
        val key = path ?: ""
        themeCache[key]?.let { return it }
        val te = path?.let { xml(it) }?.child("themeElements")
        val colors = HashMap<String, Int>(DEFAULT_THEME)
        te?.child("clrScheme")?.kids()?.forEach { k ->
            val c = k.child("srgbClr")?.attr("val")?.let { parseHex(it) }
                ?: k.child("sysClr")?.attr("lastClr")?.let { parseHex(it) }
            if (c != null) colors[k.ln] = c
        }
        val fmt = te?.child("fmtScheme")
        val fonts = te?.child("fontScheme")
        val t = Theme(
            colors,
            fmt?.child("fillStyleLst")?.kids() ?: emptyList(),
            fmt?.child("bgFillStyleLst")?.kids() ?: emptyList(),
            fmt?.child("lnStyleLst")?.children("ln") ?: emptyList(),
            fonts?.child("majorFont")?.child("latin")?.attr("typeface") ?: "Calibri Light",
            fonts?.child("minorFont")?.child("latin")?.attr("typeface") ?: "Calibri",
            path
        )
        themeCache[key] = t
        return t
    }

    private fun parseHex(s: String?): Int? {
        if (s == null || s.length < 6) return null
        return try {
            0xFF000000.toInt() or s.substring(0, 6).toInt(16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun pctByte(v: String?): Int = (((v?.toFloatOrNull() ?: 0f) / 100000f) * 255f).roundToInt().coerceIn(0, 255)

    private fun schemeColor(name: String, ctx: Ctx, ph: Int?): Int? {
        val n = when (name) {
            "phClr" -> return ph ?: Color.BLACK
            "bg1", "tx1", "bg2", "tx2" -> ctx.clrMap[name] ?: DEFAULT_MAP.getValue(name)
            else -> name
        }
        return ctx.theme.colors[n] ?: DEFAULT_THEME[n]
    }

    /** Cor do primeiro elemento de cor dentro de [container] (solidFill, gs, fontRef...). */
    private fun colorOf(container: Element?, ctx: Ctx, ph: Int? = null): Int? {
        if (container == null) return null
        for (k in container.kids()) {
            val base: Int? = when (k.ln) {
                "srgbClr" -> parseHex(k.attr("val"))
                "schemeClr" -> schemeColor(k.attr("val") ?: "", ctx, ph)
                "sysClr" -> parseHex(k.attr("lastClr")) ?: if (k.attr("val") == "window") Color.WHITE else Color.BLACK
                "prstClr" -> PRESET[k.attr("val") ?: ""] ?: Color.BLACK
                "scrgbClr" -> Color.rgb(pctByte(k.attr("r")), pctByte(k.attr("g")), pctByte(k.attr("b")))
                else -> null
            }
            if (base != null) return applyMods(base, k)
        }
        return null
    }

    private fun applyMods(base: Int, el: Element): Int {
        var r = Color.red(base).toFloat()
        var g = Color.green(base).toFloat()
        var b = Color.blue(base).toFloat()
        var a = Color.alpha(base) / 255f
        for (m in el.kids()) {
            val v = (m.attr("val")?.toFloatOrNull() ?: continue) / 100000f
            when (m.ln) {
                "alpha" -> a = v
                "alphaMod" -> a *= v
                "tint" -> { r = r * v + 255f * (1 - v); g = g * v + 255f * (1 - v); b = b * v + 255f * (1 - v) }
                "shade" -> { r *= v; g *= v; b *= v }
                "lumMod", "lumOff", "satMod" -> {
                    val hsl = FloatArray(3)
                    ColorUtils.RGBToHSL(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255), hsl)
                    when (m.ln) {
                        "lumMod" -> hsl[2] *= v
                        "lumOff" -> hsl[2] += v
                        else -> hsl[1] *= v
                    }
                    hsl[1] = hsl[1].coerceIn(0f, 1f)
                    hsl[2] = hsl[2].coerceIn(0f, 1f)
                    val c = ColorUtils.HSLToColor(hsl)
                    r = Color.red(c).toFloat(); g = Color.green(c).toFloat(); b = Color.blue(c).toFloat()
                }
            }
        }
        return Color.argb(
            (a * 255f).roundToInt().coerceIn(0, 255), r.roundToInt().coerceIn(0, 255),
            g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255)
        )
    }

    private fun tint(c: Int, v: Float): Int = Color.rgb(
        (Color.red(c) * v + 255 * (1 - v)).roundToInt(),
        (Color.green(c) * v + 255 * (1 - v)).roundToInt(),
        (Color.blue(c) * v + 255 * (1 - v)).roundToInt()
    )

    private fun accentColor(i: Int, ctx: Ctx): Int {
        val c = schemeColor("accent${i % 6 + 1}", ctx, null) ?: Color.GRAY
        return if (i >= 6) Color.rgb((Color.red(c) * 0.65f).toInt(), (Color.green(c) * 0.65f).toInt(), (Color.blue(c) * 0.65f).toInt()) else c
    }

    // ======================================================================
    // Preenchimentos e linhas
    // ======================================================================

    private fun fillElement(f: Element, part: String, ctx: Ctx, ph: Int?): Fill? = when (f.ln) {
        "noFill" -> NoFill
        "solidFill" -> colorOf(f, ctx, ph)?.let { SolidFill(it) } ?: NoFill
        "gradFill" -> {
            val stops = (f.child("gsLst")?.children("gs") ?: emptyList()).mapNotNull { gs ->
                colorOf(gs, ctx, ph)?.let { ((gs.attr("pos")?.toFloatOrNull() ?: 0f) / 100000f).coerceIn(0f, 1f) to it }
            }.sortedBy { it.first }
            if (stops.isEmpty()) NoFill
            else GradFill(
                stops,
                f.child("lin")?.attr("ang")?.toFloatOrNull()?.div(60000f) ?: 90f,
                f.child("path") != null && f.child("lin") == null
            )
        }
        "blipFill" -> {
            val blip = f.child("blip")
            val t = blip?.rAttr("embed")?.let { relTarget(part, it) }
            if (t == null) NoFill else ImageFill(t, srcRect(f), alphaOf(blip))
        }
        "pattFill" -> colorOf(f.child("fgClr"), ctx, ph)?.let { SolidFill(it) } ?: NoFill
        "grpFill" -> GroupFill
        else -> null
    }

    private fun parseFill(container: Element?, part: String, ctx: Ctx, ph: Int?): Fill? {
        if (container == null) return null
        for (k in container.kids()) {
            val f = fillElement(k, part, ctx, ph)
            if (f != null) return f
        }
        return null
    }

    /** fillRef / bgRef: índice na lista de estilos do tema, com phClr = cor da referência. */
    private fun refFill(ref: Element, ctx: Ctx): Fill? {
        val idx = ref.attr("idx")?.toIntOrNull() ?: 0
        val col = colorOf(ref, ctx)
        if (idx == 0) return NoFill
        val el = if (idx >= 1000) ctx.theme.bgFills.getOrNull(idx - 1001) else ctx.theme.fills.getOrNull(idx - 1)
        val f = el?.let { fillElement(it, ctx.theme.path ?: "", ctx, col) }
        return f ?: col?.let { SolidFill(it) }
    }

    private fun srcRect(blipFill: Element): RectF? {
        val s = blipFill.child("srcRect") ?: return null
        fun f(n: String) = ((s.attr(n)?.toFloatOrNull() ?: 0f) / 100000f).coerceIn(0f, 0.99f)
        return RectF(f("l"), f("t"), f("r"), f("b"))
    }

    private fun alphaOf(blip: Element?): Int =
        blip?.child("alphaModFix")?.attr("amt")?.toFloatOrNull()?.let { (it / 100000f * 255f).roundToInt().coerceIn(0, 255) } ?: 255

    private fun resolveLine(ln: Element?, style: Element?, ctx: Ctx): LineSpec? {
        val lnRef = style?.child("lnRef")
        val refIdx = lnRef?.attr("idx")?.toIntOrNull() ?: 0
        val refLn = if (refIdx > 0) ctx.theme.lines.getOrNull(refIdx - 1) else null
        val refColor = lnRef?.let { colorOf(it, ctx) }
        if (ln?.child("noFill") != null) return null
        var color: Int? = ln?.child("solidFill")?.let { colorOf(it, ctx) }
            ?: ln?.child("gradFill")?.child("gsLst")?.child("gs")?.let { colorOf(it, ctx) }
        if (color == null) {
            if (refIdx <= 0) return null
            color = refLn?.child("solidFill")?.let { colorOf(it, ctx, refColor) } ?: refColor
        }
        val col = color ?: return null
        val w = ln?.attr("w")?.let { emu(it) } ?: refLn?.attr("w")?.let { emu(it) } ?: 0.75f
        val dash = ln?.child("prstDash")?.attr("val") ?: refLn?.child("prstDash")?.attr("val")
        return LineSpec(col, max(w, 0.25f), dash, ln?.child("headEnd")?.attr("type"), ln?.child("tailEnd")?.attr("type"), ln?.attr("cap"))
    }

    private fun strokePaint(l: LineSpec): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.color = l.color
        p.strokeWidth = l.width
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = when (l.cap) {
            "rnd" -> Paint.Cap.ROUND
            "sq" -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        val w = l.width
        p.pathEffect = when (l.dash) {
            "dash", "lgDash", "sysDash" -> DashPathEffect(floatArrayOf(w * 4, w * 3), 0f)
            "dot", "sysDot" -> DashPathEffect(floatArrayOf(w, w * 2), 0f)
            "dashDot", "lgDashDot", "sysDashDot", "lgDashDotDot", "sysDashDotDot" ->
                DashPathEffect(floatArrayOf(w * 4, w * 2, w, w * 2), 0f)
            else -> null
        }
        return p
    }

    private fun rectPath(r: RectF): Path = Path().apply { addRect(r, Path.Direction.CW) }

    private fun paintFill(c: Canvas, path: Path, bounds: RectF, fill: Fill) {
        when (fill) {
            is SolidFill -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = fill.color
                c.drawPath(path, p)
            }
            is GradFill -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                if (fill.stops.size == 1 || bounds.width() <= 0f || bounds.height() <= 0f) {
                    p.color = fill.stops[0].second
                } else {
                    val colors = IntArray(fill.stops.size) { fill.stops[it].second }
                    val pos = FloatArray(fill.stops.size) { fill.stops[it].first }
                    p.shader = if (fill.radial) {
                        RadialGradient(bounds.centerX(), bounds.centerY(), max(1f, hypot(bounds.width(), bounds.height()) / 2f), colors, pos, Shader.TileMode.CLAMP)
                    } else {
                        val a = Math.toRadians(fill.angle.toDouble())
                        val cs = cos(a).toFloat()
                        val sn = sin(a).toFloat()
                        val half = max(0.5f, (abs(bounds.width() * cs) + abs(bounds.height() * sn)) / 2f)
                        val cx = bounds.centerX()
                        val cy = bounds.centerY()
                        LinearGradient(cx - cs * half, cy - sn * half, cx + cs * half, cy + sn * half, colors, pos, Shader.TileMode.CLAMP)
                    }
                }
                c.drawPath(path, p)
            }
            is ImageFill -> {
                val bmp = images.get(fill.path, bounds.width(), bounds.height()) ?: return
                val src = fill.src?.let { s ->
                    Rect(
                        (s.left * bmp.width).toInt(), (s.top * bmp.height).toInt(),
                        (bmp.width - s.right * bmp.width).toInt(), (bmp.height - s.bottom * bmp.height).toInt()
                    ).takeIf { it.width() > 0 && it.height() > 0 }
                }
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                p.alpha = fill.alpha
                c.save()
                c.clipPath(path)
                c.drawBitmap(bmp, src, bounds, p)
                c.restore()
            }
            else -> {}
        }
    }

    // ======================================================================
    // Imagens
    // ======================================================================

    private inner class Images {
        private val cache = HashMap<String, Bitmap?>()
        private var totalBytes = 0L
        private val unsupported = setOf("emf", "wmf", "svg", "tif", "tiff", "wdp", "jxr", "pict")

        fun get(path: String, wPt: Float, hPt: Float): Bitmap? {
            if (cache.containsKey(path)) return cache[path]
            val ext = path.substringAfterLast('.').lowercase()
            if (ext in unsupported) { cache[path] = null; return null }
            val bmp = try {
                val data = bytes(path)
                if (data == null) null else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, o)
                    if (o.outWidth <= 0 || o.outHeight <= 0) null else {
                        val density = if (totalBytes > 250L * 1024 * 1024) 1.0f else 2.2f
                        val target = (max(wPt, hPt) * density).coerceIn(64f, 2200f)
                        var sample = 1
                        while (max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    }
                }
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
            if (bmp != null) totalBytes += bmp.byteCount.toLong()
            cache[path] = bmp
            return bmp
        }
    }

    // ======================================================================
    // Slide
    // ======================================================================

    private fun renderSlide(c: Canvas, slidePath: String, num: Int, sw: Float, sh: Float) {
        val slide = xml(slidePath) ?: return
        val layoutPath = relByType(slidePath, "/slideLayout")
        val layout = layoutPath?.let { xml(it) }
        val masterPath = layoutPath?.let { relByType(it, "/slideMaster") }
        val master = masterPath?.let { xml(it) }
        val theme = loadTheme(masterPath?.let { relByType(it, "/theme") })

        val clrMap = HashMap<String, String>()
        master?.child("clrMap")?.let { cm -> for (k in CLR_KEYS) cm.attr(k)?.let { clrMap[k] = it } }
        for (ov in listOf(layout, slide)) {
            ov?.child("clrMapOvr")?.child("overrideClrMapping")?.let { cm ->
                for (k in CLR_KEYS) cm.attr(k)?.let { clrMap[k] = it }
            }
        }
        val ctx = Ctx(theme, clrMap)
        val tx = master?.child("txStyles")
        val env = Env(
            ctx, layout?.child("cSld")?.child("spTree"), master?.child("cSld")?.child("spTree"),
            tx?.child("titleStyle"), tx?.child("bodyStyle"), tx?.child("otherStyle"), presDefault, num
        )

        c.drawColor(Color.WHITE)
        val full = RectF(0f, 0f, sw, sh)
        val sources = listOf(slide to slidePath, layout to layoutPath, master to masterPath)
        for ((el, p) in sources) {
            val bg = el?.child("cSld")?.child("bg") ?: continue
            val f = bg.child("bgPr")?.let { parseFill(it, p ?: "", ctx, null) }
                ?: bg.child("bgRef")?.let { refFill(it, ctx) }
            if (f != null && f !is NoFill && f !is GroupFill) paintFill(c, rectPath(full), full, f)
            break
        }

        val slideShow = slide.attr("showMasterSp") != "0"
        val layoutShow = layout?.attr("showMasterSp") != "0"
        if (slideShow) {
            if (layoutShow && masterPath != null) env.masterTree?.let { drawTree(c, it, masterPath, env, false, null) }
            if (layoutPath != null) env.layoutTree?.let { drawTree(c, it, layoutPath, env, false, null) }
        }
        slide.child("cSld")?.child("spTree")?.let { drawTree(c, it, slidePath, env, true, null) }
    }

    private fun drawTree(c: Canvas, tree: Element, part: String, env: Env, isSlide: Boolean, grpFill: Fill?) {
        for (el in tree.kids()) drawItem(c, el, part, env, isSlide, grpFill)
    }

    private fun drawItem(c: Canvas, el: Element, part: String, env: Env, isSlide: Boolean, grpFill: Fill?) {
        val count = c.save()
        try {
            when (el.ln) {
                "sp", "cxnSp" -> if (isSlide || phOf(el) == null) drawSp(c, el, part, env, isSlide, grpFill)
                "pic" -> if (isSlide || phOf(el) == null) drawPic(c, el, part, env, isSlide, null)
                "grpSp" -> drawGroup(c, el, part, env, isSlide, grpFill)
                "graphicFrame" -> if (isSlide || phOf(el) == null) drawFrame(c, el, part, env, isSlide)
                "AlternateContent" -> {
                    val alt = el.child("Fallback") ?: el.child("Choice")
                    alt?.kids()?.forEach { drawItem(c, it, part, env, isSlide, grpFill) }
                }
            }
        } catch (e: Exception) {
            // forma corrompida/não suportada: ignora
        } finally {
            c.restoreToCount(count)
        }
    }

    // ---------- placeholders ----------

    private fun phOf(el: Element): Element? =
        el.kids().firstOrNull { it.ln.startsWith("nv") }?.child("nvPr")?.child("ph")

    private fun normType(t: String?): String = when (t) {
        "title", "ctrTitle" -> "title"
        "dt" -> "dt"
        "ftr" -> "ftr"
        "sldNum" -> "sldNum"
        "hdr" -> "hdr"
        else -> "body"
    }

    private fun phShapes(tree: Element, out: MutableList<Pair<Element, Element>> = ArrayList()): List<Pair<Element, Element>> {
        for (k in tree.kids()) {
            when (k.ln) {
                "sp", "pic", "graphicFrame" -> phOf(k)?.let { out.add(k to it) }
                "grpSp" -> phShapes(k, out)
            }
        }
        return out
    }

    private fun findPh(tree: Element, ph: Element, master: Boolean): Element? {
        val cands = phShapes(tree)
        val idx = ph.attr("idx")
        val type = ph.attr("type") ?: "obj"
        if (!master && idx != null) cands.firstOrNull { it.second.attr("idx") == idx }?.let { return it.first }
        cands.firstOrNull { (it.second.attr("type") ?: "obj") == type }?.let { return it.first }
        val t = normType(type)
        return cands.firstOrNull { normType(it.second.attr("type") ?: "obj") == t }?.first
    }

    // ---------- formas ----------

    private fun drawSp(c: Canvas, sp: Element, part: String, env: Env, isSlide: Boolean, grpFill: Fill?) {
        val ctx = env.ctx
        val ph = phOf(sp)
        var layoutSp: Element? = null
        var masterSp: Element? = null
        var phType: String? = null
        if (ph != null && isSlide) {
            layoutSp = env.layoutTree?.let { findPh(it, ph, false) }
            val lph = layoutSp?.let { phOf(it) } ?: ph
            masterSp = env.masterTree?.let { findPh(it, lph, true) }
            phType = ph.attr("type") ?: layoutSp?.let { phOf(it)?.attr("type") } ?: "body"
        }
        val spPr = sp.child("spPr")
        val sps = listOfNotNull(spPr, layoutSp?.child("spPr"), masterSp?.child("spPr"))
        val xfrm = sps.firstNotNullOfOrNull { it.child("xfrm") } ?: return
        val b = Xf(xfrm)
        val style = sp.child("style")

        var fill: Fill? = null
        for (s in sps) {
            fill = parseFill(s, part, ctx, null)
            if (fill != null) break
        }
        if (fill === GroupFill) fill = grpFill
        if (fill == null) fill = style?.child("fillRef")?.let { refFill(it, ctx) }
        val line = resolveLine(sps.firstNotNullOfOrNull { it.child("ln") }, style, ctx)
        val geom = sps.firstNotNullOfOrNull { it.child("prstGeom") ?: it.child("custGeom") }

        c.save()
        c.translate(b.x, b.y)
        if (b.rot != 0f) c.rotate(b.rot, b.w / 2f, b.h / 2f)
        c.save()
        if (b.flipH || b.flipV) c.scale(if (b.flipH) -1f else 1f, if (b.flipV) -1f else 1f, b.w / 2f, b.h / 2f)
        val prst = if (geom == null) "rect" else if (geom.ln == "prstGeom") (geom.attr("prst") ?: "rect") else null
        if (prst != null && prst in LINE_PRESETS) {
            if (line != null) drawConnector(c, prst, adjOf(geom), b.w, b.h, line)
        } else {
            val fp: Path
            val stp: Path
            if (prst == null && geom != null) {
                val pair = custPaths(geom, b.w, b.h)
                fp = pair.first; stp = pair.second
            } else {
                fp = presetPath(prst ?: "rect", adjOf(geom), b.w, b.h); stp = fp
            }
            val f = fill
            if (f != null && f !is NoFill && f !is GroupFill) paintFill(c, fp, RectF(0f, 0f, b.w, b.h), f)
            if (line != null) c.drawPath(stp, strokePaint(line))
        }
        c.restore()

        val tx = sp.child("txBody")
        if (tx != null) {
            val styles = ArrayList<Element>()
            tx.child("lstStyle")?.let { styles.add(it) }
            if (ph != null && isSlide) {
                layoutSp?.child("txBody")?.child("lstStyle")?.let { styles.add(it) }
                masterSp?.child("txBody")?.child("lstStyle")?.let { styles.add(it) }
                when (normType(phType)) {
                    "title" -> env.titleStyle
                    "body" -> env.bodyStyle
                    else -> env.otherStyle
                }?.let { styles.add(it) }
                env.presDefault?.let { styles.add(it) }
            } else {
                env.presDefault?.let { styles.add(it) }
                env.otherStyle?.let { styles.add(it) }
            }
            val bodyPrs = listOfNotNull(
                tx.child("bodyPr"),
                layoutSp?.child("txBody")?.child("bodyPr"),
                masterSp?.child("txBody")?.child("bodyPr")
            )
            val txX = sp.child("txXfrm")
            val rect = if (txX != null) {
                val t = Xf(txX)
                RectF(t.x - b.x, t.y - b.y, t.x - b.x + t.w, t.y - b.y + t.h)
            } else RectF(0f, 0f, b.w, b.h)
            val defColor = style?.child("fontRef")?.let { colorOf(it, ctx) } ?: schemeColor("tx1", ctx, null) ?: Color.BLACK
            val family = if (normType(phType) == "title" && ph != null) ctx.theme.major else ctx.theme.minor
            drawShapeText(c, tx, rect, styles, bodyPrs, env, defColor, family)
        }
        c.restore()
    }

    private fun adjOf(geom: Element?): Map<String, Float> {
        val m = HashMap<String, Float>()
        geom?.child("avLst")?.children("gd")?.forEach { gd ->
            val name = gd.attr("name") ?: return@forEach
            val v = gd.attr("fmla")?.removePrefix("val")?.trim()?.toFloatOrNull() ?: return@forEach
            m[name] = v / 100000f
        }
        return m
    }

    private fun poly(p: Path, vararg pts: Float) {
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            p.lineTo(pts[i], pts[i + 1]); i += 2
        }
        p.close()
    }

    private fun arrow(p: Path, w: Float, h: Float, a1: Float, a2: Float) {
        val shaft = h * a1
        val y1 = (h - shaft) / 2f
        val x = w - min(w, h) * a2
        poly(p, 0f, y1, x, y1, x, 0f, w, h / 2f, x, h, x, h - y1, 0f, h - y1)
    }

    private fun star(p: Path, w: Float, h: Float, n: Int, inner: Float) {
        for (i in 0 until n * 2) {
            val ang = Math.toRadians(-90.0 + i * 180.0 / n)
            val r = if (i % 2 == 0) 1f else inner
            val x = w / 2f + cos(ang).toFloat() * w / 2f * r
            val y = h / 2f + sin(ang).toFloat() * h / 2f * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private fun presetPath(prst: String, adj: Map<String, Float>, w: Float, h: Float): Path {
        val p = Path()
        val ss = min(w, h)
        val full = RectF(0f, 0f, w, h)
        fun a(n: String, d: Float) = adj[n] ?: d
        when (prst) {
            "ellipse", "flowChartConnector", "cloud", "cloudCallout", "wedgeEllipseCallout", "pie", "chord" ->
                p.addOval(full, Path.Direction.CW)
            "roundRect", "flowChartAlternateProcess", "wedgeRoundRectCallout", "round2SameRect",
            "round1Rect", "round2DiagRect", "snip1Rect", "snip2SameRect", "snipRoundRect", "plaque", "can" -> {
                val r = ss * a("adj", 0.16667f)
                p.addRoundRect(full, r, r, Path.Direction.CW)
            }
            "flowChartTerminator" -> p.addRoundRect(full, min(h / 2f, w / 2f), h / 2f, Path.Direction.CW)
            "triangle", "flowChartExtract" -> poly(p, w * a("adj", 0.5f), 0f, w, h, 0f, h)
            "flowChartMerge" -> poly(p, 0f, 0f, w, 0f, w / 2f, h)
            "rtTriangle" -> poly(p, 0f, 0f, w, h, 0f, h)
            "diamond", "flowChartDecision" -> poly(p, w / 2f, 0f, w, h / 2f, w / 2f, h, 0f, h / 2f)
            "parallelogram", "flowChartInputOutput" -> {
                val o = ss * a("adj", 0.25f); poly(p, o, 0f, w, 0f, w - o, h, 0f, h)
            }
            "trapezoid", "flowChartManualOperation" -> {
                val o = ss * a("adj", 0.25f); poly(p, o, 0f, w - o, 0f, w, h, 0f, h)
            }
            "hexagon", "flowChartPreparation" -> {
                val o = ss * a("adj", 0.25f); poly(p, o, 0f, w - o, 0f, w, h / 2f, w - o, h, o, h, 0f, h / 2f)
            }
            "octagon" -> {
                val o = ss * a("adj", 0.29289f)
                poly(p, o, 0f, w - o, 0f, w, o, w, h - o, w - o, h, o, h, 0f, h - o, 0f, o)
            }
            "pentagon" -> poly(p, w / 2f, 0f, w, h * 0.382f, w * 0.809f, h, w * 0.191f, h, 0f, h * 0.382f)
            "homePlate", "flowChartOffpageConnector" -> {
                val o = ss * a("adj", 0.5f); poly(p, 0f, 0f, w - o, 0f, w, h / 2f, w - o, h, 0f, h)
            }
            "chevron" -> {
                val o = ss * a("adj", 0.5f); poly(p, 0f, 0f, w - o, 0f, w, h / 2f, w - o, h, 0f, h, o, h / 2f)
            }
            "rightArrow", "leftArrow" -> {
                arrow(p, w, h, a("adj1", 0.5f), a("adj2", 0.5f))
                if (prst == "leftArrow") p.transform(Matrix().apply { setScale(-1f, 1f, w / 2f, h / 2f) })
            }
            "upArrow", "downArrow" -> {
                arrow(p, h, w, a("adj1", 0.5f), a("adj2", 0.5f))
                val m = Matrix()
                m.setRotate(90f)
                m.postTranslate(w, 0f)
                if (prst == "upArrow") m.postScale(1f, -1f, w / 2f, h / 2f)
                p.transform(m)
            }
            "star4" -> star(p, w, h, 4, 0.38f)
            "star5" -> star(p, w, h, 5, 0.382f)
            "star6" -> star(p, w, h, 6, 0.5f)
            "star8" -> star(p, w, h, 8, 0.6f)
            "plus", "mathPlus" -> {
                val o = ss * a("adj", 0.25f)
                poly(p, o, 0f, w - o, 0f, w - o, o, w, o, w, h - o, w - o, h - o, w - o, h, o, h, o, h - o, 0f, h - o, 0f, o, o, o)
            }
            "donut" -> {
                p.addOval(full, Path.Direction.CW)
                val t = ss * a("adj", 0.25f)
                if (t * 2 < ss) p.addOval(RectF(t, t, w - t, h - t), Path.Direction.CCW)
                p.fillType = Path.FillType.EVEN_ODD
            }
            "frame" -> {
                p.addRect(full, Path.Direction.CW)
                val t = ss * a("adj1", 0.125f)
                if (t * 2 < ss) p.addRect(RectF(t, t, w - t, h - t), Path.Direction.CCW)
                p.fillType = Path.FillType.EVEN_ODD
            }
            else -> p.addRect(full, Path.Direction.CW)
        }
        return p
    }

    private fun custPaths(geom: Element, w: Float, h: Float): Pair<Path, Path> {
        val fillP = Path()
        val strokeP = Path()
        for (pe in geom.child("pathLst")?.children("path") ?: emptyList()) {
            val pw = pe.attr("w")?.toFloatOrNull() ?: 0f
            val phh = pe.attr("h")?.toFloatOrNull() ?: 0f
            val sx = if (pw > 0f) w / pw else 1f / EMU_PER_PT
            val sy = if (phh > 0f) h / phh else 1f / EMU_PER_PT
            val path = Path()
            var cx = 0f
            var cy = 0f
            for (cmd in pe.kids()) {
                val pts = cmd.children("pt").map {
                    PointF((it.attr("x")?.toFloatOrNull() ?: 0f) * sx, (it.attr("y")?.toFloatOrNull() ?: 0f) * sy)
                }
                when (cmd.ln) {
                    "moveTo" -> if (pts.isNotEmpty()) { path.moveTo(pts[0].x, pts[0].y); cx = pts[0].x; cy = pts[0].y }
                    "lnTo" -> if (pts.isNotEmpty()) { path.lineTo(pts[0].x, pts[0].y); cx = pts[0].x; cy = pts[0].y }
                    "cubicBezTo" -> if (pts.size >= 3) {
                        path.cubicTo(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y); cx = pts[2].x; cy = pts[2].y
                    }
                    "quadBezTo" -> if (pts.size >= 2) {
                        path.quadTo(pts[0].x, pts[0].y, pts[1].x, pts[1].y); cx = pts[1].x; cy = pts[1].y
                    }
                    "arcTo" -> {
                        val wr = (cmd.attr("wR")?.toFloatOrNull() ?: 0f) * sx
                        val hr = (cmd.attr("hR")?.toFloatOrNull() ?: 0f) * sy
                        val st = (cmd.attr("stAng")?.toFloatOrNull() ?: 0f) / 60000f
                        val swp = (cmd.attr("swAng")?.toFloatOrNull() ?: 0f) / 60000f
                        val rad = Math.toRadians(st.toDouble())
                        val ox = cx - wr * cos(rad).toFloat()
                        val oy = cy - hr * sin(rad).toFloat()
                        path.arcTo(RectF(ox - wr, oy - hr, ox + wr, oy + hr), st, swp, false)
                        val er = Math.toRadians((st + swp).toDouble())
                        cx = ox + wr * cos(er).toFloat(); cy = oy + hr * sin(er).toFloat()
                    }
                    "close" -> path.close()
                }
            }
            if (pe.attr("fill") != "none") fillP.addPath(path)
            val s = pe.attr("stroke")
            if (s != "0" && s != "false") strokeP.addPath(path)
        }
        return fillP to strokeP
    }

    private fun drawConnector(c: Canvas, prst: String, adj: Map<String, Float>, w: Float, h: Float, line: LineSpec) {
        val path = Path()
        val pts: List<PointF>
        var curved = false
        when {
            prst == "bentConnector2" -> pts = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(w, h))
            prst.startsWith("bentConnector") -> {
                val x = w * (adj["adj1"] ?: 0.5f)
                pts = listOf(PointF(0f, 0f), PointF(x, 0f), PointF(x, h), PointF(w, h))
            }
            prst.startsWith("curvedConnector") -> {
                curved = true
                pts = listOf(PointF(0f, 0f), PointF(w / 2f, 0f), PointF(w / 2f, h), PointF(w, h))
                path.moveTo(0f, 0f); path.cubicTo(w / 2f, 0f, w / 2f, h, w, h)
            }
            else -> pts = listOf(PointF(0f, 0f), PointF(w, h))
        }
        if (!curved) {
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        }
        c.drawPath(path, strokePaint(line))
        if (line.tail != null && line.tail != "none") arrowHead(c, pts[pts.size - 2], pts[pts.size - 1], line)
        if (line.head != null && line.head != "none") arrowHead(c, pts[1], pts[0], line)
    }

    private fun arrowHead(c: Canvas, from: PointF, to: PointF, line: LineSpec) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.01f) return
        val ux = dx / len
        val uy = dy / len
        val size = max(line.width * 3f, 4f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = line.color
        if (line.tail == "oval" || line.head == "oval") {
            c.drawCircle(to.x, to.y, size / 2f, p); return
        }
        val bx = to.x - ux * size
        val by = to.y - uy * size
        val path = Path()
        path.moveTo(to.x, to.y)
        path.lineTo(bx - uy * size / 2f, by + ux * size / 2f)
        path.lineTo(bx + uy * size / 2f, by - ux * size / 2f)
        path.close()
        c.drawPath(path, p)
    }

    // ---------- imagens ----------

    private fun drawPic(c: Canvas, pic: Element, part: String, env: Env, isSlide: Boolean, fallback: Xf?) {
        val ph = phOf(pic)
        val spPr = pic.child("spPr")
        var xfrm = spPr?.child("xfrm")
        if (xfrm == null && ph != null && isSlide) {
            val l = env.layoutTree?.let { findPh(it, ph, false) }
            xfrm = l?.child("spPr")?.child("xfrm")
                ?: env.masterTree?.let { findPh(it, l?.let { x -> phOf(x) } ?: ph, true) }?.child("spPr")?.child("xfrm")
        }
        val b = xfrm?.let { Xf(it) } ?: fallback ?: return
        val blipFill = pic.child("blipFill") ?: return
        val target = blipFill.child("blip")?.rAttr("embed")?.let { relTarget(part, it) } ?: return
        val geom = spPr?.child("prstGeom") ?: spPr?.child("custGeom")
        c.save()
        c.translate(b.x, b.y)
        if (b.rot != 0f) c.rotate(b.rot, b.w / 2f, b.h / 2f)
        if (b.flipH || b.flipV) c.scale(if (b.flipH) -1f else 1f, if (b.flipV) -1f else 1f, b.w / 2f, b.h / 2f)
        val path = if (geom != null && geom.ln == "custGeom") custPaths(geom, b.w, b.h).first
        else presetPath(geom?.attr("prst") ?: "rect", adjOf(geom), b.w, b.h)
        paintFill(c, path, RectF(0f, 0f, b.w, b.h), ImageFill(target, srcRect(blipFill), alphaOf(blipFill.child("blip"))))
        resolveLine(spPr?.child("ln"), pic.child("style"), env.ctx)?.let { c.drawPath(path, strokePaint(it)) }
        c.restore()
    }

    // ---------- grupos ----------

    private fun drawGroup(c: Canvas, g: Element, part: String, env: Env, isSlide: Boolean, grpFill: Fill?) {
        val gsp = g.child("grpSpPr")
        val own = parseFill(gsp, part, env.ctx, null)
        val myFill = if (own == null || own === GroupFill) grpFill else own
        c.save()
        val xf = gsp?.child("xfrm")
        if (xf != null) {
            val b = Xf(xf)
            c.translate(b.x, b.y)
            if (b.rot != 0f) c.rotate(b.rot, b.w / 2f, b.h / 2f)
            if (b.flipH || b.flipV) c.scale(if (b.flipH) -1f else 1f, if (b.flipV) -1f else 1f, b.w / 2f, b.h / 2f)
            if (b.chW > 0f && b.chH > 0f) c.scale(b.w / b.chW, b.h / b.chH)
            c.translate(-b.chX, -b.chY)
        }
        for (k in g.kids()) drawItem(c, k, part, env, isSlide, myFill)
        c.restore()
    }

    // ---------- graphicFrame: tabela, gráfico, SmartArt, OLE ----------

    private fun drawFrame(c: Canvas, gf: Element, part: String, env: Env, isSlide: Boolean) {
        var xf = gf.child("xfrm")
        if (xf == null) {
            val ph = phOf(gf)
            if (ph != null && isSlide) xf = env.layoutTree?.let { findPh(it, ph, false) }?.child("spPr")?.child("xfrm")
        }
        val b = Xf(xf ?: return)
        val gd = gf.child("graphic")?.child("graphicData") ?: return
        val tbl = gd.child("tbl")
        if (tbl != null) {
            drawTable(c, tbl, b, env); return
        }
        val uri = gd.attr("uri") ?: ""
        when {
            uri.endsWith("/chart") ->
                gd.child("chart")?.rAttr("id")?.let { relTarget(part, it) }?.let { drawChart(c, it, b, env) }
            uri.endsWith("/diagram") -> drawSmartArt(c, gd, b, part, env)
            else -> findDesc(gd, "pic")?.let { drawPic(c, it, part, env, true, b) }
        }
    }

    private fun drawSmartArt(c: Canvas, gd: Element, b: Xf, part: String, env: Env) {
        val relIds = gd.child("relIds") ?: return
        val dataPath = relIds.rAttr("dm")?.let { relTarget(part, it) } ?: return
        val data = xml(dataPath) ?: return
        val relId = findDesc(data, "dataModelExt")?.attr("relId")
        val drawingPath = relId?.let { relTarget(part, it) }
            ?: rels(part).firstOrNull { it.type.endsWith("/diagramDrawing") }?.target ?: return
        val tree = xml(drawingPath)?.let { findDesc(it, "spTree") } ?: return
        c.save()
        c.translate(b.x, b.y)
        drawTree(c, tree, drawingPath, env, true, null)
        c.restore()
    }

    // ---------- tabelas ----------

    private fun tableStyle(id: String?, ctx: Ctx): TStyle {
        val path = relByType(presPath, "/tableStyles") ?: "ppt/tableStyles.xml"
        val st = xml(path)?.children("tblStyle")?.firstOrNull { it.attr("styleId") == id }
        if (st == null) {
            if (id == "{2D5ABB26-0587-4C30-8999-92F81FD0307C}") return TStyle(emptyMap(), emptyMap())
            val a1 = schemeColor("accent1", ctx, null) ?: 0xFF4472C4.toInt()
            val lt = schemeColor("lt1", ctx, null) ?: Color.WHITE
            val dk = schemeColor("dk1", ctx, null) ?: Color.BLACK
            val head = TPart(SolidFill(a1), lt, true)
            val band = TPart(SolidFill(tint(a1, 0.4f)), null, null)
            val bl = LineSpec(lt, 1f, null, null, null, null)
            return TStyle(
                mapOf(
                    "wholeTbl" to TPart(SolidFill(tint(a1, 0.2f)), dk, null), "band1H" to band, "band1V" to band,
                    "firstRow" to head, "lastRow" to head, "firstCol" to head, "lastCol" to head
                ),
                mapOf("left" to bl, "right" to bl, "top" to bl, "bottom" to bl, "insideH" to bl, "insideV" to bl)
            )
        }
        val parts = HashMap<String, TPart>()
        for (k in st.kids()) {
            val tx = k.child("tcTxStyle")
            val tcs = k.child("tcStyle")
            val fill = tcs?.child("fill")?.let { parseFill(it, "", ctx, null) } ?: tcs?.child("fillRef")?.let { refFill(it, ctx) }
            val color = tx?.let { colorOf(it, ctx) ?: it.child("fontRef")?.let { fr -> colorOf(fr, ctx) } }
            val bold = tx?.attr("b")?.let { it == "on" }
            parts[k.ln] = TPart(fill, color, bold)
        }
        val borders = HashMap<String, LineSpec?>()
        st.child("wholeTbl")?.child("tcStyle")?.child("tcBdr")?.let { bdr ->
            for (e in bdr.kids()) {
                val l = e.child("ln")
                borders[e.ln] = if (l != null) resolveLine(l, null, ctx)
                else e.child("lnRef")?.let { r -> colorOf(r, ctx)?.let { LineSpec(it, 1f, null, null, null, null) } }
            }
        }
        return TStyle(parts, borders)
    }

    private fun drawTable(c: Canvas, tbl: Element, b: Xf, env: Env) {
        val ctx = env.ctx
        val cols = tbl.child("tblGrid")?.children("gridCol")?.map { emu(it.attr("w")) } ?: return
        val rows = tbl.children("tr")
        val nC = cols.size
        val nR = rows.size
        if (nC == 0 || nR == 0) return
        val tblPr = tbl.child("tblPr")
        fun flag(n: String) = isTrue(tblPr?.attr(n))
        val firstRow = flag("firstRow")
        val lastRow = flag("lastRow")
        val firstCol = flag("firstCol")
        val lastCol = flag("lastCol")
        val bandRow = flag("bandRow")
        val bandCol = flag("bandCol")
        val ts = tableStyle(tblPr?.child("tableStyleId")?.textContent?.trim(), ctx)
        val tx1 = schemeColor("tx1", ctx, null) ?: Color.BLACK
        val grid = Array(nR) { rows[it].children("tc") }

        fun look(ri: Int, ci: Int): Triple<Fill?, Int, Boolean> {
            val order = ArrayList<String>()
            order.add("wholeTbl")
            val headerOrFooter = (firstRow && ri == 0) || (lastRow && ri == nR - 1)
            if (bandRow && !headerOrFooter) order.add(if ((if (firstRow) ri - 1 else ri) % 2 == 0) "band1H" else "band2H")
            if (bandCol) order.add(if ((if (firstCol) ci - 1 else ci) % 2 == 0) "band1V" else "band2V")
            if (firstCol && ci == 0) order.add("firstCol")
            if (lastCol && ci == nC - 1) order.add("lastCol")
            if (lastRow && ri == nR - 1) order.add("lastRow")
            if (firstRow && ri == 0) order.add("firstRow")
            var fill: Fill? = null
            var color = tx1
            var bold = false
            for (k in order) {
                val p = ts.parts[k] ?: continue
                if (p.fill != null) fill = p.fill
                if (p.color != null) color = p.color
                if (p.bold != null) bold = p.bold
            }
            return Triple(fill, color, bold)
        }

        fun margins(tcPr: Element?) = floatArrayOf(
            tcPr?.attr("marL")?.let { emu(it) } ?: 7.2f, tcPr?.attr("marR")?.let { emu(it) } ?: 7.2f,
            tcPr?.attr("marT")?.let { emu(it) } ?: 3.6f, tcPr?.attr("marB")?.let { emu(it) } ?: 3.6f
        )

        fun cellText(tc: Element, w: Float, ri: Int, ci: Int): TextBox? {
            val tx = tc.child("txBody") ?: return null
            val m = margins(tc.child("tcPr"))
            val lk = look(ri, ci)
            return buildText(
                tx, max(1f, w - m[0] - m[1]), listOfNotNull(tx.child("lstStyle"), env.presDefault, env.otherStyle),
                env, lk.second, lk.third, ctx.theme.minor, 1f, 0f, true
            )
        }

        val xs = FloatArray(nC + 1)
        xs[0] = b.x
        for (i in 0 until nC) xs[i + 1] = xs[i] + cols[i]
        val heights = FloatArray(nR) { emu(rows[it].attr("h")) }
        for (ri in 0 until nR) {
            for ((ci, tc) in grid[ri].withIndex()) {
                if (ci >= nC) break
                if (isTrue(tc.attr("hMerge")) || isTrue(tc.attr("vMerge"))) continue
                if ((tc.attr("rowSpan")?.toIntOrNull() ?: 1) > 1) continue
                val span = tc.attr("gridSpan")?.toIntOrNull() ?: 1
                val box = cellText(tc, xs[min(nC, ci + span)] - xs[ci], ri, ci) ?: continue
                val m = margins(tc.child("tcPr"))
                heights[ri] = max(heights[ri], box.height + m[2] + m[3])
            }
        }
        val ys = FloatArray(nR + 1)
        ys[0] = b.y
        for (i in 0 until nR) ys[i + 1] = ys[i] + heights[i]

        val borderLines = ArrayList<Pair<FloatArray, LineSpec>>()
        for (ri in 0 until nR) {
            for ((ci, tc) in grid[ri].withIndex()) {
                if (ci >= nC) break
                if (isTrue(tc.attr("hMerge")) || isTrue(tc.attr("vMerge"))) continue
                val ce = min(nC, ci + (tc.attr("gridSpan")?.toIntOrNull() ?: 1))
                val re = min(nR, ri + (tc.attr("rowSpan")?.toIntOrNull() ?: 1))
                val rect = RectF(xs[ci], ys[ri], xs[ce], ys[re])
                val tcPr = tc.child("tcPr")
                val lk = look(ri, ci)
                val fill = parseFill(tcPr, "", ctx, null) ?: lk.first
                if (fill != null && fill !is NoFill && fill !is GroupFill) paintFill(c, rectPath(rect), rect, fill)

                val box = cellText(tc, rect.width(), ri, ci)
                if (box != null) {
                    val m = margins(tcPr)
                    val innerH = rect.height() - m[2] - m[3]
                    val dy = when (tcPr?.attr("anchor")) {
                        "ctr" -> (innerH - box.height) / 2f
                        "b" -> innerH - box.height
                        else -> 0f
                    }
                    c.save()
                    c.translate(rect.left + m[0], rect.top + m[2] + dy)
                    c.scale(1f / K, 1f / K)
                    box.layout.draw(c)
                    c.restore()
                }

                fun edge(tag: String, key: String): LineSpec? {
                    val e = tcPr?.child(tag)
                    return if (e != null) resolveLine(e, null, ctx) else ts.borders[key]
                }
                edge("lnT", if (ri == 0) "top" else "insideH")?.let { borderLines.add(floatArrayOf(rect.left, rect.top, rect.right, rect.top) to it) }
                edge("lnB", if (re == nR) "bottom" else "insideH")?.let { borderLines.add(floatArrayOf(rect.left, rect.bottom, rect.right, rect.bottom) to it) }
                edge("lnL", if (ci == 0) "left" else "insideV")?.let { borderLines.add(floatArrayOf(rect.left, rect.top, rect.left, rect.bottom) to it) }
                edge("lnR", if (ce == nC) "right" else "insideV")?.let { borderLines.add(floatArrayOf(rect.right, rect.top, rect.right, rect.bottom) to it) }
            }
        }
        for ((pts, l) in borderLines) c.drawLine(pts[0], pts[1], pts[2], pts[3], strokePaint(l))
    }

    // ---------- gráficos ----------

    private fun numCache(el: Element?): List<Double?> {
        if (el == null) return emptyList()
        val cache = findDesc(el, "numCache") ?: findDesc(el, "numLit") ?: return emptyList()
        val n = cache.child("ptCount")?.attr("val")?.toIntOrNull() ?: 0
        val pts = cache.children("pt")
        val size = max(n, (pts.maxOfOrNull { it.attr("idx")?.toIntOrNull() ?: 0 } ?: -1) + 1)
        val out = MutableList<Double?>(size) { null }
        for (p in pts) {
            val i = p.attr("idx")?.toIntOrNull() ?: continue
            if (i in 0 until size) out[i] = p.child("v")?.textContent?.trim()?.toDoubleOrNull()
        }
        return out
    }

    private fun strCache(el: Element?): List<String> {
        if (el == null) return emptyList()
        val cache = findDesc(el, "strCache") ?: findDesc(el, "numCache") ?: findDesc(el, "strLit") ?: return emptyList()
        val pts = cache.children("pt")
        val size = max(cache.child("ptCount")?.attr("val")?.toIntOrNull() ?: 0, (pts.maxOfOrNull { it.attr("idx")?.toIntOrNull() ?: 0 } ?: -1) + 1)
        val out = MutableList(size) { "" }
        for (p in pts) {
            val i = p.attr("idx")?.toIntOrNull() ?: continue
            if (i in 0 until size) out[i] = p.child("v")?.textContent ?: ""
        }
        return out
    }

    private fun fmtNum(d: Double): String =
        if (d % 1.0 == 0.0 && abs(d) < 1e15) d.toLong().toString() else "%.1f".format(d)

    private fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || raw.isNaN() || raw.isInfinite()) return 1.0
        val e = floor(log10(raw))
        val f = raw / 10.0.pow(e)
        val nf = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return nf * 10.0.pow(e)
    }

    private fun pieColor(s: ChartSeries, i: Int, ctx: Ctx): Int =
        s.el.children("dPt").firstOrNull { it.child("idx")?.attr("val")?.toIntOrNull() == i }
            ?.child("spPr")?.child("solidFill")?.let { colorOf(it, ctx) } ?: accentColor(i, ctx)

    private fun drawChart(c: Canvas, chartPath: String, b: Xf, env: Env) {
        val root = xml(chartPath) ?: return
        val chart = root.child("chart") ?: return
        val plot = chart.child("plotArea") ?: return
        val ctx = env.ctx
        val ch = plot.kids().firstOrNull { it.ln in CHART_TYPES } ?: return
        val kind = ch.ln
        val textColor = schemeColor("tx1", ctx, null) ?: Color.DKGRAY
        val sers = ch.children("ser").mapIndexed { i, s ->
            val name = s.child("tx")?.let { t -> findDesc(t, "v")?.textContent ?: allText(t, "t") }?.takeIf { it.isNotBlank() } ?: "Série ${i + 1}"
            val sp = s.child("spPr")
            val col = sp?.child("solidFill")?.let { colorOf(it, ctx) }
                ?: sp?.child("ln")?.child("solidFill")?.let { colorOf(it, ctx) } ?: accentColor(i, ctx)
            ChartSeries(name, numCache(s.child("val") ?: s.child("yVal")), strCache(s.child("cat") ?: s.child("xVal")), col, s)
        }
        if (sers.isEmpty()) return

        val w = b.w
        val h = b.h
        c.save()
        c.translate(b.x, b.y)
        c.clipRect(0f, 0f, w, h)
        root.child("spPr")?.let { sp ->
            val f = parseFill(sp, chartPath, ctx, null)
            if (f != null && f !is NoFill && f !is GroupFill) paintFill(c, rectPath(RectF(0f, 0f, w, h)), RectF(0f, 0f, w, h), f)
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = textColor
        var top = 6f
        var bottom = h - 6f
        var right = w - 6f
        val isPie = kind == "pieChart" || kind == "pie3DChart" || kind == "doughnutChart" || kind == "ofPieChart"

        chart.child("title")?.let { t ->
            val title = allText(t, "t").ifBlank { allText(t, "v") }.ifBlank { if (sers.size == 1) sers[0].name else "" }
            if (title.isNotBlank()) {
                tp.textSize = 14f
                tp.typeface = Typeface.DEFAULT_BOLD
                val s = TextUtils.ellipsize(title, tp, w - 12f, TextUtils.TruncateAt.END).toString()
                c.drawText(s, (w - tp.measureText(s)) / 2f, top + 14f, tp)
                top += 24f
            }
        }
        tp.typeface = Typeface.DEFAULT
        tp.textSize = 9f

        if (chart.child("legend") != null) {
            val entries = if (isPie) {
                val s = sers[0]
                s.vals.indices.map { i -> (s.cats.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "${i + 1}") to pieColor(s, i, ctx) }
            } else sers.map { it.name to it.color }
            val sq = Paint(Paint.ANTI_ALIAS_FLAG)
            val pos = chart.child("legend")?.child("legendPos")?.attr("val") ?: "r"
            if (pos == "r" || pos == "l") {
                val lw = min(w * 0.35f, (entries.maxOfOrNull { tp.measureText(it.first) } ?: 0f) + 18f)
                var ly = max(top, (h - entries.size * 14f) / 2f)
                val lx = w - 6f - lw
                for ((name, col) in entries) {
                    sq.color = col
                    c.drawRect(lx, ly + 2f, lx + 8f, ly + 10f, sq)
                    c.drawText(TextUtils.ellipsize(name, tp, lw - 14f, TextUtils.TruncateAt.END).toString(), lx + 12f, ly + 9.5f, tp)
                    ly += 14f
                }
                right -= lw + 6f
            } else {
                val items = entries.map { (n, col) ->
                    val t = TextUtils.ellipsize(n, tp, w * 0.4f, TextUtils.TruncateAt.END).toString()
                    Triple(t, col, 14f + tp.measureText(t) + 10f)
                }
                val lines = ArrayList<List<Triple<String, Int, Float>>>()
                var cur = ArrayList<Triple<String, Int, Float>>()
                var cw = 0f
                for (item in items) {
                    if (cw + item.third > w - 12f && cur.isNotEmpty()) {
                        lines.add(cur); cur = ArrayList(); cw = 0f
                    }
                    cur.add(item); cw += item.third
                }
                if (cur.isNotEmpty()) lines.add(cur)
                var ly = bottom - lines.size * 14f
                for (line in lines) {
                    var lx = (w - line.sumOf { it.third.toDouble() }.toFloat()) / 2f
                    for ((t, col, iw) in line) {
                        sq.color = col
                        c.drawRect(lx, ly + 2f, lx + 8f, ly + 10f, sq)
                        c.drawText(t, lx + 12f, ly + 9.5f, tp)
                        lx += iw
                    }
                    ly += 14f
                }
                bottom -= lines.size * 14f + 4f
            }
        }

        if (isPie) drawPie(c, ch, sers[0], kind == "doughnutChart", 6f, top, right, bottom, ctx)
        else drawAxisChart(c, ch, kind, sers, 6f, top, right, bottom, tp)
        c.restore()
    }

    private fun drawPie(c: Canvas, ch: Element, s: ChartSeries, donut: Boolean, left: Float, top: Float, right: Float, bottom: Float, ctx: Ctx) {
        val vals = s.vals.map { max(0.0, it ?: 0.0) }
        val total = vals.sum()
        if (total <= 0.0) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val r = max(4f, min(right - left, bottom - top) / 2f * 0.92f)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        var ang = -90f + (ch.child("firstSliceAng")?.attr("val")?.toFloatOrNull() ?: 0f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1f }
        val dl = s.el.child("dLbls") ?: ch.child("dLbls")
        val showPct = isTrue(dl?.child("showPercent")?.attr("val"))
        val showVal = isTrue(dl?.child("showVal")?.attr("val"))
        val hole = if (donut) ((ch.child("holeSize")?.attr("val")?.toFloatOrNull() ?: 50f) / 100f).coerceIn(0.1f, 0.9f) else 0f
        val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9f; typeface = Typeface.DEFAULT_BOLD }
        for ((i, v) in vals.withIndex()) {
            val sweep = (v / total * 360.0).toFloat()
            p.color = pieColor(s, i, ctx)
            c.drawArc(oval, ang, sweep, true, p)
            c.drawArc(oval, ang, sweep, true, sep)
            if ((showPct || showVal) && sweep > 12f) {
                val mid = Math.toRadians((ang + sweep / 2f).toDouble())
                val lr = if (donut) r * (1f + hole) / 2f else r * 0.62f
                val t = if (showPct) "${(v / total * 100).roundToInt()}%" else fmtNum(v)
                c.drawText(t, cx + cos(mid).toFloat() * lr - lp.measureText(t) / 2f, cy + sin(mid).toFloat() * lr + 3f, lp)
            }
            ang += sweep
        }
        if (donut) {
            p.color = Color.WHITE
            c.drawCircle(cx, cy, r * hole, p)
        }
    }

    private fun drawAxisChart(
        c: Canvas, ch: Element, kind: String, sers: List<ChartSeries>,
        left0: Float, top: Float, right: Float, bottom: Float, tp: TextPaint
    ) {
        val horizontal = ch.child("barDir")?.attr("val") == "bar"
        val grouping = ch.child("grouping")?.attr("val") ?: "clustered"
        val isBar = kind.startsWith("bar")
        val isArea = kind.startsWith("area")
        val stacked = grouping == "stacked" || grouping == "percentStacked"
        val percent = grouping == "percentStacked"
        val nCat = sers.maxOf { it.vals.size }
        if (nCat == 0) return
        val cats = (0 until nCat).map { i -> sers.firstNotNullOfOrNull { s -> s.cats.getOrNull(i)?.takeIf { it.isNotEmpty() } } ?: "${i + 1}" }
        fun raw(si: Int, ci: Int): Double = sers[si].vals.getOrNull(ci) ?: 0.0
        val totals = DoubleArray(nCat) { ci -> sers.indices.sumOf { abs(raw(it, ci)) } }
        fun value(si: Int, ci: Int): Double = if (percent && totals[ci] > 0) raw(si, ci) / totals[ci] * 100.0 else raw(si, ci)

        var lo = 0.0
        var hi = 0.0
        for (ci in 0 until nCat) {
            if (stacked) {
                var pos = 0.0
                var neg = 0.0
                for (si in sers.indices) {
                    val x = value(si, ci); if (x >= 0) pos += x else neg += x
                }
                hi = max(hi, pos); lo = min(lo, neg)
            } else for (si in sers.indices) {
                val x = value(si, ci); hi = max(hi, x); lo = min(lo, x)
            }
        }
        if (hi == lo) hi = lo + 1.0
        val step = niceStep((hi - lo) / 5.0)
        val axMin = floor(lo / step) * step
        val axMax = ceil(hi / step) * step
        val nTicks = ((axMax - axMin) / step).roundToInt().coerceIn(1, 20)
        val ticks = (0..nTicks).map { axMin + it * step }
        val label: (Double) -> String = { fmtNum(it) + if (percent) "%" else "" }

        tp.textSize = 8f
        tp.color = 0xFF595959.toInt()
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D9D9.toInt(); strokeWidth = 0.5f }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C8C8C.toInt(); strokeWidth = 0.75f }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val gap = (ch.child("gapWidth")?.attr("val")?.toFloatOrNull() ?: 150f) / 100f

        if (!horizontal) {
            val lw = (ticks.maxOfOrNull { tp.measureText(label(it)) } ?: 0f) + 6f
            val left = left0 + lw
            val plotTop = top + 4f
            val plotBottom = bottom - 14f
            val plotRight = right - 4f
            if (plotRight - left < 10f || plotBottom - plotTop < 10f) return
            fun yOf(x: Double) = plotBottom - ((x - axMin) / (axMax - axMin) * (plotBottom - plotTop)).toFloat()
            for (t in ticks) {
                val y = yOf(t)
                c.drawLine(left, y, plotRight, y, grid)
                val s = label(t)
                c.drawText(s, left - 4f - tp.measureText(s), y + 3f, tp)
            }
            val slot = (plotRight - left) / nCat
            for ((ci, name) in cats.withIndex()) {
                val s = TextUtils.ellipsize(name, tp, max(4f, slot - 2f), TextUtils.TruncateAt.END).toString()
                c.drawText(s, left + slot * (ci + 0.5f) - tp.measureText(s) / 2f, plotBottom + 10f, tp)
            }
            val zeroY = yOf(0.0.coerceIn(axMin, axMax))
            if (isBar) {
                for (ci in 0 until nCat) {
                    if (stacked) {
                        val bw = slot / (1f + gap)
                        val x0 = left + slot * ci + (slot - bw) / 2f
                        var pos = 0.0
                        var neg = 0.0
                        for (si in sers.indices) {
                            val v = value(si, ci)
                            val base = if (v >= 0) pos else neg
                            val y0 = yOf(base)
                            val y1 = yOf(base + v)
                            bar.color = sers[si].color
                            c.drawRect(x0, min(y0, y1), x0 + bw, max(y0, y1), bar)
                            if (v >= 0) pos += v else neg += v
                        }
                    } else {
                        val bw = slot / (sers.size + gap)
                        val start = left + slot * ci + bw * gap / 2f
                        for (si in sers.indices) {
                            val y1 = yOf(value(si, ci))
                            bar.color = sers[si].color
                            c.drawRect(start + bw * si, min(zeroY, y1), start + bw * (si + 1), max(zeroY, y1), bar)
                        }
                    }
                }
            } else {
                val cum = DoubleArray(nCat)
                val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
                val dot = Paint(Paint.ANTI_ALIAS_FLAG)
                for ((si, s) in sers.withIndex()) {
                    val path = Path()
                    val pts = ArrayList<PointF>()
                    for (ci in 0 until nCat) {
                        if (!stacked && s.vals.getOrNull(ci) == null) continue
                        val v = value(si, ci) + if (stacked) cum[ci] else 0.0
                        if (stacked) cum[ci] = v
                        pts.add(PointF(left + slot * (ci + 0.5f), yOf(v)))
                    }
                    if (pts.isEmpty()) continue
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                    if (isArea) {
                        path.lineTo(pts.last().x, zeroY); path.lineTo(pts[0].x, zeroY); path.close()
                        bar.color = s.color
                        c.drawPath(path, bar)
                    } else {
                        lineP.color = s.color
                        c.drawPath(path, lineP)
                        val marker = s.el.child("marker")?.child("symbol")?.attr("val")
                        if (marker != "none") {
                            dot.color = s.color
                            for (pt in pts) c.drawCircle(pt.x, pt.y, 2.5f, dot)
                        }
                    }
                }
            }
            c.drawLine(left, zeroY, plotRight, zeroY, axis)
        } else {
            val lw = min((right - left0) * 0.3f, (cats.maxOfOrNull { tp.measureText(it) } ?: 0f) + 6f)
            val left = left0 + lw
            val plotTop = top + 4f
            val plotBottom = bottom - 12f
            val plotRight = right - 8f
            if (plotRight - left < 10f || plotBottom - plotTop < 10f) return
            fun xOf(x: Double) = left + ((x - axMin) / (axMax - axMin) * (plotRight - left)).toFloat()
            for (t in ticks) {
                val x = xOf(t)
                c.drawLine(x, plotTop, x, plotBottom, grid)
                val s = label(t)
                c.drawText(s, x - tp.measureText(s) / 2f, plotBottom + 10f, tp)
            }
            val slot = (plotBottom - plotTop) / nCat
            for ((ci, name) in cats.withIndex()) {
                val s = TextUtils.ellipsize(name, tp, lw - 4f, TextUtils.TruncateAt.END).toString()
                c.drawText(s, left - 4f - tp.measureText(s), plotBottom - slot * (ci + 0.5f) + 3f, tp)
            }
            val zeroX = xOf(0.0.coerceIn(axMin, axMax))
            for (ci in 0 until nCat) {
                val slotBottom = plotBottom - slot * ci
                if (stacked) {
                    val bh = slot / (1f + gap)
                    val y1 = slotBottom - (slot - bh) / 2f
                    var pos = 0.0
                    var neg = 0.0
                    for (si in sers.indices) {
                        val v = value(si, ci)
                        val base = if (v >= 0) pos else neg
                        val x0 = xOf(base)
                        val x1 = xOf(base + v)
                        bar.color = sers[si].color
                        c.drawRect(min(x0, x1), y1 - bh, max(x0, x1), y1, bar)
                        if (v >= 0) pos += v else neg += v
                    }
                } else {
                    val bh = slot / (sers.size + gap)
                    val start = slotBottom - bh * gap / 2f
                    for (si in sers.indices) {
                        val x1 = xOf(value(si, ci))
                        bar.color = sers[si].color
                        c.drawRect(min(zeroX, x1), start - bh * (si + 1), max(zeroX, x1), start - bh * si, bar)
                    }
                }
            }
            c.drawLine(zeroX, plotTop, zeroX, plotBottom, axis)
        }
    }

    // ======================================================================
    // Texto
    // ======================================================================

    private fun androidFamily(name: String?): String {
        val n = (name ?: "").lowercase()
        return when {
            n.contains("courier") || n.contains("consolas") || n.contains("mono") || n.contains("lucida console") -> "monospace"
            n.contains("narrow") || n.contains("condensed") -> "sans-serif-condensed"
            listOf("times", "georgia", "cambria", "garamond", "palatino", "book antiqua", "bookman", "constantia", "serif", "baskerville", "didot")
                .any { n.contains(it) } && !n.contains("sans") -> "serif"
            n.contains("light") -> "sans-serif-light"
            else -> "sans-serif"
        }
    }

    private fun mapBullet(ch: String, font: String?): String {
        val f = font?.lowercase() ?: ""
        if (f.contains("wingdings") || f.contains("symbol")) {
            return when (ch) {
                "§" -> "▪"; "Ø" -> "➢"; "ü" -> "✓"; "q" -> "❑"; "v" -> "❖"; "n" -> "■"
                "l" -> "●"; "o" -> "○"; "è", "ð", "à" -> "➔"; "Ÿ", "·" -> "•"; "w" -> "◆"
                else -> "•"
            }
        }
        return ch.ifEmpty { "•" }
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    private fun autoNum(type: String, n: Int): String {
        val core = when {
            type.startsWith("alphaLc") -> ('a' + ((n - 1) % 26)).toString()
            type.startsWith("alphaUc") -> ('A' + ((n - 1) % 26)).toString()
            type.startsWith("romanLc") -> roman(n)
            type.startsWith("romanUc") -> roman(n).uppercase()
            else -> n.toString()
        }
        return when {
            type.contains("ParenBoth") -> "($core)"
            type.contains("ParenR") -> "$core)"
            type.contains("Plain") -> core
            else -> "$core."
        }
    }

    private fun drawShapeText(
        c: Canvas, tx: Element, rect: RectF, styles: List<Element>, bodyPrs: List<Element>,
        env: Env, defColor: Int, family: String
    ) {
        fun bp(n: String): String? = bodyPrs.firstNotNullOfOrNull { it.attr(n) }
        val lIns = bp("lIns")?.let { emu(it) } ?: 7.2f
        val tIns = bp("tIns")?.let { emu(it) } ?: 3.6f
        val rIns = bp("rIns")?.let { emu(it) } ?: 7.2f
        val bIns = bp("bIns")?.let { emu(it) } ?: 3.6f
        val anchor = bp("anchor") ?: "t"
        val vert = bp("vert") ?: "horz"
        val wrap = bp("wrap") != "none"
        val fitOwner = bodyPrs.firstOrNull { it.child("normAutofit") != null || it.child("spAutoFit") != null || it.child("noAutofit") != null }
        val norm = fitOwner?.child("normAutofit")
        val fontScale = norm?.attr("fontScale")?.toFloatOrNull()?.div(100000f) ?: 1f
        val lnRed = norm?.attr("lnSpcReduction")?.toFloatOrNull()?.div(100000f) ?: 0f
        val vertical = vert == "vert" || vert == "eaVert" || vert == "vert270" || vert == "wordArtVert" || vert == "mongolianVert"
        val bw = if (vertical) rect.height() else rect.width()
        val bh = if (vertical) rect.width() else rect.height()
        val innerW = max(1f, bw - lIns - rIns)
        val innerH = bh - tIns - bIns
        var scale = fontScale
        var box = buildText(tx, innerW, styles, env, defColor, false, family, scale, lnRed, wrap) ?: return
        if (norm != null) {
            var guard = 0
            while (box.height > innerH * 1.03f && scale > 0.3f && guard < 12) {
                scale *= 0.9f; guard++
                box = buildText(tx, innerW, styles, env, defColor, false, family, scale, lnRed, wrap) ?: return
            }
        }
        c.save()
        c.translate(rect.left, rect.top)
        if (vertical) {
            if (vert == "vert270") { c.translate(0f, rect.height()); c.rotate(-90f) }
            else { c.translate(rect.width(), 0f); c.rotate(90f) }
        }
        val dy = when (anchor) {
            "ctr" -> (innerH - box.height) / 2f
            "b" -> innerH - box.height
            else -> 0f
        }
        c.translate(lIns, tIns + dy)
        c.scale(1f / K, 1f / K)
        box.layout.draw(c)
        c.restore()
    }

    private fun buildText(
        tx: Element, innerW: Float, styles: List<Element>, env: Env, defColor: Int, defBold: Boolean,
        defFamily: String, fontScale: Float, lnRed: Float, wrap: Boolean
    ): TextBox? {
        val ctx = env.ctx
        val paras = tx.children("p")
        val hasAny = paras.any { p ->
            p.kids().any { (it.ln == "r" || it.ln == "fld") && !(it.child("t")?.textContent.isNullOrEmpty()) }
        }
        if (!hasAny) return null

        val sb = SpannableStringBuilder()
        val counters = IntArray(10)
        val bulletPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

        for ((pi, p) in paras.withIndex()) {
            val pPr = p.child("pPr")
            val lvl = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val chain = ArrayList<Element>()
            if (pPr != null) chain.add(pPr)
            for (s in styles) s.child("lvl${lvl + 1}pPr")?.let { chain.add(it) }
            for (s in styles) s.child("defPPr")?.let { chain.add(it) }
            val defRPrs = chain.mapNotNull { it.child("defRPr") }

            val runs = ArrayList<Pair<Element?, String>>()
            for (k in p.kids()) {
                when (k.ln) {
                    "r" -> runs.add(k.child("rPr") to (k.child("t")?.textContent ?: ""))
                    "br" -> runs.add(k.child("rPr") to "\n")
                    "fld" -> {
                        val t = if (k.attr("type") == "slidenum") env.slideNum.toString() else (k.child("t")?.textContent ?: "")
                        runs.add(k.child("rPr") to t)
                    }
                }
            }
            val hasText = runs.any { it.second.isNotBlank() }
            fun sizeOf(rPr: Element?): Float =
                ((rPr?.attr("sz") ?: defRPrs.firstNotNullOfOrNull { it.attr("sz") })?.toFloatOrNull()?.div(100f) ?: 18f) * fontScale
            val firstRun = runs.firstOrNull { it.second.isNotBlank() }
            val firstSize = sizeOf(firstRun?.first ?: p.child("endParaRPr"))

            val start = sb.length
            var firstColor = defColor
            if (!hasText) {
                sb.append(" ")
                sb.setSpan(RelativeSizeSpan(firstSize * K / BASE), start, sb.length, EXCL)
            } else {
                var firstSeen = false
                for ((rPr, text0) in runs) {
                    if (text0.isEmpty()) continue
                    val rp = listOfNotNull(rPr) + defRPrs
                    fun rA(n: String): String? = rp.firstNotNullOfOrNull { it.attr(n) }
                    var text = text0.replace('\u000B', '\n')
                    val cap = rA("cap")
                    if (cap == "all" || cap == "small") text = text.uppercase()
                    val s0 = sb.length
                    sb.append(text)
                    val e0 = sb.length
                    val baseline = rA("baseline")?.toFloatOrNull() ?: 0f
                    var sz = sizeOf(rPr)
                    if (baseline != 0f) sz *= 0.66f
                    sb.setSpan(RelativeSizeSpan(sz * K / BASE), s0, e0, EXCL)
                    val bold = rA("b")?.let { isTrue(it) } ?: defBold
                    val italic = rA("i")?.let { isTrue(it) } ?: false
                    if (bold || italic) {
                        sb.setSpan(StyleSpan(if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC), s0, e0, EXCL)
                    }
                    val link = rPr?.child("hlinkClick") != null
                    val u = rA("u")
                    if ((u != null && u != "none") || link) sb.setSpan(UnderlineSpan(), s0, e0, EXCL)
                    val strike = rA("strike")
                    if (strike != null && strike != "noStrike") sb.setSpan(StrikethroughSpan(), s0, e0, EXCL)
                    val ownColor = rPr?.let { r ->
                        r.child("solidFill")?.let { colorOf(it, ctx) }
                            ?: r.child("gradFill")?.child("gsLst")?.child("gs")?.let { colorOf(it, ctx) }
                    }
                    val color = ownColor
                        ?: (if (link) schemeColor("hlink", ctx, null) else null)
                        ?: defRPrs.firstNotNullOfOrNull { d -> d.child("solidFill")?.let { colorOf(it, ctx) } }
                        ?: defColor
                    if (!firstSeen) { firstColor = color; firstSeen = true }
                    sb.setSpan(ForegroundColorSpan(color), s0, e0, EXCL)
                    var face = rp.firstNotNullOfOrNull { it.child("latin")?.attr("typeface") } ?: defFamily
                    if (face == "+mj-lt") face = ctx.theme.major
                    if (face == "+mn-lt") face = ctx.theme.minor
                    sb.setSpan(TypefaceSpan(androidFamily(face)), s0, e0, EXCL)
                    val lf = face.lowercase()
                    if (lf.contains("calibri") || lf.contains("segoe") || lf.contains("aptos")) sb.setSpan(ScaleXSpan(0.93f), s0, e0, EXCL)
                    if (baseline > 0f) sb.setSpan(SuperscriptSpan(), s0, e0, EXCL)
                    else if (baseline < 0f) sb.setSpan(SubscriptSpan(), s0, e0, EXCL)
                }
            }
            if (pi < paras.size - 1) sb.append("\n")
            val end = sb.length

            fun pAttr(n: String): String? = chain.firstNotNullOfOrNull { it.attr(n) }
            val al = when (pAttr("algn")) {
                "ctr" -> Layout.Alignment.ALIGN_CENTER
                "r" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            sb.setSpan(AlignmentSpan.Standard(al), start, end, EXCL)

            val marL = pAttr("marL")?.let { emu(it) } ?: 0f
            val indent = pAttr("indent")?.let { emu(it) } ?: 0f
            var bullet: String? = null
            val owner = chain.firstOrNull { el ->
                el.child("buNone") != null || el.child("buChar") != null || el.child("buAutoNum") != null || el.child("buBlip") != null
            }
            if (hasText && owner != null) {
                owner.child("buChar")?.let { chEl ->
                    val font = chain.firstNotNullOfOrNull { it.child("buFont")?.attr("typeface") }
                    bullet = mapBullet(chEl.attr("char") ?: "•", font)
                }
                val an = owner.child("buAutoNum")
                if (an != null) {
                    val startAt = an.attr("startAt")?.toIntOrNull() ?: 1
                    counters[lvl] = if (counters[lvl] == 0) startAt else counters[lvl] + 1
                    for (j in lvl + 1 until counters.size) counters[j] = 0
                    bullet = autoNum(an.attr("type") ?: "arabicPeriod", counters[lvl])
                } else counters[lvl] = 0
                if (owner.child("buBlip") != null) bullet = "•"
            }
            val bText = bullet
            if (bText != null) {
                val bColor = chain.firstNotNullOfOrNull { el -> el.child("buClr")?.let { colorOf(it, ctx) } } ?: firstColor
                val bSize = chain.firstNotNullOfOrNull { el -> el.child("buSzPct")?.attr("val")?.toFloatOrNull() }
                    ?.let { firstSize * it / 100000f }
                    ?: chain.firstNotNullOfOrNull { el -> el.child("buSzPts")?.attr("val")?.toFloatOrNull() }?.div(100f)
                    ?: firstSize
                bulletPaint.textSize = bSize * K
                val bx = max(0f, marL + indent)
                val bwidth = bulletPaint.measureText("$bText ") / K
                val first = max(marL, bx + bwidth)
                sb.setSpan(BulletMarginSpan((first * K).roundToInt(), (marL * K).roundToInt(), bText, bx * K, bColor, bSize * K), start, end, EXCL)
            } else if (marL != 0f || indent != 0f) {
                sb.setSpan(LeadingMarginSpan.Standard(((marL + indent).coerceAtLeast(0f) * K).roundToInt(), (marL * K).roundToInt()), start, end, EXCL)
            }

            fun spc(n: String): Element? = chain.firstNotNullOfOrNull { it.child(n) }
            val lnSpc = spc("lnSpc")
            var mult = (lnSpc?.child("spcPct")?.attr("val")?.toFloatOrNull()?.div(100000f) ?: 1f) - lnRed
            mult = mult.coerceAtLeast(0.5f)
            val exact = lnSpc?.child("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f)
            fun pts(e: Element?): Float = e?.child("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f)
                ?: e?.child("spcPct")?.attr("val")?.toFloatOrNull()?.let { it / 100000f * firstSize * 1.2f } ?: 0f
            val before = if (pi == 0) 0f else pts(spc("spcBef"))
            val after = if (pi == paras.size - 1) 0f else pts(spc("spcAft"))
            sb.setSpan(ParaSpacingSpan((before * K).roundToInt(), (after * K).roundToInt(), mult, exact?.times(K)), start, end, EXCL)
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = BASE
        paint.color = defColor
        paint.typeface = Typeface.create(androidFamily(defFamily), Typeface.NORMAL)
        var widthPx = max(1, (innerW * K).toInt())
        if (!wrap) widthPx = max(widthPx, ceil(Layout.getDesiredWidth(sb, paint)).toInt() + 2)
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        return TextBox(layout, widthPx / K, layout.height / K)
    }
}

/** Marcador (bullet/numeração) desenhado na margem do parágrafo. */
private class BulletMarginSpan(
    private val firstMargin: Int, private val restMargin: Int, private val bullet: String,
    private val bx: Float, private val color: Int, private val size: Float
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = if (first) firstMargin else restMargin

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?
    ) {
        if (!first || text !is Spanned || text.getSpanStart(this) != start) return
        val oldColor = p.color
        val oldSize = p.textSize
        val oldStyle = p.style
        p.color = color
        p.textSize = size
        p.style = Paint.Style.FILL
        c.drawText(bullet, x + dir * bx, baseline.toFloat(), p)
        p.color = oldColor
        p.textSize = oldSize
        p.style = oldStyle
    }
}

/** Espaçamento entre linhas e antes/depois do parágrafo. */
private class ParaSpacingSpan(
    private val before: Int, private val after: Int, private val mult: Float, private val exact: Float?
) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val sp = text as? Spanned ?: return
        val s = sp.getSpanStart(this)
        val e = sp.getSpanEnd(this)
        val h = fm.descent - fm.ascent
        val ex = exact
        if (ex != null) {
            fm.ascent -= (ex - h).roundToInt()
        } else if (mult != 1f) {
            fm.ascent -= (h * (mult - 1f)).roundToInt()
        }
        fm.top = min(fm.top, fm.ascent)
        if (start == s) {
            fm.ascent -= before; fm.top -= before
        }
        if (end >= e) {
            fm.descent += after; fm.bottom += after
        }
    }
}
