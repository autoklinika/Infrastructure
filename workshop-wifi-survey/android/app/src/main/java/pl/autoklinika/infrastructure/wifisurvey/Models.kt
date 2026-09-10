package pl.autoklinika.infrastructure.wifisurvey

import androidx.room.*
import kotlinx.serialization.Serializable

const val SCHEMA_VERSION = 1

@Serializable
data class SurveyConfig(
    val connected_interval_ms: Long = 1000,
    val location_interval_ms: Long = 1000,
    val max_location_age_ms: Long = 2500,
    val accuracy_warning_m: Float = 10f,
    val accuracy_exclusion_m: Float = 20f,
    val min_start_free_bytes: Long = 100L * 1024 * 1024,
    val stop_free_bytes: Long = 20L * 1024 * 1024,
    val scan_collection_enabled: Boolean = false,
    val scan_interval_ms: Long = 30_000,
    val max_scan_age_ms: Long = 5_000,
    val indoor_anchors_enabled: Boolean = false,
    val mobile_data_state: String = "OPERATOR_NOT_RECORDED",
    val bluetooth_state: String = "OPERATOR_NOT_RECORDED",
    val vpn_active: Boolean = false,
) {
    init {
        require(connected_interval_ms in 500..10000)
        require(location_interval_ms == 1000L)
        require(max_location_age_ms in 0..60000)
        require(accuracy_warning_m > 0 && accuracy_exclusion_m >= accuracy_warning_m)
        require(scan_interval_ms >= 5_000 && max_scan_age_ms in 0..60_000)
    }
}

@Serializable
@Entity(tableName = "sessions")
data class SurveySession(
    @PrimaryKey val id: String,
    val schema_version: Int = SCHEMA_VERSION,
    val name: String,
    val mode: String,
    val route_profile: String?,
    val started_at_utc: String,
    val ended_at_utc: String? = null,
    val started_elapsed_ns: Long,
    val ended_elapsed_ns: Long? = null,
    val app_version_name: String,
    val app_version_code: Int,
    val git_commit: String?,
    val device_manufacturer: String,
    val device_model: String,
    val android_release: String,
    val android_sdk: Int,
    val ssid_filter: String?,
    val notes: String?,
    val configuration_snapshot_json: String,
    val status: String = "ACTIVE",
    @ColumnInfo(defaultValue = "'NOT_EXPORTED'") val export_status: String = "NOT_EXPORTED",
    val export_uri: String? = null,
    val export_name: String? = null,
    val export_error: String? = null,
)

@Serializable
@Entity(tableName = "locations", indices = [Index(value = ["session_id", "sequence_no"], unique = true),
    Index(value = ["session_id", "timestamp_elapsed_ns"])], foreignKeys = [ForeignKey(
    entity = SurveySession::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.NO_ACTION)])
data class LocationSample(
    @PrimaryKey val id: String,
    val session_id: String,
    val sequence_no: Long,
    val timestamp_utc: String,
    val timestamp_elapsed_ns: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy_m: Float?,
    val altitude_m: Double? = null,
    val vertical_accuracy_m: Float? = null,
    val bearing_deg: Float? = null,
    val bearing_accuracy_deg: Float? = null,
    val speed_mps: Float? = null,
    val speed_accuracy_mps: Float? = null,
    val provider: String? = null,
    val is_mock_if_available: Boolean = false,
)

@Serializable
@Entity(tableName = "connected_wifi", indices = [Index(value = ["session_id", "sequence_no"], unique = true),
    Index("location_id_at_capture")], foreignKeys = [
    ForeignKey(entity = SurveySession::class, parentColumns = ["id"], childColumns = ["session_id"]),
    ForeignKey(entity = LocationSample::class, parentColumns = ["id"], childColumns = ["location_id_at_capture"])])
data class ConnectedWifiSample(
    @PrimaryKey val id: String,
    val session_id: String,
    val sequence_no: Long,
    val timestamp_utc: String,
    val timestamp_elapsed_ns: Long,
    val source: String = "CONNECTED_LINK",
    val ssid: String?,
    val bssid: String?,
    val rssi_dbm: Int?,
    val frequency_mhz: Int?,
    val band: String?,
    val channel: Int?,
    val channel_width: Int? = null,
    val rx_link_speed_mbps: Int? = null,
    val tx_link_speed_mbps: Int? = null,
    val max_supported_rx_link_speed_mbps: Int? = null,
    val max_supported_tx_link_speed_mbps: Int? = null,
    val wifi_standard: Int? = null,
    val network_transport_state: String,
    val location_id_at_capture: String?,
    val location_age_ms: Double?,
    val location_accuracy_m_at_capture: Float?,
    val quality_flags: String,
)

@Serializable
@Entity(tableName = "events", indices = [Index(value = ["session_id", "sequence_no"], unique = true)],
    foreignKeys = [ForeignKey(entity = SurveySession::class, parentColumns = ["id"], childColumns = ["session_id"])])
data class SurveyEvent(
    @PrimaryKey val id: String,
    val session_id: String,
    val sequence_no: Long,
    val timestamp_utc: String,
    val timestamp_elapsed_ns: Long,
    val type: String,
    val payload_json: String,
)

data class Stamp(val utc: String, val elapsed: Long)
data class WifiReading(
    val connected: Boolean = false,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequency: Int? = null,
    val rx: Int? = null,
    val tx: Int? = null,
    val maxRx: Int? = null,
    val maxTx: Int? = null,
    val standard: Int? = null,
    val flags: Set<String> = emptySet(),
)

data class StartRequest(val name: String, val mode: String, val route: String?, val filter: String?,
                        val notes: String?, val config: SurveyConfig)
