package com.local.simhubconnect.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryParserTest {

    @Test
    fun parsesSimHubGameDataPayload() {
        val json = """
            {
              "GameData": {
                "SpeedKmh": 212.4,
                "Rpms": 7450,
                "MaxRpm": 8500,
                "Gear": "4",
                "Throttle": 87.5,
                "Brake": 0,
                "TyreTemperatureFrontLeft": 88.0,
                "TyreTemperatureFrontRight": 91.5,
                "TyreTemperatureRearLeft": 74.0,
                "TyreTemperatureRearRight": 101.0,
                "ABS": 6,
                "TC": 2,
                "BrakeBias": 56.5,
                "DRS": "AVAILABLE",
                "ERSBattery": 0.72,
                "FuelPerLap": 2.8,
                "Handbrake": false,
                "SurfaceType": "Gravel",
                "CurrentSector": 2,
                "Fuel": 32.4,
                "MaxFuel": 60,
                "Position": 3,
                "TotalCars": 20,
                "CurrentLap": 4,
                "TotalLaps": 12,
                "CurrentLapTime": "1:23.456",
                "LastLapTime": "00:01:22.100",
                "BestLapTime": "1:21.900",
                "CarModel": "Ferrari 488 GT3",
                "TrackName": "Monza"
              }
            }
        """.trimIndent()

        val data = TelemetryParser.parse(json)
        requireNotNull(data)
        assertTrue(data.hasCoreData)
        assertEquals(212.4, data.speedKmh!!, 0.001)
        assertEquals(7450.0, data.rpm!!, 0.001)
        assertEquals("4", data.gear)
        assertEquals(87.5, data.throttle!!, 0.001)
        assertEquals(0.0, data.brake!!, 0.001)
        assertEquals(88.0, data.tyreFl!!, 0.001)
        assertEquals(91.5, data.tyreFr!!, 0.001)
        assertEquals(74.0, data.tyreRl!!, 0.001)
        assertEquals(101.0, data.tyreRr!!, 0.001)
        assertEquals("6", data.abs)
        assertEquals("2", data.tc)
        assertEquals(56.5, data.brakeBias!!, 0.001)
        assertEquals(true, data.drs)
        assertEquals(72.0, data.ers!!, 0.001)
        assertEquals(2.8, data.fuelPerLap!!, 0.001)
        assertEquals(false, data.handbrake)
        assertEquals("Gravel", data.surface)
        assertEquals(2, data.sector)
        assertEquals(32.4, data.fuel!!, 0.001)
        assertEquals(3, data.position)
        assertEquals(4, data.lap)
        assertEquals(12, data.totalLaps)
        assertEquals(83_456L, data.currentLapMs!!)
        assertEquals(82_100L, data.lastLapMs!!)
        assertEquals(81_900L, data.bestLapMs!!)
        assertEquals("Ferrari 488 GT3", data.car)
        assertEquals("Monza", data.track)
    }

    @Test
    fun parsesDataCoreKeysRatiosAndSecondBasedLaps() {
        val json = """
            {"DataCorePlugin":{"GameData":{"NewData":{
              "SpeedKmh":"180","Rpms":6000,"Gear":3,"Throttle":0.5,"Brake":1,
              "BestLapTime":81.5,"TyreTempFL":60
            }}}}
        """.trimIndent()

        val data = TelemetryParser.parse(json)!!
        assertEquals(180.0, data.speedKmh!!, 0.001)
        assertEquals("3", data.gear)
        assertEquals(50.0, data.throttle!!, 0.001)
        assertEquals(100.0, data.brake!!, 0.001)
        assertEquals(60.0, data.tyreFl!!, 0.001)
        assertEquals(81_500L, data.bestLapMs!!)
    }

    @Test
    fun parsesPluginSpecificNames() {
        val json = """
            {
              "SpeedKmh": 150,
              "Aids": { "ABSLevel": 4, "TractionControlState": "OFF", "BrakeBalance": 56.5 },
              "ERSBatteryLevel": 0.44,
              "DRSState": "ACTIVE",
              "HandbrakeState": true,
              "SurfaceName": "Asphalt",
              "SectorIndex": 3,
              "FuelUsedPerLap": 3.4
            }
        """.trimIndent()

        val data = TelemetryParser.parse(json)!!
        assertEquals("4", data.abs)
        assertEquals("OFF", data.tc)
        assertEquals(56.5, data.brakeBias!!, 0.001)
        assertEquals(44.0, data.ers!!, 0.001)
        assertEquals(true, data.drs)
        assertEquals(true, data.handbrake)
        assertEquals("Asphalt", data.surface)
        assertEquals(3, data.sector)
        assertEquals(3.4, data.fuelPerLap!!, 0.001)
    }

    @Test
    fun fallsBackToTokenMatchingForUnknownNames() {
        val json = """
            {
              "SpeedKmh": 90,
              "AbsInterventionLevel": 2,
              "TractionControlSetting": 5,
              "HybridEnergyRemaining": 0.33,
              "FuelLitersPerLapAvg": 2.1,
              "TrackSurfaceName": "Gravel",
              "SectorNumber": 2
            }
        """.trimIndent()

        val data = TelemetryParser.parse(json)!!
        assertEquals("2", data.abs)
        assertEquals("5", data.tc)
        assertEquals(33.0, data.ers!!, 0.001)
        assertEquals(2.1, data.fuelPerLap!!, 0.001)
        assertEquals("Gravel", data.surface)
        assertEquals(2, data.sector)
    }

    @Test
    fun parsesKeyValueArraysAndDropsNulls() {
        val json = """
            [{"Key":"SpeedKmh","Value":100},{"Key":"Gear","Value":"2"},{"Key":"Fuel","Value":null}]
        """.trimIndent()

        val data = TelemetryParser.parse(json)!!
        assertEquals(100.0, data.speedKmh!!, 0.001)
        assertEquals("2", data.gear)
        assertNull(data.fuel)
    }

    @Test
    fun rejectsNonJsonBodies() {
        assertNull(TelemetryParser.parse("404 - Not Found"))
        assertNull(TelemetryParser.parse("<html>SimHub</html>"))
    }
}
