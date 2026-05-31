package com.ditherprint.app.ui.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.ditherprint.app.dithering.DitherAlgorithm
import com.ditherprint.app.printer.PhomemoBleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToCamera: () -> Unit
) {
    val ditheredBitmap by viewModel.ditheredBitmap.collectAsState()
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val rawBitmap by viewModel.rawBitmap.collectAsState()
    val showOriginal by viewModel.showOriginal.collectAsState()
    val algorithm by viewModel.algorithm.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val contrast by viewModel.contrast.collectAsState()
    val invert by viewModel.invert.collectAsState()
    val bayerSize by viewModel.bayerSize.collectAsState()
    val bayerScale by viewModel.bayerScale.collectAsState()
    val threshold by viewModel.threshold.collectAsState()
    val gamma by viewModel.gamma.collectAsState()
    val errorDiffusionStrength by viewModel.errorDiffusionStrength.collectAsState()
    val serpentine by viewModel.serpentine.collectAsState()
    val isPrinting by viewModel.isPrinting.collectAsState()
    val printError by viewModel.printError.collectAsState()
    val connectionState by viewModel.bleManager.state.collectAsState()
    val printProgress by viewModel.bleManager.printProgress.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val showSaveDialog by viewModel.showSaveFavoriteDialog.collectAsState()
    val isCropping by viewModel.isCropping.collectAsState()
    val cropRect by viewModel.cropRect.collectAsState()

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
                    if (originalBitmap != null) {
                        if (isCropping) {
                            IconButton(onClick = { viewModel.applyCrop() }) {
                                Icon(Icons.Default.Check, contentDescription = "Apply crop")
                            }
                            IconButton(onClick = { viewModel.resetCrop() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel crop")
                            }
                        } else {
                            IconButton(onClick = { viewModel.toggleCropMode() }) {
                                Icon(Icons.Default.Crop, contentDescription = "Crop")
                            }
                        }
                    }
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
                val bitmapToShow = if (showOriginal) originalBitmap else (ditheredBitmap ?: originalBitmap)
                if (isCropping && rawBitmap != null) {
                    CroppableImage(
                        bitmap = rawBitmap!!,
                        cropRect = cropRect,
                        onCropRectChange = { viewModel.updateCropRect(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text("Crop", modifier = Modifier.padding(horizontal = 4.dp))
                    }
                } else if (bitmapToShow != null) {
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
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onNavigateToCamera) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Camera")
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
                    bayerScale = bayerScale,
                    threshold = threshold,
                    gamma = gamma,
                    errorDiffusionStrength = errorDiffusionStrength,
                    serpentine = serpentine,
                    favorites = favorites,
                    onAlgorithmChange = viewModel::setAlgorithm,
                    onBrightnessChange = viewModel::setBrightness,
                    onContrastChange = viewModel::setContrast,
                    onInvertChange = viewModel::setInvert,
                    onBayerSizeChange = viewModel::setBayerSize,
                    onBayerScaleChange = viewModel::setBayerScale,
                    onThresholdChange = viewModel::setThreshold,
                    onGammaChange = viewModel::setGamma,
                    onErrorDiffusionStrengthChange = viewModel::setErrorDiffusionStrength,
                    onSerpentineChange = viewModel::setSerpentine,
                    onFavoriteSelect = viewModel::applyFavorite,
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onCamera = onNavigateToCamera
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

@Composable
private fun CroppableImage(
    bitmap: Bitmap,
    cropRect: RectF,
    onCropRectChange: (RectF) -> Unit,
    modifier: Modifier = Modifier
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scrimColor = Color.Black.copy(alpha = 0.5f)
    val handleColor = Color.White
    val borderColor = Color.White
    val handleRadius = 12.dp

    // Inset so crop handles at edges remain touchable
    val cropPadding = 24.dp
    val density = LocalDensity.current
    val padPx = with(density) { cropPadding.toPx() }

    // Compute the image bounds within the container (with padding for handles)
    fun imageRect(): Rect {
        if (containerSize == IntSize.Zero) return Rect.Zero
        val containerW = containerSize.width.toFloat() - padPx * 2
        val containerH = containerSize.height.toFloat() - padPx * 2
        if (containerW <= 0 || containerH <= 0) return Rect.Zero
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val containerAspect = containerW / containerH

        return if (bitmapAspect > containerAspect) {
            val drawW = containerW
            val drawH = containerW / bitmapAspect
            val top = padPx + (containerH - drawH) / 2f
            Rect(padPx, top, padPx + drawW, top + drawH)
        } else {
            val drawH = containerH
            val drawW = containerH * bitmapAspect
            val left = padPx + (containerW - drawW) / 2f
            Rect(left, padPx, left + drawW, padPx + drawH)
        }
    }

    Box(
        modifier = modifier.onSizeChanged { containerSize = it }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Crop preview",
            modifier = Modifier
                .fillMaxSize()
                .padding(cropPadding),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )

        // Crop overlay
        val currentCropRect by rememberUpdatedState(cropRect)

        // Which edge/corner is being dragged; locked at drag-start so recomposition doesn't break it
        var activeEdge by remember { mutableStateOf("") }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val handleTouchRadius = 48f
                    detectDragGestures(
                        onDragStart = { touchPos ->
                            val imgRect = imageRect()
                            if (imgRect == Rect.Zero) return@detectDragGestures
                            val imgW = imgRect.width
                            val imgH = imgRect.height
                            val cr = currentCropRect
                            val cropL = imgRect.left + cr.left * imgW
                            val cropT = imgRect.top + cr.top * imgH
                            val cropR = imgRect.left + cr.right * imgW
                            val cropB = imgRect.top + cr.bottom * imgH

                            val nearLeft = kotlin.math.abs(touchPos.x - cropL) < handleTouchRadius
                            val nearRight = kotlin.math.abs(touchPos.x - cropR) < handleTouchRadius
                            val nearTop = kotlin.math.abs(touchPos.y - cropT) < handleTouchRadius
                            val nearBottom = kotlin.math.abs(touchPos.y - cropB) < handleTouchRadius

                            activeEdge = when {
                                nearLeft && nearTop -> "TL"
                                nearRight && nearTop -> "TR"
                                nearLeft && nearBottom -> "BL"
                                nearRight && nearBottom -> "BR"
                                nearLeft -> "L"
                                nearRight -> "R"
                                nearTop -> "T"
                                nearBottom -> "B"
                                touchPos.x in cropL..cropR && touchPos.y in cropT..cropB -> "MOVE"
                                else -> ""
                            }
                        },
                        onDragEnd = { activeEdge = "" },
                        onDragCancel = { activeEdge = "" }
                    ) { change, dragAmount ->
                        change.consume()
                        if (activeEdge.isEmpty()) return@detectDragGestures
                        val imgRect = imageRect()
                        if (imgRect == Rect.Zero) return@detectDragGestures
                        val imgW = imgRect.width
                        val imgH = imgRect.height
                        val dx = dragAmount.x / imgW
                        val dy = dragAmount.y / imgH
                        val cr = currentCropRect
                        val newRect = RectF(cr.left, cr.top, cr.right, cr.bottom)
                        val minSize = 0.05f

                        when (activeEdge) {
                            "TL" -> {
                                newRect.left = (newRect.left + dx).coerceIn(0f, newRect.right - minSize)
                                newRect.top = (newRect.top + dy).coerceIn(0f, newRect.bottom - minSize)
                            }
                            "TR" -> {
                                newRect.right = (newRect.right + dx).coerceIn(newRect.left + minSize, 1f)
                                newRect.top = (newRect.top + dy).coerceIn(0f, newRect.bottom - minSize)
                            }
                            "BL" -> {
                                newRect.left = (newRect.left + dx).coerceIn(0f, newRect.right - minSize)
                                newRect.bottom = (newRect.bottom + dy).coerceIn(newRect.top + minSize, 1f)
                            }
                            "BR" -> {
                                newRect.right = (newRect.right + dx).coerceIn(newRect.left + minSize, 1f)
                                newRect.bottom = (newRect.bottom + dy).coerceIn(newRect.top + minSize, 1f)
                            }
                            "L" -> newRect.left = (newRect.left + dx).coerceIn(0f, newRect.right - minSize)
                            "R" -> newRect.right = (newRect.right + dx).coerceIn(newRect.left + minSize, 1f)
                            "T" -> newRect.top = (newRect.top + dy).coerceIn(0f, newRect.bottom - minSize)
                            "B" -> newRect.bottom = (newRect.bottom + dy).coerceIn(newRect.top + minSize, 1f)
                            "MOVE" -> {
                                val w = newRect.width()
                                val h = newRect.height()
                                newRect.left = (newRect.left + dx).coerceIn(0f, 1f - w)
                                newRect.top = (newRect.top + dy).coerceIn(0f, 1f - h)
                                newRect.right = newRect.left + w
                                newRect.bottom = newRect.top + h
                            }
                        }
                        onCropRectChange(newRect)
                    }
                }
        ) {
            val imgRect = imageRect()
            if (imgRect == Rect.Zero) return@Canvas
            val imgW = imgRect.width
            val imgH = imgRect.height

            val cropL = imgRect.left + cropRect.left * imgW
            val cropT = imgRect.top + cropRect.top * imgH
            val cropR = imgRect.left + cropRect.right * imgW
            val cropB = imgRect.top + cropRect.bottom * imgH

            // Draw scrim outside crop area
            // Top
            drawRect(scrimColor, Offset(imgRect.left, imgRect.top), Size(imgW, cropT - imgRect.top))
            // Bottom
            drawRect(scrimColor, Offset(imgRect.left, cropB), Size(imgW, imgRect.bottom - cropB))
            // Left
            drawRect(scrimColor, Offset(imgRect.left, cropT), Size(cropL - imgRect.left, cropB - cropT))
            // Right
            drawRect(scrimColor, Offset(cropR, cropT), Size(imgRect.right - cropR, cropB - cropT))

            // Draw crop border
            drawRect(
                borderColor,
                Offset(cropL, cropT),
                Size(cropR - cropL, cropB - cropT),
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw rule-of-thirds lines
            val thirdW = (cropR - cropL) / 3f
            val thirdH = (cropB - cropT) / 3f
            val guideColor = Color.White.copy(alpha = 0.3f)
            for (i in 1..2) {
                drawLine(guideColor, Offset(cropL + thirdW * i, cropT), Offset(cropL + thirdW * i, cropB), strokeWidth = 1.dp.toPx())
                drawLine(guideColor, Offset(cropL, cropT + thirdH * i), Offset(cropR, cropT + thirdH * i), strokeWidth = 1.dp.toPx())
            }

            // Draw corner handles
            val hr = handleRadius.toPx()
            val corners = listOf(
                Offset(cropL, cropT), Offset(cropR, cropT),
                Offset(cropL, cropB), Offset(cropR, cropB)
            )
            corners.forEach { center ->
                drawCircle(handleColor, hr, center)
                drawCircle(Color.Black, hr, center, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsPanel(
    algorithm: DitherAlgorithm,
    brightness: Float,
    contrast: Float,
    invert: Boolean,
    bayerSize: Int,
    bayerScale: Float,
    threshold: Float,
    gamma: Float,
    errorDiffusionStrength: Float,
    serpentine: Boolean,
    favorites: List<com.ditherprint.app.data.SettingsRepository.Favorite>,
    onAlgorithmChange: (DitherAlgorithm) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onInvertChange: (Boolean) -> Unit,
    onBayerSizeChange: (Int) -> Unit,
    onBayerScaleChange: (Float) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onGammaChange: (Float) -> Unit,
    onErrorDiffusionStrengthChange: (Float) -> Unit,
    onSerpentineChange: (Boolean) -> Unit,
    onFavoriteSelect: (com.ditherprint.app.data.SettingsRepository.Favorite) -> Unit,
    onPickPhoto: () -> Unit,
    onCamera: () -> Unit
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
            Spacer(Modifier.height(4.dp))
            Text("Bayer Amount: ${"%.2f".format(bayerScale)}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = bayerScale,
                onValueChange = onBayerScaleChange,
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Threshold (only shown for Threshold algorithm)
        if (algorithm == DitherAlgorithm.THRESHOLD) {
            Spacer(Modifier.height(8.dp))
            Text("Threshold: ${(threshold * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Error diffusion controls (for algorithms with a kernel)
        if (algorithm.kernel != null) {
            Spacer(Modifier.height(8.dp))
            Text("Error Strength: ${(errorDiffusionStrength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = errorDiffusionStrength,
                onValueChange = onErrorDiffusionStrengthChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = serpentine, onCheckedChange = onSerpentineChange)
                Text("Serpentine scanning")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Gamma / exposure slider
        Text("Exposure: ${"%.2f".format(gamma)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = gamma,
            onValueChange = onGammaChange,
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth()
        )

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

        // Invert + Camera + Pick Photo row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = invert, onCheckedChange = onInvertChange)
                Text("Invert")
            }
            TextButton(onClick = onCamera) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Camera")
            }
            TextButton(onClick = onPickPhoto) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Photo")
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
