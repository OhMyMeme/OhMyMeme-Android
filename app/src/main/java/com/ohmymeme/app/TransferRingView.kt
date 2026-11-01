package com.ohmymeme.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 顶栏传输进度环（复刻桌面端 SyncStatusBadge 传输图标）：
 * 外圈轨道 + 进度弧（files → bytes → 未知时不定旋转），仅传输期间显示。
 * 几何与桌面端一致：24 视口下 r=10.5、轨道 1.6、进度弧 1.9、不定弧 14/66 圆周。
 */
class TransferRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = ContextCompat.getColor(context, R.color.border_light)
        alpha = (0.85f * 255).toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val trackRect = RectF()
    private val valueRect = RectF()

    private var progress: Float = 0f
    private var indeterminate = false
    private var spinAnimator: ValueAnimator? = null

    fun setProgress(pct: Float) {
        indeterminate = false
        spinAnimator?.cancel()
        spinAnimator = null
        rotation = 0f
        progress = pct.coerceIn(0f, 0.99f)
        invalidate()
    }

    fun setIndeterminate() {
        if (indeterminate) return
        indeterminate = true
        val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1150
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { rotation = (it as ValueAnimator).animatedValue as Float }
        }
        anim.start()
        spinAnimator = anim
    }

    fun reset() {
        indeterminate = false
        progress = 0f
        spinAnimator?.cancel()
        spinAnimator = null
        rotation = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width / 24f
        val c = width / 2f
        val r = 10.5f * s
        trackPaint.strokeWidth = 1.6f * s
        valuePaint.strokeWidth = 1.9f * s
        val tr = r + trackPaint.strokeWidth / 2f
        val vr = r + valuePaint.strokeWidth / 2f
        trackRect.set(c - tr, c - tr, c + tr, c + tr)
        valueRect.set(c - vr, c - vr, c + vr, c + vr)
        canvas.drawArc(trackRect, 0f, 360f, false, trackPaint)
        val sweep = if (indeterminate) 76.4f else progress * 360f
        canvas.drawArc(valueRect, -90f, sweep, false, valuePaint)
    }

    override fun onDetachedFromWindow() {
        spinAnimator?.cancel()
        spinAnimator = null
        super.onDetachedFromWindow()
    }
}
