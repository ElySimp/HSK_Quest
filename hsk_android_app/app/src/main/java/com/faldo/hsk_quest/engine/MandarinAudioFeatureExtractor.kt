package com.faldo.hsk_quest.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ToneNetFeatures(
    val tensorData: FloatArray,
    val nFrames: Int,
    val nSubFrames: Int,
)

/**
 * Pure-Kotlin DSP feature extractor matching Cell 3 of kaggle_mandarin_tone_classifier.ipynb:
 * produces a [1, 240, 88] float32 feature tensor consisting of 80 active-frame MVN
 * HTK Log-Mel channels and 8 YIN-CMNDF F0/energy/spectral-tilt prosody channels.
 */
object MandarinAudioFeatureExtractor {

    const val SAMPLE_RATE = 16_000
    const val FRAME_LEN = 400
    const val FRAME_HOP = 160
    const val FFT_LEN = 512
    const val N_FFT_BINS = FFT_LEN / 2 + 1 // 257
    const val N_MELS = 80
    const val N_PROSODY = 8
    const val N_FEATURES = N_MELS + N_PROSODY // 88
    const val FMIN_HZ = 20.0
    const val FMAX_HZ = 7600.0
    const val MAX_FRAMES = 240
    const val SUBSAMPLE_FACTOR = 2
    const val SUBSAMPLED_FRAMES = MAX_FRAMES / SUBSAMPLE_FACTOR // 120
    const val MAX_SAMPLES = (MAX_FRAMES - 1) * FRAME_HOP + FRAME_LEN // 38,640 -> capped at 38,400 (2.4s)
    const val CLIP_MAX_SAMPLES = 38_400

    const val PITCH_MIN_LAG = 35
    const val PITCH_MAX_LAG = 213
    const val YIN_WIN = FRAME_LEN - PITCH_MAX_LAG // 187

    private val hannWindow: DoubleArray
    private val melFilterBank: Array<DoubleArray> // [257][80]
    private val lowBandIndices: IntArray
    private val midBandIndices: IntArray
    private val fftCosTable: DoubleArray
    private val fftSinTable: DoubleArray
    private val bitReverseTable: IntArray

    init {
        hannWindow = DoubleArray(FRAME_LEN) { n ->
            0.5 - 0.5 * cos(2.0 * PI * n / (FRAME_LEN - 1))
        }

        val fftFreqs = DoubleArray(N_FFT_BINS) { k ->
            k * (SAMPLE_RATE / 2.0) / (FFT_LEN / 2.0)
        }

        val lowList = mutableListOf<Int>()
        val midList = mutableListOf<Int>()
        for (k in 0 until N_FFT_BINS) {
            val f = fftFreqs[k]
            if (f >= 60.0 && f < 350.0) lowList += k
            if (f >= 350.0 && f <= 2200.0) midList += k
        }
        lowBandIndices = lowList.toIntArray()
        midBandIndices = midList.toIntArray()

        val minMel = hzToMelHtk(FMIN_HZ)
        val maxMel = hzToMelHtk(FMAX_HZ)
        val hzPts = DoubleArray(N_MELS + 2) { i ->
            val m = minMel + (maxMel - minMel) * i / (N_MELS + 1)
            melToHzHtk(m)
        }
        melFilterBank = Array(N_FFT_BINS) { DoubleArray(N_MELS) }
        for (k in 0 until N_FFT_BINS) {
            val f = fftFreqs[k]
            for (m in 0 until N_MELS) {
                val fL = hzPts[m]
                val fC = hzPts[m + 1]
                val fR = hzPts[m + 2]
                val up = (f - fL) / max(1e-6, fC - fL)
                val down = (fR - f) / max(1e-6, fR - fC)
                melFilterBank[k][m] = max(0.0, min(up, down))
            }
        }

        val halfFft = FFT_LEN / 2
        fftCosTable = DoubleArray(halfFft) { k -> cos(-2.0 * PI * k / FFT_LEN) }
        fftSinTable = DoubleArray(halfFft) { k -> sin(-2.0 * PI * k / FFT_LEN) }
        val bits = Integer.numberOfTrailingZeros(FFT_LEN)
        bitReverseTable = IntArray(FFT_LEN) { i ->
            var rev = 0
            var x = i
            repeat(bits) {
                rev = (rev shl 1) or (x and 1)
                x = x shr 1
            }
            rev
        }
    }

    /**
     * Checks whether a recorded clip contains actual speech rather than ambient noise.
     */
    fun hasAudibleSpeech(samples: FloatArray): Boolean {
        if (samples.size < FRAME_LEN) return false
        var maxAbs = 0f
        var maxFrameRms = 0f
        var offset = 0
        while (offset + FRAME_LEN <= samples.size) {
            var sumSq = 0.0
            for (i in 0 until FRAME_LEN) {
                val s = samples[offset + i]
                val a = abs(s)
                if (a > maxAbs) maxAbs = a
                sumSq += s * s
            }
            val rms = sqrt(sumSq / FRAME_LEN).toFloat()
            if (rms > maxFrameRms) maxFrameRms = rms
            offset += FRAME_HOP
        }
        return maxAbs >= 0.015f && maxFrameRms >= 0.005f
    }

    /**
     * Trims excessive leading/trailing silence while retaining small acoustic margins,
     * keeping the active utterance inside the 2.4s (38,400-sample) model window.
     */
    fun trimToMaxWindow(samples: FloatArray, maxSamples: Int = CLIP_MAX_SAMPLES): FloatArray {
        if (samples.isEmpty()) return FloatArray(FRAME_LEN)
        val winLen = 160 // 10ms RMS frames
        val nBins = samples.size / winLen
        if (nBins == 0) return samples.copyOf(min(samples.size, maxSamples))

        val rms = FloatArray(nBins)
        var peakRms = 0f
        for (b in 0 until nBins) {
            var sumSq = 0.0
            val base = b * winLen
            for (i in 0 until winLen) {
                val s = samples[base + i]
                sumSq += s * s
            }
            val r = sqrt(sumSq / winLen).toFloat()
            rms[b] = r
            if (r > peakRms) peakRms = r
        }

        if (peakRms < 1e-4f) {
            return samples.copyOf(min(samples.size, maxSamples))
        }

        val threshold = max(peakRms * 0.08f, 0.004f)
        var firstActive = 0
        while (firstActive < nBins && rms[firstActive] < threshold) firstActive++
        var lastActive = nBins - 1
        while (lastActive > firstActive && rms[lastActive] < threshold) lastActive--

        val startBin = max(0, firstActive - 15) // 150ms lead-in
        val endBin = min(nBins, lastActive + 21) // 200ms tail
        val startSample = startBin * winLen
        val endSample = min(samples.size, endBin * winLen)

        if (endSample - startSample <= maxSamples) {
            return if (samples.size <= maxSamples) {
                samples
            } else {
                samples.copyOfRange(startSample, endSample)
            }
        }

        // If active span still exceeds 2.4s, select highest-energy contiguous 2.4s window.
        val maxBins = maxSamples / winLen
        var windowSum = 0.0
        for (b in startBin until min(nBins, startBin + maxBins)) {
            windowSum += rms[b] * rms[b]
        }
        var bestStart = startBin
        var bestSum = windowSum
        for (b in startBin + 1..(nBins - maxBins)) {
            windowSum -= rms[b - 1] * rms[b - 1]
            windowSum += rms[b + maxBins - 1] * rms[b + maxBins - 1]
            if (windowSum > bestSum) {
                bestSum = windowSum
                bestStart = b
            }
        }
        val clippedStart = bestStart * winLen
        val clippedEnd = min(samples.size, clippedStart + maxSamples)
        return samples.copyOfRange(clippedStart, clippedEnd)
    }

    /**
     * Extracts [240 * 88] flattened input tensor features from 16 kHz mono audio in [-1, 1].
     */
    fun extractFeatures(wavInput: FloatArray): ToneNetFeatures {
        val rawLen = min(wavInput.size, CLIP_MAX_SAMPLES)
        val paddedLen = max(FRAME_LEN, rawLen)
        val wav = DoubleArray(paddedLen)
        var sum = 0.0
        for (i in 0 until rawLen) {
            val v = wavInput[i].toDouble()
            wav[i] = v
            sum += v
        }
        val clipMean = sum / paddedLen
        for (i in 0 until paddedLen) {
            wav[i] -= clipMean
        }

        val nFrames = min(MAX_FRAMES, max(1, (paddedLen - FRAME_LEN) / FRAME_HOP + 1))

        val fftRe = DoubleArray(FFT_LEN)
        val fftIm = DoubleArray(FFT_LEN)
        val powSpec = DoubleArray(N_FFT_BINS)

        val logMel = Array(nFrames) { DoubleArray(N_MELS) }
        val frameEMel = DoubleArray(nFrames)
        val rawTilt = DoubleArray(nFrames)
        val logE = DoubleArray(nFrames)
        val f0Hz = DoubleArray(nFrames)
        val voicingProb = DoubleArray(nFrames)

        val centered = DoubleArray(FRAME_LEN)
        val yinDiff = DoubleArray(PITCH_MAX_LAG + 1)
        val yinCmndf = DoubleArray(PITCH_MAX_LAG + 1)

        for (t in 0 until nFrames) {
            val base = t * FRAME_HOP

            // 1) Windowed 512-pt RFFT power spectrum for Log-Mel + low/mid spectral tilt
            var fSum = 0.0
            for (i in 0 until FRAME_LEN) {
                val sample = wav[base + i]
                fSum += sample
                fftRe[i] = sample * hannWindow[i]
                fftIm[i] = 0.0
            }
            for (i in FRAME_LEN until FFT_LEN) {
                fftRe[i] = 0.0
                fftIm[i] = 0.0
            }

            computeFftInPlace(fftRe, fftIm)
            for (k in 0 until N_FFT_BINS) {
                val re = fftRe[k]
                val im = fftIm[k]
                powSpec[k] = re * re + im * im
            }

            var melSumForFrame = 0.0
            for (m in 0 until N_MELS) {
                var acc = 0.0
                for (k in 0 until N_FFT_BINS) {
                    acc += powSpec[k] * melFilterBank[k][m]
                }
                val lm = ln(max(acc, 1e-6))
                logMel[t][m] = lm
                melSumForFrame += lm
            }
            frameEMel[t] = melSumForFrame / N_MELS

            var lowBandE = 0.0
            for (k in lowBandIndices) lowBandE += powSpec[k]
            var midBandE = 0.0
            for (k in midBandIndices) midBandE += powSpec[k]
            rawTilt[t] = ln(max(lowBandE, 1e-6)) - ln(max(midBandE, 1e-6))

            // 2) Frame-centered RMS energy + YIN CMNDF F0
            val fMean = fSum / FRAME_LEN
            var r0 = 1e-8
            for (i in 0 until FRAME_LEN) {
                val c = wav[base + i] - fMean
                centered[i] = c
                r0 += c * c
            }
            val rms = sqrt(r0 / FRAME_LEN)
            logE[t] = ln(max(rms, 1e-6))

            var e0 = 0.0
            for (j in 0 until YIN_WIN) {
                val cj = centered[j]
                e0 += cj * cj
            }

            if (e0 <= 1e-8) {
                f0Hz[t] = SAMPLE_RATE.toDouble() / PITCH_MIN_LAG
                voicingProb[t] = 0.0
            } else {
                var runningSum = 0.0
                yinCmndf[0] = 1.0
                for (tau in 1..PITCH_MAX_LAG) {
                    var d = 0.0
                    for (j in 0 until YIN_WIN) {
                        val delta = centered[j] - centered[j + tau]
                        d += delta * delta
                    }
                    yinDiff[tau] = d
                    runningSum += d
                    yinCmndf[tau] = d * tau / max(runningSum, 1e-12)
                }

                var bestTau = -1
                for (tau in PITCH_MIN_LAG..PITCH_MAX_LAG) {
                    if (yinCmndf[tau] < 0.22) {
                        bestTau = tau
                        break
                    }
                }
                if (bestTau != -1) {
                    while (bestTau + 1 <= PITCH_MAX_LAG && yinCmndf[bestTau + 1] <= yinCmndf[bestTau]) {
                        bestTau++
                    }
                } else {
                    bestTau = PITCH_MIN_LAG
                    var bestVal = yinCmndf[PITCH_MIN_LAG]
                    for (tau in PITCH_MIN_LAG + 1..PITCH_MAX_LAG) {
                        if (yinCmndf[tau] < bestVal) {
                            bestVal = yinCmndf[tau]
                            bestTau = tau
                        }
                    }
                }

                val cmndfMin = yinCmndf[bestTau]
                var refinedLag = bestTau.toDouble()
                if (bestTau in (PITCH_MIN_LAG + 1) until PITCH_MAX_LAG) {
                    val yL = yinCmndf[bestTau - 1]
                    val yC = yinCmndf[bestTau]
                    val yR = yinCmndf[bestTau + 1]
                    val denom = yL - 2.0 * yC + yR
                    if (abs(denom) > 1e-6) {
                        refinedLag += clamp((yL - yR) / (2.0 * denom), -0.5, 0.5)
                    }
                }
                f0Hz[t] = SAMPLE_RATE.toDouble() / max(refinedLag, 20.0)
                voicingProb[t] = clamp(1.0 - cmndfMin, 0.0, 1.0)
            }
        }

        // 3) Global active-frame MVN on 80 Log-Mel channels
        val maxFrameE = frameEMel.maxOrNull() ?: 0.0
        var activeFrameCount = 0
        for (t in 0 until nFrames) {
            if (frameEMel[t] >= maxFrameE - 6.0) activeFrameCount++
        }
        val useActiveOnly = activeFrameCount >= 3
        var melSum = 0.0
        var melItems = 0
        for (t in 0 until nFrames) {
            if (!useActiveOnly || frameEMel[t] >= maxFrameE - 6.0) {
                for (m in 0 until N_MELS) {
                    melSum += logMel[t][m]
                }
                melItems += N_MELS
            }
        }
        val melMean = if (melItems > 0) melSum / melItems else 0.0
        var melVarSum = 0.0
        for (t in 0 until nFrames) {
            if (!useActiveOnly || frameEMel[t] >= maxFrameE - 6.0) {
                for (m in 0 until N_MELS) {
                    val diff = logMel[t][m] - melMean
                    melVarSum += diff * diff
                }
            }
        }
        val melStd = max(if (melItems > 0) sqrt(melVarSum / melItems) else 0.35, 0.35)

        // 4) Spectral tilt & log energy normalization + delta energy
        val (tiltMean, rawTiltStd) = meanAndStd(rawTilt)
        val tiltStd = max(rawTiltStd, 0.35)
        val (eMean, rawEStd) = meanAndStd(logE)
        val eStd = max(rawEStd, 0.25)

        val normLogE = DoubleArray(nFrames) { t ->
            clamp((logE[t] - eMean) / eStd, -3.5, 3.5)
        }
        val deltaE = if (nFrames >= 2) gradient1D(normLogE) else DoubleArray(nFrames)
        val lowMidTilt = DoubleArray(nFrames) { t ->
            clamp((rawTilt[t] - tiltMean) / tiltStd, -3.5, 3.5)
        }

        // 5) Voiced mask, 3-pt median F0 smoothing, log2 F0 interp & deltas
        val maxLogE = logE.maxOrNull() ?: -1e6
        val voicedMask = BooleanArray(nFrames) { t ->
            voicingProb[t] >= 0.52 && logE[t] >= maxLogE - 4.2
        }
        val f0Smooth = f0Hz.copyOf()
        for (t in 1 until nFrames - 1) {
            if (voicedMask[t - 1] && voicedMask[t] && voicedMask[t + 1]) {
                f0Smooth[t] = median3(f0Hz[t - 1], f0Hz[t], f0Hz[t + 1])
            }
        }

        val voicedIndices = mutableListOf<Int>()
        for (t in 0 until nFrames) {
            if (voicedMask[t]) voicedIndices += t
        }

        val normF0 = DoubleArray(nFrames)
        val globalF0 = DoubleArray(nFrames)
        val deltaF0 = DoubleArray(nFrames)
        val delta2F0 = DoubleArray(nFrames)

        if (voicedIndices.size >= 2) {
            val vLogF0 = DoubleArray(voicedIndices.size) { i ->
                log2(clamp(f0Smooth[voicedIndices[i]], 60.0, 500.0))
            }
            val (vMean, rawVStd) = meanAndStd(vLogF0)
            val vStd = max(rawVStd, 0.18)
            val vNorm = DoubleArray(vLogF0.size) { i ->
                clamp((vLogF0[i] - vMean) / vStd, -3.5, 3.5)
            }
            val logRef170 = log2(170.0)
            val vGlobal = DoubleArray(vLogF0.size) { i ->
                clamp((vLogF0[i] - logRef170) / 0.50, -3.0, 3.0)
            }

            linearInterp(nFrames, voicedIndices, vNorm, normF0)
            linearInterp(nFrames, voicedIndices, vGlobal, globalF0)

            val keepMask = BooleanArray(nFrames)
            for (vi in voicedIndices) {
                val lo = max(0, vi - 4)
                val hi = min(nFrames - 1, vi + 4)
                for (k in lo..hi) keepMask[k] = true
            }
            for (t in 0 until nFrames) {
                if (!keepMask[t]) {
                    normF0[t] = 0.0
                    globalF0[t] = 0.0
                }
            }

            if (nFrames >= 2) {
                val rawDelta = gradient1D(normF0)
                for (t in 0 until nFrames) {
                    deltaF0[t] = clamp(rawDelta[t], -2.5, 2.5)
                }
            }
            if (nFrames >= 3) {
                val rawDelta2 = gradient1D(deltaF0)
                for (t in 0 until nFrames) {
                    delta2F0[t] = clamp(rawDelta2[t], -2.5, 2.5)
                }
            }
        }

        // 6) Pack [MAX_FRAMES, 88] row-major float32 tensor
        val tensorData = FloatArray(MAX_FRAMES * N_FEATURES)
        for (t in 0 until nFrames) {
            val rowOffset = t * N_FEATURES
            for (m in 0 until N_MELS) {
                tensorData[rowOffset + m] = clamp((logMel[t][m] - melMean) / melStd, -6.0, 6.0).toFloat()
            }
            tensorData[rowOffset + 80] = normF0[t].toFloat()
            tensorData[rowOffset + 81] = globalF0[t].toFloat()
            tensorData[rowOffset + 82] = deltaF0[t].toFloat()
            tensorData[rowOffset + 83] = delta2F0[t].toFloat()
            tensorData[rowOffset + 84] = voicingProb[t].toFloat()
            tensorData[rowOffset + 85] = normLogE[t].toFloat()
            tensorData[rowOffset + 86] = deltaE[t].toFloat()
            tensorData[rowOffset + 87] = lowMidTilt[t].toFloat()
        }

        val nSubFrames = min(SUBSAMPLED_FRAMES, max(1, ceil(nFrames.toDouble() / SUBSAMPLE_FACTOR).toInt()))
        return ToneNetFeatures(tensorData, nFrames, nSubFrames)
    }

    private fun computeFftInPlace(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until FFT_LEN) {
            val j = bitReverseTable[i]
            if (i < j) {
                val tmpR = re[i]
                val tmpI = im[i]
                re[i] = re[j]
                im[i] = im[j]
                re[j] = tmpR
                im[j] = tmpI
            }
        }
        var len = 2
        while (len <= FFT_LEN) {
            val halfLen = len shr 1
            val step = FFT_LEN / len
            var i = 0
            while (i < FFT_LEN) {
                var tableIdx = 0
                for (j in 0 until halfLen) {
                    val idx1 = i + j
                    val idx2 = idx1 + halfLen
                    val wr = fftCosTable[tableIdx]
                    val wi = fftSinTable[tableIdx]
                    val tr = wr * re[idx2] - wi * im[idx2]
                    val ti = wr * im[idx2] + wi * re[idx2]
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    tableIdx += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun meanAndStd(values: DoubleArray): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        var varSum = 0.0
        for (v in values) {
            val d = v - mean
            varSum += d * d
        }
        return mean to sqrt(varSum / values.size)
    }

    private fun gradient1D(values: DoubleArray): DoubleArray {
        val n = values.size
        val out = DoubleArray(n)
        if (n < 2) return out
        out[0] = values[1] - values[0]
        for (i in 1 until n - 1) {
            out[i] = 0.5 * (values[i + 1] - values[i - 1])
        }
        out[n - 1] = values[n - 1] - values[n - 2]
        return out
    }

    private fun linearInterp(
        nFrames: Int,
        xs: List<Int>,
        ys: DoubleArray,
        out: DoubleArray,
    ) {
        val lastIdx = xs.size - 1
        var seg = 0
        for (t in 0 until nFrames) {
            when {
                t <= xs[0] -> out[t] = ys[0]
                t >= xs[lastIdx] -> out[t] = ys[lastIdx]
                else -> {
                    while (seg + 1 < xs.size && xs[seg + 1] < t) seg++
                    val x0 = xs[seg]
                    val x1 = xs[seg + 1]
                    val alpha = (t - x0).toDouble() / (x1 - x0)
                    out[t] = ys[seg] + alpha * (ys[seg + 1] - ys[seg])
                }
            }
        }
    }

    private fun median3(a: Double, b: Double, c: Double): Double {
        return max(min(a, b), min(max(a, b), c))
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double {
        return when {
            v < lo -> lo
            v > hi -> hi
            else -> v
        }
    }

    private fun hzToMelHtk(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHzHtk(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
