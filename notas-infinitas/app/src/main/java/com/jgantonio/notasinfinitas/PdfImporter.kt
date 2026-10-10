package com.jgantonio.notasinfinitas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Importa um PDF como páginas-imagem na tela infinita. Cada página é renderizada
 * uma de cada vez (pouca memória) e salva como JPEG na pasta da nota.
 */
object PdfImporter {

    enum class Layout(val label: String) { VERTICAL("Em coluna"), HORIZONTAL("Lado a lado"), GRID("Em grade") }

    class Page(val file: String, val widthPt: Int, val heightPt: Int)

    /** Pontos do PDF -> unidades do mundo (A4 fica com ~890 de largura). */
    private const val WORLD_PER_PT = 1.5f
    private const val GAP = 48f

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun pageCount(context: Context, uri: Uri): Int =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> PdfRenderer(pfd).use { it.pageCount } } ?: 0

    /**
     * Renderiza as páginas para [dir]. [progress] recebe (página atual, total) e
     * [cancelled] permite interromper. Lança exceção para PDF protegido por senha.
     */
    fun render(
        context: Context,
        uri: Uri,
        dir: File,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): List<Page> {
        dir.mkdirs()
        val out = ArrayList<Page>()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return out
        pfd.use {
            PdfRenderer(pfd).use { renderer ->
                val n = renderer.pageCount
                val batch = UUID.randomUUID().toString().take(8)
                for (i in 0 until n) {
                    if (cancelled()) break
                    progress(i + 1, n)
                    renderer.openPage(i).use { page ->
                        // Resolução boa para zoom, sem exagero: ~2200 px no lado maior.
                        val k = min(3f, 2200f / max(page.width, page.height))
                        val w = max(1, (page.width * k).toInt())
                        val h = max(1, (page.height * k).toInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val name = "pdf_${batch}_p${i + 1}.jpg"
                        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        bmp.recycle()
                        out.add(Page(name, page.width, page.height))
                    }
                }
            }
        }
        return out
    }

    /** Posiciona as páginas a partir de (x0, y0) no arranjo escolhido. */
    fun layout(pages: List<Page>, layout: Layout, x0: Float, y0: Float): List<ImageElement> {
        val out = ArrayList<ImageElement>()
        val cols = when (layout) {
            Layout.VERTICAL -> 1
            Layout.HORIZONTAL -> max(1, pages.size)
            Layout.GRID -> max(1, min(4, ceil(sqrt(pages.size.toDouble())).toInt()))
        }
        var x = x0
        var y = y0
        var rowH = 0f
        pages.forEachIndexed { i, p ->
            if (i > 0 && i % cols == 0) {
                x = x0
                y += rowH + GAP
                rowH = 0f
            }
            val w = p.widthPt * WORLD_PER_PT
            val h = p.heightPt * WORLD_PER_PT
            out.add(ImageElement(p.file, RectF(x, y, x + w, y + h), shadow = true, locked = true, pdfPage = i + 1))
            x += w + GAP
            rowH = max(rowH, h)
        }
        return out
    }
}
