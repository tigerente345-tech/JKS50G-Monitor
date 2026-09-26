# JKS50G Monitor v0.2

Experimentelles Android-Projekt zum Auslesen des JKS50G/OXIMETER per BLE.

## Bereits eingebaut
- BLE-Scan nach `OXIMETER`
- Verbindung per GATT
- bekannte Schnittstelle Service `FFE0`, Characteristic `FFE1`
- Notifications
- MTU-Anforderung 247
- Zusammensetzen fragmentierter Frames
- Protokoll: `AA BB [Länge LE] [Payload] [Checksum]`
- Checksumme: Summe ab Längenfeld bis Payload-Ende modulo 256
- Erkennung von Live-Nachrichten `40 03 02`
- Erkennung von History-Antworten `03 01`
- Rohdatenanzeige zur weiteren Protokollanalyse

## Bekannter Mitschnitt
`AA BB 11 00 40 03 02 64 5A 82 3C 00 03 DE 09 26 08 00 EA`

Die Bedeutung einzelner Bytes (insbesondere SpO2/Puls) wird absichtlich noch nicht als gesichert behandelt.

## History
In bisherigen Mitschnitten kündigte `03 01` 2049 Byte an; beobachtet wurden aber unvollständige Übertragungen
(z.B. 7×244 = 1708 bzw. 6×244 = 1464 Byte). Die History-Anforderung muss noch anhand weiterer BLE-Mitschnitte
der Original-App rekonstruiert werden.

## Sicherheit
Die App schreibt in dieser Version noch keine unbekannten Befehle an das Gerät. Das ist absichtlich so,
bis die History-Kommandos sicher rekonstruiert sind.
