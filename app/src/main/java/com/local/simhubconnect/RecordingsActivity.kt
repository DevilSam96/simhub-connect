package com.local.simhubconnect

import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.local.simhubconnect.databinding.ActivityRecordingsBinding
import com.local.simhubconnect.net.ServerStore
import com.local.simhubconnect.telemetry.RecordingFile
import com.local.simhubconnect.telemetry.RecordingState
import com.local.simhubconnect.telemetry.TelemetryHub
import com.local.simhubconnect.telemetry.TelemetryStatus
import com.local.simhubconnect.ui.RecordingsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manages the CSV race recordings produced from SimHub's live telemetry. */
class RecordingsActivity : ThemedActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var store: ServerStore
    private lateinit var adapter: RecordingsAdapter

    private var attached = false
    private var status: TelemetryStatus = TelemetryStatus.OFFLINE
    private var wasRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ServerStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = RecordingsAdapter(
            onOpen = { startActivity(AnalysisActivity.createIntent(this, it.file)) },
            onShare = { share(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.recordButton.setOnClickListener { toggleRecording() }

        val host = store.lastHost
        binding.sourceText.text = if (host != null) {
            getString(R.string.telemetry_source, "$host:${store.lastPort}")
        } else {
            getString(R.string.telemetry_no_server)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TelemetryHub.recording.collect { onRecordingChanged(it) } }
                launch { TelemetryHub.status.collect { status = it; renderStatus() } }
            }
        }
        onRecordingChanged(TelemetryHub.recording.value)
        refreshList()
    }

    override fun onStart() {
        super.onStart()
        val host = store.lastHost
        if (host != null) {
            TelemetryHub.attach(host, store.lastPort)
            attached = true
        }
    }

    override fun onStop() {
        if (attached) {
            TelemetryHub.detach()
            attached = false
        }
        super.onStop()
    }

    private fun toggleRecording() {
        if (TelemetryHub.recording.value.active) {
            TelemetryHub.stopRecording()
            refreshList()
            return
        }
        if (store.lastHost == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.record_no_server)
                .setPositiveButton(R.string.action_close, null)
                .show()
            return
        }
        if (!TelemetryHub.startRecording(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.record_error)
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    private fun onRecordingChanged(state: RecordingState) {
        binding.recordButton.setText(
            if (state.active) R.string.record_stop else R.string.record_start
        )
        binding.recordInfo.text = if (state.active) {
            getString(R.string.record_active, state.samples)
        } else {
            getString(R.string.record_idle)
        }
        if (wasRecording && !state.active) refreshList()
        wasRecording = state.active
        renderStatus()
    }

    private fun renderStatus() {
        binding.statusText.setText(
            when (status) {
                TelemetryStatus.LIVE -> R.string.status_live
                TelemetryStatus.NO_DATA -> R.string.status_waiting
                TelemetryStatus.OFFLINE -> R.string.status_offline
            }
        )
        val active = TelemetryHub.recording.value.active
        binding.recordButton.isEnabled = store.lastHost != null || active
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { TelemetryHub.recordings(this@RecordingsActivity) }
            adapter.submitList(files)
            binding.emptyState.isVisible = files.isEmpty()
            binding.countText.text = getString(R.string.recordings_count, files.size)
        }
    }

    private fun confirmDelete(file: RecordingFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, file.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { TelemetryHub.delete(file.file) }
                    refreshList()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun share(file: RecordingFile) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.files",
                file.file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.action_share)))
        } catch (_: Exception) {
            // The provider is missing or the file disappeared: nothing to share.
        }
    }
}
