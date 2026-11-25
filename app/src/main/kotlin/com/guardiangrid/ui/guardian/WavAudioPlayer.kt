package com.guardiangrid.ui.guardian

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Lightweight Android WAV audio player for Guardian responders listening to
 * received 10s pre-trigger, live 2s chunks, or 20s complete incident recordings.
 */
class WavAudioPlayer {
    private val _playingClipId = MutableStateFlow<String?>(null)
    val playingClipId: StateFlow<String?> = _playingClipId.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var player: MediaPlayer? = null

    fun togglePlay(clipId: String, filePath: String?) {
        if (_playingClipId.value == clipId) {
            stop()
            return
        }
        stop()

        if (filePath.isNullOrBlank()) {
            _playbackError.value = "Audio file not downloaded yet."
            return
        }
        val file = File(filePath)
        if (!file.exists() || file.length() <= 44L) {
            _playbackError.value = "Audio clip missing or empty on disk."
            return
        }

        try {
            _playbackError.value = null
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _playingClipId.value = null
                    releasePlayerOnly()
                }
                setOnErrorListener { _, what, _ ->
                    _playbackError.value = "Playback error ($what)"
                    _playingClipId.value = null
                    true
                }
                prepare()
                start()
            }
            player = mp
            _playingClipId.value = clipId
        } catch (e: Exception) {
            _playbackError.value = e.message ?: "Unable to play WAV audio"
            stop()
        }
    }

    fun stop() {
        _playingClipId.value = null
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
