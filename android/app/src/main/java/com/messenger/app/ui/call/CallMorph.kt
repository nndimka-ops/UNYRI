package com.messenger.app.ui.call

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

object CallMorphPolicy {
    enum class Style { SPRING, POWER_SAVER, FADE, PLAIN }
    fun style(reduced: Boolean, powerSaver: Boolean, talkBack: Boolean, foreground: Boolean,
        busy: Boolean, systemEntry: Boolean = false): Style = when {
        talkBack || !foreground || busy || systemEntry -> Style.PLAIN
        reduced -> Style.FADE
        powerSaver -> Style.POWER_SAVER
        else -> Style.SPRING
    }
    fun duration(style: Style, expanding: Boolean): Long = when (style) {
        Style.SPRING -> if (expanding) 400 else 440
        Style.POWER_SAVER -> if (expanding) 280 else 300
        Style.FADE -> if (expanding) 180 else 200
        Style.PLAIN -> 0
    }
}

/** One transient native overlay. No bitmap, frame clock or transform remains at rest. */
internal class CallMorph private constructor(root: ViewGroup, window: Window?) {
    private val rootRef = WeakReference(root)
    private val windowRef = WeakReference(window)
    private var busy by mutableStateOf(false)
    val isMorphing: Boolean get() = busy
    var barFrame: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private fun style(systemEntry: Boolean = false): CallMorphPolicy.Style {
        val root = rootRef.get() ?: return CallMorphPolicy.Style.PLAIN
        return CallMorphPolicy.style(!ValueAnimator.areAnimatorsEnabled(),
            root.context.getSystemService(PowerManager::class.java).isPowerSaveMode,
            root.context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled,
            root.isShown && root.hasWindowFocus(), busy, systemEntry)
    }
    private fun target(root: ViewGroup): Rect {
        val frame = barFrame
        if (frame != null && frame.width() > 0 && frame.height() > 0 && frame.top >= 0 && frame.bottom <= root.height) return Rect(frame)
        val d = root.resources.displayMetrics.density
        val top = root.rootWindowInsets?.systemWindowInsetTop ?: 0
        return Rect((10*d).roundToInt(), top+(4*d).roundToInt(), root.width-(10*d).roundToInt(), top+(52*d).roundToInt())
    }
    fun collapse(valid: () -> Boolean, dismiss: () -> Unit) {
        val style = style()
        val root = rootRef.get(); val window = windowRef.get()
        if (root == null || window == null || style == CallMorphPolicy.Style.PLAIN || root.width == 0 || root.height == 0) { if (valid()) dismiss(); return }
        busy = true
        val scale = minOf(1f, 1024f / root.width)
        val bitmap = Bitmap.createBitmap((root.width * scale).roundToInt().coerceAtLeast(1),
            (root.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        var completed = false
        val fallback = Runnable { if (!completed) { completed = true; busy = false; if (valid()) dismiss() } }
        handler.postDelayed(fallback, 150)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (completed) { bitmap.recycle(); return@request }
                completed = true; handler.removeCallbacks(fallback)
                if (result != PixelCopy.SUCCESS || !valid()) {
                    bitmap.recycle(); busy = false; if (valid()) dismiss(); return@request
                }
                val image = ImageView(root.context).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP }
                val overlay = container(root).apply { addView(image, FrameLayout.LayoutParams(-1,-1)) }
                root.addView(overlay, ViewGroup.LayoutParams(-1,-1))
                dismiss()
                root.post { animate(root, overlay, Rect(0,0,root.width,root.height), target(root), style, false) {
                    image.setImageDrawable(null); bitmap.recycle()
                } }
            }, handler)
        } catch (_: IllegalArgumentException) { handler.removeCallbacks(fallback); completed = true; bitmap.recycle(); busy = false; if (valid()) dismiss() }
    }
    fun expand(from: Rect? = null, systemEntry: Boolean = false, valid: () -> Boolean = { true }, present: () -> Unit) {
        val style = style(systemEntry)
        val root = rootRef.get()
        if (root == null || style == CallMorphPolicy.Style.PLAIN) { if (valid()) present(); return }
        busy = true
        val overlay = container(root).apply { setBackgroundColor(0xFF101010.toInt()) }
        root.addView(overlay)
        animate(root, overlay, from ?: target(root), Rect(0,0,root.width,root.height), style, true) {
            if (valid()) present()
        }
    }
    private fun container(root: ViewGroup) = FrameLayout(root.context).apply {
        isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipToOutline = true
        background = GradientDrawable().apply { setColor(0xFF101010.toInt()); cornerRadius = 24 * resources.displayMetrics.density }
        elevation = 1000 * resources.displayMetrics.density
    }
    private fun animate(root: ViewGroup, overlay: FrameLayout, from: Rect, to: Rect, style: CallMorphPolicy.Style,
        expanding: Boolean, completion: () -> Unit) {
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                completion()
                if (expanding) overlay.animate().alpha(0f).setStartDelay(30).setDuration(150).withEndAction {
                    root.removeView(overlay); busy = false
                }.start()
                else { root.removeView(overlay); busy = false }
            }
        }
        val animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = CallMorphPolicy.duration(style, expanding)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                // Analytic critically damped spring; finite and native-view only.
                val q = if (style == CallMorphPolicy.Style.SPRING) (1 - (1 + 8*p) * kotlin.math.exp(-8*p)).coerceIn(0f,1f) else p
                fun between(a: Int,b: Int) = (a+(b-a)*q).roundToInt()
                if (style == CallMorphPolicy.Style.FADE) {
                    overlay.layout(0,0,root.width,root.height); overlay.alpha = if (expanding) p else 1-p
                } else {
                    overlay.layout(between(from.left,to.left),between(from.top,to.top),between(from.right,to.right),between(from.bottom,to.bottom))
                    overlay.alpha = if (expanding) 1f else (1 - ((p-.409f)/.591f).coerceIn(0f,1f))
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { finish() }
                override fun onAnimationCancel(animation: android.animation.Animator) { finish() }
            })
        }
        animator.start()
        handler.postDelayed({ if (!finished) { animator.cancel(); finish() } }, 800)
    }
    companion object {
        private val instances = WeakHashMap<ViewGroup, CallMorph>()
        fun get(root: ViewGroup, window: Window?) = instances.getOrPut(root) { CallMorph(root, window) }
    }
}

@Composable
internal fun rememberCallMorph(): CallMorph? {
    val root = LocalView.current.rootView as? ViewGroup
    val window = (LocalContext.current as? Activity)?.window
    return remember(root, window) { root?.let { CallMorph.get(it, window) } }
}
