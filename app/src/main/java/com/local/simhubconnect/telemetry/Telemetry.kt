package com.local.simhubconnect.telemetry

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class TelemetryStatus { OFFLINE, NO_DATA, LIVE }

/** Normalised snapshot of the data SimHub publishes for its dashboards. */
data class Telemetry(
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val maxRpm: Double? = null,
    val gear: String? = null,
    /** 0..100 */
    val throttle: Double? = null,
    /** 0..100 */
    val brake: Double? = null,
    /** Tyre temperatures in °C, when the simulation exposes them. */
    val tyreFl: Double? = null,
    val tyreFr: Double? = null,
    val tyreRl: Double? = null,
    val tyreRr: Double? = null,
    /** Discipline-specific systems and session info, when the game exposes them. */
    val abs: String? = null,
    val tc: String? = null,
    val brakeBias: Double? = null,
    val drs: Boolean? = null,
    val ers: Double? = null,
    val fuelPerLap: Double? = null,
    val handbrake: Boolean? = null,
    val surface: String? = null,
    val sector: Int? = null,
    val fuel: Double? = null,
    val fuelCapacity: Double? = null,
    val position: Int? = null,
    val totalCars: Int? = null,
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val currentLapMs: Long? = null,
    val lastLapMs: Long? = null,
    val bestLapMs: Long? = null,
    val car: String? = null,
    val track: String? = null,
    val game: String? = null,
    /** World position, used to rebuild the track map from a recording. */
    val posX: Double? = null,
    val posY: Double? = null,
    val posZ: Double? = null,
    /** 0..1 progress along the lap, when the game exposes it. */
    val lapDistPct: Double? = null,
    val airTemp: Double? = null,
    val trackTemp: Double? = null,
    /** 0..100, 0 = asciutto. */
    val wetness: Double? = null
) {
    val rpmRatio: Float
        get() {
            val max = maxRpm?.takeIf { it > 500.0 } ?: 8000.0
            return ((rpm ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
        }

    val hasCoreData: Boolean
        get() = speedKmh != null || rpm != null || gear != null
}

/**
 * SimHub publishes all precomputed properties on `/api/GetGamedata` (JSON).
 * The endpoint is not documented and its shape can change, so instead of
 * binding to fixed paths we flatten the document and look up common property
 * names, accepting numbers, numeric strings and formatted lap times.
 */
object TelemetryParser {

    private val SPEED_KMH = listOf("speedkmh")
    private val SPEED_MS = listOf("speedms")
    private val SPEED = listOf("speed", "groundspeed", "cardspeed")
    private val RPM = listOf("rpms", "rpm", "enginerpm", "currentrpm")
    private val MAX_RPM = listOf("maxrpms", "maxrpm", "rpmmax", "enginemaxrpm")
    private val GEAR = listOf("gear", "gearstring", "currentgear", "gearnumber")
    private val THROTTLE = listOf("throttle", "throttleposition", "throttlepercent", "throttlepct")
    private val BRAKE = listOf("brake", "brakeposition", "brakepct", "brakepercent")
    private val TYRE_FL = listOf("tyretemperaturefrontleft", "tyretempfrontleft", "tyretemperaturefl", "tyretempfl")
    private val TYRE_FR = listOf("tyretemperaturefrontright", "tyretempfrontright", "tyretemperaturefr", "tyretempfr")
    private val TYRE_RL = listOf("tyretemperaturerearleft", "tyretemprearleft", "tyretemperaturerl", "tyretemprl")
    private val TYRE_RR = listOf("tyretemperaturerearright", "tyretemprearright", "tyretemperaturerr", "tyretemprr")
    private val ABS = listOf(
        "abs", "abslevel", "absintensity", "absmode", "absassist", "absstate",
        "absenabled", "absactive", "absvalue", "absvaluelevel", "abssetting",
        "absintervention", "aidsabs", "carabs", "absdisplay"
    )
    private val TC = listOf(
        "tc", "tclevel", "tractioncontrol", "tractioncontrollevel", "tcintensity",
        "tcstate", "tcenabled", "tcactive", "tcvalue", "tcsetting", "tcs",
        "tractioncontrolstate", "tractioncontrolactive"
    )
    private val BRAKE_BIAS = listOf(
        "brakebias", "brakebalance", "brakebiaspercent", "brakefrontbias",
        "brakebalancefront", "brakebiasfront", "frontbrakebias", "brakeratio",
        "brakefrontpercent", "brakebalancepercent"
    )
    private val DRS = listOf(
        "drs", "drsstatus", "drsactive", "drsavailable", "drsstate", "drsenabled",
        "drsmode", "drsindicator", "drsopen", "reardrs", "drszone"
    )
    private val ERS = listOf(
        "ersbattery", "erslevel", "erscharge", "erspercent", "ers", "ersbatterylevel",
        "ersbatterycharge", "ersremaining", "erschargelevel", "ersdeployment",
        "ersmode", "ersstate", "energybattery", "hybridbattery", "energyremaining"
    )
    private val FUEL_PER_LAP = listOf(
        "fuelperlap", "averagefuelperlap", "avgfuelperlap", "fuelconsumptionperlap",
        "fuelusedperlap", "fuelusageperlap", "fuellitersperlap", "fuellitresperlap",
        "fuelavgperlap", "fuelperlapl", "litersperlap", "litresperlap", "fuellap"
    )
    private val HANDBRAKE = listOf(
        "handbrake", "handbrakestate", "handbrakeactive", "handbrakestatus",
        "handbrakeposition", "handbrakevalue", "handbrakeinput", "parkingbrake", "hb"
    )
    private val SURFACE = listOf(
        "surfacetype", "surface", "roadtype", "tracktype", "surfacename",
        "currentsurface", "tracksurface", "roadsurface", "surfaceid", "groundtype"
    )
    private val SECTOR = listOf(
        "currentsector", "sector", "sectornumber", "sectorindex", "currentsectorindex",
        "sectorcurrent", "sectornum", "currentsectornumber"
    )
    private val FUEL = listOf("fuelremaining", "fuel", "fuellevel", "fuelliters", "currentfuel")
    private val FUEL_CAPACITY = listOf("maxfuel", "fueltankcapacity", "fueltank", "fuelcapacity")
    private val POSITION = listOf("raceposition", "overallposition", "carposition", "position", "place")
    private val TOTAL_CARS = listOf("totalcars", "opponentscount", "carcount", "fieldsize")
    private val LAP = listOf("currentlap", "lapnumber", "lap")
    private val TOTAL_LAPS = listOf("totallaps", "sessionlaps", "lapsintotal")
    private val CURRENT_LAP = listOf("currentlaptime", "laptimecurrent", "currentlaptimeseconds")
    private val LAST_LAP = listOf("lastlaptime", "lastlaptimeseconds", "previouslaptime")
    private val BEST_LAP = listOf("bestlaptime", "fastestlaptime", "sessionbestlaptime", "bestlaptimeseconds")
    private val CAR = listOf("carmodel", "carname", "vehicle", "car")
    private val TRACK = listOf("trackname", "track", "circuit")
    private val GAME = listOf("gamename", "game", "sim")
    private val POS_X = listOf("positionx", "worldpositionx", "posx", "carpositionx", "gpsx")
    private val POS_Y = listOf("positiony", "worldpositiony", "posy", "carpositiony", "gpsy")
    private val POS_Z = listOf("positionz", "worldpositionz", "posz", "carpositionz", "gpsz")
    private val LAP_DIST_PCT = listOf(
        "lapdistpct", "lapdistancepct", "lapdistancepercent", "trackpositionpercent",
        "currentlapdistpct", "lapdist"
    )
    private val AIR_TEMP = listOf(
        "airtemp", "airtemperature", "ambienttemp", "ambienttemperature", "airtemperaturec"
    )
    private val TRACK_TEMP = listOf(
        "tracktemp", "tracktemperature", "roadtemp", "roadtemperature",
        "surfacetemperature", "tracktemperaturec"
    )
    private val WETNESS = listOf("wetness", "trackwetness", "rainintensity", "rainlevel")

    fun parse(body: String): Telemetry? {
        val text = body.trim().trimStart('\uFEFF')
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val root = try {
            val json = text.substring(start)
            if (json.startsWith("[")) JSONArray(json) else JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val values = HashMap<String, Any?>()
        collect(root, values)
        if (values.isEmpty()) return null

        val speed = number(values, SPEED_KMH)
            ?: number(values, SPEED_MS)?.times(3.6)
            ?: number(values, SPEED)

        return Telemetry(
            speedKmh = speed?.takeIf { it >= 0.0 && it.isFinite() },
            rpm = number(values, RPM)?.takeIf { it >= 0.0 },
            maxRpm = number(values, MAX_RPM)?.takeIf { it > 0.0 },
            gear = gearText(values),
            throttle = percent(values, THROTTLE),
            brake = percent(values, BRAKE),
            tyreFl = number(values, TYRE_FL)?.takeIf { it.isFinite() && it > -50.0 },
            tyreFr = number(values, TYRE_FR)?.takeIf { it.isFinite() && it > -50.0 },
            tyreRl = number(values, TYRE_RL)?.takeIf { it.isFinite() && it > -50.0 },
            tyreRr = number(values, TYRE_RR)?.takeIf { it.isFinite() && it > -50.0 },
            abs = levelText(
                values,
                withLoose(
                    values, ABS,
                    listOf("abs", "level"), listOf("abs", "state"), listOf("abs", "mode"),
                    listOf("abs", "active"), listOf("abs", "assist"), listOf("abs", "value")
                )
            ),
            tc = levelText(
                values,
                withLoose(
                    values, TC,
                    listOf("tc", "level"), listOf("tc", "state"), listOf("tc", "active"),
                    listOf("traction", "level"), listOf("traction", "state"),
                    listOf("traction", "control")
                )
            ),
            brakeBias = percent(
                values,
                withLoose(
                    values, BRAKE_BIAS,
                    listOf("brake", "bias"), listOf("brake", "balance")
                )
            ),
            drs = flag(
                values,
                withLoose(
                    values, DRS,
                    listOf("drs", "state"), listOf("drs", "active"),
                    listOf("drs", "available"), listOf("drs", "enabled")
                )
            ),
            ers = percent(
                values,
                withLoose(
                    values, ERS,
                    listOf("ers", "level"), listOf("ers", "battery"), listOf("ers", "charge"),
                    listOf("ers", "percent"), listOf("ers", "remaining"),
                    listOf("energy", "remaining"), listOf("hybrid", "energy"),
                    listOf("battery", "level"), listOf("battery", "charge")
                )
            ),
            fuelPerLap = number(
                values,
                withLoose(
                    values, FUEL_PER_LAP,
                    listOf("fuel", "perlap"), listOf("fuel", "lap", "avg"),
                    listOf("fuel", "lap", "average")
                )
            )?.takeIf { it > 0.0 },
            handbrake = flag(
                values,
                withLoose(values, HANDBRAKE, listOf("handbrake"), listOf("parkingbrake"))
            ),
            surface = text(
                values,
                withLoose(
                    values, SURFACE,
                    listOf("surface", "type"), listOf("surface", "name")
                )
            ),
            sector = integer(
                values,
                withLoose(
                    values, SECTOR,
                    listOf("sector", "index"), listOf("sector", "number")
                )
            )?.takeIf { it > 0 },
            fuel = number(values, FUEL)?.takeIf { it >= 0.0 },
            fuelCapacity = number(values, FUEL_CAPACITY)?.takeIf { it > 0.0 },
            position = integer(values, POSITION)?.takeIf { it > 0 },
            totalCars = integer(values, TOTAL_CARS)?.takeIf { it > 0 },
            lap = integer(values, LAP)?.takeIf { it > 0 },
            totalLaps = integer(values, TOTAL_LAPS)?.takeIf { it > 0 },
            currentLapMs = millis(values, CURRENT_LAP),
            lastLapMs = millis(values, LAST_LAP),
            bestLapMs = millis(values, BEST_LAP),
            car = text(values, CAR),
            track = text(values, TRACK),
            game = text(values, GAME),
            posX = number(values, POS_X)?.takeIf { it.isFinite() },
            posY = number(values, POS_Y)?.takeIf { it.isFinite() },
            posZ = number(values, POS_Z)?.takeIf { it.isFinite() },
            lapDistPct = number(values, LAP_DIST_PCT)?.let { raw ->
                when {
                    raw in 0.0..1.05 -> raw
                    raw in 10.0..100.0 -> raw / 100.0
                    else -> null
                }
            },
            airTemp = number(values, AIR_TEMP)?.takeIf { it.isFinite() && it > -50.0 },
            trackTemp = number(values, TRACK_TEMP)?.takeIf { it.isFinite() && it > -50.0 },
            wetness = number(values, WETNESS)?.let { raw ->
                if (raw <= 1.01) raw * 100.0 else raw
            }?.coerceIn(0.0, 100.0)
        )
    }

    // ------------------------------------------------------------- flattening

    private fun collect(node: Any?, out: MutableMap<String, Any?>) {
        when (node) {
            is JSONObject -> {
                if (node.has("Key") && node.has("Value") && node.length() <= 3) {
                    store(out, node.optString("Key"), node.opt("Value"))
                    return
                }
                for (key in node.keys()) {
                    val value = node.opt(key)
                    store(out, key, value)
                    if (value is JSONObject || value is JSONArray) collect(value, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    /** Stores the normalised leaf name ("SpeedKmh") and the full dotted name. */
    private fun store(out: MutableMap<String, Any?>, key: String, value: Any?) {
        val leaf = key.substringAfterLast('.').substringAfterLast('/').substringAfterLast(':')
        val simple = normalize(leaf)
        if (simple.isNotEmpty()) out[simple] = value
        val full = normalize(key)
        if (full.isNotEmpty() && full != simple) out.putIfAbsent(full, value)
    }

    private fun normalize(key: String): String =
        key.filter { it.isLetterOrDigit() }.lowercase()

    // ---------------------------------------------------------------- lookups

    /**
     * Every SimHub plugin names the same channel slightly differently
     * ("ABSLevel", "Aids.ABS", "TractionControlState", ...). When none of the
     * exact aliases is published we fall back to keys that contain all tokens
     * of one of the [patterns], so the chips stay populated with unusual games.
     */
    private fun withLoose(
        values: Map<String, Any?>,
        keys: List<String>,
        vararg patterns: List<String>
    ): List<String> {
        if (raw(values, keys) != null || patterns.isEmpty()) return keys
        val preferred = listOf(
            "level", "state", "mode", "percent", "value", "active", "charge", "bias", "status"
        )
        val found = values.keys
            .filter { key -> patterns.any { tokens -> tokens.all { key.contains(it) } } }
            .sortedWith(
                compareBy(
                    { key -> if (preferred.any { key.contains(it) }) 0 else 1 },
                    { key -> key.length }
                )
            )
        return if (found.isEmpty()) keys else keys + found
    }

    private fun raw(values: Map<String, Any?>, keys: List<String>): Any? {
        for (key in keys) {
            val value = values[key]
            if (value != null && value != JSONObject.NULL) return value
        }
        return null
    }

    private fun number(values: Map<String, Any?>, keys: List<String>): Double? {
        val value = raw(values, keys) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().replace(',', '.').toDoubleOrNull()
                ?: Regex("-?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
            else -> null
        }
    }

    private fun integer(values: Map<String, Any?>, keys: List<String>): Int? =
        number(values, keys)?.roundToInt()

    private fun text(values: Map<String, Any?>, keys: List<String>): String? {
        val value = raw(values, keys) ?: return null
        val text = when (value) {
            is String -> value
            is Number -> value.toString()
            else -> return null
        }.trim()
        return text.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }?.take(48)
    }

    private fun percent(values: Map<String, Any?>, keys: List<String>): Double? {
        val value = number(values, keys) ?: return null
        val percent = if (value <= 1.01) value * 100.0 else value
        return percent.coerceIn(0.0, 100.0)
    }

    private fun gearText(values: Map<String, Any?>): String? {
        val value = raw(values, GEAR) ?: return null
        val text = when (value) {
            is Number -> value.toInt().toString()
            is String -> value.trim()
            else -> return null
        }
        if (text.isEmpty()) return null
        return when (text.lowercase()) {
            "0" -> "N"
            "-1" -> "R"
            else -> text.uppercase().take(3)
        }
    }

    private fun levelText(values: Map<String, Any?>, keys: List<String>): String? {
        val value = raw(values, keys) ?: return null
        return when (value) {
            is Boolean -> if (value) "ON" else "OFF"
            is Number -> value.toInt().toString()
            is String -> value.trim().takeIf { it.isNotEmpty() }?.uppercase()?.take(6)
            else -> null
        }
    }

    private fun flag(values: Map<String, Any?>, keys: List<String>): Boolean? {
        val value = raw(values, keys) ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "on", "1", "yes", "active", "available", "open", "enabled" -> true
                "false", "off", "0", "no", "inactive", "closed", "disabled" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun millis(values: Map<String, Any?>, keys: List<String>): Long? =
        toMillis(raw(values, keys))

    private fun toMillis(value: Any?): Long? {
        if (value == null) return null
        if (value is Number) return secondsOrMillis(value.toDouble())
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        if (text.startsWith("PT", ignoreCase = true)) return parseIsoDuration(text)
        if (text.contains(':')) {
            var seconds = 0.0
            for (part in text.split(':')) {
                val chunk = part.replace(',', '.').toDoubleOrNull() ?: return null
                seconds = seconds * 60 + chunk
            }
            return secondsOrMillis(seconds)
        }
        return text.replace(',', '.').toDoubleOrNull()?.let(::secondsOrMillis)
    }

    private fun secondsOrMillis(value: Double): Long? {
        if (!value.isFinite() || value <= 0.0) return null
        return if (value > 3600.0) value.toLong() else (value * 1000.0).toLong()
    }

    private fun parseIsoDuration(text: String): Long? {
        val match = Regex(
            "PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:[.,]\\d+)?)S)?",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        val hours = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[3].replace(',', '.').toDoubleOrNull() ?: 0.0
        return secondsOrMillis(hours * 3600 + minutes * 60 + seconds)
    }
}
