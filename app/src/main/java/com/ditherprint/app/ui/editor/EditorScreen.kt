package com.ditherprint.app.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ditherprint.app.dithering.DitherAlgorithm
import com.ditherprint.app.printer.PhomemoBleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onNavigateToSettings: () -> Unit
) {
    val ditheredBitmap by viewModel.ditheredBitmap.collectAsState()
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val showOriginal by viewModel.showOriginal.collectAsState()
    val algorithm by viewModel.algorithm.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val contrast by viewModel.contrast.collectAsState()
    val invert by viewModel.invert.collectAsState()
    val bayerSize by viewModel.bayerSize.collectAsState()
    val isPrinting by viewModel.isPrinting.collectAsState()
    val printError by viewModel.printError.collectAsState()
    val connectionState by viewModel.bleManager.state.collectAsState()
    val printProgress by viewModel.bleManager.printProgress.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val showSaveDialog by viewModel.showSaveFavoriteDialog.collectAsState()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.loadImage(it) }
    }

    // Print error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(printError) {
        printError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearPrintError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("DitherPrint") },
                actions = {
                    IconButton(onClick = { viewModel.saveToGallery() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save to gallery")
                    }
                    IconButton(onClick = { viewModel.showSaveFavoriteDialog() }) {
                        Icon(Icons.Default.Star, contentDescription = "Save favorite")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            val isConnected = connectionState is PhomemoBleManager.ConnectionState.Connected
            ExtendedFloatingActionButton(
                onClick = {
                    if (isConnected) viewModel.print()
                    else onNavigateToSettings()
                },
                icon = {
                    if (isPrinting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Print, contentDescription = "Print")
                    }
                },
                text = {
                    Text(
                        when {
                            isPrinting -> "Printing... ${((printProgress?.percent ?: 0f) * 100).toInt()}%"
                            isConnected -> "Print"
                            else -> "Connect Printer"
                        }
                    )
                },
                expanded = true
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Image preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bitmapToShow = if (showOriginal) originalBitmap else ditheredBitmap
                if (bitmapToShow != null) {
                    ZoomableImage(
                        bitmap = bitmapToShow,
                        modifier = Modifier.fillMaxSize(),
                        onClick = { viewModel.toggleShowOriginal() }
                    )
                    // Show indicator
                    if (showOriginal) {
                        Badge(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text("Original", modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                } else {
                    // No image loaded yet
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Select Photo")
                        }
                    }
                }
            }

            // Controls panel
            if (originalBitmap != null) {
                ControlsPanel(
                    algorithm = algorithm,
                    brightness = brightness,
                    contrast = contrast,
                    invert = invert,
                    bayerSize = bayerSize,
                    favorites = favorites,
                    onAlgorithmChange = viewModel::setAlgorithm,
                    onBrightnessChange = viewModel::setBrightness,
                    onContrastChange = viewModel::setContrast,
                    onInvertChange = viewModel::setInvert,
                    onBayerSizeChange = viewModel::setBayerSize,
                    onFavoriteSelect = viewModel::applyFavorite,
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }
        }

        // Save favorite dialog
        if (showSaveDialog) {
            SaveFavoriteDialog(
                onDismiss = viewModel::dismissSaveFavoriteDialog,
                onSave = viewModel::saveFavorite
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Dithered preview",
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offset += pan
                }
            }
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None  // Keep pixel-sharp for dithered preview
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsPanel(
    algorithm: DitherAlgorithm,
    brightness: Float,
    contrast: Float,
    invert: Boolean,
    bayerSize: Int,
    favorites: List<com.ditherprint.app.data.SettingsRepository.Favorite>,
    onAlgorithmChange: (DitherAlgorithm) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onInvertChange: (Boolean) -> Unit,
    onBayerSizeChange: (Int) -> Unit,
    onFavoriteSelect: (com.ditherprint.app.data.SettingsRepository.Favorite) -> Unit,
    onPickPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Favorites row
        if (favorites.isNotEmpty()) {
            Text("Favorites", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favorites) { fav ->
                    FilterChip(
                        selected = false,
                        onClick = { onFavoriteSelect(fav) },
                        label = { Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = if (fav.isDefault) {
                            { Icon(Icons.Default.Star, contentDescription = "Default", modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Algorithm dropdown
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = algorithm.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Algorithm") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DitherAlgorithm.entries.forEach { alg ->
                    DropdownMenuItem(
                        text = { Text(alg.displayName) },
                        onClick = {
                            onAlgorithmChange(alg)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Bayer size (only shown for Bayer algorithm)
        if (algorithm == DitherAlgorithm.BAYER) {
            Spacer(Modifier.height(8.dp))
            Text("Bayer Size: ${bayerSize}x${bayerSize}", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 8, 16).forEach { size ->
                    FilterChip(
                        selected = bayerSize == size,
                        onClick = { onBayerSizeChange(size) },
                        label = { Text("${size}x${size}") }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Brightness slider
        Text("Brightness: ${"%.2f".format(brightness)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = brightness,
            onValueChange = onBrightnessChange,
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        // Contrast slider
        Text("Contrast: ${"%.2f".format(contrast)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = contrast,
            onValueChange = onContrastChange,
            valueRange = 0f..2f,
            modifier = Modifier.fillMaxWidth()
        )

        // Invert + Pick Photo row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = invert, onCheckedChange = onInvertChange)
                Text("Invert")
            }
            TextButton(onClick = onPickPhoto) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Change Photo")
            }
        }
    }
}

@Composable
private fun SaveFavoriteDialog(
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var setAsDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Favorite") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = setAsDefault, onCheckedChange = { setAsDefault = it })
                    Text("Set as default (used on share)")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), setAsDefault) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
