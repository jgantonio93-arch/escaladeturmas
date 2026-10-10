package com.jgantonio.notasinfinitas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tela de escrita infinita nas duas direções (horizontal e vertical).
 *
 * Coordenadas: tela = mundo * scale + offset  ->  mundo = (tela - offset) / scale.
 *
 * Toque:
 *  - S Pen usa a ferramenta atual (caneta, borracha, seleção, texto).
 *    Com o botão lateral pressionado, apaga.
 *  - Dedos movem a tela (1 dedo) e dão zoom (2 dedos).
 *  - Com [fingerDraws] ligado, 1 dedo usa a ferramenta e 2 dedos movem/dão zoom.
 */
class InfiniteCanvasView(context: Context) : View(context) {

    enum class Tool { PEN, ERASER, SELECT, TEXT }

    interface Listener {
        fun onStateChanged()
        fun onSelectionChanged(count: Int)
        fun onTextRequest(x: Float, y: Float, existing: TextElement?)
    }

    private enum class Gesture { NONE, DRAW, ERASE, NAVIGATE, LASSO, MOVE_SEL, SCALE_SEL, TAP_TEXT }

    // ---- Configuração ------------------------------------------------------

    var listener: Listener? = null
    var tool = Tool.PEN
        set(v) {
            field = v
            if (v != Tool.SELECT) clearSelection()
            invalidate()
        }
    lateinit var pen: PenSettings
    var fingerDraws = false
    var autoShapes = false
    var spenButtonErases = true
    var eraserArea = false
    var eraserSize = 14f
    var eraserHighlighterOnly = false
    var pageStyle = PageStyle.DOTS
        set(v) { field = v; dirty = true; invalidate() }
    var paperColor = Color.WHITE
        set(v) { field = v; dirty = true; invalidate() }
    var assetDir: File? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val isEmpty get() = elements.isEmpty()
    val zoomPercent get() = (scale * 100f).toInt()
    val hasSelection get() = selection.isNotEmpty()

    /** Há mudanças ainda não salvas. */
    var dirty = false
        private set

    // ---- Dados -------------------------------------------------------------

    private val elements = ArrayList<Element>()
    private val undoStack = ArrayList<EditAction>()
    private val redoStack = ArrayList<EditAction>()
    private var recording: ArrayList<Op>? = null

    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private var hasInitialPosition = false

    private val density = resources.displayMetrics.density
    /** Converte o "tamanho" da caneta em unidades do mundo. */
    val unit = density * 0.5f

    // ---- Gesto atual -------------------------------------------------------

    private var gesture = Gesture.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var builder: StrokeBuilder? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var eraserX = -1f
    private var eraserY = -1f
    private var lastEraseWX = Float.NaN
    private var lastEraseWY = Float.NaN
    private var downX = 0f
    private var downY = 0f

    // Laço e seleção
    private val lassoPath = Path()
    private val lassoXs = ArrayList<Float>()
    private val lassoYs = ArrayList<Float>()
    private val selection = ArrayList<Element>()
    private val selectionSet = HashSet<Element>()
    private var selDx = 0f
    private var selDy = 0f
    private var selScale = 1f
    private var selOriginX = 0f
    private var selOriginY = 0f
    private var selStartWX = 0f
    private var selStartWY = 0f
    private var selHandleWX = 0f
    private var selHandleWY = 0f

    // ---- Pintura -----------------------------------------------------------

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#888888")
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#1F5FD1")
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F5FD1") }
    private val handleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint().apply { color = Color.parseColor("#DDE1E7") }
    private var gridPoints = FloatArray(0)
    private val visibleWorld = RectF()
    private val bitmaps = HashMap<String, Bitmap?>()

    init {
        isFocusable = true
    }

    // ---- Edição ------------------------------------------------------------

    private fun begin() {
        recording = ArrayList()
    }

    private fun insertAt(index: Int, e: Element) {
        val i = index.coerceIn(0, elements.size)
        elements.add(i, e)
        recording?.add(Op.Insert(i, e))
    }

    private fun removeAt(index: Int) {
        val e = elements.removeAt(index)
        recording?.add(Op.Remove(index, e))
    }

    private fun commit() {
        val ops = recording ?: return
        recording = null
        if (ops.isEmpty()) return
        undoStack.add(EditAction(ops))
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
        changed()
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        clearSelection()
        action.undo(elements)
        redoStack.add(action)
        changed()
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        clearSelection()
        action.redo(elements)
        undoStack.add(action)
        changed()
    }

    fun clearAll() {
        if (elements.isEmpty()) return
        clearSelection()
        begin()
        for (i in elements.indices.reversed()) removeAt(i)
        commit()
    }

    fun addText(x: Float, y: Float, text: String, size: Float, color: Int) {
        begin()
        insertAt(elements.size, TextElement(text, x, y, size, color))
        commit()
    }

    fun replaceText(old: TextElement, text: String, size: Float, color: Int) {
        val i = elements.indexOf(old)
        if (i < 0) return
        begin()
        removeAt(i)
        if (text.isNotBlank()) insertAt(i, TextElement(text, old.x, old.y, size, color))
        commit()
    }

    /** Insere uma imagem no meio da área visível e já deixa ela selecionada. */
    fun addImage(file: String, imgW: Int, imgH: Int) {
        val visW = width / scale
        val visH = height / scale
        val w = min(visW * 0.6f, imgW.toFloat() / density * 1.5f)
        val h = w * imgH / max(1, imgW)
        val cx = (width / 2f - offsetX) / scale
        val cy = (height / 2f - offsetY) / scale
        val e = ImageElement(file, RectF(cx - w / 2, cy - min(h, visH) / 2, cx + w / 2, cy - min(h, visH) / 2 + h))
        begin()
        insertAt(elements.size, e)
        commit()
        tool = Tool.SELECT
        setSelection(listOf(e))
    }

    // ---- Seleção -----------------------------------------------------------

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        selectionSet.clear()
        listener?.onSelectionChanged(0)
        invalidate()
    }

    private fun setSelection(list: List<Element>) {
        selection.clear()
        selection.addAll(list)
        selectionSet.clear()
        selectionSet.addAll(list)
        listener?.onSelectionChanged(selection.size)
        invalidate()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        begin()
        for (i in elements.indices.reversed()) if (elements[i] in selectionSet) removeAt(i)
        commit()
        clearSelection()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        val d = 24f * density / scale
        val copies = selection.map { it.transformed(1f, 0f, 0f, d, d) }
        begin()
        for (c in copies) insertAt(elements.size, c)
        commit()
        setSelection(copies)
    }

    fun recolorSelection(color: Int) {
        if (selection.isEmpty()) return
        val replaced = ArrayList<Element>()
        begin()
        for (e in selection) {
            val i = elements.indexOf(e)
            val n = when (e) {
                is StrokeElement -> e.withColor(color)
                is TextElement -> e.withColor(color)
                else -> e
            }
            if (i >= 0 && n !== e) {
                removeAt(i)
                insertAt(i, n)
            }
            replaced.add(n)
        }
        commit()
        setSelection(replaced)
    }

    private fun selectionBounds(): RectF? {
        if (selection.isEmpty()) return null
        val r = RectF(selection[0].bounds)
        for (e in selection) r.union(e.bounds)
        return r
    }

    /** Retângulo da seleção na tela, já com o movimento/escala em andamento. */
    private fun selectionScreenRect(): RectF? {
        val b = selectionBounds() ?: return null
        fun tx(v: Float) = ((v - selOriginX) * selScale + selOriginX + selDx) * scale + offsetX
        fun ty(v: Float) = ((v - selOriginY) * selScale + selOriginY + selDy) * scale + offsetY
        val pad = 8f * density
        return RectF(tx(b.left) - pad, ty(b.top) - pad, tx(b.right) + pad, ty(b.bottom) + pad)
    }

    private fun commitSelectionTransform() {
        if (selDx == 0f && selDy == 0f && selScale == 1f) return
        val moved = ArrayList<Element>()
        begin()
        for (e in selection) {
            val i = elements.indexOf(e)
            val n = e.transformed(selScale, selOriginX, selOriginY, selDx, selDy)
            if (i >= 0) {
                removeAt(i)
                insertAt(i, n)
            }
            moved.add(n)
        }
        commit()
        selDx = 0f; selDy = 0f; selScale = 1f
        setSelection(moved)
    }

    // ---- Câmera, carregar e salvar -----------------------------------------

    /** Leva a câmera de volta para o conteúdo (ou para a origem, se vazio). */
    fun recenter() {
        val content = contentBounds()
        if (content == null) {
            scale = 1f
            offsetX = width * 0.1f
            offsetY = max(height * 0.1f, 96f * density)
        } else {
            val fit = min(width / (content.width() + 80f), height / (content.height() + 80f))
            scale = fit.coerceIn(MIN_SCALE, 1f)
            offsetX = width / 2f - content.centerX() * scale
            offsetY = height / 2f - content.centerY() * scale
        }
        invalidate()
        listener?.onStateChanged()
    }

    fun snapshot() = NoteData(ArrayList(elements), ViewState(offsetX, offsetY, scale), pageStyle, paperColor)

    fun markSaved() {
        dirty = false
    }

    fun load(note: NoteData) {
        elements.clear()
        elements.addAll(note.elements)
        undoStack.clear()
        redoStack.clear()
        note.viewState?.let {
            offsetX = it.offsetX
            offsetY = it.offsetY
            scale = it.scale.coerceIn(MIN_SCALE, MAX_SCALE)
            hasInitialPosition = true
        }
        pageStyle = note.style
        paperColor = note.paperColor
        dirty = false
        invalidate()
        listener?.onStateChanged()
    }

    fun contentBounds(): RectF? {
        if (elements.isEmpty()) return null
        val r = RectF(elements[0].bounds)
        for (e in elements) r.union(e.bounds)
        return r
    }

    /**
     * Desenha todo o conteúdo num bitmap de no máximo [maxW] x [maxH] pixels
     * (sobre a cor do papel). Usado para exportar e para a miniatura.
     */
    fun renderToBitmap(maxW: Int, maxH: Int, maxScale: Float = 2f): Bitmap? {
        val content = contentBounds() ?: return null
        val pad = 40f
        val w = content.width() + pad * 2
        val h = content.height() + pad * 2
        val s = minOf(maxScale, maxW / w, maxH / h, sqrt(16_000_000f / (w * h)))
        val bmp = Bitmap.createBitmap(max(1, (w * s).toInt()), max(1, (h * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paperColor)
        c.scale(s, s)
        c.translate(pad - content.left, pad - content.top)
        drawContent(c, null)
        return bmp
    }

    /** Desenha os elementos num canvas já transformado para o mundo. */
    fun drawContent(canvas: Canvas, clip: RectF?) {
        for (e in elements) {
            if (clip != null && !RectF.intersects(e.bounds, clip)) continue
            drawElement(canvas, e)
        }
    }

    // ---- Toque -------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasInitialPosition && w > 0) {
            // Começa abaixo do dock de ferramentas, que flutua no topo.
            offsetX = w * 0.08f
            offsetY = max(h * 0.1f, 96f * density)
            hasInitialPosition = true
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun isPen(e: MotionEvent, index: Int): Boolean {
        val type = e.getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun wx(sx: Float) = (sx - offsetX) / scale
    private fun wy(sy: Float) = (sy - offsetY) / scale

    private fun onDown(e: MotionEvent) {
        activePointerId = e.getPointerId(0)
        val x = e.getX(0)
        val y = e.getY(0)
        downX = x
        downY = y
        val pen = isPen(e, 0)
        if (pen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Entrega os pontos da caneta sem esperar o próximo frame: menos atraso.
            requestUnbufferedDispatch(e)
        }
        if (!pen && !fingerDraws) {
            gesture = Gesture.NAVIGATE
            resetNavigation(e, -1)
            return
        }
        val buttonErase = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            (spenButtonErases && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        gesture = when {
            buttonErase && tool != Tool.SELECT -> Gesture.ERASE
            tool == Tool.PEN -> Gesture.DRAW
            tool == Tool.ERASER -> Gesture.ERASE
            tool == Tool.TEXT -> Gesture.TAP_TEXT
            else -> selectionGestureAt(x, y)
        }
        when (gesture) {
            Gesture.DRAW -> {
                builder = StrokeBuilder(pen(), unit).also {
                    it.add(wx(x), wy(y), pressureOf(e, 0), minPointDist())
                }
                invalidate()
            }
            Gesture.ERASE -> {
                begin()
                lastEraseWX = Float.NaN
                eraseAt(x, y)
            }
            Gesture.LASSO -> {
                clearSelection()
                lassoXs.clear(); lassoYs.clear()
                lassoPath.reset()
                lassoPath.moveTo(x, y)
                lassoXs.add(wx(x)); lassoYs.add(wy(y))
            }
            Gesture.MOVE_SEL, Gesture.SCALE_SEL -> {
                val b = selectionBounds()!!
                selStartWX = wx(x); selStartWY = wy(y)
                selOriginX = b.left; selOriginY = b.top
                selHandleWX = b.right; selHandleWY = b.bottom
                selDx = 0f; selDy = 0f; selScale = 1f
            }
            else -> Unit
        }
    }

    private fun pen(): PenSettings = pen

    private fun selectionGestureAt(x: Float, y: Float): Gesture {
        val r = selectionScreenRect() ?: return Gesture.LASSO
        val handleR = 28f * density
        if (hypot(x - r.right, y - r.bottom) <= handleR) return Gesture.SCALE_SEL
        if (r.contains(x, y)) return Gesture.MOVE_SEL
        return Gesture.LASSO
    }

    private fun onPointerDown(e: MotionEvent) {
        when (gesture) {
            Gesture.NAVIGATE -> resetNavigation(e, -1)
            Gesture.NONE -> Unit
            else -> {
                val idx = e.findPointerIndex(activePointerId)
                val drawingWithPen = idx >= 0 && isPen(e, idx)
                if (!drawingWithPen) {
                    // Usando a ferramenta com o dedo e veio um segundo dedo: vira navegação.
                    abortToolGesture()
                    gesture = Gesture.NAVIGATE
                    resetNavigation(e, -1)
                }
                // Com a S Pen em uso, ignoramos a palma/dedos.
            }
        }
    }

    private fun onMove(e: MotionEvent) {
        if (gesture == Gesture.NAVIGATE) {
            navigate(e)
            return
        }
        val idx = e.findPointerIndex(activePointerId)
        if (idx < 0) return
        val x = e.getX(idx)
        val y = e.getY(idx)
        when (gesture) {
            Gesture.DRAW -> {
                val b = builder ?: return
                val md = minPointDist()
                for (h in 0 until e.historySize) {
                    b.add(wx(e.getHistoricalX(idx, h)), wy(e.getHistoricalY(idx, h)), historicalPressureOf(e, idx, h), md)
                }
                b.add(wx(x), wy(y), pressureOf(e, idx), md)
                invalidate()
            }
            Gesture.ERASE -> {
                for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                eraseAt(x, y)
            }
            Gesture.LASSO -> {
                lassoPath.lineTo(x, y)
                lassoXs.add(wx(x)); lassoYs.add(wy(y))
                invalidate()
            }
            Gesture.MOVE_SEL -> {
                selDx = wx(x) - selStartWX
                selDy = wy(y) - selStartWY
                invalidate()
            }
            Gesture.SCALE_SEL -> {
                val num = (wx(x) - selOriginX) + (wy(y) - selOriginY)
                val den = (selHandleWX - selOriginX) + (selHandleWY - selOriginY)
                if (den > 0.01f) selScale = (num / den).coerceIn(0.1f, 20f)
                invalidate()
            }
            else -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        if (gesture == Gesture.NAVIGATE) {
            resetNavigation(e, e.actionIndex)
        } else if (e.getPointerId(e.actionIndex) == activePointerId) {
            finishGesture(e)
        }
    }

    private fun onUp(e: MotionEvent) {
        if (gesture != Gesture.NAVIGATE && gesture != Gesture.NONE) finishGesture(e)
        gesture = Gesture.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        eraserX = -1f
        invalidate()
    }

    private fun finishGesture(e: MotionEvent) {
        val canceled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (e.flags and MotionEvent.FLAG_CANCELED) != 0
        if (canceled) {
            abortToolGesture()
            gesture = Gesture.NONE
            return
        }
        when (gesture) {
            Gesture.DRAW -> {
                val b = builder
                builder = null
                if (b != null && b.n > 0) {
                    var stroke = b.build()
                    if (autoShapes) {
                        ShapeRecognizer.recognize(stroke, 24f * density / scale)?.let { stroke = it }
                    }
                    begin()
                    insertAt(elements.size, stroke)
                    commit()
                }
            }
            Gesture.ERASE -> commit()
            Gesture.LASSO -> {
                if (lassoXs.size >= 3) {
                    val lasso = Lasso(lassoXs.toFloatArray(), lassoYs.toFloatArray())
                    setSelection(elements.filter { it.insideLasso(lasso) })
                }
                lassoPath.reset()
            }
            Gesture.MOVE_SEL, Gesture.SCALE_SEL -> commitSelectionTransform()
            Gesture.TAP_TEXT -> {
                if (hypot(e.x - downX, e.y - downY) < 12f * density) {
                    val px = wx(downX)
                    val py = wy(downY)
                    val hit = elements.lastOrNull { it is TextElement && it.bounds.contains(px, py) } as TextElement?
                    listener?.onTextRequest(px, py, hit)
                }
            }
            else -> Unit
        }
        gesture = Gesture.NONE
        eraserX = -1f
        invalidate()
    }

    /** Cancela o que a ferramenta estava fazendo (palma detectada, segundo dedo...). */
    private fun abortToolGesture() {
        when (gesture) {
            Gesture.DRAW -> builder = null
            Gesture.ERASE -> commit()
            Gesture.LASSO -> lassoPath.reset()
            Gesture.MOVE_SEL, Gesture.SCALE_SEL -> { selDx = 0f; selDy = 0f; selScale = 1f }
            else -> Unit
        }
        eraserX = -1f
        invalidate()
    }

    private fun cancelGesture() {
        abortToolGesture()
        gesture = Gesture.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun minPointDist() = 0.6f / scale

    private fun pressureOf(e: MotionEvent, idx: Int): Float =
        if (isPen(e, idx)) e.getPressure(idx) else FINGER_PRESSURE

    private fun historicalPressureOf(e: MotionEvent, idx: Int, h: Int): Float =
        if (isPen(e, idx)) e.getHistoricalPressure(idx, h) else FINGER_PRESSURE

    private fun eraseAt(sx: Float, sy: Float) {
        eraserX = sx
        eraserY = sy
        val px = wx(sx)
        val py = wy(sy)
        val r = eraserSize * density / scale
        // Preenche o caminho entre o ponto anterior e o atual.
        if (!lastEraseWX.isNaN()) {
            val d = hypot(px - lastEraseWX, py - lastEraseWY)
            val steps = ceil(d / (r * 0.5f)).toInt()
            for (k in 1 until steps) {
                val f = k.toFloat() / steps
                eraseWorld(lastEraseWX + (px - lastEraseWX) * f, lastEraseWY + (py - lastEraseWY) * f, r)
            }
        }
        eraseWorld(px, py, r)
        lastEraseWX = px
        lastEraseWY = py
        invalidate()
    }

    private fun eraseWorld(px: Float, py: Float, r: Float) {
        var i = elements.size - 1
        while (i >= 0) {
            val s = elements[i] as? StrokeElement
            if (s != null && (!eraserHighlighterOnly || s.type == BrushType.HIGHLIGHTER)) {
                if (eraserArea) {
                    val pieces = s.eraseCircle(px, py, r)
                    if (pieces != null) {
                        removeAt(i)
                        pieces.forEachIndexed { k, p -> insertAt(i + k, p) }
                        dirty = true
                    }
                } else if (s.hits(px, py, r)) {
                    removeAt(i)
                    dirty = true
                }
            }
            i--
        }
    }

    // ---- Navegação (mover e zoom) ------------------------------------------

    private fun focus(e: MotionEvent, skipIndex: Int): Triple<Float, Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skipIndex) continue
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        if (n == 0) return Triple(0f, 0f, 0f)
        val fx = sx / n
        val fy = sy / n
        var span = 0f
        if (n >= 2) {
            for (i in 0 until e.pointerCount) {
                if (i == skipIndex) continue
                span += hypot(e.getX(i) - fx, e.getY(i) - fy)
            }
            span /= n
        }
        return Triple(fx, fy, span)
    }

    private fun resetNavigation(e: MotionEvent, skipIndex: Int) {
        val (fx, fy, span) = focus(e, skipIndex)
        lastFocusX = fx
        lastFocusY = fy
        lastSpan = span
    }

    private fun navigate(e: MotionEvent) {
        val (fx, fy, span) = focus(e, -1)
        if (lastSpan > 10f && span > 10f) {
            val newScale = (scale * span / lastSpan).coerceIn(MIN_SCALE, MAX_SCALE)
            // Zoom em torno do ponto entre os dedos.
            offsetX = lastFocusX - (lastFocusX - offsetX) * (newScale / scale)
            offsetY = lastFocusY - (lastFocusY - offsetY) * (newScale / scale)
            scale = newScale
        }
        offsetX += fx - lastFocusX
        offsetY += fy - lastFocusY
        lastFocusX = fx
        lastFocusY = fy
        lastSpan = span
        invalidate()
        listener?.onStateChanged()
    }

    private fun changed() {
        dirty = true
        invalidate()
        listener?.onStateChanged()
    }

    // ---- Renderização ------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(paperColor)
        visibleWorld.set(wx(0f), wy(0f), wx(width.toFloat()), wy(height.toFloat()))
        drawPattern(canvas)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        val moving = selDx != 0f || selDy != 0f || selScale != 1f
        for (e in elements) {
            if (moving && e in selectionSet) {
                canvas.save()
                canvas.translate(selDx, selDy)
                canvas.translate(selOriginX, selOriginY)
                canvas.scale(selScale, selScale)
                canvas.translate(-selOriginX, -selOriginY)
                drawElement(canvas, e)
                canvas.restore()
            } else if (RectF.intersects(e.bounds, visibleWorld)) {
                drawElement(canvas, e)
            }
        }
        builder?.let { StrokeRenderer.drawBuilder(canvas, it) }
        canvas.restore()

        if (gesture == Gesture.LASSO) canvas.drawPath(lassoPath, dashPaint)
        selectionScreenRect()?.let { r ->
            canvas.drawRect(r, dashPaint)
            canvas.drawCircle(r.right, r.bottom, 11f * density, handlePaint)
            canvas.drawCircle(r.right, r.bottom, 5f * density, handleInner)
        }
        if (eraserX >= 0f) {
            canvas.drawCircle(eraserX, eraserY, eraserSize * density, eraserPaint)
        }
    }

    private fun drawElement(canvas: Canvas, e: Element) {
        when (e) {
            is StrokeElement -> StrokeRenderer.drawElement(canvas, e)
            is TextElement -> {
                textPaint.textSize = e.size
                textPaint.color = e.color
                val fm = textPaint.fontMetrics
                var baseline = e.y - fm.ascent
                for (line in e.lines) {
                    canvas.drawText(line, e.x, baseline, textPaint)
                    baseline += e.lineHeight
                }
            }
            is ImageElement -> {
                val bmp = bitmapFor(e.file)
                if (bmp != null) canvas.drawBitmap(bmp, null, e.rect, bitmapPaint)
                else canvas.drawRect(e.rect, placeholderPaint)
            }
        }
    }

    private fun bitmapFor(name: String): Bitmap? {
        if (bitmaps.containsKey(name)) return bitmaps[name]
        val dir = assetDir
        val bmp = if (dir == null) null else try {
            BitmapFactory.decodeFile(File(dir, name).path)
        } catch (e: OutOfMemoryError) {
            null
        }
        bitmaps[name] = bmp
        return bmp
    }

    private fun isDarkPaper(): Boolean {
        val c = paperColor
        val lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        return lum < 128
    }

    /** Fundo (pontos, linhas ou grade) que acompanha a rolagem. */
    private fun drawPattern(canvas: Canvas) {
        if (pageStyle == PageStyle.BLANK) return
        val dark = isDarkPaper()
        var spacing = GRID_SPACING
        while (spacing * scale < 18f * density) spacing *= 2f
        while (spacing * scale > 80f * density) spacing /= 2f

        val startX = floor(visibleWorld.left / spacing) * spacing
        val startY = floor(visibleWorld.top / spacing) * spacing
        val cols = ((visibleWorld.right - startX) / spacing).toInt() + 1
        val rows = ((visibleWorld.bottom - startY) / spacing).toInt() + 1

        when (pageStyle) {
            PageStyle.DOTS -> {
                val needed = cols * rows * 2
                if (needed <= 0 || needed > 200_000) return
                if (gridPoints.size < needed) gridPoints = FloatArray(needed)
                var k = 0
                for (r in 0 until rows) {
                    val sy = (startY + r * spacing) * scale + offsetY
                    for (c in 0 until cols) {
                        gridPoints[k++] = (startX + c * spacing) * scale + offsetX
                        gridPoints[k++] = sy
                    }
                }
                gridPaint.color = if (dark) Color.parseColor("#4A5260") else Color.parseColor("#C9CED6")
                gridPaint.strokeWidth = 2.2f * density
                canvas.drawPoints(gridPoints, 0, k, gridPaint)
            }
            PageStyle.LINES, PageStyle.GRID -> {
                linePaint.color = if (dark) Color.parseColor("#3A4250") else Color.parseColor("#D9DEE6")
                linePaint.strokeWidth = 1f * density
                for (r in 0 until rows) {
                    val sy = (startY + r * spacing) * scale + offsetY
                    canvas.drawLine(0f, sy, width.toFloat(), sy, linePaint)
                }
                if (pageStyle == PageStyle.GRID) {
                    for (c in 0 until cols) {
                        val sx = (startX + c * spacing) * scale + offsetX
                        canvas.drawLine(sx, 0f, sx, height.toFloat(), linePaint)
                    }
                }
            }
            PageStyle.BLANK -> Unit
        }
    }

    companion object {
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 8f
        private const val MAX_UNDO = 300
        private const val GRID_SPACING = 40f
        private const val FINGER_PRESSURE = 0.6f
    }
}
