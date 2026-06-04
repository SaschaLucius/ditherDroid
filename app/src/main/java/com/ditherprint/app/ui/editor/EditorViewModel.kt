package com.ditherprint.app.ui.editor

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ditherprint.app.data.SettingsRepository
import com.ditherprint.app.dithering.DitherAlgorithm
import com.ditherprint.app.dithering.DitherEngine
import com.ditherprint.app.printer.PhomemoBleManager
import com.ditherprint.app.printer.PhomemoProtocol
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

private const val TAG = "CropDebug"

@OptIn(FlowPreview::class)
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepo = SettingsRepository(application)
    val bleManager = PhomemoBleManager(application)

    // Dither parameters
    private val _algorithm = MutableStateFlow(DitherAlgorithm.FLOYD_STEINBERG)
    val algorithm: StateFlow<DitherAlgorithm> = _algorithm

    private val _brightness = MutableStateFlow(0f)
    val brightness: StateFlow<Float> = _brightness

    private val _contrast = MutableStateFlow(1f)
    val contrast: StateFlow<Float> = _contrast

    private val _invert = MutableStateFlow(false)
    val invert: StateFlow<Boolean> = _invert

    private val _bayerSize = MutableStateFlow(4)
    val bayerSize: StateFlow<Int> = _bayerSize

    private val _bayerScale = MutableStateFlow(1f)
    val bayerScale: StateFlow<Float> = _bayerScale

    private val _threshold = MutableStateFlow(0.5f)
    val threshold: StateFlow<Float> = _threshold

    private val _gamma = MutableStateFlow(1.5f)
    val gamma: StateFlow<Float> = _gamma

    private val _errorDiffusionStrength = MutableStateFlow(1f)
    val errorDiffusionStrength: StateFlow<Float> = _errorDiffusionStrength

    private val _serpentine = MutableStateFlow(false)
    val serpentine: StateFlow<Boolean> = _serpentine

    // Image state
    private val _rawBitmap = MutableStateFlow<Bitmap?>(null)
    val rawBitmap: StateFlow<Bitmap?> = _rawBitmap

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap

    private val _ditheredBitmap = MutableStateFlow<Bitmap?>(null)
    val ditheredBitmap: StateFlow<Bitmap?> = _ditheredBitmap

    private val _showOriginal = MutableStateFlow(false)
    val showOriginal: StateFlow<Boolean> = _showOriginal

    // Crop state (normalized 0..1 rect)
    private val _isCropping = MutableStateFlow(false)
    val isCropping: StateFlow<Boolean> = _isCropping

    private val _cropRect = MutableStateFlow(RectF(0f, 0f, 1f, 1f))
    val cropRect: StateFlow<RectF> = _cropRect

    // Printer state
    private val _isPrinting = MutableStateFlow(false)
    val isPrinting: StateFlow<Boolean> = _isPrinting

    private val _printError = MutableStateFlow<String?>(null)
    val printError: StateFlow<String?> = _printError

    // Favorites
    val favorites = settingsRepo.favorites.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )
    val printerSettings = settingsRepo.printerSettings.stateIn(
        viewModelScope, SharingStarted.Lazily, SettingsRepository.PrinterSettings()
    )

    // Save favorite dialog
    private val _showSaveFavoriteDialog = MutableStateFlow(false)
    val showSaveFavoriteDialog: StateFlow<Boolean> = _showSaveFavoriteDialog

    private var ditherJob: Job? = null

    init {
        // Load default settings
        viewModelScope.launch {
            settingsRepo.defaultDitherSettings.first().let { defaults ->
                _algorithm.value = defaults.algorithm
                _brightness.value = defaults.brightness
                _contrast.value = defaults.contrast
                _invert.value = defaults.invert
                _bayerSize.value = defaults.bayerSize
            }
        }

        // Auto-connect to last known printer
        viewModelScope.launch {
            settingsRepo.printerSettings.first().let { settings ->
                val mac = settings.lastPrinterMac
                if (!mac.isNullOrBlank()) {
                    bleManager.connectByAddress(mac)
                }
            }
        }

        // Auto-query printer info when connection is established
        viewModelScope.launch {
            bleManager.state.collect { state ->
                if (state is PhomemoBleManager.ConnectionState.Connected) {
                    delay(500) // Give the printer a moment to stabilize
                    bleManager.queryPrinterInfo()
                }
            }
        }

        // Re-scale image when paper size changes
        viewModelScope.launch {
            printerSettings
                .map { it.paperSize }
                .distinctUntilChanged()
                .drop(1) // skip initial value
                .collect { applyCropAndResize() }
        }

        // Auto-re-dither when parameters change (debounced)
        viewModelScope.launch {
            @Suppress("INFERRED_TYPE_VARIABLE_INTO_POSSIBLE_EMPTY_INTERSECTION")
            combine(
                _algorithm, _brightness, _contrast, _invert, _bayerSize,
                _bayerScale, _threshold, _gamma, _errorDiffusionStrength,
                _serpentine
            ) { values ->
                values // just trigger the combine
            }.combine(_originalBitmap) { _, _ -> Unit
            }.debounce(150).collect {
                Log.d(TAG, "redither triggered by combine flow. originalBitmap=${_originalBitmap.value?.width}x${_originalBitmap.value?.height}")
                redither()
            }
        }
    }

    fun loadImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // Two-pass decode: get dimensions first, then downsample
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open image")
                boundsStream.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
                // decodeStream returns null intentionally with inJustDecodeBounds=true
                if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                    throw IOException("Cannot read image dimensions")
                }

                // Calculate inSampleSize to avoid OOM on large images
                val maxDim = 4096
                var sampleSize = 1
                while (boundsOptions.outWidth / sampleSize > maxDim || boundsOptions.outHeight / sampleSize > maxDim) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                } ?: throw IOException("Cannot decode image")

                _rawBitmap.value = bitmap
                _cropRect.value = RectF(0f, 0f, 1f, 1f)
                _isCropping.value = false
                applyCropAndResize()
            } catch (e: Exception) {
                _printError.value = "Failed to load image: ${e.message}"
            }
        }
    }

    fun loadBitmap(bitmap: Bitmap) {
        _rawBitmap.value = bitmap
        _cropRect.value = RectF(0f, 0f, 1f, 1f)
        _isCropping.value = false
        applyCropAndResize()
    }

    private fun applyCropAndResize() {
        val raw = _rawBitmap.value ?: return
        if (raw.isRecycled) return
        val crop = _cropRect.value
        val paperSize = printerSettings.value.paperSize

        val x = (crop.left * raw.width).toInt().coerceIn(0, raw.width - 1)
        val y = (crop.top * raw.height).toInt().coerceIn(0, raw.height - 1)
        val w = ((crop.right - crop.left) * raw.width).toInt().coerceIn(1, raw.width - x)
        val h = ((crop.bottom - crop.top) * raw.height).toInt().coerceIn(1, raw.height - y)

        val cropped = Bitmap.createBitmap(raw, x, y, w, h)
        val resized = resizeForPrinter(cropped, paperSize.widthPx)
        if (cropped !== resized && cropped !== raw) cropped.recycle()
        _originalBitmap.value = resized
    }

    private fun resizeForPrinter(bitmap: Bitmap, targetWidth: Int): Bitmap {
        // Auto-rotate landscape images to maximize print size
        val oriented = if (bitmap.width > bitmap.height) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(90f)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val aspect = oriented.height.toFloat() / oriented.width.toFloat()
        val targetHeight = (targetWidth * aspect).toInt()

        // Resize to printer width
        val resized = Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
        if (oriented !== bitmap && oriented !== resized) oriented.recycle()

        // If narrower than full printer width, center on white background
        if (targetWidth < PhomemoProtocol.IMAGE_WIDTH) {
            val full = Bitmap.createBitmap(
                PhomemoProtocol.IMAGE_WIDTH, targetHeight, Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(full)
            canvas.drawColor(android.graphics.Color.WHITE)
            val left = (PhomemoProtocol.IMAGE_WIDTH - targetWidth) / 2f
            canvas.drawBitmap(resized, left, 0f, null)
            return full
        }

        return resized
    }

    private fun redither() {
        val source = _originalBitmap.value ?: return
        Log.d(TAG, "redither() source=${source.width}x${source.height}, recycled=${source.isRecycled}")
        ditherJob?.cancel()
        ditherJob = viewModelScope.launch(Dispatchers.Default) {
            val result = DitherEngine.process(
                source = source,
                algorithm = _algorithm.value,
                brightness = _brightness.value,
                contrast = _contrast.value,
                invert = _invert.value,
                bayerSize = _bayerSize.value,
                bayerScale = _bayerScale.value,
                threshold = _threshold.value,
                gamma = _gamma.value,
                errorDiffusionStrength = _errorDiffusionStrength.value,
                serpentine = _serpentine.value
            )
            if (isActive) {
                _ditheredBitmap.value = result
                Log.d(TAG, "redither() DONE. ditheredBitmap=${result.width}x${result.height}")
            } else {
                Log.d(TAG, "redither() cancelled, recycling result")
                result.recycle()
            }
        }
    }

    fun setAlgorithm(alg: DitherAlgorithm) { _algorithm.value = alg }
    fun setBrightness(v: Float) { _brightness.value = v }
    fun setContrast(v: Float) { _contrast.value = v }
    fun setInvert(v: Boolean) { _invert.value = v }
    fun setBayerSize(v: Int) { _bayerSize.value = v }
    fun setBayerScale(v: Float) { _bayerScale.value = v }
    fun setThreshold(v: Float) { _threshold.value = v }
    fun setGamma(v: Float) { _gamma.value = v }
    fun setErrorDiffusionStrength(v: Float) { _errorDiffusionStrength.value = v }
    fun setSerpentine(v: Boolean) { _serpentine.value = v }
    fun toggleShowOriginal() { _showOriginal.value = !_showOriginal.value }
    fun showSaveFavoriteDialog() { _showSaveFavoriteDialog.value = true }
    fun dismissSaveFavoriteDialog() { _showSaveFavoriteDialog.value = false }
    fun clearPrintError() { _printError.value = null }

    fun toggleCropMode() { _isCropping.value = !_isCropping.value }

    fun updateCropRect(rect: RectF) {
        _cropRect.value = rect
    }

    fun applyCrop() {
        val crop = RectF(_cropRect.value)
        Log.d(TAG, "applyCrop() called. cropRect=$crop")
        Log.d(TAG, "  _rawBitmap=${_rawBitmap.value}, recycled=${_rawBitmap.value?.isRecycled}")
        Log.d(TAG, "  _originalBitmap before=${_originalBitmap.value?.width}x${_originalBitmap.value?.height}")
        Log.d(TAG, "  _ditheredBitmap before=${_ditheredBitmap.value?.width}x${_ditheredBitmap.value?.height}")
        ditherJob?.cancel()
        _isCropping.value = false
        _ditheredBitmap.value = null
        Log.d(TAG, "  isCropping set to false, ditheredBitmap cleared")
        viewModelScope.launch(Dispatchers.Default) {
            val raw = _rawBitmap.value
            if (raw == null) {
                Log.e(TAG, "  ERROR: _rawBitmap is null, aborting crop")
                return@launch
            }
            if (raw.isRecycled) {
                Log.e(TAG, "  ERROR: _rawBitmap is recycled, aborting crop")
                return@launch
            }
            val paperSize = printerSettings.value.paperSize
            Log.d(TAG, "  raw size=${raw.width}x${raw.height}, paperSize=${paperSize.widthPx}")

            val x = (crop.left * raw.width).toInt().coerceIn(0, raw.width - 1)
            val y = (crop.top * raw.height).toInt().coerceIn(0, raw.height - 1)
            val w = ((crop.right - crop.left) * raw.width).toInt().coerceIn(1, raw.width - x)
            val h = ((crop.bottom - crop.top) * raw.height).toInt().coerceIn(1, raw.height - y)
            Log.d(TAG, "  crop pixels: x=$x, y=$y, w=$w, h=$h")

            val cropped = Bitmap.createBitmap(raw, x, y, w, h)
            Log.d(TAG, "  cropped bitmap: ${cropped.width}x${cropped.height}")
            val resized = resizeForPrinter(cropped, paperSize.widthPx)
            Log.d(TAG, "  resized bitmap: ${resized.width}x${resized.height}")
            if (cropped !== resized && cropped !== raw) cropped.recycle()

            Log.d(TAG, "  setting _originalBitmap to ${resized.width}x${resized.height} (was ${_originalBitmap.value?.width}x${_originalBitmap.value?.height})")
            _originalBitmap.value = resized
            Log.d(TAG, "  applyCrop() DONE. _originalBitmap now=${_originalBitmap.value?.width}x${_originalBitmap.value?.height})")
        }
    }

    fun resetCrop() {
        _cropRect.value = RectF(0f, 0f, 1f, 1f)
        _isCropping.value = false
        viewModelScope.launch(Dispatchers.Default) {
            applyCropAndResize()
        }
    }

    fun applyFavorite(fav: SettingsRepository.Favorite) {
        _algorithm.value = fav.settings.algorithm
        _brightness.value = fav.settings.brightness
        _contrast.value = fav.settings.contrast
        _invert.value = fav.settings.invert
        _bayerSize.value = fav.settings.bayerSize
    }

    fun saveFavorite(name: String, setAsDefault: Boolean) {
        viewModelScope.launch {
            val settings = SettingsRepository.DitherSettings(
                algorithm = _algorithm.value,
                brightness = _brightness.value,
                contrast = _contrast.value,
                invert = _invert.value,
                bayerSize = _bayerSize.value
            )
            settingsRepo.saveFavorite(name, settings, setAsDefault)
            _showSaveFavoriteDialog.value = false
        }
    }

    fun deleteFavorite(name: String) {
        viewModelScope.launch { settingsRepo.deleteFavorite(name) }
    }

    fun setDefaultFavorite(name: String) {
        viewModelScope.launch { settingsRepo.setDefaultFavorite(name) }
    }

    fun savePrinterConnection(mac: String, name: String) {
        viewModelScope.launch { settingsRepo.savePrinterSettings(mac, name) }
    }

    fun saveDensity(density: PhomemoProtocol.Density) {
        viewModelScope.launch { settingsRepo.saveDensity(density) }
    }

    fun savePaperSize(paperSize: PhomemoProtocol.PaperSize) {
        viewModelScope.launch { settingsRepo.savePaperSize(paperSize) }
    }

    fun savePrintSpeed(speed: PhomemoProtocol.PrintSpeed) {
        viewModelScope.launch { settingsRepo.savePrintSpeed(speed) }
    }

    fun savePaperFeed(feed: Int) {
        viewModelScope.launch { settingsRepo.savePaperFeed(feed) }
    }

    fun saveAlignment(alignment: PhomemoProtocol.Alignment) {
        viewModelScope.launch { settingsRepo.saveAlignment(alignment) }
    }

    fun refreshPrinterInfo() {
        viewModelScope.launch { bleManager.queryPrinterInfo() }
    }

    fun print() {
        val bitmap = _ditheredBitmap.value ?: return
        val settings = printerSettings.value

        _isPrinting.value = true
        _printError.value = null

        viewModelScope.launch {
            try {
                bleManager.print(
                    bitmap,
                    settings.density,
                    settings.printSpeed,
                    settings.alignment,
                    settings.paperFeed
                )
            } catch (e: Exception) {
                _printError.value = "Print failed: ${e.message}"
            } finally {
                _isPrinting.value = false
            }
        }
    }

    fun saveToGallery() {
        val bitmap = _ditheredBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "dithered_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DitherPrint")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } catch (e: Exception) {
                _printError.value = "Save failed: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared() // cancels viewModelScope before we recycle bitmaps
        bleManager.disconnect()
        _rawBitmap.value?.recycle()
        _originalBitmap.value?.recycle()
        _ditheredBitmap.value?.recycle()
    }
}
