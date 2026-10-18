package com.ditherprint.app.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE manager for discovering and communicating with Phomemo printers.
 */
class PhomemoBleManager(private val context: Context) {

    companion object {
        private const val TAG = "PhomemoBleManager"
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Prefer known Phomemo / thermal-printer FF00-style services when present
        private val PREFERRED_SERVICE_PREFIXES = listOf(
            "0000ff00", "0000ff10", "0000ae00", "0000fff0"
        )
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Scanning : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    data class PrintProgress(val bytesSent: Int, val totalBytes: Int) {
        val percent: Float get() = if (totalBytes == 0) 0f else bytesSent.toFloat() / totalBytes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private val _printProgress = MutableStateFlow<PrintProgress?>(null)
    val printProgress: StateFlow<PrintProgress?> = _printProgress.asStateFlow()

    private val _printerInfo = MutableStateFlow(PhomemoProtocol.PrinterInfo())
    val printerInfo: StateFlow<PhomemoProtocol.PrinterInfo> = _printerInfo.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanTimeoutJob: Job? = null
    private var negotiatedMtu: Int = 23

    private val writeMutex = Mutex()
    private val writeGeneration = AtomicInteger(0)
    private val pendingWrite = AtomicReference<CompletableDeferred<Unit>?>(null)
    private var printDoneDeferred: CompletableDeferred<Unit>? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasBluetoothPermission(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean =
        hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasScanPermission(): Boolean =
        hasBluetoothPermission(Manifest.permission.BLUETOOTH_SCAN)

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun loadBondedDevices() {
        if (!hasConnectPermission()) {
            _bondedDevices.value = emptyList()
            return
        }
        val adapter = bluetoothAdapter ?: return
        val bonded = try {
            adapter.bondedDevices.toList()
        } catch (_: SecurityException) {
            emptyList()
        }
        _bondedDevices.value = bonded
    }

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = 8000) {
        if (!hasScanPermission()) {
            _state.value = ConnectionState.Error("Bluetooth scan permission required")
            return
        }
        if (!hasConnectPermission()) {
            _state.value = ConnectionState.Error("Bluetooth connect permission required")
            _bondedDevices.value = emptyList()
            return
        }
        val adapter = bluetoothAdapter ?: run {
            _state.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = ConnectionState.Error("Bluetooth is turned off")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = ConnectionState.Error("BLE scanner not available. Is Bluetooth on?")
            return
        }

        loadBondedDevices()
        _discoveredDevices.value = emptyList()
        _state.value = ConnectionState.Scanning

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, scanSettings, scanCallback)

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val activeScanner = scanner
        scanner = null
        if (activeScanner != null && hasScanPermission()) {
            try {
                activeScanner.stopScan(scanCallback)
            } catch (_: SecurityException) {
            }
        }
        if (_state.value is ConnectionState.Scanning) {
            _state.value = ConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val current = _discoveredDevices.value
            if (current.any { it.address == device.address }) return
            _discoveredDevices.value = current + device
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = ConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            _state.value = ConnectionState.Error("Bluetooth connect permission required")
            return
        }
        if (!isBluetoothEnabled()) {
            _state.value = ConnectionState.Error("Bluetooth is turned off")
            return
        }
        stopScan()
        closeGattQuietly()
        _state.value = ConnectionState.Connecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun connectByAddress(macAddress: String) {
        if (!hasConnectPermission()) {
            _state.value = ConnectionState.Error("Bluetooth connect permission required")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            _state.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = ConnectionState.Error("Bluetooth is turned off")
            return
        }
        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (_: IllegalArgumentException) {
            _state.value = ConnectionState.Error("Invalid MAC address")
            return
        }
        stopScan()
        closeGattQuietly()
        _state.value = ConnectionState.Connecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT status=$status newState=$newState")
                _state.value = ConnectionState.Error("Connection failed (status $status)")
                closeGattQuietly(gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    bluetoothGatt = null
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (_state.value !is ConnectionState.Error) {
                        _state.value = ConnectionState.Disconnected
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Error("Service discovery failed")
                closeGattQuietly(gatt)
                return
            }

            val (foundWrite, foundNotify) = selectCharacteristics(gatt)
            if (foundWrite == null) {
                _state.value = ConnectionState.Error("No writable characteristic found")
                closeGattQuietly(gatt)
                return
            }

            writeCharacteristic = foundWrite

            if (foundNotify != null) {
                notifyCharacteristic = foundNotify
                gatt.setCharacteristicNotification(foundNotify, true)
                val descriptor = foundNotify.getDescriptor(CCC_DESCRIPTOR_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
                Log.d(TAG, "Notifications enabled on ${foundNotify.uuid}")
            }

            val deviceName = gatt.device.name ?: gatt.device.address
            _state.value = ConnectionState.Connected(deviceName)
            _printerInfo.value = PhomemoProtocol.PrinterInfo()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val deferred = pendingWrite.getAndSet(null) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deferred.complete(Unit)
            } else {
                deferred.completeExceptionally(Exception("Write failed with status $status"))
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleNotification(data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
        }
    }

    /**
     * Prefer characteristics on known Phomemo-style services; prefer WRITE over WRITE_NO_RESPONSE.
     */
    private fun selectCharacteristics(
        gatt: BluetoothGatt
    ): Pair<BluetoothGattCharacteristic?, BluetoothGattCharacteristic?> {
        data class Cand(val char: BluetoothGattCharacteristic, val preferred: Boolean, val hasAck: Boolean)

        val writeCands = mutableListOf<Cand>()
        val notifyCands = mutableListOf<Cand>()

        for (service in gatt.services) {
            val uuid = service.uuid.toString().lowercase()
            val preferred = PREFERRED_SERVICE_PREFIXES.any { uuid.startsWith(it) }
            for (char in service.characteristics) {
                val props = char.properties
                val hasWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                val hasWriteNr = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                if (hasWrite || hasWriteNr) {
                    writeCands += Cand(char, preferred, hasAck = hasWrite)
                }
                val hasNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val hasIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (hasNotify || hasIndicate) {
                    notifyCands += Cand(char, preferred, hasAck = hasNotify)
                }
            }
        }

        val write = writeCands
            .sortedWith(compareByDescending<Cand> { it.preferred }.thenByDescending { it.hasAck })
            .firstOrNull()?.char
        val notify = notifyCands
            .sortedWith(compareByDescending<Cand> { it.preferred }.thenByDescending { it.hasAck })
            .firstOrNull()?.char
        return write to notify
    }

    private fun handleNotification(data: ByteArray) {
        val result = PhomemoProtocol.parseNotification(data, _printerInfo.value)
        if (result != null) {
            val (field, updated) = result
            _printerInfo.value = updated
            Log.d(TAG, "Printer info updated: $field -> $updated")
            if (field == "print" && !updated.isPrinting) {
                printDoneDeferred?.complete(Unit)
            }
        }
    }

    suspend fun queryPrinterInfo() {
        if (_state.value !is ConnectionState.Connected) return
        val queries = listOf(
            PhomemoProtocol.QueryCommands.BATTERY,
            PhomemoProtocol.QueryCommands.PAPER,
            PhomemoProtocol.QueryCommands.COVER,
            PhomemoProtocol.QueryCommands.FIRMWARE,
            PhomemoProtocol.QueryCommands.SERIAL,
            PhomemoProtocol.QueryCommands.VERSION,
            PhomemoProtocol.QueryCommands.MAC
        )
        for (query in queries) {
            try {
                sendData(query)
                delay(150)
            } catch (e: Exception) {
                Log.w(TAG, "Query failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendData(data: ByteArray) {
        writeMutex.withLock {
            val gatt = bluetoothGatt ?: throw IllegalStateException("Not connected")
            val char = writeCharacteristic ?: throw IllegalStateException("No writable characteristic")

            val chunkSize = (negotiatedMtu - 3).coerceIn(20, 512)
            val totalBytes = data.size
            var offset = 0

            _printProgress.value = PrintProgress(0, totalBytes)

            try {
                while (offset < totalBytes) {
                    val end = minOf(offset + chunkSize, totalBytes)
                    val chunk = data.copyOfRange(offset, end)

                    val generation = writeGeneration.incrementAndGet()
                    val deferred = CompletableDeferred<Unit>()
                    pendingWrite.set(deferred)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val result = gatt.writeCharacteristic(
                            char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                        if (result != BluetoothStatusCodes.SUCCESS) {
                            pendingWrite.compareAndSet(deferred, null)
                            throw Exception("Failed to initiate write (code $result)")
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = chunk
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        if (!gatt.writeCharacteristic(char)) {
                            pendingWrite.compareAndSet(deferred, null)
                            throw Exception("Failed to initiate write")
                        }
                    }

                    try {
                        withTimeout(5000) { deferred.await() }
                    } catch (e: TimeoutCancellationException) {
                        // Invalidate this write so a late callback cannot complete the next chunk
                        pendingWrite.compareAndSet(deferred, null)
                        writeGeneration.incrementAndGet()
                        throw Exception("Write timed out (gen $generation)")
                    }

                    offset = end
                    _printProgress.value = PrintProgress(offset, totalBytes)
                }
                delay(200)
            } finally {
                _printProgress.value = null
            }
        }
    }

    suspend fun print(
        bitmap: android.graphics.Bitmap,
        density: PhomemoProtocol.Density,
        speed: PhomemoProtocol.PrintSpeed = PhomemoProtocol.PrintSpeed.NORMAL,
        alignment: PhomemoProtocol.Alignment = PhomemoProtocol.Alignment.LEFT,
        paperFeed: Int = PhomemoProtocol.DEFAULT_PAPER_FEED
    ) {
        if (_state.value !is ConnectionState.Connected) {
            throw IllegalStateException("Not connected")
        }
        require(bitmap.width == PhomemoProtocol.IMAGE_WIDTH) {
            "Bitmap must be ${PhomemoProtocol.IMAGE_WIDTH} pixels wide, got ${bitmap.width}"
        }

        printDoneDeferred = CompletableDeferred()
        _printerInfo.value = _printerInfo.value.copy(isPrinting = true)

        try {
            sendData(PhomemoProtocol.buildHeatSettingsPacket(speed))
            delay(50)
            if (density != PhomemoProtocol.Density.DEFAULT) {
                sendData(PhomemoProtocol.buildDensityPacket(density))
                delay(100)
            }
            val printData = PhomemoProtocol.buildPrintData(bitmap, alignment, paperFeed)
            sendData(printData)

            try {
                withTimeout(30_000) {
                    printDoneDeferred?.await()
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Print-done notification timeout, assuming complete")
            }
        } finally {
            printDoneDeferred = null
            _printerInfo.value = _printerInfo.value.copy(isPrinting = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattQuietly(gatt: BluetoothGatt? = bluetoothGatt) {
        gatt ?: return
        if (hasConnectPermission()) {
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
            }
        }
        try {
            gatt.close()
        } catch (_: Exception) {
        }
        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        pendingWrite.getAndSet(null)?.cancel()
        closeGattQuietly()
        negotiatedMtu = 23
        _state.value = ConnectionState.Disconnected
        _printerInfo.value = PhomemoProtocol.PrinterInfo()
    }

    /** Release coroutine resources when the owning ViewModel is cleared. */
    fun release() {
        disconnect()
        scope.cancel()
    }
}
