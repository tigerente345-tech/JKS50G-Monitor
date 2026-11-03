package de.nicole.jks50gmonitor

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * JKS50G Monitor 0.3 – safe-by-default BLE diagnosis.
 *
 * No history read, delete, reset or finish command is sent automatically.
 * The ONLY non-CCCD device command available is a manual, confirmed
 * 04 01 01 probe observed in Health Tree traffic. Its meaning is not proven.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var dataView: TextView
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var testButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var shareButton: Button

    private val main = Handler(Looper.getMainLooper())
    private val uuidService = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val uuidCharacteristic = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val uuidCccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // In original Health Tree traffic observed; meaning still being verified.
    // Deliberately NEVER sent automatically and NEVER described as a confirmed live command.
    private val experimentalCommand = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0x09, 0x00,
        0x04, 0x01, 0x01, 0x00, 0x00, 0x00, 0x0F
    )

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var scanning = false
    private var connected = false
    private var notifyReady = false
    private val rxBuffer = ArrayList<Byte>()
    private var rxCount = 0
    private var receivedBytes = 0
    private var lastRxTime: Long? = null
    private val logText = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        dataView = findViewById(R.id.live)
        logView = findViewById(R.id.log)
        connectButton = findViewById(R.id.connect)
        testButton = findViewById(R.id.liveTest)
        disconnectButton = findViewById(R.id.disconnect)
        shareButton = findViewById(R.id.shareLog)

        connectButton.setOnClickListener { ensurePermissionsAndScan() }
        testButton.setOnClickListener { confirmExperimentalTest() }
        disconnectButton.setOnClickListener { disconnect() }
        shareButton.setOnClickListener { shareLog() }
        findViewById<Button>(R.id.clearLog).setOnClickListener {
            logText.setLength(0)
            logView.text = ""
            append("Protokoll manuell geleert")
        }
        append("JKS50G Monitor v0.3 – passiver Diagnosemodus")
        append("Beim Verbinden werden keine History-Befehle gesendet.")
        updateButtons()
    }

    private fun ensurePermissionsAndScan() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing += Manifest.permission.BLUETOOTH_CONNECT
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
        } else {
            scan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            scan()
        } else if (requestCode == 7) {
            append("Bluetooth-Berechtigungen fehlen; kein Scan möglich")
        }
    }

    private fun scan() {
        stopScan()
        disconnect()
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            append("Bluetooth nicht aktiv oder Scanner nicht verfügbar")
            return
        }
        rxBuffer.clear()
        rxCount = 0
        receivedBytes = 0
        lastRxTime = null
        scanning = true
        statusView.text = "Suche OXIMETER …"
        updateButtons()
        append("BLE-Scan gestartet")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    result.scanRecord?.deviceName ?: result.device.name
                } catch (_: SecurityException) {
                    null
                }
                if (name?.contains("OXIMETER", ignoreCase = true) == true) {
                    stopScan()
                    append("OXIMETER gefunden")
                    connectGatt(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                ui {
                    scanning = false
                    updateButtons()
                    append("BLE-Scanfehler: $errorCode")
                }
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(callback)
        } catch (e: Exception) {
            scanning = false
            scanCallback = null
            append("Scan fehlgeschlagen: ${e.message}")
            updateButtons()
            return
        }
        main.postDelayed({
            if (scanning && scanCallback === callback) {
                stopScan()
                statusView.text = "OXIMETER nicht gefunden"
                append("Scan nach 15 Sekunden beendet")
            }
        }, 15000)
    }

    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        scanning = false
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {}
        updateButtons()
    }

    private fun connectGatt(device: BluetoothDevice) {
        statusView.text = "Verbinde …"
        append("GATT-Verbindungsaufbau")
        try {
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            append("Verbindung fehlgeschlagen: ${e.message}")
            statusView.text = "Verbindungsfehler"
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, newState: Int) {
            ui {
                append("GATT-Verbindungsstatus: code=$code, state=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && code == BluetoothGatt.GATT_SUCCESS) {
                    connected = true
                    notifyReady = false
                    statusView.text = "Verbunden – suche Dienste"
                    append("Keine automatische History-Anfrage, kein MTU-Request")
                    updateButtons()
                    try {
                        append("Dienste abfragen: ${g.discoverServices()}")
                    } catch (e: Exception) {
                        append("Dienstsuche fehlgeschlagen: ${e.message}")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                    connected = false
                    notifyReady = false
                    writeCharacteristic = null
                    statusView.text = "Getrennt (Status $code)"
                    updateButtons()
                    if (gatt === g) gatt = null
                    try { g.close() } catch (_: Exception) {}
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            ui { append("MTU bestätigt: $mtu, Status=$status") }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            ui {
                append("Dienstsuche Status=$code")
                if (code != BluetoothGatt.GATT_SUCCESS) return@ui
                val characteristic = g.getService(uuidService)?.getCharacteristic(uuidCharacteristic)
                if (characteristic == null) {
                    statusView.text = "FFE0/FFE1 fehlt"
                    append("Service FFE0 / Characteristic FFE1 nicht gefunden")
                    return@ui
                }
                val canNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val canIndicate = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                append("FFE1 gefunden; NOTIFY=$canNotify, INDICATE=$canIndicate")
                if (!canNotify && !canIndicate) return@ui
                val descriptor = characteristic.getDescriptor(uuidCccd)
                if (descriptor == null) {
                    append("CCCD 2902 fehlt")
                    return@ui
                }
                writeCharacteristic = characteristic
                val local = g.setCharacteristicNotification(characteristic, true)
                if (!local) {
                    append("Lokale Notification-Aktivierung fehlgeschlagen")
                    return@ui
                }
                val value = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                append("CCCD-Schreiben gestartet: $started")
                if (!started) statusView.text = "CCCD konnte nicht aktiviert werden"
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) {
            if (descriptor.uuid != uuidCccd) return
            ui {
                notifyReady = code == BluetoothGatt.GATT_SUCCESS
                append("CCCD-Schreibstatus: $code")
                statusView.text = if (notifyReady) "Verbunden – passiv (kein Abruf)"
                    else "Notifications fehlgeschlagen ($code)"
                updateButtons()
                // Intentionally send NOTHING automatically.
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == uuidCharacteristic) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value?.clone() ?: return
                receive(bytes)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == uuidCharacteristic) receive(value.clone())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            ui { append("Schreibbestätigung Status=$status") }
        }
    }

    private fun receive(bytes: ByteArray) {
        ui {
            val now = System.currentTimeMillis()
            val gap = lastRxTime?.let { " (+${now - it} ms)" } ?: ""
            lastRxTime = now
            rxCount++
            receivedBytes += bytes.size
            append("RX #$rxCount ${bytes.size} Byte$gap: ${hex(bytes)}")
            // Concatenate GATT notifications; a single protocol frame may span
            // many 20/244-byte BLE chunks. No additional device writes are needed.
            bytes.forEach { rxBuffer.add(it) }
            dataView.text = "Pakete: $rxCount / Bytes: $receivedBytes\n" +
                "Letzte Größe: ${bytes.size} Byte"
            parseBufferedFrames()
        }
    }

    private fun parseBufferedFrames() {
        while (rxBuffer.size >= 2) {
            // Find frame boundary, keeping a possible trailing AA for the next chunk.
            if (rxBuffer[0] != 0xAA.toByte() || rxBuffer[1] != 0xBB.toByte()) {
                rxBuffer.removeAt(0)
                continue
            }
            if (rxBuffer.size < 4) return
            val length = (rxBuffer[2].toInt() and 255) or
                ((rxBuffer[3].toInt() and 255) shl 8)
            val expectedTotal = 2 + length
            if (length < 3 || length > 8192) {
                append("Ungültiges Längenfeld: $length, suche neue Frame-Grenze")
                rxBuffer.removeAt(0)
                continue
            }
            if (rxBuffer.size < expectedTotal) {
                // Announced history frames often reach only a partial size.
                dataView.text = "Unvollständiges Frame: ${rxBuffer.size} / $expectedTotal Byte\n" +
                    "BLE-Pakete: $rxCount / Gesamtbytes: $receivedBytes"
                return
            }
            val frame = ByteArray(expectedTotal) { rxBuffer[it] }
            repeat(expectedTotal) { rxBuffer.removeAt(0) }
            val actual = frame.last().toInt() and 255
            var checksum = 0
            for (i in 2 until frame.lastIndex) {
                checksum = (checksum + (frame[i].toInt() and 255)) and 255
            }
            val tag = if (frame.size >= 6) {
                "%02X %02X".format(frame[4].toInt() and 255, frame[5].toInt() and 255)
            } else "??"
            append("Frame vollständig: $expectedTotal Byte, Typ $tag, " +
                "Checksum ${if (checksum == actual) "OK" else "ABWEICHEND ($checksum/$actual)"}")
            dataView.text = "Frame komplett: $expectedTotal Byte\nTyp: $tag\n" +
                "Checksum: ${if (checksum == actual) "OK" else "abweichend"}"
            // Deliberately no interpretation as validated SpO2 or pulse.
        }
    }

    private fun confirmExperimentalTest() {
        if (!connected || !notifyReady) return
        AlertDialog.Builder(this)
            .setTitle("Experimentellen Befehl senden?")
            .setMessage(
                "Der Befehl 04 01 01 wurde im Originalverkehr beobachtet, aber seine genaue Funktion " +
                "ist noch nicht sicher bekannt. Er könnte eine Sitzung beenden oder den Ring beeinflussen. " +
                "Es werden keine Lösch- oder Verlaufsbefehle gesendet. Nur ausführen, wenn du den " +
                "Ring bewusst testen möchtest."
            )
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Einmal senden") { _, _ -> sendExperimentalCommand() }
            .show()
    }

    private fun sendExperimentalCommand() {
        val g = gatt ?: return
        val c = writeCharacteristic ?: return
        if (!connected || !notifyReady) return
        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, experimentalCommand, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = experimentalCommand
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            append("TX 04 01 01 (experimentell) ${hex(experimentalCommand)}; angenommen=$result")
            statusView.text = if (result) "Testbefehl gesendet – Empfang beobachten"
                else "Sendevorgang fehlgeschlagen"
        } catch (e: Exception) {
            append("Schreiben fehlgeschlagen: ${e.message}")
        }
    }

    private fun disconnect() {
        stopScan()
        val old = gatt
        connected = false
        notifyReady = false
        writeCharacteristic = null
        statusView.text = "Getrennt"
        updateButtons()
        if (old != null) {
            append("Trennung vom Nutzer angefordert")
            try { old.disconnect() } catch (_: Exception) {}
            // GATT is closed by onConnectionStateChange or onDestroy.
        }
    }

    private fun shareLog() {
        val log = "JKS50G Monitor v0.3\n${logText}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JKS50G Bluetooth-Log")
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(intent, "Protokoll teilen"))
    }

    private fun append(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui { append(message) }
            return
        }
        val text = "${timeFormat.format(Date())} $message\n"
        logText.append(text)
        if (logText.length > 80000) {
            logText.delete(0, logText.length - 60000)
        }
        logView.text = logText.toString()
    }

    private fun updateButtons() {
        if (!::connectButton.isInitialized) return
        connectButton.isEnabled = !scanning && !connected
        disconnectButton.isEnabled = connected || gatt != null
        testButton.isEnabled = connected && notifyReady
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

    private fun ui(action: () -> Unit) = runOnUiThread(action)

    override fun onDestroy() {
        stopScan()
        main.removeCallbacksAndMessages(null)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        super.onDestroy()
    }
}
