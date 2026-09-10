# Mapa pomiarów i mały ekran Flipa — 0.3

## Obsługa

W „Moich pomiarach” wybierz zapis lub otwórz eksport ZIP. Wynik otwiera się od
mapy OpenStreetMap; nie wymaga Google Cloud, konta użytkownika ani klucza API.
Wybierz sieć, następnie „Wszystkie AP” lub pojedynczy AP. Powiększanie, przesuwanie,
przycisk „Cały pomiar” i widok na cały ekran pomagają obejrzeć dany teren.
Dotknij pola, aby porównać AP i zobaczyć liczbę odczytów, zakres sygnału i GPS.
Wykres czasu pozostaje pod sąsiednią zakładką. Wybrana chwila zaznacza się na mapie
wraz z kołem raportowanej dokładności, jeśli miała poprawną pozycję.
Opcja „Wszystkie sieci” pozwala obejrzeć także AP nadające pod różnymi SSID.

Od wersji 0.3.1 przycisk „Trasa GPS” od razu otwiera duże okno mapy na cały ekran.
„Pokaż to miejsce na trasie” pod wykresem otwiera to samo okno z zaznaczoną chwilą;
zamknięcie przyciskiem „Wróć” albo systemowym gestem wraca do wykresu.
Na mapie od razu widać wybór sieci/AP i prostą legendę. „Jak czytać mapę” rozwija
objaśnienie progów, jakości danych i wybór źródła. Przegląd bez dokładnych pozycji
pokazuje czytelny komunikat zamiast pustego podkładu. Obliczenia i surowy zapis
pozostają takie same jak w 0.3.0.

Nowe sesje zapisują połączenie telefonu i skany widocznych AP. Starsze sesje
pokazują wyłącznie połączone AP. Wykrycie AP nie oznacza połączenia, dostępu do
internetu ani spełnienia wymagań przepustowości. Oddzielne przyciski źródeł
uniemożliwiają mieszanie RSSI skanu z RSSI połączenia.

## Interpretacja mapy

- AP oznacza radio identyfikowane przez BSSID. Dwa radia jednego urządzenia mogą
  mieć dwa numery AP; ten sam BSSID ma ten sam numer w obu źródłach danego pomiaru.
  Numery nie są trwałymi nazwami urządzeń pomiędzy sesjami.
- Sieć domyślna pochodzi z najczęściej zapisywanego SSID połączenia. Pozostałe
  wykryte sieci są dostępne w wyborze. Nie ustalamy położenia samego nadajnika.
- Pole ma bok co najmniej 10 m i co najmniej dwukrotność najgorszej zaakceptowanej
  dokładności GPS, zaokrągloną w górę do 5 m. To orientacyjny obszar agregacji,
  nie gwarancja zasięgu ani granica błędu GPS. Domyślny limit accuracy wynosi 20 m.
- Kolor opiera się na konserwatywnym 10. percentylu: uporządkowane RSSI,
  indeks `floor((n-1)*0.1)`. Co najmniej 90% zapisanych odczytów ma taki lub lepszy
  sygnał. Zielony ≥−67 dBm; bursztynowy od −75 do poniżej −67; czerwony <−75.
  Próg jest orientacyjny, zależny od zastosowania; nie jest certyfikacją sieci.
- Wypełnienie wymaga przynajmniej trzech niezależnych czasów odczytu tego AP
  w polu. 1–2 odczyty to puste kółko. Nie interpolujemy, nie kreślimy okręgów
  propagacji wokół najsilniejszej próbki i nie wypełniamy luk między punktami.
- „Wszystkie AP” wybiera najsilniejszy percentyl spośród AP z ≥3 odczytami w polu.
  Jeśli takich brak, pokazuje jedynie wstępny punkt najlepszego dostępnego AP.
  Popup porównuje wszystkie zmierzone AP; filtr pokazuje osobno wybrane radio.
- Puste pole oznacza brak danych spełniających kryteria, nie brak Wi-Fi. AP
  niewykryty w skanie nie jest automatycznie pomiarem −100 dBm. Nie obliczamy
  procentu pokrycia nieznanej powierzchni na podstawie samych odwiedzonych miejsc.
- Statystyki używają wszystkich zaakceptowanych odczytów przed skracaniem wykresu.
  Postój daje więcej odczytów w jednym polu; percentyl opisuje te odczyty,
  nie równomierny rozkład przestrzenny. GPS wewnątrz budynku nie zastępuje planu piętra.

Import zachowuje wcześniejsze limity: 100 MiB po rozpakowaniu i 100 000 rekordów
na strumień. Mapa ma limit 5000 zajętych pól; przekroczenie daje czytelny komunikat,
bez obcinania surowego zapisu. Eksport na komputer pozostaje dostępny.

## Skany i zamknięty telefon

FGS lokalizacji startuje z widocznego ekranu aplikacji. Zapis Wi-Fi i żądania GPS
pozostają około 1 Hz. Podczas aktywnej sesji ograniczony czasowo PARTIAL_WAKE_LOCK
utrzymuje CPU po zgaszeniu ekranu (10 minut, odnowienie co 5 minut); zamknięcie,
błąd i zniszczenie usługi zwalniają blokadę. Nie ma automatycznego restartu pomiaru
z widżetu ani żądania uprawnienia do lokalizacji w tle.

Android ogranicza skany Wi-Fi: standardowo maksymalnie 4 żądania w ciągu 2 minut
na pierwszym planie, a w tle może obowiązywać limit jednego skanu na 30 minut.
FGS/wake lock nie jest obietnicą obejścia limitów radia. Aplikacja żąda skanu co
30 s; jeśli operator wcześniej wyłączył throttling w opcjach programistycznych,
co 5 s. Ustawienie i przyjęcie żądań są audytowane. Odbieramy też skany inicjowane
przez system. UI pokazuje rzeczywisty wiek ostatniego świeżego wyniku.

Surowe nieaktualne wyniki nadal są zapisywane. Do mapy skan trafia wyłącznie
z poprawnym, świeżym czasem obserwacji i ważnym powiązaniem GPS; szczegóły
w [kontrakcie eksportu](export-schema-v1.md). Zmiana położenia telefonu po
starym skanie nie przesuwa tego skanu na nowe miejsce.

## Panel Flipa

Ustawienia telefonu → Ekran zewnętrzny → Widżety → „Pomiar Wi-Fi”. Nazwy/układ
menu mogą się różnić między wersjami One UI. Widżet ma standardową deklarację
Android i metadane Samsunga `display="sub_screen"`; może też działać na pulpicie.

Panel pokazuje czas, sygnał, jakość GPS, wiek skanów i czas aktualizacji. Przyciski
„Oznacz miejsce” i „Zakończ” obsługuje aktywna usługa. Pierwszy zapisuje notatkę
z czasem zdarzenia; drugi kończy pomiar i eksportuje ZIP. Start nowej sesji wymaga
otwarcia telefonu i przejścia kontroli gotowości. Powiadomienie oferuje te same
dwie czynności. Widżet odświeża się co pięć odczytów; stały czas aktualizacji
pozwala zauważyć, że status przestał się odświeżać.

Obsługa zamknięcia jest oddzielana od zwykłego wygaszenia: event DEVICE_STATE
zapisuje ekran i dostępny czujnik zawiasu. Jeśli firmware nie udostępnia czujnika,
wartość jest UNAVAILABLE, a fizyczne zamknięcie musi potwierdzić operator.

## Podkład mapy i prywatność

Leaflet 1.9.4, JS/CSS i licencja BSD są dołączone w assets. SHA-256 oryginalnych plików:

- JS: `db49d009c841f5ca34a888c96511ae936fd9f5533e90d8b2c4d57596f4e5641a`
- CSS: `a7837102824184820dfa198d1ebcd109ff6d0ff9a2672a074b9a1b4d147d04c6`

WebView ładuje kod lokalnie, bez mostka JavaScript, dostępu do plików telefonu,
zewnętrznych skryptów, analityki ani uploadu pomiarów. SSID i inne teksty danych
są traktowane jako tekst. Żądania sieciowe warstwy mapy ograniczono do kafli HTTPS
aktualnego widoku `tile.openstreetmap.org`; serwer może z nich ustalić oglądany
obszar. Nadpisany User-Agent identyfikuje aplikację i repozytorium. Zwykły cache
WebView respektuje nagłówki HTTP; brak pobierania całych obszarów w tle.
Atrybucja OSM jest zawsze widoczna. Przy niedostępnym podkładzie pomiary nadal
rysują się lokalnie, z komunikatem o braku mapy. Nie gwarantujemy map offline.

Źródła implementacji:

- [Leaflet — stabilne wydanie i sumy integralności](https://leafletjs.com/download.html)
- [Zasady używania kafli OpenStreetMap](https://operations.osmfoundation.org/policies/tiles/)
- [Android — skanowanie Wi-Fi i limity](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Samsung — widżety Flex Window](https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html)
- [Android — utrzymywanie urządzenia aktywnego](https://developer.android.com/develop/background-work/background-tasks/awake)

## Weryfikacja wydania

W 0.3.1 na fizycznym Flip6 sprawdzono otwarcie pełnego ekranu jednym dotknięciem
„Trasa GPS”, wypełnienie okna przez mapę, wybór AP, rozwijanie objaśnień oraz powrót
przyciskiem „Wróć”. Sprawdzono też otwarcie zaznaczonej chwili z wykresu i powrót
systemowym Wstecz z zachowaniem wybranej chwili. Zrzuty pozostały prywatne.

54 testy JVM/Robolectric i 29 testów Python przeszły. Kontrole obejmują zachowanie
danych podczas migracji 1→3 i 2→3, recovery po ostatnim skanie, round-trip ZIP,
odrzucenie fałszywej świeżości/duplikatów, join w czasie obserwacji, rozdzielenie
źródeł/AP, percentyl, brak wypełniania luk i wielkość pól wynikającą z GPS.
Widżet testowano natywnym rendererem Androida w 352×339 dp, z font scale 1,0 i 1,3,
łącznie z wywołaniem notatki przez PendingIntent. Lint: 0 błędów, 16 dotychczasowych ostrzeżeń.

Na fizycznym Flip6 wykonano aktualizację z zachowaniem bazy, wyświetlenie podkładu
OSM i pomiarów, zapis/eksport skanów, próbę ze zgaszonym ekranem i krótką próbę
z fizycznie zamkniętym zawiasem. Raporty rzeczywistych prób i dane pozostają prywatne.
To nie jest jeszcze pełna akceptacja pomiarów terenowych: długi zapis bez USB,
roaming, porównanie orientacji telefonu i dłuższa próba zamknięcia nadal wymagają
osobnego testu. Rejestracja widżetu została potwierdzona w systemie Samsunga;
operator musi dodać go do ekranu zewnętrznego, a obsługę w tym hostcie sprawdzić fizycznie.
