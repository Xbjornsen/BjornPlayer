package com.bjorntech.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin

class EqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val speeds = floatArrayOf(3.8f, 5.2f, 4.4f)
    private val phases = floatArrayOf(0f, 1.1f, 2.2f)

    var isAnimating = false
        set(value) {
            field = value
            if (value) post(ticker) else { removeCallbacks(ticker); invalidate() }
        }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 50)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = w / 5f
        val t = System.currentTimeMillis() / 1000f

        for (i in 0..2) {
            val frac = if (isAnimating)
                (0.25f + 0.75f * ((sin((t * speeds[i] + phases[i]).toDouble()) + 1.0) / 2.0)).toFloat()
            else 0.25f
            val barH = frac * h
            val left = i * (barW + barW / 2f)
            canvas.drawRoundRect(left, h - barH, left + barW, h, 2f, 2f, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }
}
