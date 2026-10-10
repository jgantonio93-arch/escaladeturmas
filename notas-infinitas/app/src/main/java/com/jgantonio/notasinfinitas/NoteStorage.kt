package com.jgantonio.notasinfinitas

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Posição da câmera sobre a tela infinita. */
data class ViewState(val offsetX: Float, val offsetY: Float, val scale: Float)

class Note(val strokes: List<Stroke>, val viewState: ViewState?)

/**
 * Salva a nota num arquivo binário simples dentro da pasta privada do app.
 * Grava num arquivo temporário e depois renomeia, para não corromper a nota
 * se o app for fechado no meio da gravação.
 */
object NoteStorage {
    private const val MAGIC = 0x4E494E46 // "NINF"
    private const val VERSION = 1

    fun save(file: File, strokes: List<Stroke>, state: ViewState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeFloat(state.offsetX)
            out.writeFloat(state.offsetY)
            out.writeFloat(state.scale)
            out.writeInt(strokes.size)
            for (s in strokes) {
                out.writeInt(s.color)
                out.writeFloat(s.baseWidth)
                out.writeInt(s.pointCount)
                val data = s.data
                for (i in 0 until s.floatCount) out.writeFloat(data[i])
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(file: File): Note? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() > VERSION) return null
                val state = ViewState(input.readFloat(), input.readFloat(), input.readFloat())
                val count = input.readInt()
                val strokes = ArrayList<Stroke>(count)
                repeat(count) {
                    val stroke = Stroke(input.readInt(), input.readFloat())
                    repeat(input.readInt()) {
                        stroke.add(input.readFloat(), input.readFloat(), input.readFloat())
                    }
                    strokes.add(stroke)
                }
                Note(strokes, state)
            }
        } catch (e: Exception) {
            null
        }
    }
}
