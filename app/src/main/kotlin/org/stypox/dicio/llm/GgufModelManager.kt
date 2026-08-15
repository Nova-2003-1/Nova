package org.stypox.dicio.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.util.downloadBinaryFileWithPartial
import org.stypox.dicio.util.getResponse
import java.io.File

/**
 * Manages the on-device GGUF model file: downloading it (once), tracking progress, and loading it
 * into the [LlmEngine]. Follows the same partial-download / on-disk-marker approach as the Vosk
 * model manager so an interrupted download can resume/redo cleanly.
 *
 * The model is stored in the app's private files dir. The download URL and desired model can be
 * changed from settings (see [defaultModelUrl]); when the URL changes, the old file is discarded
 * and the new model is downloaded.
 */
class GgufModelManager(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val engine: LlmEngine,
) {
    private val filesDir: File = appContext.filesDir
    private val modelFile: File get() = File(filesDir, MODEL_FILE_NAME)
    private val modelUrlMarker: File get() = File(filesDir, MODEL_URL_MARKER)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Disabled)
    val state: StateFlow<LlmModelState> = _state

    /** Absolute path of the model file, for passing to [LlmEngine.ensureLoaded]. */
    val modelPath: String get() = modelFile.absolutePath

    /**
     * Recomputes the model state from what is on disk, for the given [modelUrl]. Called when the
     * feature is enabled or the configured model changes.
     */
    fun refresh(enabled: Boolean, modelUrl: String) {
        if (!enabled) {
            _state.value = LlmModelState.Disabled
            return
        }
        val urlChanged = try {
            modelUrlMarker.readText() != modelUrl
        } catch (e: Exception) {
            true // marker missing
        }
        _state.value = when {
            urlChanged -> LlmModelState.NotDownloaded(modelUrl)
            modelFile.exists() -> LlmModelState.NotLoaded
            else -> LlmModelState.NotDownloaded(modelUrl)
        }
    }

    /**
     * Advances the model to the [LlmModelState.Ready] state: downloads it if needed, then loads it
     * into the engine. Safe to call repeatedly; concurrent calls are coalesced.
     */
    fun ensureReady(modelUrl: String) {
        if (currentJob?.isActive == true) return
        currentJob = scope.launch {
            try {
                when (val s = _state.value) {
                    LlmModelState.Disabled -> return@launch
                    is LlmModelState.NotDownloaded -> {
                        download(modelUrl)
                        load()
                    }
                    is LlmModelState.ErrorDownloading -> {
                        download(modelUrl)
                        load()
                    }
                    LlmModelState.NotLoaded, is LlmModelState.ErrorLoading -> load()
                    LlmModelState.Downloading, LlmModelState.Loading, LlmModelState.Ready -> {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ensureReady failed", t)
            }
        }
    }

    private suspend fun download(modelUrl: String) {
        _state.value = LlmModelState.Downloading(null)
        try {
            withContext(Dispatchers.IO) {
                val response = okHttpClient.getResponse(modelUrl)
                downloadBinaryFileWithPartial(
                    response = response,
                    file = modelFile,
                    cacheDir = appContext.cacheDir,
                ) { current, total ->
                    _state.value = LlmModelState.Downloading(
                        if (total > 0) current.toFloat() / total else null
                    )
                }
                modelUrlMarker.writeText(modelUrl)
            }
            _state.value = LlmModelState.NotLoaded
        } catch (t: Throwable) {
            Log.e(TAG, "Model download failed", t)
            _state.value = LlmModelState.ErrorDownloading(modelUrl, t)
            throw t
        }
    }

    private suspend fun load() {
        _state.value = LlmModelState.Loading
        try {
            engine.ensureLoaded(modelPath)
            _state.value = LlmModelState.Ready
        } catch (t: Throwable) {
            _state.value = LlmModelState.ErrorLoading(t)
            throw t
        }
    }

    /** Unloads the model from memory (keeps the file on disk). */
    fun unload() {
        engine.unload()
        if (_state.value == LlmModelState.Ready || _state.value == LlmModelState.Loading) {
            _state.value = LlmModelState.NotLoaded
        }
    }

    companion object {
        private val TAG = GgufModelManager::class.simpleName
        private const val MODEL_FILE_NAME = "llm-model.gguf"
        private const val MODEL_URL_MARKER = "llm-model-url"

        /**
         * Default model. Qwen2.5-0.5B-Instruct is multilingual (good German) and follows the tool
         * convention better than TinyDolphin, at a smaller size. Swap for a TinyDolphin GGUF URL in
         * settings if you specifically want that model.
         */
        const val defaultModelUrl =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }
}
