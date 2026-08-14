package org.stypox.dicio.llm

/**
 * Builds the raw prompt string fed to the model from a list of [LlmMessage]s and the available
 * [LlmToolDef]s.
 *
 * Small on-device models expose different chat templates. Rather than rely on the GGUF's embedded
 * template (which llama.cpp can apply, but which we do not always control), we use an explicit,
 * ChatML-style template that works well with Qwen2.5 and is close enough for TinyLlama/TinyDolphin.
 * If you switch to a model with a very different template, change [SYSTEM_OPEN]…[ASSISTANT_OPEN]
 * here (or make them configurable from settings).
 *
 * The tool list is injected into the system message together with the calling convention described
 * in `docs/local-llm.md`.
 */
object ChatFormat {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    /**
     * The instruction block that teaches the model how to call tools. Kept deliberately short and
     * explicit because small models follow terse instructions better.
     */
    fun toolInstructions(tools: List<LlmToolDef>): String {
        if (tools.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("You can call tools to act on the user's device. ")
        sb.append("To call a tool, reply with ONLY one line of JSON and nothing else:\n")
        sb.append("{\"tool\": \"<name>\", \"arguments\": {<args>}}\n")
        sb.append("If no tool is needed, just answer in plain language. Available tools:\n")
        for (tool in tools) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description)
            if (tool.params.isNotEmpty()) {
                sb.append(" Arguments: ")
                sb.append(tool.params.joinToString(", ") { p ->
                    "${p.name} (${p.type}${if (p.required) "" else ", optional"}): ${p.description}"
                })
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Renders [messages] into a single prompt string, appending the tool instructions to the
     * (first) system message, and ending with an open assistant turn so the model continues.
     */
    fun build(messages: List<LlmMessage>, tools: List<LlmToolDef>): String {
        val sb = StringBuilder()
        val toolBlock = toolInstructions(tools)

        var injectedTools = toolBlock.isEmpty()
        for (message in messages) {
            val content = when (message.role) {
                LlmRole.SYSTEM -> if (!injectedTools) {
                    injectedTools = true
                    message.content + "\n\n" + toolBlock
                } else {
                    message.content
                }
                LlmRole.TOOL -> "Result of ${message.toolName ?: "tool"}: ${message.content}"
                else -> message.content
            }
            sb.append(IM_START).append(message.role.wire).append('\n')
                .append(content).append(IM_END).append('\n')
        }

        // if there was no system message to attach the tool block to, prepend one
        if (!injectedTools) {
            val sysBlock = IM_START + LlmRole.SYSTEM.wire + "\n" + toolBlock + IM_END + "\n"
            sb.insert(0, sysBlock)
        }

        sb.append(IM_START).append(LlmRole.ASSISTANT.wire).append('\n')
        return sb.toString()
    }
}
