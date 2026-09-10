package pl.autoklinika.infrastructure.wifisurvey

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "scan_snapshots", indices = [Index("session_id")], foreignKeys = [
    ForeignKey(entity = SurveySession::class, parentColumns = ["id"], childColumns = ["session_id"])])
data class ScanSnapshot(
    val session_id: String, @PrimaryKey val snapshot_id: String,
    val request_elapsed_ns: Long?, val callback_elapsed_ns: Long, val callback_utc: String,
    val request_accepted: Boolean?, val results_updated: Boolean, val result_count: Int,
)

@Serializable
@Entity(tableName = "scan_results", indices = [Index("session_id"), Index("snapshot_id"), Index("location_id_for_seen_time")], foreignKeys = [
    ForeignKey(entity = SurveySession::class, parentColumns = ["id"], childColumns = ["session_id"]),
    ForeignKey(entity = ScanSnapshot::class, parentColumns = ["snapshot_id"], childColumns = ["snapshot_id"]),
    ForeignKey(entity = LocationSample::class, parentColumns = ["id"], childColumns = ["location_id_for_seen_time"])])
data class ScanObservation(
    @PrimaryKey val id: String, val session_id: String, val snapshot_id: String,
    val source: String = "SCAN_RESULT", val ssid: String?, val bssid: String?,
    val rssi_dbm: Int, val frequency_mhz: Int, val band: String?, val channel: Int?,
    val channel_width: Int, val capabilities: String?, val rtt_responder: Boolean,
    val platform_seen_elapsed_us: Long, val result_age_at_callback_ms: Double,
    val fresh: Boolean, val location_id_for_seen_time: String?, val location_join_delta_ms: Double?,
    val quality_flags: String,
)

data class ScanReading(val ssid: String?, val bssid: String?, val rssi: Int, val frequency: Int,
    val width: Int, val capabilities: String?, val rtt: Boolean, val seenUs: Long)
data class ScanBatch(val stamp: Stamp, val updated: Boolean, val readings: List<ScanReading>)
