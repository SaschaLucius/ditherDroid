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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSettingsScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val connectionState by viewModel.bleManager.state.collectAsState()
    val discoveredDevices by viewModel.bleManager.discoveredDevices.collectAsState()
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionsGranted = grants.values.all { it }
        if (permissionsGranted) {
            viewModel.bleManager.startScan()
        }
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
                                is PhomemoBleManager.ConnectionState.Scanning -> {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                                else -> {
                                    Button(onClick = {
                                        if (permissionsGranted) {
                                            viewModel.bleManager.startScan()
                                        } else {
                                            permissionLauncher.launch(blePermissions)
                                        }
                                    }) {
                                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Scan")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Discovered devices
            if (discoveredDevices.isNotEmpty()) {
                item {
                    Text("Found Devices", style = MaterialTheme.typography.labelMedium)
                }
                items(discoveredDevices) { device ->
                    @SuppressLint("MissingPermission")
                    val deviceName = device.name ?: device.address
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.bleManager.connect(device)
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
                            .menuAnchor()
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
