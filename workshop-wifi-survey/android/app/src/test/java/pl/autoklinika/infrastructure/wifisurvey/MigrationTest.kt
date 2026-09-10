package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    @Test fun migrationOneToTwoPreservesSessionAndMeasurement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val schema = surveyJson.parseToJsonElement(File("schemas/pl.autoklinika.infrastructure.wifisurvey.SurveyDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        val entities = schema["entities"]!!.jsonArray
        // In-memory SQLite avoids Room 2.8.4 MigrationTestHelper's Windows path-name mismatch.
        // Execute the historical exported schema, run production migration SQL, then compare
        // tables, indices and foreign keys with a newly generated current Room database.
        val legacy = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in entities) {
                        val value = entity.jsonObject
                        val table = value["tableName"]!!.jsonPrimitive.content
                        fun sql(raw: JsonElement) = raw.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(sql(value["createSql"]!!))
                        value["indices"]?.jsonArray?.forEach { db.execSQL(sql(it.jsonObject["createSql"]!!)) }
                    }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not used")
            }).build())
        val current = Room.inMemoryDatabaseBuilder(context, SurveyDatabase::class.java).allowMainThreadQueries().build()
        try {
            val database = legacy.writableDatabase
            database.execSQL("""INSERT INTO sessions (id,schema_version,name,mode,started_at_utc,started_elapsed_ns,
                app_version_name,app_version_code,device_manufacturer,device_model,android_release,android_sdk,
                configuration_snapshot_json,status) VALUES ('synthetic',1,'SYNTHETIC','OUTDOOR','2026-01-01T00:00:00Z',1000000000,
                'SYNTHETIC',1,'SYNTHETIC','SYNTHETIC','14',34,'{}','ACTIVE')""")
            database.execSQL("""INSERT INTO locations (id,session_id,sequence_no,timestamp_utc,timestamp_elapsed_ns,latitude,longitude,accuracy_m,is_mock_if_available)
                VALUES ('synthetic-location','synthetic',1,'2026-01-01T00:00:01Z',2000000000,0.0001,0.0002,5.0,0)""")
            SurveyDatabase.MIGRATION_1_2.migrate(database)
            for (entity in entities) {
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                assertEquals(table, TableInfo.read(current.openHelper.writableDatabase, table), TableInfo.read(database, table))
            }
            database.query("SELECT export_status,export_uri,status FROM sessions WHERE id='synthetic'").use {
                assertTrue(it.moveToFirst()); assertEquals("NOT_EXPORTED", it.getString(0)); assertTrue(it.isNull(1)); assertEquals("ACTIVE", it.getString(2))
            }
            database.query("SELECT timestamp_elapsed_ns FROM locations WHERE id='synthetic-location'").use { assertTrue(it.moveToFirst()); assertEquals(2000000000L, it.getLong(0)) }
            database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { legacy.close(); current.close() }
    }
}
