package pl.autoklinika.infrastructure.wifisurvey

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale
import kotlinx.coroutines.launch

fun signalColor(rssi: Int?): Color = when {
    rssi == null -> Muted
    rssi >= -67 -> Teal
    rssi >= -75 -> Amber
    else -> WeakRed
}

@Composable
fun ReviewScreen(review: SurveyReview?, loading: Boolean, error: String?, imported: Boolean, exporting: Boolean,
                 onBack: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) { Text("←  Moje pomiary") }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(); Text("Przygotowuję wykres i trasę…")
                }
            }
            error != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nie można otworzyć pomiaru", style = MaterialTheme.typography.headlineSmall)
                Text(error, color = Muted)
            }
            review != null -> ReviewContent(review, imported, exporting, onExport)
        }
    }
}

@Composable
private fun ReviewContent(review: SurveyReview, imported: Boolean, exporting: Boolean, onExport: () -> Unit) {
    var route by rememberSaveable(review.session.id) { mutableStateOf(true) }
    var expandedMap by rememberSaveable(review.session.id) { mutableStateOf(false) }
    var selectedIndex by rememberSaveable(review.session.id) { mutableIntStateOf(0) }
    val selected = review.points.getOrNull(selectedIndex)
    var details by rememberSaveable(review.session.id) { mutableStateOf(false) }
    val chartAnchor = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun showChart() { scope.launch { withFrameNanos { }; chartAnchor.bringIntoView() } }
    val select: (ReviewPoint) -> Unit = { selectedIndex = review.points.indexOf(it).coerceAtLeast(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("WYNIK POMIARU", color = Teal, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
            Text(review.session.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${sessionDate(review.session)}  ·  ${durationLabel(review.durationSeconds)}", color = Muted, style = MaterialTheme.typography.bodyMedium)
            if (imported) Text("Podgląd pliku · sprawdzono integralność", color = Teal, style = MaterialTheme.typography.labelMedium)
            if (review.session.status == "INTERRUPTED") Text("Pomiar został przerwany. Wynik obejmuje zachowane odczyty.", color = Amber)
        }
        if (!route) Surface(color = Ink, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.goodPercent?.let { "${it.roundToInt()}% odczytów z dobrym sygnałem" } ?: "Brak odczytów siły sygnału",
                    color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(when {
                    review.sampleCount == 0 -> "Pomiar zakończono przed zapisaniem pierwszego odczytu."
                    review.weakCount > 0 && review.locatedCount > 0 -> "Sygnał chwilami słabł. Dotknij wykresu i sprawdź położenie, jeśli zapisano je w tej chwili."
                    review.weakCount > 0 -> "Sygnał chwilami słabł. Przebieg znajdziesz na wykresie; w tym pomiarze nie ma dokładnych pozycji GPS."
                    review.validSignalCount > 0 -> "W zapisanych odczytach sygnał był dobry. Możesz sprawdzić przebieg całego pomiaru."
                    else -> "Sprawdź połączenie telefonu z Wi-Fi i wykonaj kolejny pomiar."
                }, color = Color(0xFFDCEDE7))
                if (review.disconnectedCount > 0) Text("Odczyty bez połączenia: ${review.disconnectedCount} z ${review.sampleCount}.", color = Color(0xFFFFD4C6))
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = route, onClick = { route = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Mapa zasięgu") }
            SegmentedButton(selected = !route, onClick = { route = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sygnał w czasie") }
        }
        WhiteCard(Modifier.bringIntoViewRequester(chartAnchor)) {
            Text(if (route) "Zasięg w zbadanych miejscach" else "Jak zmieniał się sygnał?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (route) "Wybierz AP lub dotknij pola na mapie." else "Wyżej = lepszy sygnał. Dotknij wykresu, aby wybrać chwilę.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (route) {
                when {
                    review.coverage.cells.isEmpty() -> Text("Brak dokładnych pomiarów GPS z rozpoznanym AP. Odczyty Wi-Fi są dostępne na wykresie.", modifier = Modifier.padding(vertical = 24.dp), color = Muted)
                    else -> {
                        CoverageMapView(review.coverage, selected, Modifier.fillMaxWidth().height(450.dp))
                        TextButton(onClick = { expandedMap = true }, modifier = Modifier.fillMaxWidth()) { Text("Powiększ mapę na cały ekran") }
                    }
                }
            } else if (review.points.isEmpty()) Text("Ten pomiar nie zawiera jeszcze odczytów.", modifier = Modifier.padding(vertical = 24.dp))
            else SignalChart(review, selected, select)
            if (!route) FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Legend("Dobry", Teal); Legend("Słabszy", Amber); Legend("Bardzo słaby", WeakRed)
            }
            Text(if (route) "Kolor opisuje zapisany sygnał w polu, nie gwarantuje zasięgu w każdym jego punkcie. Nie wypełniamy miejsc bez pomiarów."
                else "Przerwy na wykresie oznaczają brak odczytu. To pomiar siły Wi-Fi, a nie prędkości internetu.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (route && !review.coverage.scanEnabled) Text("Starszy pomiar: zapisano tylko AP połączony z telefonem. Nowy pomiar zbierze również pozostałe widoczne AP.", color = Amber, style = MaterialTheme.typography.bodySmall)
        if (route && review.coverage.scanEnabled && review.coverage.mappedScanCount == 0) Text("Brak świeżych skanów AP z dokładną pozycją. Mapa pokazuje dostępne pomiary połączenia telefonu.", color = Amber, style = MaterialTheme.typography.bodySmall)
        if (selected != null) WhiteCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Chwila ${durationLabel(selected.seconds)}", style = MaterialTheme.typography.titleMedium)
                Text(selected.rssi?.let { "$it dBm" } ?: "—", color = signalColor(selected.rssi), fontWeight = FontWeight.Bold)
            }
            Text(signalLabel(selected.rssi, selected.connected), style = MaterialTheme.typography.titleLarge, color = signalColor(selected.rssi))
            Text(if (selected.latitude != null) "Pozycja zapisana · dokładność około ${selected.accuracy?.roundToInt()} m"
                else "Brak dokładnej pozycji w tej chwili. Tego odczytu nie ma na trasie.", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (!route && selected.latitude != null) TextButton(onClick = { route = true; showChart() }) { Text("Pokaż to miejsce na trasie") }
            if (review.points.size > 1) {
                Text("Wybierz chwilę pomiaru", style = MaterialTheme.typography.labelSmall, color = Muted)
                Slider(value = selectedIndex.toFloat(), onValueChange = { selectedIndex = it.roundToInt().coerceIn(review.points.indices) },
                    valueRange = 0f..review.points.lastIndex.toFloat(),
                    modifier = Modifier.semantics { contentDescription = "Wybierz chwilę pomiaru, teraz ${durationLabel(selected.seconds)}" })
            }
        }
        if (route) Text("GPS pokazuje położenie orientacyjnie. W budynku nie zastępuje planu pomieszczeń. Na trasę przyjmujemy tylko prawidłowe pozycje z dokładnością do ${surveyJson.decodeFromString<SurveyConfig>(review.session.configuration_snapshot_json).accuracy_exclusion_m.toInt()} m.",
            style = MaterialTheme.typography.bodySmall, color = Muted)
        if (review.weakCount > 0) OutlinedButton(onClick = {
            review.points.filter { it.rssi != null }.minByOrNull { it.rssi!! }?.let(select); route = false; showChart()
        }, modifier = Modifier.fillMaxWidth()) { Text("Pokaż najsłabszy sygnał") }
        TextButton(onClick = { details = !details }) { Text(if (details) "Ukryj szczegóły" else "Szczegóły pomiaru") }
        if (details) WhiteCard {
            Text("Odczyty Wi-Fi: ${review.sampleCount}\nDo pokazania na trasie: ${review.locatedCount}\nSłabszy sygnał: ${review.weakCount} odczytów\nPrzerwy w zapisie: ${review.gapCount}")
            Text("Dobry sygnał: co najmniej −67 dBm. Procent obejmuje wszystkie odczyty, także brak połączenia lub brak wartości sygnału. Nie określa procentu pokrycia terenu.", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("Wykres: bieżące połączenie telefonu. Mapa: osobno połączenie i skany AP. Skanów: ${review.coverage.scanCount}; świeżych z dokładnym GPS: ${review.coverage.mappedScanCount}. AP oznacza pojedyncze radio (BSSID), nie zawsze osobne urządzenie.", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (review.sampleCount > review.points.size) Text("Długi zapis: wykres pokazuje skrót z zachowaniem skrajnych wartości. Podsumowanie obejmuje wszystkie odczyty.", style = MaterialTheme.typography.bodySmall)
            selected?.latitude?.let { Text("Wybrana pozycja GPS: ${String.format(Locale.ROOT, "%.6f, %.6f", it, selected.longitude)}", style = MaterialTheme.typography.bodySmall) }
        }
        if (!imported) {
            OutlinedButton(onClick = onExport, enabled = !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (exporting) "Zapisuję plik…" else "Zapisz plik ZIP")
            }
            Text(if (review.session.export_status == "EXPORT_FAILED") "Nie udało się zapisać pliku. Pomiar jest w aplikacji — możesz ponowić zapis."
                else "Pliki znajdziesz w folderze Pobrane / WorkshopWiFiSurvey.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(20.dp))
    }
    if (expandedMap) Dialog(onDismissRequest = { expandedMap = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
            TextButton(onClick = { expandedMap = false }) { Text("←  Wróć do wyniku") }
            CoverageMapView(review.coverage, selected, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(9.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SignalChart(review: SurveyReview, selected: ReviewPoint?, onSelect: (ReviewPoint) -> Unit) {
    val density = LocalDensity.current
    val left = with(density) { 36.dp.toPx() }; val right = with(density) { 12.dp.toPx() }
    val top = with(density) { 18.dp.toPx() }; val bottom = with(density) { 30.dp.toPx() }
    val paint = remember(density) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(82, 104, 101); textSize = with(density) { 10.sp.toPx() } } }
    val low = min(-90, review.minimumRssi ?: -90).toFloat()
    val high = max(-35, review.points.mapNotNull { it.rssi }.maxOrNull() ?: -35).toFloat()
    val duration = max(1.0, review.durationSeconds)
    Canvas(Modifier.fillMaxWidth().height(250.dp)
        .semantics { contentDescription = "Wykres siły sygnału. ${review.weakCount} słabszych odczytów. Wybierz chwilę suwakiem poniżej." }
        .pointerInput(review) { detectTapGestures { tap ->
            val second = ((tap.x - left) / (size.width - left - right)).coerceIn(0f, 1f) * duration
            review.points.minByOrNull { abs(it.seconds - second) }?.let(onSelect)
        } }) {
        val width = size.width - left - right; val height = size.height - top - bottom
        fun y(value: Float) = top + (high - value) / (high - low) * height
        fun x(seconds: Double) = left + (seconds / duration * width).toFloat()
        clipRect(left, top, size.width - right, size.height - bottom) {
            listOf(Triple(high, -67f, Teal), Triple(-67f, -75f, Amber), Triple(-75f, low, WeakRed)).forEach { (upper, lower, color) ->
                drawRect(color.copy(alpha = .07f), Offset(left, y(upper)), Size(width, y(lower) - y(upper)))
            }
        }
        for (level in listOf(high, -55f, -67f, -75f, low).distinct()) {
            drawLine(Muted.copy(alpha = .16f), Offset(left, y(level)), Offset(size.width - right, y(level)), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(level.toInt().toString(), 0f, y(level) + 3.dp.toPx(), paint)
        }
        review.points.zipWithNext().forEach { (a, b) ->
            if (a.rssi != null && b.rssi != null && a.signalSegment == b.signalSegment)
                drawLine(signalColor(min(a.rssi, b.rssi)), Offset(x(a.seconds), y(a.rssi.toFloat())), Offset(x(b.seconds), y(b.rssi.toFloat())), 2.4.dp.toPx(), StrokeCap.Round)
        }
        review.points.filter { it.rssi != null }.forEach { drawCircle(signalColor(it.rssi), 1.5.dp.toPx(), Offset(x(it.seconds), y(it.rssi!!.toFloat()))) }
        selected?.let { point ->
            drawLine(Ink.copy(alpha = .5f), Offset(x(point.seconds), top), Offset(x(point.seconds), size.height - bottom), 1.dp.toPx())
            point.rssi?.let { drawCircle(Color.White, 6.dp.toPx(), Offset(x(point.seconds), y(it.toFloat())))
                drawCircle(signalColor(it), 4.dp.toPx(), Offset(x(point.seconds), y(it.toFloat()))) }
        }
        drawContext.canvas.nativeCanvas.drawText("dBm", 0f, 10.dp.toPx(), paint)
        val labels = listOf(0.0, duration / 2, duration)
        labels.forEachIndexed { index, second ->
            val label = durationLabel(second)
            val shift = when (index) { 0 -> 0f; 1 -> paint.measureText(label) / 2; else -> paint.measureText(label) }
            drawContext.canvas.nativeCanvas.drawText(label, x(second) - shift, size.height - 5.dp.toPx(), paint)
        }
    }
}

@Composable
private fun RouteChart(projection: RouteProjection, selected: ReviewPoint?, onSelect: (ReviewPoint) -> Unit) {
    val density = LocalDensity.current
    val padding = with(density) { 28.dp.toPx() }
    val paint = remember(density) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(82, 104, 101); textSize = with(density) { 10.sp.toPx() } } }
    fun position(item: RoutePosition, size: Float): Offset {
        val usable = size - 2 * padding
        return Offset((size / 2 + (item.east - projection.centerEast) / projection.span * usable).toFloat(),
            (size / 2 - (item.north - projection.centerNorth) / projection.span * usable).toFloat())
    }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
        .semantics { contentDescription = "Trasa GPS, północ u góry. ${projection.positions.size} punktów. Wybierz chwilę suwakiem poniżej." }
        .pointerInput(projection) { detectTapGestures { tap ->
            projection.positions.minByOrNull { (position(it, size.width.toFloat()) - tap).getDistanceSquared() }?.let { onSelect(it.point) }
        } }) {
        val usable = size.width - 2 * padding
        for (i in 0..4) {
            val value = padding + usable * i / 4
            drawLine(Muted.copy(alpha = .12f), Offset(value, padding), Offset(value, size.height - padding), 1.dp.toPx())
            drawLine(Muted.copy(alpha = .12f), Offset(padding, value), Offset(size.width - padding, value), 1.dp.toPx())
        }
        projection.positions.zipWithNext().forEach { (a, b) ->
            if (a.point.routeSegment == b.point.routeSegment)
                drawLine(signalColor(b.point.rssi).copy(alpha = .4f), position(a, size.width), position(b, size.width), 2.dp.toPx(), StrokeCap.Round)
        }
        projection.positions.forEach { drawCircle(signalColor(it.point.rssi), 3.5.dp.toPx(), position(it, size.width)) }
        projection.positions.firstOrNull()?.let { drawCircle(Ink, 7.dp.toPx(), position(it, size.width), style = Stroke(1.5.dp.toPx())) }
        projection.positions.find { it.point == selected }?.let {
            val pixel = position(it, size.width)
            clipRect { drawCircle(Teal.copy(alpha = .12f), (it.point.accuracy!! / projection.span * usable).toFloat(), pixel) }
            drawCircle(Color.White, 8.dp.toPx(), pixel); drawCircle(signalColor(it.point.rssi), 5.dp.toPx(), pixel)
            drawCircle(Ink, 8.dp.toPx(), pixel, style = Stroke(1.5.dp.toPx()))
        }
        drawContext.canvas.nativeCanvas.drawText("↑ Północ", padding, 13.dp.toPx(), paint)
        val scale = projection.span / 4
        val baseline = size.height - 11.dp.toPx()
        drawLine(Ink, Offset(padding, baseline), Offset(padding + usable / 4, baseline), 2.dp.toPx())
        drawContext.canvas.nativeCanvas.drawText("${scale.roundToInt()} m", padding + usable / 4 + 7.dp.toPx(), baseline + 3.dp.toPx(), paint)
    }
}
