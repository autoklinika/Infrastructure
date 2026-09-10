package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import android.hardware.*
import android.os.PowerManager
import android.os.SystemClock

/** Keep the collector's CPU running only during a user-started recording, with a bounded lease. */
class RecordingPower(context: Context) : SensorEventListener {
    private val power = context.getSystemService(PowerManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkshopWiFiSurvey:recording").apply { setReferenceCounted(false) }
    private var renewed = 0L
    var fold = "UNAVAILABLE"; private set
    val interactive get() = power.isInteractive
    fun start() {
        renew(force = true)
        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    fun renew(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - renewed >= 5 * 60_000 || !lock.isHeld) { lock.acquire(10 * 60_000L); renewed = now }
    }
    fun close() { sensors.unregisterListener(this); if (lock.isHeld) lock.release() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        fold = when { event.values[0] <= 5 -> "CLOSED"; event.values[0] >= 170 -> "OPEN"; else -> "PARTIAL" }
    }
}
