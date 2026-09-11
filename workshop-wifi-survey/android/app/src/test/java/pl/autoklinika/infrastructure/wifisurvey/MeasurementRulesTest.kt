package pl.autoklinika.infrastructure.wifisurvey

import org.junit.Assert.*
import org.junit.Test

class MeasurementRulesTest {
    @Test fun frequencyBandChannel() {
        mapOf(2412 to ("2.4GHz" to 1), 2472 to ("2.4GHz" to 13), 2484 to ("2.4GHz" to 14),
            4940 to ("5GHz" to 188), 5180 to ("5GHz" to 36), 5220 to ("5GHz" to 44),
            5825 to ("5GHz" to 165), 5935 to ("6GHz" to 2), 5955 to ("6GHz" to 1),
            7115 to ("6GHz" to 233)).forEach { (frequency, result) -> assertEquals(result, MeasurementRules.bandChannel(frequency)) }
        listOf(null, -1, 2413, 5900, 9999).forEach { assertEquals(null to null, MeasurementRules.bandChannel(it)) }
    }
    @Test fun unknownWidthIsOpenAndUnavailableIsDifferent() {
        assertEquals("UNKNOWN(99)", MeasurementRules.widthLabel(99))
        assertEquals("unavailable", MeasurementRules.widthLabel(null))
        assertEquals("320 MHz", MeasurementRules.widthLabel(5))
        assertEquals("80+80 MHz", MeasurementRules.widthLabel(4))
    }
    @Test fun rssiThresholdBoundaries() {
        mapOf(-55 to "VERY_GOOD", -56 to "GOOD", -67 to "GOOD", -68 to "MARGINAL",
            -70 to "MARGINAL", -71 to "WEAK", -75 to "WEAK", -76 to "PROBLEMATIC", -127 to "UNKNOWN")
            .forEach { (rssi, grade) -> assertEquals(grade, MeasurementRules.rssiGrade(rssi)) }
        assertEquals("UNKNOWN", MeasurementRules.rssiGrade(null))
    }
    @Test fun ageHasSubMillisecondPrecisionAndRejectsFuture() {
        assertEquals(2500.000001, MeasurementRules.ageMs(3_500_000_001, 1_000_000_000)!!, 0.0000001)
        assertNull(MeasurementRules.ageMs(999, 1000))
    }
    @Test fun bindingAcceptsBoundaryAndRejectsOneNanosecondBeyond() {
        val location = fixtureLocation("s", 1_000_000_000)
        assertEquals(location.id, MeasurementRules.bind(3_500_000_000, location, SurveyConfig()).location?.id)
        val rejected = MeasurementRules.bind(3_500_000_001, location, SurveyConfig())
        assertNull(rejected.location)
        assertTrue(rejected.flags.containsAll(listOf("UNLOCATED", "LOCATION_STALE")))
        assertTrue(MeasurementRules.bind(10, null, SurveyConfig()).flags.contains("UNLOCATED"))
    }
    @Test fun poorAndMockLocationRemainsRawAndJoined() {
        val location = fixtureLocation("s", 1_000_000_000).copy(accuracy_m = 35f, is_mock_if_available = true)
        val bound = MeasurementRules.bind(1_100_000_000, location, SurveyConfig())
        assertEquals(location.id, bound.location?.id)
        assertTrue(bound.flags.containsAll(listOf("LOCATION_POOR_ACCURACY", "MOCK_LOCATION")))
    }
    @Test fun utcClockChangeCannotAffectBinding() {
        val location = fixtureLocation("s", 1_000_000_000).copy(timestamp_utc = "1990-01-01T00:00:00Z")
        assertEquals(location.id, MeasurementRules.bind(1_100_000_000, location, SurveyConfig()).location?.id)
    }
    @Test fun preflightBlocksOnlyFailedCriticalRequirements() {
        assertFalse(Preflight().canStart)
        assertFalse(Preflight(listOf(PreflightCheck("permission", "denied", false))).canStart)
        assertTrue(Preflight(listOf(PreflightCheck("RTT", "NO", false, false), PreflightCheck("permission", "granted", true))).canStart)
    }
}

fun fixtureLocation(session: String, elapsed: Long, id: String = "location-$elapsed") = LocationSample(
    id, session, 0, "2026-01-01T00:00:00Z", elapsed, 0.0001, 0.0002, 4f,
    altitude_m = 3.0, vertical_accuracy_m = 5f, bearing_deg = 90f, speed_mps = 0.8f, provider = "SYNTHETIC")
