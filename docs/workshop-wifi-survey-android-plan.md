# Workshop WiFi Survey — specyfikacja aplikacji Android i workflow z Codex

**Status:** dokument projektowy / source of truth dla implementacji

**Repozytorium:** `autoklinika/Infrastructure`

**Data ustalenia:** 2026-09-10

**Docelowe urządzenie pomiarowe:** Samsung Galaxy Z Flip6

**Powiązane dokumenty:**

- `docs/unifi-network-bom-v2.md` — aktualny plan infrastruktury UniFi,
- `docs/assets/podworko-rzut.jpg` — aktualny rzut posesji/podwórka do wykorzystania przy wizualizacji zasięgu.

---

## 1. Cel dokumentu

Ten dokument ma być wystarczający, aby po przekazaniu go Codexowi mógł rozpocząć implementację bez ponownego odtwarzania założeń z rozmów.

Codex ma traktować ten dokument jako wymagania funkcjonalne i techniczne dla projektu **Workshop WiFi Survey**.

Główny cel projektu:

> wykorzystać Samsung Galaxy Z Flip6 jako mobilny rejestrator parametrów Wi-Fi i pozycji, wykonać rzeczywisty obchód W1–W5 oraz placu, a następnie automatycznie wygenerować mapy pokrycia i dane potrzebne do strojenia infrastruktury UniFi.

Aplikacja ma służyć do pomiarów naszej własnej infrastruktury WLAN, odbiorów instalacji, porównywania kolejnych konfiguracji AP i wykrywania obszarów wymagających korekty.

---

## 2. Zasada pracy z repozytorium

Nie wykonywać bezpośrednich zmian w `main` i nie scalać do `main` bez jednoznacznej zgody użytkownika.

Implementację prowadzić na osobnej gałęzi, przykładowo:

```text
feat/workshop-wifi-survey-mvp
```

Przed każdym większym etapem sprawdzić aktualny stan repozytorium i nie nadpisywać istniejących dokumentów ani ustaleń.

Po zakończeniu etapu:

1. wykonać możliwe testy automatyczne,
2. zapisać wynik i ograniczenia,
3. uaktualnić dokumentację,
4. przygotować commit/PR,
5. nie merge'ować do `main` bez zgody użytkownika.

Testy wymagające fizycznego telefonu, przejścia po obiekcie albo rzeczywistej infrastruktury Wi-Fi wykonuje użytkownik; Codex ma przygotować dokładną procedurę i przeanalizować wyniki.

---

## 3. Docelowy workflow operatora

Podstawowy scenariusz użytkowania ma wyglądać następująco:

1. Podłączamy **Galaxy Z Flip6** do komputera z Windows 11 przez USB.
2. Codex przygotowuje telefon: sprawdza ADB, wersję Androida, model, uprawnienia, stan aplikacji i ustawienia wymagane do pomiaru.
3. Codex kompiluje/aktualizuje i uruchamia **Workshop WiFi Survey**.
4. Operator rozpoczyna sesję pomiarową.
5. Odłączamy USB.
6. Operator chodzi po W1–W5 oraz placu z telefonem.
7. Aplikacja zapisuje lokalnie parametry Wi-Fi, pozycję i zdarzenia.
8. Operator kończy sesję i wraca do komputera.
9. Podłączamy telefon ponownie przez USB.
10. Codex pobiera plik sesji/logi przez ADB.
11. Codex generuje analizę, mapę/heatmapę oraz raport.
12. Na podstawie raportu korygujemy konfigurację UniFi.
13. Wykonujemy kolejny survey.
14. Kolejne sesje są porównywane z poprzednimi.

Kluczowe założenie: **podczas właściwego survey telefon pracuje samodzielnie i nie jest połączony przez USB z komputerem**.

Phone Link / Łącze z telefonem może być używane na Windows do przygotowania i obsługi telefonu, ale podczas właściwego pomiaru nie powinien być aktywny podgląd ekranu ani inne zbędne połączenie generujące ruch WLAN.

---

## 4. Architektura projektu

Projekt podzielić na dwie części.

### 4.1. Android — Workshop WiFi Survey

Odpowiada za:

- zbieranie parametrów bieżącego połączenia Wi-Fi,
- wykonywanie skanów sąsiednich AP,
- identyfikację SSID/BSSID,
- rejestrowanie pozycji,
- wykrywanie zmian AP/roamingu,
- lokalny zapis danych,
- eksport kompletnej sesji,
- prosty podgląd jakości pomiaru na żywo.

### 4.2. Windows/Codex — narzędzia analityczne

Odpowiadają za:

- przygotowanie telefonu przez ADB,
- instalację APK,
- pobieranie sesji,
- walidację danych,
- generowanie map pokrycia,
- generowanie raportu,
- porównanie kolejnych sesji,
- przygotowanie rekomendacji zmian w UniFi.

Nie próbować wykonywać całej analizy i ciężkiej interpolacji mapy na telefonie w MVP. Telefon ma być przede wszystkim **wiarygodnym rejestratorem pomiarów**.

---

## 5. Nazwa projektu i proponowana struktura repo

Nazwa robocza aplikacji:

```text
Workshop WiFi Survey
```

Proponowany application ID:

```text
pl.autoklinika.infrastructure.wifisurvey
```

Proponowana struktura:

```text
workshop-wifi-survey/
├── android/
│   ├── app/
│   ├── build.gradle.kts
│   └── ...
├── analysis/
│   ├── README.md
│   ├── requirements.txt lub pyproject.toml
│   ├── import_survey.py
│   ├── validate_survey.py
│   ├── generate_heatmap.py
│   └── compare_surveys.py
├── config/
│   ├── ap-map.example.json
│   └── survey-defaults.json
├── docs/
│   └── ...
└── README.md
```

Jeżeli istniejąca struktura repo po rozpoczęciu prac sugeruje lepszą lokalizację, Codex może ją dostosować, ale Android collector i analiza desktopowa mają pozostać logicznie rozdzielone.

---

## 6. Technologie Android

Preferowane:

- Kotlin,
- Android SDK,
- Jetpack Compose dla UI,
- coroutines / Flow,
- Room/SQLite dla lokalnego storage,
- foreground service podczas aktywnej sesji,
- Fused Location Provider dla pozycji,
- `WifiManager`, `ConnectivityManager`, `NetworkCapabilities`, `WifiInfo` dla parametrów WLAN,
- `WifiRttManager` tylko jako opcjonalne rozszerzenie.

Nie wiązać projektu ze sztywną wersją Android Studio/Gradle zapisaną w tym dokumencie. Przy rozpoczęciu implementacji użyć aktualnego stabilnego toolchainu, który jest kompatybilny z Galaxy Z Flip6 i aktualnym Androidem na urządzeniu.

Codex ma najpierw wykryć:

```text
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

oraz dostosować `compileSdk`, `targetSdk` i wymagania runtime do aktualnej wersji systemu.

---

## 7. Uprawnienia Android

Projekt wymaga co najmniej obsługi:

- dostępu do stanu Wi-Fi,
- skanowania Wi-Fi,
- dokładnej lokalizacji,
- dostępu do pobliskich urządzeń Wi-Fi tam, gdzie wymaga tego bieżące API Androida,
- Internetu/LAN dla późniejszych testów aktywnych,
- foreground service dla długiej sesji pomiarowej.

W praktyce Codex powinien zweryfikować bieżące wymagania Android SDK dla m.in.:

```text
ACCESS_WIFI_STATE
CHANGE_WIFI_STATE
ACCESS_FINE_LOCATION
NEARBY_WIFI_DEVICES
INTERNET
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_LOCATION
```

Nie zakładać, że samo `NEARBY_WIFI_DEVICES` wystarczy do `getScanResults()` / `startScan()`. Aplikacja faktycznie zapisuje pozycję GPS, więc dokładna lokalizacja jest funkcją podstawową i powinna być jawnie autoryzowana przez użytkownika.

Podczas pierwszego uruchomienia aplikacja ma posiadać ekran **Preflight**, który jednoznacznie pokazuje:

```text
Wi-Fi                  OK / ERROR
Precise location       OK / ERROR
Location services      ON / OFF
Wi-Fi scanning         ON / OFF
Foreground logging     OK / ERROR
Storage/export         OK / ERROR
Wi-Fi RTT device       YES / NO
```

Brak krytycznego wymagania ma blokować START SURVEY i pokazywać przyczynę.

---

## 8. Android Wi-Fi scan throttling

Android ogranicza częstotliwość inicjowanych przez aplikację skanów Wi-Fi. Dla telefonu używanego jako dedykowane urządzenie pomiarowe wykorzystujemy opcję deweloperską **Wi-Fi scan throttling** i podczas testów lokalnych wyłączamy ograniczanie skanowania.

Codex ma w procedurze przygotowania telefonu:

1. sprawdzić, czy Developer Options są aktywne,
2. sprawdzić stan funkcji scan throttling, o ile można go wiarygodnie odczytać,
3. wyłączyć throttling metodą oficjalnie/bezpiecznie działającą na aktualnym systemie,
4. jeżeli nie ma stabilnej publicznej metody ADB, użyć Phone Link/Computer Use do zmiany opcji w UI,
5. nie opierać implementacji na nieudokumentowanym kluczu systemowym bez weryfikacji na urządzeniu.

Aplikacja ma również prawidłowo działać przy włączonym throttlingu, tylko z niższą częstotliwością odświeżania pełnego skanu.

---

## 9. Dwa strumienie danych Wi-Fi

Nie traktować `startScan()` jako jedynego źródła danych.

### 9.1. Fast connected-link sample

Podczas całej aktywnej sesji rejestrować parametry aktualnie połączonego AP możliwie regularnie, docelowo około **1 próbka/s**:

- timestamp monotonic + UTC,
- SSID,
- BSSID,
- RSSI,
- frequency MHz,
- band,
- channel,
- channel width, jeśli dostępne,
- RX link speed, jeśli dostępne,
- TX link speed, jeśli dostępne,
- standard/PHY, jeśli API udostępnia wiarygodną wartość,
- network ID/transport info tylko jeżeli jest stabilne i przydatne.

### 9.2. Neighbor scan snapshot

Niezależnie wykonywać okresowy scan sąsiednich BSSID, np. co **5–10 s** w trybie pomiarowym, z uwzględnieniem ograniczeń systemu.

Dla każdego wyniku zapisywać co najmniej:

- SSID,
- BSSID,
- RSSI,
- frequency,
- band,
- channel,
- channel width,
- capability/security string,
- timestamp skanu,
- informację, czy wynik jest świeży czy pochodzi z poprzedniego skanu.

Nie udawać większej częstotliwości danych niż rzeczywiście dostarczył system.

---

## 10. Parametry Wi-Fi i ich interpretacja

Podstawowym parametrem mapy pokrycia jest **RSSI [dBm]**.

Domyślne progi projektu:

| RSSI | Ocena |
|---:|---|
| `>= -55 dBm` | bardzo dobry |
| `-56 ... -67 dBm` | dobry / docelowy |
| `-68 ... -70 dBm` | graniczny |
| `-71 ... -75 dBm` | słaby |
| `< -75 dBm` | problematyczny |

Głównym progiem odbiorowym na start przyjmujemy **-67 dBm**. Progi muszą być konfigurowalne, a nie wpisane na stałe w logikę analizy.

Aplikacja nie ma raportować SNR/noise floor jako rzeczywistego pomiaru, jeżeli publiczne API urządzenia nie udostępnia wiarygodnej wartości szumu. Nie wyliczać fikcyjnego SNR.

Tak samo nie deklarować rzeczywistego channel utilization/airtime utilization, jeżeli nie ma wiarygodnego źródła danych.

To urządzenie jest testerem WLAN opartym o standardowe API Androida, a **nie analizatorem widma RF i nie interfejsem monitor mode**.

---

## 11. Pasma

Projekt ma obsługiwać co najmniej:

- 2.4 GHz,
- 5 GHz.

Galaxy Z Flip6 sprzętowo pozwala również pracować z 6 GHz, dlatego model danych i UI przygotować tak, aby **6 GHz nie wymagało późniejszej przebudowy**.

Aktualna infrastruktura z `docs/unifi-network-bom-v2.md` jest projektowana przede wszystkim pod 2.4/5 GHz; 6 GHz pozostaje funkcją przyszłościową.

Kanał wyliczać z częstotliwości tylko wtedy, gdy platforma nie podaje go bezpośrednio. Implementacja powinna obsługiwać poprawnie pasma 2.4/5/6 GHz.

---

## 12. Pozycjonowanie — wymaganie podstawowe

Każda próbka Wi-Fi ma być możliwie powiązana z pozycją urządzenia.

Aplikacja zapisuje równolegle strumień lokalizacyjny i łączy próbki na podstawie timestampów.

Dla każdej lokalizacji zapisywać:

- latitude,
- longitude,
- horizontal accuracy [m],
- altitude, jeżeli dostępne,
- vertical accuracy, jeżeli dostępne,
- bearing, jeżeli dostępne,
- speed, jeżeli dostępne,
- timestamp UTC,
- timestamp monotonic,
- typ/źródło danych tylko jeżeli API udostępnia to wiarygodnie.

Docelowa częstotliwość lokalizacji podczas aktywnego survey: około **1 Hz**, z priorytetem dokładności nad oszczędzaniem baterii.

---

## 13. OUTDOOR — GPS/Fused Location

Na zewnątrz podstawową pozycją jest dokładna lokalizacja z Androida/Fused Location Provider.

Każdy punkt musi mieć zapisaną wartość `accuracy_m`.

Domyślna klasyfikacja jakości pozycji:

| Accuracy | Użycie |
|---:|---|
| `<= 10 m` | użyć normalnie |
| `>10 m i <=20 m` | oznaczyć ostrzeżeniem |
| `>20 m` | domyślnie nie używać do interpolacji heatmapy |

Progi mają być konfigurowalne.

Nie wolno generować fałszywie precyzyjnej mapy z punktów o słabym GPS. Raport ma podawać liczbę/udział próbek odrzuconych z powodu słabej lokalizacji.

---

## 14. INDOOR — nie opierać mapy tylko na GPS

W W1–W5 GPS może być niedokładny albo niestabilny. Dlatego projekt ma od początku obsługiwać **lokalny układ współrzędnych planu budynku**.

MVP dla indoor:

1. użytkownik wybiera plan/rzut,
2. aplikacja przechowuje lokalne współrzędne `x_m`, `y_m` albo znormalizowane współrzędne obrazu,
3. podczas przejścia użytkownik może nacisnąć **MARK POSITION/ANCHOR** i wskazać znany punkt na planie,
4. próbki między kolejnymi anchorami mogą zostać później interpolowane po czasie w narzędziu desktopowym,
5. surowy GPS nadal jest zapisywany jako dane pomocnicze.

Nie budować w MVP skomplikowanego dead reckoning z IMU. Przygotować model danych tak, aby w przyszłości można było dodać kroki/IMU bez migracji całej architektury.

---

## 15. Wi-Fi RTT / FTM — funkcja opcjonalna

Projekt ma przy uruchomieniu sprawdzać możliwość użycia Wi-Fi RTT.

Sprawdzić co najmniej:

- `PackageManager.FEATURE_WIFI_RTT`,
- dostępność `WifiRttManager`,
- czy konkretny ScanResult/AP deklaruje obsługę respondera FTM/802.11mc/odpowiedniego RTT.

Jeżeli telefon i AP obsługują RTT, można później rejestrować:

- BSSID respondera,
- odległość,
- odchylenie standardowe odległości,
- RSSI użytych ramek,
- liczbę prób i udanych pomiarów,
- timestamp.

**MVP nie może zależeć od RTT.**

Jeżeli UniFi AP nie wspierają odpowiedniej funkcji respondera, aplikacja po prostu pokazuje `RTT AP: unavailable` i działa dalej z GPS/indoor coordinates.

---

## 16. Roaming

Aplikacja ma wykrywać zmianę BSSID podczas pozostawania w tym samym SSID.

Zdarzenie roamingu zapisywać jako event:

```text
old_bssid
new_bssid
ssid
timestamp
rssi_before
rssi_after
```

Nie deklarować precyzyjnego czasu roamingu wyłącznie na podstawie wolnego próbkowania RSSI.

W drugim etapie można dodać **Roaming Test Mode**, który uruchamia częsty, lekki probe do lokalnego hosta i mierzy przerwę w osiągalności podczas zmiany BSSID.

Raport powinien rozróżniać:

- `BSSID transition detected`,
- `estimated service interruption`,
- rozdzielczość/niepewność pomiaru.

---

## 17. Aktywne testy LAN — osobny tryb

Nie wykonywać ciągłego testu przepustowości podczas zwykłego survey RSSI.

Ciągły `iperf` sam zajmuje airtime i może zmieniać warunki radiowe, które próbujemy mierzyć.

Podzielić testy na:

### Passive/Low-impact Survey

- RSSI,
- BSSID,
- kanał,
- częstotliwość,
- link speed,
- scan results,
- pozycja,
- roaming events.

### Connectivity Probe

Lekki test do konfigurowalnego hosta LAN:

- RTT,
- jitter,
- failed probes / packet loss proxy.

Preferować kontrolowany endpoint TCP/HTTP lub dedykowany lokalny serwis zamiast opierania całej funkcji na zachowaniu ICMP konkretnego urządzenia.

### Throughput Test

Osobny świadomie uruchamiany test do hosta w lokalnej sieci, docelowo np. iperf3.

Nie korzystać z publicznego Speedtest jako podstawowego testu jakości WLAN, ponieważ wynik miesza jakość Wi-Fi z dostępem WAN/operatora.

---

## 18. Model danych

Preferować Room/SQLite.

### SurveySession

Co najmniej:

```text
id UUID
name
mode OUTDOOR / INDOOR / MIXED
started_at
ended_at
app_version
device_model
android_version
android_sdk
ssid_filter optional
notes
configuration_snapshot
```

### ConnectedWifiSample

```text
id
session_id
timestamp_utc
timestamp_elapsed_ms
ssid
bssid
rssi_dbm
frequency_mhz
band
channel
channel_width
rx_link_speed_mbps nullable
tx_link_speed_mbps nullable
phy_standard nullable
location_id nullable
indoor_x nullable
indoor_y nullable
```

### WifiScanSnapshot

```text
id
session_id
scan_timestamp
fresh boolean
```

### WifiScanResult

```text
snapshot_id
ssid
bssid
rssi_dbm
frequency_mhz
band
channel
channel_width
capabilities
rtt_responder boolean/unknown
```

### LocationSample

```text
id
session_id
timestamp_utc
timestamp_elapsed_ms
latitude
longitude
accuracy_m
altitude_m nullable
vertical_accuracy_m nullable
bearing_deg nullable
speed_mps nullable
accepted_for_heatmap boolean
```

### SurveyEvent

```text
id
session_id
timestamp
type
payload_json
```

Przykładowe eventy:

```text
SURVEY_START
SURVEY_STOP
BSSID_CHANGE
LOCATION_DEGRADED
LOCATION_RECOVERED
WIFI_DISCONNECTED
WIFI_RECONNECTED
USER_ANCHOR
RTT_RESULT
PROBE_FAILURE
```

Schemat ma być wersjonowany.

---

## 19. Mapowanie BSSID → fizyczny AP

Nie wpisywać AP na stałe w kodzie.

Użyć pliku konfiguracyjnego, np.:

```text
config/ap-map.json
```

Przykład:

```json
{
  "aa:bb:cc:dd:ee:01": {
    "name": "AP-W1",
    "location": "W1-W3",
    "model": "U7 Long-Range"
  }
}
```

Po wdrożeniu infrastruktury plik zostanie uzupełniony rzeczywistymi BSSID.

Planowane AP są opisane w `docs/unifi-network-bom-v2.md`; aplikacja nie może jednak zakładać, że BOM zawsze odpowiada aktualnemu stanowi fizycznemu.

---

## 20. UI aplikacji — MVP

### Ekran 1 — Preflight

Pokazuje:

```text
Device             Galaxy Z Flip6
Wi-Fi              OK
Location           PRECISE / APPROX
GPS accuracy       4.2 m
Scan availability  OK
RTT device         YES/NO
Storage            OK
```

Przycisk:

```text
START NEW SURVEY
```

### Ekran 2 — konfiguracja sesji

Pola:

- nazwa sesji,
- OUTDOOR / INDOOR / MIXED,
- opcjonalny filtr SSID,
- notatka,
- aktywne pasma,
- częstotliwość próbkowania w granicach pozwalanych przez system.

### Ekran 3 — LIVE SURVEY

Duże, czytelne elementy:

```text
SSID
AP/BSSID
Band
Channel
RSSI
GPS accuracy
liczba próbek
czas sesji
```

RSSI ma mieć dużą wartość liczbową w dBm; kolory są wyłącznie dodatkiem i nie mogą zastępować liczby.

Przyciski:

```text
MARK POSITION
ADD NOTE
STOP SURVEY
```

### Ekran 4 — zakończenie/eksport

Pokazuje:

- czas,
- liczbę próbek Wi-Fi,
- liczbę lokalizacji,
- liczbę scan snapshotów,
- liczbę roaming events,
- procent punktów z dobrym GPS,
- ścieżkę eksportu.

---

## 21. Foreground service i praca podczas obchodu

Aktywna sesja ma być odporna na przypadkowe przełączenie aplikacji.

Podczas survey użyć foreground service z czytelną stałą notyfikacją, np.:

```text
Workshop WiFi Survey
Survey active — 12:34 — 742 samples
```

W MVP preferować pracę aplikacji na pierwszym planie i ekran aktywny podczas właściwego pomiaru Wi-Fi, szczególnie jeżeli zachowanie skanów przy wygaszonym ekranie na Flip6 nie zostało jeszcze zweryfikowane.

Dopiero po testach sprzętowych zdecydować, czy można niezawodnie prowadzić pełny survey po złożeniu telefonu/wygaszeniu ekranu.

---

## 22. Eksport danych

Po STOP SURVEY aplikacja tworzy kompletny, wersjonowany pakiet sesji.

Preferowany pojedynczy plik:

```text
survey_YYYYMMDD_HHMMSS_<short-id>.zip
```

W środku co najmniej:

```text
metadata.json
connected_wifi.csv
scan_results.csv
locations.csv
events.csv
track.geojson
schema_version.txt
```

Opcjonalnie:

```text
survey.db
notes.txt
```

Eksport zapisywać do łatwo dostępnego katalogu użytkownika, preferencyjnie:

```text
Documents/WorkshopWiFiSurvey/
```

Tak, aby można go było pobrać przez ADB bez specjalnych operacji na prywatnym sandboxie aplikacji.

---

## 23. Windows / ADB preflight

Codex powinien posiadać prostą procedurę lub narzędzie CLI wykonujące kolejno:

```text
1. adb devices
2. identyfikacja telefonu
3. odczyt Android/API
4. kontrola zainstalowanej wersji APK
5. instalacja/upgrade APK
6. nadanie możliwych runtime permissions przez adb pm grant
7. uruchomienie aplikacji
8. kontrola logcat w razie błędu
```

ADB służy do automatyzacji technicznej. Phone Link może służyć do czynności wymagających kliknięcia w UI Samsunga/Androida.

Pierwsza autoryzacja debugowania USB lub niektóre chronione zgody systemowe mogą wymagać fizycznego zatwierdzenia na telefonie. Nie próbować omijać zabezpieczeń Androida.

---

## 24. Pobieranie wyników przez Codex

Po ponownym podłączeniu telefonu Codex ma:

1. znaleźć najnowszy eksport,
2. pobrać go do katalogu projektu,
3. rozpakować,
4. uruchomić walidator,
5. nie analizować danych, jeżeli schemat lub pliki są uszkodzone bez zgłoszenia błędu.

Proponowana struktura lokalnych danych:

```text
workshop-wifi-survey/data/raw/<survey-id>/
workshop-wifi-survey/data/processed/<survey-id>/
workshop-wifi-survey/reports/<survey-id>/
```

Surowych danych nigdy nie modyfikować w miejscu. Wszystkie transformacje robić do `processed`.

---

## 25. Walidacja sesji

Przed generowaniem heatmapy sprawdzić:

- monotonie timestampów,
- zakres RSSI,
- poprawność częstotliwości,
- zgodność BSSID,
- brak duplikatów rekordów wynikających z importu,
- liczbę próbek,
- czas trwania,
- dostępność lokalizacji,
- procent punktów z `accuracy <= threshold`,
- luki czasowe w pomiarze,
- częstotliwość rzeczywistych skanów,
- czy skan był fresh/stale.

Raport walidacyjny ma powstać nawet wtedy, gdy późniejsza mapa nie może zostać wygenerowana.

---

## 26. Generowanie mapy zasięgu

### Outdoor

Dla punktów z zaakceptowaną dokładnością GPS:

1. przeliczyć współrzędne geograficzne na lokalny układ metryczny,
2. utworzyć warstwę punktów RSSI,
3. wykonać interpolację dopiero przy odpowiedniej gęstości próbek,
4. nie extrapolować agresywnie poza rzeczywiście przebytą trasę,
5. zachować możliwość pokazania również surowych punktów.

Domyślną prostą metodą interpolacji może być IDW. Nie maskować braków danych przez nadmierne wygładzanie.

### Indoor

Heatmapę nanosić na plan budynku na podstawie `x/y` oraz anchorów użytkownika.

### Rzut posesji

`docs/assets/podworko-rzut.jpg` jest pierwszym dostępnym podkładem dla naszej posesji.

Nie zakładać, że obraz jest obecnie georeferencjonowany ani ma poprawną skalę. Analiza powinna mieć etap kalibracji planu przy pomocy znanych punktów/anchorów. W przyszłości można zapisać transformację GPS ↔ piksel planu i ponownie używać jej dla następnych survey.

---

## 27. Raporty

Po każdej sesji Codex ma generować co najmniej:

```text
summary.md
coverage_24ghz.png
coverage_5ghz.png
route.png lub track.geojson
weak_spots.csv
roaming_events.csv
```

Jeżeli są dane 6 GHz:

```text
coverage_6ghz.png
```

Raport powinien zawierać:

- datę i czas survey,
- urządzenie,
- wersję aplikacji,
- czas trwania,
- liczbę próbek,
- użyte AP/BSSID,
- procent obszaru/próbek >= -67 dBm,
- miejsca poniżej -67/-70/-75 dBm,
- zdarzenia roamingu,
- luki w pomiarze,
- jakość lokalizacji,
- ostrzeżenia o niewystarczających danych.

---

## 28. Porównanie survey

Każda sesja ma pozostawać osobnym, niezmiennym rekordem.

Narzędzie desktopowe ma umożliwiać porównanie np.:

```text
Survey 001 — konfiguracja początkowa
Survey 002 — AP-W1 TX High -> Medium
Survey 003 — zmiana kanału AP-W3
```

Raport porównawczy powinien pokazać:

- zmianę RSSI dla porównywalnych obszarów,
- zmianę udziału punktów powyżej progu,
- zmianę liczby weak spots,
- roaming przed/po,
- ewentualne pogorszenie innego obszaru.

Nie porównywać bezkrytycznie próbek o zupełnie innych trasach/gęstości. Raport ma podawać poziom porównywalności danych.

---

## 29. Kontekst naszej sieci UniFi

Aktualny plan AP i przełączników znajduje się w `docs/unifi-network-bom-v2.md`.

Survey ma być przygotowany przede wszystkim do strojenia:

- W1–W3,
- W4,
- W5,
- pomieszczenia gospodarczego,
- domu,
- placu/podwórka.

BSSID/AP mają być identyfikowane konfiguracyjnie, aby po zamontowaniu urządzeń można było przypisać fizyczne nazwy, np.:

```text
AP-W1-W3
AP-W4
AP-W5
AP-HOUSE
AP-YARD
```

Dokładne nazwy zostaną ustalone przy wdrożeniu UniFi.

---

## 30. Kryteria MVP

MVP jest zakończony dopiero, gdy na fizycznym Galaxy Z Flip6 można przeprowadzić cały poniższy scenariusz:

1. Codex buduje APK bez błędów.
2. Codex instaluje APK przez ADB.
3. Aplikacja przechodzi Preflight.
4. Użytkownik rozpoczyna survey.
5. Telefon jest odłączany od USB.
6. Aplikacja zbiera minimum RSSI+BSSID+frequency oraz lokalizację przez kilka minut.
7. Następuje co najmniej jedna zmiana lokalizacji.
8. Sesja zostaje zatrzymana bez utraty danych.
9. Powstaje ZIP eksportu.
10. Codex pobiera ZIP przez ADB.
11. Walidator potwierdza poprawny format.
12. Narzędzie desktopowe generuje co najmniej scatter map/track z RSSI.
13. Surowe dane pozostają zachowane.
14. Test i jego rezultat zostają opisane w repo.

---

## 31. Etapy implementacji

### Stage 1 — Android collector MVP

Zbudować:

- projekt Android,
- permissions,
- Preflight,
- Start/Stop survey,
- foreground service,
- Room DB,
- 1 Hz connected Wi-Fi sample,
- 1 Hz location sample,
- eventy połączenia/BSSID,
- prosty live screen,
- eksport ZIP/CSV/GeoJSON.

### Stage 2 — scan sąsiednich AP

Dodać:

- kontrolowane skany,
- scan snapshots,
- fresh/stale status,
- 2.4/5/6 GHz model,
- RTT capability discovery.

### Stage 3 — desktop analysis

Dodać:

- ADB pull,
- validator,
- parsowanie,
- track/scatter RSSI,
- heatmapę outdoor,
- wykrywanie weak spots,
- summary.md.

### Stage 4 — indoor positioning

Dodać:

- floorplan,
- anchors,
- lokalne x/y,
- kalibrację,
- indoor heatmap.

### Stage 5 — active network tests

Dodać:

- lekki connectivity probe,
- jitter/loss proxy,
- roaming test mode,
- później iperf3/throughput jako osobny test.

### Stage 6 — porównanie i integracja UniFi

Dodać:

- porównywanie survey,
- mapę różnicową,
- powiązanie BSSID z nazwami AP,
- rekomendacje zmian parametrów UniFi.

Nie zaczynać od Stage 6. Najpierw wiarygodny collector i format danych.

---

## 32. Testy automatyczne

Co najmniej:

### Android

- unit test przeliczania frequency → band/channel,
- test progów RSSI,
- test serializacji/exportu,
- test migracji Room,
- test eventu BSSID change na danych sztucznych,
- test odrzucania złej lokalizacji według accuracy threshold.

### Desktop

- parser poprawnego ZIP,
- parser uszkodzonego ZIP,
- walidacja timestampów,
- walidacja BSSID/frequency,
- generowanie mapy na synthetic dataset,
- brak crasha przy braku GPS,
- brak crasha przy braku scan_results,
- reproducible output dla danych testowych.

Testów sprzętowych nie emulować jako „zaliczone”. Wyraźnie oddzielać wynik testów software od fizycznej walidacji Flip6.

---

## 33. Zachowanie w sytuacjach błędowych

Aplikacja nie może po cichu kontynuować pomiaru z niewiarygodnymi danymi.

Przykłady:

- brak Wi-Fi → event + widoczne ostrzeżenie,
- utrata lokalizacji → event, Wi-Fi nadal może być zapisywane,
- accuracy > threshold → zapisać surowy punkt, ale oznaczyć jako niewiarygodny dla heatmapy,
- brak świeżego skanu → zachować poprzednie scan results jako `stale`, nie jako nowy pomiar,
- brak miejsca na dysku → zatrzymać/ostrzeż użytkownika zanim dojdzie do utraty sesji,
- crash → po restarcie spróbować odzyskać niezamkniętą sesję i oznaczyć ją `interrupted`.

---

## 34. Dane surowe są nadrzędne

Zasada projektu:

> Nigdy nie zapisujemy wyłącznie wyniku przetworzonego, jeżeli można zachować surowy pomiar.

Przykład:

Zachować:

```text
frequency_mhz = 5220
rssi_dbm = -63
bssid = ...
accuracy_m = 4.8
```

oraz dopiero dodatkowo wyliczyć:

```text
band = 5GHz
channel = 44
quality = GOOD
```

Dzięki temu późniejsza zmiana progów lub algorytmu nie wymaga powtarzania fizycznego survey.

---

## 35. Wersjonowanie i reproducibility

Każdy eksport musi zawierać:

- schema version,
- app version/git commit jeżeli dostępne,
- konfigurację sesji,
- progi użyte przy live view,
- model telefonu,
- Android SDK,
- datę/czas.

Raport desktopowy również ma zapisywać wersję narzędzia/commit, aby można było odtworzyć sposób wygenerowania konkretnej heatmapy.

---

## 36. Czego MVP nie ma robić

Nie budować na pierwszym etapie:

- analizatora widma RF,
- monitor mode / packet capture,
- systemu wykrywania obcych nadajników nie-Wi-Fi,
- rozbudowanego ML do pozycjonowania,
- SLAM,
- ciągłego speedtestu,
- automatycznej zmiany konfiguracji UniFi bez zatwierdzenia,
- rozbudowanego backendu/chmury.

Dane mają pozostawać lokalnie na telefonie i komputerze, chyba że użytkownik później zdecyduje inaczej.

---

## 37. Ograniczenia pomiarowe telefonu

Galaxy Z Flip6 jest bardzo dobrym odbiornikiem do praktycznego survey użytkownika końcowego, ale wynik reprezentuje zachowanie **tego konkretnego klienta Wi-Fi**.

Nie jest to:

- skalibrowany laboratoryjny miernik poziomu RF,
- analizator widma,
- WLAN Pi z monitor mode,
- NetAlly/AirCheck.

Raport ma więc używać określeń typu `RSSI reported by Android/Flip6`, a nie sugerować laboratoryjnej dokładności mocy RF.

To jest jednak właściwy pomiar do naszego głównego celu: sprawdzenia, jak rzeczywisty klient mobilny zachowuje się w naszej sieci UniFi podczas przemieszczania się po warsztacie i placu.

---

## 38. Oczekiwany przykładowy wynik

Po survey Codex powinien móc przedstawić wynik podobny do:

```text
Workshop WiFi Survey #004

Duration: 18m 32s
Samples: 1112
Valid GPS samples: 88%

5 GHz coverage:
  >= -67 dBm: 91%
  -68..-70 dBm: 5%
  < -70 dBm: 4%

Weak areas:
  W2 NE corner     -72 dBm
  W3 entrance      -69 dBm
  W4 rear          -74 dBm

Roaming events:
  AP-W1-W3 -> AP-W4
  AP-W4 -> AP-W5

Warnings:
  46 samples excluded from heatmap due to location accuracy > 20 m
```

oraz mapy pokrycia osobno dla 2.4 i 5 GHz.

---

## 39. Instrukcja startowa dla Codexa

Po otrzymaniu tego dokumentu i polecenia rozpoczęcia projektu Codex ma:

1. przeczytać `docs/workshop-wifi-survey-android-plan.md`,
2. przeczytać `docs/unifi-network-bom-v2.md`,
3. sprawdzić aktualną zawartość repo i aktualny `main`,
4. utworzyć osobną gałąź `feat/workshop-wifi-survey-mvp` lub uzgodniony odpowiednik,
5. nie zmieniać `main`,
6. utworzyć szkielet `workshop-wifi-survey/`,
7. rozpocząć od **Stage 1 — Android collector MVP**,
8. uruchomić wszystkie testy możliwe bez sprzętu,
9. podłączyć Flip6 przez ADB, gdy użytkownik jest przy komputerze,
10. wykonać test sprzętowy MVP według kryteriów z sekcji 30,
11. zapisać wynik testów w repo,
12. dopiero potem przejść do kolejnego etapu.

W przypadku niejasności technicznej Codex ma preferować rozwiązanie, które zachowuje **surowe dane, możliwość późniejszej analizy i kompatybilność wsteczną formatu**, zamiast upraszczać kosztem utraty informacji pomiarowej.

---

## 40. Referencje techniczne do weryfikacji przy implementacji

Przed implementacją szczegółów uprawnień i skanowania sprawdzić aktualną dokumentację Android Developers, ponieważ wymagania zależą od `targetSdk` i wersji systemu:

- Wi-Fi permissions: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- Wi-Fi scanning: https://developer.android.com/develop/connectivity/wifi/wifi-scan
- Location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- Current/fused location: https://developer.android.com/develop/sensors-and-location/location/retrieve-current
- Wi-Fi RTT: https://developer.android.com/develop/connectivity/wifi/wifi-rtt

Dokumentacja Androida ma pierwszeństwo przed starymi przykładami znalezionymi w blogach/StackOverflow.

---

## 41. Decyzje projektowe ustalone na dzień 2026-09-10

1. **Galaxy Z Flip6** jest pierwszym urządzeniem pomiarowym.
2. Na obecnym etapie nie kupujemy specjalnego miernika wyłącznie po to, aby wykonać podstawowy site survey.
3. Windows 11 + Codex ma przygotowywać telefon i analizować wyniki.
4. ADB pozostaje technicznym kanałem instalacji, diagnostyki i pobierania danych.
5. Phone Link może służyć do sterowania UI telefonu z Windows, bez potrzeby budowania własnego mechanizmu mirrorowania.
6. Podczas właściwego survey USB jest odłączone.
7. Podczas właściwego survey nie utrzymujemy zbędnego screen mirroringu/Phone Link generującego ruch.
8. Pozycja GPS jest zapisywana razem z **dokładnością pomiaru**.
9. Na zewnątrz używamy GPS/Fused Location.
10. W budynkach nie polegamy wyłącznie na GPS — przewidujemy local coordinates/anchors.
11. Wi-Fi RTT jest opcjonalnym rozszerzeniem i będzie wykrywane runtime.
12. Podstawą mapy zasięgu jest RSSI raportowane przez Flip6.
13. Pełny throughput jest osobnym trybem, aby nie zanieczyszczać zwykłego survey.
14. Każdy survey jest wersjonowany i zachowywany do porównań przed/po zmianach UniFi.
15. `docs/assets/podworko-rzut.jpg` ma zostać wykorzystany jako początkowy podkład posesji po odpowiedniej kalibracji/georeferencji.

---

## 42. Następna akcja

Po zatwierdzeniu dokumentu następnym zadaniem projektu jest:

> **zbudować Stage 1 — Android collector MVP na osobnej gałęzi i doprowadzić do pierwszego fizycznego testu na Samsung Galaxy Z Flip6.**
