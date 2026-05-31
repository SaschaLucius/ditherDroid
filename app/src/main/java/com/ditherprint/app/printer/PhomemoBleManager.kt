package com.ditherprint.app.printer

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * BLE manager for discovering and communicating with Phomemo printers.
 * Ported from node-phomemo-printer/index.js BLE logic.
 */
class PhomemoBleManager(private val context: Context) {

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

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _printProgress = MutableStateFlow<PrintProgress?>(null)
    val printProgress: StateFlow<PrintProgress?> = _printProgress.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var writeComplete = CompletableDeferred<Unit>()

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = 8000) {
        val adapter = bluetoothAdapter ?: run {
            _state.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = ConnectionState.Error("BLE scanner not available. Is Bluetooth on?")
            return
        }

        _discoveredDevices.value = emptyList()
        _state.value = ConnectionState.Scanning

        val scanFilter = ScanFilter.Builder().build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        CoroutineScope(Dispatchers.Main).launch {
            delay(durationMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        if (_state.value is ConnectionState.Scanning) {
            _state.value = ConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            // Only show devices that look like Phomemo printers
            if (!name.startsWith("M0", ignoreCase = true) &&
                !name.contains("phomemo", ignoreCase = true) &&
                !name.startsWith("T0", ignoreCase = true)
            ) return

            val current = _discoveredDevices.value
            if (current.none { it.address == device.address }) {
                _discoveredDevices.value = current + device
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = ConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        _state.value = ConnectionState.Connecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Request higher MTU for faster printing
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = ConnectionState.Disconnected
                    writeCharacteristic = null
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // After MTU negotiation, discover services
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Error("Service discovery failed")
                return
            }

            // Find a writable characteristic (matching node-phomemo-printer approach)
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                    ) {
                        writeCharacteristic = char
                        val deviceName = gatt.device.name ?: gatt.device.address
                        _state.value = ConnectionState.Connected(deviceName)
                        return
                    }
                }
            }
            _state.value = ConnectionState.Error("No writable characteristic found")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeComplete.complete(Unit)
            } else {
                writeComplete.completeExceptionally(
                    Exception("Write failed with status $status")
                )
            }
        }
    }

    /**
     * Send data to the printer in chunks, reporting progress.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendData(data: ByteArray) {
        val gatt = bluetoothGatt ?: throw IllegalStateException("Not connected")
        val char = writeCharacteristic ?: throw IllegalStateException("No writable characteristic")

        val chunkSize = 512  // After MTU negotiation, use larger chunks
        val totalBytes = data.size
        var offset = 0

        _printProgress.value = PrintProgress(0, totalBytes)

        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize, totalBytes)
            val chunk = data.copyOfRange(offset, end)

            writeComplete = CompletableDeferred()
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!gatt.writeCharacteristic(char)) {
                throw Exception("Failed to initiate write")
            }

            // Wait for write callback
            withTimeout(5000) { writeComplete.await() }

            offset = end
            _printProgress.value = PrintProgress(offset, totalBytes)
        }

        // Small delay to let printer process final chunk
        delay(200)
        _printProgress.value = null
    }

    /**
     * Print a dithered bitmap.
     */
    suspend fun print(bitmap: android.graphics.Bitmap, density: PhomemoProtocol.Density) {
        // Send density packet if not default
        if (density != PhomemoProtocol.Density.DEFAULT) {
            sendData(PhomemoProtocol.buildDensityPacket(density))
            delay(100)
        }
        // Send print data
        val printData = PhomemoProtocol.buildPrintData(bitmap)
        sendData(printData)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _state.value = ConnectionState.Disconnected
    }
}
