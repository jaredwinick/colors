package com.jaredwinick.colors.camera.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class CaptureArtifactPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `capture identity requires UUIDv4 and normalized UTC time`() {
        val captureId = UUID.randomUUID().toString()
        assertEquals(captureId, CaptureArtifactPolicy.requireCaptureId(captureId))
        assertEquals(
            "2026-08-30T12:34:56Z",
            CaptureArtifactPolicy.requireUtcTimestamp("2026-08-30T12:34:56Z"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CaptureArtifactPolicy.requireCaptureId("not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureArtifactPolicy.requireUtcTimestamp("2026-08-30T06:34:56-06:00")
        }
    }

    @Test
    fun `orientation is normalized before longest-edge resize`() {
        val portrait = ImageTransformPlanner.plan(4_032, 3_024, exifOrientation = 6, 1_920)
        assertEquals(90, portrait.rotationDegrees)
        assertEquals(ImageDimensions(3_024, 4_032), portrait.orientedDimensions)
        assertEquals(ImageDimensions(1_440, 1_920), portrait.finalDimensions)

        val landscape = ImageTransformPlanner.plan(4_032, 3_024, exifOrientation = 1, 1_920)
        assertEquals(ImageDimensions(1_920, 1_440), landscape.finalDimensions)
    }

    @Test
    fun `images are never enlarged and oversized JPEGs are rejected`() {
        val small = ImageTransformPlanner.plan(800, 600, exifOrientation = 1, 1_920)
        assertEquals(ImageDimensions(800, 600), small.finalDimensions)
        assertThrows(IllegalArgumentException::class.java) {
            CaptureArtifactPolicy.requireJpegSize(CaptureArtifactPolicy.MAX_JPEG_BYTES + 1)
        }
    }

    @Test
    fun `normal and absent EXIF orientation are display ready`() {
        assertTrue(CaptureArtifactPolicy.isDisplayReadyExifOrientation(0))
        assertTrue(CaptureArtifactPolicy.isDisplayReadyExifOrientation(1))
        assertFalse(CaptureArtifactPolicy.isDisplayReadyExifOrientation(6))
    }

    @Test
    fun `incomplete work and new orphan images are removed without deleting legacy captures`() {
        val root = temporaryFolder.newFolder("captures")
        val work = File(root, "work").apply { mkdirs() }
        val images = File(root, "images").apply { mkdirs() }
        val metadata = File(root, "metadata").apply { mkdirs() }
        val captureId = UUID.randomUUID().toString()
        File(work, "$captureId.raw.jpg").writeText("partial")
        File(images, ".$captureId.jpg.tmp").writeText("partial")
        val orphan = File(images, "$captureId.jpg").apply { writeText("orphan") }
        val legacy = File(images, "colors-legacy.jpg").apply { writeText("keep") }

        val removed = IncompleteCaptureCleaner.clean(work, images, metadata)

        assertEquals(3, removed)
        assertFalse(orphan.exists())
        assertTrue(legacy.exists())
    }
}
