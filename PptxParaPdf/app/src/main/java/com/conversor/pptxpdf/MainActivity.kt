package com.conversor.pptxpdf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.conversor.pptxpdf.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var pdfFile: File? = null
    private var baseName: String = "apresentacao"

    private val pickPptx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) convert(uri)
    }

    private val savePdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val src = pdfFile
        if (uri != null && src != null) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)!!.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        }
                    }.isSuccess
                }
                toast(if (ok) "PDF salvo com sucesso" else "Não foi possível salvar o PDF")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPick.setOnClickListener {
            pickPptx.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                    "application/vnd.ms-powerpoint",
                    "application/octet-stream"
                )
            )
        }
        b.btnSave.setOnClickListener { savePdf.launch("$baseName.pdf") }
        b.btnShare.setOnClickListener { sharePdf() }
        b.btnOpen.setOnClickListener { openPdf() }

        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent)).ifEmpty { clipUris(intent) }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent).ifEmpty { clipUris(intent) }
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
                toast("Nenhum arquivo .pptx recebido")
            }
            return
        }
        // Em envios múltiplos, prefere o primeiro arquivo que pareça ser uma apresentação.
        val uri = uris.firstOrNull { isPresentationName(displayName(it)) } ?: uris.first()
        if (uris.size > 1) toast("Vários arquivos recebidos: convertendo ${displayName(uri)}")
        convert(uri)
    }

    private fun isPresentationName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".pptx") || n.endsWith(".ppsx") || n.endsWith(".pptm")
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.filterNotNull() ?: emptyList()

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun convert(uri: Uri) {
        val name = displayName(uri)
        baseName = name.substringBeforeLast('.').ifBlank { "apresentacao" }
        b.txtFile.text = name
        b.txtStatus.text = "Lendo arquivo…"
        b.actions.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = true
        b.btnPick.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(cacheDir, "input.pptx")
                    contentResolver.openInputStream(uri)!!.use { inp ->
                        tmp.outputStream().use { inp.copyTo(it) }
                    }
                    val outDir = File(cacheDir, "pdfs").apply { mkdirs() }
                    outDir.listFiles()?.forEach { it.delete() }
                    val out = File(outDir, "$baseName.pdf")
                    PptxToPdfConverter(tmp).convert(out) { done, total ->
                        runOnUiThread {
                            b.progress.isIndeterminate = false
                            b.progress.max = total
                            b.progress.setProgressCompat(done, true)
                            b.txtStatus.text = "Convertendo slide $done de $total…"
                        }
                    }
                    tmp.delete()
                    out
                }
            }
            b.btnPick.isEnabled = true
            b.progress.visibility = View.GONE
            result.onSuccess { file ->
                pdfFile = file
                val kb = file.length() / 1024
                b.txtStatus.text = "Pronto! PDF gerado (${if (kb > 1024) "%.1f MB".format(kb / 1024f) else "$kb KB"})."
                b.actions.visibility = View.VISIBLE
            }.onFailure { e ->
                pdfFile = null
                b.txtStatus.text = "Erro na conversão: ${e.message ?: e.javaClass.simpleName}\n" +
                        "Verifique se o arquivo é um .pptx válido (o formato antigo .ppt não é suportado)."
            }
        }
    }

    private fun pdfUri(): Uri? = pdfFile?.let {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
    }

    private fun sharePdf() {
        val uri = pdfUri() ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Compartilhar PDF"))
    }

    private fun openPdf() {
        val uri = pdfUri() ?: return
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast("Nenhum leitor de PDF instalado")
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "apresentacao.pptx"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
