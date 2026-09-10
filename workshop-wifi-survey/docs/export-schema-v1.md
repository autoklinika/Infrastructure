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

Trzy strumienie przyszłych etapów zawierają tylko nagłówki; metadata wskazuje
`collector_stage=1`, `measurement_source=CONNECTED_LINK` i `unimplemented_streams`.
Walidator Stage 1 odrzuca pakiet z rekordami scan/anchor zamiast udawać ich analizę.
Opcjonalne `survey.db` nie jest eksportowane.

UTF-8 bez BOM. CSV: przecinek, CRLF, wartości zawsze w cudzysłowach,
wewnętrzne cudzysłowy podwojone; null to pusta komórka. Wieloliniowe notatki
i znaki Unicode pozostają surowe. Przy otwieraniu CSV w arkuszu importuj tekst
jako tekst, bez interpretacji formuł. Nie modyfikujemy surowych notatek pod Excel.

Pełne nazwy i typy pól definiują `Models.kt` oraz historyczne schematy Room
w `android/app/schemas/`. Nazwy kolumn CSV wynikają z serializatorów tych modeli;
zmiany niekompatybilne wymagają nowej wersji eksportu. Unknown `wifi_standard`
i width to otwarte liczby całkowite, a nie zamknięte enumy.

`metadata.json` zawiera pola sesji, snapshot konfiguracji, wersję aplikacji,
commit kompilacji (z `-dirty`, gdy checkout zawierał zmiany), model/API i liczniki.
Pola techniczne ostatniego eksportu z bazy nie są kopiowane do metadata.

Każdy z trzech aktywnych strumieni ma własne `sequence_no` od 1 oraz UTC i elapsed ns.
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
odczytu zegara collectora (Wi-Fi/event lub START). Czas fix od providera, który
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

Room DB version 2 jest niezależne od export schema 1. Migracja 1→2 dodaje wyłącznie
`export_status`, `export_uri`, `export_name`, `export_error`. Status pomiaru
COMPLETED/INTERRUPTED pozostaje zachowany także przy `export_status=EXPORT_FAILED`.
Nie stosujemy destrukcyjnej migracji.

`track.geojson` to FeatureCollection surowych punktów fix (longitude, latitude),
z accuracy, mock i timestampami. Nie interpoluje i nie łączy linią luk GPS.
To nie jest heatmapa ani automatyczna selekcja danych do analizy przestrzennej.
