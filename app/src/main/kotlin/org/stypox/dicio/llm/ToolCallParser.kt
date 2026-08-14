package org.stypox.dicio.llm

import org.json.JSONObject

/**
 * Detects and parses tool calls out of the model's (streamed) output.
 *
 * The convention (see `docs/local-llm.md`) is that a tool call is a single JSON object of the form
 * `{"tool": "<name>", "arguments": {...}}`. Because small models are not perfectly obedient, this
 * parser is forgiving: it looks for the first balanced `{...}` block anywhere in the accumulated
 * text and tries to interpret it as a tool call. All argument values are returned as strings (the
 * individual tool parses them to the right type).
 */
object ToolCallParser {

    /**
     * Returns the index at which a balanced JSON object starts if the accumulated [text] contains a
     * complete one, or -1 otherwise. Used by the engine to know when it can stop generating early.
     */
    fun indexOfCompleteJsonObject(text: String): Int {
        val start = text.indexOf('{')
        if (start < 0) return -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return start
                    }
                }
            }
        }
        return -1
    }

    /**
     * Tries to parse a [LlmToolCall] out of [text]. Returns null if [text] does not contain a
     * well-formed tool-call JSON object with a `"tool"` string field.
     */
    fun parse(text: String): LlmToolCall? {
        val start = text.indexOf('{')
        if (start < 0) return null
        // find the matching closing brace of the first object
        if (indexOfCompleteJsonObject(text) < 0) return null
        val jsonText = extractFirstObject(text, start) ?: return null

        return try {
            val obj = JSONObject(jsonText)
            val name = obj.optString("tool").takeIf { it.isNotBlank() } ?: return null
            val args = mutableMapOf<String, String>()
            obj.optJSONObject("arguments")?.let { argsObj ->
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    // store every value as its string form (booleans/numbers included)
                    args[key] = argsObj.get(key).toString()
                }
            }
            LlmToolCall(name, args)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFirstObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
