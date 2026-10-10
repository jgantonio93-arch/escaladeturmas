package com.jgantonio.notasinfinitas

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.math.abs

/**
 * Testes de funcionamento: simulam toques de caneta e dedo na tela e conferem o resultado.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FunctionalTest {

    private lateinit var app: android.app.Application
    private lateinit var controller: ActivityController<EditorActivity>
    private lateinit var editor: EditorActivity
    private lateinit var canvas: InfiniteCanvasView
    private lateinit var note: NoteInfo
    private var time = 0L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.getSharedPreferences("canetas", 0).edit().clear().commit()
        Library.init(app, reload = true)
        val folder = Library.folders.first()
        note = Library.createNote(folder.id, "Teste")
        openEditor()
    }

    private fun openEditor() {
        controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_NOTE, note.id)).setup()
        editor = controller.get()
        canvas = editor.canvasForTests
        layout()
    }

    private fun layout() {
        val dm = app.resources.displayMetrics
        val decor = editor.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
        ShadowLooper.idleMainLooper()
    }

    // ---- Toques simulados ---------------------------------------------------------------

    private fun event(action: Int, pts: List<Pair<Float, Float>>, tool: Int, down: Long): MotionEvent {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = 0.8f; size = 1f } }
        time += 16
        return MotionEvent.obtain(down, time, action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** Arrasta um dedo/caneta pelos pontos (coordenadas da tela do canvas). */
    private fun drag(pts: List<Pair<Float, Float>>, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        val down = time
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(pts.first()), tool, down))
        for (p in pts.drop(1)) canvas.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(p), tool, down))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_UP, listOf(pts.last()), tool, down))
        ShadowLooper.idleMainLooper()
    }

    private fun tap(x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) = drag(listOf(x to y, x to y), tool)

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 30) =
        (0..n).map { x0 + (x1 - x0) * it / n to y0 + (y1 - y0) * it / n }

    private fun pinch(cx: Float, cy: Float, from: Float, to: Float) {
        val f = MotionEvent.TOOL_TYPE_FINGER
        val down = time
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(cx - from to cy), f, down))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(cx - from to cy, cx + from to cy), f, down))
        for (k in 1..10) {
            val d = from + (to - from) * k / 10
            canvas.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(cx - d to cy, cx + d to cy), f, down))
        }
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(cx - to to cy, cx + to to cy), f, down))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_UP, listOf(cx - to to cy), f, down))
    }

    private fun items() = canvas.elementsSnapshot()
    private fun strokes() = items().filterIsInstance<StrokeElement>()

    // ---- Testes ----------------------------------------------------------------------------

    @Test
    fun caneta_desenha_e_desfazer_refazer() {
        drag(line(200f, 600f, 800f, 650f))
        assertEquals(1, strokes().size)
        canvas.undo()
        assertEquals(0, items().size)
        canvas.redo()
        assertEquals(1, strokes().size)
    }

    @Test
    fun dedo_move_a_tela_sem_riscar() {
        val before = canvas.screenX(0f)
        drag(line(300f, 800f, 600f, 900f), MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(0, items().size)
        assertTrue("a tela deve ter rolado", abs(canvas.screenX(0f) - before - 300f) < 2f)
    }

    @Test
    fun dedo_escreve_quando_ativado() {
        canvas.fingerDraws = true
        drag(line(200f, 600f, 700f, 600f), MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(1, strokes().size)
    }

    @Test
    fun pinca_da_zoom_e_contador_acompanha() {
        assertEquals(100, canvas.zoomPercent)
        pinch(500f, 900f, 100f, 250f)
        assertTrue("zoom aumentou: ${canvas.zoomPercent}", canvas.zoomPercent in 200..300)
        pinch(500f, 900f, 250f, 103f)
        assertEquals("ímã em 100%", 100, canvas.zoomPercent)
        canvas.zoomTo(0.5f)
        assertEquals(50, canvas.zoomPercent)
    }

    @Test
    fun borracha_traco_inteiro_e_por_area() {
        drag(line(100f, 600f, 900f, 600f))
        drag(line(100f, 900f, 900f, 900f))
        canvas.tool = InfiniteCanvasView.Tool.ERASER
        canvas.eraserArea = false
        drag(line(500f, 500f, 500f, 700f))
        assertEquals("apagou só a primeira linha", 1, strokes().size)
        canvas.eraserArea = true
        drag(line(500f, 850f, 500f, 950f))
        assertEquals("por área divide a linha em duas", 2, strokes().size)
        canvas.undo()
        assertEquals(1, strokes().size)
    }

    @Test
    fun borracha_so_marca_texto() {
        canvas.pen = PenSettings.default(BrushType.HIGHLIGHTER, Color.YELLOW)
        drag(line(100f, 600f, 900f, 600f))
        canvas.pen = PenSettings.default(BrushType.PEN, Color.BLACK)
        drag(line(100f, 610f, 900f, 610f))
        canvas.tool = InfiniteCanvasView.Tool.ERASER
        canvas.eraserHighlighterOnly = true
        drag(line(500f, 500f, 500f, 700f))
        assertEquals(1, strokes().size)
        assertEquals(BrushType.PEN, strokes()[0].type)
    }

    @Test
    fun formas_automaticas_endireitam_linha() {
        canvas.autoShapes = true
        drag((0..40).map { 150f + it * 18f to 700f + (if (it % 2 == 0) 3f else -3f) })
        assertEquals(2, strokes()[0].n)
    }

    @Test
    fun laco_seleciona_move_e_desfaz() {
        drag(line(300f, 700f, 600f, 700f))
        val before = strokes()[0].bounds.left
        canvas.tool = InfiniteCanvasView.Tool.SELECT
        drag(listOf(250f to 600f, 700f to 600f, 700f to 800f, 250f to 800f, 250f to 610f))
        assertEquals(1, canvas.selectedItems.size)
        drag(line(450f, 700f, 650f, 760f, 10))
        val moved = strokes()[0].bounds.left
        assertTrue("moveu ~200px (zoom 100%): ${moved - before}", abs(moved - before - 200f) < 3f)
        canvas.undo()
        assertTrue(abs(strokes()[0].bounds.left - before) < 0.5f)
    }

    @Test
    fun selecao_gira_duplica_ordena_e_exclui() {
        drag(line(300f, 700f, 600f, 700f))
        canvas.selectAll()
        canvas.rotateSelection(90f)
        val b = strokes()[0].bounds
        assertTrue("ficou vertical", b.height() > b.width())
        canvas.duplicateSelection()
        assertEquals(2, strokes().size)
        canvas.reorderSelection(false)
        assertTrue(items()[0] === canvas.selectedItems[0])
        canvas.deleteSelection()
        assertEquals(1, items().size)
    }

    private fun addPhoto(): ImageElement {
        val dir = Library.assetsDir(note.id).apply { mkdirs() }
        val b = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        File(dir, "foto.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        canvas.addImage("foto.jpg", 400, 200)
        return canvas.selectedImage!!
    }

    @Test
    fun imagem_toque_seleciona_gira_espelha_recorta_trava() {
        val img = addPhoto()
        canvas.clearSelection()
        tap(canvas.screenX(img.centerX), canvas.screenY(img.centerY))
        assertNotNull("toque seleciona a imagem", canvas.selectedImage)
        canvas.rotateSelection(90f)
        assertEquals(90f, canvas.selectedImage!!.rotation, 0.01f)
        canvas.editImages { it.with(flipH = true, filter = ImageFilter.SEPIA, brightness = 0.3f) }
        assertTrue(canvas.selectedImage!!.flipH)

        canvas.startCrop()
        assertTrue(canvas.inCropMode)
        canvas.setCropShape(InfiniteCanvasView.CropShape.SQUARE)
        canvas.applyCrop()
        val c = canvas.selectedImage!!
        assertTrue("recorte quadrado", abs(c.rect.width() - c.rect.height()) < 1f)
        assertTrue(c.crop.width() < 1f)

        canvas.setLocked(true)
        assertTrue(canvas.selectionLocked)
        canvas.clearSelection()
        canvas.tool = InfiniteCanvasView.Tool.SELECT
        drag(listOf(0f to 0f, 1200f to 0f, 1200f to 2000f, 0f to 2000f, 0f to 10f))
        assertEquals("laço ignora imagem travada", 0, canvas.selectedItems.size)
        tap(canvas.screenX(c.centerX), canvas.screenY(c.centerY))
        assertNotNull("toque ainda seleciona a travada (para destravar)", canvas.selectedImage)
    }

    @Test
    fun ajustes_de_imagem_viram_uma_acao_de_desfazer() {
        addPhoto()
        canvas.beginDraft()
        canvas.updateDraft { it.with(contrast = 0.2f) }
        canvas.updateDraft { it.with(contrast = 0.4f, shadow = true) }
        canvas.finishDraft()
        assertEquals(0.4f, canvas.selectedImage!!.contrast, 0.001f)
        canvas.undo()
        assertEquals(0f, (items().last() as ImageElement).contrast, 0.001f)
    }

    @Test
    fun texto_na_tela_digita_formata_e_salva() {
        canvas.tool = InfiniteCanvasView.Tool.TEXT
        tap(400f, 700f)
        val te = editor.textEditorForTests
        assertTrue("toque com a ferramenta de texto abre a edição", te.isActive)
        val edit = findEdit()
        edit.setText("Olá mundo")
        edit.setSelection(0, 3)
        // Botão "B" da barra com trecho selecionado: só "Olá" fica em negrito
        clickText("B")
        te.finish()
        val t = items().filterIsInstance<TextElement>().single()
        assertEquals("Olá mundo", t.text)
        assertTrue(t.spans.any { it.type == TextSpan.BOLD && it.start == 0 && it.end == 3 })
        assertTrue(!t.bold)

        // Selecionar, girar, aumentar e espelhar
        canvas.select(t)
        canvas.rotateSelection(45f)
        canvas.editTexts { it.with(flipH = true) }
        val r = canvas.selectedText!!
        assertEquals(45f, r.rotation, 0.01f)
        assertTrue(r.flipH)

        // Persistência
        controller.pause().stop().destroy()
        Library.io.submit {}.get()
        openEditor()
        val back = items().filterIsInstance<TextElement>().single()
        assertEquals(45f, back.rotation, 0.01f)
        assertTrue(back.spans.any { it.type == TextSpan.BOLD })
    }

    @Test
    fun texto_vazio_nao_cria_caixa_e_apagar_texto_remove() {
        canvas.tool = InfiniteCanvasView.Tool.TEXT
        tap(400f, 700f)
        editor.textEditorForTests.finish()
        assertEquals(0, items().size)
        tap(400f, 700f)
        findEdit().setText("x")
        editor.textEditorForTests.finish()
        val t = items().single() as TextElement
        canvas.tool = InfiniteCanvasView.Tool.TEXT
        tap(canvas.screenX(t.centerX), canvas.screenY(t.centerY))
        assertTrue(editor.textEditorForTests.isActive)
        findEdit().setText("")
        editor.textEditorForTests.finish()
        assertEquals(0, items().size)
    }

    @Test
    fun conversao_de_estilos_do_texto() {
        val sb = SpannableStringBuilder("abcdef")
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.RED), 2, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val spans = InlineTextEditor.spansOf(sb, 6)
        assertTrue(spans.any { it.type == TextSpan.BOLD && it.end == 2 })
        assertTrue(spans.any { it.type == TextSpan.COLOR && it.value == Color.RED })
        val t = TextElement("abcdef", 0f, 0f, 40f, Color.BLACK, spans = spans)
        assertTrue(t.boxWidth > 40f && t.boxHeight >= 40f)
    }

    @Test
    fun nota_salva_e_reabre_com_tudo() {
        drag(line(200f, 600f, 800f, 650f))
        addPhoto()
        canvas.addText(TextElement("oi", 10f, 10f, 40f, Color.BLACK))
        canvas.pageStyle = PageStyle.GRID
        controller.pause().stop().destroy()
        Library.io.submit {}.get()
        openEditor()
        assertEquals(3, items().size)
        assertEquals(PageStyle.GRID, canvas.pageStyle)
        assertTrue("miniatura gerada", Library.thumbFile(note.id).exists())
    }

    @Test
    fun biblioteca_pastas_e_notas() {
        val a = Library.createFolder("A", Color.RED, null)
        val b = Library.createFolder("B", Color.BLUE, a.id)
        val n = Library.createNote(b.id, "nota")
        Library.updateFolder(a, parent = b.id)
        assertNull("pasta não entra dentro da própria subpasta", a.parent)
        Library.updateNote(n, title = "nova", folder = a.id)
        assertEquals("nova", Library.note(n.id)!!.title)
        assertEquals(a.id, Library.note(n.id)!!.folder)
        val dup = Library.duplicateNote(n)
        assertTrue(dup.title.contains("cópia"))
        Library.deleteFolder(a.id)
        assertNull(Library.folder(b.id))
        assertNull(Library.note(n.id))
        assertNull(Library.note(dup.id))
    }

    @Test
    fun pdf_paginas_entram_por_baixo_e_travadas() {
        drag(line(200f, 600f, 800f, 650f))
        val pages = PdfImporter.layout(listOf(PdfImporter.Page("p1.jpg", 595, 842), PdfImporter.Page("p2.jpg", 595, 842)),
            PdfImporter.Layout.HORIZONTAL, 0f, 0f)
        canvas.addPages(pages)
        val list = items()
        assertTrue(list[0] is ImageElement && list[1] is ImageElement && list[2] is StrokeElement)
        assertTrue(list.take(2).all { (it as ImageElement).locked })
        canvas.undo()
        assertEquals(1, items().size)
    }

    // ---- Ajudantes ----------------------------------------------------------------------

    private fun findEdit(): EditText = findAll(editor.window.decorView).filterIsInstance<EditText>().single()

    private fun clickText(text: String) {
        val v = findAll(editor.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString() == text && it !is EditText }
        v.performClick()
    }

    private fun findAll(v: View): List<View> =
        if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { findAll(v.getChildAt(it)) } else listOf(v)
}
