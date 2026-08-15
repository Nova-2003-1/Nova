package org.stypox.dicio.llm.orchestrator

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.llm.LlmToolDef

/**
 * A capability that the LLM orchestrator can invoke on the user's behalf. Each tool corresponds to
 * an action (usually backed by an existing Dicio skill) and is advertised to the model through its
 * [definition].
 *
 * To expose a new skill to the model, implement this interface and register the tool in
 * [org.stypox.dicio.llm.LlmModule.provideToolRegistry].
 */
interface LlmTool {
    /** How this tool is described to the model (name, purpose, parameters). */
    val definition: LlmToolDef

    /**
     * Executes the tool with the [args] the model provided (raw strings keyed by parameter name)
     * and returns the [SkillOutput] to present to the user.
     *
     * @param ctx the skill context (resources, locale, speech output, …)
     * @param args the arguments extracted from the model's tool call
     */
    suspend fun execute(ctx: SkillContext, args: Map<String, String>): SkillOutput
}
