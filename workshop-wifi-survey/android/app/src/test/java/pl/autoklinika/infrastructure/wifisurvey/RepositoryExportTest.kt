package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoryExportTest {
    private lateinit var db: SurveyDatabase
    private lateinit var repository: SurveyRepository
    private val sessionId = "00000000-0000-4000-8000-000000000001"
    private fun stamp(seconds: Long) = Stamp("2026-01-01T00:00:%02dZ".format(seconds), seconds * 1_000_000_000)
    private fun session() = SurveySession(id = sessionId, name = "SYNTHETIC, \"survey\"\nfixture", mode = "OUTDOOR", route_profile = "SYNTHETIC_V1",
        started_at_utc = stamp(1).utc, started_elapsed_ns = stamp(1).elapsed,
        app_version_name = "SYNTHETIC", app_version_code = 1, git_commit = "SYNTHETIC",
        device_manufacturer = "SYNTHETIC", device_model = "SYNTHETIC_DEVICE", android_release = "14", android_sdk = 34,
        ssid_filter = "SYNTHETIC_WIFI", notes = "SYNTHETIC ONLY — not a real site", configuration_snapshot_json = surveyJson.encodeToString(SurveyConfig()))
    private fun reading(bssid: String = "02:00:00:00:00:01") = WifiReading(true, "SYNTHETIC_WIFI", bssid, -63, 5220, 600, 600, 1200, 1200, 6)

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SurveyDatabase::class.java).allowMainThreadQueries().build()
        repository = SurveyRepository(db)
    }
    @After fun close() { db.close() }

    @Test fun bssidTransitionsDisconnectAndReconnect() = runBlocking {
        repository.start(session())
        repository.wifi(reading(), stamp(2))
        repository.wifi(reading("02:00:00:00:00:02"), stamp(3))
        repository.wifi(WifiReading(), stamp(4))
        repository.wifi(reading(), stamp(5))
        repository.wifi(reading("02:00:00:00:00:02").copy(ssid = "SYNTHETIC_OTHER"), stamp(6))
        val events = db.dao().eventPage(sessionId, 0)
        assertEquals(1, events.count { it.type == "BSSID_CHANGE" })
        assertEquals(1, events.count { it.type == "WIFI_DISCONNECTED" })
        assertEquals(1, events.count { it.type == "WIFI_RECONNECTED" })
        val transition = surveyJson.parseToJsonElement(events.single { it.type == "BSSID_CHANGE" }.payload_json).jsonObject
        assertEquals("02:00:00:00:00:01", transition["old_bssid"]?.jsonPrimitive?.content)
        assertEquals(-63, transition["rssi_before"]?.jsonPrimitive?.int)
        assertEquals(3_000_000_000, events.single { it.type == "BSSID_CHANGE" }.timestamp_elapsed_ns)
    }

    @Test fun locationJoinIsPersistedAndStaleSamplesAreRetained() = runBlocking {
        repository.start(session())
        repository.location(fixtureLocation(sessionId, stamp(2).elapsed))
        val joined = repository.wifi(reading(), stamp(3))!!
        val stale = repository.wifi(reading(), stamp(5))!!
        assertNotNull(joined.location_id_at_capture)
        assertEquals(1000.0, joined.location_age_ms!!, 0.0)
        assertNull(stale.location_id_at_capture)
        assertEquals(3000.0, stale.location_age_ms!!, 0.0)
        assertTrue(stale.quality_flags.contains("UNLOCATED"))
        assertEquals(2L, db.dao().wifiCount(sessionId))
    }

    @Test fun futureAndOutOfOrderFixesDoNotDisplaceValidPastFix() = runBlocking {
        repository.start(session())
        repository.location(fixtureLocation(sessionId, stamp(2).elapsed, "past"))
        repository.location(fixtureLocation(sessionId, stamp(8).elapsed, "future"))
        repository.location(fixtureLocation(sessionId, stamp(1).elapsed, "delayed"))
        val sample = repository.wifi(reading(), stamp(3))!!
        assertEquals("past", sample.location_id_at_capture)
        assertEquals(3L, db.dao().locationCount(sessionId))
        assertEquals(stamp(4).elapsed, repository.stop(stamp(4))!!.ended_elapsed_ns)
    }

    @Test fun interruptedRecoveryIsIdempotentAndDoesNotMixRebootClocks() = runBlocking {
        repository.start(session())
        repository.wifi(reading(), stamp(3))
        val recovered = SurveyRepository(db)
        assertEquals(1, recovered.recover("2026-01-02T00:00:00Z"))
        assertEquals(0, recovered.recover("2026-01-02T00:00:01Z"))
        val value = db.dao().session(sessionId)
        assertEquals("INTERRUPTED", value.status)
        assertEquals(3_000_000_000, value.ended_elapsed_ns)
        assertEquals(1L, db.dao().wifiCount(sessionId))
        val event = db.dao().lastEvent(sessionId)!!
        assertEquals("APP_RECOVERED_INTERRUPTED_SESSION", event.type)
        assertEquals(3_000_000_000, event.timestamp_elapsed_ns)
        val output = ByteArrayOutputStream()
        SurveyExporter.write(db.dao(), value, output)
        assertTrue(output.size() > 0)
    }

    @Test fun exportFailureKeepsRawAndCompletionAndCanRetry() = runBlocking {
        repository.start(session()); repository.wifi(reading(), stamp(2)); repository.stop(stamp(3))
        try { repository.export(sessionId, stamp(4)) { throw java.io.IOException("synthetic failure") }; fail("should fail") }
        catch (_: java.io.IOException) { }
        assertEquals("COMPLETED", db.dao().session(sessionId).status)
        assertEquals("EXPORT_FAILED", db.dao().session(sessionId).export_status)
        assertEquals(1L, db.dao().wifiCount(sessionId))
        repository.export(sessionId, stamp(5)) { SurveyExporter.Published("content://synthetic/1", "survey_synthetic.zip") }
        assertEquals("EXPORTED", db.dao().session(sessionId).export_status)
        assertEquals("survey_synthetic.zip", db.dao().session(sessionId).export_name)
    }

    @Test fun syntheticExportRoundTripAndZipChecksums() = runBlocking {
        repository.start(session())
        repository.location(fixtureLocation(sessionId, stamp(2).elapsed))
        repository.wifi(reading(), stamp(2))
        repository.note(stamp(3), "comma, quote \" and newline\nZażółć gęślą ☕")
        repository.location(fixtureLocation(sessionId, stamp(3).elapsed).copy(accuracy_m = 35f))
        repository.wifi(reading("02:00:00:00:00:02"), stamp(3))
        repository.wifi(WifiReading(), stamp(4))
        repository.wifi(reading(), stamp(7))
        val ended = repository.stop(stamp(8))!!
        val output = ByteArrayOutputStream()
        SurveyExporter.write(db.dao(), ended, output)
        val bytes = output.toByteArray()
        val second = ByteArrayOutputStream()
        SurveyExporter.write(db.dao(), ended, second)
        assertArrayEquals(bytes, second.toByteArray())
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        assertEquals(10, files.size)
        assertEquals("1\n", files.getValue("schema_version.txt").toString(Charsets.UTF_8))
        files.getValue("checksums.sha256").toString(Charsets.UTF_8).trim().lines().forEach { line ->
            val (hash, name) = line.split("  ", limit = 2)
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(files.getValue(name)).joinToString("") { "%02x".format(it) })
        }
        val metadata = surveyJson.parseToJsonElement(files.getValue("metadata.json").toString(Charsets.UTF_8)).jsonObject
        assertEquals(session().name, metadata["name"]!!.jsonPrimitive.content)
        assertTrue(files.getValue("events.csv").toString(Charsets.UTF_8).contains("Zażółć"))
        val geo = surveyJson.parseToJsonElement(files.getValue("track.geojson").toString(Charsets.UTF_8)).jsonObject
        assertEquals(2, geo["features"]!!.jsonArray.size)
        assertEquals(0.0002, geo["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonPrimitive.double, 0.0)
        val target = File("build/synthetic/survey_synthetic.zip")
        target.parentFile!!.mkdirs(); target.writeBytes(bytes)
    }

    @Test fun exportRefusesActiveSession() = runBlocking {
        repository.start(session())
        try { SurveyExporter.write(db.dao(), session(), ByteArrayOutputStream()); fail("active export allowed") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun paginatedExportKeepsMoreThanOnePage() = runBlocking {
        repository.start(session())
        repeat(505) { repository.location(fixtureLocation(sessionId, 2_000_000_000L + it, "loc-$it")) }
        val ended = repository.stop(stamp(3))!!
        val output = ByteArrayOutputStream(); SurveyExporter.write(db.dao(), ended, output)
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "locations.csv") assertEquals(506, zip.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }.size)
            }
        }
    }
}
