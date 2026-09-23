package com.ghost.hub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.col(id: Int): Int = ContextCompat.getColor(this, id)

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, stroke)
}

fun oval(fill: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
}

fun Context.label(str: String, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
    TextView(this).apply {
        text = str
        textSize = sizeSp
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

fun View.rippleForeground(borderless: Boolean = false) {
    val tv = TypedValue()
    val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
    context.theme.resolveAttribute(attr, tv, true)
    foreground = ContextCompat.getDrawable(context, tv.resourceId)
}

/** Shrinks slightly while pressed and gives a light haptic tap on release. Click handling still runs. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.97f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(90).start()
            MotionEvent.ACTION_UP -> {
                v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(160).start()
                val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                if (inside) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(160).start()
        }
        false
    }
}

