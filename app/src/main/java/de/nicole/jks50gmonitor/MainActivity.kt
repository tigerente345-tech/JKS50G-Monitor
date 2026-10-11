package de.nicole.jks50gmonitor

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var log: TextView
    private lateinit var connect: Button

    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var ffe1: BluetoothGattCharacteristic? = null

    private val rx = ArrayList<Byte>()

    private val historyRx = ArrayList<Byte>()
    private var historyActive = false
    private var historyExpected = 0

    private val SERVICE_FFE0 =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    private val CHAR_FFE1 =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private val CCCD =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /*
     * Im HCI-Mitschnitt bestätigter History-Abruf.
     */
    private val HISTORY_COMMAND = byteArrayOf(
        0xAA.toByte(),
        0xBB.toByte(),
        0x09,
        0x00,
        0x03,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x0D
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        live = findViewById(R.id.live)
        log = findViewById(R.id.log)
        connect = findViewById(R.id.connect)

        connect.setOnClickListener {
            ensurePermissionsAndScan()
        }
    }

    private fun ensurePermissionsAndScan() {

        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 31) {

            if (
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }

            if (
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }

        } else if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (needed.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                7
            )

        } else {
            scan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 7 &&
            grantResults.isNotEmpty() &&
            grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
        ) {
            scan()
        }
    }

    private fun resetBuffers() {
        rx.clear()
        historyRx.clear()
        historyActive = false
        historyExpected = 0
    }

    private fun scan() {

        val manager =
            getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        val adapter = manager.adapter ?: return

        status.text = "Suche nach OXIMETER …"
        live.text = "Noch keine Daten"
        log.text = ""

        resetBuffers()
        ffe1 = null

        try {
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null

        append("BLE-Scan gestartet")

        val scanner = adapter.bluetoothLeScanner ?: run {
            append("Bluetooth-Scanner nicht verfügbar")
            return
        }

        val callback = object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                val name = try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }

                if (
                    name?.contains(
                        "