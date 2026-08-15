package org.stypox.dicio.llm.orchestrator

import org.stypox.dicio.llm.LlmToolDef

/**
 * Holds the set of [LlmTool]s available to the orchestrator and looks them up by name.
 */
class ToolRegistry(tools: List<LlmTool>) {

    private val byName: Map<String, LlmTool> = tools.associateBy { it.definition.name }

    /** All tool definitions, to render into the prompt. */
    val definitions: List<LlmToolDef> = tools.map { it.definition }

    /** Returns the tool with the given [name], or null if there is no such tool. */
    fun find(name: String): LlmTool? = byName[name]

    val isEmpty: Boolean get() = byName.isEmpty()
}
