package com.faldo.hsk_quest.ui.battle

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.faldo.hsk_quest.engine.MandarinAudioFeatureExtractor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * In-memory 16 kHz mono PCM16 audio capture helper for ToneNet inference.
 * Buffers up to 3.4s of raw samples so speaker onset delays still fit within
 * the model's 2.4s window after silence trimming, and supports both manual
 * tap-to-stop and automatic post-speech end-of-utterance detection.
 */
class Pcm16kAudioRecorder(
    private val maxDurationMs: Int = DEFAULT_MAX_DURATION_MS,
) {

    interface Listener {
        fun onRecordingStarted()
        fun onAmplitude(rmsNormalized: Float)
        fun onRecordingCompleted(samples: FloatArray)
        fun onRecordingError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRecordingFlag = AtomicBoolean(false)
    private val isCancelledFlag = AtomicBoolean(false)

    @Volatile
    private var captureThread: Thread? = null

    val isRecording: Boolean
        get() = isRecordingFlag.get()

    fun start(listener: Listener) {
        if (!isRecordingFlag.compareAndSet(false, true)) return
        isCancelledFlag.set(false)

        val thread = Thread({
            runCaptureLoop(listener)
        }, "ToneNetPcm16kRecorder")
        captureThread = thread
        thread.start()
    }

    fun stop() {
        isRecordingFlag.set(false)
    }

    fun cancel() {
        isCancelledFlag.set(true)
        isRecordingFlag.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(listener: Listener) {
        val sampleRate = MandarinAudioFeatureExtractor.SAMPLE_RATE
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufBytes <= 0) {
            isRecordingFlag.set(false)
            postMain { listener.onRecordingError("Microphone 16kHz PCM unsupported on this device.") }
            return
        }

        val bufferBytes = maxOf(minBufBytes * 2, READ_CHUNK_SAMPLES * 2 * 2)
        val record = createAudioRecord(sampleRate, bufferBytes)
        if (record == null) {
            isRecordingFlag.set(false)
            postMain { listener.onRecordingError("Could not initialize microphone for tone grading.") }
            return
        }

        val maxSamples = (sampleRate * (maxDurationMs / 1000.0)).toInt()
        val captured = FloatArray(maxSamples)
        var capturedLen = 0
        val shortBuf = ShortArray(READ_CHUNK_SAMPLES)

        try {
            record.startRecording()
            postMain { listener.onRecordingStarted() }

            var speechDetected = false
            var speechStartSample = 0
            var trailingSilenceSamples = 0
            val minSpeechTailSamples = (sampleRate * 0.55).toInt()
            val minUtteranceAfterOnset = (sampleRate * 0.65).toInt()

            while (isRecordingFlag.get() && !isCancelledFlag.get() && capturedLen < maxSamples) {
                val readCount = record.read(shortBuf, 0, minOf(shortBuf.size, maxSamples - capturedLen))
                if (readCount < 0) break
                if (readCount == 0) continue

                var sumSq = 0.0
                for (i in 0 until readCount) {
                    val norm = shortBuf[i] / 32768f
                    captured[capturedLen + i] = norm
                    sumSq += norm * norm
                }
                val chunkRms = sqrt(sumSq / readCount).toFloat()
                capturedLen += readCount

                postMain {
                    if (!isCancelledFlag.get()) {
                        listener.onAmplitude((chunkRms * 5f).coerceIn(0f, 1f))
                    }
                }

                if (chunkRms >= SPEECH_ONSET_RMS) {
                    if (!speechDetected) {
                        speechDetected = true
                        speechStartSample = capturedLen - readCount
                    }
                    trailingSilenceSamples = 0
                } else if (speechDetected) {
                    trailingSilenceSamples += readCount
                    val spokenDuration = capturedLen - speechStartSample
                    if (spokenDuration >= minUtteranceAfterOnset &&
                        trailingSilenceSamples >= minSpeechTailSamples
                    ) {
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            isRecordingFlag.set(false)
            runCatching { record.stop() }
            runCatching { record.release() }
            if (!isCancelledFlag.get()) {
                postMain { listener.onRecordingError("Microphone read error: ${t.message ?: "unknown"}") }
            }
            return
        }

        isRecordingFlag.set(false)
        runCatching { record.stop() }
        runCatching { record.release() }

        if (isCancelledFlag.get()) return

        val output = captured.copyOf(capturedLen)
        postMain {
            if (!isCancelledFlag.get()) {
                listener.onRecordingCompleted(output)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            try {
                val candidate = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate
                }
                candidate.release()
            } catch (_: SecurityException) {
                return null
            } catch (_: Throwable) {
                // Try fallback AudioSource
            }
        }
        return null
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val READ_CHUNK_SAMPLES = 320 // 20ms at 16 kHz
        private const val SPEECH_ONSET_RMS = 0.015f
        const val DEFAULT_MAX_DURATION_MS = 3_400
    }
}
