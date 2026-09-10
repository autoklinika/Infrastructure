package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SurveyLogReaderTest {
    private lateinit var db: SurveyDatabase
    private lateinit var cache: File
    private lateinit var original: ByteArray
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SurveyDatabase::class.java).allowMainThreadQueries().build()
        cache = java.nio.file.Files.createTempDirectory("survey-synthetic-test").toFile()
        val repo = SurveyRepository(db)
        repo.start(reviewSession().copy(status = "ACTIVE", ended_at_utc = null, ended_elapsed_ns = null))
        repo.location(fixtureLocation("SYNTHETIC", 2_000_000_000))
        repo.wifi(WifiReading(true, "SYNTHETIC", "02:00:00:00:00:01", -80, 5220), Stamp("2026-01-01T00:00:02Z", 2_000_000_000))
        repo.note(Stamp("2026-01-01T00:00:03Z", 3_000_000_000), "SYNTHETIC, \"notatka\"\nZażółć gęślą")
        repo.wifi(WifiReading(), Stamp("2026-01-01T00:00:05Z", 5_000_000_000))
        val ended = repo.stop(Stamp("2026-01-01T00:00:10Z", 10_000_000_000))!!
        original = ByteArrayOutputStream().also { SurveyExporter.write(db.dao(), ended, it) }.toByteArray()
    }
    @After fun cleanup() { db.close(); cache.deleteRecursively() }

    private fun repack(rehash: Boolean = true, change: (MutableMap<String, ByteArray>) -> Unit): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(original.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes()
        } }
        change(entries)
        if (rehash) entries["checksums.sha256"] = entries.filterKeys { it != "checksums.sha256" }.entries.joinToString("") { (name, bytes) ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "  $name\n"
        }.toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } } }.toByteArray()
    }
    private fun reject(bytes: ByteArray) {
        try { SurveyLogReader.read(bytes.inputStream(), cache); fail("Malformed log accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test fun databaseAndVerifiedZipProduceSameResultWithoutChangingData() = runBlocking {
        val before = db.dao().wifiPage("SYNTHETIC", 0)
        val local = ReviewAnalysis.load(db.dao(), "SYNTHETIC")
        val imported = SurveyLogReader.read(original.inputStream(), cache)
        assertEquals(local, imported)
        assertEquals(2, local.sampleCount); assertEquals(1, local.weakCount); assertEquals(1, local.locatedCount)
        assertEquals(before, db.dao().wifiPage("SYNTHETIC", 0)); assertEquals(0, cache.listFiles()!!.size)
        assertEquals(imported, SurveyLogReader.read(original.inputStream(), cache)) // Reopening never duplicates sessions.
        assertEquals(2L, db.dao().wifiCount("SYNTHETIC"))
    }

    @Test fun rejectsChangedCsvWithoutMatchingChecksum() {
        reject(repack(false) { it["connected_wifi.csv"] = it.getValue("connected_wifi.csv") + byteArrayOf(32) })
    }
    @Test fun rejectsTraversalAndDoesNotExtractOutsidePrivateTemporaryDirectory() {
        reject(repack { it["../escape.txt"] = "SYNTHETIC".toByteArray() })
        assertFalse(File(cache.parentFile, "escape.txt").exists())
    }
    @Test fun rejectsUnsupportedSchemaEvenWithValidChecksums() {
        reject(repack { it["schema_version.txt"] = "99\n".toByteArray() })
    }
    @Test fun rejectsMissingRequiredStream() { reject(repack { it.remove("locations.csv") }) }
    @Test fun rejectsInventedJoinAgeEvenWithValidChecksums() {
        reject(repack { it["connected_wifi.csv"] = it.getValue("connected_wifi.csv").toString(Charsets.UTF_8).replace("\"0.0\"", "\"999.0\"").toByteArray() })
    }
    @Test fun rejectsWrongLocationSessionEvenWithValidChecksums() {
        reject(repack { it["locations.csv"] = it.getValue("locations.csv").toString(Charsets.UTF_8).replace("\"SYNTHETIC\"", "\"DIFFERENT_SYNTHETIC\"").toByteArray() })
    }
}
