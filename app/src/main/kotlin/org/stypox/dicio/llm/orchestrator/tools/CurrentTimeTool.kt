package org.stypox.dicio.llm.orchestrator.tools

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.llm.LlmToolDef
import org.stypox.dicio.llm.orchestrator.LlmTool
import org.stypox.dicio.skills.current_time.CurrentTimeOutput
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Exposes the current time to the model. Because the model has no clock, it is instructed to call
 * this tool for "what time is it?" style questions. Reuses [CurrentTimeOutput] for identical
 * phrasing/UI to the built-in skill.
 */
class CurrentTimeTool : LlmTool {
    override val definition = LlmToolDef(
        name = "current_time",
        description = "Get the current local time on the device. Call this for any question " +
            "about what time it is now.",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): SkillOutput {
        val formatter = DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(ctx.locale)
        val timeStr = LocalTime.now().format(formatter)
        return CurrentTimeOutput(timeStr)
    }
}
