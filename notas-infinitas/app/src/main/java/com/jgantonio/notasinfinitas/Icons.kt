package com.jgantonio.notasinfinitas

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Ícones de linha desenhados em código (grade de 24x24), no estilo One UI. */
enum class Icon {
    BACK, UNDO, REDO, MORE, ERASER, LASSO, TEXT, IMAGE, PLUS, FOLDER, FOLDER_PLUS, NOTE,
    TRASH, COPY, PALETTE, CHECK, CLOSE, EDIT, MOVE, STAR, TOUCH, EXPORT, PDF, PAGE, CENTER, SHAPES, CHEVRON_RIGHT
}

class IconDrawable(
    private val icon: Icon,
    color: Int,
    private val sizePx: Int,
    strokePx: Float,
) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = strokePx
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    var color: Int
        get() = stroke.color
        set(v) {
            stroke.color = v
            fill.color = v
            invalidateSelf()
        }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        val sw = stroke.strokeWidth
        stroke.strokeWidth = sw / s
        drawIcon(canvas)
        stroke.strokeWidth = sw
        canvas.restore()
    }

    private fun path(block: Path.() -> Unit) = Path().apply(block)

    private fun Path.rrect(l: Float, t: Float, r: Float, b: Float, rad: Float) =
        addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)

    private fun drawIcon(c: Canvas) {
        when (icon) {
            Icon.BACK -> c.drawPath(path { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }, stroke)
            Icon.CHEVRON_RIGHT -> c.drawPath(path { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }, stroke)
            Icon.UNDO, Icon.REDO -> {
                c.save()
                if (icon == Icon.REDO) c.scale(-1f, 1f, 12f, 12f)
                c.drawPath(path {
                    moveTo(8.5f, 4.5f); lineTo(4f, 9f); lineTo(8.5f, 13.5f)
                    moveTo(4f, 9f); lineTo(14.5f, 9f)
                    arcTo(RectF(9.5f, 9f, 20f, 19.5f), -90f, 180f)
                    lineTo(9f, 19.5f)
                }, stroke)
                c.restore()
            }
            Icon.MORE -> for (y in listOf(5f, 12f, 19f)) c.drawCircle(12f, y, 1.7f, fill)
            Icon.ERASER -> c.drawPath(path {
                moveTo(8f, 20f); lineTo(20f, 20f)
                moveTo(4.6f, 14.4f); lineTo(13.6f, 5.4f)
                quadTo(14.3f, 4.7f, 15f, 5.4f); lineTo(19.6f, 10f)
                quadTo(20.3f, 10.7f, 19.6f, 11.4f); lineTo(11f, 20f)
                lineTo(8.6f, 20f); lineTo(4.6f, 16f)
                quadTo(3.8f, 15.2f, 4.6f, 14.4f)
                moveTo(9f, 10f); lineTo(15f, 16f)
            }, stroke)
            Icon.LASSO -> {
                val dashed = Paint(stroke).apply { pathEffect = DashPathEffect(floatArrayOf(2.6f, 2.4f), 0f) }
                c.drawOval(RectF(3.5f, 3.5f, 20.5f, 15.5f), dashed)
                c.drawPath(path { moveTo(8.5f, 15f); quadTo(6f, 18f, 8f, 20.5f); quadTo(9.5f, 21.5f, 10.5f, 19.5f) }, stroke)
            }
            Icon.TEXT -> c.drawPath(path {
                moveTo(5f, 7f); lineTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 7f)
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(9f, 19f); lineTo(15f, 19f)
            }, stroke)
            Icon.IMAGE -> {
                c.drawPath(path { rrect(3.5f, 5f, 20.5f, 19f, 2.5f) }, stroke)
                c.drawCircle(9f, 10f, 1.6f, stroke)
                c.drawPath(path { moveTo(4f, 17.5f); lineTo(9.5f, 12.5f); lineTo(13.5f, 16f); lineTo(16f, 13.5f); lineTo(20f, 17f) }, stroke)
            }
            Icon.PLUS -> c.drawPath(path { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }, stroke)
            Icon.FOLDER, Icon.FOLDER_PLUS, Icon.MOVE -> {
                c.drawPath(path {
                    moveTo(3.5f, 8f); quadTo(3.5f, 5.5f, 6f, 5.5f); lineTo(9.5f, 5.5f); lineTo(11.5f, 7.5f)
                    lineTo(18f, 7.5f); quadTo(20.5f, 7.5f, 20.5f, 10f); lineTo(20.5f, 16.5f)
                    quadTo(20.5f, 19f, 18f, 19f); lineTo(6f, 19f); quadTo(3.5f, 19f, 3.5f, 16.5f); close()
                }, stroke)
                if (icon == Icon.FOLDER_PLUS) c.drawPath(path { moveTo(12f, 10.5f); lineTo(12f, 16f); moveTo(9.25f, 13.25f); lineTo(14.75f, 13.25f) }, stroke)
                if (icon == Icon.MOVE) c.drawPath(path { moveTo(8.5f, 13.25f); lineTo(15f, 13.25f); moveTo(12.5f, 10.75f); lineTo(15f, 13.25f); lineTo(12.5f, 15.75f) }, stroke)
            }
            Icon.NOTE -> {
                c.drawPath(path { rrect(5f, 3.5f, 19f, 20.5f, 2.5f) }, stroke)
                c.drawPath(path { moveTo(8.5f, 8.5f); lineTo(15.5f, 8.5f); moveTo(8.5f, 12f); lineTo(15.5f, 12f); moveTo(8.5f, 15.5f); lineTo(12.5f, 15.5f) }, stroke)
            }
            Icon.TRASH -> c.drawPath(path {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(9f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(15f, 7f)
                moveTo(6f, 7f); lineTo(7f, 19f); quadTo(7.2f, 20.5f, 8.7f, 20.5f); lineTo(15.3f, 20.5f); quadTo(16.8f, 20.5f, 17f, 19f); lineTo(18f, 7f)
                moveTo(10f, 11f); lineTo(10f, 16.5f); moveTo(14f, 11f); lineTo(14f, 16.5f)
            }, stroke)
            Icon.COPY -> {
                c.drawPath(path { rrect(8.5f, 8.5f, 20f, 20f, 2.5f) }, stroke)
                c.drawPath(path { moveTo(15.5f, 8.5f); lineTo(15.5f, 6f); quadTo(15.5f, 4f, 13.5f, 4f); lineTo(6f, 4f); quadTo(4f, 4f, 4f, 6f); lineTo(4f, 13.5f); quadTo(4f, 15.5f, 6f, 15.5f); lineTo(8.5f, 15.5f) }, stroke)
            }
            Icon.PALETTE -> {
                c.drawPath(path {
                    moveTo(12f, 3.5f)
                    cubicTo(6.8f, 3.5f, 3.5f, 7.3f, 3.5f, 12f)
                    cubicTo(3.5f, 16.7f, 7.3f, 20.5f, 12f, 20.5f)
                    cubicTo(13.4f, 20.5f, 13.8f, 19.3f, 13.1f, 18.2f)
                    cubicTo(12.3f, 17f, 13f, 15.5f, 14.5f, 15.5f)
                    lineTo(16.5f, 15.5f)
                    cubicTo(18.8f, 15.5f, 20.5f, 13.8f, 20.5f, 11.5f)
                    cubicTo(20.5f, 7f, 16.7f, 3.5f, 12f, 3.5f); close()
                }, stroke)
                c.drawCircle(8f, 11f, 1.3f, fill)
                c.drawCircle(10.5f, 7.5f, 1.3f, fill)
                c.drawCircle(14.8f, 8f, 1.3f, fill)
            }
            Icon.CHECK -> c.drawPath(path { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }, stroke)
            Icon.CLOSE -> c.drawPath(path { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }, stroke)
            Icon.EDIT -> c.drawPath(path {
                moveTo(4f, 20f); lineTo(4.8f, 16f); lineTo(15.5f, 5.3f)
                quadTo(16.5f, 4.3f, 17.5f, 5.3f); lineTo(18.7f, 6.5f)
                quadTo(19.7f, 7.5f, 18.7f, 8.5f); lineTo(8f, 19.2f); close()
                moveTo(13.5f, 7.3f); lineTo(16.7f, 10.5f)
            }, stroke)
            Icon.STAR -> {
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 8.5f else 3.8f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = 12f + r * cos(a).toFloat()
                    val y = 12.6f + r * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, stroke)
            }
            Icon.TOUCH -> {
                c.drawPath(path {
                    moveTo(9.5f, 20.5f); lineTo(6.5f, 15.5f)
                    quadTo(5.7f, 14f, 7f, 13.5f); quadTo(8f, 13.2f, 8.8f, 14.3f); lineTo(10f, 16f)
                    lineTo(10f, 6.5f); quadTo(10f, 5f, 11.5f, 5f); quadTo(13f, 5f, 13f, 6.5f); lineTo(13f, 11.5f)
                    lineTo(17f, 12.3f); quadTo(18.8f, 12.7f, 18.5f, 14.5f); lineTo(17.5f, 20.5f)
                }, stroke)
            }
            Icon.EXPORT -> c.drawPath(path {
                moveTo(12f, 15f); lineTo(12f, 3.5f); moveTo(7.5f, 8f); lineTo(12f, 3.5f); lineTo(16.5f, 8f)
                moveTo(4.5f, 13f); lineTo(4.5f, 18f); quadTo(4.5f, 20.5f, 7f, 20.5f); lineTo(17f, 20.5f); quadTo(19.5f, 20.5f, 19.5f, 18f); lineTo(19.5f, 13f)
            }, stroke)
            Icon.PDF -> {
                c.drawPath(path {
                    moveTo(14f, 3.5f); lineTo(7f, 3.5f); quadTo(5f, 3.5f, 5f, 5.5f); lineTo(5f, 18.5f); quadTo(5f, 20.5f, 7f, 20.5f)
                    lineTo(17f, 20.5f); quadTo(19f, 20.5f, 19f, 18.5f); lineTo(19f, 8.5f); close()
                    moveTo(14f, 3.5f); lineTo(14f, 8.5f); lineTo(19f, 8.5f)
                    moveTo(8.5f, 13f); lineTo(15.5f, 13f); moveTo(8.5f, 16.5f); lineTo(13f, 16.5f)
                }, stroke)
            }
            Icon.PAGE -> {
                c.drawPath(path { rrect(4f, 4f, 20f, 20f, 2.5f) }, stroke)
                c.drawPath(path { moveTo(4f, 9.5f); lineTo(20f, 9.5f); moveTo(4f, 14.5f); lineTo(20f, 14.5f); moveTo(9.5f, 4f); lineTo(9.5f, 20f); moveTo(14.5f, 4f); lineTo(14.5f, 20f) }, stroke)
            }
            Icon.CENTER -> {
                c.drawCircle(12f, 12f, 6.5f, stroke)
                c.drawCircle(12f, 12f, 1.6f, fill)
                c.drawPath(path { moveTo(12f, 2.5f); lineTo(12f, 5.5f); moveTo(12f, 18.5f); lineTo(12f, 21.5f); moveTo(2.5f, 12f); lineTo(5.5f, 12f); moveTo(18.5f, 12f); lineTo(21.5f, 12f) }, stroke)
            }
            Icon.SHAPES -> {
                c.drawCircle(8.5f, 8.5f, 4.5f, stroke)
                c.drawPath(path { rrect(11.5f, 11.5f, 20f, 20f, 1.5f) }, stroke)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
