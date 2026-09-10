# Pierwszy fizyczny test Samsung Galaxy Z Flip6

Status pełnej próby >=5 minut: **NOT RUN — wymaga operatora i fizycznego telefonu**.
Procedura zaktualizowana do interfejsu 0.2. Krótkie nagrania oraz podgląd wyników
na fizycznym Flip6 zostały sprawdzone; nie zastępują pełnego testu terenowego.
Build/unit tests/Robolectric nie zastępują tych czynności.

## 1. Przygotuj checkout i APK

Pracuj na `feat/workshop-wifi-survey-mvp`, bez zmian w `main`.
Z katalogu repozytorium uruchom:

```powershell
git branch --show-current
python workshop-wifi-survey/analysis/check_privacy.py
git check-ignore -v workshop-wifi-survey/data/WorkshopWiFiSurvey/raw/probe/survey_probe.zip
Push-Location workshop-wifi-survey/android
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain
Pop-Location
python -m unittest discover -s workshop-wifi-survey/analysis -p 'test_*.py' -v
```

Ustaw wcześniej JAVA_HOME/ANDROID_HOME zgodnie z README. Żaden prawdziwy eksport
nie może trafić do niezweryfikowanej ścieżki.

## 2. Podłącz i zainstaluj

1. Rozłóż Flip6, odblokuj ekran, włącz Developer Options i USB debugging.
2. Podłącz USB i potwierdź klucz komputera na telefonie. Nie obchodź autoryzacji.
3. Podłącz tylko jedno urządzenie ADB. Wykonaj:

```powershell
python workshop-wifi-survey/analysis/device.py preflight
python workshop-wifi-survey/analysis/device.py install
```

Model docelowy powinien zaczynać się od `SM-F741`. Wymagane API >=34.
Skrypt zachowuje dane preflight prywatnie; nie kopiuj seriala ani pełnych logów do PR.
Przy błędzie podpisu APK **nie odinstalowuj aplikacji**, dopóki nie pobierzesz sesji.

## 3. Zgody i test blokad

1. Gdy aplikacja poprosi, wybierz „Nadaj dostęp”. Zezwól na **dokładną**
   lokalizację podczas używania aplikacji, urządzenia Wi-Fi w pobliżu i powiadomienia.
2. Włącz Wi-Fi i Location Services. Połącz telefon z badaną własną siecią.
3. Sprawdź nazwę sieci i komunikat „Gotowe do pomiaru”. Pełna lista kontroli
   znajduje się pod „Informacje o telefonie”.
4. Zweryfikuj, że odmowa precise location, wyłączenie Wi-Fi/Location Services
   lub powiadomień blokuje START z opisem przyczyny. Przywróć wymagane warunki.
5. Nie zmieniaj scan throttling dla Stage 1 — skany nie są uruchamiane.

## 4. Pierwsza sesja: co najmniej 5 minut

1. Wyjdź na zewnątrz. Wybierz „Na zewnątrz”; opcjonalnie wpisz nazwę pomiaru.
   Interwał 1000 ms i limit wieku GPS 2500 ms są ustawione automatycznie.
2. W porównywanych sesjach zachowaj takie same ustawienia telefonu. Warunki,
   stan danych komórkowych i Bluetooth możesz opisać notatką już podczas pomiaru.
   Obecność VPN aplikacja zapisuje automatycznie w konfiguracji.
3. Poczekaj na fix: wiek <=2500 ms i accuracy <=20 m (preferowane <=10 m), brak mock.
4. Naciśnij „Rozpocznij pomiar”. Sprawdź powiadomienie, czas i rosnącą liczbę odczytów.
5. Odłącz USB. Wyłącz Phone Link, mirroring, hotspot i inne aktywne transfery.
6. Idź co najmniej 5 minut trasą W1–W5, przejściami i placem, 0.7–1.0 m/s.
   Trzymaj rozłożony telefon, ekranem do siebie, na wysokości ok. 1.2–1.5 m.
   Ekran ma pozostać aktywny. Przejście do wnętrz może oznaczać poor accuracy/UNLOCATED.
7. Użyj „Dodaj notatkę o miejscu” przy postoju lub istotnej zmianie warunków. Zachowaj obserwację
   momentów przejścia BSSID; sam licznik nie mierzy przerwy w ruchu sieciowym.
8. Naciśnij „Zakończ i zobacz wynik” i poczekaj na zapis. Sprawdź wykres sygnału,
   trasę GPS oraz liczby odczytów pod „Szczegóły pomiaru”. ZIP jest w folderze
   Pobrane / WorkshopWiFiSurvey. Możesz go otworzyć przez „Moje pomiary” →
   „Otwórz pomiar z pliku ZIP” i porównać wynik z podglądem z bazy.

## 5. Pobierz i oceń

Podłącz telefon ponownie i wykonaj:

```powershell
python workshop-wifi-survey/analysis/device.py pull
git status --short
```

Oryginalny ZIP i jego suma są w ignorowanym `data/WorkshopWiFiSurvey/raw/`;
raporty w `data/WorkshopWiFiSurvey/reports/`. Sprawdź:

- zgodny schema 1 i wszystkie checksumy;
- liczbę próbek zbliżoną do 300 przy 5 minutach i interwale 1 s;
- `observed_connected_hz`, `sampling_gap_count`, `max_sampling_gap_ms`;
- dla diagnostyki luki >2.5 × target interval są oznaczane ostrzeżeniem;
- sequence i elapsed bez błędów, UTC parsowalne;
- poprawne joiny <=2500 ms; stare/brakujące fix zachowane jako UNLOCATED;
- raw GPS z accuracy, także słabe punkty wewnątrz;
- BSSID_CHANGE z old/new, SSID, RSSI i granicznymi elapsed, bez deklaracji czasu outage.

PASS formatu z ostrzeżeniami jakości wymaga oceny operatora. Nie oznaczaj fizycznej
częstotliwości lokalizacji jako PASS, jeżeli system nie dostarczał fix około 1 Hz.
Interfejs 0.2 pokazuje wykres RSSI i punktową trasę z zaakceptowanych lokalizacji;
nie interpoluje heatmap. Surowe punkty track.geojson pozostają bez zmian.
Nie rozpoczynaj Stage 2 bez zakończenia oceny collectora.

## 6. Osobne próby awarii

Po zabezpieczeniu pierwszego ZIP wykonaj krótsze sesje testowe:

1. Wyłącz Wi-Fi na kilkanaście sekund, włącz ponownie: oczekuj disconnect/reconnect
   i ciągłości rekordów z odpowiednimi flagami.
2. Wyłącz Location Services po START, potem przywróć: Wi-Fi nadal przyrasta,
   po 2500 ms brak join, event LOCATION_DEGRADED, po fix LOCATION_RECOVERED.
3. Podczas kolejnej sesji wymuś zatrzymanie aplikacji z ustawień Androida.
   Uruchom ponownie: sesja INTERRUPTED, wcześniejsze dane dostępne, eksport działa.
4. Osobno sprawdź reboot telefonu w trakcie sesji: stara sesja INTERRUPTED,
   nowa sesja ma nowe ID, bez mieszania elapsed dwóch bootów.
5. Ponów eksport zakończonej sesji, także po restarcie aplikacji. Stary ZIP i DB
   pozostają; nowa nazwa/status są widoczne. Nie testuj braku miejsca przez
   zapełnianie całego telefonu z produkcyjnymi danymi.

Screen off, złożenie telefonu, działanie w tle i polityka baterii Samsunga wymagają
oddzielnej oceny; **nie są zatwierdzoną metodologią Stage 1**.

## 7. Publiczny zapis wyniku

Do repo dodaj tylko model/API, commit aplikacji, czas trwania, agregaty liczności,
wynik testów i opis ograniczeń. Bez realnych SSID/BSSID/GPS, nazw klientów,
seriala, tras, zrzutów ekranu i oryginalnych logów. Realne artefakty zostają w data/.
Przed commitem wykonaj `git diff --cached` i `check_privacy.py`. Nie merge'uj do main.
