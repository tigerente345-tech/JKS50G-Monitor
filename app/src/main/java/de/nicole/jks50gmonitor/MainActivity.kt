
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
    private var finishSequenceStarted = false

    private val SERVICE_FFE0 =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    private val CHAR_FFE1 =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private val CCCD =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Im HCI-Mitschnitt bestätigter History-Abruf: 03 01
    private val HISTORY_COMMAND = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(),
        0x09, 0x00,
        0x03, 0x01,
        0x00, 0x00, 0x00, 0x00,
        0x0D
    )

    // Im Originalverkehr beobachteter Abschlussbefehl: 03 03
    private val HISTORY_FINISH_COMMAND = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(),
        0x09, 0x00,
        0x03, 0x03,
        0x00, 0x00, 0x00, 0x00,
        0x0F
    )

    // Im Originalverkehr direkt danach beobachtet: 04 01 01
    private val SESSION_FINISH_COMMAND = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(),
        0x09, 0x00,
        0x04, 0x01, 0x01,
        0x00, 0x00, 0x00,
        0x0F
    )

    private val historySilenceRunnable = Runnable {
        if (historyActive && !finishSequenceStarted) {
            finishHistorySequence()
        }
    }

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
        main.removeCallbacks(historySilenceRunnable)

        rx.clear()
        historyRx.clear()

        historyActive = false
        historyExpected = 0
        finishSequenceStarted = false
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
                        "OXIMETER",
                        ignoreCase = true
                    ) == true
                ) {

                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                    }

                    append(
                        "Gefunden: $name / ${result.device.address}"
                    )

                    connectGatt(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                append("Scan-Fehler: $errorCode")
            }
        }

        try {
            scanner.startScan(callback)
        } catch (e: Exception) {
            append("Scan konnte nicht starten: ${e.message}")
            return
        }

        main.postDelayed({

            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }

            if (gatt == null) {
                status.text = "Kein OXIMETER gefunden"
            }

        }, 15000)
    }

    private fun connectGatt(device: BluetoothDevice) {

        status.text = "Verbinde …"

        gatt = device.connectGatt(
            this,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                statusCode: Int
            ) {
                ui {
                    append("MTU geändert: $mtu / Status: $statusCode")
                }
            }

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                statusCode: Int,
                newState: Int
            ) {

                ui {
                    append("Verbindungsstatus: $statusCode")
                }

                if (
                    newState == BluetoothProfile.STATE_CONNECTED &&
                    statusCode == BluetoothGatt.GATT_SUCCESS
                ) {

                    ui {
                        status.text =
                            "Verbunden – Dienste werden gelesen"

                        append("GATT verbunden")
                        append("Keine eigene MTU-Anforderung")
                    }

                    val started = g.discoverServices()

                    ui {
                        append("Dienstsuche gestartet: $started")
                    }

                } else if (
                    newState == BluetoothProfile.STATE_DISCONNECTED ||
                    statusCode != BluetoothGatt.GATT_SUCCESS
                ) {

                    main.removeCallbacks(historySilenceRunnable)

                    ui {

                        if (
                            historyRx.isNotEmpty() &&
                            historyExpected > 0 &&
                            historyRx.size < historyExpected
                        ) {
                            status.text =
                                "History-Übertragung beendet"

                            live.text =
                                "History unvollständig\n" +
                                "${historyRx.size} / " +
                                "$historyExpected Byte empfangen"
                        } else {
                            status.text = "Getrennt"
                        }

                        append(
                            "GATT getrennt / Status $statusCode"
                        )
                    }

                    try {
                        g.close()
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                statusCode: Int
            ) {

                ui {
                    append(
                        "Dienste gefunden / Status $statusCode"
                    )
                }

                if (
                    statusCode != BluetoothGatt.GATT_SUCCESS
                ) {
                    ui {
                        status.text =
                            "Dienstsuche fehlgeschlagen"
                    }
                    return
                }

                val c =
                    g.getService(SERVICE_FFE0)
                        ?.getCharacteristic(CHAR_FFE1)

                if (c == null) {

                    ui {
                        status.text =
                            "FFE0/FFE1 nicht gefunden"

                        append("Verfügbare Dienste:")

                        g.services.forEach {
                            append(it.uuid.toString())
                        }
                    }

                    return
                }

                ffe1 = c

                ui {
                    append("FFE0 / FFE1 gefunden")
                    append("Eigenschaften: ${c.properties}")
                }

                val notifySupported =
                    (
                        c.properties and
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    ) != 0

                val indicateSupported =
                    (
                        c.properties and
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0

                ui {
                    append(
                        "Notify unterstützt: $notifySupported"
                    )
                    append(
                        "Indicate unterstützt: $indicateSupported"
                    )
                }

                if (!notifySupported && !indicateSupported) {

                    ui {
                        status.text =
                            "FFE1 unterstützt keine Notifications"
                    }

                    return
                }

                val localEnabled =
                    g.setCharacteristicNotification(
                        c,
                        true
                    )

                ui {
                    append(
                        "Lokale Notification-Aktivierung: $localEnabled"
                    )
                }

                if (!localEnabled) {
                    ui {
                        status.text =
                            "Notification-Aktivierung fehlgeschlagen"
                    }
                    return
                }

                val descriptor = c.getDescriptor(CCCD)

                if (descriptor == null) {

                    ui {
                        status.text = "CCCD fehlt"
                        append(
                            "Descriptor 2902 nicht gefunden"
                        )
                    }

                    return
                }

                val value =
                    if (notifySupported) {
                        BluetoothGattDescriptor
                            .ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor
                            .ENABLE_INDICATION_VALUE
                    }

                val writeStarted =
                    if (Build.VERSION.SDK_INT >= 33) {

                        g.writeDescriptor(
                            descriptor,
                            value
                        ) == BluetoothStatusCodes.SUCCESS

                    } else {

                        @Suppress("DEPRECATION")
                        descriptor.value = value

                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }

                ui {
                    append(
                        "CCCD-Schreibvorgang gestartet: $writeStarted"
                    )

                    status.text =
                        if (writeStarted) {
                            "Verbunden – warte auf Bestätigung"
                        } else {
                            "CCCD-Schreibvorgang fehlgeschlagen"
                        }
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                statusCode: Int
            ) {

                if (descriptor.uuid != CCCD) return

                ui {
                    append(
                        "CCCD-Schreibstatus: $statusCode"
                    )
                }

                if (
                    statusCode == BluetoothGatt.GATT_SUCCESS
                ) {

                    ui {
                        status.text =
                            "Verbunden – Notifications bestätigt"

                        append(
                            "FFE1 Notifications erfolgreich aktiviert"
                        )

                        append(
                            "History-Abruf wird gestartet …"
                        )
                    }

                    main.postDelayed(
                        {
                            sendHistoryCommand(g)
                        },
                        1500
                    )

                } else {

                    ui {
                        status.text =
                            "Notifications fehlgeschlagen"

                        append(
                            "CCCD-Fehler: $statusCode"
                        )
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                if (
                    Build.VERSION.SDK_INT < 33 &&
                    characteristic.uuid == CHAR_FFE1
                ) {

                    @Suppress("DEPRECATION")
                    consume(
                        characteristic.value ?: return
                    )
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                if (characteristic.uuid == CHAR_FFE1) {
                    consume(value)
                }
            }
        }

    private fun sendHistoryCommand(
        g: BluetoothGatt
    ) {

        historyRx.clear()
        historyActive = false
        historyExpected = 0
        finishSequenceStarted = false

        append("TX HISTORY: ${hex(HISTORY_COMMAND)}")

        writeCommand(
            g,
            HISTORY_COMMAND,
            "History-Befehl"
        )

        ui {
            status.text =
                "History angefordert – warte auf Daten"
        }
    }

    private fun writeCommand(
        g: BluetoothGatt,
        command: ByteArray,
        name: String
    ): Boolean {

        val c = ffe1

        if (c == null) {
            append("$name nicht möglich: FFE1 fehlt")
            return false
        }

        val started =
            if (Build.VERSION.SDK_INT >= 33) {

                g.writeCharacteristic(
                    c,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS

            } else {

                @Suppress("DEPRECATION")
                c.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                c.value = command

                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }

        append("$name gesendet: $started")

        return started
    }

    @Synchronized
    private fun consume(chunk: ByteArray) {

        if (historyActive) {

            chunk.forEach {
                historyRx.add(it)
            }

            showHistoryProgress()
            restartHistorySilenceTimer()

            if (
                historyExpected > 0 &&
                historyRx.size >= historyExpected
            ) {

                historyActive = false
                main.removeCallbacks(historySilenceRunnable)

                ui {
                    status.text =
                        "History vollständig empfangen"

                    live.text =
                        "History vollständig\n" +
                        "${historyRx.size} / " +
                        "$historyExpected Byte"

                    append(
                        "HISTORY VOLLSTÄNDIG: " +
                        "${historyRx.size} Byte"
                    )
                }
            }

            return
        }

        val start = findHistoryStart(chunk)

        if (start >= 0) {

            historyRx.clear()

            for (i in start until chunk.size) {
                historyRx.add(chunk[i])
            }

            if (historyRx.size >= 4) {

                val length =
                    (historyRx[2].toInt() and 0xff) or
                    (
                        (historyRx[3].toInt() and 0xff)
                        shl 8
                    )

                historyExpected = 2 + length
            }

            historyActive = true
            finishSequenceStarted = false

            ui {
                append("HISTORY-START erkannt")
                append(
                    "Erwartete Größe: $historyExpected Byte"
                )
            }

            showHistoryProgress()
            restartHistorySilenceTimer()

            return
        }

        ui {
            append("RX ${hex(chunk)}")

            live.text =
                "Bluetooth-Daten empfangen:\n${hex(chunk)}"
        }

        chunk.forEach {
            rx.add(it)
        }

        parseNormalFrames()
    }

    private fun restartHistorySilenceTimer() {

        main.removeCallbacks(historySilenceRunnable)

        /*
         * Vier Sekunden ohne ein einziges neues History-Paket:
         * Dann führen wir den in der Original-App beobachteten
         * Abschlussablauf aus.
         */
        main.postDelayed(
            historySilenceRunnable,
            4000
        )
    }

    private fun finishHistorySequence() {

        val g = gatt ?: return

        finishSequenceStarted = true
        main.removeCallbacks(historySilenceRunnable)

        ui {
            status.text =
                "History steht – Abschluss wird gesendet"

            append(
                "4 Sekunden keine History-Daten"
            )

            append(
                "TX ABSCHLUSS 03 03: " +
                hex(HISTORY_FINISH_COMMAND)
            )
        }

        writeCommand(
            g,
            HISTORY_FINISH_COMMAND,
            "Abschluss 03 03"
        )

        /*
         * Den zweiten beobachteten Befehl nicht gleichzeitig
         * losschicken, sondern mit kurzem Abstand.
         */
        main.postDelayed({

            ui {
                append(
                    "TX ABSCHLUSS 04 01 01: " +
                    hex(SESSION_FINISH_COMMAND)
                )
            }

            writeCommand(
                g,
                SESSION_FINISH_COMMAND,
                "Abschluss 04 01 01"
            )

            ui {

                status.text =
                    "History-Abschluss gesendet"

                live.text =
                    "History unvollständig\n" +
                    "${historyRx.size} / " +
                    "$historyExpected Byte empfangen\n" +
                    "Abschlussbefehle gesendet"
            }

        }, 500)
    }

    private fun findHistoryStart(
        data: ByteArray
    ): Int {

        if (data.size < 7) return -1

        for (i in 0..data.size - 7) {

            if (
                (data[i].toInt() and 0xff) == 0xAA &&
                (data[i + 1].toInt() and 0xff) == 0xBB &&
                (data[i + 2].toInt() and 0xff) == 0xFF &&
                (data[i + 3].toInt() and 0xff) == 0x07 &&
                (data[i + 4].toInt() and 0xff) == 0x43 &&
                (data[i + 5].toInt() and 0xff) == 0x01 &&
                (data[i + 6].toInt() and 0xff) == 0x27
            ) {
                return i
            }
        }

        return -1
    }

    private fun showHistoryProgress() {

        val received = historyRx.size

        ui {
            status.text = "History wird empfangen"

            live.text =
                "History-Daten\n" +
                "$received / $historyExpected Byte empfangen"

            append(
                "History: $received / " +
                "$historyExpected Byte"
            )
        }
    }

    private fun parseNormalFrames() {

        while (true) {

            while (
                rx.size >= 2 &&
                !(
                    (rx[0].toInt() and 0xff) == 0xAA &&
                    (rx[1].toInt() and 0xff) == 0xBB
                )
            ) {
                rx.removeAt(0)
            }

            if (rx.size < 5) return

            val frameLength =
                (rx[2].toInt() and 0xff) or
                (
                    (rx[3].toInt() and 0xff)
                    shl 8
                )

            if (
                frameLength < 3 ||
                frameLength > 8192
            ) {
                rx.removeAt(0)
                continue
            }

            val total = 2 + frameLength

            if (rx.size < total) return

            val frame =
                ByteArray(total) {
                    rx[it]
                }

            repeat(total) {
                rx.removeAt(0)
            }

            parseFrame(frame)
        }
    }

    private fun parseFrame(
        frame: ByteArray
    ) {

        if (frame.size < 5) return

        val expected =
            frame.last().toInt() and 0xff

        var sum = 0

        for (i in 2 until frame.lastIndex) {
            sum =
                (
                    sum +
                    (frame[i].toInt() and 0xff)
                ) and 0xff
        }

        val payload =
            frame.copyOfRange(
                4,
                frame.lastIndex
            )

        ui {
            append("FRAME ${hex(frame)}")

            append(
                "Checksum ${
                    if (sum == expected)
                        "OK"
                    else
                        "FEHLER"
                }"
            )
        }

        if (
            payload.size >= 3 &&
            (payload[0].toInt() and 0xff) == 0x40 &&
            (payload[1].toInt() and 0xff) == 0x03 &&
            (payload[2].toInt() and 0xff) == 0x02
        ) {

            ui {
                live.text =
                    "Live-/Status-Frame empfangen"
            }
        }
    }

    private fun append(
        s: String
    ) = ui {

        val old = log.text.toString()

        log.text =
            (
                old +
                (
                    if (old.isEmpty())
                        ""
                    else
                        "\n"
                ) +
                s
            ).takeLast(16000)
    }

    private fun hex(
        b: ByteArray
    ) =
        b.joinToString(" ") {
            "%02X".format(
                it.toInt() and 0xff
            )
        }

    private fun ui(
        block: () -> Unit
    ) =
        runOnUiThread(block)

    override fun onDestroy() {

        main.removeCallbacks(historySilenceRunnable)

        super.onDestroy()

        try {
            gatt?.close()
        } catch (_: Exception) {
        }
    }
}