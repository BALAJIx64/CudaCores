package com.guardiangrid

import com.guardiangrid.core.models.AudioStage
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.TwoStageAudioEvidenceRecorder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit test suite validating Task 6: Audio Evidence Privacy & Lifecycle.
 *
 * Covers:
 * - Controlled audio capture in private application directory.
 * - Stage 1 rolling preview capture into private files.
 * - File existence and metadata verification.
 * - Explicit user deletion of evidence files.
 * - Cleanup of temporary unverified preview files.
 */
class AudioEvidenceLifecycleTest {

    private lateinit var tempDir: File
    private lateinit var circularBuffer: CircularAudioBuffer
    private lateinit var recorder: TwoStageAudioEvidenceRecorder

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "gg_audio_ev_${System.currentTimeMillis()}").apply { mkdirs() }
        circularBuffer = CircularAudioBuffer(durationMs = 5000L, sampleRate = 16000)
        recorder = TwoStageAudioEvidenceRecorder(
            circularBuffer = circularBuffer,
            cacheDirectory = tempDir
        )
    }

    @After
    fun tearDown() {
        recorder.stopRecording()
        tempDir.deleteRecursively()
    }

    @Test
    fun testCaptureStageOnePreview() = runBlocking {
        // Feed synthetic PCM samples into circular buffer (1 second of 16kHz audio = 16000 samples)
        val testPcm = ShortArray(16000) { (it % 100).toShort() }
        circularBuffer.write(testPcm)

        val result = recorder.captureStageOneBuffer(1000L)
        assertTrue("Stage 1 buffer capture must succeed", result.isSuccess)

        val segment = result.getOrNull()
        assertNotNull(segment)
        assertEquals(AudioStage.STAGE_1_PREVIEW, segment?.stage)
        assertEquals(16000, segment?.sampleRate)

        val evidenceFile = File(segment!!.filePath)
        assertTrue("Evidence file must physically exist in private storage", evidenceFile.exists())
        assertTrue("WAV header + PCM must have non-zero file size", evidenceFile.length() > 44)
    }

    @Test
    fun testUserDeleteEvidence() = runBlocking {
        val testPcm = ShortArray(8000) { 100.toShort() }
        circularBuffer.write(testPcm)

        val result = recorder.captureStageOneBuffer(500L)
        val filePath = result.getOrNull()!!.filePath
        val file = File(filePath)
        assertTrue(file.exists())

        // User deletes evidence file
        val deleted = recorder.deleteEvidence(filePath)
        assertTrue("User deletion must succeed", deleted)
        assertFalse("Evidence file must be permanently removed", file.exists())
    }

    @Test
    fun testCleanupTemporaryPreviews() {
        val evidenceDir = File(tempDir, "evidence").apply { mkdirs() }
        val oldFile = File(evidenceDir, "stage1_preview_old.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            setLastModified(System.currentTimeMillis() - 7200_000L) // 2 hours old
        }
        val freshFile = File(evidenceDir, "stage1_preview_fresh.wav").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
            setLastModified(System.currentTimeMillis()) // Fresh
        }

        val cleaned = recorder.cleanupTemporaryPreviews(maxAgeMs = 3600_000L) // 1 hour cutoff
        assertEquals(1, cleaned)
        assertFalse("Old unverified preview must be deleted", oldFile.exists())
        assertTrue("Fresh preview must be retained", freshFile.exists())
    }
}
