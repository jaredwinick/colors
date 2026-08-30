package com.jaredwinick.colors.camera.mask

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class MaskRecoverySource {
    ACTIVE,
    BACKUP,
    BUNDLED_DEFAULT,
}

data class MaskInitialization(val source: MaskRecoverySource, val rejectedActivePreserved: Boolean)

class SkyMaskStore(
    private val directory: File,
    private val bundledDefaultJson: String,
) {
    private val activeFile = File(directory, "active.json")
    private val backupFile = File(directory, "backup.json")
    private val draftFile = File(directory, "draft.json")
    private val previewMarkerFile = File(directory, "draft-preview.txt")
    private val rejectedActiveFile = File(directory, "rejected-active.json")

    @Synchronized
    fun initialize(): MaskInitialization {
        directory.mkdirs()
        val canonicalDefault = SkyMaskJson.encode(SkyMaskJson.decode(bundledDefaultJson))
        if (readValid(activeFile) != null) return MaskInitialization(MaskRecoverySource.ACTIVE, false)

        val rejected = if (activeFile.exists()) {
            atomicWrite(rejectedActiveFile, activeFile.readText(Charsets.UTF_8))
            true
        } else {
            false
        }
        val backup = readValid(backupFile)
        return if (backup != null) {
            atomicWrite(activeFile, SkyMaskJson.encode(backup))
            MaskInitialization(MaskRecoverySource.BACKUP, rejected)
        } else {
            atomicWrite(activeFile, canonicalDefault)
            MaskInitialization(MaskRecoverySource.BUNDLED_DEFAULT, rejected)
        }
    }

    @Synchronized
    fun active(): SkyMaskConfig = readRequired(activeFile, "Active mask")

    @Synchronized
    fun activeJson(): String = SkyMaskJson.encode(active())

    @Synchronized
    fun backupAvailable(): Boolean = readValid(backupFile) != null

    @Synchronized
    fun rejectedActiveAvailable(): Boolean = rejectedActiveFile.isFile

    @Synchronized
    fun stage(json: String): SkyMaskConfig {
        val config = SkyMaskJson.decode(json)
        atomicWrite(draftFile, SkyMaskJson.encode(config))
        previewMarkerFile.delete()
        return config
    }

    @Synchronized
    fun stageBundledDefault(): SkyMaskConfig = stage(bundledDefaultJson)

    @Synchronized
    fun stageBackup(): SkyMaskConfig {
        val backup = readRequired(backupFile, "No valid mask backup is available")
        return stage(SkyMaskJson.encode(backup))
    }

    @Synchronized
    fun draft(): SkyMaskConfig = readRequired(draftFile, "No mask draft is awaiting preview")

    @Synchronized
    fun markDraftPreviewed(sizes: List<MaskSize>): List<MaskStatistics> {
        require(sizes.isNotEmpty()) { "At least one preview size is required" }
        val json = draftFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
            ?: throw IllegalStateException("No mask draft is awaiting preview")
        val config = SkyMaskJson.decode(json)
        val statistics = sizes.distinct().map { SkyMaskRasterizer.rasterize(config, it).statistics }
        val marker = buildString {
            append(sha256(json))
            sizes.distinct().forEach { size -> append("|${size.width}x${size.height}") }
        }
        atomicWrite(previewMarkerFile, marker)
        return statistics
    }

    @Synchronized
    fun activatePreviewedDraft(): SkyMaskConfig {
        val draftJson = draftFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
            ?: throw IllegalStateException("No mask draft is awaiting activation")
        val marker = previewMarkerFile.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        val fields = marker.split('|')
        require(fields.firstOrNull() == sha256(draftJson) && fields.size >= 2) {
            "The draft must be previewed successfully before activation"
        }
        val config = SkyMaskJson.decode(draftJson)
        fields.drop(1).forEach { encodedSize ->
            val components = encodedSize.split('x')
            require(components.size == 2) { "The preview confirmation is malformed" }
            SkyMaskRasterizer.rasterize(
                config,
                MaskSize(components[0].toInt(), components[1].toInt()),
            )
        }
        readValid(activeFile)?.let { current -> atomicWrite(backupFile, SkyMaskJson.encode(current)) }
        atomicWrite(activeFile, SkyMaskJson.encode(config))
        discardDraft()
        return config
    }

    @Synchronized
    fun discardDraft() {
        draftFile.delete()
        previewMarkerFile.delete()
    }

    private fun readRequired(file: File, label: String): SkyMaskConfig {
        require(file.isFile) { label }
        return runCatching { SkyMaskJson.decode(file.readText(Charsets.UTF_8)) }
            .getOrElse { error -> throw IllegalStateException("$label is invalid: ${error.message}", error) }
    }

    private fun readValid(file: File): SkyMaskConfig? = file.takeIf(File::isFile)?.let {
        runCatching { SkyMaskJson.decode(it.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun atomicWrite(destination: File, value: String) {
        directory.mkdirs()
        val temporary = File(directory, ".${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

