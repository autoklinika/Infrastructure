package pl.autoklinika.infrastructure.wifisurvey

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.PushbackReader
import java.io.StringReader

fun reviewSession() = SurveySession(id = "SYNTHETIC", name = "SYNTHETIC", mode = "OUTDOOR", route_profile = null,
    started_at_utc = "2026-01-01T00:00:00Z", ended_at_utc = "2026-01-01T00:00:10Z",
    started_elapsed_ns = 1_000_000_000, ended_elapsed_ns = 11_000_000_000,
    app_version_name = "SYNTHETIC", app_version_code = 1, git_commit = "SYNTHETIC",
    device_manufacturer = "SYNTHETIC", device_model = "SYNTHETIC", android_release = "14", android_sdk = 34,
    ssid_filter = null, notes = null, configuration_snapshot_json = surveyJson.encodeToString(SurveyConfig()), status = "COMPLETED")

class SurveyReviewTest {
    private fun sample(index: Int, rssi: Int? = -60) = ReviewSample(index.toLong() + 1, (index + 1L) * 1_000_000_000,
        rssi, true, 0.0, index * 0.00001, 4f, 100.0, false, "CHANNEL_WIDTH_UNAVAILABLE")

    @Test fun summarySeparatesWeakReadingsDisconnectsAndBadValues() {
        val rows = listOf(sample(0, -67), sample(1, -68), sample(2, -80), sample(3, -127), sample(4, -45).copy(connected = false))
        val review = ReviewAnalysis.build(reviewSession(), rows)
        assertEquals(5, review.sampleCount); assertEquals(3, review.validSignalCount)
        assertEquals(1, review.goodCount); assertEquals(2, review.weakCount); assertEquals(1, review.disconnectedCount)
        assertEquals(20.0, review.goodPercent!!, 0.001); assertEquals(-80, review.minimumRssi)
        assertNull(review.points[3].rssi); assertNull(review.points[4].rssi)
    }

    @Test fun mapNeverRepairsMissingOrStaleJoinsAndExcludesPoorMockAndFilteredGps() {
        val valid = sample(0)
        val rows = listOf(valid, sample(1).copy(age = 2500.000001), sample(2).copy(accuracy = 20.01f),
            sample(3).copy(mock = true), sample(4).copy(latitude = null, longitude = null),
            sample(5).copy(flags = "UNLOCATED"), sample(6).copy(flags = "SSID_FILTER_MISMATCH"),
            sample(7).copy(accuracy = null), sample(8).copy(latitude = Double.NaN), sample(9).copy(age = -1.0))
        val review = ReviewAnalysis.build(reviewSession(), rows)
        assertEquals(10, review.validSignalCount); assertEquals(1, review.locatedCount)
        assertEquals(1, review.routePoints.size)
        assertEquals(valid.latitude, review.routePoints.single().latitude)
        assertEquals(20.01f, rows[2].accuracy) // Read-only analysis keeps the raw input.
    }

    @Test fun noLinesBridgeMissingValuesOrTimeGaps() {
        val review = ReviewAnalysis.build(reviewSession(), listOf(sample(0), sample(1).copy(latitude = null),
            sample(2), sample(3, null), sample(4), sample(10)))
        assertNotEquals(review.points[0].routeSegment, review.points[2].routeSegment)
        assertNotEquals(review.points[2].signalSegment, review.points[4].signalSegment)
        assertNotEquals(review.points[4].signalSegment, review.points[5].signalSegment)
        assertEquals(1, review.gapCount)
    }

    @Test fun emptyAndSinglePointHaveSafeDurationAndProjection() {
        val empty = ReviewAnalysis.build(reviewSession(), emptyList())
        assertNull(empty.goodPercent); assertTrue(empty.points.isEmpty())
        assertEquals(20.0, RouteProjection(empty.routePoints).span, 0.0)
        val one = ReviewAnalysis.build(reviewSession(), listOf(sample(0)))
        assertTrue(RouteProjection(one.routePoints).span.isFinite()); assertEquals("0:00", durationLabel(0.0))
    }

    @Test fun metricProjectionUsesCorrectAxesAndWrapsDateline() {
        val points = ReviewAnalysis.build(reviewSession(), listOf(sample(0).copy(latitude = 0.0, longitude = 179.999),
            sample(1).copy(latitude = 0.001, longitude = -179.999))).routePoints
        val second = RouteProjection(points).positions[1]
        assertEquals(222.39, second.east, 0.05); assertEquals(111.195, second.north, 0.05)
    }

    @Test fun longChartRetainsOneSampleDipAndGapWithoutChangingStatistics() {
        val rows = (0..10000).map { sample(it, if (it == 4501) -91 else -50) }.toMutableList()
        rows[5000] = rows[5000].copy(rssi = null, latitude = null, longitude = null)
        val result = ReviewAnalysis.build(reviewSession().copy(ended_elapsed_ns = 10_002_000_000_000), rows)
        assertTrue(result.points.size < 4000); assertEquals(10001, result.sampleCount)
        assertEquals(1, result.weakCount); assertTrue(result.points.any { it.rssi == -91 })
        val before = result.points.last { it.seconds < 5000 }; val after = result.points.first { it.seconds > 5000 }
        assertNotEquals(before.signalSegment, after.signalSegment)
        assertNotEquals(before.routeSegment, after.routeSegment)
    }

    @Test fun csvHandlesQuotedUnicodeCommasNewlinesAndTrailingEmptyCells() {
        val csv = CsvRecords(PushbackReader(StringReader("a,b,c\r\n\"Zażółć, \"\"cytat\"\"\nwiersz\",2,\r\n"), 1))
        assertEquals(listOf("a", "b", "c"), csv.next())
        assertEquals(listOf("Zażółć, \"cytat\"\nwiersz", "2", ""), csv.next()); assertNull(csv.next())
    }

    @Test(expected = IllegalArgumentException::class) fun csvRejectsUnclosedQuotes() {
        CsvRecords(PushbackReader(StringReader("a,\"unfinished"), 1)).next()
    }
}
