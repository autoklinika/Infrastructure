package pl.autoklinika.infrastructure.wifisurvey

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: SurveyViewModel = viewModel()
            val busy by vm.app.busy.collectAsStateWithLifecycle()
            DisposableEffect(busy) {
                if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF15635B))) {
                Surface(Modifier.fillMaxSize()) { SurveyScreen(vm) }
            }
        }
    }

    @Composable
    private fun SurveyScreen(vm: SurveyViewModel) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.previewLoop() } }
        val preflight by vm.preflight.collectAsStateWithLifecycle()
        val previewWifi by vm.previewWifi.collectAsStateWithLifecycle()
        val busy by vm.app.busy.collectAsStateWithLifecycle()
        val live by vm.app.live.collectAsStateWithLifecycle()
        val error by vm.app.error.collectAsStateWithLifecycle()
        val exporting by vm.exporting.collectAsStateWithLifecycle()
        val sessions by remember { vm.app.repository.dao.sessions() }.collectAsStateWithLifecycle(emptyList())
        var name by rememberSaveable { mutableStateOf("") }
        var mode by rememberSaveable { mutableStateOf("OUTDOOR") }
        var route by rememberSaveable { mutableStateOf("SITE_BASELINE_V1") }
        var filter by rememberSaveable { mutableStateOf("") }
        var notes by rememberSaveable { mutableStateOf("") }
        var interval by rememberSaveable { mutableStateOf("1000") }
        var maxAge by rememberSaveable { mutableStateOf("2500") }
        var mobile by rememberSaveable { mutableStateOf("OPERATOR_NOT_RECORDED") }
        var bluetooth by rememberSaveable { mutableStateOf("OPERATOR_NOT_RECORDED") }
        var activeNote by rememberSaveable { mutableStateOf("") }
        val intervalValue = interval.toLongOrNull()
        val ageValue = maxAge.toLongOrNull()
        val fieldsValid = name.isNotBlank() && intervalValue != null && intervalValue in 500..10000 && ageValue != null && ageValue in 0..60000
        SideEffect {
            vm.mode = mode
            if (fieldsValid) vm.config = SurveyConfig(connected_interval_ms = intervalValue, max_location_age_ms = ageValue,
                mobile_data_state = mobile, bluetooth_state = bluetooth)
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.recheckExport() }
        Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Workshop WiFi Survey", style = MaterialTheme.typography.headlineMedium)
            Text("Stage 1 • CONNECTED_LINK • schema $SCHEMA_VERSION", style = MaterialTheme.typography.labelLarge)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) {
                Text("Aktywny survey", style = MaterialTheme.typography.titleLarge)
                if (live.sessionId == null) Text("Przygotowanie lub eksport — poczekaj…")
                live.sample?.let { sample ->
                    Text("${sample.ssid ?: "SSID niedostępne"}\n${sample.bssid ?: "BSSID niedostępne"}")
                    Text("${sample.rssi_dbm ?: "—"} dBm • ${MeasurementRules.rssiGrade(sample.rssi_dbm)}", style = MaterialTheme.typography.headlineSmall)
                    Text("${sample.band ?: "—"} • kanał ${sample.channel ?: "—"} • ${sample.frequency_mhz ?: "—"} MHz")
                    Text("Lokalizacja: ${sample.location_accuracy_m_at_capture ?: "—"} m • wiek ${sample.location_age_ms?.toLong() ?: "—"} ms")
                    Text("Wi-Fi: ${sample.sequence_no} • lokalizacje: ${live.locations} • czas: ${live.durationSeconds} s")
                    Text(sample.quality_flags)
                }
                Text("Telefon rozłożony, ekran aktywny. Bez USB, Phone Link i speedtestu podczas pomiaru.")
                OutlinedTextField(activeNote, { activeNote = it.take(4000) }, label = { Text("Notatka podczas pomiaru") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.note(activeNote); activeNote = "" }, enabled = activeNote.isNotBlank() && live.sessionId != null) { Text("ADD NOTE") }
                Button(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text("STOP SURVEY") }
            } else {
                Text("New Survey", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it.take(200) }, label = { Text("Nazwa sesji") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("OUTDOOR", "INDOOR", "MIXED").forEach { choice ->
                        FilterChip(selected = mode == choice, onClick = { mode = choice }, label = { Text(choice) })
                    }
                }
                if (mode != "OUTDOOR") Text("Stage 1 zapisuje surowy GPS także wewnątrz. Anchory i plan indoor będą w Stage 4; próbki bez fix pozostają UNLOCATED.")
                OutlinedTextField(route, { route = it.take(200) }, label = { Text("route_profile") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(filter, { filter = it.take(200) }, label = { Text("Opcjonalny filtr SSID") }, modifier = Modifier.fillMaxWidth())
                Text("Filtr oznacza próbki spoza SSID; wszystkie pomiary pozostają w bazie.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("Notatka / warunki pomiaru") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(interval, { interval = it }, label = { Text("Connected interval [ms], 500–10000") }, isError = intervalValue == null || intervalValue !in 500..10000, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maxAge, { maxAge = it }, label = { Text("Maks. wiek lokalizacji [ms], 0–60000") }, isError = ageValue == null || ageValue !in 0..60000, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mobile, { mobile = it.take(100) }, label = { Text("Dane komórkowe: zapisz ON / OFF") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bluetooth, { bluetooth = it.take(100) }, label = { Text("Bluetooth: zapisz ON / OFF") }, modifier = Modifier.fillMaxWidth())
                Text("Preflight", style = MaterialTheme.typography.titleLarge)
                preflight.checks.forEach { check ->
                    Text("${if (check.ok) "OK" else if (check.critical) "BLOKADA" else "UWAGA"} • ${check.label}: ${check.value}",
                        color = if (!check.ok && check.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (previewWifi.connected) Text("Połączenie: ${previewWifi.ssid ?: "—"} • ${previewWifi.rssi ?: "—"} dBm")
                Button(onClick = { permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Nadaj wymagane uprawnienia") }
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Ustawienia lokalizacji") }
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("Ustawienia aplikacji i powiadomień") }
                OutlinedButton(onClick = vm::recheckExport) { Text("Sprawdź zapis eksportu ponownie") }
                Button(onClick = { vm.start(StartRequest(name, mode, route.ifBlank { null }, filter.ifBlank { null }, notes.ifBlank { null }, vm.config)) },
                    enabled = fieldsValid && preflight.canStart && !exporting, modifier = Modifier.fillMaxWidth()) { Text("START SURVEY") }
                if (!preflight.canStart) Text("START zablokowany — usuń pozycje BLOKADA powyżej.", color = MaterialTheme.colorScheme.error)
                if (live.message.isNotBlank()) Text(live.message)
                Text("Zapisane sesje", style = MaterialTheme.typography.titleLarge)
                sessions.forEach { session -> SessionCard(vm, session, exporting) }
                Text("Eksporty zawierają prywatne GPS i identyfikatory Wi-Fi. Pobieraj je wyłącznie do sprawdzonego katalogu data/.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun SessionCard(vm: SurveyViewModel, session: SurveySession, exporting: Boolean) {
        var summary by remember(session.id, session.status, session.export_status) { mutableStateOf("") }
        LaunchedEffect(session.id, session.status, session.export_status) {
            summary = withContext(Dispatchers.IO) {
                val dao = vm.app.repository.dao
                val count = dao.wifiCount(session.id)
                val located = dao.locatedCount(session.id)
                "Wi-Fi $count • GPS ${dao.locationCount(session.id)} • powiązane $located/$count • BSSID transitions ${dao.transitionCount(session.id)}"
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.name, style = MaterialTheme.typography.titleMedium)
                Text("${session.status} • ${session.mode}")
                Text(summary)
                session.ended_elapsed_ns?.let { Text("Czas ${(it - session.started_elapsed_ns) / 1_000_000_000}s${if (session.status == "INTERRUPTED") " (przerwana; patrz eventy)" else ""}") }
                Text("Eksport: ${session.export_status}")
                session.export_name?.let { Text("Download/WorkshopWiFiSurvey/$it") }
                session.export_error?.let { Text("Błąd: $it. Można ponowić.") }
                if (session.status != "ACTIVE") OutlinedButton(onClick = { vm.export(session.id) }, enabled = !exporting) { Text("Eksportuj ZIP ponownie") }
            }
        }
    }
}
