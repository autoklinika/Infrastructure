package pl.autoklinika.infrastructure.wifisurvey

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val vm: SurveyViewModel = viewModel()
            val busy by vm.app.busy.collectAsStateWithLifecycle()
            DisposableEffect(busy) {
                if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            SurveyTheme { SurveyApp(vm) }
        }
    }

    @Composable
    private fun SurveyApp(vm: SurveyViewModel) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.previewLoop() } }
        val preflight by vm.preflight.collectAsStateWithLifecycle()
        val wifi by vm.previewWifi.collectAsStateWithLifecycle()
        val busy by vm.app.busy.collectAsStateWithLifecycle()
        val live by vm.app.live.collectAsStateWithLifecycle()
        val error by vm.app.error.collectAsStateWithLifecycle()
        val exporting by vm.exporting.collectAsStateWithLifecycle()
        val reviewing by vm.reviewing.collectAsStateWithLifecycle()
        val review by vm.review.collectAsStateWithLifecycle()
        val loading by vm.reviewLoading.collectAsStateWithLifecycle()
        val reviewError by vm.reviewError.collectAsStateWithLifecycle()
        val imported by vm.imported.collectAsStateWithLifecycle()
        val sessions by remember { vm.app.repository.dao.sessions() }.collectAsStateWithLifecycle(emptyList())
        var tab by rememberSaveable { mutableIntStateOf(0) }
        SideEffect { vm.previewEnabled = tab == 0 && !reviewing && !busy }
        var lastActive by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(busy, live.sessionId) {
            if (busy && live.sessionId != null) lastActive = live.sessionId
            if (!busy && lastActive != null) {
                val id = lastActive!!; lastActive = null; tab = 1; vm.showSession(id)
            }
        }
        BackHandler(reviewing && !busy) { vm.closeReview(); tab = 1 }
        BackHandler(!reviewing && !busy && tab == 1) { tab = 0 }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.recheckExport() }
        val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::openLog) }
        val requestPermissions = {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS))
        }
        val fixCheck: (String) -> Unit = { action ->
            when (action) {
                "permissions" -> requestPermissions()
                "wifi" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                "location" -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                "app" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                "storage" -> startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                else -> vm.recheckExport()
            }
        }
        Scaffold(containerColor = Paper, bottomBar = {
            if (!busy && !reviewing) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { SurveyIcon("signal") }, label = { Text("Nowy pomiar") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { SurveyIcon("chart") }, label = { Text("Moje pomiary") })
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when {
                    busy -> ActiveScreen(live, error, vm::stop, vm::note)
                    reviewing -> ReviewScreen(review, loading, reviewError, imported, exporting,
                        onBack = { vm.closeReview(); tab = 1 }, onExport = { review?.let { vm.export(it.session.id) } })
                    tab == 0 -> NewSurveyScreen(preflight, wifi, error, exporting, fixCheck,
                        onMode = { vm.mode = it }, onStart = { name, mode ->
                            vm.config = SurveyConfig(); vm.mode = mode
                            vm.start(StartRequest(name, mode, null, null, null, SurveyConfig()))
                        })
                    else -> HistoryScreen(sessions.filter { it.status != "ACTIVE" }, vm::showSession,
                        onOpenFile = { openFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) })
                }
            }
        }
    }
}
