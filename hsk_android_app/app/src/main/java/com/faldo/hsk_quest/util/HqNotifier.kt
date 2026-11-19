package com.faldo.hsk_quest.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.faldo.hsk_quest.R

/**
 * Compact, non-blocking top HUD notification pill that replaces oversized OEM system Toasts
 * (such as Xiaomi HyperOS/MIUI Toasts with large app icons) and bottom-covering Snackbars.
 * Touch events pass directly through the banner so gameplay and navigation are never blocked.
 */
object HqNotifier {

    private const val TAG_HUD_TOAST = "hq_compact_hud_toast"
    private const val DEFAULT_DURATION_MS = 1650L

    fun show(activity: Activity?, message: CharSequence?, durationMs: Long = DEFAULT_DURATION_MS) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val text = message?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        val density = act.resources.displayMetrics.density

        // Remove any previous active banner immediately so notifications never stack or linger
        root.findViewWithTag<View>(TAG_HUD_TOAST)?.let { existing ->
            existing.animate().cancel()
            root.removeView(existing)
        }

        val banner = TextView(act).apply {
            tag = TAG_HUD_TOAST
            this.text = text
            setBackgroundResource(R.drawable.bg_card_gold_border)
            setTextColor(ContextCompat.getColor(act, R.color.hq_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val padH = (14 * density).toInt()
            val padV = (7 * density).toInt()
            setPadding(padH, padV, padH, padV)
            maxWidth = (310 * density).toInt()
            elevation = 16 * density
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val statusBarTop = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            ?.top ?: (28 * density).toInt()

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = statusBarTop + (10 * density).toInt()
            marginStart = (20 * density).toInt()
            marginEnd = (20 * density).toInt()
        }

        banner.alpha = 0f
        banner.translationY = -10f * density
        root.addView(banner, lp)

        banner.animate()
            .alpha(0.96f)
            .translationY(0f)
            .setDuration(150L)
            .withEndAction {
                banner.postDelayed({
                    if (banner.parent === root) {
                        banner.animate()
                            .alpha(0f)
                            .translationY(-8f * density)
                            .setDuration(160L)
                            .withEndAction {
                                if (banner.parent === root) {
                                    root.removeView(banner)
                                }
                            }
                            .start()
                    }
                }, durationMs)
            }
            .start()
    }

    fun showFromContext(context: Context?, message: CharSequence?, durationMs: Long = DEFAULT_DURATION_MS) {
        val act = findActivity(context)
        if (act != null) {
            show(act, message, durationMs)
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var cur = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }
}

fun Fragment.showHqToast(message: CharSequence?, durationMs: Long = 1650L) {
    if (!isAdded) return
    HqNotifier.show(activity, message, durationMs)
}
