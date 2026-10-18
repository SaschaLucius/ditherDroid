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
import com.ditherprint.app.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

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

    private val _isLoadingImage = MutableStateFlow(false)
    val isLoadingImage: StateFlow<Boolean> = _isLoadingImage.asStateFlow()

    private val _isDithering = MutableStateFlow(false)
    val isDithering: StateFlow<Boolean> = _isDithering.asStateFlow()

    // Crop state (normalized 0..1 rect)
    private val _isCropping = MutableStateFlow(false)
    val isCropping: StateFlow<Boolean> = _isCropping

    private val _cropRect = MutableStateFlow(RectF(0f, 0f, 1f, 1f))
    val cropRect: StateFlow<RectF> = _cropRect

    // Printer state
    private val _isPrinting = MutableStateFlow(false)
    val isPrinting: StateFlow<Boolean> = _isPrinting

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage = _userMessage.asSharedFlow()

    // Favorites
    val favorites = settingsRepo.favorites.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )
    val printerSettings = settingsRepo.printerSettings.stateIn(
        viewModelScope, SharingStarted.Lazily, SettingsRepository.PrinterSettings()
    )

    private val _showSaveFavoriteDialog = MutableStateFlow(false)
    val showSaveFavoriteDialog: StateFlow<Boolean> = _showSaveFavoriteDialog

    private var ditherJob: Job? = null
    private var cropJob: Job? = null
    private val imageMutex = Mutex()
    private val printMutex = Mutex()

    init {
        viewModelScope.launch {
            settingsRepo.defaultDitherSettings.first().let { defaults ->
                _algorithm.value = defaults.algorithm
                _brightness.value = defaults.brightness
                _contrast.value = defaults.contrast
                _invert.value = defaults.invert
                _bayerSize.value = defaults.bayerSize
                _bayerScale.value = defaults.bayerScale
                _threshold.value = defaults.threshold
                _gamma.value = defaults.gamma
                _errorDiffusionStrength.value = defaults.errorDiffusionStrength
                _serpentine.value = defaults.serpentine
            }
        }

        // Defer auto-connect slightly so permission UX can settle; still attempt if already granted
        viewModelScope.launch {
            delay(300)
            settingsRepo.printerSettings.first().let { settings ->
                val mac = settings.lastPrinterMac
                if (!mac.isNullOrBlank()) {
                    bleManager.connectByAddress(mac)
                }
            }
        }

        viewModelScope.launch {
            bleManager.state.collect { state ->
                if (state is PhomemoBleManager.ConnectionState.Connected) {
                    delay(500)
                    bleManager.queryPrinterInfo()
                }
            }
        }

        viewModelScope.launch {
            printerSettings
                .map { it.paperSize }
                .distinctUntilChanged()
                .drop(1)
                .collect { applyCropAndResizeAsync() }
        }

        viewModelScope.launch {
            combine(
                combine(_algorithm, _brightness, _contrast, _invert) { _, _, _, _ -> },
                combine(_bayerSize, _bayerScale, _threshold, _gamma) { _, _, _, _ -> },
                combine(_errorDiffusionStrength, _serpentine, _originalBitmap) { _, _, _ -> }
            ) { _, _, _ -> }
                .debounce(150)
                .collect { redither() }
        }
    }

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            _isLoadingImage.value = true
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeUri(uri)
                }
                setRawBitmap(bitmap)
            } catch (e: Exception) {
                emitMessage("Failed to load image: ${e.message}")
            } finally {
                _isLoadingImage.value = false
            }
        }
    }

    fun loadBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _isLoadingImage.value = true
            try {
                setRawBitmap(bitmap)
            } catch (e: Exception) {
                emitMessage("Failed to load image: ${e.message}")
            } finally {
                _isLoadingImage.value = false
            }
        }
    }

    private suspend fun decodeUri(uri: Uri): Bitmap {
        val context = getApplication<Application>()
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        } ?: throw IOException("Cannot open image")

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            throw IOException("Cannot read image dimensions")
        }

        val maxDim = 4096
        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > maxDim ||
            boundsOptions.outHeight / sampleSize > maxDim
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IOException("Cannot decode image")
    }

    private suspend fun setRawBitmap(bitmap: Bitmap) {
        imageMutex.withLock {
            val old = _rawBitmap.value
            _rawBitmap.value = bitmap
            BitmapUtils.recycleIfDifferent(old, bitmap)
            _cropRect.value = RectF(0f, 0f, 1f, 1f)
            _isCropping.value = false
        }
        applyCropAndResizeAsync()
    }

    private fun applyCropAndResizeAsync() {
        cropJob?.cancel()
        cropJob = viewModelScope.launch(Dispatchers.Default) {
            applyCropAndResize()
        }
    }

    private suspend fun applyCropAndResize() {
        imageMutex.withLock {
            val raw = _rawBitmap.value ?: return
            if (raw.isRecycled) return
            val crop = _cropRect.value
            val paperSize = printerSettings.value.paperSize

            val x = (crop.left * raw.width).toInt().coerceIn(0, raw.width - 1)
            val y = (crop.top * raw.height).toInt().coerceIn(0, raw.height - 1)
            val w = ((crop.right - crop.left) * raw.width).toInt().coerceIn(1, raw.width - x)
            val h = ((crop.bottom - crop.top) * raw.height).toInt().coerceIn(1, raw.height - y)

            val cropped = Bitmap.createBitmap(raw, x, y, w, h)
            val resized = BitmapUtils.resizeForPrinter(cropped, paperSize.widthPx)
            if (cropped !== resized && cropped !== raw) cropped.recycle()

            // Invariant: always IMAGE_WIDTH wide for the print encoder
            check(resized.width == PhomemoProtocol.IMAGE_WIDTH) {
                "Resized bitmap must be ${PhomemoProtocol.IMAGE_WIDTH} wide, got ${resized.width}"
            }

            val oldOriginal = _originalBitmap.value
            _originalBitmap.value = resized
            BitmapUtils.recycleIfDifferent(oldOriginal, resized)
        }
    }

    private fun redither() {
        val source = _originalBitmap.value ?: return
        if (source.isRecycled) return
        ditherJob?.cancel()
        ditherJob = viewModelScope.launch(Dispatchers.Default) {
            _isDithering.value = true
            try {
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
                    val old = _ditheredBitmap.value
                    _ditheredBitmap.value = result
                    BitmapUtils.recycleIfDifferent(old, result)
                } else {
                    result.recycle()
                }
            } finally {
                if (isActive) _isDithering.value = false
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

    fun toggleCropMode() { _isCropping.value = !_isCropping.value }

    fun updateCropRect(rect: RectF) {
        _cropRect.value = rect
    }

    fun applyCrop() {
        ditherJob?.cancel()
        _isCropping.value = false
        val oldDithered = _ditheredBitmap.value
        _ditheredBitmap.value = null
        BitmapUtils.recycleIfDifferent(oldDithered, null)
        applyCropAndResizeAsync()
    }

    fun resetCrop() {
        _cropRect.value = RectF(0f, 0f, 1f, 1f)
        _isCropping.value = false
        applyCropAndResizeAsync()
    }

    fun applyFavorite(fav: SettingsRepository.Favorite) {
        _algorithm.value = fav.settings.algorithm
        _brightness.value = fav.settings.brightness
        _contrast.value = fav.settings.contrast
        _invert.value = fav.settings.invert
        _bayerSize.value = fav.settings.bayerSize
        _bayerScale.value = fav.settings.bayerScale
        _threshold.value = fav.settings.threshold
        _gamma.value = fav.settings.gamma
        _errorDiffusionStrength.value = fav.settings.errorDiffusionStrength
        _serpentine.value = fav.settings.serpentine
    }

    fun saveFavorite(name: String, setAsDefault: Boolean) {
        viewModelScope.launch {
            val settings = SettingsRepository.DitherSettings(
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
            settingsRepo.saveFavorite(name, settings, setAsDefault)
            _showSaveFavoriteDialog.value = false
            emitMessage("Favorite \"$name\" saved")
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

    /** Whether the print FAB should be enabled for printing (not for connect). */
    fun canPrint(): Boolean {
        return !_isPrinting.value &&
            _ditheredBitmap.value != null &&
            !_ditheredBitmap.value!!.isRecycled &&
            bleManager.state.value is PhomemoBleManager.ConnectionState.Connected
    }

    fun print() {
        viewModelScope.launch {
            if (!printMutex.tryLock()) {
                emitMessage("Print already in progress")
                return@launch
            }
            try {
                val bitmap = _ditheredBitmap.value
                if (bitmap == null || bitmap.isRecycled) {
                    emitMessage("Nothing to print — load and dither an image first")
                    return@launch
                }
                if (bleManager.state.value !is PhomemoBleManager.ConnectionState.Connected) {
                    emitMessage("Printer not connected")
                    return@launch
                }
                if (_isPrinting.value) {
                    emitMessage("Print already in progress")
                    return@launch
                }

                val info = bleManager.printerInfo.value
                if (info.paper == "out") {
                    emitMessage("Printer is out of paper")
                    return@launch
                }
                if (info.cover == "open") {
                    emitMessage("Printer cover is open")
                    return@launch
                }
                if (bitmap.width != PhomemoProtocol.IMAGE_WIDTH) {
                    emitMessage("Image width invalid for printer (${bitmap.width})")
                    return@launch
                }

                val settings = printerSettings.value
                _isPrinting.value = true
                try {
                    bleManager.print(
                        bitmap,
                        settings.density,
                        settings.printSpeed,
                        settings.alignment,
                        settings.paperFeed
                    )
                    emitMessage("Print complete")
                } catch (e: Exception) {
                    emitMessage("Print failed: ${e.message}")
                } finally {
                    _isPrinting.value = false
                }
            } finally {
                printMutex.unlock()
            }
        }
    }

    fun saveToGallery() {
        val bitmap = _ditheredBitmap.value
        if (bitmap == null || bitmap.isRecycled) {
            emitMessage("Nothing to save")
            return
        }
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
                    emitMessage("Saved to gallery")
                } ?: emitMessage("Save failed: could not create file")
            } catch (e: Exception) {
                emitMessage("Save failed: ${e.message}")
            }
        }
    }

    private fun emitMessage(message: String) {
        _userMessage.tryEmit(message)
    }

    override fun onCleared() {
        ditherJob?.cancel()
        cropJob?.cancel()
        super.onCleared()
        bleManager.release()
        _rawBitmap.value?.let { if (!it.isRecycled) it.recycle() }
        _originalBitmap.value?.let { if (!it.isRecycled) it.recycle() }
        _ditheredBitmap.value?.let { if (!it.isRecycled) it.recycle() }
    }
}
