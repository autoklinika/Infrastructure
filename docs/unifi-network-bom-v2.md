# UniFi — lista zakupów sieci v2

## Założenia

- Główny RACK i serwerownia: **W5**.
- Światłowód operatora kończy się w W5.
- W1–W3: osobny budynek z lokalnym switchem PoE.
- W4–W5: drugi budynek.
- Między budynkami istnieje Ethernet; na start używany jako uplink, docelowo możliwość przejścia na 10 Gb/s po światłowodzie.
- W2: CM5 sterownika wentylacji, HMI iiyama, Zigbee i urządzenia pomocnicze.
- Jedna zarządzana infrastruktura Wi‑Fi na całej posesji z roamingiem.
- Osobne sieci logiczne: główna, IoT i Guest.
- Guest: wyłącznie Internet, bez dostępu do NAS, Minisforum, CM5, HMI, IoT i management.
- Backbone i urządzenia serwerowe: 10G-ready.
- AP i typowe końcówki: 2.5 GbE.
- Duży zapas portów i mocy PoE.

## Główny RACK — W5

| Element | Ilość | Rola |
|---|---:|---|
| **UniFi Cloud Gateway Fiber — UCG-Fiber** | 1 | Router, firewall, kontroler UniFi |
| **UniFi Pro Max 24 PoE — USW-Pro-Max-24-PoE** | 1 | Główny switch CORE |
| **UACC-DAC-SFP10-1M** | 1 | UCG-Fiber ↔ CORE 10 Gb/s |
| Patch panel Keystone 24-port 1U | 1 | Okablowanie strukturalne |
| Organizer kabli 1U | 1–2 | Organizacja patchcordów |
| PDU rack 230 V | 1 | Dystrybucja zasilania |
| UPS rack | 1 | Do doboru po policzeniu obciążenia |
| Szafa 19" ok. 24–32U / 800 mm | 1 | Główny rack |

## Wi‑Fi

### W4
**1 × UniFi U7 In-Wall — U7-IW**

W4 wymaga dobrego Wi‑Fi i możliwości podłączania urządzeń przewodowych. U7-IW pełni rolę AP Wi‑Fi 7 i lokalnego mini-switcha 2.5 GbE.

### W5
**1 × U7 Lite**

### Pomieszczenie gospodarcze
**1 × U7 Lite**

### Dom
**1 × U7 Pro**

Na start jeden mocniejszy AP dla całego domu; w razie potrzeby możliwość dołożenia kolejnego.

### W1–W3
**1 × U7 Long-Range na start**

Testujemy pokrycie trzech pomieszczeń jednym AP. Jeśli będzie potrzeba, dokładamy drugi lub trzeci AP.

### Plac
**1 × U7 Outdoor**

Dokładne miejsce i kierunek montażu zostaną ustalone po analizie pokrycia.

## Węzeł W1–W3

| Element | Ilość | Rola |
|---|---:|---|
| **UniFi Pro Max 16 PoE — USW-Pro-Max-16-PoE** | 1 | Switch lokalny |
| Lokalna mała szafa / rack | 1 | Punkt dystrybucyjny |
| Patch panel | 1 | Zakończenie lokalnego okablowania |
| Patchcordy | wg potrzeb | Patch panel ↔ switch |

Planowane urządzenia: uplink do W5, 1–3 AP, CM5, iiyama PoE, Zigbee oraz przyszła automatyka.

## Okablowanie W4

Do W4 prowadzimy minimum **3 niezależne linie Cat6A**:

1. **W4-01 → U7 In-Wall**
2. **W4-02 → niezależne gniazdo RJ45**
3. **W4-03 → rezerwa**

Dzięki temu sieć przewodowa W4 nie zależy od działania AP i pozostaje zapas na rozbudowę.

## Sieci Wi‑Fi / VLAN

Na tych samych AP tworzymy co najmniej:

- **główne Wi‑Fi** — urządzenia zaufane,
- **IoT Wi‑Fi** — ESP32 i automatyka,
- **Guest Wi‑Fi** — klienci i goście.

Guest Wi‑Fi:

- osobne SSID,
- osobny VLAN,
- dostęp do Internetu,
- brak dostępu do NAS,
- brak dostępu do Minisforum AI,
- brak dostępu do CM5 i HMI,
- brak dostępu do IoT i management,
- możliwość izolacji klientów między sobą,
- możliwość limitu prędkości.

Nie wymaga dodatkowego AP ani kontrolera.

## Uplink między budynkami

Na początku wykorzystujemy istniejący Ethernet. Jeśli zestawi 2.5 Gb/s, używamy 2.5 Gb/s; jeśli tylko 1 Gb/s, startujemy z 1 Gb/s. Oba switche pozostają przygotowane pod późniejsze połączenie 10 Gb/s SFP+ po światłowodzie.

## Backbone

- **2.5 GbE** — AP i typowe urządzenia końcowe.
- **10 GbE-ready** — QNAP, Minisforum AI, gateway/core i przyszły backbone między budynkami.

## Aktualny BOM aktywnej infrastruktury UniFi

| Lokalizacja | Model | Ilość | Rola |
|---|---|---:|---|
| W5 rack | **UCG-Fiber** | 1 | Router / firewall / kontroler |
| W5 rack | **USW-Pro-Max-24-PoE** | 1 | Główny switch CORE |
| W5 rack | **UACC-DAC-SFP10-1M** | 1 | UCG-Fiber ↔ CORE 10G |
| W1–W3 | **USW-Pro-Max-16-PoE** | 1 | Switch lokalny |
| W4 | **U7 In-Wall** | 1 | Wi‑Fi 7 + lokalne 2.5 GbE |
| W5 | **U7 Lite** | 1 | AP |
| Pom. gospodarcze | **U7 Lite** | 1 | AP |
| Dom | **U7 Pro** | 1 | AP |
| W1–W3 | **U7 Long-Range** | 1 | AP testowy / zasięgowy |
| Plac | **U7 Outdoor** | 1 | AP zewnętrzny |

## Budżet

Aktualny poziom kosztu aktywnej infrastruktury UniFi: orientacyjnie **11 000–11 500 zł brutto**.

Osobno liczymy: rack, UPS, Cat6A, keystony, patchpanele, patchcordy, PDU, elementy 10G/SFP+, zabezpieczenia i pozostały osprzęt instalacyjny.

## Na razie nie kupujemy

- dodatkowego AP W1–W3 — dopiero po testach,
- UniFi Switch Aggregation — dopiero gdy zabraknie portów 10G,
- modułów SFP+ między budynkami — dopiero przy przejściu na światłowód 10G,
- kart 10GbE dla QNAP i Minisforum — dobierzemy osobno,
- dodatkowych AP outdoor.

## Status

Dokument stanowi aktualny punkt odniesienia do dalszego projektowania głównej szafy W5, zasilania i UPS, okablowania strukturalnego, VLAN-ów oraz rozmieszczenia AP.