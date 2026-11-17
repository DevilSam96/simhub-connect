package com.local.simhubconnect.telemetry

import android.content.Context
import com.local.simhubconnect.net.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingState(
    val active: Boolean = false,
    val file: File? = null,
    val samples: Long = 0L,
    val startedAt: Long = 0L
)

data class RecordingFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

/**
 * Single owner of the SimHub telemetry poll and of the race recording.
 *
 * Screens attach/detach while visible; polling keeps running when a recording
 * is active so leaving the dashboard does not interrupt the log.
 */
object TelemetryHub {

    private const val POLL_INTERVAL_MS = 200L
    private const val RETRY_INTERVAL_MS = 1000L
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1500
    private const val MAX_BODY_CHARS = 1_000_000

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _status = MutableStateFlow(TelemetryStatus.OFFLINE)
    val status: StateFlow<TelemetryStatus> = _status.asStateFlow()

    private val _recording = MutableStateFlow(RecordingState())
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    private var host: String? = null
    private var port: Int = ServerStore.DEFAULT_PORT
    private var attachments = 0

    private var pollJob: Job? = null
    private var pollHost: String? = null
    private var pollPort: Int = 0

    private var writer: FileWriter? = null
    private var recordingFile: File? = null
    private var samples = 0L

    // ------------------------------------------------------------------- API

    fun attach(host: String, port: Int) {
        synchronized(lock) {
            this.host = host
            this.port = port
            attachments++
            if (pollHost != host || pollPort != port) {
                pollJob?.cancel()
                pollJob = null
                _telemetry.value = null
                _status.value = TelemetryStatus.OFFLINE
            }
            ensurePollingLocked()
        }
    }

    fun detach() {
        synchronized(lock) {
            if (attachments > 0) attachments--
            if (attachments == 0 && !_recording.value.active) stopPollingLocked()
        }
    }

    fun startRecording(context: Context): Boolean {
        var started = true
        synchronized(lock) {
            if (!_recording.value.active) {
                started = false
                val directory = recordingsDir(context)
                if (directory.exists() || directory.mkdirs()) {
                    val file = File(directory, "telemetria-${FILE_DATE.format(Date())}.csv")
                    try {
                        val output = FileWriter(file, false)
                        output.write("$CSV_HEADER\n")
                        output.flush()
                        writer = output
                        recordingFile = file
                        samples = 0L
                        _recording.value = RecordingState(true, file, 0L, System.currentTimeMillis())
                        ensurePollingLocked()
                        started = true
                    } catch (_: Exception) {
                        started = false
                    }
                }
            }
        }
        return started
    }

    fun stopRecording() {
        synchronized(lock) {
            closeWriter()
            _recording.value = RecordingState()
            recordingFile = null
            if (attachments == 0) stopPollingLocked()
        }
    }

    fun recordings(context: Context): List<RecordingFile> {
        val activeFile = synchronized(lock) { recordingFile }
        val files = recordingsDir(context).listFiles { file ->
            file.isFile && file.name.endsWith(".csv") && file.name != activeFile?.name
        } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .map { RecordingFile(it, displayName(it), it.length(), it.lastModified()) }
    }

    fun delete(file: File): Boolean {
        var deleted = false
        synchronized(lock) {
            if (file.name != recordingFile?.name) deleted = file.delete()
        }
        return deleted
    }

    // --------------------------------------------------------------- polling

    private fun ensurePollingLocked() {
        if (pollJob?.isActive == true) return
        val server = host ?: return
        pollHost = server
        pollPort = port
        val polledPort = port
        pollJob = scope.launch { pollLoop(server, polledPort) }
    }

    private fun stopPollingLocked() {
        pollJob?.cancel()
        pollJob = null
        pollHost = null
        _telemetry.value = null
        _status.value = TelemetryStatus.OFFLINE
    }

    private suspend fun pollLoop(host: String, port: Int) {
        while (currentCoroutineContext().isActive) {
            val body = fetch("http://$host:$port/api/GetGamedata")
            val parsed = body?.let { TelemetryParser.parse(it) }
            val live = parsed != null && parsed.hasCoreData
            _telemetry.value = if (live) parsed else null
            _status.value = when {
                live -> TelemetryStatus.LIVE
                parsed != null -> TelemetryStatus.NO_DATA
                else -> TelemetryStatus.OFFLINE
            }
            if (live && parsed != null) appendSample(parsed, System.currentTimeMillis())
            delay(if (live) POLL_INTERVAL_MS else RETRY_INTERVAL_MS)
        }
    }

    private fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SimHubConnect/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { reader ->
                // A single read() can stop mid-body: the JSON must be read whole.
                val builder = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (builder.length < MAX_BODY_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ------------------------------------------------------------- recording

    private fun appendSample(data: Telemetry, timestamp: Long) {
        synchronized(lock) {
            val output = writer
            if (output == null) return
            try {
                output.write(csvRow(data, timestamp))
                output.write("\n")
                samples++
                if (samples % 5L == 0L) output.flush()
                _recording.value = _recording.value.copy(samples = samples)
            } catch (_: Exception) {
                closeWriter()
                _recording.value = RecordingState()
                recordingFile = null
            }
        }
    }

    private fun closeWriter() {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    private fun csvRow(data: Telemetry, timestamp: Long): String = listOf(
        timestamp.toString(),
        number(data.speedKmh),
        number(data.rpm),
        text(data.gear),
        number(data.throttle),
        number(data.brake),
        number(data.tyreFl),
        number(data.tyreFr),
        number(data.tyreRl),
        number(data.tyreRr),
        number(data.fuel),
        data.position?.toString().orEmpty(),
        data.lap?.toString().orEmpty(),
        data.currentLapMs?.toString().orEmpty(),
        data.lastLapMs?.toString().orEmpty(),
        data.bestLapMs?.toString().orEmpty(),
        text(data.car),
        text(data.track),
        coordinate(data.posX),
        coordinate(data.posY),
        coordinate(data.posZ),
        number(data.lapDistPct),
        number(data.airTemp),
        number(data.trackTemp),
        number(data.wetness)
    ).joinToString(",")

    private fun coordinate(value: Double?): String =
        value?.let { String.format(Locale.US, "%.3f", it) } ?: ""

    private fun number(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: ""

    private fun text(value: String?): String {
        val clean = value?.replace('\n', ' ')?.replace('\r', ' ') ?: return ""
        return if (clean.contains(',') || clean.contains('"')) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
    }

    // ----------------------------------------------------------------- files

    private fun recordingsDir(context: Context): File = File(context.filesDir, "telemetry")

    private fun displayName(file: File): String {
        val raw = file.nameWithoutExtension.removePrefix("telemetria-")
        return try {
            val date = FILE_DATE.parse(raw)
            if (date != null) DISPLAY_DATE.format(date) else raw
        } catch (_: Exception) {
            raw
        }
    }

    private val FILE_DATE = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val DISPLAY_DATE = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    private const val CSV_HEADER =
        "timestamp_ms,speed_kmh,rpm,gear,throttle_pct,brake_pct," +
            "tyre_fl_c,tyre_fr_c,tyre_rl_c,tyre_rr_c,fuel_l,position,lap," +
            "current_lap_ms,last_lap_ms,best_lap_ms,car,track," +
            "pos_x,pos_y,pos_z,lap_dist_pct,air_c,road_c,wetness_pct"
}
