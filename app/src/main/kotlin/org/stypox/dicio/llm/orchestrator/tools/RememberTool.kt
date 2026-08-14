package org.stypox.dicio.llm.orchestrator.tools

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.llm.KnowledgeStore
import org.stypox.dicio.llm.LlmToolDef
import org.stypox.dicio.llm.LlmToolParam
import org.stypox.dicio.llm.orchestrator.LlmAnswerOutput
import org.stypox.dicio.llm.orchestrator.LlmTool

/**
 * Lets the model persist something it learned about the user into the offline [KnowledgeStore]
 * (the `assistant-memory.md` Markdown file). This is the "mitlernen" capability: the model is
 * instructed to call this whenever the user shares a durable preference or fact about themselves.
 *
 * Everything stays on device; the file is also readable by the user in settings.
 */
class RememberTool(
    private val knowledgeStore: KnowledgeStore,
) : LlmTool {
    override val definition = LlmToolDef(
        name = "remember",
        description = "Save a durable fact or preference the user told you about themselves " +
            "(e.g. their name, home city, likes/dislikes, routines) so you can recall it later. " +
            "Only save lasting facts about the user, not one-off requests.",
        params = listOf(
            LlmToolParam(
                name = "fact",
                type = "string",
                description = "The fact to remember, phrased as a short standalone statement, " +
                    "e.g. 'The user's name is Eddie' or 'The user prefers metric units'.",
            )
        ),
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): SkillOutput {
        val fact = args["fact"]?.trim().orEmpty()
        if (fact.isBlank()) {
            return LlmAnswerOutput("")
        }
        val isNew = knowledgeStore.remember(fact)
        // Speak a short confirmation. Kept generic/localizable-friendly and unobtrusive.
        return LlmAnswerOutput(if (isNew) "Okay, I'll remember that." else "")
    }
}
