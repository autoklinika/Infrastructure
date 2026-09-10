package pl.autoklinika.infrastructure.wifisurvey

import kotlin.math.*

/** Read-only presentation model. Never rewrites raw samples or reconstructs a missing capture join. */
data class ReviewSample(
    val sequence: Long, val elapsed: Long, val rssi: Int?, val connected: Boolean,
    val latitude: Double?, val longitude: Double?, val accuracy: Float?,
    val age: Double?, val mock: Boolean?, val flags: String,
)

data class ReviewPoint(
    val seconds: Double, val rssi: Int?, val connected: Boolean,
    val latitude: Double?, val longitude: Double?, val accuracy: Float?,
    val signalSegment: Int, val routeSegment: Int,
)

data class SurveyReview(
    val session: SurveySession, val points: List<ReviewPoint>, val sampleCount: Int,
    val validSignalCount: Int, val goodCount: Int, val weakCount: Int,
    val disconnectedCount: Int, val locatedCount: Int, val minimumRssi: Int?,
    val durationSeconds: Double, val gapCount: Int,
) {
    val goodPercent get() = if (validSignalCount == 0 || sampleCount == 0) null else 100.0 * goodCount / sampleCount
    val routePoints get() = points.filter { it.latitude != null && it.longitude != null }
}

object ReviewAnalysis {
    const val MAX_SAMPLES = 100_000
    fun build(session: SurveySession, samples: List<ReviewSample>): SurveyReview {
        require(samples.size <= MAX_SAMPLES) { "Ten pomiar jest za długi do podglądu na telefonie. Użyj eksportu na komputerze." }
        val config = surveyJson.decodeFromString<SurveyConfig>(session.configuration_snapshot_json)
        val gapSeconds = max(2.5, config.connected_interval_ms / 1000.0 * 2.5)
        var signalSegment = 0; var routeSegment = 0; var gaps = 0
        var good = 0; var weak = 0; var disconnected = 0; var valid = 0; var located = 0
        var previous: ReviewPoint? = null
        val all = samples.map { sample ->
            val seconds = (sample.elapsed - session.started_elapsed_ns) / 1e9
            val flags = sample.flags.split('|').toSet()
            val rssi = sample.rssi?.takeIf { sample.connected && it in -126..0 && "RSSI_INVALID" !in flags }
            if (!sample.connected) disconnected++
            if (rssi != null) { valid++; if (rssi >= -67) good++ else weak++ }
            val onMap = sample.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                sample.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
                sample.accuracy?.let { it.isFinite() && it >= 0 && it <= config.accuracy_exclusion_m } == true &&
                sample.age?.let { it.isFinite() && it >= 0 && it <= config.max_location_age_ms } == true &&
                sample.mock == false && flags.none { it in setOf("UNLOCATED", "LOCATION_STALE", "LOCATION_FROM_FUTURE",
                    "LOCATION_POOR_ACCURACY", "LOCATION_ACCURACY_UNKNOWN", "MOCK_LOCATION", "SSID_FILTER_MISMATCH") }
            if (onMap) located++
            val gap = previous?.let { seconds - it.seconds > gapSeconds } == true
            if (gap) gaps++
            if (gap || rssi == null || previous?.rssi == null) signalSegment++
            if (gap || !onMap || previous?.latitude == null) routeSegment++
            ReviewPoint(seconds, rssi, sample.connected, sample.latitude.takeIf { onMap },
                sample.longitude.takeIf { onMap }, sample.accuracy.takeIf { onMap }, signalSegment, routeSegment)
                .also { previous = it }
        }
        // Min/max per chronological bucket preserve short weak-signal dips. Segment IDs retain gaps.
        val displayed = if (all.size <= 2000) all else all.chunked(ceil(all.size / 500.0).toInt()).flatMap { bucket ->
            listOfNotNull(bucket.first(), bucket.filter { it.rssi != null }.minByOrNull { it.rssi!! },
                bucket.filter { it.rssi != null }.maxByOrNull { it.rssi!! }, bucket.firstOrNull { it.rssi == null },
                bucket.firstOrNull { it.latitude != null }, bucket.lastOrNull { it.latitude != null },
                bucket.last()).distinct().sortedBy { it.seconds }
        }
        val duration = ((session.ended_elapsed_ns ?: samples.lastOrNull()?.elapsed ?: session.started_elapsed_ns) - session.started_elapsed_ns) / 1e9
        return SurveyReview(session, displayed, samples.size, valid, good, weak, disconnected, located,
            all.mapNotNull { it.rssi }.minOrNull(), duration.coerceAtLeast(0.0), gaps)
    }

    suspend fun load(dao: SurveyDao, id: String): SurveyReview {
        require(dao.wifiCount(id) <= MAX_SAMPLES) { "Ten pomiar jest za długi do podglądu na telefonie. Użyj eksportu na komputerze." }
        val rows = mutableListOf<ReviewSample>()
        var after = 0L
        while (true) {
            val page = dao.reviewPage(id, after)
            if (page.isEmpty()) break
            rows.addAll(page); after = page.last().sequence
        }
        return build(dao.session(id), rows)
    }
}

/** North-up local metric projection, wrapping longitude at the dateline. No map service involved. */
data class RoutePosition(val point: ReviewPoint, val east: Double, val north: Double)
class RouteProjection(points: List<ReviewPoint>) {
    private val origin = points.firstOrNull { it.latitude != null && it.longitude != null }
    val positions = points.mapNotNull { point ->
        val latitude = point.latitude ?: return@mapNotNull null
        val longitude = point.longitude ?: return@mapNotNull null
        val delta = ((longitude - origin!!.longitude!! + 540) % 360) - 180
        RoutePosition(point, 6_371_000 * Math.toRadians(delta) * cos(Math.toRadians(origin.latitude!!)),
            6_371_000 * Math.toRadians(latitude - origin.latitude))
    }
    val minEast = positions.minOfOrNull { it.east } ?: 0.0
    val maxEast = positions.maxOfOrNull { it.east } ?: 0.0
    val minNorth = positions.minOfOrNull { it.north } ?: 0.0
    val maxNorth = positions.maxOfOrNull { it.north } ?: 0.0
    val span = max(20.0, max(maxEast - minEast, maxNorth - minNorth) * 1.25)
    val centerEast = (minEast + maxEast) / 2
    val centerNorth = (minNorth + maxNorth) / 2
}

fun signalLabel(rssi: Int?, connected: Boolean = true): String = when {
    !connected -> "Brak połączenia"
    rssi == null || rssi !in -126..0 -> "Brak odczytu"
    rssi >= -55 -> "Bardzo dobry"
    rssi >= -67 -> "Dobry"
    rssi >= -70 -> "Przeciętny"
    rssi >= -75 -> "Słaby"
    else -> "Bardzo słaby"
}

fun durationLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}
