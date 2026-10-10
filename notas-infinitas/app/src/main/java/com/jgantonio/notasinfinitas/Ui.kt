package com.jgantonio.notasinfinitas

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Sistema visual do app (cores, tipografia e componentes), montado em código.
 *
 * Direção (Taste Skill + Awesome Design/Notion + Web Interface Guidelines):
 * papel quente em vez de cinza frio, um único acento cor de tinta (saturação ~50%),
 * títulos em serifa com personalidade (Fraunces) e interface em grotesca (Onest),
 * raios variados, sombras tingidas e todos os pares de texto/fundo com contraste AA.
 */
object Ui {
    val BG = Color.parseColor("#F6F4EF")          // papel
    val SURFACE = Color.WHITE
    val INK = Color.parseColor("#1F1D1A")
    val MUTED = Color.parseColor("#6A665E")       // 5,2:1 sobre o papel
    val LINE = Color.parseColor("#E6E2DA")
    val ACCENT = Color.parseColor("#3A55B8")      // tinta azul, 6,6:1 sobre branco
    val ACCENT_SOFT = Color.parseColor("#E9EDF8")
    val DANGER = Color.parseColor("#C4302B")
    val FIELD = Color.parseColor("#EFECE6")
    /** Cor das sombras: um marrom quente bem escuro (sombra "do papel", não cinza). */
    val SHADOW = Color.parseColor("#3C301E")

    // Tipografia: carregada em init(); até lá, fontes do sistema.
    var REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL); private set
    var MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); private set
    var BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD); private set
    var DISPLAY: Typeface = Typeface.create("serif", Typeface.BOLD); private set
    private var fontsLoaded = false

    private val fontCache = HashMap<String, Typeface?>()

    private fun variable(ctx: Context, file: String, settings: String): Typeface? =
        fontCache.getOrPut("$file|$settings") {
            try {
                Typeface.Builder(ctx.assets, "fonts/$file").setFontVariationSettings(settings).build()
            } catch (_: Throwable) {
                null
            }
        }

    /** Carrega Onest (interface) e Fraunces (títulos). Chamar no onCreate das telas. */
    fun init(context: Context) {
        if (fontsLoaded) return
        val c = context.applicationContext
        appContext = c
        variable(c, "Onest.ttf", "'wght' 400")?.let { REGULAR = it }
        variable(c, "Onest.ttf", "'wght' 520")?.let { MEDIUM = it }
        variable(c, "Onest.ttf", "'wght' 620")?.let { BOLD = it }
        variable(c, "Fraunces.ttf", "'wght' 600, 'opsz' 72, 'SOFT' 50, 'WONK' 0")?.let { DISPLAY = it }
        fontsLoaded = true
    }

    fun fraunces(context: Context, weight: Int, italic: Boolean): Typeface =
        variable(context.applicationContext, "Fraunces.ttf", "'wght' $weight, 'opsz' 36, 'SOFT' 50")
            ?.let { if (italic) Typeface.create(it, Typeface.ITALIC) else it } ?: Typeface.create("serif", if (italic) Typeface.ITALIC else Typeface.NORMAL)

    fun onest(context: Context, weight: Int, italic: Boolean): Typeface =
        variable(context.applicationContext, "Onest.ttf", "'wght' $weight")
            ?.let { if (italic) Typeface.create(it, Typeface.ITALIC) else it } ?: Typeface.create("sans-serif", if (italic) Typeface.ITALIC else Typeface.NORMAL)

    /** Respeita "remover animações" do sistema (movimento reduzido). */
    fun animationsOn(): Boolean = android.animation.ValueAnimator.areAnimatorsEnabled()

    /** Curva de "mola amortecida": rápida no início, assenta suave (cubic-bezier .32,.72,0,1). */
    val EASE = android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /** Feedback físico ao pressionar: a peça afunda um pouco (escala 0,97). */
    fun View.pressable(scaleTo: Float = 0.97f): View {
        val pressed = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(this@pressable, View.SCALE_X, scaleTo),
                android.animation.ObjectAnimator.ofFloat(this@pressable, View.SCALE_Y, scaleTo),
            )
            duration = 110
            interpolator = EASE
        }
        val rest = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(this@pressable, View.SCALE_X, 1f),
                android.animation.ObjectAnimator.ofFloat(this@pressable, View.SCALE_Y, 1f),
            )
            duration = 220
            interpolator = EASE
        }
        stateListAnimator = android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), rest)
        }
        return this
    }

    /** Sombra suave e tingida de marrom (luz de cima, papel sobre papel). */
    fun View.softShadow(elevationDp: Float): View {
        elevation = elevationDp * resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = SHADOW
            outlineSpotShadowColor = SHADOW
        }
        return this
    }

    /** Fundo de papel com uma granulação quase imperceptível (tira o aspecto "chapado"). */
    fun Context.paperBackground(): Drawable {
        val size = 128
        val rnd = java.util.Random(11)
        val px = IntArray(size * size) {
            val v = rnd.nextInt(256)
            Color.argb(if (v > 128) 10 else 0, 60, 48, 30)
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val grain = android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(BG), grain))
    }

    fun Context.dp(v: Float) = v * resources.displayMetrics.density
    fun Context.dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ---- Fundos ------------------------------------------------------------------

    fun Context.rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp)
        setColor(fill)
        if (stroke != 0) setStroke(dpi(strokeDp), stroke)
    }

    fun Context.pill(fill: Int, stroke: Int = LINE, radiusDp: Float = 100f) = rounded(fill, radiusDp, stroke)

    fun Context.circle(fill: Int, stroke: Int, strokeDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dpi(strokeDp), stroke)
    }

    /** Efeito de toque (ripple) por cima de um fundo. */
    fun ripple(content: Drawable?, mask: Drawable?, color: Int = Color.parseColor("#1A000000")) =
        RippleDrawable(ColorStateList.valueOf(color), content, mask)

    fun Context.card(radiusDp: Float = 20f, elevationDp: Float = 1.5f, view: View) {
        view.background = ripple(rounded(SURFACE, radiusDp), rounded(Color.WHITE, radiusDp))
        view.softShadow(elevationDp)
        view.clipToOutline = true
        view.pressable(0.98f)
    }

    // ---- Texto -------------------------------------------------------------------

    fun Context.label(text: String, sizeSp: Float = 13f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) MEDIUM else REGULAR
    }

    fun Context.title(text: String, sizeSp: Float) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(INK)
        typeface = DISPLAY
        // Títulos grandes: tracking negativo e entrelinha curta (presença).
        letterSpacing = if (sizeSp >= 28f) -0.025f else -0.01f
        setLineSpacing(0f, 1.05f)
    }

    // ---- Layouts -----------------------------------------------------------------

    fun Context.vertical(paddingDp: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dpi(paddingDp)
        setPadding(p, p, p, p)
    }

    fun Context.horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun Context.space(wDp: Float, hDp: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpi(wDp), dpi(hDp))
    }

    fun weightSpace(context: Context) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    // ---- Ícones e botões ---------------------------------------------------------

    fun Context.icon(icon: Icon, color: Int = INK, sizeDp: Float = 24f, strokeDp: Float = 1.8f) =
        IconDrawable(icon, color, dpi(sizeDp), dp(strokeDp))

    /** Botão redondo só com ícone (44dp). */
    fun Context.iconButton(icon: Icon, color: Int = INK, sizeDp: Float = 44f, desc: String? = null, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(icon(icon, color))
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(null, circle(Color.WHITE, 0, 0f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
        // Botão só com ícone precisa de rótulo para o leitor de tela (em português).
        contentDescription = desc ?: icon.label
        pressable(0.92f)
    }

    fun Context.primaryButton(text: String, color: Int = ACCENT, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = MEDIUM
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minHeight = dpi(50f)
        setPadding(dpi(22f), 0, dpi(22f), 0)
        background = ripple(rounded(color, 14f), rounded(Color.WHITE, 14f), Color.parseColor("#33FFFFFF"))
        setOnClickListener { onClick() }
        pressable()
    }

    fun Context.secondaryButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = MEDIUM
        gravity = Gravity.CENTER
        setTextColor(INK)
        minHeight = dpi(50f)
        setPadding(dpi(22f), 0, dpi(22f), 0)
        background = ripple(rounded(FIELD, 14f), rounded(Color.WHITE, 14f))
        setOnClickListener { onClick() }
        pressable()
    }

    /** Botão em pílula com ícone e texto (barra de seleção, ações rápidas). */
    fun Context.pillButton(icon: Icon?, text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = MEDIUM
        gravity = Gravity.CENTER
        setTextColor(INK)
        minHeight = dpi(40f)
        setPadding(dpi(14f), 0, dpi(16f), 0)
        if (icon != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(icon, INK, 20f), null, null, null)
            compoundDrawablePadding = dpi(8f)
        }
        background = ripple(pill(Color.TRANSPARENT, 0), pill(Color.WHITE, 0))
        setOnClickListener { onClick() }
        pressable(0.95f)
    }

    fun Context.textField(initial: String, hint: String, multiLine: Boolean = false) = EditText(this).apply {
        setText(initial)
        this.hint = hint
        typeface = REGULAR
        textSize = 16f
        setTextColor(INK)
        setHintTextColor(MUTED)
        background = rounded(FIELD, 14f)
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (multiLine) {
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
    }

    // ---- Controles ---------------------------------------------------------------

    /** Controle segmentado (ex.: "Traço inteiro | Por área"). */
    fun Context.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = horizontal().apply {
            background = pill(FIELD, 0)
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        val items = ArrayList<TextView>()
        fun style(sel: Int) = items.forEachIndexed { i, t ->
            t.background = if (i == sel) pill(Color.WHITE, 0) else null
            t.elevation = if (i == sel) dp(1f) else 0f
            t.setTextColor(if (i == sel) INK else MUTED)
        }
        options.forEachIndexed { i, o ->
            val t = TextView(this).apply {
                text = o
                textSize = 14f
                typeface = MEDIUM
                gravity = Gravity.CENTER
                minHeight = dpi(38f)
                setOnClickListener { style(i); onSelect(i) }
            }
            items.add(t)
            box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        style(selected)
        return box
    }

    /** Linha com ícone, texto e chave liga/desliga. */
    @Suppress("UseSwitchCompatOrMaterialCode")
    fun Context.switchRow(icon: Icon, text: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = horizontal().apply { minimumHeight = dpi(52f) }
        row.addView(ImageView(this).apply { setImageDrawable(icon(icon, MUTED, 22f)) },
            LinearLayout.LayoutParams(dpi(24f), dpi(24f)).apply { marginEnd = dpi(14f) })
        row.addView(label(text, 15f, INK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.WHITE),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, Color.parseColor("#C9CED6")),
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    // ---- Painéis inferiores ------------------------------------------------------------

    /** Painel que sobe de baixo, com alça e título. [build] preenche o conteúdo. */
    fun Context.sheet(title: String?, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = vertical().apply { setPadding(dpi(20f), dpi(10f), dpi(20f), dpi(20f)) }
        content.addView(View(this).apply { background = pill(Color.parseColor("#D5D9E0"), 0) },
            LinearLayout.LayoutParams(dpi(36f), dpi(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpi(14f)
            })
        if (title != null) {
            content.addView(title(title, 19f), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dpi(12f) })
        }
        build(content, dialog)
        val scroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(SURFACE)
                val r = dp(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val holder = FrameLayout(this)
        holder.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(holder)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            val maxW = minOf(resources.displayMetrics.widthPixels, dpi(600f))
            setLayout(maxW, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.32f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        return dialog
    }

    class SheetItem(val icon: Icon, val text: String, val danger: Boolean = false, val onClick: () -> Unit)

    /** Lista de ações com ícone (substitui menus e diálogos de lista). */
    fun Context.actionSheet(title: String?, items: List<SheetItem>, header: View? = null): Dialog = sheet(title) { box, dialog ->
        if (header != null) box.addView(header)
        for (it in items) {
            val color = if (it.danger) DANGER else INK
            val row = horizontal().apply {
                minimumHeight = dpi(54f)
                setPadding(dpi(6f), 0, dpi(6f), 0)
                background = ripple(null, rounded(Color.WHITE, 14f))
                setOnClickListener { _ -> dialog.dismiss(); it.onClick() }
            }
            row.addView(ImageView(this).apply { setImageDrawable(icon(it.icon, color, 22f)) },
                LinearLayout.LayoutParams(dpi(24f), dpi(24f)).apply { marginEnd = dpi(16f) })
            row.addView(label(it.text, 16f, color))
            box.addView(row)
        }
    }

    fun Context.confirm(title: String, message: String, action: String, onOk: () -> Unit) {
        sheet(title) { box, dialog ->
            box.addView(label(message, 15f, MUTED).apply { setLineSpacing(0f, 1.15f) })
            box.addView(buttonRow(
                secondaryButton("Cancelar") { dialog.dismiss() },
                primaryButton(action, DANGER) { dialog.dismiss(); onOk() },
            ))
        }
    }

    fun Context.promptText(title: String, initial: String, hint: String = "", action: String = "Salvar", onOk: (String) -> Unit) {
        sheet(title) { box, dialog ->
            val field = textField(initial, hint)
            box.addView(field)
            box.addView(buttonRow(
                secondaryButton("Cancelar") { dialog.dismiss() },
                primaryButton(action) { dialog.dismiss(); onOk(field.text.toString().trim()) },
            ))
            field.requestFocus()
            field.selectAll()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun Context.buttonRow(vararg buttons: View) = horizontal().apply {
        setPadding(0, dpi(20f), 0, 0)
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dpi(10f)
            })
        }
    }

    /** Seção com título pequeno em cinza. */
    /** Título de seção: frase normal (sem CAIXA ALTA), seminegrito, um tom abaixo do texto principal. */
    fun Context.sectionLabel(text: String) = label(text, 15f, INK, bold = true).apply {
        typeface = BOLD
        alpha = 0.88f
        setPadding(0, dpi(18f), 0, dpi(8f))
    }

    /** Agrupa vistas em linhas de [perRow] itens. */
    fun Context.wrapRow(children: List<View>, perRow: Int): LinearLayout {
        val col = vertical()
        var row: LinearLayout? = null
        children.forEachIndexed { i, v ->
            if (i % perRow == 0) row = horizontal().also { col.addView(it) }
            row!!.addView(v)
        }
        return col
    }
}
