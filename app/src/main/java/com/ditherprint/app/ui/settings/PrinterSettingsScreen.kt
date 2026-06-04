package com.ditherprint.app.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BatteryUnknown
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ditherprint.app.printer.PhomemoBleManager
import com.ditherprint.app.printer.PhomemoProtocol
import com.ditherprint.app.ui.editor.EditorViewModel

private enum class PendingBluetoothAction {
    StartScan,
    Reconnect,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSettingsScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onScanQr: () -> Unit = {}
) {
    val connectionState by viewModel.bleManager.state.collectAsState()
    val discoveredDevices by viewModel.bleManager.discoveredDevices.collectAsState()
    val bondedDevices by viewModel.bleManager.bondedDevices.collectAsState()
    val printerSettings by viewModel.printerSettings.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    val context = LocalContext.current
    val blePermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(
            blePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var pendingBluetoothAction by remember {
        mutableStateOf<PendingBluetoothAction?>(null)
    }

    var hasScanned by remember { mutableStateOf(false) }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            viewModel.bleManager.loadBondedDevices()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionsGranted = blePermissions.all {
            grants[it] ?: (ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED)
        }
        if (permissionsGranted) {
            when (pendingBluetoothAction) {
                PendingBluetoothAction.StartScan -> {
                    hasScanned = true
                    viewModel.bleManager.startScan()
                }
                PendingBluetoothAction.Reconnect -> {
                    printerSettings.lastPrinterMac?.let(viewModel.bleManager::connectByAddress)
                }
                null -> Unit
            }
        }
        pendingBluetoothAction = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection section
            item {
                Text(
                    "Printer Connection",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Status", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    when (connectionState) {
                                        is PhomemoBleManager.ConnectionState.Connected ->
                                            "Connected: ${(connectionState as PhomemoBleManager.ConnectionState.Connected).deviceName}"
                                        is PhomemoBleManager.ConnectionState.Connecting -> "Connecting..."
                                        is PhomemoBleManager.ConnectionState.Scanning -> "Scanning..."
                                        is PhomemoBleManager.ConnectionState.Error ->
                                            "Error: ${(connectionState as PhomemoBleManager.ConnectionState.Error).message}"
                                        else -> "Disconnected"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (printerSettings.lastPrinterName != null) {
                                    Text(
                                        "Last: ${printerSettings.lastPrinterName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            when (connectionState) {
                                is PhomemoBleManager.ConnectionState.Connected -> {
                                    OutlinedButton(onClick = { viewModel.bleManager.disconnect() }) {
                                        Text("Disconnect")
                                    }
                                }
                                is PhomemoBleManager.ConnectionState.Scanning,
                                is PhomemoBleManager.ConnectionState.Connecting -> {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                                else -> {
                                    Column(horizontalAlignment = Alignment.End) {
                                        // Direct reconnect if we have a saved MAC
                                        if (printerSettings.lastPrinterMac != null) {
                                            Button(onClick = {
                                                if (permissionsGranted) {
                                                    viewModel.bleManager.connectByAddress(printerSettings.lastPrinterMac!!)
                                                } else {
                                                    pendingBluetoothAction = PendingBluetoothAction.Reconnect
                                                    permissionLauncher.launch(blePermissions)
                                                }
                                            }) {
                                                Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                                                Spacer(Modifier.width(4.dp))
                                                Text("Reconnect")
                                            }
                                            Spacer(Modifier.height(4.dp))
                                        }
                                        OutlinedButton(onClick = {
                                            hasScanned = true
                                            if (permissionsGranted) {
                                                viewModel.bleManager.startScan()
                                            } else {
                                                pendingBluetoothAction = PendingBluetoothAction.StartScan
                                                permissionLauncher.launch(blePermissions)
                                            }
                                        }) {
                                            Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Scan")
                                        }
                                        Text(
                                            "Finds nearby Bluetooth devices. Tap one to connect by MAC address.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        OutlinedButton(onClick = onScanQr) {
                                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("QR Code")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Paired (bonded) devices
            if (bondedDevices.isNotEmpty()) {
                item {
                    Text("Paired Devices", style = MaterialTheme.typography.labelMedium)
                }
                items(bondedDevices) { device ->
                    @SuppressLint("MissingPermission")
                    val deviceName = device.name ?: device.address
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.bleManager.connectByAddress(device.address)
                                viewModel.savePrinterConnection(device.address, deviceName)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(deviceName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.Bluetooth, contentDescription = "Connect")
                        }
                    }
                }
            }

            // Discovered devices (from BLE scan)
            if (connectionState is PhomemoBleManager.ConnectionState.Scanning ||
                discoveredDevices.isNotEmpty()
            ) {
                item {
                    Text("Found Devices", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (connectionState is PhomemoBleManager.ConnectionState.Scanning && discoveredDevices.isEmpty()) {
                item {
                    Text(
                        "Scanning for nearby devices…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(discoveredDevices) { device ->
                    @SuppressLint("MissingPermission")
                    val deviceName = device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.bleManager.connectByAddress(device.address)
                                viewModel.savePrinterConnection(device.address, deviceName)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(deviceName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.BluetoothConnected, contentDescription = "Connect")
                        }
                    }
                }
            if (hasScanned &&
                connectionState !is PhomemoBleManager.ConnectionState.Scanning &&
                discoveredDevices.isEmpty()
            ) {
                item {
                    Text(
                        "No devices found. Make sure the printer is on and nearby, then tap Scan again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Printer status section (only when connected)
            if (connectionState is PhomemoBleManager.ConnectionState.Connected) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Printer Status", style = MaterialTheme.typography.titleMedium)
                }

                item {
                    val printerInfo by viewModel.bleManager.printerInfo.collectAsState()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Status Info", style = MaterialTheme.typography.labelMedium)
                                IconButton(onClick = { viewModel.refreshPrinterInfo() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }

                            // Battery
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                val batteryIcon = when {
                                    printerInfo.battery == null -> Icons.AutoMirrored.Filled.BatteryUnknown
                                    printerInfo.battery!! <= 5 -> Icons.Default.Battery0Bar
                                    printerInfo.battery!! <= 25 -> Icons.Default.Battery2Bar
                                    printerInfo.battery!! <= 50 -> Icons.Default.Battery4Bar
                                    printerInfo.battery!! <= 75 -> Icons.Default.Battery5Bar
                                    else -> Icons.Default.BatteryFull
                                }
                                val batteryColor = when {
                                    printerInfo.battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    printerInfo.battery!! <= 10 -> MaterialTheme.colorScheme.error
                                    printerInfo.battery!! <= 25 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Icon(
                                    batteryIcon,
                                    contentDescription = "Battery",
                                    tint = batteryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Battery: ${printerInfo.battery?.let { "$it%" } ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // Paper
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                val paperColor = when (printerInfo.paper) {
                                    "out" -> MaterialTheme.colorScheme.error
                                    "ok" -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Icon(
                                    Icons.Default.Receipt,
                                    contentDescription = "Paper",
                                    tint = paperColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Paper: ${printerInfo.paper?.replaceFirstChar { it.uppercase() } ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // Firmware
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Firmware",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Firmware: ${printerInfo.firmware ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // Serial
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Tag,
                                    contentDescription = "Serial",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Serial: ${printerInfo.serial ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // Version
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Version",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Version: ${printerInfo.version ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // MAC
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = "MAC",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "MAC: ${printerInfo.mac ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // Density section
            item {
                Spacer(Modifier.height(8.dp))
                Text("Print Density", style = MaterialTheme.typography.titleMedium)
            }

            item {
                var densityExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = densityExpanded,
                    onExpandedChange = { densityExpanded = it }
                ) {
                    OutlinedTextField(
                        value = printerSettings.density.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Density") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = densityExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = densityExpanded,
                        onDismissRequest = { densityExpanded = false }
                    ) {
                        PhomemoProtocol.Density.entries.forEach { density ->
                            DropdownMenuItem(
                                text = { Text(density.label) },
                                onClick = {
                                    viewModel.saveDensity(density)
                                    densityExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Paper size section
            item {
                Spacer(Modifier.height(8.dp))
                Text("Paper Size", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhomemoProtocol.PaperSize.entries.forEach { size ->
                        FilterChip(
                            selected = printerSettings.paperSize == size,
                            onClick = { viewModel.savePaperSize(size) },
                            label = { Text(size.label) }
                        )
                    }
                }
            }

            // Print speed section
            item {
                Spacer(Modifier.height(8.dp))
                Text("Print Speed", style = MaterialTheme.typography.titleMedium)
            }

            item {
                var speedExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = speedExpanded,
                    onExpandedChange = { speedExpanded = it }
                ) {
                    OutlinedTextField(
                        value = printerSettings.printSpeed.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Speed / Quality") },
                        supportingText = { Text("Fast = lighter print, Max Quality = darker & slower") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speedExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = speedExpanded,
                        onDismissRequest = { speedExpanded = false }
                    ) {
                        PhomemoProtocol.PrintSpeed.entries.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text(speed.label) },
                                onClick = {
                                    viewModel.savePrintSpeed(speed)
                                    speedExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Alignment section
            item {
                Spacer(Modifier.height(8.dp))
                Text("Alignment", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhomemoProtocol.Alignment.entries.forEach { alignment ->
                        FilterChip(
                            selected = printerSettings.alignment == alignment,
                            onClick = { viewModel.saveAlignment(alignment) },
                            label = { Text(alignment.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // Paper feed section
            item {
                Spacer(Modifier.height(8.dp))
                Text("Paper Feed", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Column {
                    Text(
                        "Feed after print: ${printerSettings.paperFeed} dots",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = printerSettings.paperFeed.toFloat(),
                        onValueChange = { viewModel.savePaperFeed(it.toInt()) },
                        valueRange = PhomemoProtocol.MIN_PAPER_FEED.toFloat()..PhomemoProtocol.MAX_PAPER_FEED.toFloat(),
                        steps = PhomemoProtocol.MAX_PAPER_FEED - PhomemoProtocol.MIN_PAPER_FEED - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${PhomemoProtocol.MIN_PAPER_FEED}", style = MaterialTheme.typography.bodySmall)
                        Text("${PhomemoProtocol.MAX_PAPER_FEED}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Favorites management
            if (favorites.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Saved Favorites", style = MaterialTheme.typography.titleMedium)
                }

                items(favorites) { fav ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(fav.name, style = MaterialTheme.typography.bodyLarge)
                                    if (fav.isDefault) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = "Default",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    "${fav.settings.algorithm.displayName} | B:${"%.1f".format(fav.settings.brightness)} C:${"%.1f".format(fav.settings.contrast)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                if (!fav.isDefault) {
                                    IconButton(onClick = {
                                        viewModel.setDefaultFavorite(fav.name)
                                    }) {
                                        Icon(
                                            Icons.Default.StarOutline,
                                            contentDescription = "Set as default"
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteFavorite(fav.name) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
