package com.ditherprint.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ditherprint.app.dithering.DitherAlgorithm
import com.ditherprint.app.printer.PhomemoProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persists printer settings and favorites using DataStore.
 */
class SettingsRepository(private val context: Context) {

    private val LAST_PRINTER_MAC = stringPreferencesKey("last_printer_mac")
    private val LAST_PRINTER_NAME = stringPreferencesKey("last_printer_name")
    private val DENSITY = intPreferencesKey("density")
    private val PAPER_SIZE = stringPreferencesKey("paper_size")
    private val PRINT_SPEED = stringPreferencesKey("print_speed")
    private val PAPER_FEED = intPreferencesKey("paper_feed")
    private val ALIGNMENT = stringPreferencesKey("alignment")

    private val FAV_ALGORITHM = stringPreferencesKey("fav_algorithm")
    private val FAV_BRIGHTNESS = floatPreferencesKey("fav_brightness")
    private val FAV_CONTRAST = floatPreferencesKey("fav_contrast")
    private val FAV_INVERT = booleanPreferencesKey("fav_invert")
    private val FAV_BAYER_SIZE = intPreferencesKey("fav_bayer_size")
    private val FAV_BAYER_SCALE = floatPreferencesKey("fav_bayer_scale")
    private val FAV_THRESHOLD = floatPreferencesKey("fav_threshold")
    private val FAV_GAMMA = floatPreferencesKey("fav_gamma")
    private val FAV_ERROR_STRENGTH = floatPreferencesKey("fav_error_strength")
    private val FAV_SERPENTINE = booleanPreferencesKey("fav_serpentine")

    private val FAVORITE_NAMES = stringPreferencesKey("favorite_names")
    private val DEFAULT_FAVORITE = stringPreferencesKey("default_favorite")

    data class PrinterSettings(
        val lastPrinterMac: String? = null,
        val lastPrinterName: String? = null,
        val density: PhomemoProtocol.Density = PhomemoProtocol.Density.DEFAULT,
        val paperSize: PhomemoProtocol.PaperSize = PhomemoProtocol.PaperSize.MM_53,
        val printSpeed: PhomemoProtocol.PrintSpeed = PhomemoProtocol.PrintSpeed.NORMAL,
        val paperFeed: Int = PhomemoProtocol.DEFAULT_PAPER_FEED,
        val alignment: PhomemoProtocol.Alignment = PhomemoProtocol.Alignment.LEFT
    )

    data class DitherSettings(
        val algorithm: DitherAlgorithm = DitherAlgorithm.FLOYD_STEINBERG,
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val invert: Boolean = false,
        val bayerSize: Int = 4,
        val bayerScale: Float = 1f,
        val threshold: Float = 0.5f,
        val gamma: Float = 1.5f,
        val errorDiffusionStrength: Float = 1f,
        val serpentine: Boolean = false
    )

    data class Favorite(
        val name: String,
        val settings: DitherSettings,
        val isDefault: Boolean = false
    )

    val printerSettings: Flow<PrinterSettings> = context.dataStore.data.map { prefs ->
        PrinterSettings(
            lastPrinterMac = prefs[LAST_PRINTER_MAC],
            lastPrinterName = prefs[LAST_PRINTER_NAME],
            density = prefs[DENSITY]?.let { value ->
                PhomemoProtocol.Density.entries.find { it.value == value }
            } ?: PhomemoProtocol.Density.DEFAULT,
            paperSize = prefs[PAPER_SIZE]?.let { name ->
                PhomemoProtocol.PaperSize.entries.find { it.name == name }
            } ?: PhomemoProtocol.PaperSize.MM_53,
            printSpeed = prefs[PRINT_SPEED]?.let { name ->
                PhomemoProtocol.PrintSpeed.entries.find { it.name == name }
            } ?: PhomemoProtocol.PrintSpeed.NORMAL,
            paperFeed = prefs[PAPER_FEED] ?: PhomemoProtocol.DEFAULT_PAPER_FEED,
            alignment = prefs[ALIGNMENT]?.let { name ->
                PhomemoProtocol.Alignment.entries.find { it.name == name }
            } ?: PhomemoProtocol.Alignment.LEFT
        )
    }

    val defaultDitherSettings: Flow<DitherSettings> = context.dataStore.data.map { prefs ->
        DitherSettings(
            algorithm = prefs[FAV_ALGORITHM]?.let { name ->
                DitherAlgorithm.entries.find { it.name == name }
            } ?: DitherAlgorithm.FLOYD_STEINBERG,
            brightness = prefs[FAV_BRIGHTNESS] ?: 0f,
            contrast = prefs[FAV_CONTRAST] ?: 1f,
            invert = prefs[FAV_INVERT] ?: false,
            bayerSize = prefs[FAV_BAYER_SIZE] ?: 4,
            bayerScale = prefs[FAV_BAYER_SCALE] ?: 1f,
            threshold = prefs[FAV_THRESHOLD] ?: 0.5f,
            gamma = prefs[FAV_GAMMA] ?: 1.5f,
            errorDiffusionStrength = prefs[FAV_ERROR_STRENGTH] ?: 1f,
            serpentine = prefs[FAV_SERPENTINE] ?: false
        )
    }

    val favorites: Flow<List<Favorite>> = context.dataStore.data.map { prefs ->
        val names = prefs[FAVORITE_NAMES]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val defaultName = prefs[DEFAULT_FAVORITE]
        names.map { name ->
            Favorite(
                name = name,
                settings = readFavoriteSettings(prefs, name),
                isDefault = name == defaultName
            )
        }
    }

    private fun readFavoriteSettings(prefs: Preferences, name: String): DitherSettings {
        return DitherSettings(
            algorithm = prefs[stringPreferencesKey("fav_${name}_alg")]?.let { a ->
                DitherAlgorithm.entries.find { it.name == a }
            } ?: DitherAlgorithm.FLOYD_STEINBERG,
            brightness = prefs[floatPreferencesKey("fav_${name}_bri")] ?: 0f,
            contrast = prefs[floatPreferencesKey("fav_${name}_con")] ?: 1f,
            invert = prefs[booleanPreferencesKey("fav_${name}_inv")] ?: false,
            bayerSize = prefs[intPreferencesKey("fav_${name}_bay")] ?: 4,
            bayerScale = prefs[floatPreferencesKey("fav_${name}_bsc")] ?: 1f,
            threshold = prefs[floatPreferencesKey("fav_${name}_thr")] ?: 0.5f,
            gamma = prefs[floatPreferencesKey("fav_${name}_gam")] ?: 1.5f,
            errorDiffusionStrength = prefs[floatPreferencesKey("fav_${name}_err")] ?: 1f,
            serpentine = prefs[booleanPreferencesKey("fav_${name}_serp")] ?: false
        )
    }

    suspend fun savePrinterSettings(mac: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_PRINTER_MAC] = mac
            prefs[LAST_PRINTER_NAME] = name
        }
    }

    suspend fun saveDensity(density: PhomemoProtocol.Density) {
        context.dataStore.edit { prefs ->
            prefs[DENSITY] = density.value
        }
    }

    suspend fun savePaperSize(paperSize: PhomemoProtocol.PaperSize) {
        context.dataStore.edit { prefs ->
            prefs[PAPER_SIZE] = paperSize.name
        }
    }

    suspend fun savePrintSpeed(speed: PhomemoProtocol.PrintSpeed) {
        context.dataStore.edit { prefs ->
            prefs[PRINT_SPEED] = speed.name
        }
    }

    suspend fun savePaperFeed(feed: Int) {
        context.dataStore.edit { prefs ->
            prefs[PAPER_FEED] = feed.coerceIn(PhomemoProtocol.MIN_PAPER_FEED, PhomemoProtocol.MAX_PAPER_FEED)
        }
    }

    suspend fun saveAlignment(alignment: PhomemoProtocol.Alignment) {
        context.dataStore.edit { prefs ->
            prefs[ALIGNMENT] = alignment.name
        }
    }

    suspend fun saveFavorite(name: String, settings: DitherSettings, setAsDefault: Boolean = false) {
        context.dataStore.edit { prefs ->
            val existing = prefs[FAVORITE_NAMES]?.split(",")?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
            if (name !in existing) existing.add(name)
            prefs[FAVORITE_NAMES] = existing.joinToString(",")

            prefs[stringPreferencesKey("fav_${name}_alg")] = settings.algorithm.name
            prefs[floatPreferencesKey("fav_${name}_bri")] = settings.brightness
            prefs[floatPreferencesKey("fav_${name}_con")] = settings.contrast
            prefs[booleanPreferencesKey("fav_${name}_inv")] = settings.invert
            prefs[intPreferencesKey("fav_${name}_bay")] = settings.bayerSize
            prefs[floatPreferencesKey("fav_${name}_bsc")] = settings.bayerScale
            prefs[floatPreferencesKey("fav_${name}_thr")] = settings.threshold
            prefs[floatPreferencesKey("fav_${name}_gam")] = settings.gamma
            prefs[floatPreferencesKey("fav_${name}_err")] = settings.errorDiffusionStrength
            prefs[booleanPreferencesKey("fav_${name}_serp")] = settings.serpentine

            if (setAsDefault) {
                prefs[DEFAULT_FAVORITE] = name
                // Mirror into the legacy "default" keys used on cold start
                prefs[FAV_ALGORITHM] = settings.algorithm.name
                prefs[FAV_BRIGHTNESS] = settings.brightness
                prefs[FAV_CONTRAST] = settings.contrast
                prefs[FAV_INVERT] = settings.invert
                prefs[FAV_BAYER_SIZE] = settings.bayerSize
                prefs[FAV_BAYER_SCALE] = settings.bayerScale
                prefs[FAV_THRESHOLD] = settings.threshold
                prefs[FAV_GAMMA] = settings.gamma
                prefs[FAV_ERROR_STRENGTH] = settings.errorDiffusionStrength
                prefs[FAV_SERPENTINE] = settings.serpentine
            }
        }
    }

    suspend fun deleteFavorite(name: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[FAVORITE_NAMES]?.split(",")?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
            existing.remove(name)
            prefs[FAVORITE_NAMES] = existing.joinToString(",")

            prefs.remove(stringPreferencesKey("fav_${name}_alg"))
            prefs.remove(floatPreferencesKey("fav_${name}_bri"))
            prefs.remove(floatPreferencesKey("fav_${name}_con"))
            prefs.remove(booleanPreferencesKey("fav_${name}_inv"))
            prefs.remove(intPreferencesKey("fav_${name}_bay"))
            prefs.remove(floatPreferencesKey("fav_${name}_bsc"))
            prefs.remove(floatPreferencesKey("fav_${name}_thr"))
            prefs.remove(floatPreferencesKey("fav_${name}_gam"))
            prefs.remove(floatPreferencesKey("fav_${name}_err"))
            prefs.remove(booleanPreferencesKey("fav_${name}_serp"))

            if (prefs[DEFAULT_FAVORITE] == name) {
                prefs.remove(DEFAULT_FAVORITE)
            }
        }
    }

    suspend fun setDefaultFavorite(name: String) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_FAVORITE] = name
        }
    }
}
