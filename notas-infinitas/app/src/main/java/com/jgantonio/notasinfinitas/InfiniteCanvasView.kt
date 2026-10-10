package com.jgantonio.notasinfinitas

import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

    /** Formatos do modo de recorte. */
    enum class CropShape { RECT, SQUARE, WIDE, ELLIPSE, LASSO }

    interface Listener {
        fun onStateChanged()
        fun onSelectionChanged(count: Int)
        fun onTextRequest(x: Float, y: Float, existing: TextElement?)
        fun onCropModeChanged(active: Boolean)
    }

    private enum class Gesture { NONE, DRAW, ERASE, NAVIGATE, LASSO, MOVE_SEL, SCALE_SEL, ROTATE_SEL, TAP_TEXT, CROP }

    // ---- Configuração ------------------------------------------------------

    var listener: Listener? = null
    var tool = Tool.PEN
        set(v) {
            field = v
            if (v != Tool.SELECT) {
                cancelCrop()
                clearSelection()
            }
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

    val images = ImageRenderer { invalidate() }
    var assetDir: File? = null
        set(v) { field = v; images.dir = v }

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val isEmpty get() = elements.isEmpty()
    /** Zoom arredondado (antes era truncado: 0,999 aparecia como 99%). */
    val zoomPercent get() = (scale * 100f).roundToInt()
    val hasSelection get() = selection.isNotEmpty()
    val selectedItems: List<Element> get() = selection
    /** A imagem selecionada, quando a seleção é exatamente uma imagem. */
    val selectedImage: ImageElement? get() = selection.singleOrNull() as? ImageElement
    val selectionLocked get() = selection.any { it.locked }
    val inCropMode get() = cropTarget != null

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
    private var maxPointers = 1

    // Laço e seleção
    private val lassoPath = Path()
    private val lassoXs = ArrayList<Float>()
    private val lassoYs = ArrayList<Float>()
    private val selection = ArrayList<Element>()
    private val selectionSet = HashSet<Element>()
    /** Transformação em andamento (mover/escalar/girar), aplicada só na prévia até soltar. */
    private var selT = Transform()
    private var gestureFrame: Frame? = null
    private var startWX = 0f
    private var startWY = 0f

    // Edição de imagem pelo painel de ajustes (prévia sem gravar no desfazer a cada mudança)
    private var draftOriginal: ImageElement? = null
    private var draftCurrent: ImageElement? = null

    // Recorte
    private var cropTarget: ImageElement? = null
    private val cropRect = RectF(0f, 0f, 1f, 1f)
    var cropShape = CropShape.RECT
        private set
    private var cropFree = ArrayList<Float>()
    private var cropDragEdges = 0 // bits: 1=esq, 2=topo, 4=dir, 8=base, 16=mover
    private var cropLastNX = 0f
    private var cropLastNY = 0f

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
        color = Ui.ACCENT
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Ui.ACCENT
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val handleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }
    private val cropShade = Paint().apply { color = Color.argb(110, 15, 18, 24) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gridPoints = FloatArray(0)
    private val visibleWorld = RectF()
    private val rotateIcon by lazy { IconDrawable(Icon.ROTATE, Color.WHITE, (16 * density).toInt(), 2f * density) }
    private val lockIcon by lazy { IconDrawable(Icon.LOCK, Color.WHITE, (14 * density).toInt(), 2f * density) }

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
        pushAction(EditAction(ops))
    }

    private fun pushAction(a: EditAction) {
        undoStack.add(a)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
        changed()
    }

    /** Troca elementos mantendo a posição de cada um na pilha (uma ação de desfazer). */
    private fun replaceAll(pairs: List<Pair<Element, Element>>) {
        begin()
        for ((old, new) in pairs) {
            val i = elements.indexOf(old)
            if (i < 0 || old === new) continue
            removeAt(i)
            insertAt(i, new)
        }
        commit()
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        finishDraft()
        cancelCrop()
        clearSelection()
        action.undo(elements)
        redoStack.add(action)
        changed()
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        finishDraft()
        cancelCrop()
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

    fun addText(e: TextElement) {
        begin()
        insertAt(elements.size, e)
        commit()
    }

    /** Troca a caixa [old] por [new] (ou apaga, se [new] for nulo). */
    fun replaceText(old: TextElement, new: TextElement?) {
        val i = elements.indexOf(old)
        if (i < 0) return
        begin()
        removeAt(i)
        if (new != null) insertAt(i, new)
        commit()
        if (selection.contains(old)) {
            if (new != null) setSelection(listOf(new)) else clearSelection()
        }
    }

    /** Texto sendo editado na tela (não é desenhado pelo canvas enquanto isso). */
    var editingText: TextElement? = null
        set(v) { field = v; invalidate() }

    /** Quando não nulo, recebe o primeiro toque (ex.: para terminar a edição de texto). */
    var touchInterceptor: (() -> Boolean)? = null

    val zoom get() = scale
    fun screenX(wx: Float) = wx * scale + offsetX
    fun screenY(wy: Float) = wy * scale + offsetY
    fun worldX(sx: Float) = wx(sx)
    fun worldY(sy: Float) = wy(sy)

    /** Rola a tela (sem mudar o zoom) para que o retângulo do mundo [r] fique visível. */
    fun ensureVisible(r: RectF, marginPx: Float) {
        var dx = 0f
        var dy = 0f
        val l = screenX(r.left); val t = screenY(r.top); val rr = screenX(r.right); val b = screenY(r.bottom)
        if (b > height - marginPx) dy = height - marginPx - b
        if (t + dy < marginPx) dy = marginPx - t
        if (rr > width - marginPx) dx = width - marginPx - rr
        if (l + dx < marginPx) dx = marginPx - l
        if (dx != 0f || dy != 0f) {
            offsetX += dx
            offsetY += dy
            invalidate()
            listener?.onStateChanged()
        }
    }

    /** Aplica [change] aos textos selecionados (uma ação de desfazer). */
    fun editTexts(change: (TextElement) -> TextElement) {
        val pairs = selection.map { if (it is TextElement) it to change(it) else it to it }
        replaceAll(pairs)
        setSelection(pairs.map { it.second })
    }

    val selectedText: TextElement? get() = selection.singleOrNull() as? TextElement

    private fun viewCenterWorld() = wx(width / 2f) to wy(height / 2f)

    /** Insere uma imagem no meio da área visível e já deixa ela selecionada. */
    fun addImage(file: String, imgW: Int, imgH: Int) {
        val visW = width / scale
        val w = min(visW * 0.6f, imgW.toFloat() / density * 1.5f)
        val h = w * imgH / max(1, imgW)
        val (cx, cy) = viewCenterWorld()
        val e = ImageElement(file, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
        begin()
        insertAt(elements.size, e)
        commit()
        tool = Tool.SELECT
        setSelection(listOf(e))
    }

    /** Insere várias imagens (páginas de PDF) por baixo dos traços, numa única ação. */
    fun addPages(pages: List<ImageElement>) {
        if (pages.isEmpty()) return
        begin()
        pages.forEachIndexed { i, p -> insertAt(i, p) }
        commit()
        val r = RectF(pages[0].bounds)
        // Enquadra a primeira página
        val fit = min(width / (r.width() + 60f), height / (r.height() + 60f)).coerceIn(MIN_SCALE, 1.5f)
        scale = fit
        offsetX = width / 2f - r.centerX() * scale
        offsetY = max(96f * density, height / 2f - r.height() * scale / 2f) - r.top * scale
        invalidate()
        listener?.onStateChanged()
    }

    /** Ponto (mundo) onde começar a colocar um conteúdo grande: abaixo do que já existe. */
    fun freeSpotBelow(): Pair<Float, Float> {
        val c = contentBounds() ?: return 0f to 0f
        return c.left to c.bottom + 80f
    }

    // ---- Seleção -----------------------------------------------------------

    fun clearSelection() {
        finishDraft()
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

    /** Seleciona um item específico (troca a ferramenta para seleção). */
    fun select(e: Element) {
        if (e !in elements) return
        tool = Tool.SELECT
        setSelection(listOf(e))
    }

    fun elementsSnapshot(): List<Element> = ArrayList(elements)

    fun selectAll() {
        tool = Tool.SELECT
        setSelection(elements.filter { !it.locked })
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
        val copies = selection.map {
            val c = it.transformed(Transform.translate(d, d))
            if (c is ImageElement && c.locked) c.with(locked = false) else c
        }
        begin()
        for (c in copies) insertAt(elements.size, c)
        commit()
        setSelection(copies)
    }

    fun recolorSelection(color: Int) {
        if (selection.isEmpty()) return
        val pairs = selection.map {
            it to when (it) {
                is StrokeElement -> it.withColor(color)
                is TextElement -> it.withColor(color)
                is ImageElement -> it.with(borderColor = color, borderWidth = if (it.borderWidth > 0f) it.borderWidth else 6f)
            }
        }
        replaceAll(pairs)
        setSelection(pairs.map { it.second })
    }

    /** Traz a seleção para a frente (true) ou envia para trás (false), mantendo a ordem interna. */
    fun reorderSelection(toFront: Boolean) {
        if (selection.isEmpty()) return
        val ordered = elements.filter { it in selectionSet }
        begin()
        for (i in elements.indices.reversed()) if (elements[i] in selectionSet) removeAt(i)
        if (toFront) ordered.forEach { insertAt(elements.size, it) }
        else ordered.forEachIndexed { k, e -> insertAt(k, e) }
        commit()
        invalidate()
    }

    /** Aplica [change] às imagens selecionadas (uma ação de desfazer). */
    fun editImages(change: (ImageElement) -> ImageElement) {
        val pairs = selection.map { if (it is ImageElement) it to change(it) else it to it }
        replaceAll(pairs)
        setSelection(pairs.map { it.second })
    }

    fun rotateSelection(degrees: Float) {
        val f = selectionFrame() ?: return
        val t = Transform(rotation = degrees, ox = f.cx, oy = f.cy)
        val pairs = selection.map { it to it.transformed(t) }
        replaceAll(pairs)
        setSelection(pairs.map { it.second })
    }

    fun setLocked(locked: Boolean) {
        editImages { it.with(locked = locked) }
    }

    /** Troca o arquivo da imagem selecionada mantendo posição, largura e efeitos. */
    fun replaceSelectedImage(file: String, imgW: Int, imgH: Int) {
        val img = selectedImage ?: return
        val w = img.rect.width()
        val h = w * imgH / max(1, imgW)
        val n = img.with(file = file, crop = RectF(0f, 0f, 1f, 1f), freeMask = null,
            mask = if (img.mask == ImageMask.FREE) ImageMask.RECT else img.mask,
            rect = RectF(img.centerX - w / 2, img.centerY - h / 2, img.centerX + w / 2, img.centerY + h / 2))
        replaceAll(listOf(img to n))
        setSelection(listOf(n))
    }

    // ---- Rascunho de edição de imagem (painel de ajustes) ---------------------------

    fun beginDraft() {
        val img = selectedImage ?: return
        draftOriginal = img
        draftCurrent = img
    }

    /** Mostra a mudança na hora, sem criar uma entrada de desfazer a cada movimento. */
    fun updateDraft(change: (ImageElement) -> ImageElement) {
        val cur = draftCurrent ?: return
        val n = change(cur)
        val i = elements.indexOf(cur)
        if (i < 0) return
        elements[i] = n
        draftCurrent = n
        selection.clear(); selection.add(n)
        selectionSet.clear(); selectionSet.add(n)
        invalidate()
    }

    fun finishDraft() {
        val orig = draftOriginal ?: return
        val cur = draftCurrent ?: return
        draftOriginal = null
        draftCurrent = null
        if (orig === cur) return
        val i = elements.indexOf(cur)
        if (i < 0) return
        pushAction(EditAction(listOf(Op.Remove(i, orig), Op.Insert(i, cur))))
        listener?.onSelectionChanged(selection.size)
    }

    // ---- Quadro da seleção ---------------------------------------------------------

    /** Quadro (centro, tamanho, ângulo) em coordenadas do mundo. */
    class Frame(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float) {
        fun corners(): FloatArray {
            val t = Transform(rotation = angle, ox = cx, oy = cy)
            val xs = floatArrayOf(cx - w / 2, cx + w / 2, cx + w / 2, cx - w / 2)
            val ys = floatArrayOf(cy - h / 2, cy - h / 2, cy + h / 2, cy + h / 2)
            return FloatArray(8) { i -> if (i % 2 == 0) t.x(xs[i / 2], ys[i / 2]) else t.y(xs[i / 2], ys[i / 2]) }
        }

        fun contains(px: Float, py: Float, pad: Float): Boolean {
            val t = Transform(rotation = -angle, ox = cx, oy = cy)
            val lx = t.x(px, py) - cx
            val ly = t.y(px, py) - cy
            return abs(lx) <= w / 2 + pad && abs(ly) <= h / 2 + pad
        }
    }

    private fun selectionFrame(): Frame? {
        if (selection.isEmpty()) return null
        val img = selectedImage
        if (img != null) return Frame(img.centerX, img.centerY, img.rect.width(), img.rect.height(), img.rotation)
        val txt = selectedText
        if (txt != null) {
            val p = txt.pad
            return Frame(txt.centerX, txt.centerY, txt.boxWidth + p * 2, txt.boxHeight + p * 2, txt.rotation)
        }
        val r = RectF(selection[0].bounds)
        for (e in selection) r.union(e.bounds)
        return Frame(r.centerX(), r.centerY(), r.width(), r.height(), 0f)
    }

    /** Cantos do quadro na tela, já com o gesto em andamento. */
    private fun frameScreenCorners(f: Frame): FloatArray {
        val c = f.corners()
        val pad = 6f * density / scale
        // Afasta cada canto do centro um pouco (folga visual)
        for (i in 0 until 4) {
            val dx = c[i * 2] - f.cx
            val dy = c[i * 2 + 1] - f.cy
            val len = max(0.001f, hypot(dx, dy))
            c[i * 2] += dx / len * pad * 1.4f
            c[i * 2 + 1] += dy / len * pad * 1.4f
        }
        for (i in 0 until 4) {
            val x = c[i * 2]
            val y = c[i * 2 + 1]
            c[i * 2] = selT.x(x, y) * scale + offsetX
            c[i * 2 + 1] = selT.y(x, y) * scale + offsetY
        }
        return c
    }

    /** Posição da alça de girar na tela: acima do meio da borda de cima. */
    private fun rotateHandle(c: FloatArray): Pair<Float, Float> {
        val mx = (c[0] + c[2]) / 2f
        val my = (c[1] + c[3]) / 2f
        val bx = (c[4] + c[6]) / 2f
        val by = (c[5] + c[7]) / 2f
        val len = max(0.001f, hypot(mx - bx, my - by))
        val off = 34f * density
        return (mx + (mx - bx) / len * off) to (my + (my - by) / len * off)
    }

    private fun commitSelectionTransform() {
        if (selT.isIdentity) {
            selT = Transform()
            return
        }
        val t = selT
        selT = Transform()
        val pairs = selection.map { it to it.transformed(t) }
        replaceAll(pairs)
        setSelection(pairs.map { it.second })
    }

    // ---- Recorte -------------------------------------------------------------------

    fun startCrop() {
        val img = selectedImage ?: return
        if (img.locked) return
        cropTarget = img
        cropRect.set(img.crop)
        cropFree.clear()
        cropShape = when (img.mask) {
            ImageMask.ELLIPSE -> CropShape.ELLIPSE
            ImageMask.FREE -> CropShape.LASSO
            else -> CropShape.RECT
        }
        img.freeMask?.let { m -> cropFree.addAll(m.toList()) }
        listener?.onCropModeChanged(true)
        invalidate()
    }

    fun setCropShape(shape: CropShape) {
        val img = cropTarget ?: return
        cropShape = shape
        if (shape == CropShape.LASSO) {
            cropFree.clear()
        } else {
            val aspect = when (shape) {
                CropShape.SQUARE, CropShape.ELLIPSE -> if (shape == CropShape.SQUARE) 1f else null
                CropShape.WIDE -> 16f / 9f
                else -> null
            }
            if (aspect != null) fitAspect(img, aspect)
        }
        listener?.onCropModeChanged(true)
        invalidate()
    }

    /** Maior retângulo com a proporção [aspect] (largura/altura) centrado no recorte atual. */
    private fun fitAspect(img: ImageElement, aspect: Float) {
        val full = img.fullLocalFrame()
        val fw = full.width()
        val fh = full.height()
        val cx = cropRect.centerX()
        val cy = cropRect.centerY()
        var wn = 1f
        var hn = wn * fw / (fh * aspect)
        if (hn > 1f) {
            hn = 1f
            wn = hn * fh * aspect / fw
        }
        val l = (cx - wn / 2f).coerceIn(0f, 1f - wn)
        val t = (cy - hn / 2f).coerceIn(0f, 1f - hn)
        cropRect.set(l, t, l + wn, t + hn)
    }

    fun resetCrop() {
        cropRect.set(0f, 0f, 1f, 1f)
        cropFree.clear()
        cropShape = CropShape.RECT
        invalidate()
    }

    fun applyCrop() {
        val img = cropTarget ?: return
        val n = when (cropShape) {
            CropShape.LASSO -> {
                if (cropFree.size >= 6) {
                    val xs = cropFree.filterIndexed { i, _ -> i % 2 == 0 }
                    val ys = cropFree.filterIndexed { i, _ -> i % 2 == 1 }
                    val r = RectF(xs.min().coerceIn(0f, 1f), ys.min().coerceIn(0f, 1f), xs.max().coerceIn(0f, 1f), ys.max().coerceIn(0f, 1f))
                    if (r.width() < 0.01f || r.height() < 0.01f) img
                    else img.withCrop(r, ImageMask.FREE, cropFree.toFloatArray())
                } else img
            }
            CropShape.ELLIPSE -> img.withCrop(RectF(cropRect), ImageMask.ELLIPSE, null)
            else -> img.withCrop(RectF(cropRect),
                if (img.mask == ImageMask.ROUNDED) ImageMask.ROUNDED else ImageMask.RECT, null)
        }
        cropTarget = null
        listener?.onCropModeChanged(false)
        if (n !== img) {
            replaceAll(listOf(img to n))
            setSelection(listOf(n))
        }
        invalidate()
    }

    fun cancelCrop() {
        if (cropTarget == null) return
        cropTarget = null
        listener?.onCropModeChanged(false)
        invalidate()
    }

    /** Ponto do mundo -> fração (0..1) da imagem original. */
    private fun toImageFraction(img: ImageElement, px: Float, py: Float): Pair<Float, Float> {
        var (lx, ly) = img.toLocal(px, py)
        if (img.flipH) lx = -lx
        if (img.flipV) ly = -ly
        val full = img.fullLocalFrame()
        return (lx - full.left) / full.width() to (ly - full.top) / full.height()
    }

    private fun cropDown(px: Float, py: Float) {
        val img = cropTarget ?: return
        val (nx, ny) = toImageFraction(img, px, py)
        cropLastNX = nx
        cropLastNY = ny
        if (cropShape == CropShape.LASSO) {
            cropFree.clear()
            cropFree.add(nx.coerceIn(0f, 1f)); cropFree.add(ny.coerceIn(0f, 1f))
            return
        }
        val full = img.fullLocalFrame()
        val tx = 24f * density / scale / full.width()
        val ty = 24f * density / scale / full.height()
        var e = 0
        if (ny in cropRect.top - ty..cropRect.bottom + ty) {
            if (abs(nx - cropRect.left) < tx) e = e or 1
            if (abs(nx - cropRect.right) < tx) e = e or 4
        }
        if (nx in cropRect.left - tx..cropRect.right + tx) {
            if (abs(ny - cropRect.top) < ty) e = e or 2
            if (abs(ny - cropRect.bottom) < ty) e = e or 8
        }
        if (e == 0 && cropRect.contains(nx, ny)) e = 16
        cropDragEdges = e
    }

    private fun cropMove(px: Float, py: Float) {
        val img = cropTarget ?: return
        val (nx, ny) = toImageFraction(img, px, py)
        if (cropShape == CropShape.LASSO) {
            cropFree.add(nx.coerceIn(0f, 1f)); cropFree.add(ny.coerceIn(0f, 1f))
            invalidate()
            return
        }
        val dx = nx - cropLastNX
        val dy = ny - cropLastNY
        cropLastNX = nx
        cropLastNY = ny
        val minN = 0.04f
        val r = cropRect
        val e = cropDragEdges
        if (e == 16) {
            val mx = dx.coerceIn(-r.left, 1f - r.right)
            val my = dy.coerceIn(-r.top, 1f - r.bottom)
            r.offset(mx, my)
        } else {
            if (e and 1 != 0) r.left = (r.left + dx).coerceIn(0f, r.right - minN)
            if (e and 4 != 0) r.right = (r.right + dx).coerceIn(r.left + minN, 1f)
            if (e and 2 != 0) r.top = (r.top + dy).coerceIn(0f, r.bottom - minN)
            if (e and 8 != 0) r.bottom = (r.bottom + dy).coerceIn(r.top + minN, 1f)
            val aspect = when (cropShape) {
                CropShape.SQUARE -> 1f
                CropShape.WIDE -> 16f / 9f
                else -> null
            }
            if (aspect != null && e != 0) {
                val full = img.fullLocalFrame()
                val hn = r.width() * full.width() / (full.height() * aspect)
                if (e and 2 != 0 && e and 8 == 0) r.top = (r.bottom - hn).coerceAtLeast(0f)
                else r.bottom = (r.top + hn).coerceAtMost(1f)
            }
        }
        invalidate()
    }

    // ---- Câmera, carregar e salvar -----------------------------------------

    /** Leva a câmera de volta para o conteúdo (ou para a origem, se vazio). */
    fun recenter() {
        val content = contentBounds()
        if (content == null) {
            scale = 1f
            offsetX = width * 0.08f
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

    /** Zoom para [target] (1 = 100%) mantendo o centro da tela no lugar. */
    fun zoomTo(target: Float) {
        val t = target.coerceIn(MIN_SCALE, MAX_SCALE)
        val cx = width / 2f
        val cy = height / 2f
        offsetX = cx - (cx - offsetX) * (t / scale)
        offsetY = cy - (cy - offsetY) * (t / scale)
        scale = t
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
        drawContent(c, null, s)
        return bmp
    }

    /** Desenha os elementos num canvas já transformado para o mundo. */
    fun drawContent(canvas: Canvas, clip: RectF?, renderScale: Float = 1f) {
        for (e in elements) {
            if (clip != null && !RectF.intersects(e.bounds, clip)) continue
            drawElement(canvas, e, renderScale, sync = true)
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

    private var swallowing = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Um toque fora do texto em edição só termina a edição (não risca a nota).
        if (e.actionMasked == MotionEvent.ACTION_DOWN && touchInterceptor?.invoke() == true) {
            swallowing = true
            return true
        }
        if (swallowing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
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
        maxPointers = 1
        val x = e.getX(0)
        val y = e.getY(0)
        downX = x
        downY = y
        val pen = isPen(e, 0)
        if (pen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Entrega os pontos da caneta sem esperar o próximo frame: menos atraso.
            try { requestUnbufferedDispatch(e) } catch (_: Exception) { }
        }
        // No recorte, caneta e dedo ajustam o recorte (dois dedos ainda movem a tela).
        if (cropTarget != null) {
            gesture = Gesture.CROP
            cropDown(wx(x), wy(y))
            return
        }
        // Com seleção, o dedo também pode arrastar/girar/escalar a seleção.
        val selGesture = if (tool == Tool.SELECT && selection.isNotEmpty()) selectionGestureAt(x, y) else null
        if (!pen && !fingerDraws && (selGesture == null || selGesture == Gesture.LASSO)) {
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
            else -> selGesture ?: Gesture.LASSO
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
                lassoXs.clear(); lassoYs.clear()
                lassoPath.reset()
                lassoPath.moveTo(x, y)
                lassoXs.add(wx(x)); lassoYs.add(wy(y))
            }
            Gesture.MOVE_SEL, Gesture.SCALE_SEL, Gesture.ROTATE_SEL -> {
                finishDraft()
                gestureFrame = selectionFrame()
                startWX = wx(x); startWY = wy(y)
                selT = Transform()
            }
            else -> Unit
        }
    }

    private fun pen(): PenSettings = pen

    private fun selectionGestureAt(x: Float, y: Float): Gesture {
        val f = selectionFrame() ?: return Gesture.LASSO
        if (selectionLocked) return Gesture.LASSO
        val c = frameScreenCorners(f)
        val r = 26f * density
        val (rx, ry) = rotateHandle(c)
        if (hypot(x - rx, y - ry) <= r) return Gesture.ROTATE_SEL
        if (hypot(x - c[4], y - c[5]) <= r) return Gesture.SCALE_SEL
        if (f.contains(wx(x), wy(y), 10f * density / scale)) return Gesture.MOVE_SEL
        return Gesture.LASSO
    }

    private fun onPointerDown(e: MotionEvent) {
        maxPointers = max(maxPointers, e.pointerCount)
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
                selT = Transform.translate(wx(x) - startWX, wy(y) - startWY)
                invalidate()
            }
            Gesture.SCALE_SEL -> {
                val f = gestureFrame ?: return
                val d0 = hypot(startWX - f.cx, startWY - f.cy)
                val d1 = hypot(wx(x) - f.cx, wy(y) - f.cy)
                if (d0 > 0.01f) selT = Transform(scale = (d1 / d0).coerceIn(0.05f, 40f), ox = f.cx, oy = f.cy)
                invalidate()
            }
            Gesture.ROTATE_SEL -> {
                val f = gestureFrame ?: return
                val a0 = Math.toDegrees(atan2((startWY - f.cy).toDouble(), (startWX - f.cx).toDouble()))
                val a1 = Math.toDegrees(atan2((wy(y) - f.cy).toDouble(), (wx(x) - f.cx).toDouble()))
                var rot = (a1 - a0).toFloat()
                // "Imã" nos ângulos retos
                val target = f.angle + rot
                val snapped = (target / 90f).roundToInt() * 90f
                if (abs(target - snapped) < 4f) rot = snapped - f.angle
                selT = Transform(rotation = rot, ox = f.cx, oy = f.cy)
                invalidate()
            }
            Gesture.CROP -> {
                for (h in 0 until e.historySize) cropMove(wx(e.getHistoricalX(idx, h)), wy(e.getHistoricalY(idx, h)))
                cropMove(wx(x), wy(y))
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
        if (gesture == Gesture.NAVIGATE && tool == Tool.SELECT && maxPointers == 1 &&
            hypot(e.x - downX, e.y - downY) < 10f * density
        ) {
            // Toque de dedo com a seleção ativa: seleciona o item tocado.
            val hit = hitTest(wx(downX), wy(downY))
            if (hit != null) setSelection(listOf(hit)) else clearSelection()
        }
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
        val tapped = hypot(e.x - downX, e.y - downY) < 10f * density
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
                if (tapped) {
                    // Toque simples: seleciona o item tocado (inclusive imagens travadas).
                    val hit = hitTest(wx(downX), wy(downY))
                    if (hit != null) setSelection(listOf(hit)) else clearSelection()
                } else if (lassoXs.size >= 3) {
                    val lasso = Lasso(lassoXs.toFloatArray(), lassoYs.toFloatArray())
                    setSelection(elements.filter { it.insideLasso(lasso) })
                }
                lassoPath.reset()
            }
            Gesture.MOVE_SEL, Gesture.SCALE_SEL, Gesture.ROTATE_SEL -> {
                gestureFrame = null
                if (tapped && gesture == Gesture.MOVE_SEL) {
                    selT = Transform()
                    // Toque dentro da seleção: troca para o item tocado, se for outro.
                    val hit = hitTest(wx(downX), wy(downY))
                    val single = selection.singleOrNull()
                    if (hit != null && selection.size > 1) setSelection(listOf(hit))
                    // Tocar de novo num texto selecionado abre a edição (como no Samsung Notes).
                    else if (single is TextElement && hit === single) listener?.onTextRequest(single.x, single.y, single)
                } else {
                    commitSelectionTransform()
                }
            }
            Gesture.TAP_TEXT -> {
                if (tapped) {
                    val px = wx(downX)
                    val py = wy(downY)
                    val hit = elements.lastOrNull { it is TextElement && it.contains(px, py) } as TextElement?
                    listener?.onTextRequest(px, py, hit)
                }
            }
            Gesture.CROP -> invalidate()
            else -> Unit
        }
        gesture = Gesture.NONE
        eraserX = -1f
        invalidate()
    }

    /** Item no topo da pilha sob o ponto (mundo). */
    private fun hitTest(px: Float, py: Float): Element? {
        val r = 10f * density / scale
        for (i in elements.indices.reversed()) {
            val e = elements[i]
            val hit = when (e) {
                is ImageElement -> e.contains(px, py)
                is TextElement -> e.contains(px, py)
                is StrokeElement -> e.hits(px, py, r)
            }
            if (hit) return e
        }
        return null
    }

    /** Cancela o que a ferramenta estava fazendo (palma detectada, segundo dedo...). */
    private fun abortToolGesture() {
        when (gesture) {
            Gesture.DRAW -> builder = null
            Gesture.ERASE -> commit()
            Gesture.LASSO -> lassoPath.reset()
            Gesture.MOVE_SEL, Gesture.SCALE_SEL, Gesture.ROTATE_SEL -> selT = Transform()
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
            var newScale = (scale * span / lastSpan).coerceIn(MIN_SCALE, MAX_SCALE)
            // "Ímã" em 100%: fica fácil voltar ao tamanho real com a pinça.
            if (abs(newScale - 1f) < 0.035f) newScale = 1f
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        images.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(paperColor)
        visibleWorld.set(wx(0f), wy(0f), wx(width.toFloat()), wy(height.toFloat()))
        drawPattern(canvas)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        val moving = !selT.isIdentity
        val crop = cropTarget
        for (e in elements) {
            if (e === crop) continue
            if (moving && e in selectionSet) {
                canvas.save()
                selT.applyTo(canvas)
                drawElement(canvas, e, scale, sync = false)
                canvas.restore()
            } else if (RectF.intersects(e.bounds, visibleWorld)) {
                drawElement(canvas, e, scale, sync = false)
            }
        }
        builder?.let { StrokeRenderer.drawBuilder(canvas, it) }
        if (crop != null) drawCrop(canvas, crop)
        canvas.restore()

        if (gesture == Gesture.LASSO) canvas.drawPath(lassoPath, dashPaint)
        if (crop == null) drawSelection(canvas)
        if (eraserX >= 0f) {
            canvas.drawCircle(eraserX, eraserY, eraserSize * density, eraserPaint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val f = selectionFrame() ?: return
        val c = frameScreenCorners(f)
        val p = Path().apply {
            moveTo(c[0], c[1]); lineTo(c[2], c[3]); lineTo(c[4], c[5]); lineTo(c[6], c[7]); close()
        }
        val locked = selectionLocked
        framePaint.color = if (locked) Color.parseColor("#8A94A6") else Ui.ACCENT
        canvas.drawPath(p, if (selection.size == 1) framePaint else dashPaint)
        if (locked) {
            // Selo de cadeado no canto
            val (x, y) = c[0] to c[1]
            canvas.drawCircle(x, y, 13f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A94A6") })
            val s = (14 * density).toInt()
            lockIcon.setBounds((x - s / 2).toInt(), (y - s / 2).toInt(), (x + s / 2).toInt(), (y + s / 2).toInt())
            lockIcon.draw(canvas)
            return
        }
        // Cantos
        for (i in listOf(0, 1, 3)) {
            canvas.drawCircle(c[i * 2], c[i * 2 + 1], 5f * density, handleInner)
            canvas.drawCircle(c[i * 2], c[i * 2 + 1], 5f * density, framePaint)
        }
        // Alça de escala (canto inferior direito)
        canvas.drawCircle(c[4], c[5] + 1.5f * density, 12f * density, handleShadow)
        canvas.drawCircle(c[4], c[5], 11f * density, handlePaint)
        canvas.drawCircle(c[4], c[5], 4.5f * density, handleInner)
        // Alça de rotação
        val (rx, ry) = rotateHandle(c)
        canvas.drawLine((c[0] + c[2]) / 2f, (c[1] + c[3]) / 2f, rx, ry, framePaint)
        canvas.drawCircle(rx, ry + 1.5f * density, 14f * density, handleShadow)
        canvas.drawCircle(rx, ry, 13f * density, handlePaint)
        val s = (16 * density).toInt()
        rotateIcon.setBounds((rx - s / 2).toInt(), (ry - s / 2).toInt(), (rx + s / 2).toInt(), (ry + s / 2).toInt())
        rotateIcon.draw(canvas)
        // Ângulo durante a rotação
        if (gesture == Gesture.ROTATE_SEL) {
            val a = ImageElement.normalizeAngle(f.angle + selT.rotation).roundToInt()
            textPaint.textSize = 13f * density
            textPaint.color = Ui.ACCENT
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("$a°", rx, ry - 22f * density, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    /** Modo de recorte: imagem inteira escurecida, área mantida em destaque e alças. */
    private fun drawCrop(canvas: Canvas, img: ImageElement) {
        val full = img.fullLocalFrame()
        // A imagem inteira, sem recorte (o próprio draw aplica rotação e espelhamento)
        images.draw(canvas, img, scale, sync = false, showFull = true)

        canvas.save()
        canvas.translate(img.centerX, img.centerY)
        canvas.rotate(img.rotation)
        canvas.scale(if (img.flipH) -1f else 1f, if (img.flipV) -1f else 1f)
        fun fx(n: Float) = full.left + n * full.width()
        fun fy(n: Float) = full.top + n * full.height()
        val keep = Path()
        if (cropShape == CropShape.LASSO) {
            if (cropFree.size >= 4) {
                keep.moveTo(fx(cropFree[0]), fy(cropFree[1]))
                var i = 2
                while (i + 1 < cropFree.size) { keep.lineTo(fx(cropFree[i]), fy(cropFree[i + 1])); i += 2 }
                keep.close()
            }
        } else {
            val r = RectF(fx(cropRect.left), fy(cropRect.top), fx(cropRect.right), fy(cropRect.bottom))
            if (cropShape == CropShape.ELLIPSE) keep.addOval(r, Path.Direction.CW) else keep.addRect(r, Path.Direction.CW)
        }
        // Escurece fora da área mantida
        val shade = Path().apply {
            addRect(full, Path.Direction.CW)
            if (!keep.isEmpty) addPath(keep)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(shade, cropShade)
        val sw = 2f * density / scale
        framePaint.color = Color.WHITE
        framePaint.strokeWidth = sw
        canvas.drawPath(keep, framePaint)
        if (cropShape != CropShape.LASSO) {
            val r = RectF(fx(cropRect.left), fy(cropRect.top), fx(cropRect.right), fy(cropRect.bottom))
            // Linhas de terço
            framePaint.strokeWidth = sw / 2f
            for (k in 1..2) {
                canvas.drawLine(r.left + r.width() * k / 3f, r.top, r.left + r.width() * k / 3f, r.bottom, framePaint)
                canvas.drawLine(r.left, r.top + r.height() * k / 3f, r.right, r.top + r.height() * k / 3f, framePaint)
            }
            // Cantoneiras grossas
            framePaint.strokeWidth = sw * 2.2f
            val len = min(r.width(), r.height()) * 0.18f
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)) {
                val dx = if (x == r.left) len else -len
                val dy = if (y == r.top) len else -len
                canvas.drawLine(x, y, x + dx, y, framePaint)
                canvas.drawLine(x, y, x, y + dy, framePaint)
            }
        }
        framePaint.strokeWidth = 1.5f * density
        framePaint.color = Ui.ACCENT
        canvas.restore()
    }

    private fun drawElement(canvas: Canvas, e: Element, renderScale: Float, sync: Boolean) {
        when (e) {
            is StrokeElement -> StrokeRenderer.drawElement(canvas, e)
            is TextElement -> if (e !== editingText) drawText(canvas, e)
            is ImageElement -> images.draw(canvas, e, renderScale, sync)
        }
    }

    private val textBg = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawText(canvas: Canvas, e: TextElement) {
        canvas.save()
        canvas.translate(e.centerX, e.centerY)
        canvas.rotate(e.rotation)
        canvas.scale(if (e.flipH) -1f else 1f, if (e.flipV) -1f else 1f)
        canvas.translate(-e.boxWidth / 2f, -e.boxHeight / 2f)
        if (e.bgColor != 0) {
            textBg.color = e.bgColor
            val p = e.pad
            canvas.drawRoundRect(RectF(-p, -p, e.boxWidth + p, e.boxHeight + p), p, p, textBg)
        }
        e.layout.draw(canvas)
        canvas.restore()
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
        private const val MIN_SCALE = 0.03f
        private const val MAX_SCALE = 8f
        private const val MAX_UNDO = 300
        private const val GRID_SPACING = 40f
        private const val FINGER_PRESSURE = 0.6f
    }
}
