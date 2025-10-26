package com.example.blescanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED

object GattClient {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connecting = false
    private lateinit var app: Application

    fun get(appCtx: Application): GattClient {
        this.app = appCtx
        return this
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PERMISSION_GRANTED
        else true
    }

    fun connectIfNeeded(mac: String) {
        if (gatt != null || connecting) return
        if (!hasBtConnectPermission()) {
            Log.w("GATT", "BLUETOOTH_CONNECT not granted – skipping connectGatt()")
            return
        }

        connecting = true
        val dev = try {
            app.getSystemService(BluetoothManager::class.java)
                ?.adapter?.getRemoteDevice(mac)
        } catch (se: SecurityException) {
            Log.w("GATT", "getRemoteDevice() SecurityException: ${se.message}")
            connecting = false
            null
        } ?: run { connecting = false; return }

        Handler(Looper.getMainLooper()).post {
            try {
                gatt = dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE).also {
                    Log.d("GATT", "Connecting to $mac…")
                }
            } catch (se: SecurityException) {
                Log.w("GATT", "connectGatt() SecurityException: ${se.message}")
                connecting = false
                gatt = null
            } catch (t: Throwable) {
                Log.w("GATT", "connectGatt() failed: ${t.message}")
                connecting = false
                gatt = null
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission") // mamy runtime-check + try/catch
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("GATT", "state=$newState status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) { cleanup(); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try {
                        if (hasBtConnectPermission()) gatt.discoverServices()
                        else { Log.w("GATT", "No BLUETOOTH_CONNECT for discoverServices()"); cleanup() }
                    } catch (se: SecurityException) {
                        Log.w("GATT", "discoverServices() SecurityException: ${se.message}")
                        cleanup()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> cleanup()
            }
        }

        @SuppressLint("MissingPermission") // lint nie śledzi, że sprawdzamy uprawnienie
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { cleanup(); return }
            if (!hasBtConnectPermission()) { Log.w("GATT", "No BLUETOOTH_CONNECT for services access"); cleanup(); return }
            // samo odczytanie listy usług też jest oznaczone @RequiresPermission
            val count = try { gatt.services.size } catch (_: SecurityException) { -1 }
            Log.i("GATT", "Services discovered: $count")
            // ... tu Twoje read/write/notify – również pod guardem hasBtConnectPermission()
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        connecting = false
        try {
            if (hasBtConnectPermission()) this@GattClient.gatt?.close()
            else Log.d("GATT", "Skip close(): missing BLUETOOTH_CONNECT")
        } catch (_: Throwable) { }
        this@GattClient.gatt = null
    }
}
