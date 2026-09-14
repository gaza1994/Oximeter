package com.garethjohnstone.oximeter.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

private const val TAG = "OximeterBle"

val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class LinkState { Idle, Scanning, Connecting, Connected, Reconnecting, NoBluetooth }

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** True if the advertisement mentions the FFE0 serial service. */
    val advertisesTargetService: Boolean,
    val lastSeenAt: Long
)

/**
 * Scan / connect / subscribe. The device free-runs, so nothing is ever written
 * to it apart from the notification descriptor.
 *
 * Two things dominate how quickly this connects:
 *
 * 1. When the address is already known, connect straight to it. That is what a
 *    desktop BLE client does, it takes a couple of seconds, and it costs no
 *    scan allowance. A watchdog falls back to scanning if the device is not
 *    actually there.
 *
 * 2. Android permits roughly 5 startScan calls per 30 seconds per app. Going
 *    over does not fail loudly - the scan simply never reports anything until
 *    the window rolls - so scan starts are counted and deferred rather than
 *    fired freely. Restarting the app repeatedly used to trip this, which
 *    looked exactly like a sensor that would not connect.
 */
@SuppressLint("MissingPermission")
class OximeterBle(private val context: Context) {

    /** Address to connect to directly, once the user has picked one. */
    var preferredAddress: String? = null

    /** When false, devices are reported but nothing connects automatically. */
    var autoConnect: Boolean = true

    var onState: (LinkState) -> Unit = {}
    var onData: (ByteArray) -> Unit = {}
    var onDevices: (List<DiscoveredDevice>) -> Unit = {}
    var onNote: (String?) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { context.getSystemService(BluetoothManager::class.java) }
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var wantConnection = false
    private var connectedAddress: String? = null
    private var retryDelayMs = 2_000L

    private val seen = LinkedHashMap<String, DiscoveredDevice>()
    private var lastDeviceEmit = 0L

    private val scanStarts = ArrayDeque<Long>()
    private var connectWatchdog: Runnable? = null
    private var closing = false

    // --- scanning ----------------------------------------------------------

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            scanning = false
            onNote(
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth needs restarting"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone cannot do BLE scanning"
                    SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
                    else -> "Scan failed, code $errorCode"
                }
            )
            scheduleRetry()
        }

        private fun handle(result: ScanResult) {
            val dev = result.device ?: return
            val record = result.scanRecord
            val name = dev.name ?: record?.deviceName
            val advertisesService = record?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true

            val isNew = !seen.containsKey(dev.address)
            seen[dev.address] = DiscoveredDevice(
                address = dev.address,
                name = name,
                rssi = result.rssi,
                advertisesTargetService = advertisesService,
                lastSeenAt = System.currentTimeMillis()
            )
            if (isNew) {
                Log.d(TAG, "saw ${name ?: "(no name)"} ${dev.address} ffe0=$advertisesService")
            }

            // Matching first: connecting matters more than refreshing a list.
            if (autoConnect && wantConnection) {
                val wanted = preferredAddress?.equals(dev.address, ignoreCase = true) == true ||
                    advertisesService
                if (wanted) {
                    connectTo(dev)
                    return
                }
            }

            // Then the list, a couple of times a second at most. Every device in
            // the house advertises several times a second, and rebuilding the
            // list on each one starves the callback that matters.
            val now = System.currentTimeMillis()
            if (isNew || now - lastDeviceEmit > 750) {
                lastDeviceEmit = now
                onDevices(seen.values.sortedByDescending { it.rssi })
            }
        }
    }

    // --- gatt --------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "connected, discovering services")
                cancelWatchdog()
                main.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "disconnected status=$status")
                closing = false
                closeGatt()
                connectedAddress = null
                if (wantConnection) {
                    onState(LinkState.Reconnecting)
                    scheduleRetry()
                } else {
                    onState(LinkState.Idle)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                val found = g.services.joinToString { it.uuid.toString().substring(4, 8) }
                Log.w(TAG, "no FFE0 service; device exposes: $found")
                onNote("That device has no FFE0 service (found: $found)")
                preferredAddress = null
                g.disconnect()
                return
            }
            val ch = service.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                onNote("FFE0 present but no FFE1 characteristic")
                g.disconnect()
                return
            }

            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD_UUID)?.let { cccd ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }

            retryDelayMs = 2_000L
            connectedAddress = g.device.address
            preferredAddress = g.device.address
            onNote(null)
            onState(LinkState.Connected)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (ch.uuid == CHAR_UUID) onData(value)
        }

        @Deprecated("Pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == CHAR_UUID) {
                @Suppress("DEPRECATION")
                ch.value?.let { onData(it) }
            }
        }
    }

    // --- lifecycle ---------------------------------------------------------

    fun start() {
        wantConnection = true
        val a = adapter
        if (a == null || !a.isEnabled) { onState(LinkState.NoBluetooth); return }
        if (connectedAddress != null) return

        val known = preferredAddress
        if (known != null && autoConnect) {
            val device = runCatching { a.getRemoteDevice(known) }.getOrNull()
            if (device != null) {
                connectTo(device)
                armWatchdog()
                return
            }
        }
        startScan()
    }

    fun stop() {
        wantConnection = false
        cancelWatchdog()
        main.removeCallbacksAndMessages(null)
        stopScan()
        releaseGatt()
        connectedAddress = null
        onState(LinkState.Idle)
    }

    /** User picked a device from the list. */
    fun connectToAddress(address: String) {
        val a = adapter ?: return
        wantConnection = true
        preferredAddress = address
        onNote(null)
        runCatching { connectTo(a.getRemoteDevice(address)) }
        armWatchdog()
    }

    fun rescan() {
        seen.clear()
        onDevices(emptyList())
        onNote(null)
        stopScan()
        startScan()
    }

    // --- internals ---------------------------------------------------------

    private fun armWatchdog() {
        cancelWatchdog()
        val task = Runnable {
            if (wantConnection && connectedAddress == null) {
                Log.d(TAG, "direct connect did not land, falling back to scanning")
                releaseGatt()
                main.postDelayed({ if (wantConnection) startScan() }, 400)
            }
        }
        connectWatchdog = task
        main.postDelayed(task, 8_000)
    }

    private fun cancelWatchdog() {
        connectWatchdog?.let { main.removeCallbacks(it) }
        connectWatchdog = null
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { onState(LinkState.NoBluetooth); return }
        if (scanning) return

        val now = System.currentTimeMillis()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > 30_000) scanStarts.removeFirst()
        if (scanStarts.size >= 4) {
            val waitMs = 30_000 - (now - scanStarts.first()) + 500
            Log.w(TAG, "scan allowance used up, waiting ${waitMs}ms")
            onNote("Android limits how often an app may scan. Retrying in ${waitMs / 1000}s.")
            onState(LinkState.Scanning)
            main.postDelayed({ if (wantConnection) startScan() }, waitMs)
            return
        }
        scanStarts.addLast(now)

        scanning = true
        onState(LinkState.Scanning)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        // No filters on purpose: the name is unreliable and offloaded filters
        // only inspect the advertisement, not the scan response.
        scanner.startScan(null, settings, scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun connectTo(device: BluetoothDevice) {
        stopScan()
        preferredAddress = device.address
        onState(LinkState.Connecting)

        if (gatt != null) {
            // Release the old link properly before opening a new one. Closing it
            // out from under a live connection is the same race that left the
            // sensor believing it was still connected.
            releaseGatt()
            main.postDelayed({ if (wantConnection) openGatt(device) }, 400)
        } else {
            openGatt(device)
        }
    }

    private fun openGatt(device: BluetoothDevice) {
        closeGatt()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Let the disconnect complete before closing.
     *
     * Calling close() straight after disconnect() aborts the handshake: the
     * local stack forgets the link but the sensor never hears about it, so it
     * keeps the connection open, stops advertising, and refuses anyone else
     * until its own supervision timeout expires. That is what made the app look
     * like it could not connect while a desktop client managed it instantly -
     * the desktop was connecting after the sensor had finally given up.
     */
    private fun releaseGatt() {
        val g = gatt ?: return
        closing = true
        runCatching { g.disconnect() }
        // If the callback never arrives, close anyway rather than leaking the
        // GATT client - Android only allows a limited number of registrations.
        main.postDelayed({
            if (closing) {
                closing = false
                closeGatt()
            }
        }, 2_000)
    }

    private fun closeGatt() {
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun scheduleRetry() {
        if (!wantConnection) return
        main.postDelayed({ if (wantConnection) start() }, retryDelayMs)
        // Backoff only matters while scanning. Retrying a known address is cheap
        // and should stay prompt.
        retryDelayMs =
            if (preferredAddress != null) 2_000L
            else (retryDelayMs * 2).coerceAtMost(15_000L)
    }
}
