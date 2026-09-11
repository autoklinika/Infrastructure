package pl.autoklinika.infrastructure.wifisurvey

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SurveyWidgetTest {
    @Test fun remoteViewsFitCoverAndNoteTargetsOnlyTheCollector() = checkWidget(1f)
    @Test fun largerFontStillFitsCoverWithAccessibleButtons() = checkWidget(1.3f)
    private fun checkWidget(scale: Float) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val displayContext = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = scale })
        val live = LiveStatus(sessionId = "SYNTHETIC", durationSeconds = 3601, freshAps = 80, scanAgeSeconds = 100)
        val view = SurveyWidget.render(displayContext, live).apply(displayContext, FrameLayout(displayContext))
        val density = context.resources.displayMetrics.density
        val width = (352 * density).toInt(); val height = (339 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val actions = view.findViewById<View>(R.id.widget_actions)
        assertTrue("Actions bottom ${actions.bottom}, available ${height - view.paddingBottom}", actions.bottom <= height - view.paddingBottom)
        val sizes = listOf(R.id.widget_title, R.id.widget_time, R.id.widget_signal, R.id.widget_gps, R.id.widget_aps, R.id.widget_updated, R.id.widget_actions, R.id.widget_note)
            .joinToString { id -> "${context.resources.getResourceEntryName(id)}=${view.findViewById<View>(id).height}" }
        assertTrue("density=$density font=${context.resources.configuration.fontScale} $sizes", view.findViewById<View>(R.id.widget_note).height >= 48 * density)
        view.findViewById<View>(R.id.widget_note).performClick()
        val intent = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(SurveyService.NOTE, intent.action)
        assertEquals(SurveyService::class.java.name, intent.component!!.className)
        assertTrue(intent.getStringExtra("text")!!.contains("małym ekranie"))
    }
    @Test fun inactiveWidgetHasNoActionThatCanStartABackgroundLocationSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = SurveyWidget.render(context, LiveStatus()).apply(context, FrameLayout(context))
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_actions).visibility)
    }
}
