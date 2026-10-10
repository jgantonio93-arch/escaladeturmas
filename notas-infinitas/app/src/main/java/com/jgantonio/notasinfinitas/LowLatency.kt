package com.jgantonio.notasinfinitas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.view.SurfaceView
import android.view.View
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/**
 * Pedaço de traço já em coordenadas da TELA, pronto para desenhar no front buffer.
 * Cada pedaço repete o último ponto do anterior, para a linha sair contínua.
 */
class InkSegment(val xs: FloatArray, val ys: FloatArray, val ws: FloatArray, val n: Int, val color: Int) {
    fun draw(c: Canvas, paint: Paint) {
        paint.color = color or (0xFF shl 24)
        if (n == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(xs[0], ys[0], ws[0] / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        for (i in 1 until n) {
            paint.strokeWidth = (ws[i - 1] + ws[i]) / 2f
            c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], paint)
        }
    }
}

/**
 * Camada de "tinta molhada" com front buffer: o traço em andamento é desenhado
 * direto no buffer que já está na tela, sem esperar o próximo quadro (vsync).
 * É a técnica de apps de anotação com latência mínima. Quando o traço termina,
 * ele passa para a nota (camada normal) e esta camada é limpa.
 *
 * Se o aparelho não suportar, [ready] fica falso e o app usa o caminho normal.
 */
@SuppressLint("ViewConstructor")
class WetInkLayer(context: Context) : SurfaceView(context) {

    private var renderer: CanvasFrontBufferedRenderer<InkSegment>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val callback = object : CanvasFrontBufferedRenderer.Callback<InkSegment> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: InkSegment) {
            param.draw(canvas, paint)
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<InkSegment>) {
            // A camada de baixo fica sempre vazia: quem mostra os traços prontos é a nota.
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
    }

    init {
        // Acima da janela, transparente: a nota aparece por baixo.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val ready: Boolean get() = try { renderer?.isValid() == true } catch (_: Throwable) { false }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = try {
            CanvasFrontBufferedRenderer(this, callback)
        } catch (_: Throwable) {
            null
        }
    }

    override fun onDetachedFromWindow() {
        try { renderer?.release(true) } catch (_: Throwable) { }
        renderer = null
        super.onDetachedFromWindow()
    }

    fun draw(seg: InkSegment) {
        try { renderer?.renderFrontBufferedLayer(seg) } catch (_: Throwable) { }
    }

    fun clear() {
        try { renderer?.clear() } catch (_: Throwable) { }
    }

    // A camada não recebe toques: eles passam para a nota logo abaixo.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?) = false
}

/**
 * Camada leve só com o traço em andamento (caminho sem front buffer). Fica por cima
 * da nota: enquanto se escreve, só ela é redesenhada; a nota não precisa refazer
 * todos os traços a cada ponto.
 */
@SuppressLint("ViewConstructor")
class LiveInkView(context: Context, private val canvasView: InfiniteCanvasView) : View(context) {
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        canvasView.drawLiveInk(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?) = false
}
