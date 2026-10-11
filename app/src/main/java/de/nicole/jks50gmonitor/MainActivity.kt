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

    private val SERVICE_FFE0 =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    private val CHAR_FFE1 =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private val CCCD =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /*
     * Im Bluetooth-Mitschnitt bestätigter Befehl zum Abrufen
     * der gespeicherten Messdaten.
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

    private fun scan() {

        val manager =
            getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        val adapter = manager.adapter ?: return

        status.text = "Suche nach OXIMETER …"
        live.text = "Noch keine Live-Daten"
        log.text = ""

        rx.clear()
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

            append(
                "Scan konnte nicht starten: ${e.message}"
            )

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
                    append(
                        "MTU tatsächlich: $mtu / Status: $statusCode"
                    )
                }
            }

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                statusCode: Int,
                newState: Int
            ) {

                ui {
                    append(
                        "Verbindungsstatus: $statusCode"
                    )
                }

                if (
                    newState ==
                    BluetoothProfile.STATE_CONNECTED &&
                    statusCode ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    ui {
                        status.text =
                            "Verbunden – Dienste werden gelesen"

                        append("GATT verbunden")
                    }

                    val mtuStarted =
                        g.requestMtu(247)

                    ui {
                        append(
                            "MTU 247 angefordert: $mtuStarted"
                        )
                    }

                    val started =
                        g.discoverServices()

                    ui {
                        append(
                            "Dienstsuche gestartet: $started"
                        )
                    }

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED ||
                    statusCode !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    ui {
                        status.text = "Getrennt"

                        append(
                            "GATT getrennt / Status $statusCode"
                        )
                    }

                    g.close()
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
                    statusCode !=
                    BluetoothGatt.GATT_SUCCESS
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
                    append(
                        "Eigenschaften: ${c.properties}"
                    )
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

                if (
                    !notifySupported &&
                    !indicateSupported
                ) {

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

                val descriptor =
                    c.getDescriptor(CCCD)

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
                        ) ==
                        BluetoothStatusCodes.SUCCESS

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

                if (descriptor.uuid != CCCD) {
                    return
                }

                ui {
                    append(
                        "CCCD-Schreibstatus: $statusCode"
                    )
                }

                if (
                    statusCode ==
                    BluetoothGatt.GATT_SUCCESS
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

                    /*
                     * Im Originalmitschnitt kam zunächst eine
                     * Status-/Live-Antwort und unmittelbar danach
                     * der History-Befehl.
                     *
                     * Wir geben dem Gerät deshalb kurz Zeit,
                     * bevor wir den bestätigten History-Befehl senden.
                     */
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
                characteristic:
                    BluetoothGattCharacteristic
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
                characteristic:
                    BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                if (
                    characteristic.uuid ==
                    CHAR_FFE1
                ) {
                    consume(value)
                }
            }
        }

    private fun sendHistoryCommand(
        g: BluetoothGatt
    ) {

        val c = ffe1

        if (c == null) {

            append(
                "History-Abruf nicht möglich: FFE1 fehlt"
            )

            return
        }

        append(
            "TX ${hex(HISTORY_COMMAND)}"
        )

        val started =
            if (Build.VERSION.SDK_INT >= 33) {

                g.writeCharacteristic(
                    c,
                    HISTORY_COMMAND,
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE
                ) ==
                BluetoothStatusCodes.SUCCESS

            } else {

                @Suppress("DEPRECATION")
                c.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                c.value = HISTORY_COMMAND

                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }

        ui {

            append(
                "History-Befehl gesendet: $started"
            )

            if (started) {

                status.text =
                    "History angefordert – empfange Daten"

            } else {

                status.text =
                    "History-Befehl konnte nicht gesendet werden"
            }
        }
    }

    @Synchronized
    private fun consume(chunk: ByteArray) {

        ui {
            append(
                "RX ${hex(chunk)}"
            )

            live.text =
                "Bluetooth-Daten empfangen:\n${hex(chunk)}"
        }

        chunk.forEach {
            rx.add(it)
        }

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

            if (rx.size < 5) {
                return
            }

            /*
             * Das Längenfeld enthält die Anzahl der Bytes
             * ab dem Längenfeld selbst bis einschließlich
             * Prüfsumme.
             *
             * Beispiel:
             * AA BB 11 00 ... xx
             *
             * 0x0011 = 17 Byte
             * plus AA BB = insgesamt 19 Byte.
             */
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

            val total =
                2 + frameLength

            if (rx.size < total) {
                return
            }

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

        if (frame.size < 5) {
            return
        }

        val expected =
            frame.last().toInt() and 0xff

        var sum = 0

        for (
            i in 2 until frame.lastIndex
        ) {

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

            append(
                "FRAME ${hex(frame)}"
            )

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
            (payload[0].toInt() and 0xff) ==
                0x40 &&
            (payload[1].toInt() and 0xff) ==
                0x03 &&
            (payload[2].toInt() and 0xff) ==
                0x02
        ) {

            val body =
                payload
                    .drop(3)
                    .joinToString(" ") {

                        "%02X".format(
                            it.toInt() and 0xff
                        )
                    }

            ui {
                live.text =
                    "Live-Frame 40 03 02\n$body"
            }
        }

        if (
            payload.size >= 3 &&
            (payload[0].toInt() and 0xff) ==
                0x43 &&
            (payload[1].toInt() and 0xff) ==
                0x01 &&
            (payload[2].toInt() and 0xff) ==
                0x27
        ) {

            ui {

                status.text =
                    "Gespeicherte Messdaten empfangen"

                live.text =
                    "History-Daten empfangen\n" +
                    "Frame: ${frame.size} Byte"

                append(
                    "HISTORY-FRAME erkannt: ${frame.size} Byte"
                )
            }
        }

        if (
            payload.size >= 2 &&
            (payload[0].toInt() and 0xff) ==
                0x03 &&
            (payload[1].toInt() and 0xff) ==
                0x01
        ) {

            ui {
                append(
                    "History-Antwort 03 01 erkannt"
                )
            }
        }
    }

    private fun append(
        s: String
    ) = ui {

        val old =
            log.text.toString()

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

        super.onDestroy()

        try {
            gatt?.close()
        } catch (_: Exception) {
        }
    }
}