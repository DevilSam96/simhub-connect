package com.local.simhubconnect

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.local.simhubconnect.databinding.ActivityAnalysisBinding
import com.local.simhubconnect.telemetry.LapData
import com.local.simhubconnect.telemetry.RecordingAnalysis
import com.local.simhubconnect.telemetry.RecordingAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Detailed analysis of a recording: track map, car behaviour, ideal lap and a
 * way to compare with other drivers on the community platforms.
 */
class AnalysisActivity : ThemedActivity() {

    private lateinit var binding: ActivityAnalysisBinding
    private var analysis: RecordingAnalysis? = null

    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.trackTitanButton.setOnClickListener { openUrl(TRACK_TITAN_URL) }
        binding.garage61Button.setOnClickListener { openUrl(GARAGE61_URL) }
        binding.searchButton.setOnClickListener { openUrl(searchUrl()) }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val file = File(path)
        binding.toolbar.subtitle = file.name

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RecordingAnalyzer.analyze(file) }
            if (isFinishing || isDestroyed) return@launch
            analysis = result
            binding.loading.isVisible = false
            if (result == null) {
                binding.statusText.setText(R.string.analysis_error)
                binding.statusText.isVisible = true
                return@launch
            }
            binding.content.isVisible = true
            render(result)
        }
    }

    private fun render(result: RecordingAnalysis) {
        val accent = themeColor(COLOR_PRIMARY)
        binding.mapView.accent = accent
        binding.chartView.accent = accent

        binding.summaryTrack.text = result.track ?: getString(R.string.analysis_unknown_track)
        binding.summaryCar.text = result.car ?: getString(R.string.analysis_unknown_car)
        binding.summaryConditions.text = getString(
            R.string.analysis_conditions_format,
            tempText(result.airTemp),
            tempText(result.trackTemp),
            wetText(result.wetness)
        )
        binding.summaryMeta.text = getString(R.string.analysis_meta, result.laps.size, result.totalSamples)
        binding.bestValue.text = formatLap(result.bestLap?.durationMs)
        binding.idealValue.text = formatLap(result.theoreticalBestMs)

        val delta = result.deltaToIdealMs
        binding.deltaValue.text = delta?.let { formatDelta(it) } ?: "--"
        binding.deltaValue.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    delta == null -> R.color.text_secondary
                    delta <= 0L -> R.color.success
                    else -> R.color.warning
                }
            )
        )

        binding.mapView.points = result.trackMap
        binding.mapView.isVisible = result.hasTrackMap
        binding.mapEmpty.isVisible = !result.hasTrackMap

        binding.idealDesc.setText(
            if (result.theoreticalBestMs != null) R.string.analysis_ideal_desc
            else R.string.analysis_ideal_unavailable
        )

        binding.onlineDesc.text = getString(
            R.string.analysis_online_desc,
            result.track ?: getString(R.string.analysis_unknown_track)
        )

        buildLapChips(result)
        renderLapTable(result)
    }

    // -------------------------------------------------------------- lap chips

    private fun buildLapChips(result: RecordingAnalysis) {
        val initial = result.laps.indexOfFirst { it === result.bestLap }.takeIf { it >= 0 } ?: 0
        binding.lapChips.removeAllViews()
        result.laps.forEachIndexed { index, lap ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = index
                text = getString(R.string.analysis_lap_chip, lap.number, formatLap(lap.durationMs))
                isCheckable = true
                isChecked = index == initial
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 32f * density
                textSize = 12f
            }
            binding.lapChips.addView(chip)
        }
        binding.lapChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { id -> group.findViewById<Chip>(id) }
                ?: return@setOnCheckedStateChangeListener
            val index = chip.tag as? Int ?: return@setOnCheckedStateChangeListener
            showLap(result, index)
        }
        showLap(result, initial)
    }

    private fun showLap(result: RecordingAnalysis, index: Int) {
        val lap = result.laps.getOrNull(index) ?: return
        binding.chartView.lap = lap
        binding.chartView.reference = result.bestLap?.takeIf { it !== lap }
        renderStats(lap)
    }

    // ------------------------------------------------------------------ stats

    private fun renderStats(lap: LapData) {
        val samples = lap.samples
        val throttle = samples.mapNotNull { it.throttle }
        val brake = samples.mapNotNull { it.brake }
        binding.statThrottle.text = throttle.averageOrNull()?.let { percentText(it) } ?: "--"
        binding.statBrake.text = brake.averageOrNull()?.let { percentText(it) } ?: "--"
        binding.statFull.text = if (samples.isEmpty()) {
            "--"
        } else {
            val full = samples.count { (it.throttle ?: 0.0) >= 95.0 }
            percentText(full * 100.0 / samples.size)
        }
        binding.statSpeed.text = samples.mapNotNull { it.speed }.maxOrNull()
            ?.let { String.format(Locale.US, "%.0f", it) + " km/h" } ?: "--"

        renderGears(samples)
        renderTyres(samples)
    }

    private fun renderGears(samples: List<com.local.simhubconnect.telemetry.RecordedSample>) {
        binding.gearContainer.removeAllViews()
        val withGear = samples.filter { !it.gear.isNullOrBlank() }
        if (withGear.isEmpty()) return
        withGear.groupingBy { it.gear!!.uppercase(Locale.US) }
            .eachCount()
            .entries
            .sortedBy { gearOrder(it.key) }
            .forEach { entry ->
                val percent = (entry.value * 100.0 / withGear.size).toInt()
                binding.gearContainer.addView(gearRow(entry.key, percent))
            }
    }

    private fun gearRow(gear: String, percent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6f * density).toInt() }
        }
        val label = TextView(this).apply {
            text = gear
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams((34f * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            progress = percent
            trackColor = ContextCompat.getColor(context, R.color.surface_variant)
            setIndicatorColor(themeColor(COLOR_PRIMARY))
            layoutParams = LinearLayout.LayoutParams(0, (6f * density).toInt(), 1f)
        }
        val value = TextView(this).apply {
            text = "$percent%"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams((58f * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(label)
        row.addView(bar)
        row.addView(value)
        return row
    }

    private fun gearOrder(gear: String): Int = when (gear) {
        "R" -> -1
        "N" -> 0
        else -> gear.toIntOrNull() ?: 99
    }

    private fun renderTyres(samples: List<com.local.simhubconnect.telemetry.RecordedSample>) {
        binding.tyreContainer.removeAllViews()
        val tyres = listOf(
            "FL" to samples.mapNotNull { it.tyreFl },
            "FR" to samples.mapNotNull { it.tyreFr },
            "RL" to samples.mapNotNull { it.tyreRl },
            "RR" to samples.mapNotNull { it.tyreRr }
        )
        for ((label, values) in tyres) {
            val average = values.averageOrNull()
            binding.tyreContainer.addView(tyreRow(label, average, values.maxOrNull()))
        }
    }

    private fun tyreRow(label: String, average: Double?, maximum: Double?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8f * density).toInt() }
        }
        val name = TextView(this).apply {
            text = label
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams((34f * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val dot = View(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_tyre_dot)
            backgroundTintList = ColorStateList.valueOf(tyreColor(average))
            layoutParams = LinearLayout.LayoutParams((12f * density).toInt(), (12f * density).toInt()).apply {
                marginEnd = (10f * density).toInt()
            }
        }
        val text = TextView(this).apply {
            text = getString(
                R.string.analysis_tyre_row,
                tempText(average),
                tempText(maximum)
            )
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(name)
        row.addView(dot)
        row.addView(text)
        return row
    }

    private fun tyreColor(temp: Double?): Int = when {
        temp == null -> ContextCompat.getColor(this, R.color.text_secondary)
        temp < 65.0 -> 0xFF38BDF8.toInt()
        temp < 95.0 -> 0xFF35E07A.toInt()
        else -> 0xFFFF4D4D.toInt()
    }

    // -------------------------------------------------------------- lap table

    private fun renderLapTable(result: RecordingAnalysis) {
        binding.lapTable.removeAllViews()
        val best = result.bestLap?.durationMs
        result.laps.forEach { lap ->
            binding.lapTable.addView(lapRow(lap, best))
        }
    }

    private fun lapRow(lap: LapData, bestMs: Long?): View {
        val isBest = lap.durationMs > 0L && lap.durationMs == bestMs
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8f * density).toInt() }
        }
        val name = TextView(this).apply {
            text = getString(R.string.analysis_lap_row, lap.number)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val time = TextView(this).apply {
            text = formatLap(lap.durationMs)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isBest) R.color.text_primary else R.color.text_secondary
                )
            )
            textSize = 14f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        var delta: Long? = null
        if (bestMs != null && bestMs > 0L && lap.durationMs > 0L && !isBest) {
            delta = lap.durationMs - bestMs
        }
        val deltaView = TextView(this).apply {
            text = delta?.let { formatDelta(it) } ?: if (isBest) getString(R.string.analysis_lap_best) else "--"
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        isBest -> R.color.success
                        delta != null -> R.color.warning
                        else -> R.color.text_secondary
                    }
                )
            )
            textSize = 13f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(name)
        row.addView(time)
        row.addView(deltaView)
        return row
    }

    // ---------------------------------------------------------------- online

    private fun searchUrl(): String {
        val track = analysis?.track
        val car = analysis?.car
        val query = listOfNotNull(track, car, getString(R.string.analysis_search_suffix))
            .joinToString(" ")
        return "https://www.google.com/search?q=" + Uri.encode(query)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No browser available: nothing to open.
        }
    }

    // ----------------------------------------------------------------- utils

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun percentText(value: Double): String = String.format(Locale.US, "%.0f%%", value)

    private fun tempText(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f °C", it) } ?: "--"

    private fun wetText(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"

    private fun formatLap(ms: Long?): String {
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

    private fun formatDelta(deltaMs: Long): String =
        String.format(Locale.US, "%+.3f", deltaMs / 1000.0)

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else sum() / size

    companion object {
        private const val EXTRA_PATH = "path"
        private const val TRACK_TITAN_URL = "https://app.tracktitan.io/leaderboards"
        private const val GARAGE61_URL = "https://garage61.net/app"
        private val COLOR_PRIMARY = androidx.appcompat.R.attr.colorPrimary

        fun createIntent(context: Context, file: File): Intent =
            Intent(context, AnalysisActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath)
    }
}
