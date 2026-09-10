package pl.autoklinika.infrastructure.wifisurvey

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.*
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun clockStamp() = Stamp(Instant.now().toString(), SystemClock.elapsedRealtimeNanos())
fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

class WifiSource(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val networks = ConcurrentHashMap<Network, WifiInfo>()
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val info = capabilities.transportInfo as? WifiInfo
            if (info != null) networks[network] = info else networks.remove(network)
        }
        override fun onLost(network: Network) { networks.remove(network) }
    }
    @SuppressLint("MissingPermission")
    fun start() {
        if (!registered) {
            // Include Wi-Fi without validated Internet, even while cellular/VPN is the default network.
            connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            registered = true
        }
    }
    fun close() {
        if (registered) { connectivity.unregisterNetworkCallback(callback); registered = false }
        networks.clear()
    }
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun read(): WifiReading = try {
        // Fresh primary-link polling. The CM callback is authoritative for network transport,
        // but its cached WifiInfo is not a new 1 Hz RSSI observation. Never reuse scan RSSI.
        val info = wifi.connectionInfo
        val connected = wifi.isWifiEnabled &&
            info.supplicantState == SupplicantState.COMPLETED && networks.isNotEmpty()
        if (!connected) WifiReading() else WifiReading(
            connected = true,
            ssid = info.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.removeSurrounding("\""),
            bssid = info.bssid?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" },
            rssi = info.rssi, frequency = info.frequency,
            rx = info.rxLinkSpeedMbps.takeIf { it >= 0 }, tx = info.txLinkSpeedMbps.takeIf { it >= 0 },
            maxRx = info.maxSupportedRxLinkSpeedMbps.takeIf { it >= 0 },
            maxTx = info.maxSupportedTxLinkSpeedMbps.takeIf { it >= 0 },
            standard = info.wifiStandard,
        )
    } catch (_: SecurityException) { WifiReading(flags = setOf("WIFI_PERMISSION_LOST")) }
}

class LocationSource(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    @SuppressLint("MissingPermission")
    fun start(onLocation: (Location) -> Unit, onFailure: () -> Unit) {
        if (callback != null) return
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach(onLocation) }
        }
        callback = cb
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(1000).setMaxUpdateDelayMillis(0).setMaxUpdateAgeMillis(0).build()
        client.requestLocationUpdates(request, cb, Looper.getMainLooper()).addOnFailureListener {
            if (callback === cb) { callback = null; onFailure() }
        }
    }
    fun close() { callback?.let { client.removeLocationUpdates(it) }; callback = null }
}

fun Location.toSample(sessionId: String) = LocationSample(
    id = UUID.randomUUID().toString(), session_id = sessionId, sequence_no = 0,
    timestamp_utc = Instant.ofEpochMilli(time).toString(), timestamp_elapsed_ns = elapsedRealtimeNanos,
    latitude = latitude, longitude = longitude, accuracy_m = if (hasAccuracy()) accuracy else null,
    altitude_m = if (hasAltitude()) altitude else null,
    vertical_accuracy_m = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    bearing_deg = if (hasBearing()) bearing else null,
    bearing_accuracy_deg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    speed_mps = if (hasSpeed()) speed else null,
    speed_accuracy_mps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    provider = provider, is_mock_if_available = isMock,
)

data class PreflightCheck(val label: String, val value: String, val ok: Boolean, val critical: Boolean = true)
data class Preflight(val checks: List<PreflightCheck> = emptyList()) {
    val canStart get() = checks.isNotEmpty() && checks.none { it.critical && !it.ok }
}

fun preflight(context: Context, wifi: WifiReading, location: LocationSample?, exportOk: Boolean,
              ready: Boolean, config: SurveyConfig, mode: String = "INDOOR"): Preflight {
    val fine = context.granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val services = context.getSystemService(LocationManager::class.java).isLocationEnabled
    val binding = MeasurementRules.bind(SystemClock.elapsedRealtimeNanos(), location, config)
    val notifications = context.getSystemService(NotificationManager::class.java)
    val channelEnabled = notifications.getNotificationChannel(SurveyService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    val free = context.filesDir.usableSpace
    return Preflight(listOf(
        PreflightCheck("Model", "${Build.MANUFACTURER} ${Build.MODEL}", Build.MODEL.startsWith("SM-F741"), false),
        PreflightCheck("Android / API", "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT >= 34),
        PreflightCheck("Wi-Fi", if (context.getSystemService(WifiManager::class.java).isWifiEnabled) "ON" else "OFF", context.getSystemService(WifiManager::class.java).isWifiEnabled),
        PreflightCheck("Połączenie Wi-Fi", if (wifi.connected) "YES" else "NO — połącz telefon z Wi-Fi", wifi.connected),
        PreflightCheck("SSID/BSSID", if (wifi.ssid != null && wifi.bssid != null) "czytelne" else "niedostępne — sprawdź zgody i lokalizację", wifi.ssid != null && wifi.bssid != null),
        PreflightCheck("Precise location", if (fine) "GRANTED" else "wymagana dokładna lokalizacja", fine),
        PreflightCheck("Location Services", if (services) "ON" else "OFF — włącz lokalizację", services),
        PreflightCheck("Wiek lokalizacji", binding.age?.let { "%.0f ms".format(it) } ?: "brak fix — wyjdź na zewnątrz i poczekaj", binding.location != null, mode == "OUTDOOR"),
        PreflightCheck("Accuracy", location?.accuracy_m?.let { "$it m" } ?: "brak", location?.accuracy_m?.let { it <= config.accuracy_exclusion_m } == true, mode == "OUTDOOR"),
        PreflightCheck("Źródło lokalizacji", if (location?.is_mock_if_available == true) "MOCK" else "brak mock", location?.is_mock_if_available != true, mode == "OUTDOOR"),
        PreflightCheck("Nearby Wi-Fi permission", if (context.granted(Manifest.permission.NEARBY_WIFI_DEVICES)) "GRANTED" else "wymagana zgoda", context.granted(Manifest.permission.NEARBY_WIFI_DEVICES)),
        PreflightCheck("Skanowanie AP", if (config.scan_collection_enabled) "włączone; Android może ograniczać częstotliwość" else "wyłączone", true, false),
        PreflightCheck("Foreground logging", if (fine && services && notifications.areNotificationsEnabled() && channelEnabled) "gotowe do START" else "włącz lokalizację i powiadomienia aplikacji", fine && services && notifications.areNotificationsEnabled() && channelEnabled),
        PreflightCheck("Google Play Services", "Fused Location Provider", GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS),
        PreflightCheck("Eksport MediaStore", if (exportOk) "Download/WorkshopWiFiSurvey — zapis sprawdzony" else "wykonaj kontrolę celu eksportu", exportOk),
        PreflightCheck("Wolne miejsce", "${free / 1024 / 1024} MiB (minimum 100 MiB)", free >= config.min_start_free_bytes),
        PreflightCheck("Baza / recovery", if (ready) "gotowe" else "inicjalizacja lub błąd bazy", ready),
        PreflightCheck("Wi-Fi RTT", if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) "YES (pomiar wyłączony)" else "NO", true, false),
    ))
}
