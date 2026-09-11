# Kontrakt eksportu schema 1

ZIP zawiera dokładnie:

```text
metadata.json
connected_wifi.csv
locations.csv
events.csv
track.geojson
scan_snapshots.csv
scan_results.csv
indoor_anchors.csv
schema_version.txt
checksums.sha256
```

Starsze pomiary mają nagłówki pustych skanów, `collector_stage=1` i
`measurement_source=CONNECTED_LINK`. Nowe pomiary z włączonym skanowaniem mają
`collector_stage=2`, wypełnione `scan_snapshots.csv` / `scan_results.csv` oraz ich liczniki.
`measurement_source` nadal opisuje strumień połączenia; każdy wynik skanu ma
osobne `source=SCAN_RESULT`. `indoor_anchors.csv` pozostaje pusty i jest wymieniony
w `unimplemented_streams`. Walidator obsługuje oba etapy i odrzuca niezgodność konfiguracji.
Opcjonalne `survey.db` nie jest eksportowane.

UTF-8 bez BOM. CSV: przecinek, CRLF, wartości zawsze w cudzysłowach,
wewnętrzne cudzysłowy podwojone; null to pusta komórka. Wieloliniowe notatki
i znaki Unicode pozostają surowe. Przy otwieraniu CSV w arkuszu importuj tekst
jako tekst, bez interpretacji formuł. Nie modyfikujemy surowych notatek pod Excel.

Pełne nazwy i typy pól definiują `Models.kt`, `ScanModels.kt` oraz historyczne schematy Room
w `android/app/schemas/`. Nazwy kolumn CSV wynikają z serializatorów tych modeli;
zmiany niekompatybilne wymagają nowej wersji eksportu. Unknown `wifi_standard`
i width to otwarte liczby całkowite, a nie zamknięte enumy.

`metadata.json` zawiera pola sesji, snapshot konfiguracji, wersję aplikacji,
commit kompilacji (z `-dirty`, gdy checkout zawierał zmiany), model/API i liczniki.
Pola techniczne ostatniego eksportu z bazy nie są kopiowane do metadata.

Każdy z trzech strumieni Wi-Fi/location/event ma własne `sequence_no` od 1 oraz UTC i elapsed ns.
Czas lokalizacji pochodzi z `Location.time` i `Location.elapsedRealtimeNanos`.
Czas Wi-Fi oznacza moment odczytu API, nie datę planowanego ticka ani gwarantowany
moment nowego pomiaru radiowego firmware.

Join po elapsed przyjmuje tylko fix już zapisany w chwili odczytu Wi-Fi,
o czasie <= capture i wieku <= `max_location_age_ms` (domyślnie 2500 ms).
Przekroczenie nawet o 1 ns odrzuca join. `location_id_at_capture` jest wtedy null,
`UNLOCATED` jest obowiązkowe, a wiek i accuracy starego fix pozostają diagnostycznie
zachowane. Poor accuracy i mock są flagami; raw location i powiązanie czasowe
pozostają w bazie. Nie są automatyczną zgodą na użycie przestrzenne.

`channel_width=null` i `CHANNEL_WIDTH_UNAVAILABLE`: publiczny `WifiInfo` nie
dostarcza wiarygodnej szerokości kanału połączenia. Nie uzupełniamy jej ze skanów.
Nieznana frequency pozostaje surowa z `FREQUENCY_UNKNOWN`. Specjalne 2484/5935 MHz
oraz zakres 4.9 GHz mają jawne mapowanie kanału.

Przerwana sesja po nowym procesie/reboocie kończy się czasem ostatniego utrwalonego
odczytu zegara collectora (Wi-Fi/event/callback skanu lub START). Czas fix od providera, który
może być opóźniony albo błędnie przyszły, nie przesuwa czasu STOP/recovery.
Event recovery ma ten sam elapsed i UTC, a rzeczywisty czas odzyskania
w payload `recovered_at_utc`, z `end_is_lower_bound=true`. Dzięki temu nie łączymy
dwóch różnych domen zegara po reboocie. Późniejszy `EXPORT_CREATED` również ma
czas domeny sesji i faktyczny `published_at_utc` w payload. Ten event jest dopisany
do DB po udanej publikacji, więc pojawi się dopiero w kolejnym eksporcie.

Eksport czyta strony do 500 rekordów, bez ładowania całej sesji do RAM. Każdy plik
oprócz samego manifestu ma SHA-256 w `checksums.sha256` (`hex`, dwie spacje, nazwa).
Zapis przez MediaStore używa `IS_PENDING`; niepełny eksport nie jest publikowany.
Przy obsłużonym błędzie rekord MediaStore jest usuwany, baza pozostaje zachowana.
Nagłe zabicie procesu podczas eksportu może pozostawić niewidoczny pending item
do sprzątnięcia przez Android; ponowienie tworzy nowy plik.

Room DB version 3 jest niezależne od export schema 1. Migracja 1→2 dodaje wyłącznie
`export_status`, `export_uri`, `export_name`, `export_error`. Status pomiaru
COMPLETED/INTERRUPTED pozostaje zachowany także przy `export_status=EXPORT_FAILED`.
Nie stosujemy destrukcyjnej migracji.
Migracja 2→3 dodaje tabele `scan_snapshots` i `scan_results`, indeksy oraz klucze
obce do sesji, snapshotu i lokalizacji. Nie zmienia żadnego wcześniejszego odczytu.

Wynik skanu zachowuje `ScanResult.timestamp` w mikrosekundach od startu urządzenia.
`fresh=true` wymaga `results_updated=true`, czasu nie wcześniejszego od START,
wieku 0–5000 ms i nowego czasu obserwacji dla pary BSSID/frequency. Cache, duplikaty
i błędnie przyszłe czasy pozostają w raw z flagami, bez joinów przestrzennych.
Join wybiera najbliższy już zapisany fix w oknie ±`max_location_age_ms` względem
czasu obserwacji AP. Fix nie może być późniejszy niż callback. Delta jest ze znakiem:
`(location_elapsed_ns - platform_seen_elapsed_us * 1000) / 1e6`.
Poor accuracy/mock pozostają zapisane, ale są wykluczone z mapy.

Żądania skanu są osobnymi eventami `SCAN_REQUEST` (czas żądania, przyjęcie,
ustawienie throttlingu i interwał). Snapshot opisuje otrzymany broadcast; jego
`request_elapsed_ns` i `request_accepted` są null. Android może dostarczyć wynik
skanu wywołanego przez inną aplikację/system, dlatego nie przypisujemy mu
nieudowodnionego autorstwa. `result_count` odpowiada liczbie surowych wyników snapshotu.
Nowy `DEVICE_STATE` zapisuje interaktywność ekranu i OPEN/CLOSED/PARTIAL/UNAVAILABLE
z publicznego czujnika kąta zawiasu. UNAVAILABLE nie oznacza telefonu otwartego.

`track.geojson` to FeatureCollection surowych punktów fix (longitude, latitude),
z accuracy, mock i timestampami. Nie interpoluje i nie łączy linią luk GPS.
To nie jest heatmapa ani automatyczna selekcja danych do analizy przestrzennej.
