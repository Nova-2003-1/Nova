package org.stypox.dicio.llm.orchestrator.tools

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.llm.LlmToolDef
import org.stypox.dicio.llm.LlmToolParam
import org.stypox.dicio.llm.orchestrator.LlmTool
import org.stypox.dicio.skills.search.searchOnDuckDuckGo

/**
 * Exposes Dicio's DuckDuckGo search skill to the model, for questions that need up-to-date or
 * external information the model itself does not know. Reuses the existing search implementation so
 * the graphical result list is identical to the built-in skill.
 */
class WebSearchTool : LlmTool {
    override val definition = LlmToolDef(
        name = "web_search",
        description = "Search the web for information you don't know or that may be recent " +
            "(news, facts, people, places). Returns a list of results to show the user.",
        params = listOf(
            LlmToolParam(
                name = "query",
                type = "string",
                description = "The search query.",
            )
        ),
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): SkillOutput {
        val query = args["query"]?.trim()
        return searchOnDuckDuckGo(ctx, query, askAgainIfNoResult = false)
    }
}
