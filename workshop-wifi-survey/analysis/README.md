# Stage 1 — walidacja i prywatne pobranie

Wymagany Python 3.11+; wyłącznie standard library. Uruchamiaj z katalogu repo.

```powershell
python workshop-wifi-survey/analysis/check_privacy.py
python workshop-wifi-survey/analysis/device.py preflight
python workshop-wifi-survey/analysis/device.py install
# Po zakończeniu survey na telefonie:
python workshop-wifi-survey/analysis/device.py pull
```

Skrypt wybiera jedyne autoryzowane urządzenie. Przy wielu urządzeniach lub braku
autoryzacji kończy się czytelnym błędem bez seriala. Instalacja aktualizuje APK
przez `adb install -r`; zgody są nadawane przez operatora w UI aplikacji.
Wersja model/API/fingerprint trafia wyłącznie do ignorowanego `data/`.

Pull wybiera ostatni eksport według nazwy UTC, pobiera do nowego katalogu `raw`,
zapisuje SHA-256 oryginału, sprawdza rejestr importów i uruchamia walidację.
Nie nadpisuje wcześniej pobranego oryginału. Niepełne `download.partial` wymaga
lokalnego sprawdzenia przed ponowieniem. Późniejszy eksport tej samej sesji
zostaje odnotowany jako duplikat sesji w ostrzeżeniu.

Można walidować ZIP ponownie, podając jego lokalną ścieżkę:

```powershell
python workshop-wifi-survey/analysis/validate_survey.py '<ścieżka do ZIP>'
```

Raporty `validation.json` i `validation.md` powstają także przy FAIL w prywatnym
katalogu raportów. `--output` pozwala wybrać inną ścieżkę, ale tylko wewnątrz
ignorowanego `data/WorkshopWiFiSurvey/`. Walidator nie rozpakowuje ZIP na dysk.
Limit rozpakowanego pakietu: 512 MiB; większy plik zwraca jawny FAIL.

PASS dotyczy integralności i formatu. Ostrzeżenia raportują m.in. brak GPS,
poor accuracy, przerwaną sesję i luki próbkowania. Nie jest to akceptacja sprzętowa
ani zgoda na heatmapę. Mapy i pipeline Stage 3 nie są zaimplementowane.

Testy:

```powershell
python -m unittest discover -s workshop-wifi-survey/analysis -p 'test_*.py' -v
```
