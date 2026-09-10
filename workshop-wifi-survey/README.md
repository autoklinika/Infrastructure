# Workshop WiFi Survey

Collector Android dla Samsung Galaxy Z Flip6, Application ID
`pl.autoklinika.infrastructure.wifisurvey`.

Zakres tego PR: **Stage 0 + Stage 1, przygotowanie do fizycznego testu**.
Nie oznacza to jeszcze pełnej akceptacji sprzętowej MVP. Wersja 0.2 dodaje prosty
polski interfejs, wykres sygnału i lokalny widok trasy GPS z zapisanych sesji/ZIP.
Skany sąsiednich AP, heatmapy, anchory indoor i aktywne testy pozostają poza zakresem.

Dokument wykonawczy: [specyfikacja v1.1](../docs/workshop-wifi-survey-android-plan.md).
Ponieważ specyfikacja nie była jeszcze na `main`, dołączono jej niezmienioną treść
z `docs/workshop-wifi-survey-android-plan`, commit
`47365e0c5697c2c91b9e214e66ededee992ab976`. Bazą implementacji jest `main`
`c89f271bd795c61ebc606944e6929fd80333620c`.

## Budowanie i testy

Zainstaluj stabilne Android SDK Platform 37.0 i Build Tools 36.0.0,
JDK zgodne z Gradle 9.4.1 (np. JDK 21 lub 25), Python 3.11+ i ADB.
Na tej maszynie zweryfikowano JBR 25.0.2 z Android Studio, SDK 37.0 rev. 2.
SDK API 37 jest stabilne (`PreviewSdkInt=0`). `minSdk=34`, `targetSdk=37`.

AGP 9.2.1 używa wbudowanego Kotlin 2.3.10; nie dodawaj pluginu
`org.jetbrains.kotlin.android`. Gradle 9.4.1 jest wersją domyślną zgodną
z AGP 9.2; wrapper ma przypiętą sumę SHA-256 dystrybucji.
Zależności są przypięte do stabilnych wersji i zweryfikowane przez build.

PowerShell, od katalogu repozytorium:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
python workshop-wifi-survey/analysis/check_privacy.py
Push-Location workshop-wifi-survey/android
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain
Pop-Location
python -m unittest discover -s workshop-wifi-survey/analysis -p 'test_*.py' -v
```

Dostosuj ścieżki JDK/SDK do komputera. Lokalną ścieżkę SDK można też ustawić
w ignorowanym `android/local.properties`. Pierwszy build/test wymaga pobrania
zależności z Google Maven, Maven Central oraz dystrybucji Robolectric.
Nie używaj Gradle Build Scan do diagnostyki prawdziwego survey.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
To APK debug, podpisane lokalnym kluczem debug; aktualizacja na innym komputerze
może wymagać tego samego klucza. Nie odinstalowuj aplikacji z niepobranymi sesjami.

## Funkcjonalność

- Ekran „Nowy pomiar” z prostą instrukcją usunięcia aktualnej blokady.
- Opcjonalna nazwa i wybór „Na zewnątrz / W budynku / Tu i tu”.
- Automatycznie ustawione 1000 ms zapisu i 2500 ms limitu wieku GPS; bez pól technicznych.
- „Moje pomiary”: podsumowanie, interaktywny wykres sygnału i trasa GPS bez usług mapowych.
- Otwieranie wcześniejszych ZIP przez systemowy wybór pliku, po kontroli checksum i powiązań.
- Location foreground service uruchamiany z widocznego ekranu, bez background location.
- Room/WAL; transakcyjny zapis każdej próbki i powiązanych eventów, kolejka maks. 64 komunikatów.
- Około 1 Hz obserwacji bieżącego połączenia i żądania FLP 1 Hz; rzeczywisty rytm zależy od Androida.
- UTC i elapsed nanoseconds, join do najnowszego dostępnego fix niepóźniejszego niż odczyt Wi-Fi.
- BSSID transition, disconnect/reconnect, degradacja/powrót lokalizacji i notatki.
- Ekran aktywny, status na żywo i powiadomienie z przyciskiem STOP.
- Wykrywanie sesji przerwanej po awarii/reboocie i ponawialny eksport.
- ZIP do `Download/WorkshopWiFiSurvey/`, bez broad storage permissions.

OUTDOOR wymaga przy START fix w dozwolonym oknie, accuracy <=20 m i braku mock.
Brak fix w INDOOR/MIXED jest ostrzeżeniem; Stage 1 zachowuje surowe dane bez anchorów.
Po START utrata fix nigdy nie odrzuca próbek Wi-Fi. Filtr SSID również oznacza
niezgodność flagą, zamiast usuwać pomiary. Progi są w snapshot sesji;
`config/survey-defaults.json` dokumentuje wartości bazowe, nie jest automatycznie
wczytywany z checkoutu przez APK. Nowe pomiary używają tych stałych wartości.
Starsze sesje zachowują własną konfigurację; przeglądarka respektuje zapisany limit
wieku i jakości lokalizacji. Szczegóły: [interfejs i wykresy 0.2](docs/interface-and-review-0.2.md).

## Prywatność

Repozytorium jest publiczne. Prywatna ścieżka lokalna to:

```text
workshop-wifi-survey/data/WorkshopWiFiSurvey/raw/<export>/
workshop-wifi-survey/data/WorkshopWiFiSurvey/processed/
workshop-wifi-survey/data/WorkshopWiFiSurvey/reports/<export>/
```

`analysis/device.py` sprawdza `git check-ignore` przed pobraniem, odrzuca ścieżki
poza tym katalogiem i nie nadpisuje oryginału. Serial ADB pozostaje tylko w pamięci
procesu. Nie zapisujemy logcat ani bugreport automatycznie. Realne mapowanie AP
wolno trzymać tylko w `config/ap-map.local.json` lub `.local/`.

`.gitignore` nie chroni przed świadomym `git add -f` ani przepisaniem danych do kodu.
Przed każdym commitem sprawdzaj staging i nie dodawaj rzeczywistych danych
do `testdata/synthetic/`. Eksport w Downloads jest prywatnym plikiem operacyjnym
na telefonie, dostępnym dla operatora; nie udostępniaj go publicznie.
Kopie chmurowe i transfer danych aplikacji są wyłączone w manifeście.

## Dalsze instrukcje

- [Pierwszy fizyczny test Flip6](docs/flip6-first-test.md)
- [Raport implementacji i ograniczenia](docs/implementation-stage-0-1.md)
- [Kontrakt eksportu](docs/export-schema-v1.md)
- [Walidacja i bezpieczny ADB](analysis/README.md)
