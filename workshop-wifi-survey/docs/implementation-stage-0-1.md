# Raport implementacji Stage 0 + Stage 1

Data: 2026-09-10. Stan: **gotowe do pierwszego fizycznego survey na Flip6**.
Branch: `feat/workshop-wifi-survey-mvp`. Bez zmian, commitów ani merge do `main`.
Commit końcowy i PR są podane w raporcie przekazania; ten dokument jest częścią commitu.

## Zrealizowano

Stage 0: strukturę projektu, reguły prywatności, 16 prób `git check-ignore`,
guard docelowej ścieżki, fikcyjne AP i synthetic dataset wygenerowany prawdziwym
serializatorem Android w testach Room. Schema eksportu ma wersję 1 od początku.

Stage 1: Kotlin/Compose, coroutines/Flow, Room/WAL, location FGS, FLP, odczyt
bieżącego połączenia przez WifiManager z kontrolą transportu ConnectivityManager.
UI obejmuje Preflight, New Survey, live status, ADD NOTE, STOP i listę sesji
z podsumowaniem oraz ponawianiem eksportu. Surowe próbki nie są usuwane przez
filtr SSID ani progi jakości GPS. Wiek/join są liczone w nanosekundowej domenie
monotonicznej; UTC nie steruje korelacją.

Eksport ZIP/CSV/GeoJSON jest strumieniowany stronami po 500 rekordów i publikowany
przez MediaStore IS_PENDING, z checksumami wszystkich składowych. Baza i wcześniej
utworzone ZIP pozostają zachowane przy ponawianiu. Awaria/reboot prowadzi do
INTERRUPTED, z ostrożną dolną granicą czasu końca i osobnym czasem odzyskania.

Desktop zawiera minimalny walidator formatu oraz chroniony ADB intake wymagany
do pierwszego testu. Nie powstały skany Stage 2, mapy/pipeline analityczny Stage 3,
anchory Stage 4 ani aktywne testy. Ich CSV są puste, z jawnymi nagłówkami i oznaczeniem
w metadata. Nie mieszamy CONNECTED_LINK z SCAN_RESULT.

## Weryfikacja

| Sprawdzenie | Wynik |
|---|---|
| Debug APK, `assembleDebug` | PASS |
| JVM/Robolectric | 17/17 PASS, 0 pominiętych |
| Python unittest | 22/22 PASS |
| Android Lint | PASS: 0 błędów, 16 ostrzeżeń |
| Prywatność `check-ignore` i guard ścieżki | PASS: 16 prób i guard |
| ADB: identyfikacja podłączonego telefonu | PASS: SM-F741B, Android 16/API 36 |
| Instalacja debug APK i otwarcie Activity | PASS |
| Proces aplikacji aktywny, Activity resumed | PASS |
| Zgody/UI Preflight, START, FGS podczas pomiaru | NOT RUN przez operatora |
| >=5 min bez USB, rzeczywiste Wi-Fi/FLP ~1 Hz | NOT RUN |
| Fizyczny roaming, disconnect, GPS loss/recovery | NOT RUN |
| Fizyczny crash/reboot recovery, eksport i ADB pull | NOT RUN |
| Screen off/złożenie/background | NOT RUN; metoda niezatwierdzona |

Testy Android obejmują pasma/kanały, specjalne częstotliwości i nieznane wartości,
RSSI, nanosekundową granicę age/join, zmianę UTC, słabą/mock lokalizację, blokady
Preflight, BSSID transition oraz disconnect/reconnect, zachowanie fix dostarczonych
poza kolejnością, recovery, odmowę eksportu ACTIVE, błąd i ponowienie eksportu,
CSV/Unicode/GeoJSON, deterministyczny ZIP, checksumy i eksport >500 rekordów.

Migracja Room 1→2 jest wykonywana na natywnym SQLite z historycznego schematu.
Porównywane są kolumny, indeksy i foreign keys z nową bazą generowaną przez Room,
oraz zachowanie sesji i pomiaru. `MigrationTestHelper` z Room 2.8.4 napotkał
niezgodność nazwy bazy/ścieżki Windows; test używa SQLite in-memory i `TableInfo`,
zachowując weryfikację produkcyjnego SQL migracji. Pełny reopen po aktualizacji APK
pozostaje elementem fizycznej próby.

Testy desktop obejmują format, uszkodzony ZIP, checksumy i ich pokrycie, wersję
schema, sequence/elapsed, BSSID, kanał, join poza limitem, brak lokalizacji,
rozdzielenie źródeł, brak pliku przyszłego strumienia, generowanie raportu FAIL,
reproducibility oraz ochronę ścieżek i obsługę pierwszej instalacji przez ADB.

Nie pobrano żadnego rzeczywistego datasetu survey. Dane technicznego preflight
telefonu pozostają wyłącznie w ignorowanym `data/`. Agent nie uruchamiał survey.

## Toolchain i decyzje

Zweryfikowano zainstalowany toolchain oraz dokumentację Android Developers:

- [AGP 9.2.1, kompatybilność Gradle/JDK/SDK](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [Uprawnienia Wi-Fi](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [NetworkCallback i FLAG_INCLUDE_LOCATION_INFO](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- [WifiInfo i redakcja identyfikatorów](https://developer.android.com/reference/android/net/wifi/WifiInfo)
- [Foreground service location](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Room i migracje](https://developer.android.com/jetpack/androidx/releases/room)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping)

Użyto stabilnego SDK 37.0 rev.2, target 37, min 34; JBR 25.0.2; AGP 9.2.1,
Gradle 9.4.1, built-in Kotlin/Compose compiler 2.3.10, KSP 2.3.6.
Gradle 9.4.1 to wersja domyślna z tabeli zgodności AGP 9.2, choć Lint wskazuje
nowsze wydanie Gradle. Pozostałe ostrzeżenia obejmują nowsze stabilne zależności,
konserwatywne `usableSpace`, zbędne przy minSdk sprawdzenie API i sugestię KTX.
Nie wyłączono detektorów ani nie ukryto ostrzeżeń. W teście migracji występuje
deprecation `TableInfo.read(SupportSQLiteDatabase)`; JBR 25 ostrzega o native access
Conscrypt w Robolectric. Build i wszystkie testy przechodzą mimo tych ostrzeżeń.

## Ograniczenia i ryzyka do oceny na telefonie

- 1 Hz to żądany rytm odczytów. Android/firmware może odświeżać RSSI rzadziej;
  snapshot API nie dowodzi nowego pomiaru radiowego co sekundę.
- `WifiManager.connectionInfo` jest publicznym, zdeprecjonowanym getterem,
  używanym do okresowego odczytu primary link. Callback CM identyfikuje transport
  Wi-Fi także przy cellular/VPN jako default; nie uzależniamy survey od Internetu.
- `channel_width` połączenia pozostaje null. Brak fałszywego SNR/noise/utilization
  i brak estymacji przerwy w transmisji z próbek 1 Hz.
- Surowy GPS może być słaby w pomieszczeniach. INDOOR/MIXED nie mają jeszcze anchorów.
  Mock/future/out-of-order fixes nie są usuwane; walidator ujawnia problemy.
- Usługa i polityka baterii Samsunga wymagają próby bez USB. Ekran pozostaje aktywny
  tylko gdy Activity jest widoczna; nie zatwierdzono screen off ani złożenia.
- Kolejka jest ograniczona do 64 komunikatów; zwykły STOP opróżnia zapisane w niej
  callbacki. Przepełnienie, błąd DB lub <20 MiB wolnego miejsca kończy survey jako
  przerwany. Nagła śmierć procesu może utracić wyłącznie niezapisany bufor/in-flight;
  utrwalone rekordy pozostają w Room. Ciężki błąd DB może wymagać ponownego startu aplikacji.
- Eksport dużej sesji wymaga dodatkowego miejsca. Obsłużony błąd umożliwia retry;
  nagła śmierć przy zapisie może pozostawić pending MediaStore item. Walidator
  desktop ma limit 512 MiB rozpakowanych danych i używa pamięci do analizy ZIP.
- `EXPORT_CREATED` jest dopisywany po sukcesie i będzie obecny w kolejnym eksporcie.
  Stan eksportu jest niezależny od statusu pomiaru, aby nie utracić informacji
  COMPLETED/INTERRUPTED po błędzie publikacji.
- APK debug ma lokalny klucz podpisu; nie odinstalowywać aplikacji przed zabezpieczeniem
  sesji. Nie wykonano testu fizycznej aktualizacji z historycznej DB v1.
- Gitignore zabezpiecza przewidziane ścieżki przed zwykłym stagingiem; wymuszony
  add lub wklejenie sekretu do kodu nadal wymaga kontroli operatora/review.

Dokładna procedura: [flip6-first-test.md](flip6-first-test.md).

## Lista utworzonych plików

Lista poniżej jest generowana z kandydatów do commitu; nie obejmuje ignorowanych
buildów, APK, danych telefonu ani prywatnych raportów.

```text
.gitattributes
.gitignore
docs/workshop-wifi-survey-android-plan.md
workshop-wifi-survey/.gitignore
workshop-wifi-survey/README.md
workshop-wifi-survey/analysis/README.md
workshop-wifi-survey/analysis/check_privacy.py
workshop-wifi-survey/analysis/device.py
workshop-wifi-survey/analysis/pyproject.toml
workshop-wifi-survey/analysis/test_device.py
workshop-wifi-survey/analysis/test_validate_survey.py
workshop-wifi-survey/analysis/validate_survey.py
workshop-wifi-survey/android/app/build.gradle.kts
workshop-wifi-survey/android/app/schemas/pl.autoklinika.infrastructure.wifisurvey.SurveyDatabase/1.json
workshop-wifi-survey/android/app/schemas/pl.autoklinika.infrastructure.wifisurvey.SurveyDatabase/2.json
workshop-wifi-survey/android/app/src/main/AndroidManifest.xml
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/MainActivity.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/MeasurementRules.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/Models.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/PlatformSources.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/SurveyApplication.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/SurveyDatabase.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/SurveyExporter.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/SurveyRepository.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/SurveyService.kt
workshop-wifi-survey/android/app/src/main/java/pl/autoklinika/infrastructure/wifisurvey/SurveyViewModel.kt
workshop-wifi-survey/android/app/src/main/res/drawable/ic_survey.xml
workshop-wifi-survey/android/app/src/main/res/xml/data_extraction_rules.xml
workshop-wifi-survey/android/app/src/test/java/pl/autoklinika/infrastructure/wifisurvey/MeasurementRulesTest.kt
workshop-wifi-survey/android/app/src/test/java/pl/autoklinika/infrastructure/wifisurvey/MigrationTest.kt
workshop-wifi-survey/android/app/src/test/java/pl/autoklinika/infrastructure/wifisurvey/RepositoryExportTest.kt
workshop-wifi-survey/android/build.gradle.kts
workshop-wifi-survey/android/gradle.properties
workshop-wifi-survey/android/gradle/wrapper/gradle-wrapper.jar
workshop-wifi-survey/android/gradle/wrapper/gradle-wrapper.properties
workshop-wifi-survey/android/gradlew
workshop-wifi-survey/android/gradlew.bat
workshop-wifi-survey/android/settings.gradle.kts
workshop-wifi-survey/config/ap-map.example.json
workshop-wifi-survey/config/survey-defaults.json
workshop-wifi-survey/docs/export-schema-v1.md
workshop-wifi-survey/docs/flip6-first-test.md
workshop-wifi-survey/docs/implementation-stage-0-1.md
workshop-wifi-survey/schema_version.txt
workshop-wifi-survey/testdata/synthetic/README.md
workshop-wifi-survey/testdata/synthetic/checksums.sha256
workshop-wifi-survey/testdata/synthetic/connected_wifi.csv
workshop-wifi-survey/testdata/synthetic/events.csv
workshop-wifi-survey/testdata/synthetic/indoor_anchors.csv
workshop-wifi-survey/testdata/synthetic/locations.csv
workshop-wifi-survey/testdata/synthetic/metadata.json
workshop-wifi-survey/testdata/synthetic/scan_results.csv
workshop-wifi-survey/testdata/synthetic/scan_snapshots.csv
workshop-wifi-survey/testdata/synthetic/schema_version.txt
workshop-wifi-survey/testdata/synthetic/track.geojson
```
