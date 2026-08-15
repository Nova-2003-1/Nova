package org.stypox.dicio.llm.orchestrator

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput

/**
 * The [org.dicio.skill.skill.SkillOutput] used when the model answers in plain language (i.e. it
 * did not call a tool). Both the spoken and graphical output are the model's answer text.
 *
 * When the model calls a tool instead, the orchestrator returns that tool's own [SkillOutput]
 * directly, so this class is only for direct answers.
 */
class LlmAnswerOutput(
    private val answer: String,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = answer
}
