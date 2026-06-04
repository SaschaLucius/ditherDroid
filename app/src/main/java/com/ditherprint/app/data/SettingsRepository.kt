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

    // Printer settings keys
    private val LAST_PRINTER_MAC = stringPreferencesKey("last_printer_mac")
    private val LAST_PRINTER_NAME = stringPreferencesKey("last_printer_name")
    private val DENSITY = intPreferencesKey("density")
    private val PAPER_SIZE = stringPreferencesKey("paper_size")
    private val PRINT_SPEED = stringPreferencesKey("print_speed")
    private val PAPER_FEED = intPreferencesKey("paper_feed")
    private val ALIGNMENT = stringPreferencesKey("alignment")

    // Favorite (default) dither settings
    private val FAV_ALGORITHM = stringPreferencesKey("fav_algorithm")
    private val FAV_BRIGHTNESS = floatPreferencesKey("fav_brightness")
    private val FAV_CONTRAST = floatPreferencesKey("fav_contrast")
    private val FAV_INVERT = booleanPreferencesKey("fav_invert")
    private val FAV_BAYER_SIZE = intPreferencesKey("fav_bayer_size")

    // Saved favorites list (simple JSON-like key-value approach)
    // Each favorite: fav_{name}_algorithm, fav_{name}_brightness, etc.
    private val FAVORITE_NAMES = stringPreferencesKey("favorite_names") // comma-separated
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
        val bayerSize: Int = 4
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
            bayerSize = prefs[FAV_BAYER_SIZE] ?: 4
        )
    }

    val favorites: Flow<List<Favorite>> = context.dataStore.data.map { prefs ->
        val names = prefs[FAVORITE_NAMES]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val defaultName = prefs[DEFAULT_FAVORITE]
        names.map { name ->
            Favorite(
                name = name,
                settings = DitherSettings(
                    algorithm = prefs[stringPreferencesKey("fav_${name}_alg")]?.let { a ->
                        DitherAlgorithm.entries.find { it.name == a }
                    } ?: DitherAlgorithm.FLOYD_STEINBERG,
                    brightness = prefs[floatPreferencesKey("fav_${name}_bri")] ?: 0f,
                    contrast = prefs[floatPreferencesKey("fav_${name}_con")] ?: 1f,
                    invert = prefs[booleanPreferencesKey("fav_${name}_inv")] ?: false,
                    bayerSize = prefs[intPreferencesKey("fav_${name}_bay")] ?: 4
                ),
                isDefault = name == defaultName
            )
        }
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
            // Add name to list
            val existing = prefs[FAVORITE_NAMES]?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            if (name !in existing) existing.add(name)
            prefs[FAVORITE_NAMES] = existing.joinToString(",")

            // Store settings
            prefs[stringPreferencesKey("fav_${name}_alg")] = settings.algorithm.name
            prefs[floatPreferencesKey("fav_${name}_bri")] = settings.brightness
            prefs[floatPreferencesKey("fav_${name}_con")] = settings.contrast
            prefs[booleanPreferencesKey("fav_${name}_inv")] = settings.invert
            prefs[intPreferencesKey("fav_${name}_bay")] = settings.bayerSize

            if (setAsDefault) {
                prefs[DEFAULT_FAVORITE] = name
            }
        }
    }

    suspend fun deleteFavorite(name: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[FAVORITE_NAMES]?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            existing.remove(name)
            prefs[FAVORITE_NAMES] = existing.joinToString(",")

            prefs.remove(stringPreferencesKey("fav_${name}_alg"))
            prefs.remove(floatPreferencesKey("fav_${name}_bri"))
            prefs.remove(floatPreferencesKey("fav_${name}_con"))
            prefs.remove(booleanPreferencesKey("fav_${name}_inv"))
            prefs.remove(intPreferencesKey("fav_${name}_bay"))

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
