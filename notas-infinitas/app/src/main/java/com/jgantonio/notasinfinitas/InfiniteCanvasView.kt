package com.jgantonio.notasinfinitas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
 *  - S Pen escreve (ou apaga, no modo borracha, com o botão lateral pressionado
 *    ou com a ponta de borracha).
 *  - Dedos movem a tela (1 dedo) e dão zoom (2 dedos).
 *  - Com [fingerDraws] ligado, 1 dedo escreve e 2 dedos movem/dão zoom.
 */
class InfiniteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Tool { PEN, ERASER }

    private sealed interface Action
    private class Added(val stroke: Stroke) : Action
    /** Traços removidos, na ordem em que saíram, com o índice que tinham no momento. */
    private class Removed(val items: List<Pair<Int, Stroke>>) : Action

    private enum class Gesture { NONE, DRAW, ERASE, NAVIGATE }

    // ---- Estado público ----------------------------------------------------

    var tool = Tool.PEN
    var color = Color.parseColor("#1B1B1F")
    var penWidth = 4f
    var fingerDraws = false

    /** Chamado quando algo que a barra de ferramentas mostra muda. */
    var onStateChanged: (() -> Unit)? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val isEmpty get() = strokes.isEmpty()
    val zoomPercent get() = (scale * 100f).toInt()

    /** Há mudanças ainda não salvas. */
    var dirty = false
        private set

    // ---- Dados -------------------------------------------------------------

    private val strokes = ArrayList<Stroke>()
    private val undoStack = ArrayList<Action>()
    private val redoStack = ArrayList<Action>()

    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private var hasInitialPosition = false

    // ---- Gesto atual -------------------------------------------------------

    private var gesture = Gesture.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var currentStroke: Stroke? = null
    private val erasedInGesture = ArrayList<Pair<Int, Stroke>>()
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var eraserX = -1f
    private var eraserY = -1f

    // ---- Desenho -----------------------------------------------------------

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9CED6")
        strokeCap = Paint.Cap.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#888888")
    }
    private var gridPoints = FloatArray(0)
    private val visibleWorld = RectF()

    private val density = resources.displayMetrics.density
    private val eraserRadiusPx = 14f * density

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
    }

    // ---- API de edição -----------------------------------------------------

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        when (action) {
            is Added -> strokes.remove(action.stroke)
            is Removed -> for ((index, s) in action.items.asReversed()) {
                strokes.add(index.coerceAtMost(strokes.size), s)
            }
        }
        redoStack.add(action)
        changed()
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        when (action) {
            is Added -> strokes.add(action.stroke)
            is Removed -> for ((_, s) in action.items) strokes.remove(s)
        }
        undoStack.add(action)
        changed()
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        val items = strokes.indices.reversed().map { it to strokes[it] }
        strokes.clear()
        push(Removed(items))
    }

    /** Leva a câmera de volta para o conteúdo (ou para a origem, se vazio). */
    fun recenter() {
        val content = contentBounds()
        scale = 1f
        if (content == null) {
            offsetX = width * 0.1f
            offsetY = height * 0.1f
        } else {
            val fit = min(width / (content.width() + 80f), height / (content.height() + 80f))
            scale = fit.coerceIn(MIN_SCALE, 1f)
            offsetX = width / 2f - content.centerX() * scale
            offsetY = height / 2f - content.centerY() * scale
        }
        changed(dirtyData = false)
    }

    fun snapshot(): Pair<List<Stroke>, ViewState> =
        ArrayList(strokes) to ViewState(offsetX, offsetY, scale)

    fun markSaved() {
        dirty = false
    }

    fun load(note: Note) {
        strokes.clear()
        strokes.addAll(note.strokes)
        undoStack.clear()
        redoStack.clear()
        note.viewState?.let {
            offsetX = it.offsetX
            offsetY = it.offsetY
            scale = it.scale.coerceIn(MIN_SCALE, MAX_SCALE)
            hasInitialPosition = true
        }
        dirty = false
        changed(dirtyData = false)
    }

    /** Gera uma imagem com todo o conteúdo da nota (fundo branco). */
    fun renderToBitmap(): Bitmap? {
        val content = contentBounds() ?: return null
        val pad = 40f
        val w = content.width() + pad * 2
        val h = content.height() + pad * 2
        val s = minOf(2f, 4096f / w, 4096f / h, sqrt(16_000_000f / (w * h)))
        val bmp = Bitmap.createBitmap(max(1, (w * s).toInt()), max(1, (h * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(s, s)
        c.translate(pad - content.left, pad - content.top)
        for (stroke in strokes) drawStroke(c, stroke)
        return bmp
    }

    private fun contentBounds(): RectF? {
        if (strokes.isEmpty()) return null
        val r = RectF(strokes[0].bounds)
        for (s in strokes) r.union(s.bounds)
        return r
    }

    private fun push(action: Action) {
        undoStack.add(action)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
        changed()
    }

    private fun changed(dirtyData: Boolean = true) {
        if (dirtyData) dirty = true
        invalidate()
        onStateChanged?.invoke()
    }

    // ---- Toque -------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasInitialPosition && w > 0) {
            offsetX = w * 0.1f
            offsetY = h * 0.1f
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

    private fun wantsErase(e: MotionEvent, index: Int): Boolean =
        tool == Tool.ERASER ||
            e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    private fun onDown(e: MotionEvent) {
        activePointerId = e.getPointerId(0)
        val pen = isPen(e, 0)
        if (pen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Entrega os pontos da caneta sem esperar o próximo frame: menos atraso.
            requestUnbufferedDispatch(e)
        }
        if (pen || fingerDraws) {
            if (wantsErase(e, 0)) {
                gesture = Gesture.ERASE
                erasedInGesture.clear()
                eraseAt(e.getX(0), e.getY(0))
            } else {
                gesture = Gesture.DRAW
                val stroke = Stroke(color, penWidth)
                currentStroke = stroke
                addPoint(stroke, e.getX(0), e.getY(0), pressureOf(e, 0))
                invalidate()
            }
        } else {
            gesture = Gesture.NAVIGATE
            resetNavigation(e, -1)
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        when (gesture) {
            Gesture.DRAW, Gesture.ERASE -> {
                // Com a S Pen escrevendo, ignoramos a palma/dedos.
                val drawingWithPen = run {
                    val idx = e.findPointerIndex(activePointerId)
                    idx >= 0 && isPen(e, idx)
                }
                if (!drawingWithPen) {
                    // Escrevendo com o dedo e veio um segundo dedo: vira navegação.
                    discardCurrent()
                    gesture = Gesture.NAVIGATE
                    resetNavigation(e, -1)
                }
            }
            Gesture.NAVIGATE -> resetNavigation(e, -1)
            Gesture.NONE -> Unit
        }
    }

    private fun onMove(e: MotionEvent) {
        when (gesture) {
            Gesture.DRAW -> {
                val stroke = currentStroke ?: return
                val idx = e.findPointerIndex(activePointerId)
                if (idx < 0) return
                for (h in 0 until e.historySize) {
                    addPoint(stroke, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), historicalPressureOf(e, idx, h))
                }
                addPoint(stroke, e.getX(idx), e.getY(idx), pressureOf(e, idx))
                invalidate()
            }
            Gesture.ERASE -> {
                val idx = e.findPointerIndex(activePointerId)
                if (idx < 0) return
                for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                eraseAt(e.getX(idx), e.getY(idx))
            }
            Gesture.NAVIGATE -> navigate(e)
            Gesture.NONE -> Unit
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
        if (gesture == Gesture.DRAW || gesture == Gesture.ERASE) finishGesture(e)
        gesture = Gesture.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        eraserX = -1f
        invalidate()
    }

    private fun finishGesture(e: MotionEvent) {
        val canceled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (e.flags and MotionEvent.FLAG_CANCELED) != 0
        when (gesture) {
            Gesture.DRAW -> {
                val stroke = currentStroke
                currentStroke = null
                if (stroke != null && !canceled) {
                    strokes.add(stroke)
                    push(Added(stroke))
                }
            }
            Gesture.ERASE -> {
                if (erasedInGesture.isNotEmpty()) push(Removed(ArrayList(erasedInGesture)))
                erasedInGesture.clear()
            }
            else -> Unit
        }
        gesture = Gesture.NONE
        eraserX = -1f
        invalidate()
    }

    private fun cancelGesture() {
        discardCurrent()
        if (gesture == Gesture.ERASE && erasedInGesture.isNotEmpty()) {
            push(Removed(ArrayList(erasedInGesture)))
            erasedInGesture.clear()
        }
        gesture = Gesture.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        eraserX = -1f
        invalidate()
    }

    private fun discardCurrent() {
        currentStroke = null
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, idx: Int): Float =
        if (isPen(e, idx)) e.getPressure(idx) else FINGER_PRESSURE

    private fun historicalPressureOf(e: MotionEvent, idx: Int, h: Int): Float =
        if (isPen(e, idx)) e.getHistoricalPressure(idx, h) else FINGER_PRESSURE

    private fun addPoint(stroke: Stroke, sx: Float, sy: Float, pressure: Float) {
        val wx = (sx - offsetX) / scale
        val wy = (sy - offsetY) / scale
        val n = stroke.pointCount
        if (n > 0) {
            // Ignora pontos praticamente iguais ao anterior.
            val dx = wx - stroke.x(n - 1)
            val dy = wy - stroke.y(n - 1)
            if (dx * dx + dy * dy < MIN_POINT_DIST2 / (scale * scale)) return
        }
        stroke.add(wx, wy, pressure)
    }

    private fun eraseAt(sx: Float, sy: Float) {
        eraserX = sx
        eraserY = sy
        val wx = (sx - offsetX) / scale
        val wy = (sy - offsetY) / scale
        val r = eraserRadiusPx / scale
        var removedAny = false
        var i = strokes.size - 1
        while (i >= 0) {
            val s = strokes[i]
            if (s.hits(wx, wy, r)) {
                strokes.removeAt(i)
                erasedInGesture.add(i to s)
                removedAny = true
            }
            i--
        }
        if (removedAny) dirty = true
        invalidate()
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
        onStateChanged?.invoke()
    }

    // ---- Renderização ------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        visibleWorld.set(
            -offsetX / scale,
            -offsetY / scale,
            (width - offsetX) / scale,
            (height - offsetY) / scale,
        )
        drawGrid(canvas)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        for (s in strokes) {
            if (RectF.intersects(s.bounds, visibleWorld)) drawStroke(canvas, s)
        }
        currentStroke?.let { drawStroke(canvas, it) }
        canvas.restore()

        if (gesture == Gesture.ERASE && eraserX >= 0f) {
            canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, eraserPaint)
        }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        val n = s.pointCount
        if (n == 0) return
        if (n == 1) {
            dotPaint.color = s.color
            canvas.drawCircle(s.x(0), s.y(0), s.widthAt(0) / 2f, dotPaint)
            return
        }
        strokePaint.color = s.color
        for (i in 1 until n) {
            strokePaint.strokeWidth = (s.widthAt(i - 1) + s.widthAt(i)) / 2f
            canvas.drawLine(s.x(i - 1), s.y(i - 1), s.x(i), s.y(i), strokePaint)
        }
    }

    /** Pontinhos de fundo que acompanham a rolagem, para dar noção de espaço. */
    private fun drawGrid(canvas: Canvas) {
        var spacing = GRID_SPACING
        while (spacing * scale < 20f * density) spacing *= 2f
        while (spacing * scale > 80f * density) spacing /= 2f

        val startX = floor(visibleWorld.left / spacing) * spacing
        val startY = floor(visibleWorld.top / spacing) * spacing
        val cols = ((visibleWorld.right - startX) / spacing).toInt() + 1
        val rows = ((visibleWorld.bottom - startY) / spacing).toInt() + 1
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
        gridPaint.strokeWidth = 2.2f * density
        canvas.drawPoints(gridPoints, 0, k, gridPaint)
    }

    companion object {
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 8f
        private const val MAX_UNDO = 200
        private const val GRID_SPACING = 40f
        private const val FINGER_PRESSURE = 0.6f
        private const val MIN_POINT_DIST2 = 0.5f * 0.5f
    }
}
