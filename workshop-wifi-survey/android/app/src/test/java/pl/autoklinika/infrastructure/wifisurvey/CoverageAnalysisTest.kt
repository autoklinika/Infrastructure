package pl.autoklinika.infrastructure.wifisurvey

import org.junit.Assert.*
import org.junit.Test

class CoverageAnalysisTest {
    private fun reading(n: Long, ap: String = "02:00:00:00:00:01", rssi: Int = -60) =
        CoverageReading(n * 1_000_000_000, "SCAN_RESULT", "SYNTHETIC", ap, rssi, 0.0, 0.0, 4f)
    @Test fun separatesOverlappingApsAndConnectedSourceWithSameRadioLabel() {
        val rows = listOf(reading(1), reading(2), reading(3), reading(1, "02:00:00:00:00:02", -80),
            reading(2, rssi = -40).copy(source = "CONNECTED_LINK"))
        val map = CoverageAnalysis.build(rows, "SYNTHETIC", 4, true)
        assertEquals(3, map.aps.size); assertEquals(3, map.cells.single().signals.size)
        assertEquals(2, map.aps.map { it.label }.distinct().size)
        assertEquals(4, map.mappedScanCount)
    }
    @Test fun duplicateSnapshotsDoNotImproveEvidenceOrMedian() {
        val rows = listOf(reading(1, rssi = -80), reading(2, rssi = -50), reading(2, rssi = -50))
        val signal = CoverageAnalysis.build(rows, null, 3, true).cells.single().signals.single()
        assertEquals(2, signal.count); assertEquals(-65.0, signal.median, .001)
        assertEquals(-80, signal.low); assertEquals(-50, signal.high)
    }
    @Test fun neverFillsTheUnmeasuredGapBetweenTwoPositions() {
        val map = CoverageAnalysis.build(listOf(reading(1), reading(2).copy(longitude = .01)), null, 2, true)
        assertEquals(2, map.cells.size)
        assertTrue(map.cells[1].west - map.cells[0].east > .009)
    }
    @Test fun sustainedSignalDoesNotHideRepeatedWeakDipsBehindAGoodMedian() {
        val rows = (1..10).map { reading(it.toLong(), rssi = if (it <= 2) -80 else -55) }
        val signal = CoverageAnalysis.build(rows, null, 10, true).cells.single().signals.single()
        assertEquals(-55.0, signal.median, .001); assertEquals(-80, signal.sustained)
    }
    @Test fun spatialResolutionCannotBeFinerThanGpsUncertainty() {
        assertEquals(40, CoverageAnalysis.build(listOf(reading(1).copy(accuracy = 19.2f)), null, 1, true).cellMeters)
        assertEquals(10, CoverageAnalysis.build(listOf(reading(1)), null, 1, true).cellMeters)
    }
    @Test fun invalidCoordinatesDoNotGenerateMapTiles() {
        val map = CoverageAnalysis.build(listOf(reading(1).copy(latitude = Double.NaN), reading(2).copy(latitude = 90.0)), null, 2, true)
        assertTrue(map.cells.isEmpty())
    }
    @Test fun datelineDoesNotCreateAWorldSpanningCell() {
        val map = CoverageAnalysis.build(listOf(reading(1).copy(longitude = 179.9999), reading(2).copy(longitude = -179.9999)), null, 2, true)
        assertTrue(map.cells.maxOf { it.east } - map.cells.minOf { it.west } < .001)
    }
    @Test fun privateNetworkNamesCannotBreakOutOfTheDataElement() {
        val text = "</script><script>alert('SYNTHETIC')</script>&\u2028"
        val safe = mapSafeJson(text)
        assertFalse(safe.contains('<')); assertFalse(safe.contains('&')); assertFalse(safe.contains('\u2028'))
    }
    @Test fun summaryUsesAllSamplesBeforeGraphDecimation() {
        val samples = (1..3000).map { n -> ReviewSample(n.toLong(), n * 1_000_000_000L, if (n == 2999) -90 else -60,
            true, 0.0, 0.0, 4f, 0.0, false, "", "SYNTHETIC", "02:00:00:00:00:01") }
        val review = ReviewAnalysis.build(reviewSession(), samples)
        assertEquals(3000, review.coverage.cells.single().signals.single().count)
        assertEquals(-90, review.coverage.cells.single().signals.single().low)
        assertTrue(review.points.size < samples.size)
    }
}
