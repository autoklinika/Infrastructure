package pl.autoklinika.infrastructure.wifisurvey

import kotlinx.serialization.Serializable
import kotlin.math.*

data class CoverageReading(val elapsed: Long, val source: String, val ssid: String?, val bssid: String,
    val rssi: Int, val latitude: Double, val longitude: Double, val accuracy: Float)

@Serializable data class CoverageAp(val id: String, val label: String, val network: String, val source: String)
@Serializable data class CellSignal(val ap: String, val median: Double, val sustained: Int, val low: Int, val high: Int,
    val count: Int, val accuracy: Float)
@Serializable data class CoverageCell(val south: Double, val west: Double, val north: Double, val east: Double,
    val signals: List<CellSignal>)
@Serializable data class CoverageMap(val aps: List<CoverageAp> = emptyList(), val cells: List<CoverageCell> = emptyList(),
    val cellMeters: Int = 10, val defaultNetwork: String? = null, val scanCount: Int = 0, val mappedScanCount: Int = 0,
    val scanEnabled: Boolean = false)

/** Descriptive bins, not a propagation model. Never fills unvisited cells or estimates AP positions. */
object CoverageAnalysis {
    fun scanReading(scan: ScanObservation, location: LocationSample?, config: SurveyConfig): CoverageReading? {
        if (!scan.fresh || scan.source != "SCAN_RESULT" || scan.bssid.isNullOrBlank() || scan.rssi_dbm !in -126..0 ||
            scan.platform_seen_elapsed_us !in 1..Long.MAX_VALUE / 1000 ||
            scan.result_age_at_callback_ms !in 0.0..config.max_scan_age_ms.toDouble() ||
            scan.location_join_delta_ms?.let { it.isFinite() && abs(it) <= config.max_location_age_ms } != true ||
            location == null || location.session_id != scan.session_id ||
            location.id != scan.location_id_for_seen_time || location.is_mock_if_available ||
            location.accuracy_m?.let { it.isFinite() && it in 0f..config.accuracy_exclusion_m } != true ||
            !location.latitude.isFinite() || location.latitude !in -85.0..85.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            scan.quality_flags.split('|').any { it in setOf("SCAN_NOT_FRESH", "DUPLICATE_OBSERVATION", "SSID_FILTER_MISMATCH", "RSSI_INVALID") }) return null
        return CoverageReading(scan.platform_seen_elapsed_us * 1000, scan.source, scan.ssid, scan.bssid,
            scan.rssi_dbm, location.latitude, location.longitude, location.accuracy_m)
    }

    fun build(readings: List<CoverageReading>, defaultNetwork: String?, scanCount: Int, scanEnabled: Boolean): CoverageMap {
        val valid = readings.filter { it.rssi in -126..0 && it.latitude.isFinite() && it.latitude in -85.0..85.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0 && it.accuracy.isFinite() && it.accuracy >= 0 }
            .distinctBy { Triple(it.source, it.bssid, it.elapsed) }
        if (valid.isEmpty()) return CoverageMap(defaultNetwork = defaultNetwork, scanCount = scanCount, scanEnabled = scanEnabled)
        // Never present bins smaller than twice the largest accepted GPS uncertainty.
        val meters = max(10, ceil(valid.maxOf { it.accuracy } * 2 / 5).toInt() * 5)
        val originLat = valid.first().latitude
        val originLon = valid.first().longitude
        val latitudeStep = Math.toDegrees(meters / 6_371_000.0)
        val longitudeStep = latitudeStep / cos(Math.toRadians(originLat))
        fun apKey(row: CoverageReading) = "${row.source}:${row.bssid}"
        val radios = valid.map { it.bssid }.distinct().sorted()
        val aps = valid.groupBy(::apKey).entries.sortedBy { it.key }.map { (id, group) ->
            CoverageAp(id, "AP ${radios.indexOf(group.first().bssid) + 1}", group.first().ssid?.takeIf { it.isNotBlank() } ?: "Sieć bez nazwy", group.first().source)
        }
        val cells = valid.groupBy { row ->
            val deltaLon = ((row.longitude - originLon + 540) % 360) - 180
            floor((row.latitude - originLat) / latitudeStep).toInt() to floor(deltaLon / longitudeStep).toInt()
        }.map { (grid, rows) ->
            val south = originLat + grid.first * latitudeStep
            val west = originLon + grid.second * longitudeStep
            CoverageCell(south, west, south + latitudeStep, west + longitudeStep, rows.groupBy(::apKey).map { (ap, group) ->
                val levels = group.map { it.rssi }.sorted()
                val median = (levels[(levels.size - 1) / 2] + levels[levels.size / 2]) / 2.0
                CellSignal(ap, median, levels[floor((levels.size - 1) * .1).toInt()], levels.first(), levels.last(), levels.size, group.maxOf { it.accuracy })
            })
        }
        require(cells.size <= 5_000) { "Ten pomiar obejmuje zbyt wiele miejsc do mapy na telefonie. Użyj eksportu na komputerze." }
        return CoverageMap(aps, cells, meters, defaultNetwork, scanCount, valid.count { it.source == "SCAN_RESULT" }, scanEnabled)
    }
}
