package com.jgantonio.notasinfinitas

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.jgantonio.notasinfinitas.Ui.chip
import com.jgantonio.notasinfinitas.Ui.choose
import com.jgantonio.notasinfinitas.Ui.circle
import com.jgantonio.notasinfinitas.Ui.confirm
import com.jgantonio.notasinfinitas.Ui.divider
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.pill
import com.jgantonio.notasinfinitas.Ui.promptText
import com.jgantonio.notasinfinitas.Ui.styleChip
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity(), InfiniteCanvasView.Listener {

    private lateinit var note: NoteInfo
    private lateinit var prefs: PenPrefs
    private lateinit var canvasView: InfiniteCanvasView

    private lateinit var titleView: TextView
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView
    private lateinit var penButton: TextView
    private lateinit var eraserButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var textButton: TextView
    private lateinit var favoritesRow: LinearLayout
    private lateinit var quickColors: LinearLayout
    private lateinit var selectionBar: View
    private lateinit var selectionLabel: TextView
    private lateinit var zoomLabel: TextView

    private val main = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.init(this)
        val n = Library.note(intent.getStringExtra(EXTRA_NOTE))
        if (n == null) {
            finish()
            return
        }
        note = n
        prefs = PenPrefs(this)

        canvasView = InfiniteCanvasView(this)
        canvasView.listener = this
        canvasView.assetDir = Library.assetsDir(note.id)
        canvasView.pen = prefs.pen(prefs.currentType)
        applyPrefs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Ui.SURFACE)
        }
        root.addView(buildTopBar())
        root.addView(buildToolBar())
        selectionBar = buildSelectionBar()
        selectionBar.visibility = View.GONE
        root.addView(selectionBar)

        val holder = FrameLayout(this)
        holder.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        zoomLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.MUTED)
            setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f))
            background = pill(Color.parseColor("#E6FFFFFF"))
            setOnClickListener { canvasView.recenter() }
        }
        holder.addView(zoomLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dpi(12f), dpi(12f))
        })
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        NoteStorage.load(Library.noteFile(note.id))?.let { canvasView.load(it) }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (::canvasView.isInitialized) save()
    }

    private fun applyPrefs() {
        canvasView.fingerDraws = prefs.fingerDraws
        canvasView.autoShapes = prefs.autoShapes
        canvasView.spenButtonErases = prefs.spenButtonErases
        canvasView.eraserArea = prefs.eraserArea
        canvasView.eraserSize = prefs.eraserSize
        canvasView.eraserHighlighterOnly = prefs.eraserHighlighterOnly
    }

    // ---- Barras ----------------------------------------------------------------

    private fun buildTopBar(): View {
        val bar = horizontal().apply { setPadding(dpi(4f), dpi(4f), dpi(8f), dpi(2f)) }
        bar.addView(iconButton("←") { finish() })
        titleView = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Ui.INK)
            isSingleLine = true
            setPadding(dpi(6f), 0, dpi(6f), 0)
            setOnClickListener { rename() }
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoButton = iconButton("↶") { canvasView.undo() }
        redoButton = iconButton("↷") { canvasView.redo() }
        bar.addView(undoButton)
        bar.addView(redoButton)
        val more = iconButton("⋮") {}
        more.setOnClickListener { showMenu(more) }
        bar.addView(more)
        return bar
    }

    private fun iconButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Ui.INK)
        minWidth = dpi(44f)
        minHeight = dpi(44f)
        setOnClickListener { onClick() }
    }

    private fun buildToolBar(): View {
        val bar = horizontal().apply { setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(6f)) }

        penButton = chip("") {
            if (canvasView.tool == InfiniteCanvasView.Tool.PEN) openPenPanel() else setTool(InfiniteCanvasView.Tool.PEN)
        }
        eraserButton = chip("🧽 Borracha") {
            if (canvasView.tool == InfiniteCanvasView.Tool.ERASER) openEraserPanel() else setTool(InfiniteCanvasView.Tool.ERASER)
        }
        selectButton = chip("⬚ Seleção") { setTool(InfiniteCanvasView.Tool.SELECT) }
        textButton = chip("T Texto") { setTool(InfiniteCanvasView.Tool.TEXT) }
        bar.addView(penButton)
        bar.addView(eraserButton)
        bar.addView(selectButton)
        bar.addView(textButton)
        bar.addView(chip("🖼 Imagem") { pickImage() })
        bar.addView(divider())

        quickColors = horizontal()
        bar.addView(quickColors)
        bar.addView(divider())

        favoritesRow = horizontal()
        bar.addView(favoritesRow)

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    private fun buildSelectionBar(): View {
        val bar = horizontal().apply {
            setPadding(dpi(12f), dpi(4f), dpi(8f), dpi(6f))
            setBackgroundColor(Ui.ACCENT_SOFT)
        }
        selectionLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#123E8C"))
            textSize = 14f
            setPadding(0, 0, dpi(8f), 0)
        }
        bar.addView(selectionLabel)
        bar.addView(chip("🗑 Excluir") { canvasView.deleteSelection() })
        bar.addView(chip("⧉ Duplicar") { canvasView.duplicateSelection() })
        bar.addView(chip("🎨 Cor") { recolorSelection() })
        bar.addView(chip("✓ Concluir") { canvasView.clearSelection() })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    private fun refresh() {
        titleView.text = note.title
        val tool = canvasView.tool
        val pen = canvasView.pen
        penButton.text = "${pen.type.icon} ${pen.type.label}"
        penButton.styleChip(tool == InfiniteCanvasView.Tool.PEN)
        eraserButton.styleChip(tool == InfiniteCanvasView.Tool.ERASER)
        selectButton.styleChip(tool == InfiniteCanvasView.Tool.SELECT)
        textButton.styleChip(tool == InfiniteCanvasView.Tool.TEXT)
        undoButton.alpha = if (canvasView.canUndo) 1f else 0.3f
        redoButton.alpha = if (canvasView.canRedo) 1f else 0.3f
        zoomLabel.text = "${canvasView.zoomPercent}%"

        // Cores rápidas da caneta atual
        quickColors.removeAllViews()
        val palette = if (pen.type == BrushType.HIGHLIGHTER) PenPrefs.HIGHLIGHT_COLORS else
            listOf(PenPrefs.INK_COLORS[0], PenPrefs.INK_COLORS[8], PenPrefs.INK_COLORS[3], PenPrefs.INK_COLORS[6])
        val shown = (palette + prefs.customColors.take(2)).distinct().let {
            if (pen.color in it) it else listOf(pen.color) + it
        }
        for (c in shown) {
            val selected = tool == InfiniteCanvasView.Tool.PEN && c == pen.color
            quickColors.addView(Panels.swatch(this, c, selected) {
                setPen(canvasView.pen.copy(color = c))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dpi(28f), dpi(28f)).apply {
                    setMargins(dpi(3f), 0, dpi(3f), 0)
                }
            })
        }

        // Favoritos
        favoritesRow.removeAllViews()
        for (f in prefs.favorites) {
            val chip = chip("${f.type.icon} ${f.size.toInt()}") { setPen(f) }
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                circle(f.color, Ui.BORDER, 1f).apply { setSize(dpi(14f), dpi(14f)) }, null, null, null,
            )
            chip.compoundDrawablePadding = dpi(6f)
            chip.styleChip(tool == InfiniteCanvasView.Tool.PEN && pen == f)
            chip.setOnLongClickListener {
                confirm("Remover favorito?", "${f.type.label}, espessura ${f.size.toInt()}", "Remover") {
                    prefs.favorites = prefs.favorites.filter { it != f }
                    refresh()
                }
                true
            }
            favoritesRow.addView(chip)
        }
    }

    private fun setTool(tool: InfiniteCanvasView.Tool) {
        canvasView.tool = tool
        refresh()
    }

    private fun setPen(p: PenSettings) {
        prefs.savePen(p)
        prefs.currentType = p.type
        canvasView.pen = p
        canvasView.tool = InfiniteCanvasView.Tool.PEN
        refresh()
    }

    private fun openPenPanel() {
        Panels.showPen(this, prefs, canvasView.pen, onChange = { p ->
            canvasView.pen = p
            applyPrefs()
            refresh()
        }, onFavoritesChanged = { refresh() })
    }

    private fun openEraserPanel() {
        Panels.showEraser(this, prefs, onChange = { applyPrefs() }, onClearAll = { canvasView.clearAll() })
    }

    private fun recolorSelection() {
        val options = PenPrefs.INK_COLORS + prefs.customColors
        val names = listOf("Preto", "Cinza", "Branco", "Vermelho", "Laranja", "Amarelo", "Verde", "Turquesa",
            "Azul", "Azul-marinho", "Roxo", "Rosa", "Marrom") + prefs.customColors.map { String.format("#%06X", it and 0xFFFFFF) }
        choose("Mudar cor da seleção", names + "Personalizada…") { i ->
            if (i < options.size) {
                canvasView.recolorSelection(options[i])
            } else {
                ColorPicker.show(this, canvasView.pen.color) { c ->
                    prefs.addCustomColor(c)
                    canvasView.recolorSelection(c)
                }
            }
        }
    }

    // ---- Menu ⋮ --------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Plano de fundo…")
        menu.menu.add(0, 2, 1, "Exportar como imagem (PNG)")
        menu.menu.add(0, 3, 2, "Exportar como PDF")
        menu.menu.add(0, 4, 3, if (prefs.fingerDraws) "Dedo: escreve ✓" else "Dedo: só move a tela")
        menu.menu.add(0, 5, 4, "Centralizar conteúdo")
        menu.menu.add(0, 6, 5, "Renomear nota")
        menu.menu.add(0, 7, 6, "Mover para pasta…")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Panels.showBackground(this, canvasView.pageStyle, canvasView.paperColor) { s, c ->
                    canvasView.pageStyle = s
                    canvasView.paperColor = c
                }
                2 -> exportPng()
                3 -> exportPdf()
                4 -> {
                    prefs.fingerDraws = !prefs.fingerDraws
                    applyPrefs()
                    toast(if (prefs.fingerDraws) "Agora o dedo também escreve. Use 2 dedos para mover." else "O dedo agora só move a tela.")
                }
                5 -> canvasView.recenter()
                6 -> rename()
                7 -> moveToFolder()
            }
            true
        }
        menu.show()
    }

    private fun rename() {
        promptText("Renomear nota", note.title) { t ->
            if (t.isNotBlank()) {
                Library.updateNote(note, title = t)
                refresh()
            }
        }
    }

    private fun moveToFolder() {
        val tree = Library.tree()
        choose("Mover para", tree.map { (f, depth) -> "    ".repeat(depth) + "📁 " + f.name }) { i ->
            Library.updateNote(note, folder = tree[i].first.id)
            toast("Nota movida para ${tree[i].first.name}")
        }
    }

    // ---- Listener da tela ---------------------------------------------------------

    override fun onStateChanged() {
        undoButton.alpha = if (canvasView.canUndo) 1f else 0.3f
        redoButton.alpha = if (canvasView.canRedo) 1f else 0.3f
        zoomLabel.text = "${canvasView.zoomPercent}%"
    }

    override fun onSelectionChanged(count: Int) {
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        selectionLabel.text = if (count == 1) "1 item" else "$count itens"
        refresh()
    }

    override fun onTextRequest(x: Float, y: Float, existing: TextElement?) {
        Panels.showText(this, existing, density, onDone = { text, size ->
            val color = canvasView.pen.color
            if (existing == null) canvasView.addText(x, y, text, size, color)
            else canvasView.replaceText(existing, text, size, existing.color)
        }, onDelete = existing?.let { e -> { canvasView.replaceText(e, "", e.size, e.color) } })
    }

    // ---- Imagem -----------------------------------------------------------------

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Escolha uma imagem"), REQ_IMAGE)
    }

    @Deprecated("Activity sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Library.io.execute {
            val result = try { importImage(uri) } catch (e: Exception) { null }
            main.post {
                if (result == null) toast("Não foi possível abrir a imagem.")
                else canvasView.addImage(result.first, result.second, result.third)
            }
        }
    }

    /** Copia a imagem para a pasta da nota (reduzida a no máximo 2048 px). */
    private fun importImage(uri: Uri): Triple<String, Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val bmp0 = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val longSide = max(bmp0.width, bmp0.height)
        val bmp = if (longSide > 2048) {
            val f = 2048f / longSide
            Bitmap.createScaledBitmap(bmp0, (bmp0.width * f).toInt(), (bmp0.height * f).toInt(), true)
        } else bmp0
        val dir = Library.assetsDir(note.id).apply { mkdirs() }
        val png = bmp.hasAlpha()
        val name = UUID.randomUUID().toString().take(12) + if (png) ".png" else ".jpg"
        FileOutputStream(File(dir, name)).use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
        }
        return Triple(name, bmp.width, bmp.height)
    }

    // ---- Salvar e exportar ---------------------------------------------------------

    private fun save() {
        val data = canvasView.snapshot()
        val wasDirty = canvasView.dirty
        canvasView.markSaved()
        if (wasDirty) Library.updateNote(note, touch = true)
        val thumb = if (wasDirty) canvasView.renderToBitmap(480, 360, 1f) else null
        val id = note.id
        Library.io.execute {
            try {
                NoteStorage.save(Library.noteFile(id), data)
                if (wasDirty) {
                    val tf = Library.thumbFile(id)
                    if (thumb == null) tf.delete() else {
                        tf.parentFile?.mkdirs()
                        FileOutputStream(tf).use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        thumb.recycle()
                    }
                }
            } catch (e: Exception) {
                main.post { toast("Erro ao salvar a nota.") }
            }
        }
    }

    private fun exportPng() {
        val bitmap = canvasView.renderToBitmap(4096, 4096)
        if (bitmap == null) {
            toast("A nota está vazia.")
            return
        }
        val name = fileName("png")
        Library.io.execute {
            val ok = try {
                writeToMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/png",
                    Environment.DIRECTORY_PICTURES + "/NotasInfinitas") { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                false
            } finally {
                bitmap.recycle()
            }
            main.post { toast(if (ok) "Imagem salva em Imagens/NotasInfinitas" else "Não foi possível salvar a imagem.") }
        }
    }

    /** PDF de uma página do tamanho do conteúdo (vetorial: os traços continuam nítidos). */
    private fun exportPdf() {
        val content = canvasView.contentBounds()
        if (content == null) {
            toast("A nota está vazia.")
            return
        }
        val pad = 40f
        val w = content.width() + pad * 2
        val h = content.height() + pad * 2
        // Limite do PDF: 14400 pt por lado.
        val s = min(1f, min(14400f / w, 14400f / h))
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(max(1, (w * s).toInt()), max(1, (h * s).toInt()), 1).create())
        val c = page.canvas
        c.drawColor(canvasView.paperColor)
        c.scale(s, s)
        c.translate(pad - content.left, pad - content.top)
        canvasView.drawContent(c, null)
        doc.finishPage(page)
        val name = fileName("pdf")
        Library.io.execute {
            val ok = try {
                writeToMediaStore(MediaStore.Downloads.EXTERNAL_CONTENT_URI, name, "application/pdf",
                    Environment.DIRECTORY_DOWNLOADS + "/NotasInfinitas") { out -> doc.writeTo(out); true }
            } catch (e: Exception) {
                false
            } finally {
                doc.close()
            }
            main.post { toast(if (ok) "PDF salvo em Downloads/NotasInfinitas" else "Não foi possível salvar o PDF.") }
        }
    }

    private fun fileName(ext: String): String {
        val safe = note.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "nota" }
        return safe + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext
    }

    private fun writeToMediaStore(
        collection: Uri,
        name: String,
        mime: String,
        relativePath: String,
        write: (java.io.OutputStream) -> Boolean,
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: return false
        val ok = contentResolver.openOutputStream(uri)?.use(write) ?: false
        if (!ok) {
            contentResolver.delete(uri, null, null)
            return false
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_NOTE = "note"
        private const val REQ_IMAGE = 41
    }
}
