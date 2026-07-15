package com.rfsat.vtb.environment

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rfsat.vtb.log.Logger
import java.util.UUID

/**
 * Reads environmental data from a PAIRED Kestrel meter over BLE (v17.0) —
 * targets the Kestrel 5700 Elite and the Kestrel DROP D3 logger.
 *
 * HONESTY NOTE ON THE PROTOCOL: Kestrel's GATT layout is proprietary and
 * undocumented. This implementation does two things:
 *
 *  1. ALWAYS: connects to the bonded Kestrel, discovers every service,
 *     reads every readable characteristic, and logs UUIDs + raw hex to
 *     vtb_log.txt. Whatever else happens, one connection attempt gives us
 *     the device's true layout to wire in precisely.
 *
 *  2. BEST-EFFORT: parses the community-reverse-engineered DROP-series
 *     service (UUID base 12630000-cc25-497d-9854-9b6c02c77054):
 *     temperature / humidity / pressure as little-endian scaled integers.
 *     If the device (particularly the 5700 Elite, whose LiNK Ballistics
 *     protocol differs) doesn't expose these, no value is taken and the
 *     phone/default environment stays in force — never a garbage parse.
 *
 * Uses only BONDED devices (pair the Kestrel in Android Bluetooth settings
 * first), so no BLE scan — and therefore no location permission — is
 * needed; only BLUETOOTH_CONNECT on Android 12+.
 */
object KestrelProvider {

    private const val TAG = "KestrelProvider"
    private const val TIMEOUT_MS = 12_000L

    /**
     * DROP D3 protocol — VERIFIED against a real device (fw 1.59, hw Rev
     * 9A2, GATT dump 2026-07-11). Every measurement characteristic is a
     * 0x07 format-tag byte followed by a little-endian int16, scaled /100:
     *   12630001 temperature  sint16/100 degC   (dump: 0x0BE3 = 30.43)
     *   12630002 humidity     uint16/100 %      (dump: 0x17F5 = 61.33)
     *   12630003 HEAT INDEX   /100 degC — NOT pressure; the pre-v19.2
     *            parser read this as pressure and its raw value happened
     *            to pass the 30-110 kPa gate (~890 hPa), silently biasing
     *            air density ~12%
     *   12630004 dew point    sint16/100 degC   (dump: 22.15 — matches
     *            30.43 degC / 61.33% exactly)
     *   12630007 station pressure uint16/100 kPa (dump: 0x279E = 101.42
     *            kPa — agrees with the phone barometer to 0.2 hPa)
     *   12630104 device clock (…day hour minute … year LE) — matched the
     *            log timestamps, confirming the byte order
     * Zeroed registers (0005/6/8/9, 000b..0f) are sensors the D3 lacks
     * (wind etc. on other DROP/5-series models).
     */
    private val DROP_SERVICE: UUID = UUID.fromString("12630000-cc25-497d-9854-9b6c02c77054")
    private val DROP_TEMPERATURE: UUID = UUID.fromString("12630001-cc25-497d-9854-9b6c02c77054")
    private val DROP_HUMIDITY: UUID = UUID.fromString("12630002-cc25-497d-9854-9b6c02c77054")
    private val DROP_PRESSURE: UUID = UUID.fromString("12630007-cc25-497d-9854-9b6c02c77054")
    /** Standard Bluetooth Environmental Sensing service — some firmware
     *  exposes it alongside the proprietary one; parse it when present
     *  (these UUIDs and formats ARE official Bluetooth SIG definitions). */
    private val ESS_SERVICE: UUID = UUID.fromString("0000181a-0000-1000-8000-00805f9b34fb")
    private val ESS_TEMPERATURE: UUID = UUID.fromString("00002a6e-0000-1000-8000-00805f9b34fb") // sint16, 0.01 °C
    private val ESS_HUMIDITY: UUID = UUID.fromString("00002a6f-0000-1000-8000-00805f9b34fb")    // uint16, 0.01 %
    private val ESS_PRESSURE: UUID = UUID.fromString("00002a6d-0000-1000-8000-00805f9b34fb")    // uint32, 0.1 Pa

    /** A bonded device that looks like a Kestrel, or null. */
    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_CONNECT
    fun findPairedKestrel(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return runCatching {
            adapter.bondedDevices.firstOrNull { it.name?.contains("kestrel", ignoreCase = true) == true }
        }.getOrNull()
    }

    /**
     * Scans for an ADVERTISING Kestrel (v18.0). The DROP series never
     * appears in Android's paired-devices list — it's BLE-advertising-only
     * with no classic bonding (confirmed on the user's D3) — so when no
     * bonded Kestrel exists we listen for advertisements instead. Every
     * named advertiser seen is logged, so even a miss identifies the D3's
     * actual advertised name for an exact filter later. Calls [onResult]
     * on the main thread with the device, or null on timeout/failure.
     */
    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_SCAN / location
    fun scanForKestrel(context: Context, timeoutMs: Long = 10_000L, onResult: (BluetoothDevice?) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Logger.e(TAG, "BLE scanner unavailable (Bluetooth off?)")
            onResult(null); return
        }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val seenNames = mutableSetOf<String>()

        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (seenNames.add(name)) Logger.i(TAG, "BLE advertiser: \"$name\" rssi=${result.rssi}")
                if (name.contains("kestrel", ignoreCase = true) ||
                    name.contains("drop", ignoreCase = true)
                ) {
                    if (done) return
                    done = true
                    runCatching { scanner.stopScan(this) }
                    Logger.i(TAG, "Kestrel found by scan: \"$name\"")
                    handler.post { onResult(result.device) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (done) return
                done = true
                Logger.e(TAG, "BLE scan failed: code $errorCode")
                handler.post { onResult(null) }
            }
        }

        Logger.i(TAG, "Starting BLE scan for Kestrel (${timeoutMs / 1000}s)")
        scanner.startScan(
            null,
            android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            cb
        )
        handler.postDelayed({
            if (!done) {
                done = true
                runCatching { scanner.stopScan(cb) }
                Logger.i(TAG, "BLE scan timeout — named advertisers seen: $seenNames")
                onResult(null)
            }
        }, timeoutMs)
    }

    /**
     * Connect, discover, log everything, best-effort read. Calls [onDone]
     * on the main thread with true if at least one environmental value was
     * obtained (already pushed into [EnvironmentManager]).
     */
    @SuppressLint("MissingPermission")
    fun read(context: Context, device: BluetoothDevice, onDone: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var tempC: Double? = null
        var pressPa: Double? = null
        var humFrac: Double? = null
        var finished = false
        val queue = ArrayDeque<BluetoothGattCharacteristic>()

        fun finish(gatt: BluetoothGatt?) {
            if (finished) return
            finished = true
            runCatching { gatt?.close() }
            val got = tempC != null || pressPa != null || humFrac != null
            if (got) EnvironmentManager.setFromKestrel(tempC, pressPa, humFrac)
            Logger.i(TAG, "Kestrel read done: temp=$tempC °C pressure=$pressPa Pa humidity=$humFrac (got=$got)")
            handler.post { onDone(got) }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Logger.i(TAG, "GATT state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish(gatt)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Discovery log: THE deliverable of the first connection —
                // it pins down this device's real layout for exact wiring.
                for (svc in gatt.services) {
                    Logger.i(TAG, "Kestrel service ${svc.uuid}")
                    for (ch in svc.characteristics) {
                        Logger.i(TAG, "  char ${ch.uuid} props=0x${ch.properties.toString(16)}")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                            queue.add(ch)
                        }
                    }
                }
                readNext(gatt)
            }

            fun readNext(gatt: BluetoothGatt) {
                val ch = queue.removeFirstOrNull() ?: run { finish(gatt); return }
                if (!gatt.readCharacteristic(ch)) readNext(gatt)
            }

            @Deprecated("pre-33 callback; fine at minSdk 26")
            override fun onCharacteristicRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                val v = ch.value ?: ByteArray(0)
                Logger.i(TAG, "  read ${ch.uuid} = ${v.joinToString("") { "%02x".format(it) }}")
                if (status == BluetoothGatt.GATT_SUCCESS && v.isNotEmpty()) parse(ch.uuid, v)
                readNext(gatt)
            }

            fun parse(uuid: UUID, v: ByteArray) {
                fun le16(): Int = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                fun sle16(): Int = le16().let { if (it > 0x7FFF) it - 0x10000 else it }
                fun le32(): Long {
                    var r = 0L
                    for (i in 0 until minOf(4, v.size)) r = r or ((v[i].toLong() and 0xFF) shl (8 * i))
                    return r
                }
                when (uuid) {
                    ESS_TEMPERATURE -> if (v.size >= 2) tempC = sle16() / 100.0
                    ESS_HUMIDITY -> if (v.size >= 2) humFrac = le16() / 10000.0
                    ESS_PRESSURE -> if (v.size >= 4) pressPa = le32() / 10.0
                    // v19.2 verified layout: tag byte at [0], LE16 value at [1..2].
                    DROP_TEMPERATURE -> if (v.size >= 3) {
                        val raw = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
                        val t = (if (raw > 0x7FFF) raw - 0x10000 else raw) / 100.0
                        if (t in -60.0..80.0) tempC = t
                    }
                    DROP_HUMIDITY -> if (v.size >= 3) {
                        val raw = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
                        val h = raw / 10000.0
                        if (h in 0.0..1.0) humFrac = h
                    }
                    DROP_PRESSURE -> if (v.size >= 3) {
                        val raw = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
                        val p = raw * 10.0 // uint16/100 kPa -> Pa
                        if (p in 30_000.0..110_000.0) pressPa = p
                    }
                }
            }
        }

        Logger.i(TAG, "Connecting to paired Kestrel \"${device.name}\"")
        val gatt = device.connectGatt(context, false, cb)
        handler.postDelayed({ finish(gatt) }, TIMEOUT_MS)
    }
}
