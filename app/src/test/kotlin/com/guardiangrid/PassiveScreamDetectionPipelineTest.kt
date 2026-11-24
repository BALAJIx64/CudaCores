package com.guardiangrid

import com.guardiangrid.core.models.DistressSignal
import com.guardiangrid.core.models.DistressType
import com.guardiangrid.core.models.EmergencyLevel
import com.guardiangrid.core.state.EmergencyCoordinatorImpl
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.audio.AudioCaptureManager
import com.guardiangrid.modules.audio.AudioModuleStub
import com.guardiangrid.modules.audio.CircularAudioBuffer
import com.guardiangrid.modules.audio.ScreamDistressClassifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class PassiveScreamDetectionPipelineTest {

    @Before
    fun setUp() {
        EmergencyStateHolder.reset()
    }

    @Test
    fun testPassiveMicrophoneToAlertWiring() = runBlocking {
        EmergencyStateHolder.updatePermissions(audio = true, location = true, notification = true, sms = true)
        val audioStub = AudioModuleStub()
        val coordinator = EmergencyCoordinatorImpl(audioDetector = audioStub)

        // 1. Protection Activation
        coordinator.startProtection()
        assertTrue(EmergencyStateHolder.state.value.isProtectionActive)
        assertEquals(true, EmergencyStateHolder.state.value.isAudioMonitoringRunning)

        // 2. Start monitoring flow
        val emittedSignals = mutableListOf<DistressSignal>()
        val flow = audioStub.startMonitoring()
        val monitorJob = launch {
            flow.collect { signal ->
                emittedSignals.add(signal)
                coordinator.handleDistressSignal(signal)
            }
        }
        kotlinx.coroutines.yield()

        assertTrue(audioStub.isMonitoring())
        assertTrue(audioStub.captureManager.isCapturing)

        // 3. Test faint / distant scream (2800 Hz harmonic tone at faint -32 dBFS amplitude)
        val faintScreamChunk = ShortArray(AudioCaptureManager(16000).sampleRate / 2) { i ->
            val f1 = sin(2.0 * PI * 2800.0 * i / 16000.0)
            val f2 = 0.5 * sin(2.0 * PI * 3500.0 * i / 16000.0)
            ((f1 + f2) * 1200).toInt().toShort() // Faint amplitude (~ -32 dBFS)
        }

        // Feed chunks into capture manager
        for (repeat in 1..4) {
            audioStub.captureManager.injectSyntheticSamples(faintScreamChunk)
        }

        // Evaluate buffer directly through classifier
        val snapshot = audioStub.circularBuffer.snapshot(500)
        val screamScore = audioStub.screamClassifier.evaluateAudioSamples(snapshot)
        println("Faint scream score: $screamScore | Engine: ${audioStub.screamClassifier.lastInferenceStatus}")

        // Directly emit distress to verify coordinator alert transition
        audioStub.screamClassifier.checkAndEmitDistress(screamScore, customSource = "FaintScreamTest")
        // Emit second frame to meet temporal persistence filter
        audioStub.screamClassifier.checkAndEmitDistress(screamScore, customSource = "FaintScreamTest")
        kotlinx.coroutines.delay(200)

        // Allow coroutines to process
        val incident = coordinator.activeIncident.value
        assertNotNull("Emergency incident should be triggered on confirmed scream", incident)
        assertEquals(EmergencyLevel.CONFIRMED_EMERGENCY, incident?.level)
        assertEquals(DistressType.SCREAM, incident?.triggeringSignals?.firstOrNull()?.type)

        // 4. Test normal speech rejection (conversational speech at 300-800 Hz)
        val speechChunk = ShortArray(8000) { i ->
            (sin(2.0 * PI * 350.0 * i / 16000.0) * 12000).toInt().toShort()
        }
        val speechScore = audioStub.screamClassifier.evaluateAudioSamples(speechChunk)
        println("Speech score: $speechScore (Must be rejected)")
        assertTrue("Speech score ($speechScore) must be below detection threshold", speechScore < 0.25f)

        // 5. Test room silence rejection
        val silenceChunk = ShortArray(8000) { 0 }
        val silenceScore = audioStub.screamClassifier.evaluateAudioSamples(silenceChunk)
        println("Silence score: $silenceScore (Must be 0.0)")
        assertEquals(0.0f, silenceScore, 0.001f)

        // Cleanup
        monitorJob.cancel()
        audioStub.stopMonitoring()
        coordinator.stopProtection()
        assertFalse(audioStub.isMonitoring())
        assertFalse(audioStub.captureManager.isCapturing)
        assertFalse(EmergencyStateHolder.state.value.isProtectionActive)
    }

    @Test
    fun testRealAudioRecordingsEvaluation() = runBlocking {
        val audioStub = AudioModuleStub()
        val samplesDir = findSamplesDir()
        println("Using samples directory: ${samplesDir.absolutePath}")

        val screamFile = File(samplesDir, "pi_scream.wav")
        val speechFile = File(samplesDir, "speech_16000_hz_mono.wav")
        val cryFile = File(samplesDir, "crying_real.wav")

        assertTrue("pi_scream.wav should exist", screamFile.exists())
        assertTrue("speech_16000_hz_mono.wav should exist", speechFile.exists())
        assertTrue("crying_real.wav should exist", cryFile.exists())

        // 1. Evaluate Full Volume Real Scream
        val screamSamples = loadWavPcm(screamFile)
        assertTrue(screamSamples.isNotEmpty())
        val fullScreamScore = audioStub.screamClassifier.evaluateAudioSamples(screamSamples)
        println("Real Scream (Full) Score: $fullScreamScore | Engine: ${audioStub.screamClassifier.lastInferenceStatus}")
        assertTrue("Full scream score ($fullScreamScore) must be >= 0.30", fullScreamScore >= 0.30f)

        // 2. Evaluate Reduced Volume Scream (50% / -6dB)
        val halfScreamSamples = ShortArray(screamSamples.size) { i -> (screamSamples[i] * 0.5f).toInt().toShort() }
        val halfScreamScore = audioStub.screamClassifier.evaluateAudioSamples(halfScreamSamples)
        println("Real Scream (50% / -6dB) Score: $halfScreamScore")
        assertTrue("50% scream score ($halfScreamScore) must be >= 0.30", halfScreamScore >= 0.30f)

        // 3. Evaluate Faint Scream (10% / -20dB)
        val faintScreamSamples = ShortArray(screamSamples.size) { i -> (screamSamples[i] * 0.1f).toInt().toShort() }
        val faintScreamScore = audioStub.screamClassifier.evaluateAudioSamples(faintScreamSamples)
        println("Real Scream (10% / -20dB) Score: $faintScreamScore")
        assertTrue("Faint scream score ($faintScreamScore) must be >= 0.30", faintScreamScore >= 0.30f)

        // 4. Evaluate Distant Scream (3% / -30dB)
        val distantScreamSamples = ShortArray(screamSamples.size) { i -> (screamSamples[i] * 0.03f).toInt().toShort() }
        val distantScreamScore = audioStub.screamClassifier.evaluateAudioSamples(distantScreamSamples)
        println("Real Scream (3% / -30dB) Score: $distantScreamScore")
        assertTrue("Distant scream score ($distantScreamScore) must be >= 0.30", distantScreamScore >= 0.30f)

        // 5. Evaluate Real Speech (Normal Volume) - MUST BE REJECTED
        val speechSamples = loadWavPcm(speechFile)
        assertTrue(speechSamples.isNotEmpty())
        val speechScore = audioStub.screamClassifier.evaluateAudioSamples(speechSamples)
        println("Real Speech (Normal) Score: $speechScore (Must be < 0.25)")
        assertTrue("Normal speech score ($speechScore) must be rejected (< 0.25)", speechScore < 0.25f)

        // 6. Evaluate Real Speech (Loud 2x Volume) - MUST BE REJECTED
        val loudSpeechSamples = ShortArray(speechSamples.size) { i ->
            val v = (speechSamples[i] * 2.0f).toInt()
            v.coerceIn(-32768, 32767).toShort()
        }
        val loudSpeechScore = audioStub.screamClassifier.evaluateAudioSamples(loudSpeechSamples)
        println("Real Speech (Loud 2x) Score: $loudSpeechScore (Must be < 0.25)")
        assertTrue("Loud speech score ($loudSpeechScore) must be rejected (< 0.25)", loudSpeechScore < 0.25f)

        // 7. Evaluate Real Crying - Detected as human vocal distress without inter-class competition
        val crySamples = loadWavPcm(cryFile)
        assertTrue(crySamples.isNotEmpty())
        val cryScore = audioStub.screamClassifier.evaluateAudioSamples(crySamples)
        println("Real Crying Score: $cryScore (Detected as vocal distress)")
        assertTrue("Crying score ($cryScore) must trigger distress (>= 0.30)", cryScore >= 0.30f)
    }

    private fun findSamplesDir(): File {
        val candidates = listOf(
            File("../tests/live_audio_test/samples"),
            File("tests/live_audio_test/samples"),
            File("../../tests/live_audio_test/samples")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: File("tests/live_audio_test/samples")
    }

    private fun loadWavPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        val dataOffset = if (bytes.size > 44 && String(bytes.copyOfRange(0, 4)) == "RIFF") 44 else 0
        val pcmLength = bytes.size - dataOffset
        val shorts = ShortArray(pcmLength / 2)
        java.nio.ByteBuffer.wrap(bytes, dataOffset, pcmLength)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }
}
