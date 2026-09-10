package pl.autoklinika.infrastructure.wifisurvey

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    var config = SurveyConfig()
    var mode = "OUTDOOR"
    private var exportOk = false

    suspend fun previewLoop() {
        val wifi = WifiSource(app)
        val location = LocationSource(app)
        var latest: LocationSample? = null
        try {
            probeExport()
            while (currentCoroutineContext().isActive) {
                if (!app.busy.value) {
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
        app.scope.launch { try { app.export(id) } finally { exporting.value = false } }
    }
}
