package org.stypox.dicio.health

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Entry point for importing fitness/health data. Given a content [Uri] (from the system file
 * picker), it detects whether the input is:
 *  - a **Gadgetbridge ZIP export** (unzipped; the SQLite DB inside is read via
 *    [GadgetbridgeImporter], any `.gpx` files inside via [GpxParser]), or
 *  - a single **GPX file** ([GpxParser]).
 *
 * The parsed data is merged into [HealthDataStore], entirely offline.
 */
class HealthImportManager(
    private val appContext: Context,
    private val store: HealthDataStore,
) {
    /** The result of an import attempt, surfaced to the settings UI. */
    sealed interface ImportState {
        data object Idle : ImportState
        data object Importing : ImportState
        data class Done(val activities: Int, val days: Int, val sources: List<String>) : ImportState
        data class Error(val message: String) : ImportState
    }

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    /**
     * Imports from [uri]. [displayName] is the original file name (used to detect .zip/.gpx and to
     * label activities). Runs on IO; updates [state] and merges into the store on success.
     */
    suspend fun import(uri: Uri, displayName: String) {
        _state.value = ImportState.Importing
        try {
            val incoming = withContext(Dispatchers.IO) {
                val lower = displayName.lowercase()
                when {
                    lower.endsWith(".zip") -> importZip(uri, displayName)
                    lower.endsWith(".gpx") -> importSingleGpx(uri, displayName)
                    else -> {
                        // fall back to sniffing the content: zip files start with "PK"
                        if (looksLikeZip(uri)) importZip(uri, displayName)
                        else importSingleGpx(uri, displayName)
                    }
                }
            }
            if (incoming.isEmpty) {
                _state.value = ImportState.Error(
                    "No recognizable health data found in $displayName."
                )
                return
            }
            store.merge(incoming)
            _state.value = ImportState.Done(
                activities = incoming.activities.size,
                days = incoming.dailyMetrics.size,
                sources = incoming.sources,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Import failed", t)
            _state.value = ImportState.Error(t.message ?: "Import failed")
        }
    }

    private fun importZip(uri: Uri, zipName: String): HealthData {
        val tempDir = File(appContext.cacheDir, "health-import-${System.identityHashCode(uri)}")
        tempDir.mkdirs()
        val activities = ArrayList<ActivityTrack>()
        val metrics = ArrayList<DailyMetric>()
        val sources = ArrayList<String>()
        try {
            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open $zipName" }
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = File(entry.name).name
                            val lower = name.lowercase()
                            when {
                                lower.endsWith(".gpx") -> {
                                    GpxParser.parse(zip.nonClosing(), name)?.let {
                                        activities.add(it)
                                        sources.add(name)
                                    }
                                }
                                lower.endsWith(".db") || lower.endsWith(".sqlite") ||
                                    lower.contains("gadgetbridge") -> {
                                    // SQLite must be a real file to open; extract it first
                                    val dbFile = File(tempDir, name)
                                    dbFile.outputStream().use { out -> zip.copyTo(out) }
                                    val dayMetrics = GadgetbridgeImporter.importDatabase(dbFile)
                                    if (dayMetrics.isNotEmpty()) {
                                        metrics.addAll(dayMetrics)
                                        sources.add(name)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
        return HealthData(
            activities = activities,
            dailyMetrics = metrics,
            sources = sources.ifEmpty { listOf(zipName) },
            importedAtMillis = nowMillis(),
        )
    }

    private fun importSingleGpx(uri: Uri, name: String): HealthData {
        val activity = appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $name" }
            GpxParser.parse(input, name)
        } ?: return HealthData()
        return HealthData(
            activities = listOf(activity),
            sources = listOf(name),
            importedAtMillis = nowMillis(),
        )
    }

    private fun looksLikeZip(uri: Uri): Boolean = try {
        appContext.contentResolver.openInputStream(uri).use { input ->
            val header = ByteArray(2)
            input?.read(header)
            header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    } catch (e: Exception) {
        false
    }

    // System.currentTimeMillis is fine here (not part of any deterministic replay).
    private fun nowMillis(): Long = System.currentTimeMillis()

    /** Wraps the ZipInputStream so parsers that call close() don't close the whole zip stream. */
    private fun ZipInputStream.nonClosing(): java.io.InputStream =
        object : java.io.FilterInputStream(this) {
            override fun close() { /* keep the zip stream open for the next entry */ }
        }

    companion object {
        private val TAG = HealthImportManager::class.simpleName
    }
}
