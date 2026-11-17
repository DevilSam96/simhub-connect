package com.local.simhubconnect

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.simhubconnect.databinding.ActivityMainBinding
import com.local.simhubconnect.databinding.DialogManualIpBinding
import com.local.simhubconnect.databinding.DialogPortBinding
import com.local.simhubconnect.databinding.ViewActionTileBinding
import com.local.simhubconnect.net.FoundServer
import com.local.simhubconnect.net.LocalNetwork
import com.local.simhubconnect.net.ServerStore
import com.local.simhubconnect.net.SimHubProbe
import com.local.simhubconnect.net.SimHubScanner
import com.local.simhubconnect.ui.ServerAdapter
import com.local.simhubconnect.ui.ServerRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scans the local Wi-Fi/Ethernet network for SimHub's Dash Studio web server,
 * lists what it finds and opens a dashboard in a fullscreen native shell.
 */
class MainActivity : ThemedActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ServerStore
    private val probe = SimHubProbe()
    private val scanner = SimHubScanner(probe)
    private val foundServers = mutableListOf<FoundServer>()
    private val adapter = ServerAdapter { row -> openDashboard(row.server) }
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private var scanRunning = false
    private var lastScanElapsedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ServerStore(this)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // The gear is a vector with a fixed colour: give it the theme tint so
        // it stays visible on the light toolbar too.
        binding.toolbar.menu.findItem(R.id.action_settings)?.icon?.mutate()?.setTint(
            ContextCompat.getColor(this, R.color.text_primary)
        )
        binding.scanButton.setOnClickListener { startScan() }
        binding.manualButton.setOnClickListener { showManualDialog() }
        bindTile(
            binding.liveTile,
            R.drawable.ic_speed,
            R.string.tile_live_title,
            R.string.tile_live_sub
        ) {
            rememberDiscoveredServer()
            startActivity(Intent(this, LiveDashboardActivity::class.java))
        }
        bindTile(
            binding.recordingsTile,
            R.drawable.ic_camera,
            R.string.tile_recordings_title,
            R.string.tile_recordings_sub
        ) {
            if (rememberDiscoveredServer()) {
                startActivity(Intent(this, RecordingsActivity::class.java))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.telemetry_no_server)
                    .setMessage(R.string.record_no_server)
                    .setPositiveButton(R.string.scan_again) { _, _ -> startScan() }
                    .setNegativeButton(R.string.action_close, null)
                    .show()
            }
        }
        bindTile(
            binding.webTile,
            R.drawable.ic_globe,
            R.string.tile_web_title,
            R.string.tile_web_sub
        ) { openWebDashboard() }
        bindTile(
            binding.settingsTile,
            R.drawable.ic_gear,
            R.string.tile_settings_title,
            R.string.tile_settings_sub
        ) { openSettings() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    openSettings()
                    true
                }
                R.id.action_port -> {
                    showPortDialog()
                    true
                }
                R.id.action_help -> {
                    showInfoDialog(R.string.help_title, R.string.help_message, null)
                    true
                }
                R.id.action_about -> {
                    showInfoDialog(R.string.about_title, R.string.about_message, BuildConfig.VERSION_NAME)
                    true
                }
                else -> false
            }
        }

        startScan()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) renderRows()
    }

    override fun onDestroy() {
        scanGeneration++
        scanRunning = false
        scanJob?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- scanning

    private fun startScan() {
        scanJob?.cancel()
        scanRunning = false
        val generation = ++scanGeneration
        val network = LocalNetwork.current(this)
        if (network == null) {
            foundServers.clear()
            renderRows()
            binding.toolbar.subtitle = getString(R.string.subtitle_offline)
            renderNoNetwork()
            return
        }

        binding.toolbar.subtitle = getString(
            R.string.subtitle_network,
            network.ipAddress,
            "${network.networkAddress}/${network.scanPrefixLength}"
        )
        foundServers.clear()
        renderRows()

        val port = store.lastPort
        val hosts = network.hosts()
        scanRunning = true
        renderScanning(0, hosts.size)
        val startedAt = SystemClock.elapsedRealtime()

        scanJob = lifecycleScope.launch {
            try {
                scanner.scan(
                    hosts = hosts,
                    port = port,
                    onProgress = { scanned, total ->
                        runOnUiThread {
                            // Late updates queued by the network threads must not
                            // revive the progress bar after the final state.
                            if (generation == scanGeneration && scanRunning) {
                                renderScanning(scanned, total)
                            }
                        }
                    },
                    onFound = { server ->
                        runOnUiThread {
                            if (generation != scanGeneration) return@runOnUiThread
                            foundServers.add(server)
                            renderRows()
                            // A server found by the very last probes can arrive
                            // after the summary: recompute it instead of dropping it.
                            if (!scanRunning) renderScanFinished(lastScanElapsedMs)
                        }
                    }
                )
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                runOnUiThread {
                    if (generation == scanGeneration && !isFinishing && !isDestroyed) {
                        scanRunning = false
                        lastScanElapsedMs = elapsed
                        renderScanFinished(elapsed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runOnUiThread {
                    if (generation == scanGeneration) {
                        scanRunning = false
                        renderError(error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun renderScanning(scanned: Int, total: Int) {
        // Determinate while scanning: the bar fills with real progress and can
        // never look like it is still spinning once every address is done.
        binding.statusProgress.isIndeterminate = false
        binding.statusProgress.max = total.coerceAtLeast(1)
        binding.statusProgress.setProgressCompat(scanned, false)
        setStatus(
            R.drawable.ic_wifi,
            R.color.accent_soft,
            getString(R.string.status_scanning_title),
            getString(R.string.status_scanning_detail, scanned, total, store.lastPort),
            loading = true
        )
    }

    private fun renderScanFinished(elapsedMs: Long) {
        binding.toolbar.subtitle = getString(R.string.subtitle_scanned, elapsedMs)
        val simhub = foundServers.filter { it.simhub }
        when {
            simhub.isNotEmpty() -> {
                val fastest = simhub.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                val detail = if (simhub.size == 1 && fastest != null) {
                    getString(R.string.status_found_detail_one, fastest.address, fastest.latencyMs ?: 0L)
                } else {
                    getString(R.string.status_found_detail_many, simhub.size)
                }
                setStatus(
                    R.drawable.ic_check_circle,
                    R.color.success,
                    getString(R.string.status_found_title),
                    detail,
                    loading = false
                )
            }
            foundServers.isNotEmpty() -> setStatus(
                R.drawable.ic_warning,
                R.color.warning,
                getString(R.string.status_http_title),
                getString(R.string.status_http_detail, store.lastPort),
                loading = false
            )
            else -> setStatus(
                R.drawable.ic_error,
                R.color.error,
                getString(R.string.status_empty_title),
                getString(R.string.status_empty_detail, store.lastPort),
                loading = false
            )
        }
    }

    private fun renderNoNetwork() = setStatus(
        R.drawable.ic_wifi,
        R.color.warning,
        getString(R.string.status_no_network_title),
        getString(R.string.status_no_network_detail),
        loading = false
    )

    private fun renderError(message: String) = setStatus(
        R.drawable.ic_error,
        R.color.error,
        getString(R.string.status_error_title),
        getString(R.string.status_error_detail, message),
        loading = false
    )

    private fun renderIdle() = setStatus(
        R.drawable.ic_wifi,
        R.color.text_secondary,
        getString(R.string.status_idle_title),
        getString(R.string.status_idle_detail),
        loading = false
    )

    private fun setStatus(
        iconRes: Int,
        colorRes: Int,
        title: String,
        detail: String,
        loading: Boolean
    ) {
        binding.statusIcon.setImageResource(iconRes)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )
        binding.statusTitle.text = title
        binding.statusDetail.text = detail
        binding.statusProgress.isVisible = loading
    }

    // ------------------------------------------------------------------- rows

    private fun renderRows() {
        val lastHost = store.lastHost
        val lastPort = store.lastPort
        val rows = mutableListOf<ServerRow>()
        if (lastHost != null) {
            val known = foundServers.firstOrNull { it.host == lastHost && it.port == lastPort }
            rows.add(
                ServerRow(
                    known ?: FoundServer(lastHost, lastPort, null, simhub = false),
                    isLastUsed = true
                )
            )
        }
        foundServers
            .filterNot { it.host == lastHost && it.port == lastPort }
            .sortedBy { it.latencyMs ?: Long.MAX_VALUE }
            .forEach { rows.add(ServerRow(it, isLastUsed = false)) }

        adapter.submitList(rows)
        binding.emptyState.isVisible = rows.isEmpty()
        binding.serverCount.isVisible = rows.isNotEmpty()
        binding.serverCount.text = rows.size.toString()
    }

    private fun openDashboard(server: FoundServer) {
        store.rememberServer(server.host, server.port)
        startActivity(DashboardActivity.createIntent(this, server.host, server.port))
    }

    /** Opens SimHub's web dashboard for the last known (or just found) server. */
    private fun openWebDashboard() {
        val discovered = foundServers.firstOrNull { it.simhub } ?: foundServers.firstOrNull()
        val host = discovered?.host ?: store.lastHost
        val port = discovered?.port ?: store.lastPort
        if (host == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.telemetry_no_server)
                .setMessage(R.string.web_no_server)
                .setPositiveButton(R.string.scan_again) { _, _ -> startScan() }
                .setNegativeButton(R.string.manual_ip) { _, _ -> showManualDialog() }
                .show()
            return
        }
        store.rememberServer(host, port)
        startActivity(DashboardActivity.createIntent(this, host, port))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun bindTile(
        tile: ViewActionTileBinding,
        iconRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        onClick: () -> Unit
    ) {
        tile.tileIcon.setImageResource(iconRes)
        tile.tileTitle.setText(titleRes)
        tile.tileSubtitle.setText(subtitleRes)
        tile.root.setOnClickListener { onClick() }
    }

    /**
     * Native telemetry needs a server too: use the SimHub found by the scan,
     * so the sections work without opening a web dashboard first.
     */
    private fun rememberDiscoveredServer(): Boolean {
        val discovered = foundServers.firstOrNull { it.simhub } ?: foundServers.firstOrNull()
        val host = discovered?.host ?: store.lastHost ?: return false
        val port = discovered?.port ?: store.lastPort
        store.rememberServer(host, port)
        return true
    }

    // ---------------------------------------------------------------- dialogs

    private fun showManualDialog() {
        val dialogBinding = DialogManualIpBinding.inflate(layoutInflater)
        dialogBinding.inputHost.setText(store.lastHost ?: "")
        dialogBinding.inputPort.setText(store.lastPort.toString())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_connect, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = dialogBinding.inputHost.text?.toString().orEmpty()
                    .trim()
                    .removePrefix("http://")
                    .removeSuffix("/")
                val port = dialogBinding.inputPort.text?.toString()?.trim()?.toIntOrNull()

                dialogBinding.hostLayout.error = null
                dialogBinding.portLayout.error = null

                if (host.isEmpty() || !host.matches(HOST_PATTERN)) {
                    dialogBinding.hostLayout.error = getString(R.string.manual_invalid_ip)
                    return@setOnClickListener
                }
                if (port == null || port !in 1..65535) {
                    dialogBinding.portLayout.error = getString(R.string.manual_invalid_port)
                    return@setOnClickListener
                }
                dialog.dismiss()
                connectManually(host, port)
            }
        }
        dialog.show()
    }

    private fun connectManually(host: String, port: Int) {
        store.lastPort = port
        setStatus(
            R.drawable.ic_wifi,
            R.color.accent_soft,
            getString(R.string.status_verifying_title, "$host:$port"),
            getString(R.string.status_verifying_detail),
            loading = true
        )
        lifecycleScope.launch {
            val server = withContext(Dispatchers.IO) { probe.probe(host, port) }
            if (isFinishing || isDestroyed) return@launch
            if (server != null) {
                openDashboard(server)
            } else {
                renderIdle()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.connect_failed_title)
                    .setMessage(getString(R.string.connect_failed_detail, host, port))
                    .setPositiveButton(R.string.action_retry) { _, _ -> connectManually(host, port) }
                    .setNegativeButton(R.string.action_close, null)
                    .show()
            }
        }
    }

    private fun showPortDialog() {
        val dialogBinding = DialogPortBinding.inflate(layoutInflater)
        dialogBinding.inputPort.setText(store.lastPort.toString())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.port_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val port = dialogBinding.inputPort.text?.toString()?.trim()?.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    dialogBinding.portLayout.error = getString(R.string.port_invalid)
                    return@setOnClickListener
                }
                dialogBinding.portLayout.error = null
                store.lastPort = port
                dialog.dismiss()
                startScan()
            }
        }
        dialog.show()
    }

    private fun showInfoDialog(titleRes: Int, messageRes: Int, argument: String?) {
        val message = if (argument != null) getString(messageRes, argument) else getString(messageRes)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    companion object {
        private val HOST_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9.\\-]{0,252}$")
    }
}
