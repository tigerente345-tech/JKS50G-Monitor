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
    private val rx = ArrayList<Byte>()

    private val SERVICE_FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val CHAR_FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        live = findViewById(R.id.live)
        log = findViewById(R.id.log)
        connect = findViewById(R.id.connect)
        connect.setOnClickListener { ensurePermissionsAndScan() }
    }

    private fun ensurePermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.BLUETOOTH_CONNECT
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 7)
        } else scan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scan()
    }

    private fun scan() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        status.text = "Suche nach OXIMETER …"
        append("BLE-Scan gestartet")
        val scanner = adapter.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    if (Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) null
                    else result.device.name
                } catch (_: SecurityException) { null }

                if (name?.contains("OXIMETER", ignoreCase = true) == true) {
                    scanner.stopScan(this)
                    append("Gefunden: $name / ${result.device.address}")
                    connectGatt(result.device)
                }
            }
        }
        scanner.startScan(callback)
        main.postDelayed({
            try { scanner.stopScan(callback) } catch (_: Exception) {}
            if (gatt == null) status.text = "Kein OXIMETER gefunden – erneut versuchen"
        }, 15000)
    }

    private fun connectGatt(device: BluetoothDevice) {
        status.text = "Verbinde …"
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui { status.text = "Verbunden – Dienste werden gelesen"; append("GATT verbunden") }
                g.requestMtu(247)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ui { status.text = "Getrennt"; append("GATT getrennt") }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, statusCode: Int) {
            ui { append("MTU: $mtu") }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val c = g.getService(SERVICE_FFE0)?.getCharacteristic(CHAR_FFE1)
            if (c == null) {
                ui {
                    status.text = "FFE0/FFE1 nicht gefunden"
                    append("Bekannte FFE0/FFE1-Schnittstelle fehlt")
                }
                return
            }
            g.setCharacteristicNotification(c, true)
            c.getDescriptor(CCCD)?.let { d ->
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
                }
            }
            ui {
                status.text = "Verbunden – Notifications aktiv"
                append("FFE0 / FFE1 aktiv")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consume(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            consume(value)
        }
    }

    @Synchronized
    private fun consume(chunk: ByteArray) {
        ui { append("RX ${hex(chunk)}") }
        chunk.forEach { rx.add(it) }
        while (true) {
            while (rx.size >= 2 && !((rx[0].toInt() and 0xff) == 0xAA && (rx[1].toInt() and 0xff) == 0xBB)) {
                rx.removeAt(0)
            }
            if (rx.size < 5) return
            val payloadLen = (rx[2].toInt() and 0xff) or ((rx[3].toInt() and 0xff) shl 8)
            val total = 2 + 2 + payloadLen + 1
            if (payloadLen > 8192) { rx.removeAt(0); continue }
            if (rx.size < total) return

            val frame = ByteArray(total) { rx[it] }
            repeat(total) { rx.removeAt(0) }
            parseFrame(frame)
        }
    }

    private fun parseFrame(frame: ByteArray) {
        val expected = frame.last().toInt() and 0xff
        var sum = 0
        for (i in 2 until frame.lastIndex) sum = (sum + (frame[i].toInt() and 0xff)) and 0xff
        val payload = frame.copyOfRange(4, frame.lastIndex)

        ui {
            append("FRAME ${hex(frame)}")
            append("Checksum ${if (sum == expected) "OK" else "FEHLER"} ($sum/$expected)")
        }

        if (payload.size >= 3 &&
            (payload[0].toInt() and 0xff) == 0x40 &&
            (payload[1].toInt() and 0xff) == 0x03 &&
            (payload[2].toInt() and 0xff) == 0x02) {

            // Die folgenden Bytes werden bewusst noch NICHT fest als SpO2/Puls beschriftet.
            // In den bisherigen Mitschnitten waren u.a. 0x64 (=100) und 0x5A (=90) zu sehen.
            val body = payload.drop(3).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            ui { live.text = "Live-Frame 40 03 02\n$body" }
        }

        if (payload.size >= 2 &&
            (payload[0].toInt() and 0xff) == 0x03 &&
            (payload[1].toInt() and 0xff) == 0x01) {
            ui { append("History-Antwort 03 01 erkannt") }
        }
    }

    private fun append(s: String) = ui {
        val old = log.text.toString()
        log.text = (old + (if (old.isEmpty()) "" else "\n") + s).takeLast(16000)
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
    private fun ui(block: () -> Unit) = runOnUiThread(block)

    override fun onDestroy() {
        super.onDestroy()
        try { gatt?.close() } catch (_: Exception) {}
    }
}
