package org.stypox.dicio.llm

/**
 * The chat role of a message sent to or received from the LLM.
 */
enum class LlmRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

/**
 * A single message in an LLM conversation.
 *
 * @param role who authored the message
 * @param content the text content
 * @param toolName for [LlmRole.TOOL] messages, the name of the tool whose result [content] is
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String,
    val toolName: String? = null,
)

/**
 * A single parameter of a tool that the LLM may call.
 *
 * @param name parameter name (e.g. `"query"`)
 * @param type a human-readable type hint shown to the model (e.g. `"string"`, `"boolean"`)
 * @param description what the parameter means, shown to the model
 * @param required whether the model must provide this parameter
 */
data class LlmToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
)

/**
 * The definition of a tool the LLM can call. This is rendered into the system prompt (see
 * [org.stypox.dicio.llm.orchestrator.ToolRegistry]) so that the model knows which tools exist and
 * how to call them.
 */
data class LlmToolDef(
    val name: String,
    val description: String,
    val params: List<LlmToolParam> = emptyList(),
)

/**
 * A tool call requested by the model, parsed out of its output.
 *
 * @param name the tool name the model asked for
 * @param arguments the arguments, as raw strings keyed by parameter name (parsing to the right
 * type is left to the individual tool, which knows its own schema)
 */
data class LlmToolCall(
    val name: String,
    val arguments: Map<String, String>,
)

/**
 * A streaming event emitted while the model generates a response.
 */
sealed interface LlmEvent {
    /** A chunk of generated text (one or more tokens). */
    data class Token(val text: String) : LlmEvent

    /** The model requested a tool call; generation for this turn is complete. */
    data class ToolCall(val call: LlmToolCall) : LlmEvent

    /** Generation finished with a plain-text answer. [fullText] is the accumulated text. */
    data class Done(val fullText: String) : LlmEvent

    /** Generation failed. */
    data class Error(val throwable: Throwable) : LlmEvent
}
