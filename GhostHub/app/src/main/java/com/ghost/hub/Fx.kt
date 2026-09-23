package com.ghost.hub

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/** Little ghosts and glowing dots drifting up behind the header. */
class GhostField(ctx: Context) : View(ctx) {
    private class P(var x: Float, var y: Float, val r: Float, val speed: Float, val phase: Float, val ghost: Boolean, val color: Int)

    private val d = resources.displayMetrics.density
    private val ps = mutableListOf<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    private var t = 0f
    private val unitGhost = Path().apply {
        // A ghost 2 units wide, top at y=-1, wavy hem at y=1.
        moveTo(-1f, 0f)
        arcTo(RectF(-1f, -1f, 1f, 1f), 180f, 180f)
        lineTo(1f, 1f)
        quadTo(0.7f, 1.35f, 0.35f, 1f)
        quadTo(0f, 0.7f, -0.35f, 1f)
        quadTo(-0.7f, 1.35f, -1f, 1f)
        close()
    }
    private val colors = intArrayOf(0x72E2F7, 0x8B7CF6, 0xFFFFFF)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ps.clear()
        repeat(16) { i ->
            val ghost = i % 4 == 0
            ps += P(
                x = Random.nextFloat() * w,
                y = Random.nextFloat() * h,
                r = if (ghost) (5 + Random.nextFloat() * 5) * d else (1.2f + Random.nextFloat() * 2.2f) * d,
                speed = (6 + Random.nextFloat() * 14) * d,
                phase = Random.nextFloat() * 6.28f,
                ghost = ghost,
                color = colors[i % colors.size],
            )
        }
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        t += dt
        for (p in ps) {
            p.y -= p.speed * dt
            if (p.y < -p.r * 2) {
                p.y = height + p.r * 2
                p.x = Random.nextFloat() * width
            }
            val wobble = sin(t * 1.3f + p.phase) * 6 * d
            val a = ((0.35f + 0.35f * sin(t * 1.7f + p.phase)) * (if (p.ghost) 90 else 150)).toInt().coerceIn(0, 255)
            paint.color = (a shl 24) or p.color
            if (p.ghost) {
                c.save()
                c.translate(p.x + wobble, p.y)
                c.scale(p.r, p.r)
                c.drawPath(unitGhost, paint)
                c.restore()
            } else {
                c.drawCircle(p.x + wobble, p.y, p.r, paint)
            }
        }
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        last = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

/** A ring drawn around an app icon: download progress, or a spinner while waiting. */
class ProgressRing(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = 0x33FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND; color = 0xFF72E2F7.toInt()
    }
    private val box = RectF()
    private var target = -1f
    private var shown = 0f
    private var spin = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    /** 0..1 for progress, negative for "waiting" (spinner). */
    fun set(progress: Float, color: Int) {
        arc.color = color
        target = progress
        if (progress < 0f) { if (!spinner.isStarted) spinner.start() } else spinner.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) - arc.strokeWidth
        box.set((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
        c.drawArc(box, 0f, 360f, false, track)
        if (target < 0f) {
            c.drawArc(box, spin - 90f, 90f, false, arc)
        } else {
            shown += (target - shown) * 0.3f
            if (kotlin.math.abs(target - shown) > 0.002f) postInvalidateOnAnimation() else shown = target
            c.drawArc(box, -90f, 360f * shown, false, arc)
        }
    }
}

/** Gentle pulsing placeholder used while the app list loads. */
fun View.shimmer() {
    val a = ValueAnimator.ofFloat(0.35f, 0.8f).apply {
        duration = 700
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { alpha = it.animatedValue as Float }
        start()
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = a.cancel()
    })
}
