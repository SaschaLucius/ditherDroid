package com.ditherprint.app.ui.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    val isLoadingImage by viewModel.isLoadingImage.collectAsState()
    val isDithering by viewModel.isDithering.collectAsState()
    val connectionState by viewModel.bleManager.state.collectAsState()
    val printProgress by viewModel.bleManager.printProgress.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val showSaveDialog by viewModel.showSaveFavoriteDialog.collectAsState()
    val isCropping by viewModel.isCropping.collectAsState()
    val cropRect by viewModel.cropRect.collectAsState()
    val printerInfo by viewModel.bleManager.printerInfo.collectAsState()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.loadImage(it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var overflowOpen by remember { mutableStateOf(false) }
    val isConnected = connectionState is PhomemoBleManager.ConnectionState.Connected
    val hasImage = originalBitmap != null
    val canPrint = isConnected && !isPrinting && ditheredBitmap != null && !isLoadingImage

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("DitherPrint") },
                actions = {
                    if (hasImage) {
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
                            IconButton(onClick = { viewModel.toggleShowOriginal() }) {
                                Icon(
                                    if (showOriginal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showOriginal) "Show dithered" else "Show original"
                                )
                            }
                        }
                    }
                    if (isConnected && printerInfo.battery != null) {
                        val bat = printerInfo.battery!!
                        val batteryIcon = when {
                            bat <= 5 -> Icons.Default.Battery0Bar
                            bat <= 25 -> Icons.Default.Battery2Bar
                            bat <= 50 -> Icons.Default.Battery4Bar
                            bat <= 75 -> Icons.Default.Battery5Bar
                            else -> Icons.Default.BatteryFull
                        }
                        val batteryColor = when {
                            bat <= 10 -> MaterialTheme.colorScheme.error
                            bat <= 25 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Icon(
                            batteryIcon,
                            contentDescription = "Battery $bat%",
                            tint = batteryColor,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Printer settings")
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save to gallery") },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.saveToGallery()
                                },
                                enabled = ditheredBitmap != null
                            )
                            DropdownMenuItem(
                                text = { Text("Save favorite") },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.showSaveFavoriteDialog()
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    when {
                        !isConnected -> onNavigateToSettings()
                        canPrint -> viewModel.print()
                        isPrinting -> Unit
                        else -> {
                            // Message emitted via print preflight or snackbar path
                            viewModel.print()
                        }
                    }
                },
                icon = {
                    if (isPrinting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Print, contentDescription = null)
                    }
                },
                text = {
                    Text(
                        when {
                            isPrinting -> {
                                val pct = ((printProgress?.percent ?: 0f) * 100).toInt()
                                "Printing… $pct%"
                            }
                            isConnected -> "Print"
                            else -> "Connect Printer"
                        }
                    )
                },
                expanded = true,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bitmapToShow = if (showOriginal) originalBitmap else (ditheredBitmap ?: originalBitmap)
                when {
                    isCropping && rawBitmap != null -> {
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
                    }
                    isLoadingImage -> {
                        CircularProgressIndicator()
                    }
                    bitmapToShow != null -> {
                        ZoomableImage(
                            bitmap = bitmapToShow,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (showOriginal) {
                            Badge(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                            ) {
                                Text("Original", modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                        if (isDithering) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            )
                        }
                        if (isPrinting) {
                            val progress = printProgress?.percent ?: 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .semantics {
                                        contentDescription = "Print progress"
                                        stateDescription = "${(progress * 100).toInt()} percent"
                                    }
                            )
                        }
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Start with a photo",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Pick from gallery or take a picture, then dither and print.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            }

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
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Image preview",
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offset += pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
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

    val cropPadding = 24.dp
    val density = LocalDensity.current
    val padPx = with(density) { cropPadding.toPx() }

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
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .semantics {
                contentDescription = "Crop region. Drag handles to adjust."
            }
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

        val currentCropRect by rememberUpdatedState(cropRect)
        var activeEdge by remember { mutableStateOf("") }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val handleTouchRadius = with(density) { 24.dp.toPx() }
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

            drawRect(scrimColor, Offset(imgRect.left, imgRect.top), Size(imgW, cropT - imgRect.top))
            drawRect(scrimColor, Offset(imgRect.left, cropB), Size(imgW, imgRect.bottom - cropB))
            drawRect(scrimColor, Offset(imgRect.left, cropT), Size(cropL - imgRect.left, cropB - cropT))
            drawRect(scrimColor, Offset(cropR, cropT), Size(imgRect.right - cropR, cropB - cropT))

            drawRect(
                borderColor,
                Offset(cropL, cropT),
                Size(cropR - cropL, cropB - cropT),
                style = Stroke(width = 2.dp.toPx())
            )

            val thirdW = (cropR - cropL) / 3f
            val thirdH = (cropB - cropT) / 3f
            val guideColor = Color.White.copy(alpha = 0.3f)
            for (i in 1..2) {
                drawLine(guideColor, Offset(cropL + thirdW * i, cropT), Offset(cropL + thirdW * i, cropB), strokeWidth = 1.dp.toPx())
                drawLine(guideColor, Offset(cropL, cropT + thirdH * i), Offset(cropR, cropT + thirdH * i), strokeWidth = 1.dp.toPx())
            }

            val hr = handleRadius.toPx()
            listOf(
                Offset(cropL, cropT), Offset(cropR, cropT),
                Offset(cropL, cropB), Offset(cropR, cropB)
            ).forEach { center ->
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
            .navigationBarsPadding()
            .padding(bottom = 88.dp)
    ) {
        if (favorites.isNotEmpty()) {
            Text(
                "Favorites",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { heading() }
            )
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
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
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
            LabeledSlider(
                label = "Bayer Amount",
                value = bayerScale,
                display = "%.2f".format(bayerScale),
                range = 0f..2f,
                onChange = onBayerScaleChange
            )
        }

        if (algorithm == DitherAlgorithm.THRESHOLD) {
            Spacer(Modifier.height(8.dp))
            LabeledSlider(
                label = "Threshold",
                value = threshold,
                display = "${(threshold * 100).toInt()}%",
                range = 0f..1f,
                onChange = onThresholdChange
            )
        }

        if (algorithm.kernel != null) {
            Spacer(Modifier.height(8.dp))
            LabeledSlider(
                label = "Error Strength",
                value = errorDiffusionStrength,
                display = "${(errorDiffusionStrength * 100).toInt()}%",
                range = 0f..1f,
                onChange = onErrorDiffusionStrengthChange
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSerpentineChange(!serpentine) }
            ) {
                Checkbox(checked = serpentine, onCheckedChange = onSerpentineChange)
                Text("Serpentine scanning")
            }
        }

        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Exposure",
            value = gamma,
            display = "%.2f".format(gamma),
            range = 0.5f..2.5f,
            onChange = onGammaChange
        )
        LabeledSlider(
            label = "Brightness",
            value = brightness,
            display = "%.2f".format(brightness),
            range = -1f..1f,
            onChange = onBrightnessChange
        )
        LabeledSlider(
            label = "Contrast",
            value = contrast,
            display = "%.2f".format(contrast),
            range = 0f..2f,
            onChange = onContrastChange
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onInvertChange(!invert) }
            ) {
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
private fun LabeledSlider(
    label: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Text("$label: $display", style = MaterialTheme.typography.labelMedium)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = display
            }
    )
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { setAsDefault = !setAsDefault }
                ) {
                    Checkbox(checked = setAsDefault, onCheckedChange = { setAsDefault = it })
                    Text("Set as default (used on launch)")
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
