package pl.autoklinika.infrastructure.wifisurvey

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.view.View
import android.widget.RemoteViews
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class SurveyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, (context.applicationContext as SurveyApplication).live.value)
    }

    companion object {
        fun update(context: Context, live: LiveStatus, feedback: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SurveyWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context, live, feedback))
        }

        internal fun render(context: Context, live: LiveStatus, feedback: String? = null): RemoteViews {
            val active = live.sessionId != null
            val sample = live.sample
            val views = RemoteViews(context.packageName, R.layout.survey_widget)
            val connected = sample?.network_transport_state == "WIFI_CONNECTED"
            val rssi = sample?.rssi_dbm?.takeIf { it in -126..0 && connected }
            views.setTextViewText(R.id.widget_title, if (active) "Pomiar Wi-Fi" else "Pomiar zapisany")
            views.setTextViewText(R.id.widget_time, if (active) durationLabel(live.durationSeconds.toDouble()) else "Gotowe")
            views.setTextViewText(R.id.widget_signal, if (active) signalLabel(rssi, connected) + (rssi?.let { " · $it dBm" } ?: "") else "Otwórz telefon, aby zobaczyć mapę")
            views.setTextColor(R.id.widget_signal, if (!active || rssi != null && rssi >= -67) 0xFF79E1C4.toInt() else 0xFFFFD08A.toInt())
            val gps = if (sample?.location_id_at_capture != null && sample.quality_flags.split('|').none {
                    it in setOf("MOCK_LOCATION", "LOCATION_POOR_ACCURACY", "LOCATION_ACCURACY_UNKNOWN") })
                "GPS ±${sample.location_accuracy_m_at_capture?.roundToInt()} m" else "GPS: czekam na pozycję"
            views.setTextViewText(R.id.widget_gps, if (active) gps else "Nowy pomiar rozpocznij na dużym ekranie")
            views.setTextViewText(R.id.widget_aps, if (!active) "" else when {
                live.scanAgeSeconds == null -> "AP: szukam świeżego skanu"
                live.scanAgeSeconds > 45 -> "AP: skan sprzed ${live.scanAgeSeconds} s"
                else -> "AP: ${live.freshAps} · skan ${live.scanAgeSeconds} s temu"
            })
            views.setTextViewText(R.id.widget_updated, feedback ?: "Odczyt: ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}")
            views.setViewVisibility(R.id.widget_actions, if (active) View.VISIBLE else View.GONE)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            views.setOnClickPendingIntent(R.id.widget_note, PendingIntent.getService(context, 20,
                Intent(context, SurveyService::class.java).setAction(SurveyService.NOTE).putExtra("text", "Punkt oznaczony na małym ekranie"), flags))
            views.setOnClickPendingIntent(R.id.widget_stop, PendingIntent.getService(context, 21,
                Intent(context, SurveyService::class.java).setAction(SurveyService.STOP), flags))
            return views
        }
    }
}

fun scanStatusLabel(live: LiveStatus): String = when {
    live.scanAgeSeconds == null -> "AP: czekam na świeży skan"
    live.scanAgeSeconds > 45 -> "AP: brak nowego skanu od ${live.scanAgeSeconds} s"
    else -> "Ostatni skan: ${live.freshAps} świeżych AP · ${live.scanAgeSeconds} s temu"
}
