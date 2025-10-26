package com.example.blescanner

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.logging.LogManager
import org.altbeacon.beacon.logging.Loggers

class MyApp : Application() {

    private lateinit var beaconManager: BeaconManager
    private var lastConnectAttemptAt: Long = 0L
    private val CONNECT_COOLDOWN_MS: Long = 15_000L

    // WakeLock trzymający CPU aktywne przy zgaszonym ekranie
    private var wakeLock: PowerManager.WakeLock? = null

    // Watchdog – restart skanu, gdy długo brak wyników
    private val watchdog = Handler(Looper.getMainLooper())
    private var lastResultMs: Long = 0L
    private var watchdogRunning = false

    // Rangujemy cały czas ten region (wszystkie iBeacon)
    private val targetUuid: Identifier? = null
    private val regionAll = Region("ibeacons-all", targetUuid, null, null)

    override fun onCreate() {
        super.onCreate()
        // Wywołaj z MainActivity po runtime-permissions:
        // (application as MyApp).startBeaconScanningIfNeeded()
    }

    /** Wywołaj po uzyskaniu zgód. Idempotentne. */
    fun startBeaconScanningIfNeeded() {
        LogManager.setLogger(Loggers.verboseLogger())
        LogManager.setVerboseLoggingEnabled(true)

        if (this::beaconManager.isInitialized) {
            Log.d("BLE", "Beacon scanning already initialized")
            return
        }

        beaconManager = BeaconManager.getInstanceForApplication(this)

        // --- TYLKO iBeacon ---
        beaconManager.beaconParsers.apply {
            clear()
            add(BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"))
        }

        // Stabilność w tle z FGS (bez ScanJobów / bez intent scanningu)
        beaconManager.setEnableScheduledScanJobs(false)
        try {
            // KLUCZOWE: wyłącz strategię intent scanningu – wymuś bezpośrednie skanowanie
            val m = BeaconManager::class.java
                .getMethod("setIntentScanningStrategyEnabled", Boolean::class.javaPrimitiveType)
            m.invoke(beaconManager, false)
            Log.d("BLE", "IntentScanningStrategyEnabled = false")
        } catch (_: Throwable) {
            // starsze wersje libki mogą nie mieć tej metody
        }
        enableLongScanForcingIfAvailable()

        // Nie przełączamy na background – wymuszamy foreground skan nawet z zgaszonym ekranem
        beaconManager.setBackgroundMode(false)

        // Okresy skanowania (lekkie ~200ms oddechu pomaga na niektórych OEM-ach)
        beaconManager.foregroundScanPeriod = 1100L
        beaconManager.foregroundBetweenScanPeriod = 200L
        beaconManager.backgroundScanPeriod = 1100L
        beaconManager.backgroundBetweenScanPeriod = 200L

        // Uruchom Foreground Service (PRZED startem rangowania)
        enableForegroundService()

        // Stałe rangowanie (BEZ monitoringu/RegionBootstrap)
        try {
            beaconManager.startRangingBeacons(regionAll)
        } catch (t: Throwable) {
            Log.w("BLE", "startRangingBeacons failed: ${t.message}")
        }

        // Logowanie i heartbeat wyników
        lastResultMs = System.currentTimeMillis()
        beaconManager.removeAllRangeNotifiers()
        beaconManager.addRangeNotifier { beacons, region ->
            lastResultMs = System.currentTimeMillis()
            beacons.forEach { b ->
                if (b.id1.toString().equals("e2c56db5-dffb-48d2-b060-d0f5a71096e0", ignoreCase = true) &&
                    b.id2.toInt() == 1 && b.id3.toInt() == 1) {
                    // Spróbuj połączyć, jeśli urządzenie jest (najprawdopodobniej)  connectable
                    val mac = b.bluetoothAddress    // może być null, albo losowy (RPA)
                    if (!mac.isNullOrBlank()) {
                        GattClient.get(this).connectIfNeeded(mac)
                    }
                }
            }
        }


        // Utrzymaj CPU aktywne i poproś o wyłączenie Doze dla aplikacji
        acquireWakeLock()
        requestDisableBatteryOptimizationsIfNeeded()

        // Start watchdog
        startWatchdog()

        Log.d("BLE", "Beacon scanning started (FGS, no ScanJobs/Intent, wakelock, continuous ranging + watchdog)")
    }

    private fun enableLongScanForcingIfAvailable() {
        try {
            val m = BeaconManager::class.java
                .getMethod("setLongScanForcingEnabled", Boolean::class.javaPrimitiveType)
            m.invoke(beaconManager, true)
            Log.d("BLE", "LongScanForcingEnabled = true")
        } catch (t: Throwable) {
            Log.w("BLE", "LongScanForcing not available in this AltBeacon version")
        }
    }

    private fun enableForegroundService() {
        val channelId = "beacon-scan"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "Beacon scanning", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Skanowanie iBeaconów…")
            .setContentText("Działa w tle")
            .setOngoing(true)
            .build()

        BeaconManager.getInstanceForApplication(this)
            .enableForegroundServiceScanning(notif, 1001)
    }

    // --- WakeLock: utrzymanie CPU aktywnego przy zgaszonym ekranie ---
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BLEScanner::WakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("BLE", "Partial wakelock acquired")
        } catch (t: Throwable) {
            Log.w("BLE", "Failed to acquire wakelock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null } catch (_: Throwable) {}
    }

    // --- Prośba o wyłączenie optymalizacji baterii (Doze) dla aplikacji ---
    private fun requestDisableBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d("BLE", "Requested ignore battery optimizations")
            } else {
                Log.d("BLE", "Battery optimizations already ignored")
            }
        } catch (t: Throwable) {
            Log.w("BLE", "Cannot request ignore battery optimizations: ${t.message}")
        }
    }

    // --- Watchdog: miękki restart skanu przy braku wyników ---
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        watchdog.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val silenceMs = now - lastResultMs
                // jeśli przez ~25–30 s nie było wyników, zrestartuj skan
                if (silenceMs > 25_000L) {
                    Log.w("BLE", "Watchdog: no results for ${silenceMs}ms, restarting ranging")
                    try {
                        beaconManager.stopRangingBeacons(regionAll)
                    } catch (t: Throwable) {
                        Log.w("BLE", "stopRanging failed: ${t.message}")
                    }
                    watchdog.postDelayed({
                        try {
                            beaconManager.startRangingBeacons(regionAll)
                            lastResultMs = System.currentTimeMillis()
                            Log.i("BLE", "Ranging restarted by watchdog")
                        } catch (t: Throwable) {
                            Log.e("BLE", "startRanging failed after restart: ${t.message}")
                        }
                    }, 600L)
                }
                watchdog.postDelayed(this, 30_000L)
            }
        })
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        watchdog.removeCallbacksAndMessages(null)
    }

    override fun onTerminate() {
        stopWatchdog()
        releaseWakeLock()
        try { beaconManager.stopRangingBeacons(regionAll) } catch (_: Throwable) {}
        super.onTerminate()
    }
}


