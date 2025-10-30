# Android BLE iBeacon Scanner + GATT Connector

Aplikacja Android skanująca **iBeacon** w tle oraz automatycznie łącząca się z modułem BLE (GATT) po wykryciu docelowego beacona. Działa nawet przy wyłączonym ekranie, z obejściami Doze i agresywnych optymalizacji OEM.

## Funkcje

-  Ciągłe skanowanie iBeacon (AltBeacon Library)
-  Działa w tle / z wygaszonym ekranem
-  Foreground Service zapobiegający ubijaniu aplikacji
-  PARTIAL_WAKE_LOCK — utrzymanie CPU aktywnego
-  Automatyczne łączenie BLE GATT po wykryciu beacona
-  Obsługa Android 12+ (BLUETOOTH_SCAN / CONNECT)
-  Watchdog restartujący skan gdy system go zatrzyma
-  Obsługa wyjątków, runtime-permissions, zabezpieczeń

## Architektura

| Plik | Rola |
|------|------|
| MainActivity.kt | Pobieranie runtime permissions i start skanowania |
| MyApp.kt | Konfiguracja AltBeacon, foreground service, wakelock, watchdog, logika skanowania |
| GattClient.kt | Bezpieczna logika połączenia BLE GATT |

## Wymagane uprawnienia

### Android 12+ (API 31+)
- BLUETOOTH_SCAN
- BLUETOOTH_CONNECT
- POST_NOTIFICATIONS

### Android 6–11
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION

Dodatkowo:
- prośba o Ignore Battery Optimizations (Doze)
- foreground service z własnym kanałem powiadomień

## Konfiguracja skanera Beacon

```
beaconManager.beaconParsers.apply {
    add(BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"))
}
beaconManager.setEnableScheduledScanJobs(false)
beaconManager.setBackgroundMode(false)
beaconManager.enableForegroundServiceScanning(notification, 1001)
```

Ustawienia skanowania:
| Parametr | Wartość |
|---|---|
foregroundScanPeriod | 1100 ms
foregroundBetweenScanPeriod | 200 ms
watchdog | restart co ~25–30s bez wyników

## GATT – połączenie i obsługa

### Mechanizmy

| Mechanizm | Opis |
|---|---|
volatile BluetoothGatt | zapobiega race conditions |
Sprawdzenie uprawnień | zgodność z Android 12+ |
Cooldown 15s | CONNECT_COOLDOWN_MS |
Obsługa SecurityException | runtime safety |

### Przepływ

1. wykrycie target iBeacon
2. pobranie MAC (jeśli nie RPA)
3. connectIfNeeded(mac)
4. discoverServices()
5. read/write/notify

## Mechanizmy niezawodności

| Mechanizm | Efekt |
|---|---|
Foreground Service | nie pozwala zabić procesu |
PARTIAL_WAKE_LOCK | CPU aktywne przy wygaszonym ekranie |
Disable Doze request | zapobiega uśpieniu |
Watchdog | naprawa braku wyników |
Cooldown połączeń | brak spamowania BLE |

## Struktura

```
app/
 ├── MainActivity.kt        
 ├── MyApp.kt                
 └── GattClient.kt           
```
