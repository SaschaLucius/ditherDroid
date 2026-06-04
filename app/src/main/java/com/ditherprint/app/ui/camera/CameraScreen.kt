package com.ditherprint.app.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ditherprint.app.dithering.DitherAlgorithm
import com.ditherprint.app.dithering.DitherEngine
import com.ditherprint.app.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onCapture: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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

    var ditheredPreview by remember { mutableStateOf<Bitmap?>(null) }
    var rawCapture by remember { mutableStateOf<Bitmap?>(null) }
    var showDithered by remember { mutableStateOf(true) }
    var hasCameraPermission by remember { mutableStateOf(false) }

    // Permission check
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDithered = !showDithered }) {
                        Icon(
                            if (showDithered) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle dither preview"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // Send the raw undithered frame to the editor so it re-dithers at printer resolution
                rawCapture?.let { bitmap ->
                    viewModel.loadBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    onCapture()
                }
            }) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!hasCameraPermission) {
                    Text("Camera permission required")
                } else if (showDithered) {
                    DitheredCameraPreview(
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
                        onFrameDithered = { ditheredPreview = it },
                        onRawFrame = { rawCapture = it }
                    )
                } else {
                    RawCameraPreview(
                        onFrameDithered = { ditheredPreview = it },
                        onRawFrame = { rawCapture = it },
                        algorithm = algorithm,
                        brightness = brightness,
                        contrast = contrast,
                        invert = invert,
                        bayerSize = bayerSize,
                        bayerScale = bayerScale,
                        threshold = threshold,
                        gamma = gamma,
                        errorDiffusionStrength = errorDiffusionStrength,
                        serpentine = serpentine
                    )
                }
            }

            // Controls panel
            CameraControlsPanel(
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
                onAlgorithmChange = viewModel::setAlgorithm,
                onBrightnessChange = viewModel::setBrightness,
                onContrastChange = viewModel::setContrast,
                onInvertChange = viewModel::setInvert,
                onBayerSizeChange = viewModel::setBayerSize,
                onBayerScaleChange = viewModel::setBayerScale,
                onThresholdChange = viewModel::setThreshold,
                onGammaChange = viewModel::setGamma,
                onErrorDiffusionStrengthChange = viewModel::setErrorDiffusionStrength,
                onSerpentineChange = viewModel::setSerpentine
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraControlsPanel(
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
    onAlgorithmChange: (DitherAlgorithm) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onInvertChange: (Boolean) -> Unit,
    onBayerSizeChange: (Int) -> Unit,
    onBayerScaleChange: (Float) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onGammaChange: (Float) -> Unit,
    onErrorDiffusionStrengthChange: (Float) -> Unit,
    onSerpentineChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
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

        // Bayer size (only for Bayer algorithm)
        if (algorithm == DitherAlgorithm.BAYER) {
            Spacer(Modifier.height(4.dp))
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
            Text("Amount: ${"%.2f".format(bayerScale)}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = bayerScale,
                onValueChange = onBayerScaleChange,
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Threshold (only for Threshold algorithm)
        if (algorithm == DitherAlgorithm.THRESHOLD) {
            Spacer(Modifier.height(4.dp))
            Text("Threshold: ${(threshold * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Error diffusion controls
        if (algorithm.kernel != null) {
            Spacer(Modifier.height(4.dp))
            Text("Error Strength: ${(errorDiffusionStrength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = errorDiffusionStrength,
                onValueChange = onErrorDiffusionStrengthChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = serpentine, onCheckedChange = onSerpentineChange)
                Text("Serpentine")
            }
        }

        Spacer(Modifier.height(4.dp))

        // Exposure
        Text("Exposure: ${"%.2f".format(gamma)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = gamma,
            onValueChange = onGammaChange,
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth()
        )

        // Brightness
        Text("Brightness: ${"%.2f".format(brightness)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = brightness,
            onValueChange = onBrightnessChange,
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        // Contrast
        Text("Contrast: ${"%.2f".format(contrast)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = contrast,
            onValueChange = onContrastChange,
            valueRange = 0f..2f,
            modifier = Modifier.fillMaxWidth()
        )

        // Invert
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = invert, onCheckedChange = onInvertChange)
            Text("Invert")
        }
    }
}

@Composable
private fun DitheredCameraPreview(
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
    onFrameDithered: (Bitmap) -> Unit,
    onRawFrame: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val isProcessing = remember { AtomicBoolean(false) }

    // Remember current dither params for use in analyzer
    val currentAlgorithm by rememberUpdatedState(algorithm)
    val currentBrightness by rememberUpdatedState(brightness)
    val currentContrast by rememberUpdatedState(contrast)
    val currentInvert by rememberUpdatedState(invert)
    val currentBayerSize by rememberUpdatedState(bayerSize)
    val currentBayerScale by rememberUpdatedState(bayerScale)
    val currentThreshold by rememberUpdatedState(threshold)
    val currentGamma by rememberUpdatedState(gamma)
    val currentErrorDiffusionStrength by rememberUpdatedState(errorDiffusionStrength)
    val currentSerpentine by rememberUpdatedState(serpentine)

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val provider = cameraProvider ?: return@addListener

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                if (!isProcessing.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val bitmap = imageProxyToBitmap(imageProxy)
                imageProxy.close()

                if (bitmap != null) {
                    // Keep full-res raw frame for capture
                    onRawFrame(bitmap)

                    // Downscale for live dither preview performance
                    val targetWidth = 280
                    val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                    val targetHeight = (targetWidth * aspect).toInt()
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

                    val dithered = DitherEngine.process(
                        source = scaled,
                        algorithm = currentAlgorithm,
                        brightness = currentBrightness,
                        contrast = currentContrast,
                        invert = currentInvert,
                        bayerSize = currentBayerSize,
                        bayerScale = currentBayerScale,
                        threshold = currentThreshold,
                        gamma = currentGamma,
                        errorDiffusionStrength = currentErrorDiffusionStrength,
                        serpentine = currentSerpentine
                    )
                    scaled.recycle()

                    displayBitmap = dithered
                    onFrameDithered(dithered)
                }
                isProcessing.set(false)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    displayBitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Dithered camera preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
    }
}

@Composable
private fun RawCameraPreview(
    onFrameDithered: (Bitmap) -> Unit,
    onRawFrame: (Bitmap) -> Unit,
    algorithm: DitherAlgorithm,
    brightness: Float,
    contrast: Float,
    invert: Boolean,
    bayerSize: Int,
    bayerScale: Float,
    threshold: Float,
    gamma: Float,
    errorDiffusionStrength: Float,
    serpentine: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isProcessing = remember { AtomicBoolean(false) }

    val currentAlgorithm by rememberUpdatedState(algorithm)
    val currentBrightness by rememberUpdatedState(brightness)
    val currentContrast by rememberUpdatedState(contrast)
    val currentInvert by rememberUpdatedState(invert)
    val currentBayerSize by rememberUpdatedState(bayerSize)
    val currentBayerScale by rememberUpdatedState(bayerScale)
    val currentThreshold by rememberUpdatedState(threshold)
    val currentGamma by rememberUpdatedState(gamma)
    val currentErrorDiffusionStrength by rememberUpdatedState(errorDiffusionStrength)
    val currentSerpentine by rememberUpdatedState(serpentine)

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                // Still dither in background so capture button has a frame ready
                imageAnalysis.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                    if (!isProcessing.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val bitmap = imageProxyToBitmap(imageProxy)
                    imageProxy.close()

                    if (bitmap != null) {
                        onRawFrame(bitmap)

                        val targetWidth = 280
                        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                        val targetHeight = (targetWidth * aspect).toInt()
                        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

                        val dithered = DitherEngine.process(
                            source = scaled,
                            algorithm = currentAlgorithm,
                            brightness = currentBrightness,
                            contrast = currentContrast,
                            invert = currentInvert,
                            bayerSize = currentBayerSize,
                            bayerScale = currentBayerScale,
                            threshold = currentThreshold,
                            gamma = currentGamma,
                            errorDiffusionStrength = currentErrorDiffusionStrength,
                            serpentine = currentSerpentine
                        )
                        scaled.recycle()
                        onFrameDithered(dithered)
                    }
                    isProcessing.set(false)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageAnalysis
                    )
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    val plane = imageProxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val width = imageProxy.width
    val height = imageProxy.height

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    bitmap.copyPixelsFromBuffer(buffer)

    // Apply rotation
    val rotation = imageProxy.imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
    return bitmap
}
