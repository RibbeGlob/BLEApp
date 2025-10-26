package com.example.blescanner

import android.app.Application
import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.util.Log

object GattClient {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connecting = false
    private lateinit var app: Application

    fun get(appCtx: Application): GattClient {
        this.app = appCtx
        return this
    }

    fun connectIfNeeded(mac: String) {
        if (gatt != null || connecting) return
        connecting = true
        val dev = app.getSystemService(BluetoothManager::class.java)
            .adapter?.getRemoteDevice(mac) ?: run {
            connecting = false
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                gatt = dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE).also {
                    Log.d("GATT", "Connecting to $mac…")
                }
            } catch (t: Throwable) {
                Log.w("GATT", "connectGatt failed: ${t.message}")
                connecting = false
                gatt = null
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("GATT", "state=$newState status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cleanup(); return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("GATT", "Connected to ${gatt.device.address}")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("GATT", "Disconnected from ${gatt.device.address}")
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { cleanup(); return }
            Log.i("GATT", "Services discovered: ${gatt.services.size}")
            // Przykład operacji:
            // val svc = gatt.getService(UUID.fromString("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"))
            // val chr = svc?.getCharacteristic(UUID.fromString("yyyy..."))
            // gatt.readCharacteristic(chr)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            Log.d("GATT", "Read ${characteristic.uuid}, status=$status")
        }

        private fun cleanup() {
            connecting = false
            try { this@GattClient.gatt?.close() } catch (_: Throwable) {}
            this@GattClient.gatt = null
        }
    }
}
