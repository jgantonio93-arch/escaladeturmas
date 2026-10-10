package com.jgantonio.notasinfinitas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import com.jgantonio.notasinfinitas.Ui.dp

/** Fileira de canetas em pé; a selecionada fica "levantada". */
@SuppressLint("ViewConstructor")
class PenRackView(context: Context, private val colorOf: (BrushType) -> Int, private val onPick: (BrushType) -> Unit) : View(context) {
    var selected: BrushType = BrushType.PEN
        set(v) { field = v; invalidate() }

    private val types = BrushType.entries
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(11f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, context.dp(118f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val slot = width / types.size.toFloat()
        val u = context.dp(1f)
        val penW = minOf(slot * 0.42f, context.dp(26f))
        val labelY = height - context.dp(6f)
        val penBottom = height - context.dp(22f)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), penBottom)
        for ((i, t) in types.withIndex()) {
            val cx = slot * (i + 0.5f)
            val top = if (t == selected) context.dp(4f) else context.dp(24f)
            val w = if (t == BrushType.HIGHLIGHTER) penW * 1.18f else penW
            PenArt.draw(canvas, t, colorOf(t), cx, top, penBottom, w, u)
        }
        canvas.restore()
        for ((i, t) in types.withIndex()) {
            val cx = slot * (i + 0.5f)
            val sel = t == selected
            labelPaint.color = if (sel) Ui.ACCENT else Ui.MUTED
            labelPaint.isFakeBoldText = sel
            canvas.drawText(t.shortLabel, cx, labelY, labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / types.size.toFloat())).toInt().coerceIn(0, types.size - 1)
            selected = types[i]
            onPick(types[i])
        }
        return true
    }
}

/** Base dos sliders desenhados (espessura e opacidade). */
abstract class FancySlider(context: Context, val min: Float, val max: Float, value: Float, private val onChange: (Float) -> Unit) : View(context) {
    var value = value.coerceIn(min, max)
        set(v) { field = v.coerceIn(min, max); invalidate() }

    protected val thumbR = context.dp(12f)
    protected val trackPad = context.dp(14f)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(context.dp(3f), 0f, context.dp(1f), Color.parseColor("#40000000"))
    }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = Color.parseColor("#D0D5DD")
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // sombra do botão do slider
    }

    protected val fraction get() = (value - min) / (max - min)
    protected val left get() = trackPad
    protected val right get() = width - trackPad
    protected val thumbX get() = left + (right - left) * fraction

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(40f).toInt())
    }

    abstract fun drawTrack(canvas: Canvas, cy: Float)

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        drawTrack(canvas, cy)
        canvas.drawCircle(thumbX, cy, thumbR, thumbFill)
        canvas.drawCircle(thumbX, cy, thumbR, thumbRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val f = ((e.x - left) / (right - left)).coerceIn(0f, 1f)
        val v = min + f * (max - min)
        if (v != value) {
            value = v
            onChange(value)
        }
        return true
    }
}

/** Slider de espessura: trilho em cunha (fino → grosso). */
class SizeSlider(context: Context, min: Float, max: Float, value: Float, onChange: (Float) -> Unit) :
    FancySlider(context, min, max, value, onChange) {
    var tint = Ui.ACCENT
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun wedge(l: Float, r: Float, cy: Float): Path {
        val h0 = context.dp(1.5f)
        val h1 = context.dp(7f)
        fun hAt(x: Float) = h0 + (h1 - h0) * ((x - left) / (right - left))
        return Path().apply {
            moveTo(l, cy - hAt(l))
            lineTo(r, cy - hAt(r))
            lineTo(r, cy + hAt(r))
            lineTo(l, cy + hAt(l))
            close()
        }
    }

    override fun drawTrack(canvas: Canvas, cy: Float) {
        paint.color = Color.parseColor("#E4E7EC")
        canvas.drawPath(wedge(left, right, cy), paint)
        paint.color = tint
        canvas.drawPath(wedge(left, thumbX, cy), paint)
    }
}

/** Slider de opacidade: xadrez + degradê do transparente à cor. */
class OpacitySlider(context: Context, min: Float, max: Float, value: Float, onChange: (Float) -> Unit) :
    FancySlider(context, min, max, value, onChange) {
    var color = Color.BLACK
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    override fun drawTrack(canvas: Canvas, cy: Float) {
        val h = context.dp(7f)
        val r = RectF(left, cy - h, right, cy + h)
        clip.reset()
        clip.addRoundRect(r, h, h, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val sq = h
        paint.shader = null
        var x = r.left
        var col = 0
        while (x < r.right) {
            var y = r.top
            var row = 0
            while (y < r.bottom) {
                paint.color = if ((col + row) % 2 == 0) Color.WHITE else Color.parseColor("#E1E4E8")
                canvas.drawRect(x, y, x + sq, y + sq, paint)
                y += sq; row++
            }
            x += sq; col++
        }
        val opaque = color or (0xFF shl 24)
        paint.shader = LinearGradient(r.left, 0f, r.right, 0f, opaque and 0x00FFFFFF, opaque, Shader.TileMode.CLAMP)
        canvas.drawRect(r, paint)
        paint.shader = null
        canvas.restore()
    }
}

/** Bolinha de cor com anel de seleção. */
@SuppressLint("ViewConstructor")
class ColorDot(context: Context, color: Int, checked: Boolean, onClick: () -> Unit) : View(context) {
    var color = color
        set(v) { field = v; invalidate() }
    var checked = checked
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        if (checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2.5f)
            paint.color = Ui.ACCENT
            canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2f, paint)
        }
        val inner = if (checked) r - context.dp(5.5f) else r - context.dp(2f)
        paint.style = Paint.Style.FILL
        paint.color = color or (0xFF shl 24)
        canvas.drawCircle(cx, cy, inner, paint)
        if (Color.luminance(color or (0xFF shl 24)) > 0.85f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(1f)
            paint.color = Ui.LINE
            canvas.drawCircle(cx, cy, inner, paint)
        }
    }
}

/** Botão "+" com anel de arco-íris para cor personalizada. */
class RainbowButton(context: Context, onClick: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(2f)
        paint.style = Paint.Style.FILL
        paint.shader = SweepGradient(cx, cy, intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
        ), null)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r - context.dp(3.5f), paint)
        paint.color = Ui.INK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        val a = r * 0.38f
        canvas.drawLine(cx - a, cy, cx + a, cy, paint)
        canvas.drawLine(cx, cy - a, cx, cy + a, paint)
    }
}

/** Amostra de traço com a caneta atual, sobre fundo cinza-claro arredondado. */
@SuppressLint("ViewConstructor")
class PenPreview(context: Context, pen: PenSettings) : View(context) {
    var pen = pen
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F4F5F8") }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), context.dp(14f), context.dp(14f), bg)
        val b = StrokeBuilder(pen, context.resources.displayMetrics.density * 0.5f)
        val margin = context.dp(28f)
        val steps = 90
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val x = margin + f * (w - 2 * margin)
            val y = h / 2 + kotlin.math.sin(f * 2 * Math.PI).toFloat() * h * 0.22f
            val p = 0.35f + 0.65f * kotlin.math.sin(f * Math.PI).toFloat()
            b.add(x, y, p, 0.5f)
        }
        StrokeRenderer.drawElement(canvas, b.build())
    }
}

/** Miniatura dos modelos de página (em branco, pontilhado, pautado, quadriculado). */
@SuppressLint("ViewConstructor")
class PageStyleTile(context: Context, val style: PageStyle, var paper: Int, checked: Boolean, onClick: () -> Unit) : View(context) {
    var checked = checked
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(context.dp(2f), context.dp(2f), width - context.dp(2f), height - context.dp(2f))
        val rad = context.dp(12f)
        paint.style = Paint.Style.FILL
        paint.color = paper
        canvas.drawRoundRect(r, rad, rad, paint)
        val dark = Color.luminance(paper) < 0.4f
        val lineC = if (dark) Color.parseColor("#4A5260") else Color.parseColor("#C9CED6")
        canvas.save()
        canvas.clipRect(r.left + rad / 2, r.top + rad / 2, r.right - rad / 2, r.bottom - rad / 2)
        val sp = context.dp(10f)
        paint.color = lineC
        paint.strokeWidth = context.dp(1f)
        when (style) {
            PageStyle.DOTS -> {
                var y = r.top + sp
                while (y < r.bottom) {
                    var x = r.left + sp
                    while (x < r.right) { canvas.drawCircle(x, y, context.dp(1.2f), paint); x += sp }
                    y += sp
                }
            }
            PageStyle.LINES, PageStyle.GRID -> {
                var y = r.top + sp
                while (y < r.bottom) { canvas.drawLine(r.left, y, r.right, y, paint); y += sp }
                if (style == PageStyle.GRID) {
                    var x = r.left + sp
                    while (x < r.right) { canvas.drawLine(x, r.top, x, r.bottom, paint); x += sp }
                }
            }
            PageStyle.BLANK -> Unit
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(if (checked) 2.5f else 1f)
        paint.color = if (checked) Ui.ACCENT else Ui.LINE
        canvas.drawRoundRect(r, rad, rad, paint)
    }
}

/** Slider de -max a +max com o preenchimento saindo do centro (brilho, contraste...). */
class CenterSlider(context: Context, limit: Float, value: Float, onChange: (Float) -> Unit) :
    FancySlider(context, -limit, limit, value, onChange) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawTrack(canvas: Canvas, cy: Float) {
        val h = context.dp(3f)
        paint.color = Color.parseColor("#E4E7EC")
        canvas.drawRoundRect(RectF(left, cy - h, right, cy + h), h, h, paint)
        val mid = (left + right) / 2f
        paint.color = Ui.ACCENT
        canvas.drawRoundRect(RectF(minOf(mid, thumbX), cy - h, maxOf(mid, thumbX), cy + h), h, h, paint)
        paint.color = Ui.MUTED
        canvas.drawCircle(mid, cy, context.dp(2.5f), paint)
    }
}
