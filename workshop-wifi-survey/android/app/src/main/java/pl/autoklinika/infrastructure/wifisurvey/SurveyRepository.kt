package pl.autoklinika.infrastructure.wifisurvey

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

class SurveyRepository(val db: SurveyDatabase) {
    val dao = db.dao()
    val lock = Mutex()
    private var session: SurveySession? = null
    private var config = SurveyConfig()
    private var previousWifi: ConnectedWifiSample? = null
    private var wifiSequence = 0L
    private var locationSequence = 0L
    private var degraded: Boolean? = null

    private suspend fun event(id: String, stamp: Stamp, type: String, payload: JsonObject = buildJsonObject {}) {
        val previous = dao.lastEvent(id)
        require(previous == null || stamp.elapsed >= previous.timestamp_elapsed_ns)
        dao.insertEvent(SurveyEvent(UUID.randomUUID().toString(), id, (previous?.sequence_no ?: 0) + 1,
            stamp.utc, stamp.elapsed, type, payload.toString()))
    }

    suspend fun recover(recoveredAtUtc: String): Int = lock.withLock {
        check(session == null)
        db.withTransaction {
            val active = dao.activeSessions()
            for (old in active) {
                val candidates = listOfNotNull(
                    Stamp(old.started_at_utc, old.started_elapsed_ns),
                    dao.lastWifi(old.id)?.let { Stamp(it.timestamp_utc, it.timestamp_elapsed_ns) },
                    dao.lastEvent(old.id)?.let { Stamp(it.timestamp_utc, it.timestamp_elapsed_ns) },
                )
                val last = candidates.maxBy { it.elapsed }
                // A reboot resets elapsedRealtime. Never append the new boot's elapsed clock to an old session.
                event(old.id, last, "APP_RECOVERED_INTERRUPTED_SESSION", buildJsonObject {
                    put("recovered_at_utc", recoveredAtUtc)
                    put("timestamp_basis", "LAST_PERSISTED_CAPTURE_CLOCK_RECORD")
                    put("end_is_lower_bound", true)
                })
                dao.updateSession(old.copy(status = "INTERRUPTED", ended_at_utc = last.utc, ended_elapsed_ns = last.elapsed))
            }
            active.size
        }
    }

    suspend fun start(value: SurveySession) = lock.withLock {
        check(session == null && dao.activeSessions().isEmpty())
        require(value.status == "ACTIVE" && value.mode in listOf("OUTDOOR", "INDOOR", "MIXED"))
        val parsed = surveyJson.decodeFromString<SurveyConfig>(value.configuration_snapshot_json)
        db.withTransaction {
            dao.insertSession(value)
            event(value.id, Stamp(value.started_at_utc, value.started_elapsed_ns), "SURVEY_START")
        }
        session = value; config = parsed; wifiSequence = 0; locationSequence = 0; previousWifi = null; degraded = null
    }

    suspend fun location(raw: LocationSample) = lock.withLock {
        val active = session ?: return@withLock
        require(raw.session_id == active.id)
        // Preserve provider fix times, including delayed delivery and weak accuracy.
        dao.insertLocation(raw.copy(sequence_no = locationSequence + 1))
        locationSequence++
    }

    suspend fun wifi(reading: WifiReading, stamp: Stamp): ConnectedWifiSample? = lock.withLock {
        val active = session ?: return@withLock null
        val latest = dao.locationAt(active.id, stamp.elapsed)
        val binding = MeasurementRules.bind(stamp.elapsed, latest, config)
        val (band, channel) = MeasurementRules.bandChannel(reading.frequency)
        val flags = binding.flags.toMutableSet().apply {
            addAll(reading.flags)
            if (!reading.connected) add("WIFI_DISCONNECTED")
            if (reading.connected && (reading.ssid == null || reading.bssid == null)) add("IDENTIFIERS_REDACTED")
            if (reading.rssi != null && reading.rssi !in -126..0) add("RSSI_INVALID")
            if (reading.frequency != null && band == null) add("FREQUENCY_UNKNOWN")
            if (active.ssid_filter != null && active.ssid_filter != reading.ssid) add("SSID_FILTER_MISMATCH")
            add("CHANNEL_WIDTH_UNAVAILABLE")
        }
        val sample = ConnectedWifiSample(UUID.randomUUID().toString(), active.id, wifiSequence + 1,
            stamp.utc, stamp.elapsed, ssid = reading.ssid, bssid = reading.bssid, rssi_dbm = reading.rssi,
            frequency_mhz = reading.frequency, band = band, channel = channel, rx_link_speed_mbps = reading.rx,
            tx_link_speed_mbps = reading.tx, max_supported_rx_link_speed_mbps = reading.maxRx,
            max_supported_tx_link_speed_mbps = reading.maxTx, wifi_standard = reading.standard,
            network_transport_state = if (reading.connected) "WIFI_CONNECTED" else "DISCONNECTED",
            location_id_at_capture = binding.location?.id, location_age_ms = binding.age,
            location_accuracy_m_at_capture = latest?.accuracy_m, quality_flags = flags.sorted().joinToString("|"))
        val isDegraded = binding.location == null || latest?.accuracy_m == null ||
            latest.accuracy_m > config.accuracy_warning_m || latest.is_mock_if_available
        db.withTransaction {
            dao.insertWifi(sample)
            MeasurementRules.transitions(previousWifi, sample).forEach { (type, payload) -> event(active.id, stamp, type, payload) }
            if (isDegraded != degraded) {
                event(active.id, stamp, if (isDegraded) "LOCATION_DEGRADED" else "LOCATION_RECOVERED", buildJsonObject {
                    put("quality_flags", binding.flags.sorted().joinToString("|"))
                    put("location_age_ms", binding.age?.let(::JsonPrimitive) ?: JsonNull)
                    put("accuracy_m", latest?.accuracy_m?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
        wifiSequence++; previousWifi = sample; degraded = isDegraded
        sample
    }

    suspend fun note(stamp: Stamp, text: String) = lock.withLock {
        session?.let { event(it.id, stamp, "USER_NOTE", buildJsonObject { put("text", text.take(4000)) }) }
    }

    suspend fun stop(stamp: Stamp, reason: String = "OPERATOR_STOP", interrupted: Boolean = false): SurveySession? = lock.withLock {
        val active = session ?: return@withLock null
        // A malformed future provider timestamp must not move the actual STOP time.
        val lastElapsed = stamp.elapsed
        val ended = active.copy(ended_at_utc = stamp.utc, ended_elapsed_ns = lastElapsed,
            status = if (interrupted) "INTERRUPTED" else "COMPLETED")
        db.withTransaction {
            event(active.id, Stamp(stamp.utc, lastElapsed), "SURVEY_STOP", buildJsonObject { put("reason", reason) })
            dao.updateSession(ended)
        }
        session = null
        ended
    }

    suspend fun export(id: String, stamp: Stamp, publish: suspend (SurveySession) -> SurveyExporter.Published): SurveyExporter.Published = lock.withLock {
        val value = dao.session(id)
        check(value.status != "ACTIVE")
        try {
            val result = publish(value)
            // Audit event uses the old session's clock domain; wall-clock publication time is in payload.
            val last = dao.lastEvent(id)
            event(id, Stamp(last?.timestamp_utc ?: value.ended_at_utc!!, last?.timestamp_elapsed_ns ?: value.ended_elapsed_ns!!),
                "EXPORT_CREATED", buildJsonObject { put("published_at_utc", stamp.utc); put("filename", result.name) })
            dao.updateSession(value.copy(export_status = "EXPORTED", export_uri = result.uri, export_name = result.name, export_error = null))
            result
        } catch (failure: Exception) {
            // Session completion/interruption is immutable. Export errors are separate metadata (DB v2).
            if (failure !is kotlinx.coroutines.CancellationException) {
                dao.updateSession(value.copy(export_status = "EXPORT_FAILED", export_error = failure.javaClass.simpleName))
            }
            throw failure
        }
    }
}
