# Prosty interfejs i przeglądanie wyników — 0.2.0

Zmiana na życzenie operatora z 10/11 września 2026: aplikacja ma być zrozumiała
bez znajomości Wi-Fi i pokazywać wykresy z zapisanych pomiarów oraz pozycje GPS.
Ta decyzja zastępuje rozbudowany formularz interfejsu z sekcji 33 specyfikacji v1.1.
Zakres nie obejmuje skanów sąsiednich AP ani pełnego Stage 2/3.

## Obsługa

1. **Nowy pomiar:** opcjonalna nazwa, wybór „Na zewnątrz / W budynku / Tu i tu”,
   czytelny stan gotowości i przycisk „Rozpocznij pomiar”. Brakujące uprawnienia
   lub ustawienia telefonu mają opis i odpowiedni przycisk naprawy.
2. **Pomiar trwa:** czas, słowna ocena sygnału z wartością dBm, status GPS,
   licznik odczytów, notatka o miejscu i „Zakończ i zobacz wynik”.
3. **Moje pomiary:** zapisane sesje z czasem i datą; po wybraniu dostępne
   podsumowanie, wykres sygnału oraz trasa GPS. Suwak umożliwia wybór chwili
   także bez dotykania wykresu. Kolory mają legendę i opisy tekstowe.
4. **Otwórz pomiar z pliku ZIP:** systemowy wybór pliku z folderu Pobrane /
   WorkshopWiFiSurvey, sprawdzenie integralności i ten sam widok wyniku.
   Otwarcie pliku nie dopisuje sesji do bazy, więc nie tworzy duplikatów.

Nazwa pustego formularza jest tworzona automatycznie z daty i godziny.
Nowe pomiary mają stałe ustawienia: odczyt Wi-Fi 1000 ms, GPS 1000 ms,
maksymalny wiek pozycji 2500 ms. Parametry techniczne, filtr SSID i profil trasy
zniknęły z formularza. Ich pola w bazie/eksporcie pozostają kompatybilne;
nowe sesje zapisują null dla niewprowadzanych pól oraz jawny brak obserwacji
stanu Bluetooth/danych komórkowych. Warunki można zapisać w notatce.
Stare konfiguracje i wszystkie surowe dane są zachowane.

## Zasady wykresów

- Oś czasu używa czasu monotonicznego względem początku sesji. UTC służy do daty.
- Dobry sygnał oznacza >= −67 dBm; słabszy −68…−75 dBm, bardzo słaby < −75 dBm.
  Szczegółowa ocena słowna zachowuje pięć progów ze specyfikacji.
- Procent dobrych odczytów ma w mianowniku wszystkie próbki, także rozłączenia
  i brak wartości RSSI. Nie jest procentem pokrycia powierzchni ani testem internetu.
- Invalid RSSI i rozłączenia tworzą przerwy, nigdy sztuczne zero dBm.
  Dodatkowa przerwa powstaje po odstępie > max(2,5 s, 2,5 × interwał sesji).
- Trasa korzysta wyłącznie z utrwalonego `location_id_at_capture` tej samej sesji.
  Nie odtwarza brakujących powiązań na podstawie UTC ani najbliższego punktu.
- Na trasie są tylko skończone współrzędne w prawidłowych zakresach, z nieujemną
  znaną accuracy <= limit sesji (domyślnie 20 m), bez mock, stale, future,
  UNLOCATED, poor/unknown accuracy i SSID_FILTER_MISMATCH. Wiek jest dodatkowo
  sprawdzany względem konfiguracji zapisanej w danej sesji.
- Lokalny rzut metryczny ma północ u góry, wspólną skalę obu osi, podziałkę
  i obsługę przejścia przez południk 180°. Punkty są kolorowane RSSI; zaznaczenie
  pokazuje orientacyjny okrąg accuracy. Brakujące pozycje przerywają linię.
- Widok działa offline, bez podkładu mapowego, interpolacji i ekstrapolacji.
  GPS w budynku nie zastępuje planu ani anchorów. Nie wysyłamy współrzędnych
  do dostawcy map, aplikacja nadal nie wymaga uprawnienia INTERNET.
- Do 2000 odczytów wykres rysuje wszystkie punkty. Dłuższe zapisy mają do około
  3500 punktów podglądu: początek/koniec, minimum/maksimum RSSI, brak odczytu
  oraz skrajne pozycje w kolejnych grupach czasu. Zachowane identyfikatory odcinków
  zapobiegają łączeniu przez luki. Statystyki zawsze obejmują cały zapis.

## Odczyt plików i kompatybilność

Room pozostaje w wersji 2, schemat eksportu w wersji 1, aplikacja 0.2.0/versionCode 2.
Nie zmieniono kontraktu collectora, wiązania próbek, eksportera ani migracji.
Zapytanie do widoku wyników jest stronicowane po 500 wierszy i nie zapisuje do bazy.
Podgląd Wi-Fi/GPS przed startem jest wstrzymany podczas oglądania historii/wyników.

Czytnik ZIP przyjmuje tylko znane nazwy plików, odrzuca duplikaty i ścieżki spoza
archiwum. Sprawdza kompletność, dokładne pokrycie checksum, SHA-256 wszystkich
plików, wersję, źródło, nagłówki CSV, identyfikatory sesji, kolejność próbek/zdarzeń,
zakres czasu sesji, UTC, liczniki i prawidłowość przechowanych powiązań GPS.
Parser CSV obsługuje przecinki, podwójne cudzysłowy, nowe linie i Unicode.
Wynik błędnego pliku nie trafia na wykres ani trasę. Wyjątki parsera nie ujawniają
surowych wierszy w komunikatach użytkownika.

Limity podglądu: 100 MB danych po rozpakowaniu, 100 000 rekordów w strumieniu,
ograniczony rozmiar wiersza/pola i metadanych. Bardzo długie sesje nadal można
eksportować i analizować na komputerze. Lokalne trasy rozciągające się na >50 km
nie są rysowane. Plik jest czytany w prywatnym katalogu cache, usuwanym po próbie;
surowy ZIP operatora i dane bazy pozostają bez zmian. Śmierć procesu może pozostawić
prywatny cache do sprzątnięcia przez system. Nie jest to pełny walidator desktopowy.

## Weryfikacja

- Android/JVM/Robolectric: **32 testy PASS**, w tym 15 nowych testów analizy,
  odczytu ZIP i zgodności podglądu z bazą; testy istniejącego collectora zachowane.
- Desktop Python: **22 testy PASS**. Kontrola prywatności: **16 prób PASS**.
- `assembleDebug` i `lintDebug`: PASS; 0 błędów i 16 dotychczasowych ostrzeżeń,
  bez wyłączania reguł Lint ani dodawania nowych zależności.
- Flip6 SM-F741B, Android 16/API 36: aktualizacja przez `adb install -r`,
  ekran startowy, historia, wykresy zapisanych sesji, kolorowa trasa GPS,
  otwarcie oryginalnego ZIP przez systemowy wybór pliku — PASS. Wybór
  najsłabszego odczytu, przewinięcie do wykresu i odpowiadającego mu punktu GPS — PASS.
- Układ ekranu startowego przy font scale 1,0 i 1,3: PASS; przy większej czcionce
  wybór miejsca zawija się, formularz przewija, a przycisk startu pozostaje widoczny.
  Przywrócono pierwotne ustawienie czcionki. Brak awarii aplikacji w kontroli runtime.
- Kontrola zachowania danych po aktualizacji: 5 sesji, 230 odczytów Wi-Fi,
  175 lokalizacji. Porównano wszystkie 405 rekordów z oryginalnymi eksportami,
  z uwzględnieniem typu Float32/Float64. Integralność bazy i zgodność danych: PASS.
- Zrzuty, pozycje, nazwy sieci, kopia bazy i oryginalne ZIP pozostają wyłącznie
  w sprawdzonym przez Git ignorowanym `data/WorkshopWiFiSurvey/`.

Do próby operatora pozostają nowy przebieg START/notatka/STOP w terenie,
ciągła sesja >=5 minut bez USB, roaming, celowe rozłączenie/GPS loss, crash/reboot,
screen off i złożenie. Testy widoku wyników nie zatwierdzają tych scenariuszy.
Nie uruchamiano nowego pomiaru w czasie tej zmiany.

API interfejsu oparto na dokumentacji Android:
[Compose Canvas](https://developer.android.com/develop/ui/compose/graphics/draw/overview),
[gesty](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press),
[systemowy wybór pliku](https://developer.android.com/training/data-storage/shared/documents-files).
