package com.jgantonio.notasinfinitas

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jgantonio.notasinfinitas.Ui.SheetItem
import com.jgantonio.notasinfinitas.Ui.actionSheet
import com.jgantonio.notasinfinitas.Ui.buttonRow
import com.jgantonio.notasinfinitas.Ui.card
import com.jgantonio.notasinfinitas.Ui.circle
import com.jgantonio.notasinfinitas.Ui.confirm
import com.jgantonio.notasinfinitas.Ui.dp
import com.jgantonio.notasinfinitas.Ui.dpi
import com.jgantonio.notasinfinitas.Ui.horizontal
import com.jgantonio.notasinfinitas.Ui.icon
import com.jgantonio.notasinfinitas.Ui.iconButton
import com.jgantonio.notasinfinitas.Ui.label
import com.jgantonio.notasinfinitas.Ui.pill
import com.jgantonio.notasinfinitas.Ui.primaryButton
import com.jgantonio.notasinfinitas.Ui.promptText
import com.jgantonio.notasinfinitas.Ui.ripple
import com.jgantonio.notasinfinitas.Ui.rounded
import com.jgantonio.notasinfinitas.Ui.secondaryButton
import com.jgantonio.notasinfinitas.Ui.sectionLabel
import com.jgantonio.notasinfinitas.Ui.sheet
import com.jgantonio.notasinfinitas.Ui.textField
import com.jgantonio.notasinfinitas.Ui.title
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
    private val ptBr = Locale("pt", "BR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Library.init(this)
        folderId = intent.getStringExtra(EXTRA_FOLDER)
        if (folderId != null && Library.folder(folderId) == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            background = Ui.run { paperBackground() }
        }
        content = vertical().apply {
            setPadding(dpi(16f), 0, dpi(16f), dpi(120f))
            // Segura o foco inicial: o teclado não abre sozinho por causa da busca.
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        })

        // Botões flutuantes
        val fabs = horizontal()
        if (folderId != null) {
            fabs.addView(ImageView(this).apply {
                setImageDrawable(icon(Icon.PDF, Ui.INK))
                scaleType = ImageView.ScaleType.CENTER
                background = ripple(circle(Ui.SURFACE, 0, 0f), circle(Color.WHITE, 0, 0f))
                Ui.run { softShadow(6f); pressable(0.94f) }
                contentDescription = "Importar PDF"
                setOnClickListener { pickPdf() }
            }, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { marginEnd = dpi(12f) })
            fabs.addView(ImageView(this).apply {
                setImageDrawable(icon(Icon.FOLDER_PLUS, Ui.INK))
                scaleType = ImageView.ScaleType.CENTER
                background = ripple(circle(Ui.SURFACE, 0, 0f), circle(Color.WHITE, 0, 0f))
                Ui.run { softShadow(6f); pressable(0.94f) }
                setOnClickListener { editFolder(null, folderId) }
            }, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { marginEnd = dpi(12f) })
        }
        fabs.addView(TextView(this).apply {
            text = if (folderId == null) "Nova pasta" else "Nova nota"
            textSize = 15f
            typeface = Ui.MEDIUM
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpi(18f), 0, dpi(22f), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(Icon.PLUS, Color.WHITE, 22f, 2.2f), null, null, null)
            compoundDrawablePadding = dpi(8f)
            background = ripple(pill(Ui.ACCENT, 0), pill(Color.WHITE, 0), Color.parseColor("#33FFFFFF"))
            Ui.run { softShadow(8f); pressable(0.96f) }
            setOnClickListener { folderId?.let { newNote(it) } ?: editFolder(null, null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(56f)))
        root.addView(fabs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
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
        animateEntry = false
        // Espera as gravações pendentes (miniaturas) e atualiza de novo.
        Library.io.execute { runOnUiThread { if (!isFinishing) render() } }
    }

    // ---- Montagem -----------------------------------------------------------------

    private fun columns(): Int {
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return ((wDp - 32f) / 170f).toInt().coerceIn(2, 6)
    }

    fun render() {
        val fid = folderId
        if (fid == null) {
            renderHome()
            return
        }
        content.removeAllViews()
        renderFolder(Library.folder(fid) ?: return)
    }

    // Cabeçalho da página inicial (fixo, para a busca não perder o foco) e corpo que muda.
    private var homeHead: LinearLayout? = null
    private var homeBody: LinearLayout? = null
    private var homeCount: TextView? = null
    private var query = ""

    private fun normalize(t: String) =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun countText(folders: Int, notes: Int): String {
        val parts = ArrayList<String>()
        if (folders > 0) parts.add(if (folders == 1) "1 pasta" else "$folders pastas")
        if (notes > 0) parts.add(if (notes == 1) "1 nota" else "$notes notas")
        return if (parts.isEmpty()) "Vazia" else parts.joinToString(" · ")
    }

    private fun renderHome() {
        if (homeHead == null) {
            val head = vertical().apply { setPadding(dpi(6f), dpi(44f), dpi(6f), dpi(8f)) }
            head.addView(label("TonyNotes", 14f, Ui.ACCENT, bold = true).apply {
                typeface = Ui.BOLD
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelativeWithIntrinsicBounds(LogoDrawable(dpi(22f)), null, null, null)
                compoundDrawablePadding = dpi(8f)
            })
            head.addView(title("Suas pastas", 40f).apply { setPadding(0, dpi(4f), 0, dpi(2f)) })
            homeCount = label("", 14f)
            head.addView(homeCount)
            val search = Ui.run { textField("", "Buscar notas e pastas") }.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon(Icon.SEARCH, Ui.MUTED, 20f), null, null, null)
                compoundDrawablePadding = dpi(10f)
                background = Ui.run { rounded(Color.WHITE, 16f, Ui.LINE) }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(e: android.text.Editable?) {
                        query = e?.toString()?.trim() ?: ""
                        renderHome()
                    }
                })
            }
            head.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(16f)
            })
            homeHead = head
            homeBody = vertical()
            content.addView(head)
            content.addView(homeBody)
        }
        homeCount?.text = countText(Library.folders.size, Library.notes.size)
        val body = homeBody!!
        body.removeAllViews()
        val target = content
        content = body
        try {
            if (query.isNotEmpty()) renderSearch() else renderHomeBody()
        } finally {
            content = target
        }
    }

    private fun renderSearch() {
        val q = normalize(query)
        val folders = Library.folders.filter { normalize(it.name).contains(q) }.sortedBy { it.name.lowercase() }
        val notes = Library.notes.filter { normalize(it.title).contains(q) }.sortedByDescending { it.modified }
        if (folders.isEmpty() && notes.isEmpty()) {
            content.addView(label("Nada encontrado para “$query”.", 15f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(48f), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        if (folders.isNotEmpty()) {
            content.addView(sectionLabel("Pastas").apply { setPadding(dpi(6f), dpi(14f), 0, dpi(4f)) })
            addGrid(folders.map { folderCard(it) })
        }
        if (notes.isNotEmpty()) {
            content.addView(sectionLabel("Notas").apply { setPadding(dpi(6f), dpi(14f), 0, dpi(4f)) })
            addGrid(notes.map { noteCard(it, showFolder = true) })
        }
    }

    private fun renderHomeBody() {
        val recent = Library.notes.sortedByDescending { it.modified }.take(8)
        if (recent.isNotEmpty()) {
            content.addView(sectionLabel("Recentes").apply { setPadding(dpi(6f), dpi(18f), 0, dpi(8f)) })
            val row = horizontal().apply { setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(8f)) }
            for (n in recent) {
                row.addView(noteCard(n, showFolder = true), LinearLayout.LayoutParams(dpi(156f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpi(12f)
                })
            }
            content.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(row)
            })
        }

        val folders = Library.subfolders(null)
        if (folders.isEmpty()) {
            content.addView(emptyState("Nenhuma pasta ainda", "Crie uma pasta para começar a organizar suas notas."))
        } else {
            content.addView(sectionLabel("Pastas").apply { setPadding(dpi(6f), dpi(14f), 0, dpi(4f)) })
            addGrid(folders.map { folderCard(it) })
        }
    }

    private fun renderFolder(folder: Folder) {
        val bar = horizontal().apply { setPadding(0, dpi(8f), 0, 0) }
        bar.addView(iconButton(Icon.BACK) { finish() })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton(Icon.MORE) { folderOptions(folder) })
        content.addView(bar)

        val head = horizontal().apply { setPadding(dpi(6f), dpi(10f), dpi(6f), dpi(4f)) }
        head.addView(ImageView(this).apply { setImageDrawable(FolderGlyphDrawable(folder.color)) },
            LinearLayout.LayoutParams(dpi(54f), dpi(44f)).apply { marginEnd = dpi(14f) })
        val titles = vertical()
        titles.addView(title(folder.name, 28f).apply { isSingleLine = true })
        val subs = Library.subfolders(folder.id)
        val notes = Library.notesIn(folder.id)
        titles.addView(label((folder.parent?.let { Library.pathOf(it) + "  ·  " } ?: "") + countText(subs.size, notes.size), 13f).apply { isSingleLine = true })
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(head)

        if (subs.isNotEmpty()) {
            content.addView(sectionLabel("Pastas").apply { setPadding(dpi(6f), dpi(18f), 0, dpi(4f)) })
            addGrid(subs.map { folderCard(it) })
        }
        if (notes.isNotEmpty()) {
            content.addView(sectionLabel("Notas").apply { setPadding(dpi(6f), dpi(18f), 0, dpi(4f)) })
            addGrid(notes.map { noteCard(it, showFolder = false) })
        }
        if (subs.isEmpty() && notes.isEmpty()) {
            content.addView(emptyState("Pasta vazia", "Toque em “Nova nota” para começar a escrever."))
        }
    }

    private fun emptyState(title: String, text: String): View {
        val box = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24f), dpi(70f), dpi(24f), 0)
        }
        box.addView(ImageView(this).apply {
            setImageDrawable(FolderGlyphDrawable(Color.parseColor("#C5D3F5")))
        }, LinearLayout.LayoutParams(dpi(96f), dpi(78f)))
        box.addView(label(title, 18f, Ui.INK, bold = true).apply { setPadding(0, dpi(18f), 0, dpi(4f)) })
        box.addView(label(text, 14f).apply { gravity = Gravity.CENTER })
        return box
    }

    /** Entrada escalonada (sobe e aparece), só na primeira montagem e sem "remover animações". */
    private var animateEntry = true

    private fun enter(v: View, index: Int) {
        if (!animateEntry || !Ui.animationsOn()) return
        v.alpha = 0f
        v.translationY = dp(14f)
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(minOf(index, 8) * 40L)
            .setDuration(420)
            .setInterpolator(Ui.EASE)
            .start()
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
                setMargins(dpi(5f), dpi(5f), dpi(5f), dpi(5f))
            })
            enter(card, i)
        }
        val rest = cards.size % cols
        if (rest != 0) repeat(cols - rest) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dpi(5f), 0, dpi(5f), 0) })
        }
    }

    private fun tint(color: Int, amount: Float): Int {
        val r = (Color.red(color) * amount + 255 * (1 - amount)).toInt()
        val g = (Color.green(color) * amount + 255 * (1 - amount)).toInt()
        val b = (Color.blue(color) * amount + 255 * (1 - amount)).toInt()
        return Color.rgb(r, g, b)
    }

    private fun folderCard(f: Folder): View {
        val card = vertical().apply {
            setPadding(dpi(16f), dpi(14f), dpi(6f), dpi(18f))
            background = ripple(rounded(tint(f.color, 0.13f), 24f), rounded(Color.WHITE, 24f))
            Ui.run { pressable(0.97f) }
            setOnClickListener {
                startActivity(Intent(this@FoldersActivity, FoldersActivity::class.java).putExtra(EXTRA_FOLDER, f.id))
            }
            setOnLongClickListener { folderOptions(f); true }
        }
        val top = horizontal().apply { gravity = Gravity.TOP }
        top.addView(ImageView(this).apply { setImageDrawable(FolderGlyphDrawable(f.color)) },
            LinearLayout.LayoutParams(dpi(50f), dpi(41f)).apply { topMargin = dpi(4f) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(iconButton(Icon.MORE, Ui.MUTED, 36f) { folderOptions(f) })
        card.addView(top)
        card.addView(label(f.name, 17f, Ui.INK, bold = true).apply {
            typeface = Ui.BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dpi(14f), dpi(8f), dpi(2f))
        })
        card.addView(label(countText(Library.folders.count { it.parent == f.id }, Library.notes.count { it.folder == f.id }), 13f))
        return card
    }

    private fun noteCard(n: NoteInfo, showFolder: Boolean): View {
        val card = vertical().apply {
            setOnClickListener { openNote(n) }
            setOnLongClickListener { noteOptions(n); true }
        }
        card(22f, 2f, card)
        // "Double bezel": a miniatura é uma folha de papel encaixada dentro do cartão,
        // com cantos concêntricos (22dp fora, 15dp dentro).
        card.setPadding(dpi(7f), dpi(7f), dpi(7f), 0)
        val thumbBox = FrameLayout(this).apply {
            background = rounded(Color.parseColor("#FBFAF7"), 15f, Color.parseColor("#EFEBE3"))
            clipToOutline = true
        }
        val f = Library.thumbFile(n.id)
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        if (bmp != null) {
            thumbBox.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
                contentDescription = "Miniatura de ${n.title}"
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            thumbBox.addView(ImageView(this).apply {
                setImageDrawable(icon(Icon.NOTE, Color.parseColor("#C9C3B8"), 34f, 1.6f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dpi(40f), dpi(40f), Gravity.CENTER))
        }
        card.addView(thumbBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(124f)))
        val info = horizontal().apply { setPadding(dpi(7f), dpi(9f), 0, dpi(9f)) }
        val texts = vertical()
        texts.addView(label(n.title, 15f, Ui.INK, bold = true).apply {
            typeface = Ui.BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val sub = if (showFolder) Library.folder(n.folder)?.name ?: "" else formatDate(n.modified)
        texts.addView(label(sub, 12.5f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        info.addView(iconButton(Icon.MORE, Ui.MUTED, 34f) { noteOptions(n) })
        card.addView(info)
        return card
    }

    private fun formatDate(t: Long): String =
        if (DateUtils.isToday(t)) "Hoje, " + SimpleDateFormat("HH:mm", ptBr).format(Date(t))
        else SimpleDateFormat("d 'de' MMM yyyy", ptBr).format(Date(t))

    // ---- Ações -------------------------------------------------------------------

    private fun newNote(folder: String) {
        val title = "Nota " + SimpleDateFormat("dd/MM HH:mm", ptBr).format(Date())
        openNote(Library.createNote(folder, title))
    }

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PDF)
    }

    @Deprecated("Activity sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val fid = folderId ?: return
        if (requestCode != REQ_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // Nota nova com o nome do arquivo; o editor importa as páginas ao abrir.
        val title = (PdfImporter.displayName(this, uri) ?: "PDF").removeSuffix(".pdf").removeSuffix(".PDF")
        val n = Library.createNote(fid, title)
        startActivity(Intent(this, EditorActivity::class.java)
            .putExtra(EditorActivity.EXTRA_NOTE, n.id)
            .putExtra(EditorActivity.EXTRA_PDF, uri.toString())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun openNote(n: NoteInfo) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_NOTE, n.id))
    }

    /** Criar (editing == null) ou editar pasta: prévia, nome e cor. */
    fun editFolder(editing: Folder?, parent: String?) {
        var color = editing?.color ?: Library.folderColors[Library.folders.size % Library.folderColors.size]
        sheet(if (editing == null) "Nova pasta" else "Editar pasta") { box, dialog ->
            val glyph = ImageView(this).apply { setImageDrawable(FolderGlyphDrawable(color)) }
            box.addView(glyph, LinearLayout.LayoutParams(dpi(84f), dpi(68f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpi(16f)
            })
            val field = textField(editing?.name ?: "", "Nome da pasta")
            box.addView(field)
            box.addView(sectionLabel("Cor"))
            val dots = ArrayList<ColorDot>()
            val row = horizontal()
            for (c in Library.folderColors) {
                val d = ColorDot(this, c, c == color) {
                    color = c
                    dots.forEach { it.checked = it.color == c }
                    glyph.setImageDrawable(FolderGlyphDrawable(c))
                }
                dots.add(d)
                row.addView(d, LinearLayout.LayoutParams(0, dpi(34f), 1f).apply { setMargins(dpi(2f), 0, dpi(2f), 0) })
            }
            box.addView(row)
            box.addView(buttonRow(
                secondaryButton("Cancelar") { dialog.dismiss() },
                primaryButton(if (editing == null) "Criar" else "Salvar") {
                    dialog.dismiss()
                    val name = field.text.toString().trim()
                    if (editing == null) Library.createFolder(name, color, parent)
                    else Library.updateFolder(editing, name = name, color = color)
                    render()
                },
            ))
            if (editing == null) field.requestFocus()
        }
    }

    private fun folderOptions(f: Folder) {
        val count = Library.itemCount(f.id)
        actionSheet(null, listOf(
            SheetItem(Icon.EDIT, "Renomear e mudar cor") { editFolder(f, f.parent) },
            SheetItem(Icon.MOVE, "Mover") {
                Pickers.folder(this, "Mover para", allowRoot = true, exclude = f.id, current = f.parent) { target ->
                    Library.updateFolder(f, parent = target)
                    render()
                }
            },
            SheetItem(Icon.TRASH, "Excluir pasta", danger = true) {
                confirm("Excluir pasta?", "“${f.name}” e tudo dentro dela ($count ${if (count == 1) "item" else "itens"}) serão apagados para sempre.", "Excluir") {
                    Library.deleteFolder(f.id)
                    if (f.id == folderId) finish() else render()
                }
            },
        ), header = Panels.headerRow(this, FolderGlyphDrawable(f.color), f.name, countText(
            Library.folders.count { it.parent == f.id }, Library.notes.count { it.folder == f.id },
        )))
    }

    private fun noteOptions(n: NoteInfo) {
        actionSheet(null, listOf(
            SheetItem(Icon.EDIT, "Renomear") {
                promptText("Renomear nota", n.title) { t ->
                    Library.updateNote(n, title = t)
                    render()
                }
            },
            SheetItem(Icon.MOVE, "Mover para outra pasta") {
                Pickers.folder(this, "Mover para", allowRoot = false, exclude = null, current = n.folder) { target ->
                    if (target != null) Library.updateNote(n, folder = target)
                    render()
                }
            },
            SheetItem(Icon.COPY, "Duplicar") {
                Library.duplicateNote(n)
                render()
            },
            SheetItem(Icon.TRASH, "Excluir nota", danger = true) {
                confirm("Excluir nota?", "“${n.title}” será apagada para sempre.", "Excluir") {
                    Library.deleteNote(n.id)
                    render()
                }
            },
        ), header = Panels.headerRow(this, icon(Icon.NOTE, Ui.MUTED, 28f), n.title, formatDate(n.modified)))
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        private const val REQ_PDF = 51
    }
}
