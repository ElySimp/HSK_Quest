package com.faldo.hsk_quest.util

import android.content.Context
import android.content.res.Configuration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Locks application font scaling (`fontScale = 1.0f`) and ensures compact-DPI OEM phones
 * (such as Xiaomi, Vivo, Oppo) receive at least the Infinix Note 40 virtual dp canvas
 * (`~408dp x 820dp`) without altering devices that already meet or exceed those dimensions.
 */
object DisplayScaleNormalizer {

    // Devices narrower than 396dp or shorter than 800dp are normalized to Infinix Note 40's canvas
    private const val MIN_PASS_THROUGH_WIDTH_DP = 396f
    private const val MIN_PASS_THROUGH_HEIGHT_DP = 800f
    private const val TARGET_MIN_WIDTH_DP = 408f
    private const val TARGET_MIN_HEIGHT_DP = 820f

    private fun computeNormalizedMetrics(
        widthPx: Int,
        heightPx: Int,
        currentDensity: Float,
    ): Pair<Float, Boolean> {
        val currentWidthDp = widthPx / currentDensity
        val currentHeightDp = heightPx / currentDensity

        return if (currentWidthDp < MIN_PASS_THROUGH_WIDTH_DP || currentHeightDp < MIN_PASS_THROUGH_HEIGHT_DP) {
            val target = min(widthPx / TARGET_MIN_WIDTH_DP, heightPx / TARGET_MIN_HEIGHT_DP)
                .coerceIn(1.5f, currentDensity)
            target to true
        } else {
            currentDensity to false
        }
    }

    fun wrapContext(base: Context): Context {
        val res = base.resources ?: return base
        val metrics = res.displayMetrics ?: return base
        val origConfig = res.configuration ?: return base

        val widthPx = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val heightPx = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val currentDensity = metrics.density.coerceAtLeast(1f)

        val (targetDensity, needsDensityScale) = computeNormalizedMetrics(widthPx, heightPx, currentDensity)
        val needsFontReset = origConfig.fontScale != 1.0f

        if (!needsDensityScale && !needsFontReset) {
            return base
        }

        val targetDensityDpi = (targetDensity * 160f).roundToInt().coerceAtLeast(160)
        val newConfig = Configuration(origConfig).apply {
            fontScale = 1.0f
            if (needsDensityScale) {
                densityDpi = targetDensityDpi
                val ratio = currentDensity / targetDensity
                screenWidthDp = if (origConfig.screenWidthDp > 0) {
                    (origConfig.screenWidthDp * ratio).roundToInt()
                } else {
                    (widthPx / targetDensity).roundToInt()
                }
                screenHeightDp = if (origConfig.screenHeightDp > 0) {
                    (origConfig.screenHeightDp * ratio).roundToInt()
                } else {
                    (heightPx / targetDensity).roundToInt()
                }
                smallestScreenWidthDp = if (origConfig.smallestScreenWidthDp > 0) {
                    (origConfig.smallestScreenWidthDp * ratio).roundToInt()
                } else {
                    (widthPx / targetDensity).roundToInt()
                }
            }
        }

        val wrapped = base.createConfigurationContext(newConfig)
        wrapped.resources?.displayMetrics?.let { wrappedMetrics ->
            if (needsDensityScale) {
                wrappedMetrics.density = targetDensity
                wrappedMetrics.densityDpi = targetDensityDpi
            }
            @Suppress("DEPRECATION")
            wrappedMetrics.scaledDensity = wrappedMetrics.density
        }
        return wrapped
    }

    /**
     * Ensures in-place metrics stay normalized after Activity configuration updates without
     * allocating an unused configuration context.
     */
    fun applyInPlace(context: Context) {
        val res = context.resources ?: return
        val metrics = res.displayMetrics ?: return
        val config = res.configuration ?: return

        val widthPx = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val heightPx = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val currentDensity = metrics.density.coerceAtLeast(1f)
        val (targetDensity, needsDensityScale) = computeNormalizedMetrics(widthPx, heightPx, currentDensity)

        if (needsDensityScale) {
            val targetDensityDpi = (targetDensity * 160f).roundToInt().coerceAtLeast(160)
            metrics.density = targetDensity
            metrics.densityDpi = targetDensityDpi
            config.densityDpi = targetDensityDpi
        }
        if (config.fontScale != 1.0f) {
            config.fontScale = 1.0f
        }
        @Suppress("DEPRECATION")
        if (metrics.scaledDensity != metrics.density) {
            metrics.scaledDensity = metrics.density
        }
    }
}
