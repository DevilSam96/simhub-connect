package com.local.simhubconnect

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.local.simhubconnect.databinding.ActivityLiveDashboardBinding
import com.local.simhubconnect.net.ServerStore
import com.local.simhubconnect.settings.AppSettings
import com.local.simhubconnect.telemetry.RecordingState
import com.local.simhubconnect.telemetry.Telemetry
import com.local.simhubconnect.telemetry.TelemetryHub
import com.local.simhubconnect.telemetry.TelemetryStatus
import com.local.simhubconnect.ui.TelemetryDashView
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Shows the five built-in dashboards. They are fed by SimHub's telemetry
 * endpoint, so they stay independent from the web dashboard selected in SimHub.
 */
class LiveDashboardActivity : ThemedActivity() {

    private lateinit var binding: ActivityLiveDashboardBinding
    private lateinit var store: ServerStore

    private var attached = false
    private var latest: Telemetry? = null
    private var status: TelemetryStatus = TelemetryStatus.OFFLINE
    private var selectedStyle = 0

    private val demoDriver = DemoDriver()
    private val demoHandler = Handler(Looper.getMainLooper())
    private var demoRunning = false
    private val demoTick = object : Runnable {
        override fun run() {
            if (!demoRunning) return
            binding.dashView.telemetry = demoDriver.next()
            demoHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ServerStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupStyles(savedInstanceState?.getInt(KEY_TAB) ?: 0)

        val host = store.lastHost
        binding.sourceText.text = if (host != null) {
            getString(R.string.telemetry_source, "$host:${store.lastPort}")
        } else {
            getString(R.string.telemetry_no_server)
        }

        binding.demoButton.addOnCheckedChangeListener { _, checked -> setDemo(checked) }
        binding.demoButtonOverlay.setOnClickListener { binding.demoButton.isChecked = true }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TelemetryHub.status.collect { status = it; render() } }
                launch { TelemetryHub.telemetry.collect { latest = it; render() } }
                launch { TelemetryHub.recording.collect { renderRecording(it) } }
            }
        }
        render()
        renderRecording(TelemetryHub.recording.value)
        applyOrientationFullscreen()
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
        // Re-applies the chip colours: the accent can have changed in settings.
        applyOrientationFullscreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyOrientationFullscreen()
    }

    /** Landscape is the driving view: no title bar and no system bars. */
    private fun applyOrientationFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.toolbar.isVisible = !landscape
        // The footer is only informational: in landscape the dashboard gets
        // the whole screen and the demo toggle stays in the chip row.
        binding.footer.isVisible = !landscape
        styleChips(landscape)
        renderDemoState()
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupStyles(selected: Int) {
        val density = resources.displayMetrics.density
        val entries = listOf(
            TelemetryDashView.Style.GT3 to R.string.dash_gt3,
            TelemetryDashView.Style.FORMULA to R.string.dash_formula,
            TelemetryDashView.Style.RALLY to R.string.dash_rally,
            TelemetryDashView.Style.ENDURANCE to R.string.dash_endurance,
            TelemetryDashView.Style.NEON to R.string.dash_neon
        )
        binding.styleChips.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = entry.first
                text = getString(entry.second)
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = index == selected
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 32f * density
                chipStartPadding = 12f * density
                chipEndPadding = 12f * density
                chipStrokeWidth = density
                textSize = 12f
            }
            binding.styleChips.addView(chip)
        }
        binding.styleChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { id -> group.findViewById<Chip>(id) }
                ?: return@setOnCheckedStateChangeListener
            (chip.tag as? TelemetryDashView.Style)?.let { binding.dashView.style = it }
            selectedStyle = group.indexOfChild(chip)
        }
        val index = selected.coerceIn(0, entries.size - 1)
        selectedStyle = index
        binding.styleChips.getChildAt(index)?.let { binding.styleChips.check(it.id) }
        binding.dashView.style = entries[index].first
    }

    /** Small pill chips in landscape so the dashboard keeps most of the screen. */
    private fun styleChips(landscape: Boolean) {
        val density = resources.displayMetrics.density
        val accent = themeColor(COLOR_PRIMARY)
        val checkedBackground = ColorUtils.setAlphaComponent(accent, 40)
        val background = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checkedBackground, ContextCompat.getColor(this, R.color.surface_variant))
        )
        val stroke = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, ContextCompat.getColor(this, R.color.outline))
        )
        val foreground = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, ContextCompat.getColor(this, R.color.text_secondary))
        )
        // Portrait keeps the five names on one line on a phone; landscape is
        // deliberately smaller because there the dashboard has priority.
        val textSize = if (landscape) 10.5f else 11f
        val minHeight = (if (landscape) 26f else 30f) * density
        val padding = (if (landscape) 9f else 10f) * density
        for (index in 0 until binding.styleChips.childCount) {
            val chip = binding.styleChips.getChildAt(index) as? Chip ?: continue
            chip.textSize = textSize
            chip.chipMinHeight = minHeight
            chip.chipStartPadding = padding
            chip.chipEndPadding = padding
            chip.chipBackgroundColor = background
            chip.chipStrokeColor = stroke
            chip.setTextColor(foreground)
        }
    }

    private fun applyAccent() {
        binding.dashView.accentOverride = AppSettings.dashboardAccentColor(this)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    override fun onStart() {
        super.onStart()
        applyAccent()
        val host = store.lastHost
        if (host != null) {
            TelemetryHub.attach(host, store.lastPort)
            attached = true
        }
        if (binding.demoButton.isChecked) startDemo()
    }

    override fun onStop() {
        stopDemo()
        if (attached) {
            TelemetryHub.detach()
            attached = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        demoHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setDemo(on: Boolean) {
        if (on) startDemo() else stopDemo()
        if (!on) binding.dashView.telemetry = latest
        renderDemoState()
        render()
    }

    private fun renderDemoState() {
        val accent = themeColor(COLOR_PRIMARY)
        binding.demoButton.backgroundTintList = ColorStateList.valueOf(
            if (binding.demoButton.isChecked) {
                ColorUtils.setAlphaComponent(accent, 70)
            } else {
                ContextCompat.getColor(this, R.color.surface_variant)
            }
        )
        binding.demoButton.iconTint = ColorStateList.valueOf(
            if (binding.demoButton.isChecked) accent else ContextCompat.getColor(this, R.color.text_secondary)
        )
    }

    private fun startDemo() {
        stopDemo()
        binding.dashView.demo = true
        demoRunning = true
        demoHandler.post(demoTick)
    }

    private fun stopDemo() {
        demoRunning = false
        binding.dashView.demo = false
        demoHandler.removeCallbacks(demoTick)
    }

    private fun render() {
        if (demoRunning) {
            binding.overlay.isVisible = false
            return
        }
        binding.dashView.telemetry = latest
        binding.overlay.isVisible = latest == null
        when {
            store.lastHost == null ->
                setOverlay(R.string.telemetry_no_server, R.string.telemetry_no_server_detail)
            status == TelemetryStatus.NO_DATA ->
                setOverlay(R.string.telemetry_waiting_title, R.string.telemetry_waiting_detail)
            else ->
                setOverlay(R.string.telemetry_offline_title, R.string.telemetry_offline_detail)
        }
    }

    private fun setOverlay(titleRes: Int, detailRes: Int) {
        binding.overlayTitle.setText(titleRes)
        binding.overlayDetail.setText(detailRes)
    }

    private fun renderRecording(state: RecordingState) {
        binding.recordBadge.isVisible = state.active
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedStyle)
    }

    /** Synthetic data for previewing the dashboards without a running game. */
    private class DemoDriver {
        private val startedAt = System.currentTimeMillis()

        fun next(): Telemetry {
            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            val speed = (215 + 55 * sin(seconds / 5.0) + 14 * sin(seconds * 1.9)).coerceAtLeast(0.0)
            val gear = when {
                speed < 40 -> "1"
                speed < 80 -> "2"
                speed < 120 -> "3"
                speed < 160 -> "4"
                speed < 200 -> "5"
                else -> "6"
            }
            val rpm = (4200 + 4200 * (0.5 + 0.5 * sin(seconds / 1.35))).coerceAtMost(8500.0)
            val throttle = ((sin(seconds / 2.1) + 1.0) * 50.0).coerceIn(0.0, 100.0)
            val brake = if (sin(seconds / 3.3) > 0.75) 72.0 else 6.0
            val lapMs = ((seconds % 92.0) * 1000.0).toLong()

            return Telemetry(
                speedKmh = speed,
                rpm = rpm,
                maxRpm = 8500.0,
                gear = gear,
                throttle = throttle,
                brake = brake,
                tyreFl = 70.0 + 30.0 * sin(seconds / 9.0),
                tyreFr = 78.0 + 16.0 * sin(seconds / 7.0 + 1.0),
                tyreRl = 72.0 + 24.0 * sin(seconds / 8.0 + 2.0),
                tyreRr = 86.0 + 12.0 * sin(seconds / 6.0 + 3.0),
                abs = "6",
                tc = "2",
                brakeBias = 56.5,
                drs = sin(seconds / 5.5) > 0.55,
                ers = 62.0 + 30.0 * sin(seconds / 12.0),
                fuelPerLap = 2.8,
                handbrake = sin(seconds / 11.0) > 0.93,
                surface = "GHIAIA",
                sector = 2,
                fuel = 42.0,
                fuelCapacity = 60.0,
                position = 3,
                totalCars = 20,
                lap = 4,
                totalLaps = 12,
                currentLapMs = lapMs,
                lastLapMs = 92_480L,
                bestLapMs = 91_960L,
                car = "GT3 Demo",
                track = "Demo Circuit"
            )
        }
    }

    private companion object {
        const val KEY_TAB = "tab"

        // With non-transitive R classes the theme attribute lives in AppCompat.
        val COLOR_PRIMARY = androidx.appcompat.R.attr.colorPrimary
    }
}
