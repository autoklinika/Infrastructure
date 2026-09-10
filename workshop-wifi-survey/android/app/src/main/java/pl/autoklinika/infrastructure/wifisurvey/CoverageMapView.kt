package pl.autoklinika.infrastructure.wifisurvey

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream

/** Packaged map code, local overlays, no JavaScript bridge. Only viewport tiles leave the device. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun CoverageMapView(coverage: CoverageMap, selected: ReviewPoint?, modifier: Modifier = Modifier, followSelection: Boolean = false) {
    val context = LocalContext.current
    val html = remember(coverage) {
        context.assets.open("map/index.html").bufferedReader().use { it.readText() }
            .replace("__SURVEY_DATA__", mapSafeJson(surveyJson.encodeToString(coverage)))
    }
    val focus = selected?.takeIf { it.latitude != null && it.longitude != null }?.let {
        buildJsonObject {
            put("latitude", it.latitude); put("longitude", it.longitude); put("accuracy", it.accuracy)
            put("seconds", it.seconds); put("follow", followSelection)
            put("rssi", it.rssi); put("connected", it.connected); put("time", durationLabel(it.seconds))
        }.toString()
    } ?: "null"
    val currentFocus by rememberUpdatedState(focus)
    AndroidView(modifier = modifier, factory = { ctx ->
        WebView(ctx).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.domStorageEnabled = false; settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = "WorkshopWiFiSurvey/${BuildConfig.VERSION_NAME} (+https://github.com/autoklinika/Infrastructure)"
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") {
                        val name = uri.lastPathSegment
                        if (name in setOf("leaflet.js", "leaflet.css", "survey-map.js", "survey-map.css")) {
                            return WebResourceResponse(if (name!!.endsWith(".js")) "application/javascript" else "text/css",
                                "UTF-8", context.assets.open("map/$name"))
                        }
                    }
                    if (uri.scheme == "https" && uri.host == "tile.openstreetmap.org" &&
                        Regex("^/\\d{1,2}/\\d+/\\d+\\.png$").matches(uri.path.orEmpty())) return null
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.url.scheme == "https" && request.url.host == "www.openstreetmap.org")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.setSurveyFocus && window.setSurveyFocus($currentFocus)", null)
                }
            }
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
                false
            }
            loadDataWithBaseURL("https://appassets.androidplatform.net/map/", html, "text/html", "UTF-8", null)
        }
    }, update = { view ->
        if (view.tag != focus) { view.tag = focus; view.evaluateJavascript("window.setSurveyFocus && window.setSurveyFocus($focus)", null) }
    }, onRelease = { it.stopLoading(); it.destroy() })
}

internal fun mapSafeJson(json: String) = json.replace("<", "\\u003c").replace(">", "\\u003e")
    .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
