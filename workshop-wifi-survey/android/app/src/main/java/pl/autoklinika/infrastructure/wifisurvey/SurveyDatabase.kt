package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyDao {
    @Query("""SELECT w.sequence_no AS sequence, w.timestamp_elapsed_ns AS elapsed, w.rssi_dbm AS rssi,
        (w.network_transport_state = 'WIFI_CONNECTED') AS connected, l.latitude, l.longitude,
        l.accuracy_m AS accuracy, w.location_age_ms AS age, l.is_mock_if_available AS mock, w.quality_flags AS flags
        FROM connected_wifi w LEFT JOIN locations l ON w.location_id_at_capture = l.id AND w.session_id = l.session_id
        WHERE w.session_id = :id AND w.sequence_no > :after ORDER BY w.sequence_no LIMIT 500""")
    suspend fun reviewPage(id: String, after: Long): List<ReviewSample>
    @Insert suspend fun insertSession(value: SurveySession)
    @Update suspend fun updateSession(value: SurveySession)
    @Insert suspend fun insertLocation(value: LocationSample)
    @Insert suspend fun insertWifi(value: ConnectedWifiSample)
    @Insert suspend fun insertEvent(value: SurveyEvent)
    @Query("SELECT * FROM sessions ORDER BY started_at_utc DESC") fun sessions(): Flow<List<SurveySession>>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SurveySession
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE'") suspend fun activeSessions(): List<SurveySession>
    @Query("SELECT * FROM connected_wifi WHERE session_id = :id ORDER BY sequence_no DESC LIMIT 1") suspend fun lastWifi(id: String): ConnectedWifiSample?
    @Query("SELECT * FROM locations WHERE session_id = :id ORDER BY timestamp_elapsed_ns DESC LIMIT 1") suspend fun lastLocation(id: String): LocationSample?
    @Query("SELECT * FROM locations WHERE session_id = :id AND timestamp_elapsed_ns <= :elapsed ORDER BY timestamp_elapsed_ns DESC LIMIT 1") suspend fun locationAt(id: String, elapsed: Long): LocationSample?
    @Query("SELECT * FROM events WHERE session_id = :id ORDER BY sequence_no DESC LIMIT 1") suspend fun lastEvent(id: String): SurveyEvent?
    @Query("SELECT * FROM connected_wifi WHERE session_id = :id AND sequence_no > :after ORDER BY sequence_no LIMIT 500") suspend fun wifiPage(id: String, after: Long): List<ConnectedWifiSample>
    @Query("SELECT * FROM locations WHERE session_id = :id AND sequence_no > :after ORDER BY sequence_no LIMIT 500") suspend fun locationPage(id: String, after: Long): List<LocationSample>
    @Query("SELECT * FROM events WHERE session_id = :id AND sequence_no > :after ORDER BY sequence_no LIMIT 500") suspend fun eventPage(id: String, after: Long): List<SurveyEvent>
    @Query("SELECT COUNT(*) FROM connected_wifi WHERE session_id = :id") suspend fun wifiCount(id: String): Long
    @Query("SELECT COUNT(*) FROM locations WHERE session_id = :id") suspend fun locationCount(id: String): Long
    @Query("SELECT COUNT(*) FROM connected_wifi WHERE session_id = :id AND location_id_at_capture IS NOT NULL") suspend fun locatedCount(id: String): Long
    @Query("SELECT COUNT(*) FROM events WHERE session_id = :id AND type = 'BSSID_CHANGE'") suspend fun transitionCount(id: String): Long
}

@Database(entities = [SurveySession::class, LocationSample::class, ConnectedWifiSample::class, SurveyEvent::class],
    version = 2, exportSchema = true)
abstract class SurveyDatabase : RoomDatabase() {
    abstract fun dao(): SurveyDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_status TEXT NOT NULL DEFAULT 'NOT_EXPORTED'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_uri TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_name TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_error TEXT")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, SurveyDatabase::class.java, "survey.db")
            .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}
