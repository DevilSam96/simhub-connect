package com.local.simhubconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.local.simhubconnect.telemetry.LapData
import com.local.simhubconnect.telemetry.RecordedSample
import java.util.Locale
import kotlin.math.max

/**
 * Speed trace of a lap with the throttle and brake strips underneath. The best
 * lap of the session can be drawn in the background as a reference.
 */
class TelemetryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var lap: LapData? = null
        set(value) {
            field = value
            invalidate()
        }

    var reference: LapData? = null
        set(value) {
            field = value
            invalidate()
        }

    var accent: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        color = 0xFF9AA7B8.toInt()
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = lap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = 4f * density
        val right = w - 4f * density
        val chartTop = 14f * density
        val stripHeight = 7f * density
        val stripGap = 3f * density
        val chartBottom = h - stripHeight * 2f - stripGap - 10f * density
        if (chartBottom <= chartTop) return

        val referenceSpeeds = reference?.samples?.mapNotNull { it.speed } ?: emptyList()
        val lapSpeeds = current.samples.mapNotNull { it.speed }
        val maxSpeed = (lapSpeeds + referenceSpeeds).maxOrNull()?.coerceAtLeast(40.0) ?: 100.0
        val minSpeed = (lapSpeeds + referenceSpeeds).minOrNull()?.coerceAtLeast(0.0) ?: 0.0

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = 0x22FFFFFF
        for (step in 1..3) {
            val y = chartBottom - (chartBottom - chartTop) * step / 4f
            canvas.drawLine(left, y, right, y, paint)
        }
        paint.color = 0x44FFFFFF
        canvas.drawLine(left, chartBottom, right, chartBottom, paint)

        val useDistance = current.samples.count { it.distancePct != null } >= current.samples.size * 0.8
        reference?.takeIf { it !== current }?.let { ref ->
            drawSpeed(canvas, ref, left, right, chartTop, chartBottom, minSpeed, maxSpeed, Color.argb(90, 255, 255, 255), 1.4f, useDistanceFor(ref))
        }
        drawSpeed(canvas, current, left, right, chartTop, chartBottom, minSpeed, maxSpeed, accent, 2.4f, useDistance)

        val throttleTop = chartBottom + 10f * density
        val brakeTop = throttleTop + stripHeight + stripGap
        drawInputStrip(canvas, current, throttle = true, left, right, throttleTop, stripHeight, 0xFF35E07A.toInt(), useDistance)
        drawInputStrip(canvas, current, throttle = false, left, right, brakeTop, stripHeight, 0xFFFF4D4D.toInt(), useDistance)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.0f km/h", maxSpeed), right, chartTop + 6f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("THR", left, brakeTop + stripHeight, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("BRK", right, brakeTop + stripHeight, textPaint)
    }

    private fun useDistanceFor(lap: LapData): Boolean =
        lap.samples.count { it.distancePct != null } >= lap.samples.size * 0.8

    private fun drawSpeed(
        canvas: Canvas,
        data: LapData,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        minSpeed: Double,
        maxSpeed: Double,
        color: Int,
        strokeWidth: Float,
        useDistance: Boolean
    ) {
        val samples = data.samples
        if (samples.size < 2) return
        val span = (maxSpeed - minSpeed).coerceAtLeast(1.0)
        path.reset()
        var started = false
        for (index in samples.indices) {
            val speed = samples[index].speed ?: continue
            val x = left + (right - left) * xOf(samples, index, useDistance)
            val y = bottom - ((speed - minSpeed) / span).toFloat().coerceIn(0f, 1f) * (bottom - top)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (!started) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun drawInputStrip(
        canvas: Canvas,
        data: LapData,
        throttle: Boolean,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        color: Int,
        useDistance: Boolean
    ) {
        val samples = data.samples
        if (samples.size < 2) return
        val buckets = 160
        val values = FloatArray(buckets)
        val counts = IntArray(buckets)
        for (index in samples.indices) {
            val value = (if (throttle) samples[index].throttle else samples[index].brake) ?: continue
            val bucket = (xOf(samples, index, useDistance) * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
            values[bucket] += value.toFloat()
            counts[bucket]++
        }
        paint.style = Paint.Style.FILL
        paint.color = 0x22FFFFFF
        canvas.drawRect(left, top, right, top + height, paint)
        paint.color = color
        val bucketWidth = (right - left) / buckets
        for (index in 0 until buckets) {
            if (counts[index] == 0) continue
            val average = values[index] / counts[index]
            val barHeight = height * (average / 100f).coerceIn(0f, 1f)
            if (barHeight <= 0f) continue
            val x = left + bucketWidth * index
            canvas.drawRect(x, top + height - barHeight, x + max(bucketWidth - 0.5f, 0.5f), top + height, paint)
        }
    }

    /** Horizontal position of a sample: lap distance when available. */
    private fun xOf(samples: List<RecordedSample>, index: Int, useDistance: Boolean): Float {
        if (useDistance) {
            var cursor = index
            while (cursor >= 0) {
                val pct = samples[cursor].distancePct
                if (pct != null) return pct.toFloat().coerceIn(0f, 1f)
                cursor--
            }
            return 0f
        }
        return if (samples.size <= 1) 0f else index.toFloat() / (samples.size - 1)
    }
}
