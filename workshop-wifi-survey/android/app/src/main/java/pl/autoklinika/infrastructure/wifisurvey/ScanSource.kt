package pl.autoklinika.infrastructure.wifisurvey

import android.annotation.SuppressLint
import android.content.*
import android.net.wifi.WifiManager

/** Passive broadcasts include scans initiated by Android. Requests are audited separately: no false attribution. */
class ScanSource(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var receiver: BroadcastReceiver? = null
    val throttled get() = wifi.isScanThrottleEnabled

    @SuppressLint("MissingPermission")
    fun start(receive: (ScanBatch) -> Unit, failure: () -> Unit) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                val stamp = clockStamp()
                try {
                    @Suppress("DEPRECATION")
                    val readings = wifi.scanResults.map { result ->
                        ScanReading(result.wifiSsid?.toString()?.removeSurrounding("\""),
                            result.BSSID?.takeUnless { it == "02:00:00:00:00:00" }, result.level, result.frequency,
                            result.channelWidth, result.capabilities, result.is80211mcResponder, result.timestamp)
                    }
                    receive(ScanBatch(stamp, intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false), readings))
                } catch (_: SecurityException) { failure() }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun request(): Boolean = wifi.startScan()

    fun close() { receiver?.let { context.unregisterReceiver(it) }; receiver = null }
}
