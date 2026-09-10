# INEA / Fiberhost — WAN dla UCG-Fiber

**Data weryfikacji:** 2026-09-10  
**Status:** zweryfikowane testem bezpośrednim na ONT

## Cel

Potwierdzenie, czy router operatora ZTE ZXHN H298Q można usunąć z toru Internetu i podłączyć planowany **UniFi Cloud Gateway Fiber (UCG-Fiber)** bezpośrednio do ONT operatora.

## Aktualne zakończenie łącza

- infrastruktura/operator dostępu: **Fiberhost / INEA**, AS13110,
- ONT: **Nokia G-010G-T** (branding Fiberhost),
- obecny router operatora: **ZTE ZXHN H298Q**,
- światłowód pozostaje zakończony w ONT Nokia; nie planujemy wkładania włókna bezpośrednio do UCG-Fiber.

## Test wykonany 2026-09-10

Kabel Ethernet wychodzący z ONT Nokia został odłączony od portu WAN routera ZTE i podłączony bezpośrednio do komputera z kartą Ethernet skonfigurowaną jako klient DHCP.

Wynik:

- komputer otrzymał adres IPv4 automatycznie przez **DHCP/IPoE**,
- adres WAN znajdował się w zakresie **100.64.0.0/10 (CGNAT)**,
- brama domyślna i serwery DNS zostały przekazane automatycznie przez DHCP,
- dostęp do Internetu działał poprawnie bez routera ZTE,
- publiczny adres widziany z Internetu należał do **INEA / AS13110**,
- zmiana urządzenia/MAC z ZTE na komputer została zaakceptowana — nie zaobserwowano stałego bindingu MAC do routera operatora,
- do zestawienia połączenia nie było potrzebne PPPoE ani ręczne tagowanie VLAN.

Dokładnych adresów IP i adresów MAC nie zapisujemy w publicznym repozytorium.

## Docelowa topologia

```text
Światłowód Fiberhost / INEA
           │
           ▼
    Nokia G-010G-T
       GPON ONT
           │
           │ Ethernet
           ▼
       UCG-Fiber
   router / firewall
           │
           ▼
 główny switch CORE W5
           │
     cała sieć UniFi
```

## Konfiguracja WAN UCG-Fiber

Konfiguracja startowa:

- **IPv4:** DHCP,
- **PPPoE:** wyłączone,
- **WAN VLAN ID:** brak / untagged,
- **DNS:** automatic,
- **MAC Clone:** wyłączony,
- **MTU:** automatic.

Jeżeli po pierwszym podłączeniu UCG-Fiber nie otrzyma dzierżawy DHCP od razu, należy najpierw odczekać na wygaśnięcie poprzedniej dzierżawy lub wykonać kontrolowany restart ONT. Klonowanie MAC traktować wyłącznie jako plan awaryjny, nie jako konfigurację domyślną.

## Decyzja architektoniczna

Po instalacji UCG-Fiber:

- **Nokia G-010G-T pozostaje** jako ONT GPON,
- **ZTE ZXHN H298Q zostaje wycofany z roli routera**,
- UCG-Fiber przejmuje routing, NAT, firewall, VLAN-y, polityki dostępu, QoS oraz zarządzanie siecią UniFi,
- nie jest potrzebny dodatkowy moduł GPON SFP/SFP+.

## CGNAT

Łącze otrzymuje prywatny adres operatorski z zakresu **100.64.0.0/10**, a publiczny IPv4 jest realizowany po stronie INEA przez CGNAT.

Skutki:

- zwykły dostęp do Internetu działa normalnie,
- połączenia wychodzące i Tailscale działają bez potrzeby przekierowania portów,
- klasyczne przekierowanie portów z publicznego IPv4 do urządzeń LAN nie będzie dostępne bez usługi publicznego IPv4 po stronie operatora.

Jeżeli w przyszłości będzie wymagane wystawianie usług bezpośrednio do Internetu, należy sprawdzić w INEA możliwość uzyskania publicznego lub stałego IPv4.
