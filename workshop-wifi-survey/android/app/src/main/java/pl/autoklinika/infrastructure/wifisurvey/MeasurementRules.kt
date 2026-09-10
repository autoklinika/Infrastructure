package pl.autoklinika.infrastructure.wifisurvey

import kotlinx.serialization.json.*

object MeasurementRules {
    fun bandChannel(frequency: Int?): Pair<String?, Int?> = when {
        frequency == null -> null to null
        frequency == 2484 -> "2.4GHz" to 14
        frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> "2.4GHz" to (frequency - 2407) / 5
        frequency in 4910..4980 && frequency % 5 == 0 -> "5GHz" to (frequency - 4000) / 5
        frequency in 5005..5895 && frequency % 5 == 0 -> "5GHz" to (frequency - 5000) / 5
        frequency == 5935 -> "6GHz" to 2
        frequency in 5955..7115 && (frequency - 5950) % 5 == 0 -> "6GHz" to (frequency - 5950) / 5
        else -> null to null
    }

    // Platform width codes are open integers. Unknown values stay available as raw codes.
    fun widthLabel(raw: Int?): String = when (raw) {
        null -> "unavailable"
        0 -> "20 MHz"; 1 -> "40 MHz"; 2 -> "80 MHz"; 3 -> "160 MHz"
        4 -> "80+80 MHz"; 5 -> "320 MHz"
        else -> "UNKNOWN($raw)"
    }

    fun rssiGrade(rssi: Int?): String = when {
        rssi == null || rssi !in -126..0 -> "UNKNOWN"
        rssi >= -55 -> "VERY_GOOD"
        rssi >= -67 -> "GOOD"
        rssi >= -70 -> "MARGINAL"
        rssi >= -75 -> "WEAK"
        else -> "PROBLEMATIC"
    }

    fun ageMs(now: Long, locationElapsed: Long): Double? =
        if (now < locationElapsed) null else (now - locationElapsed) / 1_000_000.0

    data class Binding(val location: LocationSample?, val age: Double?, val flags: Set<String>)

    fun bind(now: Long, latest: LocationSample?, config: SurveyConfig): Binding {
        val age = latest?.let { ageMs(now, it.timestamp_elapsed_ns) }
        val valid = age != null && age <= config.max_location_age_ms
        val flags = buildSet {
            if (!valid) add("UNLOCATED")
            if (latest != null && age == null) add("LOCATION_FROM_FUTURE")
            if (age != null && age > config.max_location_age_ms) add("LOCATION_STALE")
            if (latest != null && latest.accuracy_m == null) add("LOCATION_ACCURACY_UNKNOWN")
            if (latest?.accuracy_m != null && latest.accuracy_m > config.accuracy_warning_m) add("LOCATION_ACCURACY_WARNING")
            if (latest?.accuracy_m != null && latest.accuracy_m > config.accuracy_exclusion_m) add("LOCATION_POOR_ACCURACY")
            if (latest?.is_mock_if_available == true) add("MOCK_LOCATION")
        }
        // Keep age for diagnosing stale fixes, but never create an invalid foreign-key join.
        return Binding(if (valid) latest else null, age, flags)
    }

    fun transitions(previous: ConnectedWifiSample?, current: ConnectedWifiSample): List<Pair<String, JsonObject>> {
        if (previous == null) return emptyList()
        val before = previous.network_transport_state == "WIFI_CONNECTED"
        val after = current.network_transport_state == "WIFI_CONNECTED"
        return buildList {
            if (before && !after) add("WIFI_DISCONNECTED" to buildJsonObject {})
            if (!before && after) add("WIFI_RECONNECTED" to buildJsonObject {})
            if (before && after && previous.ssid != null && previous.ssid == current.ssid &&
                previous.bssid != null && current.bssid != null && !previous.bssid.equals(current.bssid, true)) {
                add("BSSID_CHANGE" to buildJsonObject {
                    put("old_bssid", previous.bssid); put("new_bssid", current.bssid); put("ssid", current.ssid)
                    put("rssi_before", previous.rssi_dbm?.let(::JsonPrimitive) ?: JsonNull)
                    put("rssi_after", current.rssi_dbm?.let(::JsonPrimitive) ?: JsonNull)
                    put("last_old_sample_elapsed_ns", previous.timestamp_elapsed_ns)
                    put("first_new_sample_elapsed_ns", current.timestamp_elapsed_ns)
                })
            }
        }
    }
}
