package com.local.simhubconnect.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RecordingAnalysisTest {

    private val header =
        "timestamp_ms,speed_kmh,rpm,gear,throttle_pct,brake_pct," +
            "tyre_fl_c,tyre_fr_c,tyre_rl_c,tyre_rr_c,fuel_l,position,lap," +
            "current_lap_ms,last_lap_ms,best_lap_ms,car,track," +
            "pos_x,pos_y,pos_z,lap_dist_pct,air_c,road_c,wetness_pct"

    @Test
    fun buildsLapsIdealLapTrackMapAndConditions() {
        val rows = mutableListOf<String>()
        var timestamp = 0L
        val stepByLap = listOf(200L, 210L, 205L)
        for (lap in 1..3) {
            val step = stepByLap[lap - 1]
            for (index in 0 until 120) {
                val pct = index / 119.0
                val angle = pct * 2.0 * Math.PI
                val speed = 100.0 + 80.0 * abs(sin(angle))
                val cells = listOf(
                    timestamp.toString(),
                    String.format(Locale.US, "%.2f", speed),
                    "6000",
                    ((pct * 6).toInt() + 1).toString(),
                    "80.0",
                    if (pct in 0.40..0.50) "90.0" else "0.0",
                    "80.0", "81.0", "79.0", "79.5",
                    "40.0",
                    "3",
                    lap.toString(),
                    "",
                    "",
                    "",
                    "Ferrari 488 GT3",
                    "Monza",
                    String.format(Locale.US, "%.3f", 500 * cos(angle)),
                    "0.500",
                    String.format(Locale.US, "%.3f", 500 * sin(angle)),
                    String.format(Locale.US, "%.4f", pct),
                    "24.0",
                    "31.0",
                    "0.0"
                )
                rows += cells.joinToString(",")
                timestamp += step
            }
        }

        val analysis = RecordingAnalyzer.analyze("test.csv", listOf(header) + rows)
        assertNotNull(analysis)
        requireNotNull(analysis)

        assertEquals(3, analysis.laps.size)
        assertEquals("Monza", analysis.track)
        assertEquals("Ferrari 488 GT3", analysis.car)
        assertEquals(24.0, analysis.airTemp!!, 0.001)
        assertEquals(31.0, analysis.trackTemp!!, 0.001)
        assertEquals(0.0, analysis.wetness!!, 0.001)
        assertEquals(360, analysis.totalSamples)

        val best = analysis.bestLap
        assertNotNull(best)
        requireNotNull(best)
        assertEquals(1, best.number)
        assertTrue(analysis.hasTrackMap)

        val ideal = analysis.theoreticalBestMs
        assertNotNull(ideal)
        requireNotNull(ideal)
        assertTrue(ideal <= best.durationMs)
        assertTrue(ideal > 0L)
        assertTrue((analysis.deltaToIdealMs ?: -1L) >= 0L)
    }

    @Test
    fun readsRecordingsMadeBeforeTheMapColumns() {
        val legacyHeader =
            "timestamp_ms,speed_kmh,rpm,gear,throttle_pct,brake_pct," +
                "tyre_fl_c,tyre_fr_c,tyre_rl_c,tyre_rr_c,fuel_l,position,lap," +
                "current_lap_ms,last_lap_ms,best_lap_ms,car,track"
        val rows = mutableListOf<String>()
        var timestamp = 0L
        for (lap in 1..2) {
            for (index in 0 until 6) {
                val lapMs = 80_000L + (lap - 1) * 1_000L + index * 200L
                rows += listOf(
                    timestamp.toString(),
                    "120.0",
                    "6000",
                    "3",
                    "70.0",
                    "0.0",
                    "80.0", "81.0", "79.0", "79.5",
                    "30.0",
                    "2",
                    lap.toString(),
                    lapMs.toString(),
                    "",
                    "",
                    "GT3",
                    "Spa"
                ).joinToString(",")
                timestamp += 200L
            }
        }

        val analysis = RecordingAnalyzer.analyze("legacy.csv", listOf(legacyHeader) + rows)
        assertNotNull(analysis)
        requireNotNull(analysis)
        assertEquals("Spa", analysis.track)
        assertEquals(2, analysis.laps.size)
        assertNotNull(analysis.bestLap)
        assertNull(analysis.theoreticalBestMs)
        assertTrue(analysis.trackMap.isEmpty())
        assertNull(analysis.wetness)
        assertNull(analysis.airTemp)
    }

    @Test
    fun rejectsFilesWithoutHeader() {
        assertNull(RecordingAnalyzer.analyze("broken.csv", listOf("not,a,csv", "1,2,3")))
    }
}
