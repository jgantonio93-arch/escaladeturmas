package com.jgantonio.notasinfinitas

import android.graphics.RectF

/**
 * Um traço da caneta. Os pontos ficam em coordenadas do "mundo" (a tela infinita),
 * não da tela do aparelho, então o traço não muda quando o usuário rola ou dá zoom.
 *
 * Cada ponto ocupa 3 floats: x, y e pressão (0..1).
 */
class Stroke(val color: Int, val baseWidth: Float) {

    var data = FloatArray(3 * 64)
        private set
    var floatCount = 0
        private set

    val pointCount: Int get() = floatCount / 3

    /** Retângulo que envolve o traço (já incluindo a espessura). */
    val bounds = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    fun x(i: Int) = data[i * 3]
    fun y(i: Int) = data[i * 3 + 1]
    fun pressure(i: Int) = data[i * 3 + 2]

    fun widthAt(i: Int) = baseWidth * (0.35f + 0.65f * pressure(i))

    fun add(x: Float, y: Float, pressure: Float) {
        if (floatCount + 3 > data.size) data = data.copyOf(data.size * 2)
        data[floatCount++] = x
        data[floatCount++] = y
        data[floatCount++] = pressure.coerceIn(0f, 1f)
        val r = baseWidth
        if (x - r < bounds.left) bounds.left = x - r
        if (y - r < bounds.top) bounds.top = y - r
        if (x + r > bounds.right) bounds.right = x + r
        if (y + r > bounds.bottom) bounds.bottom = y + r
    }

    /** O traço passa a menos de [radius] do ponto (px, py)? */
    fun hits(px: Float, py: Float, radius: Float): Boolean {
        val reach = radius + baseWidth / 2f
        if (px < bounds.left - reach || px > bounds.right + reach ||
            py < bounds.top - reach || py > bounds.bottom + reach
        ) return false
        val n = pointCount
        if (n == 1) return dist2(px, py, x(0), y(0)) <= reach * reach
        for (i in 1 until n) {
            if (segmentDist2(px, py, x(i - 1), y(i - 1), x(i), y(i)) <= reach * reach) return true
        }
        return false
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun segmentDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return dist2(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return dist2(px, py, ax + t * dx, ay + t * dy)
    }
}
