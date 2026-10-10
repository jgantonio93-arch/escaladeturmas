package com.jgantonio.notasinfinitas

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var canvasView: InfiniteCanvasView
    private lateinit var penButton: TextView
    private lateinit var eraserButton: TextView
    private lateinit var widthButton: TextView
    private lateinit var fingerButton: TextView
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView
    private lateinit var zoomLabel: TextView
    private val colorViews = ArrayList<Pair<View, Int>>()

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val noteFile by lazy { File(filesDir, "nota.ninf") }

    private val dp get() = resources.displayMetrics.density

    private val colors = listOf(
        "#1B1B1F", // preto
        "#1F5FD1", // azul
        "#D7263D", // vermelho
        "#1E9E5A", // verde
        "#F29E0C", // laranja
        "#8E44AD", // roxo
    ).map(Color::parseColor)

    private val widths = listOf(2f to "Fina", 4f to "Média", 8f to "Grossa", 16f to "Marcador")
    private var widthIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        canvasView = InfiniteCanvasView(this)
        canvasView.penWidth = widths[widthIndex].first
        canvasView.color = colors[0]
        canvasView.onStateChanged = ::refreshToolbar

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.parseColor("#F4F5F7"))
        }
        root.addView(buildToolbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val canvasHolder = FrameLayout(this)
        canvasHolder.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        zoomLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#55606E"))
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = pill(Color.parseColor("#E6FFFFFF"), Color.parseColor("#DDE1E7"))
            setOnClickListener { canvasView.recenter() }
        }
        canvasHolder.addView(zoomLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (12 * dp).toInt(), (12 * dp).toInt())
        })
        root.addView(canvasHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        loadNote()
        refreshToolbar()
    }

    override fun onPause() {
        super.onPause()
        saveNote()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- Barra de ferramentas ----------------------------------------------

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }

        penButton = chip("✎ Caneta") { selectTool(InfiniteCanvasView.Tool.PEN) }
        eraserButton = chip("⌫ Borracha") { selectTool(InfiniteCanvasView.Tool.ERASER) }
        bar.addView(penButton)
        bar.addView(eraserButton)
        bar.addView(divider())

        for (c in colors) {
            val swatch = View(this).apply {
                setOnClickListener {
                    canvasView.color = c
                    selectTool(InfiniteCanvasView.Tool.PEN)
                }
            }
            val size = (30 * dp).toInt()
            bar.addView(swatch, LinearLayout.LayoutParams(size, size).apply {
                setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            })
            colorViews.add(swatch to c)
        }
        bar.addView(divider())

        widthButton = chip("") {
            widthIndex = (widthIndex + 1) % widths.size
            canvasView.penWidth = widths[widthIndex].first
            refreshToolbar()
        }
        bar.addView(widthButton)
        bar.addView(divider())

        undoButton = chip("↶ Desfazer") { canvasView.undo() }
        redoButton = chip("↷ Refazer") { canvasView.redo() }
        bar.addView(undoButton)
        bar.addView(redoButton)
        bar.addView(divider())

        fingerButton = chip("") {
            canvasView.fingerDraws = !canvasView.fingerDraws
            refreshToolbar()
        }
        bar.addView(fingerButton)
        bar.addView(chip("⌖ Centralizar") { canvasView.recenter() })
        bar.addView(chip("⇪ Exportar PNG") { exportPng() })
        bar.addView(chip("🗑 Limpar") { confirmClear() })

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#F4F5F7"))
            addView(bar)
        }
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = (36 * dp).toInt()
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#D5DAE1"))
        layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), (24 * dp).toInt()).apply {
            setMargins((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
    }

    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18 * dp
        setColor(fill)
        setStroke((1 * dp).toInt(), stroke)
    }

    private fun styleChip(view: TextView, selected: Boolean, enabled: Boolean = true) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
        view.background = if (selected) {
            pill(Color.parseColor("#DCE7FA"), Color.parseColor("#1F5FD1"))
        } else {
            pill(Color.WHITE, Color.parseColor("#DDE1E7"))
        }
        view.setTextColor(Color.parseColor(if (selected) "#123E8C" else "#1B1B1F"))
    }

    private fun selectTool(tool: InfiniteCanvasView.Tool) {
        canvasView.tool = tool
        refreshToolbar()
    }

    private fun refreshToolbar() {
        if (!::zoomLabel.isInitialized) return
        val pen = canvasView.tool == InfiniteCanvasView.Tool.PEN
        styleChip(penButton, pen)
        styleChip(eraserButton, !pen)

        for ((view, c) in colorViews) {
            val selected = pen && canvasView.color == c
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(((if (selected) 3 else 1) * dp).toInt(), if (selected) Color.parseColor("#1F5FD1") else Color.WHITE)
            }
            view.scaleX = if (selected) 1.12f else 1f
            view.scaleY = view.scaleX
        }

        widthButton.text = "● ${widths[widthIndex].second}"
        styleChip(widthButton, false)

        styleChip(undoButton, false, canvasView.canUndo)
        styleChip(redoButton, false, canvasView.canRedo)

        fingerButton.text = if (canvasView.fingerDraws) "☝ Dedo escreve" else "☝ Dedo move"
        styleChip(fingerButton, canvasView.fingerDraws)

        zoomLabel.text = "${canvasView.zoomPercent}%"
    }

    // ---- Ações -------------------------------------------------------------

    private fun confirmClear() {
        if (canvasView.isEmpty) return
        AlertDialog.Builder(this)
            .setTitle("Limpar a nota?")
            .setMessage("Tudo será apagado. Você ainda pode usar Desfazer logo em seguida.")
            .setPositiveButton("Limpar") { _, _ -> canvasView.clearAll() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportPng() {
        val bitmap = canvasView.renderToBitmap()
        if (bitmap == null) {
            toast("A nota está vazia.")
            return
        }
        io.execute {
            val ok = try {
                savePng(bitmap)
            } catch (e: Exception) {
                false
            } finally {
                bitmap.recycle()
            }
            main.post { toast(if (ok) "Imagem salva em Imagens/NotasInfinitas" else "Não foi possível salvar a imagem.") }
        }
    }

    private fun savePng(bitmap: Bitmap): Boolean {
        val name = "nota_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NotasInfinitas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val written = contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (!written) {
            contentResolver.delete(uri, null, null)
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return true
    }

    private fun loadNote() {
        NoteStorage.load(noteFile)?.let { canvasView.load(it) }
    }

    private fun saveNote() {
        val (strokes, state) = canvasView.snapshot()
        canvasView.markSaved()
        io.execute {
            try {
                NoteStorage.save(noteFile, strokes, state)
            } catch (e: Exception) {
                main.post { toast("Erro ao salvar a nota.") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
