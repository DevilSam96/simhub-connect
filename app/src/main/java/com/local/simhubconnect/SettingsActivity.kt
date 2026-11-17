package com.local.simhubconnect

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.local.simhubconnect.databinding.ActivitySettingsBinding
import com.local.simhubconnect.settings.AppSettings

/** Theme, app accent and dashboard accent. */
class SettingsActivity : ThemedActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        buildThemeChips()
        renderSwatches(binding.appAccentRow, AppSettings.appAccent(this), onPick = { index ->
            AppSettings.setAppAccent(this, index)
            recreate()
        })
        renderSwatches(
            binding.dashboardAccentRow,
            AppSettings.dashboardAccent(this),
            onPick = { index ->
                AppSettings.setDashboardAccent(this, index)
                renderSwatches(binding.dashboardAccentRow, index, onPick = {})
            }
        )
    }

    private fun buildThemeChips() {
        val density = resources.displayMetrics.density
        val current = AppSettings.themeMode(this)
        val modes = listOf(
            AppSettings.MODE_DARK to R.string.theme_dark,
            AppSettings.MODE_LIGHT to R.string.theme_light,
            AppSettings.MODE_SYSTEM to R.string.theme_system
        )
        binding.themeChips.removeAllViews()
        modes.forEach { (mode, label) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(label)
                tag = mode
                isCheckable = true
                isChecked = mode == current
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 38f * density
                textSize = 13f
                isChecked = mode == current
            }
            binding.themeChips.addView(chip)
        }
        binding.themeChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { id -> group.findViewById<Chip>(id) }
                ?: return@setOnCheckedStateChangeListener
            val mode = chip.tag as? Int ?: return@setOnCheckedStateChangeListener
            if (mode == AppSettings.themeMode(this)) return@setOnCheckedStateChangeListener
            AppSettings.setThemeMode(this, mode)
            AppSettings.applyNightMode(this)
        }
    }

    private fun renderSwatches(container: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val size = (46f * density).toInt()
        val ring = ContextCompat.getColor(this, R.color.text_primary)
        container.removeAllViews()
        AppSettings.ACCENTS.forEachIndexed { index, accent ->
            val isSelected = index == selected
            val button = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (10f * density).toInt()
                }
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minHeight = 0
                gravity = Gravity.CENTER
                cornerRadius = size / 2
                backgroundTintList = ColorStateList.valueOf(accent.color)
                strokeWidth = if (isSelected) (3f * density).toInt() else 0
                strokeColor = ColorStateList.valueOf(ring)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                iconSize = (22f * density).toInt()
                icon = if (isSelected) {
                    ContextCompat.getDrawable(this@SettingsActivity, R.drawable.ic_check)
                } else {
                    null
                }
                iconTint = ColorStateList.valueOf(
                    if (ColorUtils.calculateLuminance(accent.color) > 0.55) Color.BLACK else Color.WHITE
                )
                contentDescription = accent.label
                setOnClickListener { onPick(index) }
            }
            container.addView(button)
        }
    }
}
