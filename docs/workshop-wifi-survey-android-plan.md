# Workshop WiFi Survey — specyfikacja wykonawcza aplikacji Android i workflow z Codex

**Status:** READY FOR IMPLEMENTATION / source of truth dla implementacji

**Wersja dokumentu:** 1.1

**Repozytorium:** `autoklinika/Infrastructure`

**Widoczność repozytorium:** PUBLIC — traktować zasady prywatności z sekcji 6 jako wymaganie krytyczne

**Data ustalenia:** 2026-09-10

**Docelowe urządzenie pomiarowe:** Samsung Galaxy Z Flip6

**Powiązane dokumenty:**

- `docs/unifi-network-bom-v2.md` — plan infrastruktury UniFi,
- `docs/assets/podworko-rzut.jpg` — istniejący rzut posesji/podwórka; nie dodawać do repo dokładnej georeferencji tego obrazu.

---

## 1. Cel dokumentu

Ten dokument jest kompletną specyfikacją wykonawczą dla Codexa. Po jego otrzymaniu Codex ma móc rozpocząć implementację bez ponownego odtwarzania założeń z rozmów.

Główny cel projektu:

> wykorzystać Samsung Galaxy Z Flip6 jako mobilny rejestrator parametrów Wi-Fi i pozycji, wykonać powtarzalny obchód W1–W5 oraz placu, a następnie wygenerować wiarygodne mapy i raporty potrzebne do strojenia infrastruktury UniFi.

Aplikacja służy do pomiarów naszej własnej infrastruktury WLAN, odbiorów instalacji, porównywania konfiguracji AP i identyfikacji obszarów wymagających korekty.

Najważniejsza zasada jakościowa:

> najpierw zachowujemy poprawny surowy pomiar wraz z czasem, źródłem i jakością pozycji; dopiero później tworzymy interpretację, heatmapę i rekomendacje.

---

## 2. Reguły nienegocjowalne

1. Nie wykonywać bezpośrednich zmian w `main`.
2. Nie scalać do `main` bez jednoznacznej zgody użytkownika.
3. Implementację prowadzić na osobnej gałęzi, domyślnie `feat/workshop-wifi-survey-mvp`.
4. Rzeczywistych danych survey, GPS, BSSID/SSID, numeru seryjnego telefonu ani lokalnej mapy AP nie commitować do publicznego repo.
5. Nie mieszać w jednej warstwie pomiarów `CONNECTED_LINK` i `SCAN_RESULT`.
6. Do korelacji Wi-Fi ↔ pozycja używać czasu monotonicznego urządzenia; UTC służy do prezentacji i korelacji między systemami, nie jako główny klucz join.
7. Nie traktować starego wyniku skanu jako nowego pomiaru.
8. Nie tworzyć fałszywej precyzji GPS ani heatmapy poza obszarem wspartym danymi.
9. Testów wymagających fizycznego Flip6 nie oznaczać jako zaliczone na emulatorze.
10. Surowe dane są niezmienne; kolejne etapy analizy zapisują nowe artefakty.

---

## 3. Docelowy workflow operatora

1. Podłączamy Galaxy Z Flip6 do Windows 11 przez USB.
2. Codex sprawdza ADB, model, Android/API, wersję aplikacji i warunki pomiarowe.
3. Codex buduje/instaluje lub aktualizuje `Workshop WiFi Survey`.
4. Operator przechodzi ekran Preflight.
5. Operator uruchamia nową sesję pomiarową.
6. Odłączamy USB.
7. Operator wykonuje ustaloną trasę W1–W5 oraz placu.
8. Telefon samodzielnie zapisuje próbki Wi-Fi, pozycję, jakość pozycji i eventy.
9. Operator kończy sesję.
10. Telefon ponownie podłączamy przez USB.
11. Codex pobiera eksport przez ADB do lokalnego, ignorowanego przez Git katalogu.
12. Codex uruchamia walidator.
13. Dopiero po poprawnej walidacji generowane są mapy i raport.
14. Na podstawie raportu zmieniamy konfigurację UniFi.
15. Kolejny survey wykonujemy możliwie tą samą trasą i metodą.
16. Narzędzie porównawcze analizuje sesje przed/po.

Podczas właściwego survey telefon nie jest połączony USB z komputerem. Phone Link / screen mirroring powinny być wyłączone, aby nie generować dodatkowego ruchu WLAN i nie zmieniać warunków testu.

---

## 4. Architektura projektu

Projekt dzielimy na dwie logiczne części.

### 4.1. Android — collector

Odpowiada za:

- bieżący stan połączenia Wi-Fi,
- okresowe wyniki skanów AP,
- pozycję i jakość lokalizacji,
- eventy roamingu/utraty połączenia,
- lokalny trwały zapis,
- crash recovery,
- wersjonowany eksport,
- prosty podgląd jakości sesji.

### 4.2. Desktop/Windows — analiza

Odpowiada za:

- ADB preflight i instalację APK,
- pobieranie sesji,
- walidację,
- korelację danych,
- mapy punktowe i heatmapy,
- weak spots,
- roaming report,
- porównanie sesji,
- rekomendacje zmian w UniFi.

MVP nie wykonuje ciężkiej interpolacji na telefonie. Telefon ma być przede wszystkim wiarygodnym rejestratorem.

---

## 5. Proponowana struktura repo

Nazwa aplikacji:

```text
Workshop WiFi Survey
```

Application ID:

```text
pl.autoklinika.infrastructure.wifisurvey
```

Struktura bazowa:

```text
workshop-wifi-survey/
├── android/
│   ├── app/
│   └── ...
├── analysis/
│   ├── README.md
│   ├── pyproject.toml
│   ├── pull_latest_survey.py
│   ├── validate_survey.py
│   ├── generate_maps.py
│   └── compare_surveys.py
├── config/
│   ├── ap-map.example.json
│   └── survey-defaults.json
├── testdata/
│   └── synthetic/
├── docs/
│   └── ...
└── README.md
```

Android collector i analiza desktopowa mają pozostać rozdzielone nawet wtedy, gdy Codex dostosuje strukturę do istniejącego repo.

---

## 6. Prywatność i bezpieczeństwo danych — wymaganie krytyczne

Repo `autoklinika/Infrastructure` jest publiczne. Dane rzeczywistego survey są danymi operacyjnymi i nie mogą trafiać do Git.

Za prywatne traktować co najmniej:

- rzeczywiste SSID,
- rzeczywiste BSSID/MAC,
- dokładne latitude/longitude,
- dokładną georeferencję rzutu posesji,
- przebieg trasy po obiekcie,
- lokalizacje fizycznych AP,
- lokalne adresy IP ujawniające topologię, jeśli nie są potrzebne w dokumentacji publicznej,
- Android device serial / ADB serial,
- pełny `adb bugreport`, `logcat` lub screenshot zawierający powyższe dane,
- notatki operatora mogące zawierać dane osób lub klientów.

### 6.1. Zasada storage

Rzeczywiste dane mają trafiać do lokalnego katalogu poza kontrolą wersji, np.:

```text
<LOCAL_DATA_ROOT>/WorkshopWiFiSurvey/raw/<survey-id>/
<LOCAL_DATA_ROOT>/WorkshopWiFiSurvey/processed/<survey-id>/
<LOCAL_DATA_ROOT>/WorkshopWiFiSurvey/reports/<survey-id>/
```

Jeżeli dane robocze są trzymane wewnątrz checkoutu repo, muszą znajdować się wyłącznie w katalogu jawnie ignorowanym przez Git.

Przed pierwszym pobraniem rzeczywistego eksportu Codex ma dodać i zweryfikować odpowiednie reguły `.gitignore`, co najmniej dla:

```text
workshop-wifi-survey/data/
workshop-wifi-survey/.local/
workshop-wifi-survey/reports/private/
workshop-wifi-survey/config/ap-map.local.json
workshop-wifi-survey/config/*.private.json
workshop-wifi-survey/**/*.survey.zip
workshop-wifi-survey/**/survey_*.zip
.env
local.properties
```

Codex ma wykonać `git check-ignore` dla docelowego katalogu danych przed `adb pull`. Jeśli ścieżka nie jest ignorowana, pobranie do checkoutu repo ma zostać przerwane.

### 6.2. Dane dozwolone w repo

Do repo można commitować:

- kod,
- dokumentację,
- schematy danych,
- `ap-map.example.json` z fikcyjnymi wartościami,
- synthetic test data,
- raporty wygenerowane wyłącznie z synthetic/anonymized data.

Nie commitować rzeczywistych eksportów nawet wtedy, gdy wydają się chwilowo potrzebne do debugowania.

### 6.3. Konfiguracja AP

Rzeczywiste mapowanie BSSID → fizyczny AP przechowywać lokalnie jako np.:

```text
config/ap-map.local.json
```

W repo pozostaje wyłącznie `config/ap-map.example.json` z fikcyjnymi MAC/BSSID.

Istniejący `docs/assets/podworko-rzut.jpg` może być używany jako podkład projektu, ale dokładne punkty GPS potrzebne do georeferencji mają być przechowywane lokalnie, poza Git.

---

## 7. Technologie Android

Preferowane:

- Kotlin,
- Android SDK,
- Jetpack Compose,
- coroutines / Flow,
- Room/SQLite,
- foreground service typu `location` podczas aktywnego survey,
- Fused Location Provider,
- `ConnectivityManager` + `NetworkCapabilities` + `WifiInfo` dla bieżącego połączenia,
- `WifiManager` dla skanów,
- `WifiRttManager` wyłącznie opcjonalnie.

Nie zamrażać w tym dokumencie konkretnej wersji Android Studio/Gradle. Codex ma użyć aktualnego stabilnego toolchainu zgodnego z urządzeniem.

Na początku implementacji wykonać:

```text
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
```

Nie zapisywać `ro.serialno` ani ADB serial do repo/raportów publicznych.

Galaxy Z Flip6 obsługuje Wi-Fi 6E, więc model danych musi obsługiwać 2.4/5/6 GHz. Projekt nie zakłada jednak, że aktualna infrastruktura wykorzystuje 6 GHz.

---

## 8. Uprawnienia Android i Preflight

Codex ma zweryfikować bieżące wymagania Android SDK przed implementacją. Minimum do rozważenia:

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

`getScanResults()` i `startScan()` nadal wymagają odpowiednich uprawnień lokalizacyjnych oraz włączonych usług lokalizacji na wspieranych wersjach Androida. Nie zakładać, że samo `NEARBY_WIFI_DEVICES` wystarczy.

Foreground service lokalizacyjny ma być uruchamiany po naciśnięciu START SURVEY, gdy aplikacja jest widoczna. W MVP nie żądać szerokiego `ACCESS_BACKGROUND_LOCATION`, jeśli nie jest konieczny dla tego scenariusza; dodać go wyłącznie wtedy, gdy test na fizycznym Flip6 wykaże realną potrzebę i zostanie to udokumentowane.

### Ekran Preflight

Ma pokazywać co najmniej:

```text
Device model              OK / ERROR
Android API               value
Wi-Fi                     ON / OFF
Connected Wi-Fi           YES / NO
SSID/BSSID readable       YES / NO
Precise location          GRANTED / DENIED
Location services         ON / OFF
Current location age      value
Current accuracy          value
Wi-Fi scan permission     OK / ERROR
Last real scan age        value
Foreground logging        OK / ERROR
Export target             OK / ERROR
Free storage              value
Wi-Fi RTT device          YES / NO
```

START SURVEY blokować przy braku krytycznych warunków. Brak RTT nie jest błędem krytycznym.

---

## 9. Zegary i korelacja danych — zasada podstawowa

Każdy rekord pomiarowy ma posiadać dwa czasy:

1. `timestamp_utc` — ISO-8601 UTC do raportów i korelacji z innymi systemami,
2. `timestamp_elapsed_ns` lub równoważny monotoniczny czas od startu systemu — do korelacji danych wewnątrz sesji.

Do joinów Wi-Fi ↔ location używać czasu monotonicznego. Zmiana czasu systemowego/NTP/strefy nie może przesunąć pomiarów względem ścieżki.

Każdy strumień posiada własny `sequence_no`, rosnący w obrębie sesji.

---

## 10. Dwa niezależne źródła pomiaru Wi-Fi

Nie traktować wszystkich wartości RSSI jako jednego rodzaju danych.

### 10.1. `CONNECTED_LINK`

Parametry AP, z którym telefon jest aktualnie połączony. Docelowo próbka około 1 Hz.

Zapisywać co najmniej:

```text
sequence_no
timestamp_utc
timestamp_elapsed_ns
source = CONNECTED_LINK
ssid
bssid
rssi_dbm
frequency_mhz
band
channel
channel_width nullable
rx_link_speed_mbps nullable
tx_link_speed_mbps nullable
max_supported_rx_link_speed_mbps nullable
max_supported_tx_link_speed_mbps nullable
wifi_standard nullable
network_transport_state
location_id_at_capture nullable
location_age_ms nullable
```

### 10.2. `SCAN_RESULT`

Okresowe wyniki widocznych AP. To osobny strumień.

Dla snapshotu zapisać:

```text
snapshot_id
request_elapsed_ns
callback_elapsed_ns
callback_utc
request_accepted
results_updated
result_count
```

Dla każdego wyniku:

```text
snapshot_id
source = SCAN_RESULT
ssid
bssid
rssi_dbm
frequency_mhz
band
channel
channel_width
capabilities
rtt_responder
platform_seen_elapsed_us
result_age_at_callback_ms
fresh
location_id_for_seen_time nullable
location_join_delta_ms nullable
```

`ScanResult.timestamp` reprezentuje czas od startu systemu, kiedy dany AP został ostatnio widziany. Należy go zapisać i wykorzystać do określenia świeżości oraz przypisania pozycji.

Jeżeli API zwraca stary wynik, zachować go jako `fresh=false`; nie udawać nowego pomiaru.

---

## 11. Wi-Fi scan throttling

Android ogranicza aktywne `WifiManager.startScan()`.

Przy standardowym throttlingu Android 9+ aplikacja foreground może mieć bardzo ograniczoną liczbę aktywnych skanów; Android 10+ posiada opcję deweloperską wyłączenia ograniczania do testów lokalnych.

Dlatego aplikacja ma:

1. działać poprawnie również przy włączonym throttlingu,
2. rejestrować każdą próbę skanu i wynik `startScan()`,
3. rejestrować callback i `resultsUpdated`,
4. korzystać również z broadcastów skanów wykonanych przez system/inne aplikacje, jeżeli są dostępne,
5. nie generować sztucznych snapshotów co 5 s z tych samych danych.

Docelowy request interval przy wyłączonym throttlingu: konfigurowalny, startowo **5 s**. Rzeczywista częstotliwość jest zawsze raportowana z danych, nie z konfiguracji.

Procedura przygotowania telefonu ma sprawdzać opcję:

```text
Developer Options > Networking > Wi-Fi scan throttling
```

Jeżeli nie ma stabilnej publicznej metody ADB do jej zmiany, Codex ma podać użytkownikowi dokładną czynność w UI; nie stosować nieudokumentowanych hacków systemowych jako wymagania projektu.

---

## 12. Parametry i progi RSSI

Podstawową wartością jest RSSI w dBm raportowane przez Android/Flip6.

Domyślne progi:

| RSSI | Ocena |
|---:|---|
| `>= -55 dBm` | bardzo dobry |
| `-56 ... -67 dBm` | dobry / docelowy |
| `-68 ... -70 dBm` | graniczny |
| `-71 ... -75 dBm` | słaby |
| `< -75 dBm` | problematyczny |

Główny próg startowy: `-67 dBm`.

Progi są konfiguracją analizy, nie częścią surowego pomiaru.

Nie wyliczać ani nie raportować jako pomiaru:

- noise floor,
- SNR,
- channel utilization,
- airtime utilization,

jeżeli publiczne API urządzenia nie dostarcza wiarygodnego źródła tych wartości.

Telefon nie jest analizatorem widma ani interfejsem monitor mode.

---

## 13. Pasma i kanały

Obsługiwać:

- 2.4 GHz,
- 5 GHz,
- 6 GHz.

Model ma być otwarty na kolejne standardy/width values zwracane przez Androida. Nie używać enumów, które crashują przy nieznanej wartości platformy.

Kanał obliczać z częstotliwości tylko wtedy, gdy API nie daje wiarygodnej wartości. Surową `frequency_mhz` zawsze zachować.

---

## 14. Lokalizacja — surowy strumień

Docelowa częstotliwość podczas aktywnej sesji: około 1 Hz, z priorytetem dokładności.

`LocationSample` zapisuje:

```text
id
session_id
sequence_no
timestamp_utc
timestamp_elapsed_ns
latitude
longitude
accuracy_m
altitude_m nullable
vertical_accuracy_m nullable
bearing_deg nullable
bearing_accuracy_deg nullable
speed_mps nullable
speed_accuracy_mps nullable
provider nullable
is_mock_if_available
```

Surowej lokalizacji nie odrzucać z bazy tylko dlatego, że accuracy jest słabe. Odrzucenie dotyczy wyłącznie późniejszego użycia w mapie.

---

## 15. Wiązanie konkretnej próbki Wi-Fi z pozycją

To jest wymaganie krytyczne.

### 15.1. Connected link

W chwili tworzenia `ConnectedWifiSample` zapisać `timestamp_elapsed_ns` i identyfikator najnowszej znanej lokalizacji.

Zapisać również:

```text
location_id_at_capture
location_age_ms
location_accuracy_m_at_capture
```

Domyślny `max_location_age_ms` dla użycia w analizie: **2500 ms**, konfigurowalny.

Jeżeli nie ma lokalizacji w tym oknie, próbka Wi-Fi pozostaje poprawnym pomiarem Wi-Fi, ale ma status `UNLOCATED` i nie może wejść do heatmapy przestrzennej.

### 15.2. Scan result

Nie przypisywać całego snapshotu do pozycji z chwili callbacku.

Dla każdego `ScanResult`:

1. przeliczyć `platform_seen_elapsed_us` na tę samą bazę monotoniczną,
2. znaleźć najbliższą czasowo `LocationSample`,
3. zaakceptować join tylko, gdy `abs(location_time - seen_time) <= max_location_join_ms`, startowo **2500 ms**,
4. zapisać `location_id_for_seen_time` i `location_join_delta_ms`,
5. jeżeli wynik jest stary lub brak lokalizacji w oknie — oznaczyć jako nieprzydatny do mapy, ale zachować surowo.

Dzięki temu AP widziany kilka sekund wcześniej nie zostaje błędnie przeniesiony do aktualnej pozycji telefonu.

### 15.3. UTC

UTC nie może być jedyną podstawą korelacji. Join wykonujemy po monotonicznym czasie urządzenia.

---

## 16. OUTDOOR — GPS/Fused Location

Domyślna klasyfikacja jakości:

| Accuracy | Użycie |
|---:|---|
| `<= 10 m` | normalnie |
| `> 10 m i <= 20 m` | ostrzeżenie / użycie zależne od warstwy |
| `> 20 m` | domyślnie poza heatmapą |

Progi są konfigurowalne.

Raport ma podawać liczbę i udział próbek odrzuconych z powodu:

- braku lokalizacji,
- zbyt starej lokalizacji,
- słabego `accuracy_m`,
- zbyt dużego `location_join_delta_ms`.

Nie generować fałszywie precyzyjnej interpolacji z punktów o dokładności większej niż skala analizowanego obszaru.

---

## 17. INDOOR — local coordinates i anchors

W W1–W5 nie polegać wyłącznie na GPS.

MVP indoor ma wspierać:

1. wybór planu/strefy,
2. lokalny układ współrzędnych,
3. `MARK POSITION / ANCHOR`,
4. zapis znanego `x/y` na planie,
5. interpolację po czasie między kolejnymi anchorami w analizie desktopowej,
6. równoległe zachowanie surowego GPS jako danych pomocniczych.

Preferować współrzędne metryczne po kalibracji planu:

```text
indoor_x_m
indoor_y_m
floorplan_id
anchor_id
```

Jeżeli plan nie ma jeszcze skali, można przechować także współrzędne znormalizowane obrazu, ale analiza musi jawnie odróżniać piksele/normalized coordinates od metrów.

Nie budować w MVP SLAM/dead reckoning z IMU. Schemat ma pozwalać dodać IMU/steps później.

---

## 18. Metodologia fizycznego survey W1–W5 i placu

Celem jest **powtarzalność**, a nie maksymalna liczba przypadkowych punktów.

### 18.1. Standard urządzenia

Dla sesji porównawczych:

- używać tego samego Galaxy Z Flip6,
- telefon rozłożony,
- ekran aktywny, dopóki testy nie potwierdzą poprawnego działania po wygaszeniu/złożeniu,
- telefon trzymany możliwie w tej samej orientacji, ekranem do operatora,
- nie wkładać telefonu do kieszeni,
- nie zasłaniać go dłonią w zmienny sposób,
- wysokość podczas marszu orientacyjnie 1.2–1.5 m nad podłożem,
- wyłączyć hotspot/tethering,
- wyłączyć screen mirroring/Phone Link podczas pomiaru,
- nie uruchamiać równolegle speedtest/iperf/downloadów na telefonie w trybie passive survey.

Stan sieci komórkowej/VPN/Bluetooth powinien być możliwie taki sam w survey porównawczych i zapisany w `configuration_snapshot`. Dla testów aktywnych aplikacja ma wiązać ruch z Wi-Fi lub operator ma wyłączyć mobile data, aby nie dopuścić do cichego fallbacku na LTE/5G.

### 18.2. Trasa

Dla każdego survey porównawczego zapisać `route_profile`, np. `SITE_BASELINE_V1`.

Trasa powinna obejmować co najmniej:

- W1,
- W2,
- W3,
- W4,
- W5,
- przejścia między budynkami,
- obszary robocze,
- wejścia/bramy,
- plac/podwórko,
- znane miejsca krytyczne.

W każdej strefie wykonać tę samą sekwencję przejścia, np. obwód + przejście centralne, jeśli geometria pomieszczenia na to pozwala.

### 18.3. Tempo

Iść wolno i równomiernie, orientacyjnie **0.7–1.0 m/s**. Przy 1 Hz daje to próbki bieżącego połączenia mniej więcej co 0.7–1 m.

W kluczowych anchorach lub weak spots można zatrzymać się na 3–5 s, ale postój ma być oznaczony eventem/anchor, aby analiza nie traktowała nadmiaru próbek w jednym miejscu jako większego obszaru.

### 18.4. Start sesji

Przed rozpoczęciem właściwej trasy:

- poczekać na stabilne połączenie Wi-Fi,
- outdoor: poczekać na akceptowalny fix lokalizacji,
- indoor: rozpocząć w znanym anchorze,
- zapisać notatkę o istotnych warunkach, np. otwarte/zamknięte bramy, duża liczba pojazdów, pracujące maszyny, nietypowa liczba osób.

### 18.5. Sesje przed/po

Porównanie konfiguracji UniFi ma używać możliwie:

- tej samej trasy,
- podobnego tempa,
- tej samej orientacji telefonu,
- tego samego trybu survey,
- tych samych progów analizy,
- podobnych warunków środowiskowych.

Raport porównawczy ma ostrzegać, jeśli trasy lub gęstość danych są zbyt różne.

---

## 19. Rozdzielenie źródeł danych do map i heatmap

To jest wymaganie krytyczne.

Nie wolno tworzyć jednej warstwy RSSI z połączenia bieżącego i wyników skanów bez jawnego rozróżnienia.

### 19.1. Connected coverage

Źródło:

```text
source = CONNECTED_LINK
```

Pokazuje jakość AP, z którym klient rzeczywiście był połączony w danym miejscu.

Nadaje się do oceny:

- realnego doświadczenia klienta,
- miejsca przełączenia BSSID,
- rzeczywistego RSSI połączenia,
- przebiegu roamingu.

Warstwa może mieć w różnych miejscach różne pasmo, bo klient sam wybiera AP/band. Raport musi to pokazywać.

### 19.2. Scan visibility

Źródło:

```text
source = SCAN_RESULT
```

Pokazuje widoczność konkretnego BSSID/pasma z okresowych skanów.

Nadaje się do map:

- `AP-W1 5 GHz visibility`,
- `AP-W4 2.4 GHz visibility`,
- widoczności sąsiednich AP,
- overlapu zasięgu.

Mapę tworzyć tylko z `fresh=true` i poprawnie połączonych czasowo lokalizacji.

### 19.3. Zakaz mieszania

`CONNECTED_LINK.rssi_dbm` i `SCAN_RESULT.rssi_dbm` mogą różnić się sposobem/rytmem aktualizacji. Nie łączyć ich w jeden ciąg bez pola `source` i świadomej analizy.

Każda mapa/CSV/raport ma podać `measurement_source`.

---

## 20. Roaming

Zmianę BSSID przy tym samym SSID rejestrować jako event:

```text
old_bssid
new_bssid
ssid
timestamp_utc
timestamp_elapsed_ns
rssi_before
rssi_after
last_old_sample_elapsed_ns
first_new_sample_elapsed_ns
```

Raport ma używać określenia `BSSID transition detected`.

Nie podawać dokładnego czasu przerwy w usługach na podstawie samego 1 Hz RSSI.

Późniejszy `Roaming Test Mode` może dodać częsty, lekki probe do hosta LAN i raportować `estimated service interruption` wraz z rozdzielczością pomiaru.

---

## 21. Wi-Fi RTT / FTM — opcjonalne

Przy uruchomieniu wykryć:

- `PackageManager.FEATURE_WIFI_RTT`,
- `WifiRttManager`,
- możliwość RTT dla konkretnego ScanResult/AP.

Jeżeli wspierane, później można zapisywać:

```text
bssid
distance_mm
distance_stddev_mm
rssi_dbm
attempted_measurements
successful_measurements
timestamp_elapsed_ns
```

MVP nie może zależeć od RTT.

---

## 22. Aktywne testy LAN — osobny tryb

Nie wykonywać ciągłego throughput test podczas zwykłego survey RSSI.

Tryby:

### PASSIVE_SURVEY

- connected RSSI,
- BSSID,
- kanał/pasmo,
- link speeds,
- neighbor scans,
- lokalizacja,
- roaming events.

### CONNECTIVITY_PROBE

Lekki probe do konfigurowalnego hosta LAN:

- RTT,
- jitter,
- failures/loss proxy.

Preferować kontrolowany TCP/HTTP endpoint. Jeśli używane ICMP, traktować brak odpowiedzi ostrożnie.

### THROUGHPUT_TEST

Osobny, świadomie uruchamiany test, docelowo do lokalnego `iperf3`.

Publiczny Speedtest nie jest podstawowym testem jakości WLAN, bo miesza Wi-Fi z WAN/operator-em.

---

## 23. Model danych — schema v1

Schemat musi być wersjonowany od pierwszego eksportu.

### 23.1. SurveySession

```text
id UUID
schema_version
name
mode OUTDOOR / INDOOR / MIXED
route_profile nullable
started_at_utc
ended_at_utc nullable
started_elapsed_ns
ended_elapsed_ns nullable
app_version_name
app_version_code
git_commit nullable
device_manufacturer
device_model
android_release
android_sdk
ssid_filter nullable
notes nullable
configuration_snapshot_json
status ACTIVE / COMPLETED / INTERRUPTED / EXPORT_FAILED
```

### 23.2. ConnectedWifiSample

Pola z sekcji 10.1 plus:

```text
session_id
id
location_accuracy_m_at_capture nullable
quality_flags
```

### 23.3. WifiScanSnapshot

Pola z sekcji 10.2 plus `session_id`.

### 23.4. WifiScanResult

Pola z sekcji 10.2 plus:

```text
id
session_id
quality_flags
```

### 23.5. LocationSample

Pola z sekcji 14.

### 23.6. IndoorAnchor

```text
id
session_id
timestamp_utc
timestamp_elapsed_ns
floorplan_id
x_m nullable
y_m nullable
x_normalized nullable
y_normalized nullable
label nullable
```

### 23.7. SurveyEvent

```text
id
session_id
sequence_no
timestamp_utc
timestamp_elapsed_ns
type
payload_json
```

Eventy co najmniej:

```text
SURVEY_START
SURVEY_STOP
BSSID_CHANGE
WIFI_DISCONNECTED
WIFI_RECONNECTED
LOCATION_DEGRADED
LOCATION_RECOVERED
SCAN_REQUEST_FAILED
SCAN_RESULTS_NOT_UPDATED
USER_ANCHOR
USER_NOTE
APP_RECOVERED_INTERRUPTED_SESSION
EXPORT_CREATED
```

---

## 24. Local storage, trwałość i crash recovery

Preferować Room/SQLite jako source of truth aktywnej sesji.

Zapis nie może zależeć od utrzymywania całej sesji w RAM.

Wymagania:

- każda próbka zapisana transakcyjnie/batchowo z krótkim buforem,
- kontrola wolnego miejsca,
- po crash/reboocie aplikacja wykrywa niezamkniętą sesję,
- sesja odzyskana otrzymuje status `INTERRUPTED`,
- operator może ją wyeksportować,
- nie nadpisywać wcześniejszych sesji.

---

## 25. Eksport sesji

Po STOP SURVEY utworzyć wersjonowany pakiet:

```text
survey_YYYYMMDD_HHMMSS_<short-id>.zip
```

W środku minimum:

```text
metadata.json
connected_wifi.csv
scan_snapshots.csv
scan_results.csv
locations.csv
indoor_anchors.csv
events.csv
track.geojson
schema_version.txt
checksums.sha256
```

Opcjonalnie:

```text
survey.db
notes.txt
```

Każdy CSV musi mieć jawny nagłówek i stabilne nazwy pól.

Eksport do publicznego storage telefonu wykonywać przez aktualny, wspierany mechanizm Androida, preferencyjnie MediaStore do:

```text
Download/WorkshopWiFiSurvey/
```

Nie żądać broad storage permissions tylko po to, aby uprościć eksport.

Ścieżka ma być możliwa do pobrania przez ADB po ponownym podłączeniu telefonu.

---

## 26. Windows / ADB preflight

Codex ma przygotować skrypt/CLI wykonujący:

```text
1. adb devices
2. identyfikacja modelu/API
3. kontrola zainstalowanej aplikacji
4. build APK
5. instalacja/upgrade
6. nadanie możliwych runtime permissions
7. uruchomienie aplikacji
8. preflight status
9. diagnostyka logcat tylko w razie potrzeby
```

Pierwsza autoryzacja USB i chronione zgody mogą wymagać fizycznego zatwierdzenia. Nie omijać zabezpieczeń Androida.

Przed zapisaniem logcat do pliku filtrować/anonimizować dane wrażliwe, jeśli plik miałby znaleźć się w repo.

---

## 27. Pobieranie wyników przez Codex

Po ponownym podłączeniu:

1. sprawdzić bezpieczny `LOCAL_DATA_ROOT`,
2. jeśli jest wewnątrz repo — potwierdzić `git check-ignore`,
3. znaleźć najnowszy eksport na telefonie,
4. pobrać do `raw/<survey-id>/`,
5. obliczyć SHA-256 oryginalnego ZIP,
6. rozpakować kopię roboczą,
7. zweryfikować `checksums.sha256`,
8. uruchomić walidator,
9. dopiero po PASS uruchomić analizę.

Surowego ZIP i plików `raw` nigdy nie modyfikować w miejscu.

---

## 28. Walidacja sesji

Walidator ma sprawdzić co najmniej:

- poprawność schema version,
- integralność checksum,
- monotoniczność `sequence_no`,
- monotoniczność czasu elapsed w obrębie strumienia,
- parse UTC,
- zakres RSSI,
- zakres frequency/channel,
- format BSSID,
- liczbę próbek,
- czas trwania,
- duplikaty importu,
- luki w connected sampling,
- realną częstotliwość scan callbacków,
- `request_accepted` i `results_updated`,
- rozkład `result_age_at_callback_ms`,
- udział `fresh=true`,
- udział connected samples z ważnym location join,
- udział scan results z ważnym location join,
- jakość GPS,
- brak GPS/indoor coordinates,
- eventy błędów.

Walidator generuje `validation.json` i `validation.md` nawet, gdy wynik kończy się FAIL.

Analiza przestrzenna nie uruchamia się automatycznie po FAIL.

---

## 29. Generowanie map

### 29.1. Zawsze najpierw mapa punktowa

Przed heatmapą wygenerować surową mapę/scatter z punktami użytymi do analizy. Umożliwia to wykrycie złego joinu lokalizacji lub luk.

### 29.2. Outdoor

Dla zaakceptowanych punktów:

1. przeliczyć lat/lon do lokalnego układu metrycznego,
2. zachować ślad trasy,
3. filtrować wg jawnych kryteriów jakości,
4. interpolować tylko przy wystarczającej gęstości,
5. ograniczyć interpolację do bufora wokół rzeczywiście zmierzonych punktów,
6. nie extrapolować na cały obraz tylko dlatego, że algorytm potrafi.

Prosta metoda startowa: IDW. Parametry IDW zapisywać w raporcie.

### 29.3. Indoor

Heatmapa opiera się na `indoor_x/y` i anchorach. Surowy GPS nie zastępuje anchorów, jeśli jego dokładność jest niewystarczająca.

### 29.4. Rzut posesji

`docs/assets/podworko-rzut.jpg` jest podkładem wizualnym, nie georeferencjonowaną mapą.

Transformację GPS ↔ obraz kalibrować lokalnie na znanych punktach i zapisywać poza publicznym repo.

### 29.5. Źródło warstwy

Każdy plik mapy musi w nazwie/metadanych wskazywać źródło, np.:

```text
connected_coverage.png
scan_AP-W4_5ghz_visibility.png
```

Nie nazywać scan-based visibility `connected coverage`.

---

## 30. Raport sesji

Minimum:

```text
summary.md
validation.md
connected_coverage.png
route.png lub track.geojson
weak_spots.csv
roaming_events.csv
scan_freshness.csv
```

Jeżeli dane skanów mają wystarczającą gęstość:

```text
scan_visibility_24ghz.png
scan_visibility_5ghz.png
scan_visibility_6ghz.png   # gdy są dane
```

Raport ma zawierać:

- session id,
- data/czas,
- model urządzenia,
- wersja aplikacji/schema,
- czas trwania,
- source dla każdej mapy,
- liczba connected samples,
- liczba scan requests/callbacks/results,
- rzeczywista częstotliwość próbkowania,
- udział świeżych scan results,
- użyte BSSID/AP,
- udział próbek >= -67 dBm,
- weak spots,
- roaming events,
- jakość lokalizacji,
- liczba punktów odrzuconych i dokładne powody,
- ostrzeżenia o niewystarczających danych.

Nie commitować raportu z rzeczywistymi danymi do publicznego repo.

---

## 31. Porównanie survey

Każda sesja jest niezmiennym rekordem.

Przykład:

```text
Survey 001 — baseline
Survey 002 — AP-W1 TX High -> Medium
Survey 003 — AP-W3 channel change
```

Porównanie ma pokazać:

- RSSI w porównywalnych przestrzennie komórkach,
- udział obszaru/próbek nad progiem,
- weak spots przed/po,
- roaming przed/po,
- pogorszenia w innych obszarach,
- wskaźnik porównywalności trasy/gęstości.

Nie porównywać bez ostrzeżenia sesji o różnych `measurement_source`.

---

## 32. Mapowanie BSSID → fizyczny AP

Nie hardkodować w aplikacji.

Publiczny przykład:

```json
{
  "02:00:00:00:00:01": {
    "name": "AP-EXAMPLE-1",
    "zone": "EXAMPLE",
    "model": "EXAMPLE"
  }
}
```

Rzeczywisty plik:

```text
config/ap-map.local.json
```

ma być ignorowany przez Git.

---

## 33. UI aplikacji — MVP

### Preflight

Duży status warunków z sekcji 8.

### New Survey

Pola:

- nazwa,
- OUTDOOR / INDOOR / MIXED,
- `route_profile`,
- opcjonalny filtr SSID,
- notatka,
- floorplan/strefa indoor,
- target connected interval,
- target scan request interval.

### Live Survey

Pokazywać:

```text
SSID
BSSID/AP label jeśli dostępny lokalnie
Band
Channel
RSSI dBm
Location accuracy
Location age
Last real scan age
Connected samples
Fresh scan results
Session duration
```

Przyciski:

```text
MARK POSITION
ADD NOTE
STOP SURVEY
```

Kolor jest dodatkiem; dBm i wartości jakości zawsze widoczne liczbowo.

### Session Complete

Pokazać:

- czas,
- liczby rekordów,
- udział ważnych lokalizacji,
- liczbę roaming events,
- ostrzeżenia,
- status eksportu,
- nazwę pliku eksportu.

---

## 34. Foreground service i zachowanie ekranu

Aktywna sesja używa foreground service z czytelną notyfikacją, np.:

```text
Workshop WiFi Survey
Survey active — 12:34 — 742 connected samples
```

Na pierwszym etapie utrzymywać aplikację na pierwszym planie i ekran aktywny podczas właściwego survey.

Dopiero osobny test na Flip6 może zatwierdzić:

- screen off,
- złożenie telefonu,
- przejście aplikacji do background,

bez degradacji connected sampling/location/scan callbacków.

Wynik takiego testu dokumentujemy przed zmianą metodologii.

---

## 35. Etapy implementacji

### Stage 0 — safety/bootstrap

Przed collector-em:

- utworzyć branch,
- utworzyć strukturę projektu,
- dodać `.gitignore` dla realnych danych,
- dodać synthetic dataset,
- dodać schema version,
- potwierdzić `git check-ignore` dla planowanej ścieżki ADB pull.

### Stage 1 — Android collector MVP

- permissions,
- Preflight,
- Start/Stop,
- location FGS,
- Room,
- 1 Hz connected link,
- 1 Hz location,
- deterministyczne location binding,
- BSSID events,
- live UI,
- eksport ZIP/CSV/GeoJSON,
- crash recovery.

### Stage 2 — neighbor scans

- scan requests,
- callback status,
- `ScanResult.timestamp`,
- fresh/stale,
- location binding wg seen time,
- 2.4/5/6 GHz,
- RTT capability discovery.

### Stage 3 — desktop pipeline

- bezpieczny ADB pull,
- checksums,
- validator,
- scatter/track,
- connected coverage,
- scan visibility,
- weak spots,
- summary.

### Stage 4 — indoor

- floorplan,
- anchors,
- local x/y,
- interpolacja między anchorami,
- indoor heatmap.

### Stage 5 — active tests

- connectivity probe,
- roaming test mode,
- iperf3 jako osobny test.

### Stage 6 — compare/UniFi

- porównania,
- mapy różnicowe,
- lokalne mapowanie BSSID → AP,
- rekomendacje.

Nie zaczynać Stage 6 przed zweryfikowaniem collectora.

---

## 36. Testy automatyczne

### Android

Minimum:

- frequency → band/channel,
- unknown channel width/value handling,
- RSSI thresholds,
- Room migration,
- serialization/export,
- ZIP checksum,
- BSSID change event,
- location age calculation,
- connected sample ↔ location join,
- scan result timestamp ↔ location join,
- rejected join przy zbyt dużym delta,
- stale scan detection,
- interrupted session recovery.

### Desktop

Minimum:

- poprawny ZIP,
- uszkodzony ZIP,
- błędna checksum,
- nieznana schema version,
- monotonic timestamps,
- BSSID/frequency validation,
- synthetic connected map,
- synthetic scan visibility map,
- brak GPS,
- brak scan_results,
- brak valid location joins,
- rozdzielenie `CONNECTED_LINK` i `SCAN_RESULT`,
- reproducible output na synthetic dataset.

Testy mają działać bez rzeczywistych danych warsztatu.

---

## 37. Zachowanie błędowe

- brak Wi-Fi → event + widoczne ostrzeżenie,
- SSID/BSSID zredagowane przez system → Preflight error lub quality flag,
- utrata lokalizacji → event; Wi-Fi nadal zapisujemy jako `UNLOCATED`,
- location age > threshold → nie używać do mapy,
- accuracy > threshold → zachować raw, wykluczyć wg reguł analizy,
- `startScan()` false → event, nie tworzyć fałszywego fresh snapshot,
- callback `resultsUpdated=false` → oznaczyć jako not updated,
- stary `ScanResult.timestamp` → `fresh=false`,
- brak miejsca → ostrzec przed utratą danych i bezpiecznie zakończyć, jeśli konieczne,
- crash → odzyskać jako `INTERRUPTED`,
- export failure → DB pozostaje zachowana i można ponowić eksport.

---

## 38. Dane surowe są nadrzędne

Przykład zachowania raw:

```text
frequency_mhz = 5220
rssi_dbm = -63
bssid = ...
location_accuracy_m = 4.8
location_age_ms = 310
measurement_source = CONNECTED_LINK
```

Dopiero analiza wylicza:

```text
band = 5GHz
channel = 44
quality = GOOD
accepted_for_heatmap = true
```

Zmiana progów/algorytmu nie może wymagać powtórzenia survey.

---

## 39. Wersjonowanie i reproducibility

Każdy eksport zawiera:

- schema version,
- app version,
- git commit builda, jeśli dostępny,
- configuration snapshot,
- target intervals,
- model telefonu,
- Android/API,
- rozpoczęcie/zakończenie sesji.

Każdy raport zapisuje:

- analysis tool version/commit,
- parametry filtrów,
- location thresholds,
- IDW/interpolation parameters,
- measurement source,
- SHA-256 wejściowego eksportu.

---

## 40. Kryteria akceptacji MVP

MVP jest zakończony dopiero po pełnym fizycznym scenariuszu na Galaxy Z Flip6:

1. Build APK PASS.
2. Unit tests PASS.
3. Instalacja ADB PASS.
4. Preflight PASS.
5. START SURVEY uruchamia FGS i Room session.
6. USB zostaje odłączone.
7. Minimum 5 minut pomiaru bez komputera.
8. Connected Wi-Fi rejestrowane około 1 Hz bez dużych nieuzasadnionych luk.
9. Location rejestrowane około 1 Hz, gdy system dostarcza fix.
10. Każdy connected sample ma poprawny monotonic timestamp i jawny status location binding.
11. STOP SURVEY nie traci danych.
12. Powstaje wersjonowany ZIP z checksumami.
13. ADB pull trafia do ścieżki ignorowanej przez Git.
14. Walidator PASS lub generuje precyzyjny FAIL z przyczyną.
15. Powstaje scatter/track z RSSI tylko z poprawnie zlokalizowanych punktów.
16. Surowe dane pozostają niezmienione.
17. Rzeczywiste dane nie pojawiają się w `git status` jako pliki możliwe do przypadkowego commitu.
18. Wynik testu sprzętowego zostaje opisany w repo bez publikowania prywatnego datasetu.

Neighbor scan / heatmapy scan-based są wymaganiem Stage 2/3, nie blokują ukończenia minimalnego Stage 1.

---

## 41. Kryteria jakości Stage 2

Stage 2 jest zaliczony po fizycznym teście, w którym:

- zapisano scan requests i callback status,
- zapisano `ScanResult.timestamp`,
- wykazano co najmniej jeden przykład `fresh=true`,
- wykazano poprawne oznaczenie stale/not-updated,
- scan result został przypisany do lokalizacji z czasu `last seen`, nie wyłącznie callback time,
- validator raportuje rzeczywistą częstotliwość skanów,
- mapa scan visibility używa wyłącznie zaakceptowanych świeżych punktów,
- żadna warstwa nie miesza `CONNECTED_LINK` i `SCAN_RESULT` bez jawnego źródła.

---

## 42. Czego MVP nie robi

Nie budować na pierwszym etapie:

- spectrum analyzer,
- monitor mode / packet capture,
- SLAM,
- rozbudowanego ML do pozycjonowania,
- ciągłego speedtestu,
- cloud backendu,
- automatycznej zmiany ustawień UniFi,
- przechowywania prawdziwych survey w Git,
- fałszywego SNR/noise/channel utilization.

---

## 43. Ograniczenia telefonu jako miernika

Flip6 jest dobrym klientem referencyjnym do praktycznego survey, ale wynik reprezentuje zachowanie konkretnego urządzenia.

Nie jest to skalibrowany laboratoryjny miernik RF ani analizator widma.

Raport używa określeń typu:

```text
RSSI reported by Android / Galaxy Z Flip6
```

Jest to właściwe dla naszego głównego celu: sprawdzić zachowanie rzeczywistego klienta mobilnego w sieci UniFi podczas przemieszczania się po warsztacie i placu.

---

## 44. Referencje techniczne

Przed implementacją szczegółów zawsze sprawdzić aktualną dokumentację Android Developers:

- Wi-Fi permissions: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- Wi-Fi scanning: https://developer.android.com/develop/connectivity/wifi/wifi-scan
- ScanResult API: https://developer.android.com/reference/android/net/wifi/ScanResult
- Location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- Retrieve current/fused location: https://developer.android.com/develop/sensors-and-location/location/retrieve-current
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Wi-Fi RTT: https://developer.android.com/develop/connectivity/wifi/wifi-rtt

Dokumentacja platformy ma pierwszeństwo przed starymi przykładami z blogów/StackOverflow.

---

## 45. Instrukcja startowa dla Codexa

Po otrzymaniu tego dokumentu Codex ma wykonać następującą sekwencję bez ponownego pytania o opisane tu założenia:

1. przeczytać cały `docs/workshop-wifi-survey-android-plan.md`,
2. przeczytać `docs/unifi-network-bom-v2.md`,
3. sprawdzić aktualny `main`, ale go nie modyfikować,
4. utworzyć/checkout osobnej gałęzi `feat/workshop-wifi-survey-mvp`,
5. rozpocząć od **Stage 0 — safety/bootstrap**,
6. przed jakimkolwiek realnym `adb pull` zweryfikować `.gitignore` i `git check-ignore`,
7. stworzyć synthetic test data,
8. zaimplementować Stage 1,
9. uruchomić wszystkie możliwe testy software,
10. dopiero gdy użytkownik podłączy Flip6, wykonać fizyczny preflight/test,
11. zapisać rezultat testów bez publikowania prywatnych danych,
12. nie merge'ować do `main`,
13. po Stage 1 przejść do Stage 2 dopiero po spełnieniu kryteriów akceptacji.

W przypadku niejasności implementacyjnej preferować rozwiązanie, które zachowuje:

- surowe dane,
- jawne źródło pomiaru,
- czas monotoniczny,
- jakość lokalizacji,
- możliwość ponownej analizy,
- kompatybilność schematu,
- prywatność rzeczywistych danych.

---

## 46. Następna akcja

Dokument jest gotowy do przekazania Codexowi.

Następne zadanie projektu:

> **zbudować Stage 0 + Stage 1 Workshop WiFi Survey na osobnej gałęzi i doprowadzić do pierwszego fizycznego testu na Samsung Galaxy Z Flip6, bez zmian i merge do `main`.**
