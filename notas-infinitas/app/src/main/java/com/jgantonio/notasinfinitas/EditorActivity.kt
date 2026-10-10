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
    internal val textEditorForTests get() = textEditor
    internal val dockForTests get() = dock
    internal val dockGripForTests get() = dockGrip
    internal val quickColorsForTests get() = quickColorsBox
    internal val prefsForTests get() = prefs
    internal fun relayoutDockForTests() = layoutDock()
    internal fun closeTrayForTests() = closeTray()

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var undoButton: View
    private lateinit var redoButton: View
    private lateinit var dock: LinearLayout
    private lateinit var trayLayer: FrameLayout
    private lateinit var selectionBar: View
    private lateinit var selectionLabel: TextView
    private lateinit var zoomLabel: TextView

    private lateinit var textEditor: InlineTextEditor
    private lateinit var stage: FrameLayout

    private val toolButtons = LinkedHashMap<InfiniteCanvasView.Tool, ImageView>()
    private lateinit var penToolButton: ImageView
    private lateinit var colorDot: ColorDot
    private lateinit var favoritesBox: LinearLayout
    private lateinit var colorDivider: View
    private lateinit var emptyHint: View
    private lateinit var hintTitle: TextView
    private lateinit var hintBody: TextView
    private var openTray: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Library.init(this)
        val n = Library.note(intent.getStringExtra(EXTRA_NOTE))
        if (n == null) {
            finish()
            return
        }
        note = n
        prefs = PenPrefs(this)
        requestMaxRefreshRate()

        canvasView = InfiniteCanvasView(this)
        canvasView.listener = this
        canvasView.assetDir = Library.assetsDir(note.id)
        canvasView.pen = prefs.pen(prefs.currentType)
        applyPrefs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }
        // Barras do sistema e teclado: a área da nota encolhe quando o teclado abre,
        // para a barra de formatação de texto ficar logo acima dele.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(buildTopBar())

        stage = FrameLayout(this)
        stage.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Camadas da tinta em andamento (baixa latência): ficam logo acima da nota.
        val live = LiveInkView(this, canvasView)
        stage.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        canvasView.liveInk = live
        if (prefs.lowLatency) {
            val wet = WetInkLayer(this)
            stage.addView(wet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            canvasView.wetInk = wet
        }

        zoomLabel = TextView(this).apply {
            textSize = 12f
            typeface = Ui.MEDIUM
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            minHeight = dpi(32f)
            setPadding(dpi(12f), 0, dpi(12f), 0)
            background = ripple(pill(Color.parseColor("#F2FFFFFF")), pill(Color.WHITE, 0))
            fontFeatureSettings = "tnum"
            Ui.run { softShadow(2f) }
            contentDescription = "Zoom"
            setOnClickListener { showZoomMenu() }
        }
        stage.addView(zoomLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dpi(14f), 0, 0, dpi(14f))
        })

        emptyHint = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            addView(ImageView(this@EditorActivity).apply {
                setImageDrawable(PenGlyphDrawable(BrushType.FOUNTAIN, Ui.ACCENT, dpi(56f), dp(1.2f)))
            }, LinearLayout.LayoutParams(dpi(56f), dpi(56f)))
            hintTitle = label("", 17f, Ui.INK, bold = true).apply { setPadding(0, dpi(12f), 0, dpi(4f)) }
            hintBody = label("", 14f).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }
            addView(hintTitle)
            addView(hintBody)
        }
        stage.addView(emptyHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        selectionBar = buildSelectionBar()
        selectionBar.visibility = View.GONE
        stage.addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dpi(12f), 0, dpi(12f), dpi(18f))
        })

        trayLayer = FrameLayout(this).apply { visibility = View.GONE }
        stage.addView(trayLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dock = buildDock()
        stage.addView(dock)

        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        textEditor = InlineTextEditor(this, stage, canvasView,
            defaultColor = { canvasView.pen.color.takeIf { canvasView.pen.type != BrushType.HIGHLIGHTER } ?: Ui.INK },
            onFinished = { refresh() })
        stage.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && textEditor.isActive) stage.post { textEditor.keepVisible() }
        }

        val saved = NoteStorage.load(Library.noteFile(note.id))
        if (saved != null) canvasView.load(saved)
        else PageLayout.decodePref(prefs.defaultPageLayout)?.takeIf { it.paged }?.let { l ->
            // Nota nova: começa no último modo de página escolhido.
            canvasView.setPageLayout(true, l.horizontal, l.landscape, l.endless, 1)
        }
        layoutDock()
        // A barra solta depende do tamanho da área da nota.
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l != or - ol || b - t != ob - ot) && prefs.dockMode == "free") stage.post { layoutDock() }
        }
        intent.getStringExtra(EXTRA_PDF)?.let { u ->
            intent.removeExtra(EXTRA_PDF)
            // Espera a tela ter tamanho para enquadrar as páginas.
            canvasView.post { importPdf(Uri.parse(u), PdfImporter.Layout.VERTICAL) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::textEditor.isInitialized && textEditor.isActive) textEditor.finish()
        if (::canvasView.isInitialized) save()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            textEditor.isActive -> textEditor.finish()
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
        canvasView.lowLatency = prefs.lowLatency
        canvasView.selectRect = prefs.selectRect
        canvasView.selectPartial = prefs.selectPartial
    }

    /** Pede a maior taxa de atualização da tela (ex.: 120 Hz): a tinta acompanha a caneta mais de perto. */
    private fun requestMaxRefreshRate() {
        try {
            @Suppress("DEPRECATION")
            val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display else windowManager.defaultDisplay
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        } catch (_: Throwable) {
        }
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
        titleView = label("", 19f, Ui.INK, bold = true).apply {
            typeface = Ui.DISPLAY
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = label("", 12f).apply { isSingleLine = true }
        titles.addView(titleView)
        titles.addView(subtitleView)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoButton = iconButton(Icon.UNDO) { finishTyping(); canvasView.undo() }
        redoButton = iconButton(Icon.REDO) { finishTyping(); canvasView.redo() }
        bar.addView(undoButton)
        bar.addView(redoButton)
        bar.addView(iconButton(Icon.MORE) { finishTyping(); showMoreMenu() })
        return bar
    }

    private fun finishTyping() {
        if (::textEditor.isInitialized && textEditor.isActive) textEditor.finish()
    }

    // ---- Dock de ferramentas ------------------------------------------------------------
    //
    // A barra flutua sobre a nota e pode ser arrastada pela alça (⋮⋮) para qualquer lugar.
    // Solta perto da borda esquerda ou direita, ela fica em pé, como no Samsung Notes,
    // e mostra 3 cores rápidas (toque longo numa cor para trocá-la).

    private lateinit var dockGrip: ImageView
    private lateinit var quickColorsBox: LinearLayout
    private val toolsInDock = ArrayList<View>()
    private val dockVertical get() = prefs.dockMode == "left" || prefs.dockMode == "right"

    private fun toolButton(d: Drawable, desc: String = "", onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc.ifEmpty { "Caneta" }
        Ui.run { pressable(0.9f) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(46f), dpi(46f)).apply { setMargins(dpi(1f), 0, dpi(1f), 0) }
    }

    private fun dockDivider() = View(this).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(dpi(1f), dpi(24f)).apply { setMargins(dpi(6f), 0, dpi(6f), 0) }
    }

    /** Divisória que acompanha a orientação da barra. */
    private fun orientDivider(v: View) {
        v.layoutParams = if (dockVertical) LinearLayout.LayoutParams(dpi(24f), dpi(1f)).apply { setMargins(0, dpi(6f), 0, dpi(6f)) }
        else LinearLayout.LayoutParams(dpi(1f), dpi(24f)).apply { setMargins(dpi(6f), 0, dpi(6f), 0) }
    }

    private fun buildDock(): LinearLayout {
        val bar = horizontal().apply {
            setPadding(dpi(4f), dpi(5f), dpi(6f), dpi(5f))
            background = pill(Ui.SURFACE, Color.parseColor("#EEF0F3"))
            Ui.run { softShadow(10f) }
            isClickable = true
        }
        dockGrip = ImageView(this).apply {
            setImageDrawable(IconDrawable(Icon.GRIP, Color.parseColor("#A39E94"), dpi(20f), dp(1.6f)))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Arrastar barra (toque para mais opções)"
            setOnTouchListener(DockDrag())
        }
        bar.addView(dockGrip)
        penToolButton = toolButton(PenGlyphDrawable(canvasView.pen.type, canvasView.pen.color, dpi(30f), dp(1f))) {
            if (canvasView.tool == InfiniteCanvasView.Tool.PEN) toggleTray("pen") else setTool(InfiniteCanvasView.Tool.PEN)
        }
        toolButtons[InfiniteCanvasView.Tool.PEN] = penToolButton
        toolButtons[InfiniteCanvasView.Tool.ERASER] = toolButton(icon(Icon.ERASER), "Borracha") {
            if (canvasView.tool == InfiniteCanvasView.Tool.ERASER) toggleTray("eraser") else setTool(InfiniteCanvasView.Tool.ERASER)
        }
        toolButtons[InfiniteCanvasView.Tool.SELECT] = toolButton(icon(if (prefs.selectRect) Icon.RECT_SELECT else Icon.LASSO), "Seleção") {
            if (canvasView.tool == InfiniteCanvasView.Tool.SELECT) toggleTray("select") else setTool(InfiniteCanvasView.Tool.SELECT)
        }
        toolButtons[InfiniteCanvasView.Tool.TEXT] = toolButton(icon(Icon.TEXT), "Texto") { setTool(InfiniteCanvasView.Tool.TEXT) }
        toolsInDock.clear()
        toolsInDock.addAll(toolButtons.values)
        toolsInDock.add(toolButton(icon(Icon.IMAGE), "Inserir imagem ou PDF") { finishTyping(); closeTray(); showInsertMenu() })
        toolsInDock.forEach { bar.addView(it) }
        colorDivider = dockDivider()
        bar.addView(colorDivider)
        colorDot = ColorDot(this, canvasView.pen.color, false) {
            if (canvasView.tool != InfiniteCanvasView.Tool.PEN) setTool(InfiniteCanvasView.Tool.PEN)
            toggleTray("pen")
        }.apply { contentDescription = "Cor e caneta" }
        bar.addView(colorDot, LinearLayout.LayoutParams(dpi(30f), dpi(30f)).apply { setMargins(dpi(6f), 0, dpi(6f), 0) })
        quickColorsBox = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        bar.addView(quickColorsBox)
        favoritesBox = horizontal()
        bar.addView(favoritesBox)
        return bar
    }

    /** Aplica a orientação e o lugar da barra conforme [PenPrefs.dockMode]. */
    private fun layoutDock(animateFrom: Pair<Float, Float>? = null) {
        val vertical = dockVertical
        dock.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        dock.setPadding(if (vertical) dpi(5f) else dpi(4f), if (vertical) dpi(4f) else dpi(5f), dpi(5f), if (vertical) dpi(8f) else dpi(5f))
        dock.background = pill(Ui.SURFACE, Color.parseColor("#EEF0F3"), if (vertical) 30f else 100f)
        dockGrip.layoutParams = if (vertical) LinearLayout.LayoutParams(dpi(46f), dpi(24f)) else LinearLayout.LayoutParams(dpi(24f), dpi(46f))
        val toolLp = { LinearLayout.LayoutParams(dpi(46f), dpi(46f)).apply {
            if (vertical) setMargins(0, dpi(1f), 0, dpi(1f)) else setMargins(dpi(1f), 0, dpi(1f), 0)
        } }
        toolsInDock.forEach { it.layoutParams = toolLp() }
        orientDivider(colorDivider)
        favoritesBox.orientation = dock.orientation
        quickColorsBox.visibility = if (vertical) View.VISIBLE else View.GONE

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val edge = dpi(8f)
        when (prefs.dockMode) {
            "left" -> { lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL; lp.marginStart = edge }
            "right" -> { lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL; lp.marginEnd = edge }
            "free" -> {
                lp.gravity = Gravity.TOP or Gravity.START
                dock.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val w = dock.measuredWidth
                val h = dock.measuredHeight
                val sw = stage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val sh = stage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                lp.leftMargin = (prefs.dockX * sw - w / 2f).toInt().coerceIn(edge, max(edge, sw - w - edge))
                lp.topMargin = (prefs.dockY * sh - h / 2f).toInt().coerceIn(edge, max(edge, sh - h - edge))
            }
            else -> { lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.topMargin = dpi(10f) }
        }
        dock.layoutParams = lp
        dock.translationX = 0f
        dock.translationY = 0f
        if (animateFrom != null) {
            // Desliza do ponto onde foi solta até o lugar final.
            dock.post {
                dock.translationX = animateFrom.first - dock.left
                dock.translationY = animateFrom.second - dock.top
                if (Ui.animationsOn()) dock.animate().translationX(0f).translationY(0f).setDuration(260).setInterpolator(Ui.EASE).start()
                else { dock.translationX = 0f; dock.translationY = 0f }
            }
        }
        refresh()
    }

    /** Arrastar a barra pela alça; um toque simples abre as opções de posição. */
    private inner class DockDrag : View.OnTouchListener {
        private var rawX0 = 0f
        private var rawY0 = 0f
        private var dragging = false
        private val slop = android.view.ViewConfiguration.get(this@EditorActivity).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX0 = e.rawX; rawY0 = e.rawY
                    dragging = false
                    dock.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - rawX0
                    val dy = e.rawY - rawY0
                    if (!dragging && kotlin.math.hypot(dx, dy) > slop) {
                        dragging = true
                        closeTray()
                        dock.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                    }
                    if (dragging) {
                        dock.translationX = dx
                        dock.translationY = dy
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) dropDock() else showDockMenu()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) dropDock()
                    dragging = false
                }
            }
            return true
        }
    }

    private fun dropDock() {
        dock.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        val left = dock.left + dock.translationX
        val top = dock.top + dock.translationY
        val cx = left + dock.width / 2f
        val cy = top + dock.height / 2f
        val sw = stage.width.toFloat()
        val sh = stage.height.toFloat()
        val zone = dp(64f)
        prefs.dockMode = when {
            cx < zone || left < dp(4f) -> "left"
            cx > sw - zone || left + dock.width > sw - dp(4f) -> "right"
            top < dp(28f) && kotlin.math.abs(cx - sw / 2f) < dp(56f) -> "top"
            else -> "free"
        }
        if (prefs.dockMode == "free") {
            prefs.dockX = (cx / sw).coerceIn(0f, 1f)
            prefs.dockY = (cy / sh).coerceIn(0f, 1f)
        }
        layoutDock(animateFrom = left to top)
    }

    private fun showDockMenu() {
        closeTray()
        fun place(mode: String) {
            prefs.dockMode = mode
            layoutDock(animateFrom = dock.left.toFloat() to dock.top.toFloat())
        }
        actionSheet("Barra de ferramentas", listOf(
            SheetItem(Icon.DOCK_TOP, "No topo (deitada)") { place("top") },
            SheetItem(Icon.DOCK_LEFT, "À esquerda (em pé, com 3 cores)") { place("left") },
            SheetItem(Icon.DOCK_RIGHT, "À direita (em pé, com 3 cores)") { place("right") },
        ), header = label("Dica: arraste pela alça ⋮⋮ para levar a barra a qualquer lugar.", 13f).apply {
            setPadding(dpi(4f), 0, 0, dpi(8f))
        })
    }

    /** As 3 cores rápidas da barra em pé. */
    private fun fillQuickColors() {
        quickColorsBox.removeAllViews()
        if (!dockVertical) return
        quickColorsBox.addView(dockDivider().also { orientDivider(it) })
        val pen = canvasView.pen
        prefs.quickColors.forEachIndexed { i, col ->
            val dot = ColorDot(this, col, canvasView.tool == InfiniteCanvasView.Tool.PEN && pen.color == col) {
                setPen(canvasView.pen.copy(color = col))
            }
            dot.contentDescription = "Cor rápida ${i + 1} (toque longo para trocar)"
            dot.setOnLongClickListener {
                ColorPicker.show(this, col) { picked ->
                    prefs.quickColors = prefs.quickColors.toMutableList().also { it[i] = picked }
                    prefs.addCustomColor(picked)
                    setPen(canvasView.pen.copy(color = picked))
                }
                true
            }
            Ui.run { dot.pressable(0.9f) }
            quickColorsBox.addView(dot, LinearLayout.LayoutParams(dpi(32f), dpi(32f)).apply { setMargins(0, dpi(4f), 0, dpi(4f)) })
        }
    }

    private fun refresh() {
        titleView.text = note.title
        subtitleView.text = Library.pathOf(note.folder)
        val tool = canvasView.tool
        val pen = canvasView.pen
        penToolButton.setImageDrawable(PenGlyphDrawable(pen.type, pen.color, dpi(30f), dp(1f)))
        toolButtons[InfiniteCanvasView.Tool.SELECT]?.setImageDrawable(icon(if (prefs.selectRect) Icon.RECT_SELECT else Icon.LASSO))
        for ((t, b) in toolButtons) {
            val sel = t == tool
            b.background = if (sel) circle(Ui.ACCENT_SOFT, 0, 0f) else ripple(null, circle(Color.WHITE, 0, 0f))
            (b.drawable as? IconDrawable)?.color = if (sel) Ui.ACCENT else Ui.INK
        }
        val vertical = dockVertical
        colorDot.visibility = if (tool == InfiniteCanvasView.Tool.PEN && !vertical) View.VISIBLE else View.GONE
        colorDivider.visibility = colorDot.visibility
        colorDot.color = pen.color
        fillQuickColors()

        favoritesBox.removeAllViews()
        // Em pé, os favoritos só aparecem se houver altura de sobra.
        val roomForFavs = !vertical || stage.height == 0 || stage.height > dpi(600f)
        val favs = if (roomForFavs) prefs.favorites.asReversed().take(3) else emptyList()
        if (favs.isNotEmpty()) favoritesBox.addView(dockDivider().also { orientDivider(it) })
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
        if (textEditor.isActive) textEditor.finish()
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
    fun openSelectTray() = showTray("select")

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

        // A bandeja abre ao lado da barra: embaixo, em cima ou ao lado (barra em pé).
        val sw = stage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val sh = stage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val gap = dpi(10f)
        val dockRight = dock.left + dock.width
        val dockBottom = dock.top + dock.height
        val width = when (prefs.dockMode) {
            "left" -> min(sw - dockRight - gap - dpi(12f), dpi(440f))
            "right" -> min(dock.left - gap - dpi(12f), dpi(440f))
            else -> min(sw - dpi(24f), dpi(440f))
        }
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dpi(12f)
        lp.topMargin = dpi(12f)
        var fromX = 0f
        var fromY = -dp(10f)
        when (prefs.dockMode) {
            "left" -> { lp.gravity = Gravity.TOP or Gravity.START; lp.marginStart = dockRight + gap; fromX = -dp(10f); fromY = 0f }
            "right" -> { lp.gravity = Gravity.TOP or Gravity.END; lp.marginEnd = sw - dock.left + gap; fromX = dp(10f); fromY = 0f }
            else -> {
                val below = dock.top + dock.height / 2 < sh / 2
                if (below) { lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.topMargin = dockBottom + gap }
                else { lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.bottomMargin = sh - dock.top + gap; fromY = dp(10f) }
            }
        }

        val tray: View = when (which) {
            "pen" -> PenTray(this, prefs, canvasView.pen, onChange = { p ->
                canvasView.pen = p
                applyPrefs()
                refresh()
            }, onFavoritesChanged = { refresh() }, widthPx = width)
            "select" -> SelectTray(this, prefs) {
                applyPrefs()
                refresh()
            }
            else -> EraserTray(this, prefs, onChange = { applyPrefs() }, onClearAll = {
                canvasView.clearAll()
                closeTray()
            })
        }
        // Bandejas altas (marca-texto, tela deitada) rolam em vez de sair da tela.
        val scroller = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            addView(tray)
        }
        trayLayer.addView(scroller, lp)
        trayLayer.visibility = View.VISIBLE
        openTray = which
        if (Ui.animationsOn()) {
            scroller.alpha = 0f
            scroller.translationX = fromX
            scroller.translationY = fromY
            scroller.scaleX = 0.98f
            scroller.scaleY = 0.98f
            scroller.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(240).setInterpolator(Ui.EASE).start()
        }
    }

    private fun closeTray() {
        trayLayer.visibility = View.GONE
        trayLayer.removeAllViews()
        openTray = null
    }

    // ---- Barra de seleção ----------------------------------------------------------------

    private lateinit var selectionRow: LinearLayout
    private lateinit var selectionTrailing: LinearLayout

    private fun buildSelectionBar(): View {
        selectionRow = horizontal().apply { setPadding(dpi(14f), 0, dpi(4f), 0) }
        selectionTrailing = horizontal().apply { setPadding(0, 0, dpi(6f), 0) }
        selectionLabel = label("", 13f, Ui.ACCENT, bold = true)
        val outer = horizontal().apply {
            background = pill(Ui.SURFACE, Color.parseColor("#EEF0F3"))
            Ui.run { softShadow(12f) }
            isClickable = true
            setPadding(0, dpi(4f), 0, dpi(4f))
        }
        outer.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(selectionRow)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(selectionTrailing)
        return outer
    }

    /** Monta os botões conforme o que está selecionado (imagem, item travado, traços...). */
    private fun fillSelectionBar() {
        val row = selectionRow
        row.removeAllViews()
        selectionTrailing.removeAllViews()
        (selectionLabel.parent as? ViewGroup)?.removeView(selectionLabel)
        if (canvasView.inCropMode) {
            fillCropBar(row)
            return
        }
        val items = canvasView.selectedItems
        val img = canvasView.selectedImage
        val txt = canvasView.selectedText
        selectionLabel.text = when {
            img != null && img.pdfPage > 0 -> "Página ${img.pdfPage}"
            img != null -> "Imagem"
            txt != null -> "Texto"
            items.size == 1 -> "1 item"
            else -> "${items.size} itens"
        }
        row.addView(selectionLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpi(6f)
        })
        if (canvasView.selectionLocked) {
            row.addView(pillButton(Icon.UNLOCK, "Destravar") { canvasView.setLocked(false) })
            row.addView(pillButton(Icon.TRASH, "Excluir") { canvasView.deleteSelection() })
        } else if (txt != null) {
            row.addView(pillButton(Icon.EDIT, "Editar") {
                canvasView.clearSelection()
                textEditor.start(txt, 0f, 0f)
            })
            row.addView(pillButton(Icon.TEXT_BIGGER, "Maior") { canvasView.editTexts { scaleText(it, 1.2f) } })
            row.addView(pillButton(Icon.TEXT_SMALLER, "Menor") { canvasView.editTexts { scaleText(it, 1f / 1.2f) } })
            row.addView(pillButton(Icon.ROTATE, "Girar 90°") { canvasView.rotateSelection(90f) })
            row.addView(pillButton(Icon.FLIP_H, "Espelhar") { canvasView.editTexts { it.with(flipH = !it.flipH) } })
            row.addView(pillButton(Icon.FLIP_V, "Virar") { canvasView.editTexts { it.with(flipV = !it.flipV) } })
            row.addView(pillButton(Icon.PALETTE, "Cor") {
                Panels.showColorChoice(this, prefs, txt.color) { canvasView.recolorSelection(it) }
            })
            row.addView(pillButton(Icon.RESET, "Endireitar") { canvasView.editTexts { it.with(rotation = 0f, flipH = false, flipV = false) } })
            row.addView(pillButton(Icon.TO_FRONT, "Frente") { canvasView.reorderSelection(true) })
            row.addView(pillButton(Icon.TO_BACK, "Trás") { canvasView.reorderSelection(false) })
            row.addView(pillButton(Icon.COPY, "Duplicar") { canvasView.duplicateSelection() })
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
        selectionTrailing.addView(iconButton(Icon.CHECK, Ui.ACCENT, 40f) { canvasView.clearSelection() })
    }

    /** Aumenta/diminui o texto mantendo o centro no lugar. */
    private fun scaleText(t: TextElement, k: Float) =
        t.transformed(Transform(scale = k, ox = t.centerX, oy = t.centerY))

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
        selectionTrailing.addView(iconButton(Icon.CLOSE, Ui.MUTED, 40f) { canvasView.cancelCrop() })
        selectionTrailing.addView(TextView(this).apply {
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
                    Environment.DIRECTORY_PICTURES + "/TonyNotes") { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } catch (e: Exception) {
                false
            }
            bmp?.recycle()
            main.post { toast(if (ok) "Imagem salva em Imagens/TonyNotes" else "Não foi possível salvar a imagem.") }
        }
    }

    // ---- Zoom -------------------------------------------------------------------------

    private fun showZoomMenu() {
        val fitPage = if (canvasView.pageLayout.hasPages)
            listOf(SheetItem(Icon.PAGE, "Ajustar à folha") { canvasView.fitToPage(canvasView.currentPage()) }) else emptyList()
        actionSheet("Zoom: ${canvasView.zoomPercent}%", fitPage + listOf(
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
        val finger = vertical()
        finger.addView(switchRow(Icon.TOUCH, "Escrever com o dedo", prefs.fingerDraws) {
            prefs.fingerDraws = it
            applyPrefs()
        })
        finger.addView(switchRow(Icon.EDIT, "Tinta de latência mínima", prefs.lowLatency) {
            prefs.lowLatency = it
            applyPrefs()
            toast(if (it) "Reabra a nota para ativar a tinta de latência mínima." else "Tinta de latência mínima desligada.")
        })
        actionSheet(null, listOf(
            SheetItem(Icon.PAGE, "Página (infinita ou folhas)") {
                Panels.showPage(this, canvasView, prefs) { onStateChanged() }
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
        if (::textEditor.isInitialized) textEditor.reposition()
        if (::emptyHint.isInitialized) {
            emptyHint.visibility = if (canvasView.isEmpty && !(::textEditor.isInitialized && textEditor.isActive)) View.VISIBLE else View.GONE
            val l = canvasView.pageLayout
            hintTitle.text = if (l.hasPages) "Escreva na folha" else "Escreva em qualquer direção"
            hintBody.text = when {
                !l.hasPages -> "A nota não tem fim: role para os lados e para baixo.\nUse dois dedos para mover e dar zoom."
                l.endless -> "Uma folha nova aparece quando você escreve na última.\nUse dois dedos para mover e dar zoom."
                else -> "Mude o número de folhas em ⋮ › Página.\nUse dois dedos para mover e dar zoom."
            }
        }
        undoButton.alpha = if (canvasView.canUndo) 1f else 0.3f
        redoButton.alpha = if (canvasView.canRedo) 1f else 0.3f
        zoomLabel.text = "${canvasView.zoomPercent}%"
    }

    override fun onSelectionChanged(count: Int) {
        val wasVisible = selectionBar.visibility == View.VISIBLE
        selectionBar.visibility = if (count > 0 || canvasView.inCropMode) View.VISIBLE else View.GONE
        if (!wasVisible && selectionBar.visibility == View.VISIBLE && Ui.animationsOn()) {
            // Barra sobe de baixo com a mesma curva das bandejas.
            selectionBar.alpha = 0f
            selectionBar.translationY = dp(16f)
            selectionBar.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Ui.EASE).start()
        }
        // A barra fica embaixo, no lugar do contador de zoom.
        zoomLabel.visibility = if (selectionBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (selectionBar.visibility == View.VISIBLE) fillSelectionBar()
        refresh()
    }

    override fun onCropModeChanged(active: Boolean) {
        onSelectionChanged(canvasView.selectedItems.size)
    }

    override fun onTextRequest(x: Float, y: Float, existing: TextElement?) {
        closeTray()
        canvasView.clearSelection()
        emptyHint.visibility = View.GONE
        textEditor.start(existing, x, y)
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
        val thumb = if (wasDirty) canvasView.renderToBitmap(480, 360, 1f, thumbnail = true) else null
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
                    Environment.DIRECTORY_PICTURES + "/TonyNotes") { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                false
            } finally {
                bitmap.recycle()
            }
            main.post { toast(if (ok) "Imagem salva em Imagens/TonyNotes" else "Não foi possível salvar a imagem.") }
        }
    }

    /**
     * PDF vetorial (os traços continuam nítidos). Com folhas, cada folha vira uma página
     * do PDF; na tela infinita, sai uma página do tamanho do conteúdo.
     */
    private fun exportPdf() {
        val content = canvasView.contentBounds()
        if (content == null) {
            toast("A nota está vazia.")
            return
        }
        val doc = PdfDocument()
        val pages = canvasView.usedPageRects()
        if (pages.isNotEmpty()) {
            // Folha do tamanho A4 (595 pt de largura em retrato).
            val s = 595f / min(pages[0].width(), pages[0].height())
            pages.forEachIndexed { i, r ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder((r.width() * s).toInt(), (r.height() * s).toInt(), i + 1).create())
                val c = page.canvas
                c.drawColor(canvasView.paperColor)
                c.scale(s, s)
                c.translate(-r.left, -r.top)
                c.clipRect(r)
                canvasView.drawContent(c, r)
                doc.finishPage(page)
            }
        } else {
            val pad = 40f
            val w = content.width() + pad * 2
            val h = content.height() + pad * 2
            // Limite do PDF: 14400 pt por lado.
            val s = min(1f, min(14400f / w, 14400f / h))
            val page = doc.startPage(PdfDocument.PageInfo.Builder(max(1, (w * s).toInt()), max(1, (h * s).toInt()), 1).create())
            val c = page.canvas
            c.drawColor(canvasView.paperColor)
            c.scale(s, s)
            c.translate(pad - content.left, pad - content.top)
            canvasView.drawContent(c, null)
            doc.finishPage(page)
        }
        val name = fileName("pdf")
        Library.io.execute {
            val ok = try {
                writeToMediaStore(MediaStore.Downloads.EXTERNAL_CONTENT_URI, name, "application/pdf",
                    Environment.DIRECTORY_DOWNLOADS + "/TonyNotes") { out -> doc.writeTo(out); true }
            } catch (e: Exception) {
                false
            } finally {
                doc.close()
            }
            main.post { toast(if (ok) "PDF salvo em Downloads/TonyNotes" else "Não foi possível salvar o PDF.") }
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
