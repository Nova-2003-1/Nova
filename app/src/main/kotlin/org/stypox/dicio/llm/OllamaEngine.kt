package org.stypox.dicio.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [LlmEngine] that talks to the user's **own private server** — an Ollama instance running on their
 * laptop/home machine — over the local network (or a private tunnel). This is the "clever but still
 * private" brain: the data only ever goes to hardware the user controls, never to a third party.
 *
 * Uses Ollama's `/api/chat` streaming endpoint. Tool-calling reuses the same text protocol as the
 * on-device engine ([ChatFormat.toolInstructions] injected into the system message, parsed back
 * with [ToolCallParser]), so the orchestrator and tools work identically regardless of which brain
 * answered.
 *
 * This engine holds no model in memory itself; [ensureLoaded] is a no-op and [state] is driven by
 * reachability checks.
 */
class OllamaEngine(
    private val okHttpClient: OkHttpClient,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngineState>(LlmEngineState.Unloaded)
    override val state: StateFlow<LlmEngineState> = _state

    // configured per-call by the orchestrator, since they come from settings
    @Volatile var baseUrl: String = ""
    @Volatile var model: String = DEFAULT_MODEL

    override suspend fun ensureLoaded(modelPath: String) {
        // nothing to load locally; readiness is a network property (see isReachable)
        _state.value = LlmEngineState.Loaded
    }

    override fun unload() {
        _state.value = LlmEngineState.Unloaded
    }

    /**
     * Quick check whether the configured server answers. Uses a short timeout so the caller can
     * fall back to the offline engine without a long wait.
     */
    suspend fun isReachable(url: String = baseUrl): Boolean {
        if (url.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val client = okHttpClient.newBuilder()
                    .callTimeout(REACHABILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder().url(normalized(url) + "api/tags").get().build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun generate(messages: List<LlmMessage>, tools: List<LlmToolDef>): Flow<LlmEvent> =
        flow {
            val url = normalized(baseUrl)
            if (url.isBlank()) {
                emit(LlmEvent.Error(IllegalStateException("No server configured")))
                return@flow
            }

            val body = buildRequestJson(messages, tools).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url + "api/chat").post(body).build()

            val client = okHttpClient.newBuilder()
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val accumulated = StringBuilder()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        emit(LlmEvent.Error(
                            IllegalStateException("Server responded ${response.code}")
                        ))
                        return@flow
                    }
                    val source = response.body?.source()
                        ?: run {
                            emit(LlmEvent.Error(IllegalStateException("Empty response")))
                            return@flow
                        }
                    // Ollama streams newline-delimited JSON objects
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val obj = try {
                            JSONObject(line)
                        } catch (e: Exception) {
                            continue
                        }
                        val piece = obj.optJSONObject("message")?.optString("content").orEmpty()
                        if (piece.isNotEmpty()) {
                            accumulated.append(piece)
                            emit(LlmEvent.Token(piece))
                        }
                        if (obj.optBoolean("done", false)) break
                    }
                }

                val call = if (tools.isNotEmpty()) {
                    ToolCallParser.parse(accumulated.toString())
                } else {
                    null
                }
                if (call != null) {
                    emit(LlmEvent.ToolCall(call))
                } else {
                    emit(LlmEvent.Done(accumulated.toString().trim()))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Ollama generation failed", t)
                emit(LlmEvent.Error(t))
            }
        }.flowOn(Dispatchers.IO)

    private fun buildRequestJson(messages: List<LlmMessage>, tools: List<LlmToolDef>): JSONObject {
        val toolBlock = ChatFormat.toolInstructions(tools)
        val jsonMessages = JSONArray()
        var injectedTools = toolBlock.isBlank()
        for (m in messages) {
            val content = when {
                m.role == LlmRole.SYSTEM && !injectedTools -> {
                    injectedTools = true
                    m.content + "\n\n" + toolBlock
                }
                m.role == LlmRole.TOOL -> "Result of ${m.toolName ?: "tool"}: ${m.content}"
                else -> m.content
            }
            jsonMessages.put(JSONObject().put("role", m.role.wire).put("content", content))
        }
        if (!injectedTools) {
            val sys = JSONObject().put("role", LlmRole.SYSTEM.wire).put("content", toolBlock)
            jsonMessages.put(0, sys)
        }
        return JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("messages", jsonMessages)
            .put("stream", true)
    }

    private fun normalized(url: String): String {
        if (url.isBlank()) return ""
        val withScheme = if (url.startsWith("http")) url else "http://$url"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    companion object {
        private val TAG = OllamaEngine::class.simpleName
        const val DEFAULT_MODEL = "qwen2.5:7b"
        private const val REACHABILITY_TIMEOUT_MS = 1500L
        private const val READ_TIMEOUT_MS = 120_000L
    }
}
