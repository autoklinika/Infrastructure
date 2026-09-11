package pl.autoklinika.infrastructure.wifisurvey

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.*
import java.io.*
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Bounded, checksum-verified preview of a user-selected ZIP. No database insert or raw-data rewrite. */
object SurveyLogReader {
    private const val MAX_BYTES = 100L * 1024 * 1024
    private val readerJson = Json { ignoreUnknownKeys = true }
    private val required = setOf("metadata.json", "connected_wifi.csv", "locations.csv", "events.csv",
        "track.geojson", "schema_version.txt", "checksums.sha256")
    private val allowed = required + SurveyExporter.reservedHeaders.keys + "survey.db"

    fun read(input: InputStream, cache: File): SurveyReview {
        val directory = java.nio.file.Files.createTempDirectory(cache.toPath(), "survey-preview-").toFile()
        try {
            val hashes = linkedMapOf<String, String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in allowed && entry.name !in hashes) { "Nieprawidłowa zawartość pliku ZIP." }
                    val digest = MessageDigest.getInstance("SHA-256")
                    File(directory, entry.name).outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BYTES) { "Plik jest za duży do podglądu na telefonie (maks. 100 MB po rozpakowaniu)." }
                            out.write(buffer, 0, count); digest.update(buffer, 0, count)
                        }
                    }
                    hashes[entry.name] = digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            require(hashes.keys.containsAll(required)) { "W pliku brakuje części pomiaru." }
            fun small(name: String, limit: Long = 65536): String {
                val file = File(directory, name)
                require(file.length() <= limit) { "Nieprawidłowy nagłówek pomiaru." }
                return file.readText(Charsets.UTF_8)
            }
            val manifest = linkedMapOf<String, String>()
            small("checksums.sha256").lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val match = Regex("^([0-9a-fA-F]{64})  ([a-z_]+\\.[a-z0-9]+)$").matchEntire(line)
                require(match != null) { "Nieprawidłowa lista sum kontrolnych." }
                require(manifest.put(match.groupValues[2], match.groupValues[1].lowercase()) == null) { "Powtórzona suma kontrolna." }
            }
            require(manifest.keys == hashes.keys - "checksums.sha256" && manifest.all { hashes[it.key] == it.value }) {
                "Plik jest uszkodzony: sumy kontrolne nie pasują. Wybierz oryginalny eksport."
            }
            require(small("schema_version.txt").trim() == SCHEMA_VERSION.toString()) { "Ta wersja pliku nie jest jeszcze obsługiwana." }
            val metadata = readerJson.parseToJsonElement(small("metadata.json", 512 * 1024)).jsonObject
            require(metadata["measurement_source"]?.jsonPrimitive?.content == "CONNECTED_LINK") { "To nie jest pomiar bieżącego połączenia Wi-Fi." }
            val session = readerJson.decodeFromJsonElement(SurveySession.serializer(), metadata)
            require(session.schema_version == SCHEMA_VERSION && session.status in setOf("COMPLETED", "INTERRUPTED") &&
                session.mode in setOf("OUTDOOR", "INDOOR", "MIXED") && session.name.length <= 200 &&
                session.ended_elapsed_ns != null && session.started_elapsed_ns >= 0 &&
                session.ended_elapsed_ns >= session.started_elapsed_ns) { "Nieprawidłowe dane pomiaru." }
            Instant.parse(session.started_at_utc); Instant.parse(session.ended_at_utc!!)
            val config = readerJson.decodeFromString(SurveyConfig.serializer(), session.configuration_snapshot_json)
            require(readerJson.parseToJsonElement(session.configuration_snapshot_json) == metadata["configuration_snapshot"]) { "Niezgodne kopie konfiguracji pomiaru." }
            val locations = hashMapOf<String, LocationSample>()
            var locationSequence = 0L
            rows(File(directory, "locations.csv"), LocationSample.serializer()) { location ->
                require(location.session_id == session.id && location.sequence_no == ++locationSequence &&
                    location.timestamp_elapsed_ns >= 0 && locations.put(location.id, location) == null &&
                    location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                    location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
                    (location.accuracy_m == null || location.accuracy_m.isFinite() && location.accuracy_m >= 0)) { "Nieprawidłowy zapis lokalizacji." }
                Instant.parse(location.timestamp_utc)
            }
            var sequence = 0L; var lastElapsed = session.started_elapsed_ns
            val identifiers = hashSetOf<String>()
            val samples = mutableListOf<ReviewSample>()
            rows(File(directory, "connected_wifi.csv"), ConnectedWifiSample.serializer()) { sample ->
                require(sample.session_id == session.id && sample.source == "CONNECTED_LINK" && sample.sequence_no == ++sequence &&
                    identifiers.add(sample.id) && sample.timestamp_elapsed_ns in lastElapsed..session.ended_elapsed_ns &&
                    sample.network_transport_state in setOf("WIFI_CONNECTED", "DISCONNECTED")) { "Nieprawidłowa kolejność lub źródło pomiarów." }
                Instant.parse(sample.timestamp_utc); lastElapsed = sample.timestamp_elapsed_ns
                val location = sample.location_id_at_capture?.let { locations[it] ?: error("Brak lokalizacji wskazanej w pomiarze.") }
                if (location != null) {
                    val age = MeasurementRules.ageMs(sample.timestamp_elapsed_ns, location.timestamp_elapsed_ns)
                    require(age != null && age <= config.max_location_age_ms && sample.location_age_ms?.let { it.isFinite() && abs(it - age) <= 0.001 } == true &&
                        sample.location_accuracy_m_at_capture == location.accuracy_m && "UNLOCATED" !in sample.quality_flags.split('|')) {
                        "Pomiar zawiera nieprawidłowe powiązanie z lokalizacją."
                    }
                } else require("UNLOCATED" in sample.quality_flags.split('|')) { "Brak oznaczenia pomiaru bez lokalizacji." }
                samples += ReviewSample(sample.sequence_no, sample.timestamp_elapsed_ns, sample.rssi_dbm,
                    sample.network_transport_state == "WIFI_CONNECTED", location?.latitude, location?.longitude,
                    location?.accuracy_m, sample.location_age_ms, location?.is_mock_if_available, sample.quality_flags, sample.ssid, sample.bssid)
            }
            identifiers.clear(); sequence = 0; lastElapsed = session.started_elapsed_ns
            rows(File(directory, "events.csv"), SurveyEvent.serializer()) { event ->
                require(event.session_id == session.id && event.sequence_no == ++sequence && identifiers.add(event.id) &&
                    event.timestamp_elapsed_ns in lastElapsed..session.ended_elapsed_ns) { "Nieprawidłowy dziennik zdarzeń." }
                Instant.parse(event.timestamp_utc); lastElapsed = event.timestamp_elapsed_ns
                readerJson.parseToJsonElement(event.payload_json).jsonObject
            }
            val snapshots = hashMapOf<String, ScanSnapshot>()
            val scanCounts = hashMapOf<String, Int>()
            val seen = hashMapOf<String, Long>()
            val scans = mutableListOf<CoverageReading>()
            var scanCount = 0
            require(metadata["collector_stage"]?.jsonPrimitive?.int == if (config.scan_collection_enabled) 2 else 1) { "Nieprawidłowy etap zbierania danych." }
            if (config.scan_collection_enabled) require(hashes.keys.containsAll(listOf("scan_snapshots.csv", "scan_results.csv"))) { "Brak skanów AP." }
            File(directory, "scan_snapshots.csv").takeIf { it.exists() }?.let { file ->
                rows(file, ScanSnapshot.serializer()) { snapshot ->
                    require(config.scan_collection_enabled && snapshot.session_id == session.id && snapshot.result_count >= 0 &&
                        snapshot.callback_elapsed_ns in session.started_elapsed_ns..session.ended_elapsed_ns &&
                        snapshots.put(snapshot.snapshot_id, snapshot) == null) { "Nieprawidłowy zapis skanu." }
                    Instant.parse(snapshot.callback_utc)
                    require((snapshot.request_elapsed_ns == null) == (snapshot.request_accepted == null)) { "Niepełne dane żądania skanu." }
                    snapshot.request_elapsed_ns?.let { require(it in session.started_elapsed_ns..snapshot.callback_elapsed_ns) }
                }
            }
            identifiers.clear()
            File(directory, "scan_results.csv").takeIf { it.exists() }?.let { file ->
                rows(file, ScanObservation.serializer()) { scan ->
                    val snapshot = snapshots[scan.snapshot_id] ?: error("Brak skanu wskazanego przez odczyt AP.")
                    require(scan.session_id == session.id && scan.source == "SCAN_RESULT" && identifiers.add(scan.id)) { "Nieprawidłowe źródło odczytu AP." }
                    val elapsed = scan.platform_seen_elapsed_us.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) ?: 0L
                    val age = (snapshot.callback_elapsed_ns - elapsed) / 1e6
                    require(scan.result_age_at_callback_ms.isFinite() && abs(age - scan.result_age_at_callback_ms) <= .001) { "Nieprawidłowy wiek skanu." }
                    val key = "${scan.bssid}|${scan.frequency_mhz}"
                    if (scan.fresh) require(snapshot.results_updated && elapsed >= session.started_elapsed_ns &&
                        age in 0.0..config.max_scan_age_ms.toDouble() && scan.platform_seen_elapsed_us > (seen[key] ?: 0)) { "Nieaktualny skan oznaczony jako nowy." }
                    if (elapsed > 0 && age >= 0) seen[key] = maxOf(seen[key] ?: 0, scan.platform_seen_elapsed_us)
                    val location = scan.location_id_for_seen_time?.let { locations[it] ?: error("Brak pozycji skanu.") }
                    if (location != null) {
                        val delta = (location.timestamp_elapsed_ns - elapsed) / 1e6
                        require(scan.fresh && location.timestamp_elapsed_ns <= snapshot.callback_elapsed_ns && abs(delta) <= config.max_location_age_ms &&
                            scan.location_join_delta_ms?.let { it.isFinite() && abs(it - delta) <= .001 } == true) { "Nieprawidłowe powiązanie skanu z GPS." }
                    } else require(scan.location_join_delta_ms == null && "UNLOCATED" in scan.quality_flags.split('|')) { "Brak oznaczenia skanu bez GPS." }
                    CoverageAnalysis.scanReading(scan, location, config)?.let(scans::add)
                    scanCount++; scanCounts[scan.snapshot_id] = (scanCounts[scan.snapshot_id] ?: 0) + 1
                }
            }
            require(snapshots.values.all { it.result_count == (scanCounts[it.snapshot_id] ?: 0) }) { "Niepełne wyniki skanu." }
            metadata["counts"]?.jsonObject?.let { counts ->
                require(counts["connected_wifi"]?.jsonPrimitive?.long == samples.size.toLong() &&
                    counts["locations"]?.jsonPrimitive?.long == locations.size.toLong()) { "Liczba próbek nie zgadza się z opisem pliku." }
                if (config.scan_collection_enabled) require(counts["scan_snapshots"]?.jsonPrimitive?.int == snapshots.size &&
                    counts["scan_results"]?.jsonPrimitive?.int == scanCount) { "Liczba skanów nie zgadza się z opisem pliku." }
            }
            return ReviewAnalysis.build(session, samples, scans, scanCount)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun <T> rows(file: File, serializer: KSerializer<T>, consume: (T) -> Unit) {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val csv = CsvRecords(PushbackReader(reader, 1))
            val header = csv.next() ?: error("Brak nagłówka tabeli.")
            require(header.distinct().size == header.size) { "Powtórzone kolumny tabeli." }
            val descriptor = serializer.descriptor
            val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
            require(header.containsAll(names)) { "Brak wymaganych kolumn tabeli." }
            var count = 0
            while (true) {
                val row = csv.next() ?: break
                require(++count <= ReviewAnalysis.MAX_SAMPLES) { "Ten pomiar jest za długi do podglądu na telefonie." }
                require(row.size == header.size) { "Nieprawidłowy wiersz tabeli." }
                val cells = header.zip(row).toMap()
                val json = buildJsonObject {
                    names.forEachIndexed { index, name ->
                        val value = cells.getValue(name)
                        val field = descriptor.getElementDescriptor(index)
                        put(name, when {
                            value.isEmpty() && field.isNullable -> JsonNull
                            field.kind == PrimitiveKind.STRING -> JsonPrimitive(value)
                            else -> readerJson.parseToJsonElement(value).also { require(it is JsonPrimitive && !it.isString) }
                        })
                    }
                }
                consume(readerJson.decodeFromJsonElement(serializer, json))
            }
        }
    }
}

/** RFC 4180 records, including quoted commas, quotes and multiline notes; bounded cell/row size. */
internal class CsvRecords(private val reader: PushbackReader) {
    fun next(): List<String>? {
        val cells = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var seen = false; var rowSize = 0
        while (true) {
            val code = reader.read()
            if (code < 0) {
                require(!quoted) { "Niedomknięty tekst w tabeli." }
                return if (!seen && cells.isEmpty() && cell.isEmpty()) null else cells + cell.toString()
            }
            seen = true
            require(++rowSize <= 262144 && cell.length <= 65536 && cells.size <= 128) { "Zbyt długi wiersz tabeli." }
            val char = code.toChar()
            if (quoted) {
                if (char == '"') {
                    val after = reader.read()
                    if (after == '"'.code) cell.append('"') else { quoted = false; closed = true; if (after >= 0) reader.unread(after) }
                } else cell.append(char)
            } else when (char) {
                '"' -> { require(cell.isEmpty() && !closed); quoted = true }
                ',' -> { cells += cell.toString(); cell.clear(); closed = false }
                '\r', '\n' -> {
                    if (char == '\r') { val after = reader.read(); if (after >= 0 && after != '\n'.code) reader.unread(after) }
                    return cells + cell.toString()
                }
                else -> { require(!closed) { "Nieprawidłowe cudzysłowy w tabeli." }; cell.append(char) }
            }
        }
    }
}
