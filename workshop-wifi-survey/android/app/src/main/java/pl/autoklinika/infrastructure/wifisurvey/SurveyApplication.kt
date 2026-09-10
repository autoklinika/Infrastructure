package pl.autoklinika.infrastructure.wifisurvey

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class LiveStatus(val sessionId: String? = null, val sample: ConnectedWifiSample? = null,
                      val locations: Long = 0, val durationSeconds: Long = 0, val message: String = "")

class SurveyApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: SurveyRepository
    lateinit var initialization: Deferred<Unit>
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val live = MutableStateFlow(LiveStatus())
    val error = MutableStateFlow<String?>(null)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SurveyService.CHANNEL, "Trwający pomiar Wi-Fi", NotificationManager.IMPORTANCE_LOW))
        repository = SurveyRepository(SurveyDatabase.open(this))
        initialization = scope.async {
            try { repository.recover(clockStamp().utc); ready.value = true }
            catch (failure: Exception) { error.value = "Nie można otworzyć/odzyskać bazy: ${failure.javaClass.simpleName}"; throw failure }
        }
    }

    suspend fun export(id: String) {
        try {
            val result = repository.export(id, clockStamp()) { SurveyExporter.publish(this, repository.dao, it) }
            live.value = live.value.copy(message = "Eksport zapisany: ${result.name}")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error.value = "Eksport nieudany (${failure.javaClass.simpleName}). Baza zachowana; ponów eksport z listy sesji."
        }
    }
}
