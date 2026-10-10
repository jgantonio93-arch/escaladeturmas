package com.jgantonio.notasinfinitas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Formas automáticas": transforma um rabisco em linha reta, elipse/círculo,
 * triângulo, retângulo/quadrilátero ou pentágono quando ele se parece com um.
 */
object ShapeRecognizer {

    fun recognize(s: StrokeElement, minSize: Float): StrokeElement? {
        val n = s.n
        if (n < 5) return null
        val xs = s.xs
        val ys = s.ys
        var minX = xs[0]; var maxX = xs[0]; var minY = ys[0]; var maxY = ys[0]
        var length = 0f
        for (i in 1 until n) {
            length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i])
            minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i])
        }
        if (max(maxX - minX, maxY - minY) < minSize) return null
        val width = s.ws.average().toFloat()
        val chord = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])

        // Linha reta
        if (chord / length > 0.94f) {
            return make(s, floatArrayOf(xs[0], xs[n - 1]), floatArrayOf(ys[0], ys[n - 1]), width)
        }

        // Formas fechadas
        if (chord > 0.22f * length) return null

        // Elipse: todos os pontos perto da elipse inscrita no retângulo envolvente.
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = max(1f, (maxX - minX) / 2f)
        val ry = max(1f, (maxY - minY) / 2f)
        var err = 0f
        for (i in 0 until n) {
            val dx = (xs[i] - cx) / rx
            val dy = (ys[i] - cy) / ry
            err += abs(sqrt(dx * dx + dy * dy) - 1f)
        }
        err /= n

        // Polígono: simplifica o traço e conta os cantos.
        val corners = simplify(xs, ys, 0.045f * length)
        // Remove o último se ele "fecha" no primeiro.
        if (corners.size > 2) {
            val a = corners.first(); val b = corners.last()
            if (hypot(xs[a] - xs[b], ys[a] - ys[b]) < 0.18f * length) corners.removeAt(corners.size - 1)
        }
        val polygonFits = corners.size in 3..5

        if (err < 0.11f && !(polygonFits && corners.size <= 4 && err > 0.07f)) {
            val m = 72
            return make(
                s,
                FloatArray(m + 1) { cx + rx * cos(2 * PI * it / m).toFloat() },
                FloatArray(m + 1) { cy + ry * sin(2 * PI * it / m).toFloat() },
                width,
            )
        }
        if (polygonFits) {
            val k = corners.size
            return make(
                s,
                FloatArray(k + 1) { xs[corners[it % k]] },
                FloatArray(k + 1) { ys[corners[it % k]] },
                width,
            )
        }
        return null
    }

    private fun make(s: StrokeElement, xs: FloatArray, ys: FloatArray, w: Float) =
        StrokeElement(s.type, s.color, s.alpha, xs, ys, FloatArray(xs.size) { w })

    /** Ramer–Douglas–Peucker: índices dos pontos que definem a forma. */
    private fun simplify(xs: FloatArray, ys: FloatArray, eps: Float): MutableList<Int> {
        val keep = BooleanArray(xs.size)
        keep[0] = true
        keep[xs.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to xs.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1
            var bestD = eps
            for (i in a + 1 until b) {
                val d = Geometry.segmentDist(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) {
                keep[best] = true
                stack.addLast(a to best)
                stack.addLast(best to b)
            }
        }
        return keep.indices.filter { keep[it] }.toMutableList()
    }
}
