package com.ditherprint.app.ui.camera

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ditherprint.app.dithering.DitherAlgorithm
import com.ditherprint.app.dithering.DitherEngine
import com.ditherprint.app.ui.editor.EditorViewModel
import com.ditherprint.app.util.BitmapUtils
import com.ditherprint.app.util.ImageProxyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onCapture: () -> Unit
) {
    val context = LocalContext.current

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
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
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

    DisposableEffect(Unit) {
        onDispose {
            ditheredPreview?.let { if (!it.isRecycled) it.recycle() }
            rawCapture?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            val ready = rawCapture != null && !rawCapture!!.isRecycled
            FloatingActionButton(
                onClick = {
                    val frame = rawCapture
                    if (frame == null || frame.isRecycled) {
                        // Handled by LaunchedEffect below via flag
                        return@FloatingActionButton
                    }
                    viewModel.loadBitmap(frame.copy(Bitmap.Config.ARGB_8888, false))
                    onCapture()
                },
                containerColor = if (ready) {
                    FloatingActionButtonDefaults.containerColor
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.navigationBarsPadding()
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
            }
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
                when {
                    !hasCameraPermission -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Camera permission required")
                            Spacer(Modifier.height(8.dp))
                            if (permissionDenied) {
                                Button(onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        )
                                    )
                                }) { Text("Open Settings") }
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }) { Text("Try again") }
                            }
                        }
                    }
                    cameraError != null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(cameraError!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { cameraError = null }) { Text("Retry") }
                        }
                    }
                    else -> {
                        UnifiedCameraPreview(
                            showDithered = showDithered,
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
                            ditheredPreview = ditheredPreview,
                            onFrameDithered = { new ->
                                val old = ditheredPreview
                                ditheredPreview = new
                                BitmapUtils.recycleIfDifferent(old, new)
                            },
                            onRawFrame = { new ->
                                val old = rawCapture
                                rawCapture = new
                                BitmapUtils.recycleIfDifferent(old, new)
                            },
                            onError = { cameraError = it }
                        )
                        if (rawCapture == null) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

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

/**
 * Single CameraX bind lifecycle for both dithered and raw preview modes.
 * Always runs ImageAnalysis for capture frames; PreviewView is used when not dithered.
 */
@Composable
private fun UnifiedCameraPreview(
    showDithered: Boolean,
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
    ditheredPreview: Bitmap?,
    onFrameDithered: (Bitmap) -> Unit,
    onRawFrame: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val isProcessing = remember { AtomicBoolean(false) }
    val latestRaw = remember { AtomicReference<Bitmap?>(null) }

    val currentAlgorithm by rememberUpdatedState(algorithm)
    val currentBrightness by rememberUpdatedState(brightness)
    val currentContrast by rememberUpdatedState(contrast)
    val currentInvert by rememberUpdatedState(invert)
    val currentBayerSize by rememberUpdatedState(bayerSize)
    val currentBayerScale by rememberUpdatedState(bayerScale)
    val currentThreshold by rememberUpdatedState(threshold)
    val currentGamma by rememberUpdatedState(gamma)
    val currentErrorStrength by rememberUpdatedState(errorDiffusionStrength)
    val currentSerpentine by rememberUpdatedState(serpentine)

    DisposableEffect(lifecycleOwner, showDithered) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                    if (!isProcessing.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    try {
                        val bitmap = ImageProxyUtils.imageProxyToBitmap(imageProxy)
                        imageProxy.close()
                        if (bitmap == null) return@setAnalyzer

                        val previous = latestRaw.getAndSet(bitmap)
                        BitmapUtils.recycleIfDifferent(previous, bitmap)
                        // Hand a copy to UI so recycler ownership stays in this analyzer
                        onRawFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false))

                        val targetWidth = 280
                        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                        val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
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
                            errorDiffusionStrength = currentErrorStrength,
                            serpentine = currentSerpentine
                        )
                        if (scaled !== bitmap) scaled.recycle()
                        onFrameDithered(dithered)
                    } catch (_: Exception) {
                    } finally {
                        isProcessing.set(false)
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()

                if (showDithered) {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                } else {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageAnalysis
                    )
                }
            } catch (e: Exception) {
                onError(e.message ?: "Camera failed to start")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            latestRaw.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    if (showDithered) {
        if (ditheredPreview != null && !ditheredPreview.isRecycled) {
            Image(
                bitmap = ditheredPreview.asImageBitmap(),
                contentDescription = "Dithered camera preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        }
    } else {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
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
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .padding(bottom = 72.dp)
    ) {
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

        if (algorithm.kernel != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Error Strength: ${(errorDiffusionStrength * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
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
        Text("Exposure: ${"%.2f".format(gamma)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = gamma,
            onValueChange = onGammaChange,
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Brightness: ${"%.2f".format(brightness)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = brightness,
            onValueChange = onBrightnessChange,
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Contrast: ${"%.2f".format(contrast)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = contrast,
            onValueChange = onContrastChange,
            valueRange = 0f..2f,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = invert, onCheckedChange = onInvertChange)
            Text("Invert")
        }
    }
}
