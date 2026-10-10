package com.jgantonio.notasinfinitas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jgantonio.notasinfinitas.Ui.choose
import com.jgantonio.notasinfinitas.Ui.confirm
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.pill
import com.jgantonio.notasinfinitas.Ui.promptText
import com.jgantonio.notasinfinitas.Ui.vertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Página inicial: pastas. Dentro de uma pasta aparecem as subpastas e as notas.
 * A mesma tela serve para a raiz (sem EXTRA_FOLDER) e para cada pasta.
 */
class FoldersActivity : Activity() {

    private var folderId: String? = null
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pathView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.init(this)
        folderId = intent.getStringExtra(EXTRA_FOLDER)
        if (folderId != null && Library.folder(folderId) == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Ui.SURFACE)
        }
        val column = vertical()

        val header = horizontal().apply { setPadding(dpi(8f), dpi(10f), dpi(16f), dpi(6f)) }
        if (folderId != null) {
            header.addView(TextView(this).apply {
                text = "←"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Ui.INK)
                minWidth = dpi(44f)
                minHeight = dpi(44f)
                setOnClickListener { finish() }
            })
        } else {
            header.setPadding(dpi(20f), dpi(14f), dpi(16f), dpi(6f))
        }
        val titles = vertical()
        titleView = TextView(this).apply {
            textSize = if (folderId == null) 28f else 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Ui.INK)
            isSingleLine = true
        }
        pathView = label("", 13f)
        titles.addView(titleView)
        titles.addView(pathView)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (folderId != null) {
            header.addView(TextView(this).apply {
                text = "⋮"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Ui.INK)
                minWidth = dpi(44f)
                minHeight = dpi(44f)
                setOnClickListener { Library.folder(folderId)?.let { folderOptions(it) } }
            })
        }
        column.addView(header)

        content = vertical().apply { setPadding(dpi(12f), 0, dpi(12f), dpi(96f)) }
        column.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(column)

        val fab = TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.ACCENT)
            }
            elevation = dp(6f)
            setOnClickListener { onAdd() }
        }
        root.addView(fab, FrameLayout.LayoutParams(dpi(60f), dpi(60f), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dpi(20f), dpi(24f))
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (folderId != null && Library.folder(folderId) == null) {
            finish()
            return
        }
        render()
        // Espera as gravações pendentes (miniaturas) e atualiza de novo.
        Library.io.execute { runOnUiThread { if (!isFinishing) render() } }
    }

    // ---- Montagem da lista ---------------------------------------------------------

    private fun columns(): Int {
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (wDp / 170f).toInt().coerceIn(2, 6)
    }

    private fun render() {
        val fid = folderId
        val folder = Library.folder(fid)
        titleView.text = folder?.name ?: "Pastas"
        val parentPath = folder?.parent?.let { Library.pathOf(it) }
        pathView.text = parentPath ?: if (folder == null) "Suas notas organizadas por pastas" else ""
        pathView.visibility = if (pathView.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        content.removeAllViews()
        val subs = Library.subfolders(fid)
        val notes = if (fid != null) Library.notesIn(fid) else emptyList()

        if (subs.isNotEmpty()) {
            if (fid != null) content.addView(section("Pastas"))
            addGrid(subs.map { folderCard(it) })
        }
        if (notes.isNotEmpty()) {
            content.addView(section("Notas"))
            addGrid(notes.map { noteCard(it) })
        }
        if (subs.isEmpty() && notes.isEmpty()) {
            content.addView(label(
                if (fid == null) "Nenhuma pasta ainda.\nToque em + para criar a primeira."
                else "Pasta vazia.\nToque em + para criar uma nota ou subpasta.",
                15f,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(80f), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun section(text: String) = label(text, 14f, Ui.MUTED, bold = true).apply {
        setPadding(dpi(8f), dpi(16f), 0, dpi(6f))
    }

    private fun addGrid(cards: List<View>) {
        val cols = columns()
        var row: LinearLayout? = null
        cards.forEachIndexed { i, card ->
            if (i % cols == 0) {
                row = horizontal().apply { gravity = Gravity.TOP }
                content.addView(row)
            }
            row!!.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
            })
        }
        // Completa a última linha para os cards manterem a largura.
        val rest = cards.size % cols
        if (rest != 0) repeat(cols - rest) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                setMargins(dpi(6f), 0, dpi(6f), 0)
            })
        }
    }

    private fun folderCard(f: Folder): View {
        val card = vertical(12f).apply {
            background = pill(Color.WHITE, Ui.BORDER, 16f)
            setOnClickListener {
                startActivity(Intent(this@FoldersActivity, FoldersActivity::class.java).putExtra(EXTRA_FOLDER, f.id))
            }
            setOnLongClickListener { folderOptions(f); true }
        }
        card.addView(FolderIcon(f.color), LinearLayout.LayoutParams(dpi(52f), dpi(40f)))
        card.addView(label(f.name, 15f, Ui.INK, bold = true).apply {
            isSingleLine = true
            setPadding(0, dpi(10f), 0, 0)
        })
        val count = Library.itemCount(f.id)
        card.addView(label(if (count == 1) "1 item" else "$count itens", 12f))
        return card
    }

    /** Ícone de pasta desenhado com formas simples (aba + corpo). */
    private fun FolderIcon(color: Int): View {
        val frame = FrameLayout(this)
        frame.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4f)
                setColor(darker(color))
            }
        }, FrameLayout.LayoutParams(dpi(24f), dpi(12f), Gravity.TOP or Gravity.START))
        frame.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6f)
                setColor(color)
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(34f), Gravity.BOTTOM))
        return frame
    }

    private fun darker(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] *= 0.8f
        return Color.HSVToColor(hsv)
    }

    private fun noteCard(n: NoteInfo): View {
        val card = vertical().apply {
            background = pill(Color.WHITE, Ui.BORDER, 16f)
            clipToOutline = true
            setOnClickListener { openNote(n) }
            setOnLongClickListener { noteOptions(n); true }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#FAFBFC"))
            val f = Library.thumbFile(n.id)
            if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.path))
        }
        card.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(120f)))
        val info = vertical().apply { setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(10f)) }
        info.addView(label(n.title, 14f, Ui.INK, bold = true).apply { isSingleLine = true })
        info.addView(label(formatDate(n.modified), 12f))
        card.addView(info)
        return card
    }

    private fun formatDate(t: Long): String =
        if (DateUtils.isToday(t)) "Hoje, " + SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(t))
        else SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(t))

    // ---- Ações -------------------------------------------------------------------

    private fun onAdd() {
        val fid = folderId
        if (fid == null) {
            newFolder(null)
        } else {
            choose("Criar", listOf("📝 Nova nota", "📁 Nova subpasta")) { i ->
                if (i == 0) newNote(fid) else newFolder(fid)
            }
        }
    }

    private fun newNote(folder: String) {
        val title = "Nota " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        openNote(Library.createNote(folder, title))
    }

    private fun openNote(n: NoteInfo) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_NOTE, n.id))
    }

    /** Diálogo de criar/editar pasta: nome + cor. */
    private fun newFolder(parent: String?, editing: Folder? = null) {
        var color = editing?.color ?: Library.folderColors[Library.folders.size % Library.folderColors.size]
        val input = EditText(this).apply {
            setText(editing?.name ?: "")
            hint = "Nome da pasta"
            isSingleLine = true
        }
        val colors = horizontal()
        fun renderColors() {
            colors.removeAllViews()
            for (c in Library.folderColors) {
                colors.addView(Panels.swatch(this, c, c == color) { color = c; renderColors() }.apply {
                    layoutParams = LinearLayout.LayoutParams(dpi(28f), dpi(28f)).apply { setMargins(dpi(3f), dpi(8f), dpi(3f), 0) }
                })
            }
        }
        renderColors()
        val box = vertical().apply {
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(input)
            addView(android.widget.HorizontalScrollView(this@FoldersActivity).apply { addView(colors) })
        }
        AlertDialog.Builder(this)
            .setTitle(if (editing == null) "Nova pasta" else "Editar pasta")
            .setView(box)
            .setPositiveButton(if (editing == null) "Criar" else "Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (editing == null) Library.createFolder(name, color, parent)
                else Library.updateFolder(editing, name = name, color = color)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        input.requestFocus()
    }

    private fun folderOptions(f: Folder) {
        choose(f.name, listOf("✏️ Renomear / cor", "📂 Mover", "🗑 Excluir")) { i ->
            when (i) {
                0 -> newFolder(f.parent, f)
                1 -> pickFolder("Mover \"${f.name}\" para", allowRoot = true, exclude = f.id) { target ->
                    Library.updateFolder(f, parent = target)
                    render()
                }
                2 -> {
                    val count = Library.itemCount(f.id)
                    confirm("Excluir pasta?", "\"${f.name}\" e tudo dentro dela ($count itens) serão apagados para sempre.", "Excluir") {
                        Library.deleteFolder(f.id)
                        if (f.id == folderId) finish() else render()
                    }
                }
            }
        }
    }

    private fun noteOptions(n: NoteInfo) {
        choose(n.title, listOf("✏️ Renomear", "📂 Mover", "⧉ Duplicar", "🗑 Excluir")) { i ->
            when (i) {
                0 -> promptText("Renomear nota", n.title) { t ->
                    Library.updateNote(n, title = t)
                    render()
                }
                1 -> pickFolder("Mover \"${n.title}\" para", allowRoot = false, exclude = null) { target ->
                    if (target != null) Library.updateNote(n, folder = target)
                    render()
                }
                2 -> {
                    Library.duplicateNote(n)
                    render()
                }
                3 -> confirm("Excluir nota?", "\"${n.title}\" será apagada para sempre.", "Excluir") {
                    Library.deleteNote(n.id)
                    render()
                }
            }
        }
    }

    /** Lista de pastas em árvore. [exclude] esconde uma pasta e tudo dentro dela. */
    private fun pickFolder(title: String, allowRoot: Boolean, exclude: String?, onPick: (String?) -> Unit) {
        val tree = Library.tree().filter { (f, _) -> exclude == null || (f.id != exclude && !Library.isInside(f.id, exclude)) }
        val names = ArrayList<String>()
        val ids = ArrayList<String?>()
        if (allowRoot) {
            names.add("🏠 Página inicial (Pastas)")
            ids.add(null)
        }
        for ((f, depth) in tree) {
            names.add("    ".repeat(depth) + "📁 " + f.name)
            ids.add(f.id)
        }
        if (names.isEmpty()) return
        choose(title, names) { i -> onPick(ids[i]) }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}
