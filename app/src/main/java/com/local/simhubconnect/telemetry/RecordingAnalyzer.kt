package com.local.simhubconnect.telemetry

import java.io.File
import kotlin.math.max

/** One CSV row of a telemetry recording. */
data class RecordedSample(
    val timestamp: Long,
    val speed: Double? = null,
    val gear: String? = null,
    val throttle: Double? = null,
    val brake: Double? = null,
    val tyreFl: Double? = null,
    val tyreFr: Double? = null,
    val tyreRl: Double? = null,
    val tyreRr: Double? = null,
    val fuel: Double? = null,
    val lap: Int? = null,
    val currentLapMs: Long? = null,
    val posX: Double? = null,
    val posY: Double? = null,
    val posZ: Double? = null,
    val distancePct: Double? = null,
    val airTemp: Double? = null,
    val trackTemp: Double? = null,
    val wetness: Double? = null
)

/** A point of the reconstructed track, already projected on two axes. */
data class MapPoint(val x: Double, val y: Double, val speed: Double)

data class LapData(
    val number: Int,
    val samples: List<RecordedSample>,
    val durationMs: Long
) {
    val hasDistance: Boolean
        get() = samples.count { it.distancePct != null } >= 4
}

/**
 * Detailed analysis of a recorded session: laps, ideal lap and track map.
 *
 * Files are read by header name, so recordings made before the map columns
 * existed still open (they simply have no map and no ideal lap).
 */
data class RecordingAnalysis(
    val fileName: String,
    val track: String?,
    val car: String?,
    val airTemp: Double?,
    val trackTemp: Double?,
    val wetness: Double?,
    val laps: List<LapData>,
    val bestLap: LapData?,
    val theoreticalBestMs: Long?,
    val trackMap: List<MapPoint>,
    val totalSamples: Int
) {
    val hasTrackMap: Boolean get() = trackMap.size >= 8

    val deltaToIdealMs: Long?
        get() {
            val best = bestLap?.durationMs ?: return null
            val ideal = theoreticalBestMs ?: return null
            return best - ideal
        }
}

object RecordingAnalyzer {

    /** Segments used to build the ideal lap from the driven distance. */
    private const val SEGMENTS = 24

    fun analyze(file: File): RecordingAnalysis? = try {
        analyze(file.name, file.readLines())
    } catch (_: Exception) {
        null
    }

    fun analyze(fileName: String, lines: List<String>): RecordingAnalysis? {
        if (lines.size < 2) return null
        val header = splitCsv(lines.first()).map { it.trim().lowercase() }
        if ("timestamp_ms" !in header) return null
        val columns = header.withIndex().associate { it.value to it.index }

        val tsCol = columns["timestamp_ms"] ?: return null
        val samples = ArrayList<RecordedSample>(lines.size)
        var track: String? = null
        var car: String? = null
        for (index in 1 until lines.size) {
            val cells = splitCsv(lines[index])
            val timestamp = cells.getOrNull(tsCol)?.trim()?.toLongOrNull() ?: continue
            if (track == null) track = cells.text(columns["track"])?.takeIf { it.isNotBlank() }
            if (car == null) car = cells.text(columns["car"])?.takeIf { it.isNotBlank() }
            samples += RecordedSample(
                timestamp = timestamp,
                speed = cells.number(columns["speed_kmh"]),
                gear = cells.text(columns["gear"]),
                throttle = cells.number(columns["throttle_pct"]),
                brake = cells.number(columns["brake_pct"]),
                tyreFl = cells.number(columns["tyre_fl_c"]),
                tyreFr = cells.number(columns["tyre_fr_c"]),
                tyreRl = cells.number(columns["tyre_rl_c"]),
                tyreRr = cells.number(columns["tyre_rr_c"]),
                fuel = cells.number(columns["fuel_l"]),
                lap = cells.number(columns["lap"])?.toInt(),
                currentLapMs = cells.number(columns["current_lap_ms"])?.toLong(),
                posX = cells.number(columns["pos_x"]),
                posY = cells.number(columns["pos_y"]),
                posZ = cells.number(columns["pos_z"]),
                distancePct = cells.number(columns["lap_dist_pct"])?.takeIf { it in 0.0..1.05 },
                airTemp = cells.number(columns["air_c"]),
                trackTemp = cells.number(columns["road_c"]),
                wetness = cells.number(columns["wetness_pct"])
            )
        }
        if (samples.size < 2) return null

        val hasLapMarker = samples.count { it.lap != null } >= samples.size / 2
        val hasDistance = samples.count { it.distancePct != null } >= 8
        val groups = if (hasLapMarker || hasDistance) {
            groupLaps(samples, hasLapMarker)
        } else {
            listOf(samples)
        }

        val laps = groups.mapIndexed { index, group ->
            LapData(
                number = group.firstNotNullOfOrNull { it.lap } ?: (index + 1),
                samples = group,
                durationMs = lapDuration(group)
            )
        }.filter { it.samples.size >= 3 }
        if (laps.isEmpty()) return null

        val timedLaps = laps.filter { it.durationMs > 0L && lapCoversTrack(it) }
            .ifEmpty { laps.filter { it.durationMs > 0L } }
        val bestLap = timedLaps.minByOrNull { it.durationMs }

        return RecordingAnalysis(
            fileName = fileName,
            track = track,
            car = car,
            airTemp = medianTemp(samples.mapNotNull { it.airTemp }),
            trackTemp = medianTemp(samples.mapNotNull { it.trackTemp }),
            wetness = medianTemp(samples.mapNotNull { it.wetness }),
            laps = laps,
            bestLap = bestLap,
            theoreticalBestMs = theoreticalBest(timedLaps),
            trackMap = buildTrackMap(bestLap),
            totalSamples = samples.size
        )
    }

    // ------------------------------------------------------------------ laps

    private fun groupLaps(samples: List<RecordedSample>, byLapCounter: Boolean): List<List<RecordedSample>> {
        if (byLapCounter) {
            val groups = mutableListOf<MutableList<RecordedSample>>()
            var current: Int? = null
            for (sample in samples) {
                val lap = sample.lap
                if (lap == null) {
                    groups.lastOrNull()?.add(sample)
                    continue
                }
                if (current == null || lap != current) {
                    current = lap
                    groups += mutableListOf(sample)
                } else {
                    groups.last().add(sample)
                }
            }
            return groups.filter { it.size >= 3 }
        }

        // No lap counter: a wrap of the lap distance starts a new lap.
        val withDistance = samples.filter { it.distancePct != null }
        if (withDistance.size < 40) return listOf(samples)
        val groups = mutableListOf<MutableList<RecordedSample>>()
        var group = mutableListOf<RecordedSample>()
        var previous = 0.0
        var started = false
        for (sample in withDistance) {
            val pct = sample.distancePct ?: continue
            if (started && pct + 0.2 < previous) {
                groups += group
                group = mutableListOf()
            }
            started = true
            group.add(sample)
            previous = pct
        }
        if (group.size >= 3) groups += group
        return groups.filter { it.size >= 3 }.ifEmpty { listOf(withDistance) }
    }

    /**
     * The game publishes the running lap time: its maximum inside the group is
     * the lap time. Recording gaps fall back to the sample timestamps.
     */
    private fun lapDuration(group: List<RecordedSample>): Long {
        val game = group.mapNotNull { it.currentLapMs }.maxOrNull() ?: 0L
        val measured = group.last().timestamp - group.first().timestamp
        return when {
            game > 2_000L -> game
            measured > 2_000L -> measured
            else -> 0L
        }
    }

    /** A lap is complete when the recorded distance covers most of the track. */
    private fun lapCoversTrack(lap: LapData): Boolean {
        val pcts = lap.samples.mapNotNull { it.distancePct }
        if (pcts.size < 4) return true
        return (pcts.max() - pcts.min()) >= 0.80
    }

    // ------------------------------------------------------------ ideal lap

    /**
     * Ideal lap: the distance is split in [SEGMENTS] and, for every segment, the
     * fastest time spent there by any recorded lap is taken. Summing them gives
     * the lap the driver could have driven with the same conditions.
     */
    private fun theoreticalBest(laps: List<LapData>): Long? {
        val withDistance = laps.filter { it.hasDistance }
        if (withDistance.size < 2) return null
        val perLap = withDistance.mapNotNull { segmentTimes(it) }
        if (perLap.size < 2) return null
        var total = 0L
        for (segment in 0 until SEGMENTS) {
            val best = perLap.mapNotNull { it[segment] }.minOrNull() ?: return null
            total += best
        }
        return total.takeIf { it > 0L }
    }

    private fun segmentTimes(lap: LapData): List<Long?>? {
        val times = LongArray(SEGMENTS)
        val covered = BooleanArray(SEGMENTS)
        var previous: RecordedSample? = null
        for (sample in lap.samples) {
            val before = previous
            previous = sample
            val pct = sample.distancePct ?: continue
            if (before == null) continue
            val delta = sample.timestamp - before.timestamp
            if (delta !in 1L..600L) continue
            val segment = (pct * SEGMENTS).toInt().coerceIn(0, SEGMENTS - 1)
            times[segment] += delta
            covered[segment] = true
        }
        if (covered.count { it } < (SEGMENTS * 0.7).toInt()) return null
        return List(SEGMENTS) { index -> if (covered[index]) times[index] else null }
    }

    // ------------------------------------------------------------- track map

    /**
     * Rebuilds the track from the recorded world coordinates. Simulators expose
     * the up axis differently, so the pair of axes with the widest spread wins.
     */
    private fun buildTrackMap(lap: LapData?): List<MapPoint> {
        if (lap == null) return emptyList()
        val points = ArrayList<MapPoint>(lap.samples.size)
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (sample in lap.samples) {
            val x = sample.posX ?: continue
            val y = sample.posY ?: continue
            val z = sample.posZ ?: y
            val speed = sample.speed ?: 0.0
            points += MapPoint(x, y, speed)
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
            sx = maxX - minX
            sy = maxY - minY
            sz = maxZ - minZ
        }
        if (points.size < 8) return emptyList()

        // Vertical axis (small spread) must not be used as a map axis.
        val useZ = sz > 0.0 && sz >= max(sx, sy) * 0.4
        if (!useZ) return points
        val result = ArrayList<MapPoint>(points.size)
        for (sample in lap.samples) {
            val x = sample.posX ?: continue
            val z = sample.posZ ?: continue
            result += MapPoint(x, z, sample.speed ?: 0.0)
        }
        return if (result.size >= 8) result else points
    }

    // ----------------------------------------------------------------- utils

    private fun medianTemp(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        out += current.toString()
        return out
    }

    private fun List<String>.number(column: Int?): Double? {
        if (column == null) return null
        val value = getOrNull(column)?.trim() ?: return null
        if (value.isEmpty()) return null
        return value.replace(',', '.').toDoubleOrNull()
    }

    private fun List<String>.text(column: Int?): String? {
        if (column == null) return null
        return getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
