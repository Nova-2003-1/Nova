package org.stypox.dicio.llm

/**
 * The state of the on-device LLM model, covering both the download lifecycle and the in-memory
 * load lifecycle. Mirrors the structure of the Vosk model states
 * ([org.stypox.dicio.io.input.vosk.VoskState]) so the UI can treat them similarly.
 */
sealed interface LlmModelState {
    /** The local LLM feature is turned off in settings. */
    data object Disabled : LlmModelState

    /** No model file present yet; [modelUrl] is where it will be downloaded from. */
    data class NotDownloaded(val modelUrl: String) : LlmModelState

    /** The model file is downloading. [progress] is 0..1, or null if the size is unknown. */
    data class Downloading(val progress: Float?) : LlmModelState

    /** Downloading failed. */
    data class ErrorDownloading(val modelUrl: String, val throwable: Throwable) : LlmModelState

    /** The model file is on disk but not loaded into memory. */
    data object NotLoaded : LlmModelState

    /** The model is being loaded into memory. */
    data object Loading : LlmModelState

    /** The model is loaded and ready to answer. */
    data object Ready : LlmModelState

    /** Loading the model into memory failed. */
    data class ErrorLoading(val throwable: Throwable) : LlmModelState
}
