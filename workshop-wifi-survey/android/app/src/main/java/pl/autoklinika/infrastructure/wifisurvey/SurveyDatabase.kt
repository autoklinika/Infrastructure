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
        l.accuracy_m AS accuracy, w.location_age_ms AS age, l.is_mock_if_available AS mock, w.quality_flags AS flags, w.ssid, w.bssid
        FROM connected_wifi w LEFT JOIN locations l ON w.location_id_at_capture = l.id AND w.session_id = l.session_id
        WHERE w.session_id = :id AND w.sequence_no > :after ORDER BY w.sequence_no LIMIT 500""")
    suspend fun reviewPage(id: String, after: Long): List<ReviewSample>
    @Insert suspend fun insertSnapshot(value: ScanSnapshot)
    @Insert suspend fun insertScans(values: List<ScanObservation>)
    @Query("SELECT * FROM scan_snapshots WHERE session_id = :id ORDER BY callback_elapsed_ns, snapshot_id LIMIT 500 OFFSET :offset")
    suspend fun snapshotPage(id: String, offset: Long): List<ScanSnapshot>
    @Query("SELECT * FROM scan_snapshots WHERE session_id = :id ORDER BY callback_elapsed_ns DESC LIMIT 1")
    suspend fun lastSnapshot(id: String): ScanSnapshot?
    @Query("SELECT * FROM scan_results WHERE session_id = :id ORDER BY rowid LIMIT 500 OFFSET :offset")
    suspend fun scanPage(id: String, offset: Long): List<ScanObservation>
    @Query("SELECT COUNT(*) FROM scan_results WHERE session_id = :id") suspend fun scanCount(id: String): Long
    @Query("SELECT COUNT(*) FROM scan_snapshots WHERE session_id = :id") suspend fun snapshotCount(id: String): Long
    @Query("SELECT * FROM locations WHERE session_id = :id AND timestamp_elapsed_ns BETWEEN :lower AND :upper ORDER BY ABS(timestamp_elapsed_ns - :seen), sequence_no LIMIT 1")
    suspend fun nearestLocation(id: String, seen: Long, lower: Long, upper: Long): LocationSample?
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

@Database(entities = [SurveySession::class, LocationSample::class, ConnectedWifiSample::class, SurveyEvent::class,
    ScanSnapshot::class, ScanObservation::class], version = 3, exportSchema = true)
abstract class SurveyDatabase : RoomDatabase() {
    abstract fun dao(): SurveyDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS scan_snapshots (session_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                    request_elapsed_ns INTEGER, callback_elapsed_ns INTEGER NOT NULL, callback_utc TEXT NOT NULL,
                    request_accepted INTEGER, results_updated INTEGER NOT NULL, result_count INTEGER NOT NULL,
                    PRIMARY KEY(snapshot_id), FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_snapshots_session_id ON scan_snapshots(session_id)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS scan_results (id TEXT NOT NULL, session_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                    source TEXT NOT NULL, ssid TEXT, bssid TEXT, rssi_dbm INTEGER NOT NULL, frequency_mhz INTEGER NOT NULL, band TEXT, channel INTEGER,
                    channel_width INTEGER NOT NULL, capabilities TEXT, rtt_responder INTEGER NOT NULL, platform_seen_elapsed_us INTEGER NOT NULL,
                    result_age_at_callback_ms REAL NOT NULL, fresh INTEGER NOT NULL, location_id_for_seen_time TEXT, location_join_delta_ms REAL,
                    quality_flags TEXT NOT NULL, PRIMARY KEY(id),
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(snapshot_id) REFERENCES scan_snapshots(snapshot_id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(location_id_for_seen_time) REFERENCES locations(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                for (column in listOf("session_id", "snapshot_id", "location_id_for_seen_time"))
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_results_$column ON scan_results($column)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_status TEXT NOT NULL DEFAULT 'NOT_EXPORTED'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_uri TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_name TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN export_error TEXT")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, SurveyDatabase::class.java, "survey.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}
