package com.jaredwinick.colors.camera.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkyMaskStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `draft requires preview and activation backs up previous mask`() {
        val root = temporaryFolder.newFolder("mask")
        val store = SkyMaskStore(root, json(0.90))
        assertEquals(MaskRecoverySource.BUNDLED_DEFAULT, store.initialize().source)
        assertEquals(0.90, store.active().includePolygon[2].y, 0.0001)

        store.stage(json(0.80))
        assertEquals(0.90, store.active().includePolygon[2].y, 0.0001)
        assertThrows(IllegalArgumentException::class.java) { store.activatePreviewedDraft() }

        store.markDraftPreviewed(listOf(MaskSize(100, 100), MaskSize(50, 50)))
        store.activatePreviewedDraft()
        assertEquals(0.80, store.active().includePolygon[2].y, 0.0001)
        assertTrue(store.backupAvailable())

        store.stageBackup()
        store.markDraftPreviewed(listOf(MaskSize(100, 100)))
        store.activatePreviewedDraft()
        assertEquals(0.90, store.active().includePolygon[2].y, 0.0001)
    }

    @Test
    fun `corrupt active mask recovers from backup and preserves rejected JSON`() {
        val root = temporaryFolder.newFolder("recovery")
        val store = SkyMaskStore(root, json(0.90))
        store.initialize()
        store.stage(json(0.80))
        store.markDraftPreviewed(listOf(MaskSize(100, 100)))
        store.activatePreviewedDraft()
        File(root, "active.json").writeText("{bad json")

        val recovered = SkyMaskStore(root, json(0.70))
        val initialization = recovered.initialize()

        assertEquals(MaskRecoverySource.BACKUP, initialization.source)
        assertTrue(initialization.rejectedActivePreserved)
        assertTrue(recovered.rejectedActiveAvailable())
        assertEquals(0.90, recovered.active().includePolygon[2].y, 0.0001)
    }

    @Test
    fun `invalid import never changes active mask`() {
        val store = SkyMaskStore(temporaryFolder.newFolder("invalid"), json(0.90))
        store.initialize()

        assertThrows(IllegalArgumentException::class.java) { store.stage("{}") }
        assertEquals(0.90, store.active().includePolygon[2].y, 0.0001)
        assertFalse(store.backupAvailable())
    }

    private fun json(bottom: Double) = """
        {
          "schema_version": 1,
          "coordinate_space": "normalized",
          "minimum_included_fraction": 0.1,
          "minimum_included_pixels": 1,
          "include_polygon": [[0,0],[1,0],[1,$bottom],[0,$bottom]],
          "exclude_polygons": [],
          "exclude_rectangles": []
        }
    """.trimIndent()
}

