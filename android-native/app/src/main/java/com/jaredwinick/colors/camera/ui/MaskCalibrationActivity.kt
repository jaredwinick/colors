package com.jaredwinick.colors.camera.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.mask.MaskPreviewRenderer
import com.jaredwinick.colors.camera.mask.MaskSize
import com.jaredwinick.colors.camera.mask.SkyMaskConfig
import com.jaredwinick.colors.camera.mask.SkyMaskRasterizer
import com.jaredwinick.colors.camera.mask.SkyMaskRepository
import com.jaredwinick.colors.camera.persistence.ProductionCaptureRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MaskCalibrationActivity : AppCompatActivity() {
    private lateinit var masks: SkyMaskRepository
    private lateinit var captures: ProductionCaptureRepository
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var status: TextView
    private lateinit var previewStatus: TextView
    private lateinit var previewImage: ImageView
    private lateinit var confirmation: CheckBox
    private lateinit var activateButton: Button
    private lateinit var restoreBackupButton: Button
    private lateinit var sharePreviewButton: Button
    private val renderer = MaskPreviewRenderer()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewFile: File? = null
    private var draftPreviewed = false

    private val importMask = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { readDocument(uri) }
            .onSuccess { json -> stageAndPreview(json, "Imported draft") }
            .onFailure { error -> showError(error) }
    }

    private val exportMask = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(masks.activeJson().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Android could not open the selected document")
        }.onSuccess {
            toast("Active sky mask exported")
        }.onFailure(::showError)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mask_calibration)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.mask_calibration)

        masks = SkyMaskRepository(this)
        captures = ProductionCaptureRepository(this)
        configurationStore = ConfigurationStore(this)
        status = findViewById(R.id.maskStatus)
        previewStatus = findViewById(R.id.maskPreviewStatus)
        previewImage = findViewById(R.id.maskPreviewImage)
        confirmation = findViewById(R.id.confirmMaskPreview)
        activateButton = findViewById(R.id.activateMask)
        restoreBackupButton = findViewById(R.id.previewBackupMask)
        sharePreviewButton = findViewById(R.id.shareMaskPreview)

        findViewById<Button>(R.id.previewActiveMask).setOnClickListener {
            preview(masks.active(), "Active mask", isDraft = false)
        }
        findViewById<Button>(R.id.importMask).setOnClickListener {
            importMask.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        findViewById<Button>(R.id.previewDefaultMask).setOnClickListener {
            runCatching { masks.stageBundledDefault() }
                .onSuccess { preview(it, "Bundled default draft", isDraft = true) }
                .onFailure(::showError)
        }
        restoreBackupButton.setOnClickListener {
            runCatching { masks.stageBackup() }
                .onSuccess { preview(it, "Backup draft", isDraft = true) }
                .onFailure(::showError)
        }
        findViewById<Button>(R.id.discardMaskDraft).setOnClickListener {
            masks.discardDraft()
            clearDraftState("Draft discarded; the active mask was not changed")
        }
        confirmation.setOnCheckedChangeListener { _, checked ->
            activateButton.isEnabled = checked && draftPreviewed
        }
        activateButton.setOnClickListener { activateDraft() }
        findViewById<Button>(R.id.exportMask).setOnClickListener {
            exportMask.launch("colors-sky-mask.json")
        }
        sharePreviewButton.setOnClickListener { sharePreview() }

        refreshStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun stageAndPreview(json: String, label: String) {
        runCatching { masks.stage(json) }
            .onSuccess { config -> preview(config, label, isDraft = true) }
            .onFailure(::showError)
    }

    private fun preview(config: SkyMaskConfig, label: String, isDraft: Boolean) {
        val image = captures.latestCommittedImage()
        if (image == null) {
            showError(IllegalStateException("Take a production capture before previewing a sky mask"))
            return
        }
        setBusy("Generating $label preview…")
        val grid = findViewById<CheckBox>(R.id.showCalibrationGrid).isChecked
        val output = File(filesDir, "exports/colors-mask-calibration-preview.jpg")
        val analysisMaximum = configurationStore.load().paletteAnalysisDimension
        executor.execute {
            runCatching {
                val result = renderer.render(image, output, config, grid)
                val analysisSize = SkyMaskRasterizer.analysisSize(result.statistics.size, analysisMaximum)
                val validated = if (isDraft) {
                    masks.markDraftPreviewed(listOf(result.statistics.size, analysisSize))
                } else {
                    listOf(
                        result.statistics,
                        SkyMaskRasterizer.rasterize(config, analysisSize).statistics,
                    )
                }
                result to validated
            }.onSuccess { (result, statistics) ->
                mainHandler.post {
                    previewFile = result.output
                    previewImage.setImageBitmap(BitmapFactory.decodeFile(result.output.absolutePath))
                    val full = statistics.first()
                    val analysis = statistics.last()
                    previewStatus.text = buildString {
                        appendLine("$label validated")
                        appendLine(
                            "Full: ${full.size.width}×${full.size.height}; " +
                                "${full.includedPixels} included " +
                                "(${"%.2f".format(Locale.US, full.includedFraction * 100)}%)",
                        )
                        append(
                            "Analysis: ${analysis.size.width}×${analysis.size.height}; " +
                                "${analysis.includedPixels} included " +
                                "(${"%.2f".format(Locale.US, analysis.includedFraction * 100)}%)",
                        )
                    }
                    draftPreviewed = isDraft
                    confirmation.isChecked = false
                    confirmation.isEnabled = isDraft
                    sharePreviewButton.isEnabled = true
                    activateButton.isEnabled = false
                }
            }.onFailure { error -> mainHandler.post { showError(error) } }
        }
    }

    private fun activateDraft() {
        if (!draftPreviewed || !confirmation.isChecked) {
            showError(IllegalStateException("Preview and confirm the draft before activation"))
            return
        }
        runCatching { masks.activatePreviewedDraft() }
            .onSuccess {
                clearDraftState("Previewed mask activated; the previous mask is now the backup")
                refreshStatus()
            }
            .onFailure(::showError)
    }

    private fun sharePreview() {
        val file = previewFile?.takeIf(File::isFile)
        if (file == null) {
            toast("Generate a mask preview first")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share sky-mask calibration preview"))
    }

    private fun refreshStatus() {
        val active = masks.active()
        status.text = buildString {
            appendLine("Active schema: ${active.schemaVersion} (${active.coordinateSpace})")
            appendLine("Include boundary points: ${active.includePolygon.size}")
            appendLine("Exclusion polygons / rectangles: ${active.excludePolygons.size} / ${active.excludeRectangles.size}")
            appendLine("Backup available: ${if (masks.backupAvailable()) "yes" else "no"}")
            appendLine("Loaded from: ${masks.initialization.source.name}")
            append(
                "Rejected active preserved: " +
                    if (masks.rejectedActiveAvailable()) "yes" else "no",
            )
        }
        restoreBackupButton.isEnabled = masks.backupAvailable()
    }

    private fun clearDraftState(message: String) {
        draftPreviewed = false
        confirmation.isChecked = false
        confirmation.isEnabled = false
        activateButton.isEnabled = false
        previewStatus.text = message
        toast(message)
    }

    private fun setBusy(message: String) {
        draftPreviewed = false
        confirmation.isChecked = false
        confirmation.isEnabled = false
        activateButton.isEnabled = false
        sharePreviewButton.isEnabled = false
        previewStatus.text = message
    }

    private fun readDocument(uri: Uri): String {
        val bytes = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                bytes.write(buffer, 0, read)
                require(bytes.size() <= MAX_MASK_BYTES) { "Mask JSON must be 1 MB or smaller" }
            }
        } ?: throw IllegalStateException("Android could not open the selected document")
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun showError(error: Throwable) {
        val message = error.message ?: "Sky-mask operation failed"
        previewStatus.text = "ERROR: $message\nThe active mask was not changed."
        toast(message)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val MAX_MASK_BYTES = 1_048_576
    }
}

