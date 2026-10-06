package com.faldo.hsk_quest.ui.battle

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Lifecycle-safe audio recorder helper for pronunciation & speaking practice challenges.
 * Phase 2D input shell.
 */
class AudioRecorderHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isCurrentlyRecording: Boolean = false

    private var player: MediaPlayer? = null

    val isRecording: Boolean
        get() = isCurrentlyRecording

    /**
     * Starts recording audio input to the specified target file.
     * Returns true if recording started successfully, false otherwise.
     */
    fun startRecording(outputFile: File): Boolean {
        if (isCurrentlyRecording) return false

        return try {
            currentOutputFile = outputFile
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            isCurrentlyRecording = true
            true
        } catch (e: Exception) {
            releaseRecorder()
            false
        }
    }

    /**
     * Stops the active audio recording session and returns the recorded file.
     */
    fun stopRecording(): File? {
        if (!isCurrentlyRecording) return null

        val file = currentOutputFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // In case of immediate stop before buffer filled
        } finally {
            releaseRecorder()
        }
        return file
    }

    /**
     * Plays back a recorded audio file.
     */
    fun playAudio(file: File, onCompletion: () -> Unit): Boolean {
        stopPlayback()
        if (!file.exists() || file.length() == 0L) return false

        return try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                stopPlayback()
                onCompletion()
            }
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            stopPlayback()
            false
        }
    }

    fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        } finally {
            player = null
        }
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        } finally {
            recorder = null
            isCurrentlyRecording = false
        }
    }

    fun releaseAll() {
        releaseRecorder()
        stopPlayback()
    }
}
