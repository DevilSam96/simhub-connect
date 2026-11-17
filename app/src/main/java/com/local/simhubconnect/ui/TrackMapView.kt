package com.local.simhubconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.local.simhubconnect.telemetry.MapPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the circuit rebuilt from the recorded world coordinates, coloured by
 * speed: blue for the slowest point, red for the fastest.
 */
class TrackMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var points: List<MapPoint> = emptyList()
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = points
        if (pts.size < 8) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = 16f * density
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1e-6)
        val spanY = (maxY - minY).coerceAtLeast(1e-6)
        val available = max(1f, min(w - pad * 2f, h - pad * 2f))
        val scale = min(available / spanX, available / spanY)
        val left = (w - spanX * scale) / 2f
        val bottom = (h + spanY * scale) / 2f

        fun px(point: MapPoint): Float = (left + (point.x - minX) * scale).toFloat()
        fun py(point: MapPoint): Float = (bottom - (point.y - minY) * scale).toFloat()

        // Dark ribbon under the coloured line: the track reads as one shape.
        val shadow = Path()
        shadow.moveTo(px(pts.first()), py(pts.first()))
        for (index in 1 until pts.size) shadow.lineTo(px(pts[index]), py(pts[index]))
        paint.color = Color.argb(70, 0, 0, 0)
        paint.strokeWidth = 13f * density
        canvas.drawPath(shadow, paint)

        val minSpeed = pts.minOf { it.speed }
        val maxSpeed = pts.maxOf { it.speed }
        val span = (maxSpeed - minSpeed).coerceAtLeast(1.0)
        paint.strokeWidth = 7f * density
        for (index in 1 until pts.size) {
            val previous = pts[index - 1]
            val current = pts[index]
            val ratio = ((current.speed - minSpeed) / span).toFloat().coerceIn(0f, 1f)
            paint.color = speedColor(ratio)
            canvas.drawLine(px(previous), py(previous), px(current), py(current), paint)
        }

        // Start/finish marker.
        val startX = px(pts.first())
        val startY = py(pts.first())
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 0, 0, 0)
        canvas.drawCircle(startX, startY, 7f * density, paint)
        paint.color = accent
        canvas.drawCircle(startX, startY, 4.2f * density, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun speedColor(ratio: Float): Int = when {
        ratio < 0.34f -> blend(SLOW, MEDIUM, ratio / 0.34f)
        ratio < 0.67f -> blend(MEDIUM, FAST, (ratio - 0.34f) / 0.33f)
        else -> blend(FAST, TOP, (ratio - 0.67f) / 0.33f)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private companion object {
        const val SLOW = 0xFF38BDF8.toInt()
        const val MEDIUM = 0xFF35E07A.toInt()
        const val FAST = 0xFFFFD43B.toInt()
        const val TOP = 0xFFFF3B30.toInt()
    }
}
