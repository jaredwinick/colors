package com.jaredwinick.colors.camera.mask

import android.content.Context
import com.jaredwinick.colors.camera.R
import java.io.File

class SkyMaskRepository(context: Context) {
    private val store = SkyMaskStore(
        directory = File(context.filesDir, "sky-mask"),
        bundledDefaultJson = context.resources.openRawResource(R.raw.sky_mask)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() },
    )

    val initialization: MaskInitialization = store.initialize()

    fun active(): SkyMaskConfig = store.active()

    fun activeJson(): String = store.activeJson()

    fun stage(json: String): SkyMaskConfig = store.stage(json)

    fun stageBundledDefault(): SkyMaskConfig = store.stageBundledDefault()

    fun stageBackup(): SkyMaskConfig = store.stageBackup()

    fun draft(): SkyMaskConfig = store.draft()

    fun markDraftPreviewed(sizes: List<MaskSize>): List<MaskStatistics> =
        store.markDraftPreviewed(sizes)

    fun activatePreviewedDraft(): SkyMaskConfig = store.activatePreviewedDraft()

    fun discardDraft() = store.discardDraft()

    fun backupAvailable(): Boolean = store.backupAvailable()

    fun rejectedActiveAvailable(): Boolean = store.rejectedActiveAvailable()

    /**
     * Returns only a threshold-validated sampling mask. A failure throws before
     * palette construction and never mutates or deletes the source capture.
     */
    fun validatedAnalysisMask(sourceSize: MaskSize, maximumDimension: Int): RasterizedSkyMask =
        SkyMaskRasterizer.rasterize(
            active(),
            SkyMaskRasterizer.analysisSize(sourceSize, maximumDimension),
        )
}
