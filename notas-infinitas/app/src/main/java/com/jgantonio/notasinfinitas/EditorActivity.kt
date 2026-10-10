package com.jgantonio.notasinfinitas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jgantonio.notasinfinitas.Ui.SheetItem
import com.jgantonio.notasinfinitas.Ui.actionSheet
import com.jgantonio.notasinfinitas.Ui.buttonRow
import com.jgantonio.notasinfinitas.Ui.primaryButton
import com.jgantonio.notasinfinitas.Ui.secondaryButton
import com.jgantonio.notasinfinitas.Ui.segmented
import com.jgantonio.notasinfinitas.Ui.sheet
import com.jgantonio.notasinfinitas.Ui.circle
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.icon
import com.jgantonio.notasinfinitas.Ui.iconButton
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.pill
import com.jgantonio.notasinfinitas.Ui.pillButton
import com.jgantonio.notasinfinitas.Ui.promptText
import com.jgantonio.notasinfinitas.Ui.ripple
import com.jgantonio.notasinfinitas.Ui.switchRow
import com.jgantonio.notasinfinitas.Ui.vertical
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
    internal val canvasForTests get() = canvasView

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var undoButton: View
    private lateinit var redoButton: View
    private lateinit var dock: LinearLayout
    private lateinit var trayLayer: FrameLayout
    private lateinit var selectionBar: View
    private lateinit var selectionLabel: TextView
    private lateinit var zoomLabel: TextView

    private val toolButtons = LinkedHashMap<InfiniteCanvasView.Tool, ImageView>()
    private lateinit var penToolButton: ImageView
    private lateinit var colorDot: ColorDot
    private lateinit var favoritesBox: LinearLayout
    private var openTray: String? = null

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

        val stage = FrameLayout(this)
        stage.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        zoomLabel = TextView(this).apply {
            textSize = 12f
            typeface = Ui.MEDIUM
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            minHeight = dpi(32f)
            setPadding(dpi(12f), 0, dpi(12f), 0)
            background = ripple(pill(Color.parseColor("#F2FFFFFF")), pill(Color.WHITE, 0))
            elevation = dp(2f)
            setOnClickListener { showZoomMenu() }
        }
        stage.addView(zoomLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dpi(14f), 0, 0, dpi(14f))
        })

        selectionBar = buildSelectionBar()
        selectionBar.visibility = View.GONE
        stage.addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(18f)
        })

        trayLayer = FrameLayout(this).apply { visibility = View.GONE }
        stage.addView(trayLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dock = buildDock()
        stage.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dpi(10f)
        })

        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        NoteStorage.load(Library.noteFile(note.id))?.let { canvasView.load(it) }
        refresh()
        intent.getStringExtra(EXTRA_PDF)?.let { u ->
            intent.removeExtra(EXTRA_PDF)
            // Espera a tela ter tamanho para enquadrar as páginas.
            canvasView.post { importPdf(Uri.parse(u), PdfImporter.Layout.VERTICAL) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::canvasView.isInitialized) save()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            openTray != null -> closeTray()
            canvasView.hasSelection -> canvasView.clearSelection()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun applyPrefs() {
        canvasView.fingerDraws = prefs.fingerDraws
        canvasView.autoShapes = prefs.autoShapes
        canvasView.spenButtonErases = prefs.spenButtonErases
        canvasView.eraserArea = prefs.eraserArea
        canvasView.eraserSize = prefs.eraserSize
        canvasView.eraserHighlighterOnly = prefs.eraserHighlighterOnly
    }

    // ---- Barra superior ------------------------------------------------------------

    private fun buildTopBar(): View {
        val bar = horizontal().apply {
            setPadding(dpi(4f), dpi(6f), dpi(6f), dpi(6f))
            setBackgroundColor(Ui.SURFACE)
            elevation = dp(1f)
        }
        bar.addView(iconButton(Icon.BACK) { finish() })
        val titles = vertical().apply {
            setPadding(dpi(4f), 0, dpi(8f), 0)
            background = ripple(null, Ui.run { rounded(Color.WHITE, 12f) })
            setOnClickListener { rename() }
        }
        titleView = label("", 17f, Ui.INK, bold = true).apply { isSingleLine = true }
        subtitleView = label("", 12f).apply { isSingleLine = true }
        titles.addView(titleView)
        titles.addView(subtitleView)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoButton = iconButton(Icon.UNDO) { canvasView.undo() }
        redoButton = iconButton(Icon.REDO) { canvasView.redo() }
        bar.addView(undoButton)
        bar.addView(redoButton)
        bar.addView(iconButton(Icon.MORE) { showMoreMenu() })
        return bar
    }

    // ---- Dock de ferramentas ------------------------------------------------------------

    private fun toolButton(d: Drawable, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(46f), dpi(46f)).apply { setMargins(dpi(1f), 0, dpi(1f), 0) }
    }

    private fun dockDivider() = View(this).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(dpi(1f), dpi(24f)).apply { setMargins(dpi(6f), 0, dpi(6f), 0) }
    }

    private fun buildDock(): LinearLayout {
        val bar = horizontal().apply {
            setPadding(dpi(6f), dpi(5f), dpi(6f), dpi(5f))
            background = pill(Ui.SURFACE, Color.parseColor("#EEF0F3"))
            elevation = dp(8f)
            isClickable = true
        }
        penToolButton = toolButton(PenGlyphDrawable(canvasView.pen.type, canvasView.pen.color, dpi(30f), dp(1f))) {
            if (canvasView.tool == InfiniteCanvasView.Tool.PEN) toggleTray("pen") else setTool(InfiniteCanvasView.Tool.PEN)
        }
        toolButtons[InfiniteCanvasView.Tool.PEN] = penToolButton
        bar.addView(penToolButton)
        toolButtons[InfiniteCanvasView.Tool.ERASER] = toolButton(icon(Icon.ERASER)) {
            if (canvasView.tool == InfiniteCanvasView.Tool.ERASER) toggleTray("eraser") else setTool(InfiniteCanvasView.Tool.ERASER)
        }.also { bar.addView(it) }
        toolButtons[InfiniteCanvasView.Tool.SELECT] = toolButton(icon(Icon.LASSO)) { setTool(InfiniteCanvasView.Tool.SELECT) }.also { bar.addView(it) }
        toolButtons[InfiniteCanvasView.Tool.TEXT] = toolButton(icon(Icon.TEXT)) { setTool(InfiniteCanvasView.Tool.TEXT) }.also { bar.addView(it) }
        bar.addView(toolButton(icon(Icon.IMAGE)) { closeTray(); showInsertMenu() })
        bar.addView(dockDivider())
        colorDot = ColorDot(this, canvasView.pen.color, false) {
            if (canvasView.tool != InfiniteCanvasView.Tool.PEN) setTool(InfiniteCanvasView.Tool.PEN)
            toggleTray("pen")
        }
        bar.addView(colorDot, LinearLayout.LayoutParams(dpi(30f), dpi(30f)).apply { setMargins(dpi(6f), 0, dpi(6f), 0) })
        favoritesBox = horizontal()
        bar.addView(favoritesBox)
        return bar
    }

    private fun refresh() {
        titleView.text = note.title
        subtitleView.text = Library.pathOf(note.folder)
        val tool = canvasView.tool
        val pen = canvasView.pen
        penToolButton.setImageDrawable(PenGlyphDrawable(pen.type, pen.color, dpi(30f), dp(1f)))
        for ((t, b) in toolButtons) {
            val sel = t == tool
            b.background = if (sel) circle(Ui.ACCENT_SOFT, 0, 0f) else ripple(null, circle(Color.WHITE, 0, 0f))
            (b.drawable as? IconDrawable)?.color = if (sel) Ui.ACCENT else Ui.INK
        }
        colorDot.visibility = if (tool == InfiniteCanvasView.Tool.PEN) View.VISIBLE else View.GONE
        colorDot.color = pen.color

        favoritesBox.removeAllViews()
        val favs = prefs.favorites.asReversed().take(3)
        if (favs.isNotEmpty()) favoritesBox.addView(dockDivider())
        for (f in favs) {
            val sel = tool == InfiniteCanvasView.Tool.PEN && f == pen
            favoritesBox.addView(toolButton(PenGlyphDrawable(f.type, f.color, dpi(28f), dp(1f))) { setPen(f) }.apply {
                background = if (sel) circle(Ui.ACCENT_SOFT, 0, 0f) else ripple(null, circle(Color.WHITE, 0, 0f))
                layoutParams = LinearLayout.LayoutParams(dpi(42f), dpi(42f))
            })
        }
        onStateChanged()
    }

    private fun setTool(tool: InfiniteCanvasView.Tool) {
        closeTray()
        canvasView.tool = tool
        refresh()
    }

    private fun setPen(p: PenSettings) {
        prefs.savePen(p)
        prefs.currentType = p.type
        canvasView.pen = p
        canvasView.tool = InfiniteCanvasView.Tool.PEN
        closeTray()
        refresh()
    }

    // ---- Bandejas ----------------------------------------------------------------------

    private fun toggleTray(which: String) {
        if (openTray == which) closeTray() else showTray(which)
    }

    fun openPenTray() = showTray("pen")
    fun openEraserTray() = showTray("eraser")

    @SuppressLint("ClickableViewAccessibility")
    private fun showTray(which: String) {
        trayLayer.removeAllViews()
        // Toque fora da bandeja fecha a bandeja (e não risca a nota).
        trayLayer.addView(View(this).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) closeTray()
                true
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val tray: View = when (which) {
            "pen" -> PenTray(this, prefs, canvasView.pen, onChange = { p ->
                canvasView.pen = p
                applyPrefs()
                refresh()
            }, onFavoritesChanged = { refresh() })
            else -> EraserTray(this, prefs, onChange = { applyPrefs() }, onClearAll = {
                canvasView.clearAll()
                closeTray()
            })
        }
        val width = min(resources.displayMetrics.widthPixels - dpi(24f), dpi(440f))
        trayLayer.addView(tray, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dpi(74f)
        })
        trayLayer.visibility = View.VISIBLE
        openTray = which
    }

    private fun closeTray() {
        trayLayer.visibility = View.GONE
        trayLayer.removeAllViews()
        openTray = null
    }

    // ---- Barra de seleção ----------------------------------------------------------------

    private lateinit var selectionRow: LinearLayout

    private fun buildSelectionBar(): View {
        selectionRow = horizontal().apply { setPadding(dpi(14f), dpi(4f), dpi(6f), dpi(4f)) }
        selectionLabel = label("", 13f, Ui.ACCENT, bold = true)
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = pill(Ui.SURFACE, Color.parseColor("#EEF0F3"))
            elevation = dp(10f)
            isClickable = true
            addView(selectionRow)
        }
        return scroll
    }

    /** Monta os botões conforme o que está selecionado (imagem, item travado, traços...). */
    private fun fillSelectionBar() {
        val row = selectionRow
        row.removeAllViews()
        (selectionLabel.parent as? ViewGroup)?.removeView(selectionLabel)
        if (canvasView.inCropMode) {
            fillCropBar(row)
            return
        }
        val items = canvasView.selectedItems
        val img = canvasView.selectedImage
        selectionLabel.text = when {
            img != null && img.pdfPage > 0 -> "Página ${img.pdfPage}"
            img != null -> "Imagem"
            items.size == 1 -> "1 item"
            else -> "${items.size} itens"
        }
        row.addView(selectionLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpi(6f)
        })
        if (canvasView.selectionLocked) {
            row.addView(pillButton(Icon.UNLOCK, "Destravar") { canvasView.setLocked(false) })
            row.addView(pillButton(Icon.TRASH, "Excluir") { canvasView.deleteSelection() })
        } else if (img != null) {
            row.addView(pillButton(Icon.CROP, "Recortar") { canvasView.startCrop() })
            row.addView(pillButton(Icon.ADJUST, "Ajustes") { ImageTools.showAdjustments(this, canvasView) })
            row.addView(pillButton(Icon.ROTATE, "Girar 90°") { canvasView.rotateSelection(90f) })
            row.addView(pillButton(Icon.FLIP_H, "Espelhar") { canvasView.editImages { it.with(flipH = !it.flipH) } })
            row.addView(pillButton(Icon.FLIP_V, "Virar") { canvasView.editImages { it.with(flipV = !it.flipV) } })
            row.addView(pillButton(Icon.TO_FRONT, "Frente") { canvasView.reorderSelection(true) })
            row.addView(pillButton(Icon.TO_BACK, "Trás") { canvasView.reorderSelection(false) })
            row.addView(pillButton(Icon.LOCK, "Travar") { canvasView.setLocked(true) })
            row.addView(pillButton(Icon.COPY, "Duplicar") { canvasView.duplicateSelection() })
            row.addView(pillButton(Icon.IMAGE, "Substituir") { pickImage(REQ_REPLACE) })
            row.addView(pillButton(Icon.DOWNLOAD, "Salvar") { saveSelectedImage() })
            row.addView(pillButton(Icon.RESET, "Original") {
                canvasView.editImages {
                    ImageElement(it.file, RectF(it.rect), locked = false).let { base ->
                        // Volta ao arquivo original inteiro, mantendo o centro e a largura atual.
                        val full = it.fullLocalFrame()
                        val w = full.width()
                        val h = full.height()
                        base.with(rect = RectF(it.centerX - w / 2, it.centerY - h / 2, it.centerX + w / 2, it.centerY + h / 2))
                    }
                }
            })
            row.addView(pillButton(Icon.TRASH, "Excluir") { canvasView.deleteSelection() })
        } else {
            row.addView(pillButton(Icon.TRASH, "Excluir") { canvasView.deleteSelection() })
            row.addView(pillButton(Icon.COPY, "Duplicar") { canvasView.duplicateSelection() })
            row.addView(pillButton(Icon.PALETTE, "Cor") {
                Panels.showColorChoice(this, prefs, canvasView.pen.color) { canvasView.recolorSelection(it) }
            })
            row.addView(pillButton(Icon.ROTATE, "Girar 90°") { canvasView.rotateSelection(90f) })
            row.addView(pillButton(Icon.TO_FRONT, "Frente") { canvasView.reorderSelection(true) })
            row.addView(pillButton(Icon.TO_BACK, "Trás") { canvasView.reorderSelection(false) })
            if (items.any { it is ImageElement }) row.addView(pillButton(Icon.LOCK, "Travar imagens") { canvasView.setLocked(true) })
        }
        row.addView(iconButton(Icon.CHECK, Ui.ACCENT, 40f) { canvasView.clearSelection() })
    }

    private fun fillCropBar(row: LinearLayout) {
        val shapes = listOf(
            InfiniteCanvasView.CropShape.RECT to "Livre",
            InfiniteCanvasView.CropShape.SQUARE to "1:1",
            InfiniteCanvasView.CropShape.WIDE to "16:9",
            InfiniteCanvasView.CropShape.ELLIPSE to "Círculo",
            InfiniteCanvasView.CropShape.LASSO to "Mão livre",
        )
        for ((shape, name) in shapes) {
            val sel = canvasView.cropShape == shape
            row.addView(TextView(this).apply {
                text = name
                textSize = 13f
                typeface = Ui.MEDIUM
                gravity = Gravity.CENTER
                minHeight = dpi(36f)
                setPadding(dpi(12f), 0, dpi(12f), 0)
                setTextColor(if (sel) Ui.ACCENT else Ui.INK)
                background = ripple(pill(if (sel) Ui.ACCENT_SOFT else Color.TRANSPARENT, 0), pill(Color.WHITE, 0))
                setOnClickListener {
                    canvasView.setCropShape(shape)
                    fillSelectionBar()
                    if (shape == InfiniteCanvasView.CropShape.LASSO) toast("Contorne com a caneta a parte que quer manter.")
                }
            })
        }
        row.addView(pillButton(Icon.RESET, "Tudo") { canvasView.resetCrop(); fillSelectionBar() })
        row.addView(iconButton(Icon.CLOSE, Ui.MUTED, 40f) { canvasView.cancelCrop() })
        row.addView(TextView(this).apply {
            text = "Aplicar"
            textSize = 14f
            typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            minHeight = dpi(38f)
            setPadding(dpi(16f), 0, dpi(16f), 0)
            background = ripple(pill(Ui.ACCENT, 0), pill(Color.WHITE, 0), Color.parseColor("#33FFFFFF"))
            setOnClickListener { canvasView.applyCrop() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dpi(4f)
            marginEnd = dpi(4f)
        })
    }

    private fun saveSelectedImage() {
        val img = canvasView.selectedImage ?: return
        Library.io.execute {
            val bmp = try { canvasView.images.export(img) } catch (e: Exception) { null }
            val ok = bmp != null && try {
                writeToMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileName("png"), "image/png",
                    Environment.DIRECTORY_PICTURES + "/NotasInfinitas") { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } catch (e: Exception) {
                false
            }
            bmp?.recycle()
            main.post { toast(if (ok) "Imagem salva em Imagens/NotasInfinitas" else "Não foi possível salvar a imagem.") }
        }
    }

    // ---- Zoom -------------------------------------------------------------------------

    private fun showZoomMenu() {
        actionSheet("Zoom: ${canvasView.zoomPercent}%", listOf(
            SheetItem(Icon.CENTER, "Ajustar à tela (ver tudo)") { canvasView.recenter() },
            SheetItem(Icon.ZOOM, "100% (tamanho real)") { canvasView.zoomTo(1f) },
            SheetItem(Icon.ZOOM, "50%") { canvasView.zoomTo(0.5f) },
            SheetItem(Icon.ZOOM, "200%") { canvasView.zoomTo(2f) },
            SheetItem(Icon.ZOOM, "400%") { canvasView.zoomTo(4f) },
        ))
    }

    // ---- Menu ⋮ --------------------------------------------------------------------

    fun showMoreMenu() {
        closeTray()
        val finger = switchRow(Icon.TOUCH, "Escrever com o dedo", prefs.fingerDraws) {
            prefs.fingerDraws = it
            applyPrefs()
        }
        actionSheet(null, listOf(
            SheetItem(Icon.PAGE, "Plano de fundo") {
                Panels.showBackground(this, canvasView.pageStyle, canvasView.paperColor) { s, c ->
                    canvasView.pageStyle = s
                    canvasView.paperColor = c
                }
            },
            SheetItem(Icon.PDF, "Importar PDF") { pickPdf() },
            SheetItem(Icon.SELECT_ALL, "Selecionar tudo") { canvasView.selectAll(); refresh() },
            SheetItem(Icon.EXPORT, "Exportar como imagem") { exportPng() },
            SheetItem(Icon.PDF, "Exportar como PDF") { exportPdf() },
            SheetItem(Icon.CENTER, "Centralizar conteúdo") { canvasView.recenter() },
            SheetItem(Icon.EDIT, "Renomear nota") { rename() },
            SheetItem(Icon.MOVE, "Mover para outra pasta") { moveToFolder() },
        ), header = finger)
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
        Pickers.folder(this, "Mover para", allowRoot = false, exclude = null, current = note.folder) { target ->
            if (target != null) {
                Library.updateNote(note, folder = target)
                refresh()
                toast("Nota movida para ${Library.folder(target)?.name}")
            }
        }
    }

    // ---- Listener da tela ---------------------------------------------------------

    override fun onStateChanged() {
        undoButton.alpha = if (canvasView.canUndo) 1f else 0.3f
        redoButton.alpha = if (canvasView.canRedo) 1f else 0.3f
        zoomLabel.text = "${canvasView.zoomPercent}%"
    }

    override fun onSelectionChanged(count: Int) {
        selectionBar.visibility = if (count > 0 || canvasView.inCropMode) View.VISIBLE else View.GONE
        if (selectionBar.visibility == View.VISIBLE) fillSelectionBar()
        refresh()
    }

    override fun onCropModeChanged(active: Boolean) {
        onSelectionChanged(canvasView.selectedItems.size)
    }

    override fun onTextRequest(x: Float, y: Float, existing: TextElement?) {
        Panels.showText(this, existing, density, onDone = { text, size ->
            val color = canvasView.pen.color
            if (existing == null) canvasView.addText(x, y, text, size, color)
            else canvasView.replaceText(existing, text, size, existing.color)
        }, onDelete = existing?.let { e -> { canvasView.replaceText(e, "", e.size, e.color) } })
    }

    // ---- Imagem -----------------------------------------------------------------

    private fun showInsertMenu() {
        actionSheet("Inserir", listOf(
            SheetItem(Icon.IMAGE, "Imagem da galeria") { pickImage(REQ_IMAGE) },
            SheetItem(Icon.PDF, "PDF (todas as páginas)") { pickPdf() },
        ))
    }

    private fun pickImage(request: Int) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Escolha uma imagem"), request)
    }

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PDF)
    }

    @Deprecated("Activity sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PDF -> askPdfLayout(uri)
            REQ_IMAGE, REQ_REPLACE -> Library.io.execute {
                val result = try { importImage(uri) } catch (e: Exception) { null }
                main.post {
                    if (result == null) toast("Não foi possível abrir a imagem.")
                    else if (requestCode == REQ_REPLACE) canvasView.replaceSelectedImage(result.first, result.second, result.third)
                    else {
                        canvasView.addImage(result.first, result.second, result.third)
                        refresh()
                    }
                }
            }
        }
    }

    // ---- PDF ----------------------------------------------------------------------------

    /** Pergunta como organizar as páginas e importa. */
    private fun askPdfLayout(uri: Uri) {
        val name = PdfImporter.displayName(this, uri) ?: "PDF"
        var layout = PdfImporter.Layout.VERTICAL
        Ui.run {
            sheet("Importar PDF") { box, dialog ->
                box.addView(label(name, 14f).apply { setPadding(0, 0, 0, dpi(12f)) })
                box.addView(label("Como organizar as páginas?", 15f, Ui.INK))
                box.addView(segmented(PdfImporter.Layout.entries.map { it.label }, 0) { i ->
                    layout = PdfImporter.Layout.entries[i]
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpi(10f)
                })
                box.addView(label("As páginas entram travadas, como fundo, para você escrever por cima. " +
                    "Toque numa página com a seleção para destravar.", 13f).apply { setPadding(0, dpi(12f), 0, 0) })
                box.addView(buttonRow(
                    secondaryButton("Cancelar") { dialog.dismiss() },
                    primaryButton("Importar") { dialog.dismiss(); importPdf(uri, layout) },
                ))
            }
        }
    }

    private fun importPdf(uri: Uri, layout: PdfImporter.Layout) {
        var cancelled = false
        lateinit var status: TextView
        lateinit var bar: android.widget.ProgressBar
        val dialog = Ui.run {
            sheet("Importando PDF") { box, d ->
                status = label("Preparando…", 15f, Ui.INK)
                box.addView(status)
                bar = android.widget.ProgressBar(this@EditorActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = false
                    max = 100
                    progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                }
                box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(24f)).apply { topMargin = dpi(8f) })
                box.addView(buttonRow(secondaryButton("Cancelar") { cancelled = true; d.dismiss() }))
            }
        }
        dialog.setCancelable(false)
        val (x0, y0) = canvasView.freeSpotBelow()
        val dir = Library.assetsDir(note.id)
        Thread {
            val result = try {
                PdfImporter.render(this, uri, dir, progress = { i, n ->
                    main.post {
                        status.text = "Página $i de $n"
                        bar.progress = i * 100 / n
                    }
                }, cancelled = { cancelled })
            } catch (e: SecurityException) {
                main.post { toast("Esse PDF tem senha e não pode ser aberto.") }
                null
            } catch (e: Exception) {
                main.post { toast("Não foi possível abrir o PDF.") }
                null
            }
            main.post {
                if (dialog.isShowing) dialog.dismiss()
                if (!result.isNullOrEmpty()) {
                    canvasView.addPages(PdfImporter.layout(result, layout, x0, y0))
                    toast(if (result.size == 1) "1 página importada" else "${result.size} páginas importadas")
                    refresh()
                }
            }
        }.start()
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
        const val EXTRA_PDF = "pdf"
        private const val REQ_IMAGE = 41
        private const val REQ_REPLACE = 42
        private const val REQ_PDF = 43
    }
}
