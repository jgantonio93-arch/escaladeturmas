package com.jgantonio.notasinfinitas

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gera capturas de tela do app (Robolectric com renderização nativa) em docs/screenshots.
 * Não é um teste de comportamento: serve para revisar o visual sem um aparelho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val outDir = File(System.getProperty("shots.dir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun captureScreens() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Library.init(app)
        val (escola, nota) = seed(app)

        val home = Robolectric.buildActivity(FoldersActivity::class.java).setup().get()
        save(snap(home), "1-inicio")

        val folder = Robolectric.buildActivity(FoldersActivity::class.java,
            Intent(app, FoldersActivity::class.java).putExtra(FoldersActivity.EXTRA_FOLDER, escola.id)).setup().get()
        save(snap(folder), "2-pasta")

        home.editFolder(null, null)
        save(withDialog(home), "3-nova-pasta")

        val editor = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_NOTE, nota.id)).setup().get()
        save(snap(editor), "4-editor")

        editor.openPenTray()
        save(snap(editor), "5-bandeja-canetas")

        editor.openEraserTray()
        save(snap(editor), "6-bandeja-borracha")

        editor.showMoreMenu()
        save(withDialog(editor), "7-menu")

        // Imagem: seleção com alças, ajustes e recorte
        val canvas = editor.canvasForTests
        val photo = canvas.elementsSnapshot().first { it is ImageElement }
        canvas.select(photo)
        save(snap(editor), "8-imagem-selecionada")

        ImageTools.showAdjustments(editor, canvas)
        save(withDialog(editor), "9-ajustes-imagem")

        canvas.select(canvas.elementsSnapshot().first { it is ImageElement })
        canvas.startCrop()
        canvas.setCropShape(InfiniteCanvasView.CropShape.SQUARE)
        save(snap(editor), "10-recorte")
        canvas.cancelCrop()
    }

    // ---- Captura ---------------------------------------------------------------------

    private fun layoutRoot(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun snap(a: Activity): Bitmap {
        ShadowLooper.idleMainLooper()
        val dm = a.resources.displayMetrics
        val decor = a.window.decorView
        layoutRoot(decor, dm.widthPixels, dm.heightPixels)
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        // Imagens carregam em segundo plano: espera e desenha de novo.
        Thread.sleep(400)
        ShadowLooper.idleMainLooper()
        layoutRoot(decor, dm.widthPixels, dm.heightPixels)
        decor.draw(Canvas(bmp))
        return bmp
    }

    /** Tela da atividade escurecida + o painel inferior aberto por cima. */
    private fun withDialog(a: Activity): Bitmap {
        val base = snap(a)
        val dialog: Dialog = ShadowDialog.getLatestDialog() ?: return base
        val dm = a.resources.displayMetrics
        val content = (dialog.window!!.decorView as android.view.ViewGroup)
        val w = min(dm.widthPixels, (600 * dm.density).toInt())
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST))
        content.layout(0, 0, w, content.measuredHeight)
        val c = Canvas(base)
        c.drawColor(Color.argb(82, 0, 0, 0))
        c.save()
        c.translate((dm.widthPixels - w) / 2f, (dm.heightPixels - content.measuredHeight).toFloat())
        content.draw(c)
        c.restore()
        dialog.dismiss()
        return base
    }

    private fun save(b: Bitmap, name: String) {
        FileOutputStream(File(outDir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("captura: $name.png ${b.width}x${b.height}")
    }

    // ---- Dados de exemplo ------------------------------------------------------------

    private fun seed(app: android.content.Context): Pair<Folder, NoteInfo> {
        Library.folders.toList().forEach { Library.deleteFolder(it.id) }
        val c = Library.folderColors
        val escola = Library.createFolder("Escola", c[5], null)
        Library.createFolder("Trabalho", c[1], null)
        Library.createFolder("Ideias", c[4], null)
        Library.createFolder("Pessoal", c[7], null)
        Library.createFolder("Receitas", c[0], null)
        Library.createFolder("Física", c[6], escola.id)
        Library.createFolder("Matemática", c[3], escola.id)

        val unit = app.resources.displayMetrics.density * 0.5f
        val ink = Color.parseColor("#1B1B1F")
        val blue = Color.parseColor("#1F5FD1")
        val red = Color.parseColor("#D7263D")

        val aula = Library.createNote(escola.id, "Aula de física")
        val photo = samplePhoto(Library.assetsDir(aula.id))
        writeNote(app, aula, listOf(
            ImageElement(photo, android.graphics.RectF(60f, 430f, 390f, 650f), rotation = -6f,
                borderWidth = 8f, borderColor = Color.WHITE, shadow = true),
            TextElement("Leis de Newton", 40f, 30f, 30f * unit * 2, ink),
            *handwriting(PenSettings(BrushType.FOUNTAIN, ink, 5f, 100), unit, 40f, 150f, 520f, 3),
            stroke(PenSettings(BrushType.HIGHLIGHTER, Color.parseColor("#FFE600"), 22f, 45), unit,
                (0..30).map { 40f + it * 14f to 182f + sin(it / 5f) }),
            *handwriting(PenSettings(BrushType.PEN, blue, 4f, 100), unit, 40f, 300f, 420f, 2),
            circle(PenSettings(BrushType.PEN, red, 4f, 100), unit, 760f, 230f, 90f, 60f),
            stroke(PenSettings(BrushType.BRUSH, red, 8f, 100), unit, (0..30).map { 640f + it * 5f to 380f - it * 3.5f }),
            *handwriting(PenSettings(BrushType.PENCIL, Color.parseColor("#5F6368"), 5f, 90), unit, 620f, 420f, 360f, 2),
        ))
        val lista = Library.createNote(escola.id, "Lista de exercícios")
        writeNote(app, lista, listOf(
            *handwriting(PenSettings(BrushType.PEN, ink, 4f, 100), unit, 30f, 40f, 500f, 6),
        ))
        val mapa = Library.createNote(escola.id, "Mapa mental")
        writeNote(app, mapa, listOf(
            circle(PenSettings(BrushType.PEN, blue, 4f, 100), unit, 300f, 200f, 110f, 60f),
            circle(PenSettings(BrushType.PEN, red, 4f, 100), unit, 40f, 40f, 70f, 40f),
            circle(PenSettings(BrushType.PEN, Color.parseColor("#1E9E5A"), 4f, 100), unit, 560f, 380f, 80f, 45f),
            stroke(PenSettings(BrushType.PEN, ink, 3f, 100), unit, listOf(110f to 70f, 210f to 160f)),
            stroke(PenSettings(BrushType.PEN, ink, 3f, 100), unit, listOf(390f to 240f, 500f to 350f)),
            *handwriting(PenSettings(BrushType.CALLIGRAPHY, ink, 7f, 100), unit, 230f, 190f, 140f, 1),
        ))
        Library.createNote(escola.id, "Nota em branco")
        val ideia = Library.createNote(Library.folders.first { it.name == "Ideias" }.id, "App de notas")
        writeNote(app, ideia, listOf(
            *handwriting(PenSettings(BrushType.BRUSH, Color.parseColor("#8E44AD"), 6f, 100), unit, 30f, 40f, 420f, 3),
        ))
        // A nota principal fica como a mais recente.
        Library.updateNote(aula, touch = true)
        return escola to aula
    }

    /** Uma "foto" desenhada (céu, sol e montanhas) para os exemplos de imagem. */
    private fun samplePhoto(dir: File): String {
        dir.mkdirs()
        val w = 900
        val h = 600
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#7EC8F2"), Color.parseColor("#FCE3B0"), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.parseColor("#FFD15C")
        c.drawCircle(660f, 190f, 80f, p)
        p.color = Color.parseColor("#3E7C59")
        c.drawPath(android.graphics.Path().apply { moveTo(0f, 600f); lineTo(0f, 380f); lineTo(260f, 200f); lineTo(520f, 430f); lineTo(900f, 300f); lineTo(900f, 600f); close() }, p)
        p.color = Color.parseColor("#2C5E43")
        c.drawPath(android.graphics.Path().apply { moveTo(0f, 600f); lineTo(0f, 500f); lineTo(380f, 360f); lineTo(900f, 520f); lineTo(900f, 600f); close() }, p)
        val name = "exemplo.jpg"
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return name
    }

    private fun writeNote(app: android.content.Context, n: NoteInfo, elements: List<Element>) {
        val data = NoteData(elements, ViewState(60f, 80f, 1.15f), PageStyle.DOTS, Color.WHITE)
        NoteStorage.save(Library.noteFile(n.id), data)
        val view = InfiniteCanvasView(app)
        view.assetDir = Library.assetsDir(n.id)
        view.load(data)
        view.renderToBitmap(480, 360, 1f)?.let { b ->
            Library.thumbFile(n.id).apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    private fun stroke(pen: PenSettings, unit: Float, pts: List<Pair<Float, Float>>): StrokeElement {
        val b = StrokeBuilder(pen, unit)
        pts.forEachIndexed { i, (x, y) ->
            val p = 0.45f + 0.5f * sin(i / pts.size.toFloat() * PI).toFloat()
            b.add(x, y, p, 0.3f)
        }
        return b.build()
    }

    private fun circle(pen: PenSettings, unit: Float, cx: Float, cy: Float, rx: Float, ry: Float) =
        stroke(pen, unit, (0..64).map { cx + rx * cos(it / 64.0 * 2 * PI).toFloat() to cy + ry * sin(it / 64.0 * 2 * PI).toFloat() })

    /** Linhas de "letra cursiva" (palavras com laços), para parecer escrita à mão. */
    private fun handwriting(pen: PenSettings, unit: Float, x0: Float, y0: Float, width: Float, lines: Int): Array<StrokeElement> {
        val out = ArrayList<StrokeElement>()
        val rnd = java.util.Random(pen.color.toLong() + lines)
        for (l in 0 until lines) {
            var x = x0
            val y = y0 + l * 46f
            val end = x0 + width * (0.7f + rnd.nextFloat() * 0.3f)
            while (x < end) {
                // Uma "palavra": sequência de laços com alturas variadas
                val b = StrokeBuilder(pen, unit)
                val letters = 3 + rnd.nextInt(5)
                var t = 0f
                val stop = (letters * 2 * PI).toFloat()
                while (t < stop) {
                    val h = if ((t / (2 * PI)).toInt() % 3 == 1) 22f else 12f
                    b.add(x + 7f * sin(t), y - h * (0.5f - 0.5f * cos(t)), 0.45f + 0.45f * ((sin(t * 0.5f) + 1f) / 2f), 0.3f)
                    t += 0.35f
                    x += 0.9f
                }
                out.add(b.build())
                x += 18f + rnd.nextFloat() * 10f
            }
        }
        return out.toTypedArray()
    }
}
