package pl.autoklinika.infrastructure.wifisurvey

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import java.util.UUID

class SurveyService : Service() {
    companion object {
        const val CHANNEL = "active_survey"
        const val START = "START"
        const val STOP = "STOP"
        const val NOTE = "NOTE"
        private const val NOTIFICATION = 1001
    }
    private sealed interface Command {
        data object Tick : Command
        data class Fix(val sample: LocationSample) : Command
        data class Note(val text: String) : Command
        data class Stop(val reason: String, val interrupted: Boolean = false) : Command
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Command>(64)
    private val app get() = application as SurveyApplication
    private lateinit var wifi: WifiSource
    private lateinit var location: LocationSource
    private var ticker: Job? = null
    private var actor: Job? = null
    private var accepting = false
    private var starting = false
    private var sessionId: String? = null
    private var finishing = false
    private var warmupLocation: LocationSample? = null

    override fun onCreate() { super.onCreate(); wifi = WifiSource(this); location = LocationSource(this) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!starting && sessionId == null) {
                starting = true; app.busy.value = true
                try {
                    startForeground(NOTIFICATION, notification("Przygotowanie sesji…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    actor = scope.launch { begin(intent) }
                } catch (failure: Exception) {
                    app.error.value = "Nie udało się rozpocząć zapisu. Sprawdź dostęp do lokalizacji i powiadomień aplikacji."
                    app.busy.value = false; stopSelf()
                }
            }
            STOP -> requestStop("OPERATOR_STOP")
            NOTE -> if (accepting) scope.launch { queue.send(Command.Note(intent.getStringExtra("text").orEmpty())) }
        }
        return START_NOT_STICKY
    }

    private suspend fun begin(intent: Intent) {
        try {
            app.initialization.await()
            wifi.start()
            location.start({ warmupLocation = it.toSample("preflight") }, {})
            // Wait for the new transport callback; a cached UI check alone cannot authorize a new session.
            repeat(30) { if (!wifi.read().connected) delay(100) }
            val config = surveyJson.decodeFromString<SurveyConfig>(intent.getStringExtra("config") ?: error("Missing config"))
            val targetOk = withContext(Dispatchers.IO) { SurveyExporter.probeTarget(this@SurveyService) }
            val mode = intent.getStringExtra("mode") ?: "OUTDOOR"
            if (mode == "OUTDOOR") {
                repeat(100) { if (warmupLocation == null) delay(100) }
            }
            val check = preflight(this, wifi.read(), warmupLocation, targetOk, app.ready.value, config, mode)
            if (!check.canStart) {
                app.error.value = "START zablokowany: " + check.checks.filter { it.critical && !it.ok }
                    .joinToString("; ") { "${it.label}: ${it.value}" }
                closeSources(); stopSelf(); return
            }
            val stamp = clockStamp()
            val id = UUID.randomUUID().toString()
            val value = SurveySession(id = id, name = intent.getStringExtra("name").orEmpty().trim().also { require(it.isNotBlank()) },
                mode = intent.getStringExtra("mode") ?: "OUTDOOR", route_profile = intent.getStringExtra("route"),
                started_at_utc = stamp.utc, started_elapsed_ns = stamp.elapsed,
                app_version_name = BuildConfig.VERSION_NAME, app_version_code = BuildConfig.VERSION_CODE,
                git_commit = BuildConfig.GIT_COMMIT, device_manufacturer = Build.MANUFACTURER, device_model = Build.MODEL,
                android_release = Build.VERSION.RELEASE, android_sdk = Build.VERSION.SDK_INT,
                ssid_filter = intent.getStringExtra("filter"), notes = intent.getStringExtra("notes"),
                configuration_snapshot_json = surveyJson.encodeToString(config))
            withContext(Dispatchers.IO) { app.repository.start(value) }
            sessionId = id; accepting = true
            app.live.value = LiveStatus(sessionId = id)
            location.close()
            startLocation(id)
            ticker = scope.launch {
                while (isActive && accepting) { queue.send(Command.Tick); delay(config.connected_interval_ms) }
            }
            var locations = 0L
            for (command in queue) {
                when (command) {
                    is Command.Fix -> {
                        withContext(Dispatchers.IO) { app.repository.location(command.sample) }
                        locations++
                    }
                    Command.Tick -> {
                        if (filesDir.usableSpace < config.stop_free_bytes) {
                            closeSources(); finish("LOW_STORAGE", true); break
                        }
                        val reading = wifi.read()
                        val sampleStamp = clockStamp()
                        val sample = withContext(Dispatchers.IO) { app.repository.wifi(reading, sampleStamp) }
                        app.live.value = LiveStatus(id, sample, locations, (sampleStamp.elapsed - stamp.elapsed) / 1_000_000_000)
                        // Idempotent while subscribed; retries a failed FLP registration every ten samples.
                        if ((sample?.sequence_no ?: 0) % 10L == 0L && accepting) startLocation(id)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                            notification("Pomiar trwa · ${durationLabel(app.live.value.durationSeconds.toDouble())} · ${sample?.sequence_no ?: 0} odczytów"))
                    }
                    is Command.Note -> withContext(Dispatchers.IO) { app.repository.note(clockStamp(), command.text) }
                    is Command.Stop -> { finish(command.reason, command.interrupted); break }
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            app.error.value = "Pomiar został przerwany. Sprawdź gotowość telefonu. Dotychczasowe odczyty są zachowane."
            closeSources()
            withContext(Dispatchers.IO) { runCatching { app.repository.stop(clockStamp(), "COLLECTOR_ERROR", true) } }
            stopSelf()
        }
    }

    private fun startLocation(id: String) {
        try {
            location.start({ fix ->
                if (accepting && !queue.trySend(Command.Fix(fix.toSample(id))).isSuccess) {
                    app.error.value = "Telefon nie nadąża z zapisem. Pomiar zostanie zakończony z zachowaniem dotychczasowych danych."
                    requestStop("LOCATION_QUEUE_OVERFLOW", true)
                }
            }, {
                app.error.value = "Telefon nie ustalił pozycji. Odczyty Wi-Fi nadal się zapisują, chwilowo bez miejsca na trasie."
                if (accepting) scope.launch { queue.send(Command.Note("LOCATION_PROVIDER_ERROR")) }
            })
        } catch (_: SecurityException) {
            app.error.value = "Utracono dostęp do lokalizacji. Przywróć zgodę w ustawieniach aplikacji. Dotychczasowe odczyty są zachowane."
        }
    }

    private fun closeSources() { accepting = false; ticker?.cancel(); location.close() }
    private fun requestStop(reason: String, interrupted: Boolean = false) {
        if (finishing) return
        if (sessionId == null) { actor?.cancel(); stopSelf(); return }
        finishing = true
        closeSources()
        scope.launch { queue.send(Command.Stop(reason, interrupted)) }
    }
    private suspend fun finish(reason: String, interrupted: Boolean) {
        finishing = true; closeSources()
        val ended = withContext(Dispatchers.IO) { app.repository.stop(clockStamp(), reason, interrupted) }
        wifi.close()
        app.live.value = app.live.value.copy(sessionId = null, message = "Sesja zapisana. Tworzenie ZIP…")
        if (ended != null) withContext(Dispatchers.IO) { app.export(ended.id) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, SurveyService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_survey).setContentTitle("Workshop WiFi Survey")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Zakończ pomiar", stop).build()).build()
    }
    override fun onDestroy() {
        closeSources(); wifi.close(); scope.cancel(); queue.close()
        app.scope.launch {
            runCatching { app.repository.stop(clockStamp(), "SERVICE_DESTROYED", true) }.onFailure {
                app.ready.value = false
                app.error.value = "Nie udało się zamknąć sesji w bazie. Zwolnij miejsce i uruchom aplikację ponownie, aby odzyskać sesję."
            }
            app.live.value = app.live.value.copy(sessionId = null)
            app.busy.value = false
        }
        super.onDestroy()
    }
}
