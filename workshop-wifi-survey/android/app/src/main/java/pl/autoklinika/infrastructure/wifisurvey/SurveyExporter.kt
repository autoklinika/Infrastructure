package pl.autoklinika.infrastructure.wifisurvey

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val surveyJson = Json { encodeDefaults = true; explicitNulls = true }

object SurveyExporter {
    val reservedHeaders = linkedMapOf(
        "scan_snapshots.csv" to "session_id,snapshot_id,request_elapsed_ns,callback_elapsed_ns,callback_utc,request_accepted,results_updated,result_count",
        "scan_results.csv" to "id,session_id,snapshot_id,source,ssid,bssid,rssi_dbm,frequency_mhz,band,channel,channel_width,capabilities,rtt_responder,platform_seen_elapsed_us,result_age_at_callback_ms,fresh,location_id_for_seen_time,location_join_delta_ms,quality_flags",
        "indoor_anchors.csv" to "id,session_id,timestamp_utc,timestamp_elapsed_ns,floorplan_id,x_m,y_m,x_normalized,y_normalized,label",
    )

    fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    fun row(value: JsonObject, fields: List<String>): String = fields.joinToString(",") {
        val cell = value[it]
        csvCell(if (cell == null || cell == JsonNull) "" else if (cell is JsonPrimitive) cell.content else cell.toString())
    } + "\r\n"

    suspend fun write(dao: SurveyDao, session: SurveySession, output: OutputStream) {
        require(session.status != "ACTIVE") { "Cannot export an active session" }
        val scanEnabled = surveyJson.decodeFromString<SurveyConfig>(session.configuration_snapshot_json).scan_collection_enabled
        ZipOutputStream(output.buffered()).use { zip ->
            val hashes = linkedMapOf<String, String>()
            suspend fun entry(name: String, content: suspend (OutputStreamWriter) -> Unit) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                val digest = MessageDigest.getInstance("SHA-256")
                val writer = OutputStreamWriter(DigestOutputStream(zip, digest), Charsets.UTF_8)
                content(writer)
                writer.flush()
                zip.closeEntry()
                hashes[name] = digest.digest().joinToString("") { "%02x".format(it) }
            }
            val metadata = buildJsonObject {
                surveyJson.encodeToJsonElement(session).jsonObject.filterKeys { !it.startsWith("export_") }
                    .forEach { (key, value) -> put(key, value) }
                put("configuration_snapshot", surveyJson.parseToJsonElement(session.configuration_snapshot_json))
                put("measurement_source", "CONNECTED_LINK")
                put("collector_stage", if (scanEnabled) 2 else 1)
                put("counts", buildJsonObject {
                    put("connected_wifi", dao.wifiCount(session.id)); put("locations", dao.locationCount(session.id))
                    put("located_connected_wifi", dao.locatedCount(session.id)); put("bssid_transitions", dao.transitionCount(session.id))
                    if (scanEnabled) { put("scan_snapshots", dao.snapshotCount(session.id)); put("scan_results", dao.scanCount(session.id)) }
                })
                put("unimplemented_streams", JsonArray((if (scanEnabled) listOf("indoor_anchors") else listOf("neighbor_scans", "indoor_anchors")).map(::JsonPrimitive)))
            }
            entry("metadata.json") { it.write(metadata.toString() + "\n") }
            suspend fun <T> csv(name: String, fields: List<String>, fetch: suspend (Long) -> List<T>,
                                sequence: (T) -> Long, encode: (T) -> JsonObject) {
                entry(name) { writer ->
                    writer.write(fields.joinToString(",") + "\r\n")
                    var after = 0L
                    while (true) {
                        val page = fetch(after)
                        if (page.isEmpty()) break
                        page.forEach { writer.write(row(encode(it), fields)) }
                        after = sequence(page.last())
                    }
                }
            }
            fun fields(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
                (0 until descriptor.elementsCount).map(descriptor::getElementName)
            csv("connected_wifi.csv", fields(ConnectedWifiSample.serializer().descriptor),
                { dao.wifiPage(session.id, it) }, { it.sequence_no }, { surveyJson.encodeToJsonElement(it).jsonObject })
            csv("locations.csv", fields(LocationSample.serializer().descriptor),
                { dao.locationPage(session.id, it) }, { it.sequence_no }, { surveyJson.encodeToJsonElement(it).jsonObject })
            csv("events.csv", fields(SurveyEvent.serializer().descriptor),
                { dao.eventPage(session.id, it) }, { it.sequence_no }, { surveyJson.encodeToJsonElement(it).jsonObject })
            suspend fun <T> offsetCsv(name: String, fetch: suspend (Long) -> List<T>, encode: (T) -> JsonObject) {
                entry(name) { writer ->
                    val header = reservedHeaders.getValue(name)
                    writer.write(header + "\r\n")
                    var offset = 0L
                    while (true) {
                        val page = fetch(offset)
                        if (page.isEmpty()) break
                        page.forEach { writer.write(row(encode(it), header.split(','))) }
                        offset += page.size
                    }
                }
            }
            offsetCsv("scan_snapshots.csv", { dao.snapshotPage(session.id, it) }, { surveyJson.encodeToJsonElement(it).jsonObject })
            offsetCsv("scan_results.csv", { dao.scanPage(session.id, it) }, { surveyJson.encodeToJsonElement(it).jsonObject })
            entry("indoor_anchors.csv") { it.write(reservedHeaders.getValue("indoor_anchors.csv") + "\r\n") }
            entry("track.geojson") { writer ->
                // Raw fix points: no artificial line across gaps, no implicit accuracy filtering.
                writer.write("{\"type\":\"FeatureCollection\",\"features\":[")
                var after = 0L
                var first = true
                while (true) {
                    val page = dao.locationPage(session.id, after)
                    if (page.isEmpty()) break
                    for (location in page) {
                        if (!first) writer.write(",")
                        first = false
                        writer.write(buildJsonObject {
                            put("type", "Feature"); put("id", location.id)
                            put("geometry", buildJsonObject {
                                put("type", "Point")
                                put("coordinates", JsonArray(listOf(JsonPrimitive(location.longitude), JsonPrimitive(location.latitude))))
                            })
                            put("properties", buildJsonObject {
                                put("timestamp_utc", location.timestamp_utc); put("timestamp_elapsed_ns", location.timestamp_elapsed_ns)
                                put("accuracy_m", location.accuracy_m?.let(::JsonPrimitive) ?: JsonNull)
                                put("is_mock", location.is_mock_if_available); put("source", "RAW_LOCATION")
                            })
                        }.toString())
                    }
                    after = page.last().sequence_no
                }
                writer.write("]}\n")
            }
            entry("schema_version.txt") { it.write("$SCHEMA_VERSION\n") }
            zip.putNextEntry(ZipEntry("checksums.sha256").apply { time = 0L })
            zip.write(hashes.entries.joinToString("") { "${it.value}  ${it.key}\n" }.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    data class Published(val uri: String, val name: String)
    suspend fun publish(context: Context, dao: SurveyDao, session: SurveySession): Published {
        val date = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val name = "survey_${date}_${session.id.take(8)}.zip"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WorkshopWiFiSurvey")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { write(dao, session, it) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            val actualName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: name
            return Published(uri.toString(), actualName)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    fun probeTarget(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "preflight-${java.util.UUID.randomUUID()}.tmp")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/WorkshopWiFiSurvey")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try { checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(byteArrayOf(0)) } }
        finally { check(resolver.delete(uri, null, null) == 1) }
        true
    }.getOrDefault(false)
}
