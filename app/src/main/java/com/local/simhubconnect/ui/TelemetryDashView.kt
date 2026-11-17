package com.local.simhubconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.local.simhubconnect.telemetry.Telemetry
import java.util.Locale
import kotlin.math.min

/**
 * Five self-contained dashboards drawn on a Canvas. They only need a
 * [Telemetry] snapshot, so they work regardless of which SimHub web dashboard
 * is selected on the PC.
 *
 * Landscape is the racing layout (tyres left, times right, speed/gear/chips in
 * the middle); portrait stacks compact blocks so nothing can overlap on tall,
 * narrow screens.
 */
class TelemetryDashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Style { GT3, FORMULA, RALLY, ENDURANCE, NEON }

    var style: Style = Style.GT3
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var telemetry: Telemetry? = null
        set(value) {
            field = value
            invalidate()
        }

    var demo: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Accent chosen in the settings, null keeps the colour of each style. */
    var accentOverride: Int? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val typeBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val typeItalic = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val typeMono = Typeface.create("monospace", Typeface.BOLD)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val data = telemetry
        val theme = themeFor(style)
        if (style == Style.NEON) {
            drawNeon(canvas, w, h, data, theme)
        } else {
            drawRace(canvas, w, h, data, theme, style)
        }
        if (demo) drawDemoTag(canvas, theme)
    }

    // ----------------------------------------------------------------- styles

    private fun drawRace(c: Canvas, w: Float, h: Float, t: Telemetry?, theme: Theme, style: Style) {
        drawBackground(c, w, h, theme)
        if (h > w * 1.02f) {
            drawPortrait(c, w, h, t, theme, style)
        } else {
            drawLandscape(c, w, h, t, theme, style)
        }
        drawCornerBrackets(c, w, h, theme)
    }

    /**
     * Racing layout: full height side panels, centre column split in speed,
     * gear and chips. The gear and the chip row are anchored to the bottom, so
     * on short screens they shrink instead of overlapping.
     */
    private fun drawLandscape(c: Canvas, w: Float, h: Float, t: Telemetry?, theme: Theme, style: Style) {
        val barHeight = min(30f * density, h * 0.10f)
        drawRpmBar(c, w, barHeight, t?.rpmRatio ?: 0f)

        val side = 12f * density
        val panelWidth = min(w * 0.26f, 240f * density)
        val panelTop = barHeight + 10f * density
        val panelBottom = h - 10f * density
        if (panelBottom - panelTop < 60f * density) return
        val leftPanel = side
        val rightPanel = w - side - panelWidth

        drawGlassPanel(c, leftPanel, panelTop, leftPanel + panelWidth, panelBottom, 14f * density, theme)
        drawGlassPanel(c, rightPanel, panelTop, w - side, panelBottom, 14f * density, theme)
        drawPanelAccent(c, leftPanel, panelTop, theme)
        drawPanelAccent(c, rightPanel, panelTop, theme)
        drawTyres(c, leftPanel, panelTop, leftPanel + panelWidth, panelBottom, t, theme, style, columns = 2)
        drawTimes(c, rightPanel, panelTop, w - side, panelBottom, t, theme, badgeText(style, t))

        val centerLeft = leftPanel + panelWidth
        val cx = (centerLeft + rightPanel) / 2f

        val chipHeight = 30f * density
        val chipsTop = h - 8f * density - chipHeight
        val gearBottom = chipsTop - 8f * density
        val gearZone = (gearBottom - barHeight).coerceAtLeast(0f)
        val gearTop = barHeight + gearZone * 0.70f
        val gearHeight = (gearBottom - gearTop).coerceAtMost(86f * density).coerceAtLeast(0f)

        val speedZone = (gearTop - barHeight).coerceAtLeast(1f)
        val speedSize = min(speedZone * 0.62f, min(96f * density, (rightPanel - centerLeft) * 0.50f))
        val speedBaseline = barHeight + speedZone * 0.62f
        drawSpeed(c, t, cx, speedBaseline, speedSize, theme)
        val unitSize = min(14f * density, maxOf(9f * density, h * 0.035f))
        drawText(
            c,
            "KM/H",
            cx,
            min(speedBaseline + speedSize * 0.26f, gearTop - 4f * density),
            unitSize,
            theme.muted,
            Paint.Align.CENTER
        )

        drawGear(
            c,
            cx,
            min(104f * density, w * 0.20f),
            gearBottom - gearHeight,
            gearHeight,
            t,
            theme
        )
        drawExtras(c, style, cx, w, chipsTop, t, theme)
    }

    /**
     * Portrait: one column, every block sized to its content. The tyres are a
     * compact single row instead of a panel stretching to the bottom.
     */
    private fun drawPortrait(c: Canvas, w: Float, h: Float, t: Telemetry?, theme: Theme, style: Style) {
        val barHeight = min(26f * density, h * 0.06f)
        drawRpmBar(c, w, barHeight, t?.rpmRatio ?: 0f)

        val pad = 12f * density
        val contentTop = barHeight + 10f * density

        val speedSize = min(w * 0.32f, h * 0.13f)
        val speedBaseline = contentTop + speedSize * 0.92f
        drawSpeed(c, t, w / 2f, speedBaseline, speedSize, theme)
        val unitSize = min(14f * density, maxOf(9f * density, h * 0.022f))
        drawText(c, "KM/H", w / 2f, speedBaseline + unitSize * 1.35f, unitSize, theme.muted, Paint.Align.CENTER)

        var y = speedBaseline + unitSize * 1.9f
        val gearHeight = min(80f * density, h * 0.10f)
        drawGear(c, w / 2f, min(128f * density, w * 0.34f), y, gearHeight, t, theme)
        y += gearHeight + 10f * density

        val chipHeight = 30f * density
        drawExtras(c, style, w / 2f, w, y, t, theme)
        y += chipHeight + 8f * density

        val bottom = h - pad
        val remaining = bottom - y
        if (remaining < 40f * density) return

        val tyresHeight = min(
            remaining * 0.46f,
            min(146f * density, (w - pad * 2f) * 0.42f)
        ).coerceAtLeast(min(70f * density, remaining * 0.46f))
        drawGlassPanel(c, pad, y, w - pad, y + tyresHeight, 14f * density, theme)
        drawPanelAccent(c, pad, y, theme)
        drawTyres(c, pad, y, w - pad, y + tyresHeight, t, theme, style, columns = 4)
        y += tyresHeight + 8f * density

        if (bottom - y > 60f * density) {
            drawGlassPanel(c, pad, y, w - pad, bottom, 14f * density, theme)
            drawPanelAccent(c, pad, y, theme)
            drawTimes(c, pad, y, w - pad, bottom, t, theme, badgeText(style, t))
        }
    }

    /** Full-width LED rev bar with green/amber/red zones. */
    private fun drawRpmBar(c: Canvas, w: Float, barHeight: Float, ratio: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xF2000000.toInt()
        c.drawRect(0f, 0f, w, barHeight, paint)

        val segments = (w / (14f * density)).toInt().coerceIn(20, 64)
        val gap = maxOf(2f * density, w * 0.002f)
        val segmentWidth = (w - gap * (segments - 1)) / segments
        val top = barHeight * 0.24f
        val bottom = barHeight * 0.78f
        val radius = (bottom - top) * 0.28f
        for (index in 0 until segments) {
            val threshold = (index + 1f) / segments
            val lit = ratio >= threshold - 0.5f / segments
            paint.color = when {
                !lit -> 0x24FFFFFF
                index < segments * 0.55f -> 0xFF35E07A.toInt()
                index < segments * 0.82f -> 0xFFFFD43B.toInt()
                else -> 0xFFFF3B30.toInt()
            }
            val left = index * (segmentWidth + gap)
            c.drawRoundRect(left, top, left + segmentWidth, bottom, radius, radius, paint)
        }

        paint.color = 0x33000000
        c.drawRect(0f, barHeight, w, barHeight + density, paint)
    }

    private fun drawGear(
        c: Canvas,
        cx: Float,
        chipWidth: Float,
        top: Float,
        chipHeight: Float,
        t: Telemetry?,
        theme: Theme
    ) {
        if (chipHeight <= 1f) return
        val left = cx - chipWidth / 2f
        val bottom = top + chipHeight
        drawPanel(c, left, top, left + chipWidth, bottom, 12f * density, theme.panel)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.argb(90, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        c.drawRoundRect(left, top, left + chipWidth, bottom, 12f * density, 12f * density, paint)
        paint.style = Paint.Style.FILL

        val gearSize = min(52f * density, chipHeight * 0.64f)
        drawText(
            c,
            t?.gear ?: "--",
            cx,
            top + chipHeight / 2f + gearSize * 0.36f,
            gearSize,
            theme.accent,
            Paint.Align.CENTER,
            italic = true
        )
    }

    /** Discipline-specific chips: ABS/TC for GT3, ERS/DRS for formula, etc. */
    private fun drawExtras(
        c: Canvas,
        style: Style,
        cx: Float,
        w: Float,
        top: Float,
        t: Telemetry?,
        theme: Theme
    ) {
        val chips = when (style) {
            Style.GT3 -> listOf(
                Chip("ABS", t?.abs ?: "--"),
                Chip("TC", t?.tc ?: "--"),
                Chip("BIL", biasText(t))
            )
            Style.FORMULA -> listOf(
                Chip("ERS", ersText(t), ratio = t?.ers?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }),
                Chip("DRS", stateText(t?.drs), highlight = t?.drs == true),
                Chip("BIL", biasText(t))
            )
            Style.RALLY -> listOf(
                Chip("HB", stateText(t?.handbrake), highlight = t?.handbrake == true, alarm = true),
                Chip("FONDO", t?.surface?.uppercase()?.take(10) ?: "--"),
                Chip("SET", t?.sector?.toString() ?: "--")
            )
            Style.ENDURANCE -> listOf(
                Chip("CARB", fuelText(t), ratio = fuelRatio(t)),
                Chip("L/GIRO", perLapText(t)),
                Chip("GIRI", rangeText(t))
            )
            Style.NEON -> return
        }

        val gap = 8f * density
        val available = w - 40f * density
        val chipWidth = min(124f * density, (available - gap * (chips.size - 1)) / chips.size)
        val total = chipWidth * chips.size + gap * (chips.size - 1)
        var left = cx - total / 2f
        for (chip in chips) {
            drawChip(c, left, top, chipWidth, 30f * density, chip, theme)
            left += chipWidth + gap
        }
    }

    private fun drawChip(
        c: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        chip: Chip,
        theme: Theme
    ) {
        drawPanel(c, left, top, left + width, top + height, 8f * density, theme.panel)
        val color = when {
            chip.alarm && chip.highlight -> theme.danger
            chip.highlight -> 0xFF35E07A.toInt()
            else -> theme.accent
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        c.drawRoundRect(left, top, left + width, top + height, 8f * density, 8f * density, paint)
        paint.style = Paint.Style.FILL

        val inner = 8f * density
        val baseline = top + height * 0.62f
        drawText(c, chip.label, left + inner, baseline, 8f * density, theme.muted)
        drawText(
            c,
            chip.value,
            left + width - inner,
            baseline,
            fittedChipSize(chip.value, width - inner * 2f - 34f * density, 12.5f * density),
            if (chip.highlight) color else theme.text,
            Paint.Align.RIGHT,
            mono = true
        )
        chip.ratio?.let { ratio ->
            drawBar(c, left + inner, top + height - 7f * density, width - inner * 2f, 3.5f * density, ratio, color)
        }
    }

    private fun fittedChipSize(value: String, maxWidth: Float, maxSize: Float): Float {
        if (maxWidth <= 0f) return maxSize
        textPaint.typeface = typeMono
        textPaint.textSize = maxSize
        val measured = textPaint.measureText(value)
        return if (measured <= 0f || measured <= maxWidth) maxSize else maxSize * maxWidth / measured
    }

    private fun badgeText(style: Style, t: Telemetry?): String? = when (style) {
        Style.RALLY -> "SET ${t?.sector ?: "--"}"
        Style.NEON -> null
        else -> {
            val parts = mutableListOf<String>()
            t?.position?.let { position ->
                parts += t.totalCars?.let { "P$position/$it" } ?: "P$position"
            }
            t?.lap?.let { lap ->
                parts += t.totalLaps?.let { "L$lap/$it" } ?: "L$lap"
            }
            parts.joinToString(" ").takeIf { it.isNotEmpty() }
        }
    }

    private fun disciplineLabel(style: Style): String = when (style) {
        Style.GT3 -> "GT3 SPRINT"
        Style.FORMULA -> "FORMULA"
        Style.RALLY -> "RALLY"
        Style.ENDURANCE -> "ENDURANCE"
        Style.NEON -> "NEON"
    }

    private fun stateText(value: Boolean?): String = when (value) {
        true -> "ON"
        false -> "OFF"
        null -> "--"
    }

    private fun biasText(t: Telemetry?): String =
        t?.brakeBias?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"

    private fun ersText(t: Telemetry?): String =
        t?.ers?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"

    private fun fuelText(t: Telemetry?): String =
        t?.fuel?.let { String.format(Locale.US, "%.1f L", it) } ?: "--"

    private fun fuelRatio(t: Telemetry?): Float? {
        val fuel = t?.fuel ?: return null
        val capacity = t.fuelCapacity ?: return null
        if (capacity <= 0.0) return null
        return (fuel / capacity).toFloat().coerceIn(0f, 1f)
    }

    private fun perLapText(t: Telemetry?): String =
        t?.fuelPerLap?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

    private fun rangeText(t: Telemetry?): String {
        val fuel = t?.fuel ?: return "--"
        val perLap = t.fuelPerLap ?: return "--"
        if (perLap <= 0.0) return "--"
        return "≈${(fuel / perLap).toInt()} giri"
    }

    private data class Chip(
        val label: String,
        val value: String,
        val ratio: Float? = null,
        val highlight: Boolean = false,
        val alarm: Boolean = false
    )

    private fun drawTyres(
        c: Canvas,
        l: Float,
        top: Float,
        r: Float,
        bottom: Float,
        t: Telemetry?,
        theme: Theme,
        style: Style,
        columns: Int
    ) {
        val inner = 12f * density
        val titleBaseline = top + inner + 9f * density
        drawText(c, "GOMME", l + inner, titleBaseline, 10f * density, theme.muted)
        drawText(
            c,
            disciplineLabel(style),
            r - inner,
            titleBaseline,
            8.5f * density,
            theme.accent2,
            Paint.Align.RIGHT
        )
        paint.color = Color.argb(150, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        c.drawRect(
            l + inner,
            titleBaseline + 5f * density,
            l + inner + 24f * density,
            titleBaseline + 7f * density,
            paint
        )

        val gridTop = titleBaseline + 13f * density
        val rows = (4 + columns - 1) / columns
        val cellWidth = (r - l - inner * 2f) / columns
        val cellHeight = (bottom - inner - gridTop) / rows
        if (cellHeight <= 0f) return
        val cells = listOf(
            "FL" to t?.tyreFl,
            "FR" to t?.tyreFr,
            "RL" to t?.tyreRl,
            "RR" to t?.tyreRr
        )
        cells.forEachIndexed { index, cell ->
            val column = index % columns
            val row = index / columns
            drawTyre(
                c,
                l + inner + column * cellWidth,
                gridTop + row * cellHeight,
                cellWidth,
                cellHeight,
                cell.first,
                cell.second,
                theme
            )
        }
    }

    private fun drawTyre(
        c: Canvas,
        left: Float,
        top: Float,
        cellWidth: Float,
        cellHeight: Float,
        label: String,
        temp: Double?,
        theme: Theme
    ) {
        val tyreWidth = min(cellWidth * 0.52f, 44f * density)
        val tyreHeight = min(cellHeight * 0.50f, tyreWidth * 1.6f)
        val x = left + (cellWidth - tyreWidth) / 2f
        val y = top + 11f * density

        drawText(c, label, left + cellWidth / 2f, top + 9f * density, 9f * density, theme.muted, Paint.Align.CENTER)

        paint.style = Paint.Style.FILL
        paint.color = tyreColor(temp)
        c.drawRoundRect(x, y, x + tyreWidth, y + tyreHeight, tyreWidth * 0.30f, tyreWidth * 0.30f, paint)

        paint.color = 0x33000000
        val tread = tyreHeight / 5f
        for (row in 1..3) {
            val lineY = y + tread * row
            c.drawRoundRect(
                x + tyreWidth * 0.18f,
                lineY,
                x + tyreWidth * 0.82f,
                lineY + 2f * density,
                density,
                density,
                paint
            )
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = 0x66FFFFFF
        c.drawRoundRect(x, y, x + tyreWidth, y + tyreHeight, tyreWidth * 0.30f, tyreWidth * 0.30f, paint)
        paint.style = Paint.Style.FILL

        val value = temp?.let { String.format(Locale.US, "%.0f°", it) } ?: "--"
        drawText(
            c,
            value,
            left + cellWidth / 2f,
            min(y + tyreHeight + 14f * density, top + cellHeight - 2f * density),
            min(14f * density, cellHeight * 0.17f),
            if (temp == null) theme.muted else theme.text,
            Paint.Align.CENTER,
            mono = true
        )
    }

    /** Cold is light blue, optimal green, overheating red. */
    private fun tyreColor(temp: Double?): Int = when {
        temp == null -> 0x44FFFFFF
        temp < 65.0 -> 0xFF38BDF8.toInt()
        temp < 95.0 -> 0xFF35E07A.toInt()
        else -> 0xFFFF4D4D.toInt()
    }

    private fun drawTimes(
        c: Canvas,
        l: Float,
        top: Float,
        r: Float,
        bottom: Float,
        t: Telemetry?,
        theme: Theme,
        badge: String?
    ) {
        val inner = 12f * density
        val contentWidth = r - l - inner * 2f
        val titleBaseline = top + inner + 9f * density
        drawText(c, "TEMPI", l + inner, titleBaseline, 10f * density, theme.muted)
        badge?.let {
            drawText(c, it, r - inner, titleBaseline, 9f * density, theme.accent2, Paint.Align.RIGHT)
        }
        paint.color = Color.argb(150, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        c.drawRect(
            l + inner,
            titleBaseline + 5f * density,
            l + inner + 24f * density,
            titleBaseline + 7f * density,
            paint
        )

        val rowsTop = titleBaseline + 14f * density
        val rowHeight = (bottom - inner - rowsTop) / 4f
        if (rowHeight <= 0f) return
        val labelSize = min(9f * density, rowHeight * 0.24f)
        val valueSize = min(24f * density, rowHeight * 0.50f)
        val delta = deltaMillis(t)
        val rows = listOf(
            Triple("ATTUALE", lapTime(t?.currentLapMs), theme.text),
            Triple("ULTIMO", lapTime(t?.lastLapMs), theme.text),
            Triple("MIGLIORE", lapTime(t?.bestLapMs), theme.accent2),
            Triple("DELTA", deltaText(delta), deltaColor(delta, theme))
        )
        rows.forEachIndexed { index, row ->
            val rowTop = rowsTop + index * rowHeight
            drawText(c, row.first, l + inner, rowTop + labelSize, labelSize, theme.muted)
            drawText(
                c,
                row.second,
                l + inner,
                rowTop + labelSize + valueSize,
                fittedTextSize(row.second, contentWidth, valueSize),
                row.third,
                mono = true
            )
        }
    }

    private fun deltaMillis(t: Telemetry?): Long? {
        val current = t?.currentLapMs ?: return null
        val best = t.bestLapMs ?: return null
        return current - best
    }

    private fun deltaText(delta: Long?): String =
        if (delta == null) "--.--" else String.format(Locale.US, "%+.3f", delta / 1000.0)

    private fun deltaColor(delta: Long?, theme: Theme): Int = when {
        delta == null -> theme.muted
        delta <= 0L -> 0xFF35E07A.toInt()
        else -> theme.danger
    }

    private fun fittedTextSize(value: String, maxWidth: Float, maxSize: Float): Float {
        textPaint.typeface = typeMono
        textPaint.textSize = maxSize
        val measured = textPaint.measureText(value)
        return if (measured <= 0f || measured <= maxWidth) maxSize else maxSize * maxWidth / measured
    }

    private fun drawGlassPanel(
        c: Canvas,
        l: Float,
        top: Float,
        r: Float,
        bottom: Float,
        radius: Float,
        theme: Theme
    ) {
        drawPanel(c, l, top, r, bottom, radius, theme.panel)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = Color.argb(50, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        c.drawRoundRect(l, top, r, bottom, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /** Short accent tick on the top-left corner of a panel. */
    private fun drawPanelAccent(c: Canvas, left: Float, top: Float, theme: Theme) {
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(theme.accent, 210)
        val width = 3f * density
        val x = left + 1.5f * density
        c.drawRoundRect(
            x,
            top + 12f * density,
            x + width,
            top + 44f * density,
            width / 2f,
            width / 2f,
            paint
        )
    }

    /** Thin L brackets in the four corners: a racing HUD frame. */
    private fun drawCornerBrackets(c: Canvas, w: Float, h: Float, theme: Theme) {
        val inset = 4f * density
        val len = 16f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(theme.accent, 150)
        c.drawLine(inset, inset + len, inset, inset, paint)
        c.drawLine(inset, inset, inset + len, inset, paint)
        c.drawLine(w - inset - len, inset, w - inset, inset, paint)
        c.drawLine(w - inset, inset, w - inset, inset + len, paint)
        c.drawLine(inset, h - inset - len, inset, h - inset, paint)
        c.drawLine(inset, h - inset, inset + len, h - inset, paint)
        c.drawLine(w - inset - len, h - inset, w - inset, h - inset, paint)
        c.drawLine(w - inset, h - inset - len, w - inset, h - inset, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    private fun drawNeon(c: Canvas, w: Float, h: Float, t: Telemetry?, theme: Theme) {
        drawBackground(c, w, h, theme)
        drawHeader(c, w, t, theme)

        val cx = w * 0.5f
        val cy = h * 0.47f
        val radius = min(w, h) * 0.30f
        drawRpmArc(c, cx, cy, radius, t?.rpmRatio ?: 0f, theme)

        drawSpeed(c, t, cx, cy + radius * 0.28f, radius * 0.95f, theme)
        drawText(c, "KM/H", cx, cy + radius * 0.52f, radius * 0.12f, theme.muted, Paint.Align.CENTER)
        drawText(c, t?.gear ?: "--", cx, h * 0.90f, h * 0.11f, theme.accent, Paint.Align.CENTER, italic = true)

        drawLapBlock(c, w * 0.04f, h * 0.76f, w * 0.24f, t, theme, 0.72f)
        drawInputs(c, w * 0.60f, h * 0.84f, w * 0.36f, t, theme, 0.8f)
    }

    // ------------------------------------------------------------ components

    private fun drawHeader(c: Canvas, w: Float, t: Telemetry?, theme: Theme) {
        val first = (t?.car ?: t?.game ?: "SIMHUB TELEMETRY").uppercase(Locale.US).take(24)
        val second = (t?.track ?: "TELEMETRIA LIVE").uppercase(Locale.US).take(30)
        drawText(c, first, pad(), pad() + 12f * density, 13f * density, theme.text)
        drawText(c, second, pad(), pad() + 27f * density, 9f * density, theme.muted)
        drawPosition(c, w - pad(), pad(), t, theme)
    }

    private fun drawPosition(c: Canvas, right: Float, top: Float, t: Telemetry?, theme: Theme) {
        val position = t?.position ?: return
        val label = buildString {
            append('P').append(position)
            t.totalCars?.let { append('/').append(it) }
        }
        textPaint.textSize = 18f * density
        textPaint.typeface = typeBold
        val textWidth = textPaint.measureText(label)
        val pillWidth = textWidth + 22f * density
        val pillHeight = 30f * density
        drawPanel(c, right - pillWidth, top, right, top + pillHeight, pillHeight / 2f, theme.panel)
        drawText(c, label, right - pillWidth / 2f, top + pillHeight * 0.68f, 17f * density, theme.accent2, Paint.Align.CENTER)
    }

    private fun drawSpeed(c: Canvas, t: Telemetry?, cx: Float, baseline: Float, size: Float, theme: Theme) {
        val value = t?.speedKmh?.let { Math.round(it).toString() } ?: "--"
        textPaint.setShadowLayer(
            size * 0.18f,
            0f,
            0f,
            withAlpha(theme.accent, 110)
        )
        drawText(c, value, cx, baseline, size, theme.text, Paint.Align.CENTER, italic = true)
        textPaint.clearShadowLayer()
    }

    private fun drawRpmArc(c: Canvas, cx: Float, cy: Float, radius: Float, ratio: Float, theme: Theme) {
        val start = 135f
        val sweep = 270f
        val stroke = radius * 0.13f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        paint.color = Color.argb(45, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        paint.strokeWidth = stroke * 2.4f
        c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep, false, paint)

        paint.color = Color.argb(55, 255, 255, 255)
        paint.strokeWidth = stroke
        c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep, false, paint)

        paint.color = if (ratio > 0.9f) theme.danger else theme.accent
        c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep * ratio, false, paint)

        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawInputs(
        c: Canvas,
        left: Float,
        top: Float,
        width: Float,
        t: Telemetry?,
        theme: Theme,
        scale: Float
    ) {
        val rowHeight = 24f * density
        val labelSize = 10f * density * scale
        val labelWidth = 34f * density * scale
        val percentWidth = 44f * density * scale
        val barWidth = (width - labelWidth - percentWidth).coerceAtLeast(24f * density)
        val barHeight = 7f * density * scale

        drawInputRow(c, left, top, labelWidth, barWidth, percentWidth, barHeight, labelSize, "THR", t?.throttle, theme.accent2, theme)
        drawInputRow(
            c,
            left,
            top + rowHeight * scale,
            labelWidth,
            barWidth,
            percentWidth,
            barHeight,
            labelSize,
            "BRK",
            t?.brake,
            theme.danger,
            theme
        )
    }

    private fun drawInputRow(
        c: Canvas,
        left: Float,
        top: Float,
        labelWidth: Float,
        barWidth: Float,
        percentWidth: Float,
        barHeight: Float,
        labelSize: Float,
        label: String,
        value: Double?,
        color: Int,
        theme: Theme
    ) {
        val percent = (value ?: 0.0).coerceIn(0.0, 100.0)
        val textBaseline = top + barHeight / 2f + labelSize * 0.38f
        drawText(c, label, left, textBaseline, labelSize, theme.muted)
        drawBar(c, left + labelWidth, top, barWidth, barHeight, (percent / 100.0).toFloat(), color)
        drawText(
            c,
            "${percent.toInt()}%",
            left + labelWidth + barWidth + percentWidth,
            textBaseline,
            labelSize,
            theme.text,
            Paint.Align.RIGHT
        )
    }

    private fun drawLapBlock(
        c: Canvas,
        left: Float,
        top: Float,
        width: Float,
        t: Telemetry?,
        theme: Theme,
        scale: Float
    ) {
        val rowHeight = 26f * density * scale
        val labelSize = 9f * density * scale
        val valueSize = 14f * density * scale
        drawLabelValue(c, "LAP", lapCount(t), left, top, width, labelSize, valueSize, theme.text, theme)
        drawLabelValue(c, "TIME", lapTime(t?.currentLapMs), left, top + rowHeight, width, labelSize, valueSize, theme.text, theme)
        drawLabelValue(c, "LAST", lapTime(t?.lastLapMs), left, top + rowHeight * 2f, width, labelSize, valueSize, theme.muted, theme)
        drawLabelValue(c, "BEST", lapTime(t?.bestLapMs), left, top + rowHeight * 3f, width, labelSize, valueSize, theme.accent2, theme)
    }

    private fun drawLabelValue(
        c: Canvas,
        label: String,
        value: String,
        left: Float,
        top: Float,
        width: Float,
        labelSize: Float,
        valueSize: Float,
        valueColor: Int,
        theme: Theme
    ) {
        val baseline = top + valueSize * 0.82f
        drawText(c, label, left, baseline, labelSize, theme.muted)
        drawText(c, value, left + width, baseline, valueSize, valueColor, Paint.Align.RIGHT, mono = true)
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float, theme: Theme) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Diagonal streaks give the screen some racing attitude without
        // stealing contrast from the numbers.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = withAlpha(theme.accent, 16)
        var x = w * 0.08f
        while (x < w * 1.1f) {
            c.drawLine(x, h, x - h * 0.45f, 0f, paint)
            x += w * 0.16f
        }
        paint.style = Paint.Style.FILL

        paint.color = theme.grid
        paint.strokeWidth = density
        var y = h * 0.16f
        while (y < h) {
            c.drawLine(0f, y, w, y, paint)
            y += h * 0.16f
        }

        paint.shader = RadialGradient(
            w / 2f,
            h / 2f,
            maxOf(w, h) * 0.75f,
            intArrayOf(0x00000000, 0x99000000.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawDemoTag(c: Canvas, theme: Theme) {
        val height = 16f * density
        val width = 46f * density
        val left = 6f * density
        val top = 6f * density
        drawPanel(c, left, top, left + width, top + height, height / 2f, theme.panel)
        drawText(
            c,
            "DEMO",
            left + width / 2f,
            top + height * 0.70f,
            8f * density,
            theme.accent2,
            Paint.Align.CENTER
        )
    }

    private fun drawPanel(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRoundRect(l, t, r, b, radius, radius, paint)
    }

    private fun drawBar(c: Canvas, left: Float, top: Float, width: Float, height: Float, ratio: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = 0x33FFFFFF
        c.drawRoundRect(left, top, left + width, top + height, height / 2f, height / 2f, paint)
        val filled = width * ratio.coerceIn(0f, 1f)
        if (filled > 0.5f) {
            paint.color = color
            c.drawRoundRect(left, top, left + filled, top + height, height / 2f, height / 2f, paint)
        }
    }

    private fun drawText(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        italic: Boolean = false,
        mono: Boolean = false
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.typeface = when {
            mono -> typeMono
            italic -> typeItalic
            else -> typeBold
        }
        c.drawText(value, x, y, textPaint)
    }

    // ----------------------------------------------------------------- utils

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun lapTime(ms: Long?): String {
        if (ms == null || ms <= 0L) return "--:--.---"
        val totalSeconds = ms / 1000.0
        val minutes = (totalSeconds / 60.0).toInt()
        val seconds = totalSeconds - minutes * 60.0
        return if (minutes > 0) {
            String.format(Locale.US, "%d:%06.3f", minutes, seconds)
        } else {
            String.format(Locale.US, "%.3f", seconds)
        }
    }

    private fun lapCount(t: Telemetry?): String {
        val lap = t?.lap ?: return "--"
        val total = t.totalLaps
        return if (total != null) "$lap/$total" else lap.toString()
    }

    private fun pad(): Float = 18f * density

    private fun themeFor(style: Style): Theme {
        val base = when (style) {
            Style.GT3 -> THEME_GT3
            Style.FORMULA -> THEME_FORMULA
            Style.RALLY -> THEME_RALLY
            Style.ENDURANCE -> THEME_ENDURANCE
            Style.NEON -> THEME_NEON
        }
        val accent = accentOverride ?: return base
        return base.copy(accent = accent)
    }

    private data class Theme(
        val bgTop: Int,
        val bgBottom: Int,
        val panel: Int,
        val accent: Int,
        val accent2: Int,
        val warning: Int,
        val danger: Int,
        val text: Int,
        val muted: Int,
        val grid: Int
    )

    private companion object {
        val THEME_GT3 = Theme(
            bgTop = 0xFF10161D.toInt(),
            bgBottom = 0xFF05070A.toInt(),
            panel = 0xCC1B232C.toInt(),
            accent = 0xFFFF6B2C.toInt(),
            accent2 = 0xFF2DD4BF.toInt(),
            warning = 0xFFFFC24B.toInt(),
            danger = 0xFFFF4D4D.toInt(),
            text = 0xFFF2F6FA.toInt(),
            muted = 0xFF8B9AAC.toInt(),
            grid = 0x12FFFFFF
        )

        val THEME_FORMULA = Theme(
            bgTop = 0xFF120A0D.toInt(),
            bgBottom = 0xFF050507.toInt(),
            panel = 0xCC221016.toInt(),
            accent = 0xFFFF2E4D.toInt(),
            accent2 = 0xFF29D7FF.toInt(),
            warning = 0xFFFFB020.toInt(),
            danger = 0xFFFF1744.toInt(),
            text = 0xFFF7F8FB.toInt(),
            muted = 0xFF9DA3B4.toInt(),
            grid = 0x14FF2E4D
        )

        val THEME_RALLY = Theme(
            bgTop = 0xFF0A160D.toInt(),
            bgBottom = 0xFF040806.toInt(),
            panel = 0xCC14251A.toInt(),
            accent = 0xFFB6FF3B.toInt(),
            accent2 = 0xFFFFE45C.toInt(),
            warning = 0xFFFFB020.toInt(),
            danger = 0xFFFF5252.toInt(),
            text = 0xFFF3FBEF.toInt(),
            muted = 0xFF93AB95.toInt(),
            grid = 0x14B6FF3B
        )

        val THEME_ENDURANCE = Theme(
            bgTop = 0xFF07141D.toInt(),
            bgBottom = 0xFF03080C.toInt(),
            panel = 0xCC11242F.toInt(),
            accent = 0xFF38BDF8.toInt(),
            accent2 = 0xFFFFC24B.toInt(),
            warning = 0xFFFFC24B.toInt(),
            danger = 0xFFFF5C5C.toInt(),
            text = 0xFFEFF7FB.toInt(),
            muted = 0xFF8FA6B6.toInt(),
            grid = 0x1238BDF8
        )

        val THEME_NEON = Theme(
            bgTop = 0xFF160826.toInt(),
            bgBottom = 0xFF04010A.toInt(),
            panel = 0xCC241033.toInt(),
            accent = 0xFFFF2ED6.toInt(),
            accent2 = 0xFF2EF2FF.toInt(),
            warning = 0xFFFFE45C.toInt(),
            danger = 0xFFFF3B3B.toInt(),
            text = 0xFFF8F4FF.toInt(),
            muted = 0xFF9A8CB5.toInt(),
            grid = 0x14FF2ED6
        )
    }
}
