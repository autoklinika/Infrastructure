package pl.autoklinika.infrastructure.wifisurvey

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

class SurveyViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SurveyApplication
    val preflight = MutableStateFlow(Preflight())
    val previewWifi = MutableStateFlow(WifiReading())
    val exporting = MutableStateFlow(false)
    val review = MutableStateFlow<SurveyReview?>(null)
    val reviewLoading = MutableStateFlow(false)
    val reviewError = MutableStateFlow<String?>(null)
    val reviewing = MutableStateFlow(false)
    val imported = MutableStateFlow(false)
    private var reviewJob: Job? = null
    private var reviewRequest = 0L
    var config = SurveyConfig()
    var mode = "OUTDOOR"
    var previewEnabled = true
    private var exportOk = false

    suspend fun previewLoop() {
        val wifi = WifiSource(app)
        val location = LocationSource(app)
        var latest: LocationSample? = null
        try {
            probeExport()
            while (currentCoroutineContext().isActive) {
                if (!app.busy.value && previewEnabled) {
                    if (app.granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        runCatching { wifi.start() }
                        runCatching { location.start({ latest = it.toSample("preflight") }, { latest = null }) }
                    } else { wifi.close(); location.close(); latest = null }
                    val reading = wifi.read()
                    previewWifi.value = reading
                    preflight.value = preflight(app, reading, latest, exportOk, app.ready.value, config, mode)
                } else { wifi.close(); location.close(); latest = null }
                delay(500)
            }
        } finally { location.close(); wifi.close() }
    }
    suspend fun probeExport() { exportOk = withContext(Dispatchers.IO) { SurveyExporter.probeTarget(app) } }
    fun recheckExport() { viewModelScope.launch { probeExport() } }

    fun start(request: StartRequest) {
        if (!preflight.value.canStart || app.busy.value) return
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val vpn = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        val capturedConfig = request.config.copy(vpn_active = vpn)
        val intent = Intent(app, SurveyService::class.java).setAction(SurveyService.START)
            .putExtra("name", request.name.trim()).putExtra("mode", request.mode)
            .putExtra("route", request.route).putExtra("filter", request.filter).putExtra("notes", request.notes)
            .putExtra("config", surveyJson.encodeToString(capturedConfig))
        app.error.value = null
        app.live.value = LiveStatus()
        app.busy.value = true
        try { app.startForegroundService(intent) }
        catch (failure: Exception) { app.busy.value = false; app.error.value = "START nieudany: ${failure.javaClass.simpleName}" }
    }
    fun stop() { app.startService(Intent(app, SurveyService::class.java).setAction(SurveyService.STOP)) }
    fun note(text: String) { app.startService(Intent(app, SurveyService::class.java).setAction(SurveyService.NOTE).putExtra("text", text)) }
    fun export(id: String) {
        if (exporting.value || app.busy.value) return
        exporting.value = true
        // Export survives Activity rotation/background; Room remains the source if the process dies.
        app.scope.launch {
            try {
                app.export(id)
                val updatedSession = runCatching { app.repository.dao.session(id) }.getOrNull()
                withContext(Dispatchers.Main.immediate) {
                    if (updatedSession != null && !imported.value && review.value?.session?.id == id) {
                        review.value = review.value?.copy(session = updatedSession)
                    }
                }
            } finally { exporting.value = false }
        }
    }

    fun closeReview() {
        reviewRequest++; reviewJob?.cancel(); reviewing.value = false; review.value = null; reviewLoading.value = false
    }
    fun showSession(id: String) = openReview(false) { ReviewAnalysis.load(app.repository.dao, id) }
    fun openLog(uri: Uri) = openReview(true) {
        app.contentResolver.openInputStream(uri)?.use { SurveyLogReader.read(it, app.cacheDir) }
            ?: error("Nie można odczytać wybranego pliku.")
    }
    private fun openReview(fromFile: Boolean, load: suspend () -> SurveyReview) {
        reviewJob?.cancel()
        val request = ++reviewRequest
        reviewing.value = true; imported.value = fromFile; review.value = null
        reviewError.value = null; reviewLoading.value = true
        reviewJob = viewModelScope.launch {
            try { review.value = withContext(Dispatchers.IO) { load() } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // Do not surface parser fragments containing private row values or implementation details.
                reviewError.value = if (failure is IllegalArgumentException && failure.message?.let {
                    it.startsWith("Ten pomiar") || it.startsWith("Plik jest") || it.startsWith("Ta wersja")
                } == true) failure.message else "Nie udało się otworzyć pomiaru. Wybierz kompletny, oryginalny plik ZIP z tej aplikacji."
            } finally { if (request == reviewRequest) reviewLoading.value = false }
        }
    }
}
