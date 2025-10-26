package com.example.blescanner

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.blescanner.ui.theme.BLEscannerTheme
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            (application as MyApp).startBeaconScanningIfNeeded()
            Toast.makeText(this, "✅ Zgody OK – start skanowania", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "❌ Brak wymaganych uprawnień", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!hasAllPermissions()) {
            requestAllPermissions()
        } else {
            (application as MyApp).startBeaconScanningIfNeeded()
        }

        setContent {
            BLEscannerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Text(
                        text = "Skan iBeaconów działa w tle (FGS).",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        val perms = buildPermissionList()
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        requestPerms.launch(buildPermissionList().toTypedArray())
    }

    private fun buildPermissionList(): MutableList<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT in 29..30) {
            // Tylko jeśli naprawdę potrzebujesz skanów w tle na A10–A11
            // (jeśli nie – możesz to pominąć)
            // perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }
}
