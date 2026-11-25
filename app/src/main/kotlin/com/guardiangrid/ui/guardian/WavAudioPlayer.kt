package com.guardiangrid.ui.guardian

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Lightweight Android WAV audio player supporting:
 * - Guardian responder playback of received WAV chunks ([togglePlay])
 * - Incident evidence playback with Play/Pause, Seek, and Duration tracking ([playOrPause], [seekTo])
 */
class WavAudioPlayer {
    private val _playingClipId = MutableStateFlow<String?>(null)
    val playingClipId: StateFlow<String?> = _playingClipId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var player: MediaPlayer? = null
    private var loadedFilePath: String? = null

    fun togglePlay(clipId: String, filePath: String?) {
        if (_playingClipId.value == clipId) {
            stop()
            return
        }
        startFresh(clipId, filePath)
    }

    fun playOrPause(clipId: String, filePath: String?) {
        val mp = player
        if (mp != null && _playingClipId.value == clipId && loadedFilePath == filePath) {
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    _isPlaying.value = false
                    _currentPositionMs.value = mp.currentPosition.toLong().coerceAtLeast(0L)
                } else {
                    mp.start()
                    _isPlaying.value = true
                }
                return
            } catch (_: Exception) {
                // Fall through to fresh initialization
            }
        }
        startFresh(clipId, filePath)
    }

    fun seekTo(positionMs: Long) {
        val mp = player ?: return
        try {
            val dur = mp.duration.toLong().coerceAtLeast(0L)
            val clamped = positionMs.coerceIn(0L, dur).toInt()
            mp.seekTo(clamped)
            _currentPositionMs.value = clamped.toLong()
        } catch (_: Exception) {
        }
    }

    fun refreshProgress() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                _currentPositionMs.value = mp.currentPosition.toLong().coerceAtLeast(0L)
                val dur = mp.duration.toLong().coerceAtLeast(0L)
                if (dur > 0L) _durationMs.value = dur
            }
        } catch (_: Exception) {
        }
    }

    private fun startFresh(clipId: String, filePath: String?) {
        stop()

        if (filePath.isNullOrBlank()) {
            _playbackError.value = "Audio evidence file not found."
            return
        }
        val file = File(filePath)
        if (!file.exists()) {
            _playbackError.value = "Audio file missing on device."
            return
        }
        if (file.extension.equals("enc", ignoreCase = true)) {
            _playbackError.value = "Encrypted audio must be decrypted before playback."
            return
        }
        if (file.length() <= 44L) {
            _playbackError.value = "Audio clip is empty or corrupted."
            return
        }

        try {
            _playbackError.value = null
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    _playingClipId.value = null
                    loadedFilePath = null
                    releasePlayerOnly()
                }
                setOnErrorListener { _, what, _ ->
                    _playbackError.value = "Playback error ($what)"
                    _isPlaying.value = false
                    _playingClipId.value = null
                    loadedFilePath = null
                    true
                }
                prepare()
                _durationMs.value = duration.toLong().coerceAtLeast(0L)
                _currentPositionMs.value = 0L
                start()
            }
            player = mp
            loadedFilePath = file.absolutePath
            _playingClipId.value = clipId
            _isPlaying.value = true
        } catch (e: Exception) {
            _playbackError.value = e.message ?: "Unable to play WAV audio"
            stop()
        }
    }

    fun stop() {
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        _playingClipId.value = null
        loadedFilePath = null
        releasePlayerOnly()
    }

    private fun releasePlayerOnly() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }
}
