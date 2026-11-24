package com.guardiangrid

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.models.LocationData
import com.guardiangrid.core.state.DurableIncident
import com.guardiangrid.core.state.FileIncidentRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit test suite validating Task 5: Persistent Incident Management & Deduplication.
 *
 * Covers:
 * - Local incident storage across process death and restarts.
 * - Deduplication of rapid repeated distress signals within the 5-second window.
 * - Evidence file existence verification (never reporting evidence as available unless file exists).
 * - Incident inspection, retrieval, and deletion.
 */
class PersistentIncidentManagementTest {

    private lateinit var tempDir: File
    private lateinit var repo: FileIncidentRepository

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "gg_incidents_test_${System.currentTimeMillis()}").apply { mkdirs() }
        repo = FileIncidentRepository(storageDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSaveAndRetrieveIncident() {
        val incident = DurableIncident(
            incidentId = "INC-1001",
            startTimeEpochMs = 1700000000000L,
            lastUpdatedEpochMs = 1700000005000L,
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            triggerType = DistressType.SCREAM,
            confidence = 0.94f,
            location = LocationData(latitude = 37.7749, longitude = -122.4194, accuracyMeters = 5.0f),
            notes = "Test acoustic distress"
        )

        assertTrue(repo.saveIncident(incident))

        val retrieved = repo.getIncident("INC-1001")
        assertNotNull(retrieved)
        assertEquals("INC-1001", retrieved?.incidentId)
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, retrieved?.level)
        assertEquals(0.94f, retrieved?.confidence ?: 0f, 0.001f)
        assertEquals(37.7749, retrieved?.location?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun testEvidenceFileIntegrityCheck() {
        val nonExistentPath = File(tempDir, "missing_evidence.wav").absolutePath
        val incidentWithMissingEvidence = DurableIncident(
            incidentId = "INC-1002",
            startTimeEpochMs = System.currentTimeMillis(),
            lastUpdatedEpochMs = System.currentTimeMillis(),
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            triggerType = DistressType.SCREAM,
            confidence = 0.98f,
            audioEvidencePath = nonExistentPath
        )

        // Must reject reporting non-existent file as evidence
        assertFalse("Evidence file must not be reported as valid if file does not exist", incidentWithMissingEvidence.hasValidEvidenceFile)

        repo.saveIncident(incidentWithMissingEvidence)
        val loaded = repo.getIncident("INC-1002")
        assertNull("Repository must sanitize missing evidence paths to null", loaded?.audioEvidencePath)

        // Now create a real physical file on disk
        val realFile = File(tempDir, "real_evidence.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val incidentWithRealEvidence = incidentWithMissingEvidence.copy(audioEvidencePath = realFile.absolutePath)
        assertTrue("Real physical file must report as valid evidence", incidentWithRealEvidence.hasValidEvidenceFile)

        repo.saveIncident(incidentWithRealEvidence)
        val loadedReal = repo.getIncident("INC-1002")
        assertEquals(realFile.absolutePath, loadedReal?.audioEvidencePath)
    }

    @Test
    fun testSignalDeduplication() {
        val signal1 = DistressSignal(
            id = "s1",
            type = DistressType.SCREAM,
            confidence = 0.85f,
            source = "AcousticClassifier"
        )
        val signal2 = DistressSignal(
            id = "s2",
            type = DistressType.SCREAM,
            confidence = 0.96f, // Higher confidence
            source = "AcousticClassifier"
        )

        // First signal creates new incident
        val inc1 = repo.createOrDeduplicateIncident(signal1, null, false, "First signal")
        assertEquals(0.85f, inc1.confidence, 0.001f)

        // Second signal arrives within 5 seconds -> must deduplicate and merge
        val inc2 = repo.createOrDeduplicateIncident(signal2, null, false, "Second signal")
        assertEquals("Incident ID must be preserved during deduplication", inc1.incidentId, inc2.incidentId)
        assertEquals("Confidence must be upgraded to highest confidence", 0.96f, inc2.confidence, 0.001f)
        assertEquals(1, repo.getAllIncidents().size)
    }

    @Test
    fun testIncidentsSurviveProcessRestart() {
        val incident = DurableIncident(
            incidentId = "INC-RESTART",
            startTimeEpochMs = 1700000000000L,
            lastUpdatedEpochMs = 1700000000000L,
            level = EmergencyLevel.CONFIRMED_EMERGENCY,
            triggerType = DistressType.FALL_IMPACT,
            confidence = 0.99f
        )
        repo.saveIncident(incident)

        // Re-load repository from same storage directory
        val restartedRepo = FileIncidentRepository(storageDir = tempDir)
        val all = restartedRepo.getAllIncidents()
        assertEquals(1, all.size)
        assertEquals("INC-RESTART", all[0].incidentId)
        assertEquals(DistressType.FALL_IMPACT, all[0].triggerType)
    }

    @Test
    fun testDeleteIncident() {
        val incident = DurableIncident(
            incidentId = "INC-DEL",
            startTimeEpochMs = System.currentTimeMillis(),
            lastUpdatedEpochMs = System.currentTimeMillis(),
            level = EmergencyLevel.RESOLVED,
            triggerType = DistressType.MANUAL_SOS,
            confidence = 1.0f
        )
        repo.saveIncident(incident)
        assertEquals(1, repo.getAllIncidents().size)

        assertTrue(repo.deleteIncident("INC-DEL"))
        assertEquals(0, repo.getAllIncidents().size)
    }
}
