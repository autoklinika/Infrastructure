package pl.autoklinika.infrastructure.wifisurvey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Ink = Color(0xFF183B3C)
val Teal = Color(0xFF096B60)
val Paper = Color(0xFFF4F7F5)
val Muted = Color(0xFF526865)
val Amber = Color(0xFF916300)
val WeakRed = Color(0xFFB0393C)

@Composable
fun SurveyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
        primaryContainer = Color(0xFFD6EDE6), onPrimaryContainer = Ink,
        surface = Color.White, background = Paper, onSurface = Ink, onBackground = Ink,
        onSurfaceVariant = Muted, secondaryContainer = Color(0xFFD6EDE6), outline = Color(0xFF768C86),
        error = WeakRed), shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}

@Composable
fun SurveyIcon(kind: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val stroke = w * .085f
        when (kind) {
            "chart" -> {
                drawLine(color, Offset(w * .12f, h * .15f), Offset(w * .12f, h * .85f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .12f, h * .85f), Offset(w * .9f, h * .85f), stroke, StrokeCap.Round)
                listOf(.25f to .6f, .5f to .7f, .7f to .35f, .9f to .2f).zipWithNext().forEach { (a, b) ->
                    drawLine(color, Offset(a.first * w, a.second * h), Offset(b.first * w, b.second * h), stroke, StrokeCap.Round)
                }
            }
            else -> {
                for (fraction in listOf(1f, .67f, .34f)) {
                    val radius = w * .54f * fraction
                    drawArc(color, 222f, 96f, false, Offset(w / 2 - radius, h * .85f - radius),
                        Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                drawCircle(color, stroke * .6f, Offset(w / 2, h * .85f))
            }
        }
    }
}

@Composable
fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("WORKSHOP  /  WI-FI", style = MaterialTheme.typography.labelMedium, color = Teal, letterSpacing = 2.sp)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Muted)
    }
}

@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color.White) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

data class SetupHelp(val title: String, val text: String, val action: String? = null, val button: String = "Otwórz ustawienia")
fun setupHelp(checks: Preflight): SetupHelp? {
    val failed = checks.checks.filter { !it.ok && it.critical }.map { it.label }.toSet()
    return when {
        checks.checks.isEmpty() -> SetupHelp("Sprawdzam telefon…", "To potrwa chwilę.")
        "Baza / recovery" in failed -> SetupHelp("Przygotowuję zapis…", "Poprzednie pomiary pozostają zachowane.", "app")
        "Precise location" in failed || "Nearby Wi-Fi permission" in failed -> SetupHelp("Pozwól odczytać Wi-Fi i lokalizację", "W oknie Androida włącz dokładną lokalizację i zezwól na dostęp podczas używania aplikacji.", "permissions", "Nadaj dostęp")
        "Wi-Fi" in failed || "Połączenie Wi-Fi" in failed -> SetupHelp("Połącz telefon z Wi-Fi", "Wybierz sieć, której zasięg chcesz sprawdzić.", "wifi", "Wybierz sieć")
        "Location Services" in failed -> SetupHelp("Włącz lokalizację telefonu", "Dzięki niej wynik pokaże miejsca ze słabszym sygnałem.", "location", "Włącz lokalizację")
        "Foreground logging" in failed -> SetupHelp("Włącz powiadomienia aplikacji", "Telefon potrzebuje widocznego powiadomienia, żeby zapisywać pomiar.", "app")
        "Wolne miejsce" in failed -> SetupHelp("Zwolnij miejsce w telefonie", "Do rozpoczęcia potrzeba przynajmniej 100 MB.", "storage")
        "Eksport MediaStore" in failed -> SetupHelp("Sprawdź możliwość zapisu", "Nie udało się przygotować pliku z wynikiem.", "retry", "Sprawdź ponownie")
        "Źródło lokalizacji" in failed -> SetupHelp("Wyłącz symulowaną lokalizację", "Pomiar trasy potrzebuje rzeczywistej pozycji telefonu.", "app")
        "Wiek lokalizacji" in failed || "Accuracy" in failed -> SetupHelp("Czekam na pozycję GPS…", "Wyjdź na otwartą przestrzeń. Jeśli mierzysz w budynku, wybierz „W budynku”.")
        "SSID/BSSID" in failed -> SetupHelp("Czekam na odczyt sieci…", "Jeśli to trwa dłużej, sprawdź dostęp do dokładnej lokalizacji.", "app")
        "Google Play Services" in failed -> SetupHelp("Sprawdź usługi Google Play", "Są potrzebne do odczytu położenia. Włącz lub zaktualizuj je w telefonie.", "app")
        failed.isNotEmpty() -> SetupHelp("Telefon nie jest jeszcze gotowy", "Sprawdź informacje o telefonie poniżej.", "retry", "Sprawdź ponownie")
        else -> null
    }
}

@Composable
fun NewSurveyScreen(preflight: Preflight, wifi: WifiReading, error: String?, exporting: Boolean,
                    fix: (String) -> Unit, onMode: (String) -> Unit, onStart: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("OUTDOOR") }
    var details by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mode) { onMode(mode) }
    val help = setupHelp(preflight)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageHeading("Sprawdź zasięg", "Przejdź po terenie. Zobacz, gdzie Wi-Fi słabnie.")
            Surface(color = Ink, shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SurveyIcon("signal", Modifier.size(48.dp), Color(0xFFAFF0D8))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Twoja sieć", color = Color(0xFFB8D0C9), style = MaterialTheme.typography.labelLarge)
                        Text(wifi.ssid ?: if (wifi.connected) "Odczytuję nazwę…" else "Brak połączenia", color = Color.White,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (wifi.connected) Text("Sygnał: ${signalLabel(wifi.rssi).lowercase()}", color = Color(0xFFDCEDE7))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(200) }, label = { Text("Nazwa pomiaru (opcjonalnie)") },
                    placeholder = { Text("np. Spacer po warsztacie") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Text("Gdzie mierzysz?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("OUTDOOR" to "Na zewnątrz", "INDOOR" to "W budynku", "MIXED" to "Tu i tu").forEach { (value, label) ->
                        FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) })
                    }
                }
                if (mode != "OUTDOOR") Text("W budynku GPS może być niedokładny. Wykres sygnału będzie dostępny także bez trasy.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            WhiteCard {
                Text(help?.title ?: "Gotowe do pomiaru", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = if (help == null) Teal else Ink)
                Text(help?.text ?: "Naciśnij start i idź spokojnym krokiem. Trzymaj telefon rozłożony, z włączonym ekranem i bez kabla USB.", color = Muted)
                help?.action?.let { action -> OutlinedButton(onClick = { fix(action) }) { Text(help.button) } }
            }
            if (error != null) Text("Ostatnia próba nie powiodła się. Zapisane pomiary są zachowane. Sprawdź gotowość telefonu przed ponownym startem.", color = WeakRed)
            TextButton(onClick = { details = true }) { Text("Informacje o telefonie") }
        }
        Surface(color = Paper) {
            Button(onClick = {
                val defaultName = "Pomiar " + DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.now())
                onStart(name.trim().ifBlank { defaultName }, mode)
            }, enabled = preflight.canStart && !exporting,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 58.dp),
                shape = RoundedCornerShape(18.dp)) {
                Text(if (exporting) "Zapisuję plik…" else "Rozpocznij pomiar", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Informacje o telefonie") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            preflight.checks.forEach { Text("${if (it.ok) "✓" else "•"} ${it.label}\n${it.value}", style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Zamknij") } })
}

@Composable
fun ActiveScreen(live: LiveStatus, error: String?, onStop: () -> Unit, onNote: (String) -> Unit) {
    var noteDialog by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var noteSaved by rememberSaveable { mutableStateOf(false) }
    var stopping by rememberSaveable { mutableStateOf(false) }
    val saving = live.sessionId == null && live.sample != null
    val sample = live.sample
    val connected = sample?.network_transport_state == "WIFI_CONNECTED"
    val locationOk = sample?.location_id_at_capture != null && sample.quality_flags.split('|').none {
        it in setOf("MOCK_LOCATION", "LOCATION_POOR_ACCURACY", "LOCATION_ACCURACY_UNKNOWN") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageHeading(if (saving) "Zapisuję wynik…" else if (live.sessionId == null) "Przygotowuję…" else "Pomiar trwa", "Idź spokojnie. Telefon zapisuje wynik automatycznie.")
            Surface(color = Ink, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CZAS POMIARU", color = Color(0xFFB8D0C9), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
                    Text(durationLabel(live.durationSeconds.toDouble()), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light)
                    HorizontalDivider(color = Color.White.copy(alpha = .2f))
                    Text(if (sample == null) "Czekam na pierwszy odczyt…" else signalLabel(sample.rssi_dbm, connected),
                        color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(sample?.rssi_dbm?.let { "Siła sygnału: $it dBm" } ?: "Odczyt sygnału pojawi się za chwilę", color = Color(0xFFDCEDE7))
                    sample?.ssid?.let { Text(it, color = Color(0xFFDCEDE7), style = MaterialTheme.typography.bodySmall) }
                }
            }
            WhiteCard {
                Text(if (locationOk) "Trasa jest zapisywana" else "Czekam na dokładną lokalizację", fontWeight = FontWeight.SemiBold)
                Text(if (locationOk) "Dokładność GPS: około ${sample.location_accuracy_m_at_capture!!.toInt()} m."
                    else "Pomiar Wi-Fi nadal się zapisuje. Pozycja pojawi się, gdy telefon ją ustali.", color = Muted)
                Text("Zapisane odczyty: ${sample?.sequence_no ?: 0}", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            OutlinedButton(onClick = { noteDialog = true }, enabled = live.sessionId != null && !stopping,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Dodaj notatkę o miejscu") }
            if (noteSaved) Text("Notatka dodana do pomiaru.", color = Teal)
            if (error != null) Text("Wystąpił problem z pomiarem. Zapisane odczyty są zachowane.\n$error", color = WeakRed)
        }
        Button(onClick = { stopping = true; onStop() }, enabled = !stopping && !saving,
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(18.dp)) {
            Text(if (stopping || saving) "Zapisuję wynik…" else if (live.sessionId == null && live.sample == null) "Anuluj przygotowanie" else "Zakończ i zobacz wynik",
                style = MaterialTheme.typography.titleMedium)
        }
    }
    if (noteDialog) AlertDialog(onDismissRequest = { noteDialog = false }, title = { Text("Co to za miejsce?") },
        text = { OutlinedTextField(note, { note = it.take(4000) }, placeholder = { Text("np. Przy bramie, idę w stronę hali") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onNote(note); note = ""; noteSaved = true; noteDialog = false }, enabled = note.isNotBlank()) { Text("Zapisz notatkę") } },
        dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Anuluj") } })
}

fun sessionDate(session: SurveySession): String = runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(session.started_at_utc))
}.getOrDefault("Data niedostępna")
fun modeLabel(mode: String) = when (mode) { "OUTDOOR" -> "Na zewnątrz"; "INDOOR" -> "W budynku"; else -> "W budynku i na zewnątrz" }

@Composable
fun HistoryScreen(sessions: List<SurveySession>, onOpen: (String) -> Unit, onOpenFile: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("Moje pomiary", "Wybierz pomiar, aby zobaczyć sygnał i trasę.") }
        item { OutlinedButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Otwórz pomiar z pliku ZIP") } }
        if (sessions.isEmpty()) item {
            WhiteCard {
                SurveyIcon("chart", Modifier.size(40.dp), Teal)
                Text("Tutaj pojawią się wyniki", style = MaterialTheme.typography.titleLarge)
                Text("Rozpocznij pierwszy pomiar albo otwórz wcześniej zapisany plik ZIP.", color = Muted)
            }
        }
        items(sessions, key = { it.id }) { session ->
            Card(onClick = { onOpen(session.id) }, colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sessionDate(session), style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(session.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val duration = (session.ended_elapsed_ns?.minus(session.started_elapsed_ns) ?: 0) / 1e9
                    Text("${durationLabel(duration)}  ·  ${modeLabel(session.mode)}", color = Muted)
                    if (session.status == "INTERRUPTED") Text("Pomiar przerwany · część danych zachowana", color = Amber, style = MaterialTheme.typography.bodySmall)
                    Text("Zobacz wynik  →", color = Teal, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item { Text("Wyniki i trasy pozostają na Twoim telefonie.", style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
}
