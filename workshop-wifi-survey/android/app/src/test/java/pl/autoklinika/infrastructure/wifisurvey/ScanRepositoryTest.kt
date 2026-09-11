package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScanRepositoryTest {
    private lateinit var db: SurveyDatabase
    private lateinit var repo: SurveyRepository
    private val config = SurveyConfig(scan_collection_enabled = true)
    private fun stamp(seconds: Long) = Stamp("2026-01-01T00:00:${seconds.toString().padStart(2, '0')}Z", seconds * 1_000_000_000)
    private fun radio(seenUs: Long, ap: String = "02:00:00:00:00:01") = ScanReading("SYNTHETIC", ap, -65, 5220, 2, "[SYNTHETIC]", false, seenUs)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SurveyDatabase::class.java).allowMainThreadQueries().build()
        repo = SurveyRepository(db)
        repo.start(reviewSession().copy(status = "ACTIVE", ended_at_utc = null, ended_elapsed_ns = null,
            configuration_snapshot_json = surveyJson.encodeToString(config)))
    }
    @After fun cleanup() { db.close() }

    @Test fun joinsEachApAtItsOwnSeenTimeIncludingNearestAlreadyDeliveredFix() = runBlocking {
        repo.location(fixtureLocation("SYNTHETIC", 1_000_000_000).copy(id = "L1"))
        repo.location(fixtureLocation("SYNTHETIC", 2_600_000_000).copy(id = "L2"))
        assertEquals(2, repo.scans(ScanBatch(stamp(3), true, listOf(radio(2_000_000), radio(1_000_000, "02:00:00:00:00:02")))))
        val rows = db.dao().scanPage("SYNTHETIC", 0)
        assertEquals("L2", rows[0].location_id_for_seen_time); assertEquals(600.0, rows[0].location_join_delta_ms!!, .001)
        assertEquals("L1", rows[1].location_id_for_seen_time); assertEquals(0.0, rows[1].location_join_delta_ms!!, .001)
    }
    @Test fun repeatedAndStaleAndFutureScansAreRetainedWithoutSpatialJoins() = runBlocking {
        repo.location(fixtureLocation("SYNTHETIC", 2_000_000_000))
        repo.scans(ScanBatch(stamp(3), true, listOf(radio(2_000_000))))
        repo.scans(ScanBatch(stamp(4), true, listOf(radio(2_000_000))))
        repo.scans(ScanBatch(stamp(10), true, listOf(radio(3_000_000), radio(50_000_000, "02:00:00:00:00:02"))))
        val rows = db.dao().scanPage("SYNTHETIC", 0)
        assertEquals(4, rows.size); assertEquals(1, rows.count { it.fresh })
        assertTrue(rows.drop(1).all { it.location_id_for_seen_time == null && "SCAN_NOT_FRESH" in it.quality_flags })
    }
    @Test fun malformedFutureTimestampCannotPoisonLaterValidScans() = runBlocking {
        repo.scans(ScanBatch(stamp(3), true, listOf(radio(Long.MAX_VALUE))))
        assertEquals(1, repo.scans(ScanBatch(stamp(4), true, listOf(radio(3_000_000)))))
    }
    @Test fun resultsNotUpdatedCannotBeFreshEvenWithRecentTimestamps() = runBlocking {
        assertEquals(0, repo.scans(ScanBatch(stamp(3), false, listOf(radio(2_000_000)))))
        assertFalse(db.dao().scanPage("SYNTHETIC", 0).single().fresh)
    }
    @Test fun inaccurateAndMockGpsNeverEntersCoverageButRawIsRetained() = runBlocking {
        repo.location(fixtureLocation("SYNTHETIC", 2_000_000_000).copy(accuracy_m = 25f, is_mock_if_available = true))
        repo.scans(ScanBatch(stamp(3), true, listOf(radio(2_000_000))))
        val scan = db.dao().scanPage("SYNTHETIC", 0).single()
        assertNotNull(scan.location_id_for_seen_time)
        assertNull(CoverageAnalysis.scanReading(scan, db.dao().lastLocation("SYNTHETIC"), config))
    }
    @Test fun zipRoundTripPreservesScanCoverageAndDoesNotChangeRawRows() = runBlocking {
        repo.location(fixtureLocation("SYNTHETIC", 2_000_000_000))
        repo.scans(ScanBatch(stamp(3), true, listOf(radio(2_000_000), radio(2_100_000, "02:00:00:00:00:02"))))
        repo.wifi(WifiReading(true, "SYNTHETIC", "02:00:00:00:00:01", -80, 5220), stamp(4))
        val ended = repo.stop(stamp(10))!!
        val bytes = ByteArrayOutputStream().also { SurveyExporter.write(db.dao(), ended, it) }.toByteArray()
        val cache = java.nio.file.Files.createTempDirectory("synthetic-scan-import").toFile()
        try {
            val local = ReviewAnalysis.load(db.dao(), "SYNTHETIC")
            assertEquals(local, SurveyLogReader.read(bytes.inputStream(), cache))
            assertEquals(2, local.coverage.mappedScanCount); assertEquals(2L, db.dao().scanCount("SYNTHETIC"))
            assertEquals(0, cache.listFiles()!!.size)
        } finally { cache.deleteRecursively() }
    }
    @Test fun scanWithoutRecentGpsIsSavedUnlocated() = runBlocking {
        repo.location(fixtureLocation("SYNTHETIC", 1_000_000_000))
        repo.scans(ScanBatch(stamp(10), true, listOf(radio(9_000_000))))
        val scan = db.dao().scanPage("SYNTHETIC", 0).single()
        assertTrue(scan.fresh); assertNull(scan.location_id_for_seen_time)
        assertTrue("UNLOCATED" in scan.quality_flags)
    }
    @Test fun recoveryIncludesLastCommittedScanCallbackInEndTime() = runBlocking {
        repo.scans(ScanBatch(stamp(5), true, listOf(radio(4_000_000))))
        val recovered = SurveyRepository(db)
        assertEquals(1, recovered.recover("2026-01-02T00:00:00Z"))
        assertEquals(5_000_000_000L, db.dao().session("SYNTHETIC").ended_elapsed_ns)
        assertEquals("INTERRUPTED", db.dao().session("SYNTHETIC").status)
        assertEquals(1L, db.dao().scanCount("SYNTHETIC"))
    }
    @Test fun importerRejectsForgedDuplicateFreshFlagEvenWithRecomputedChecksums() = runBlocking {
        repo.scans(ScanBatch(stamp(3), true, listOf(radio(2_000_000))))
        repo.scans(ScanBatch(stamp(4), true, listOf(radio(2_000_000))))
        val ended = repo.stop(stamp(10))!!
        db.openHelper.writableDatabase.execSQL("UPDATE scan_results SET fresh = 1") // Synthetic corruption only.
        val bytes = ByteArrayOutputStream().also { SurveyExporter.write(db.dao(), ended, it) }.toByteArray()
        val cache = java.nio.file.Files.createTempDirectory("synthetic-invalid-scan").toFile()
        try {
            try { SurveyLogReader.read(bytes.inputStream(), cache); fail("Forged fresh flag accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, cache.listFiles()!!.size)
        } finally { cache.deleteRecursively() }
    }
}
