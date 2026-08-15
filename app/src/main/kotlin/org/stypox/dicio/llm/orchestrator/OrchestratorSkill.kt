package org.stypox.dicio.llm.orchestrator

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.util.RecognizeEverythingSkill

/**
 * [SkillInfo] describing the LLM orchestrator, used only as metadata (name/icon shown in the
 * interaction log). The skill is not built through the normal ranker: when the LLM orchestrator is
 * enabled, [org.stypox.dicio.eval.SkillEvaluator] constructs [OrchestratorSkill] directly with the
 * injected [LlmOrchestrator] and routes every input to it.
 */
object OrchestratorInfo : SkillInfo("llm_orchestrator") {
    override fun name(context: Context): String = "Local AI"

    override fun sentenceExample(context: Context): String = "Ask me anything"

    @Composable
    override fun icon() = rememberVectorPainter(Icons.Default.AutoAwesome)

    // Not built via the ranker; the evaluator constructs OrchestratorSkill with the DI orchestrator.
    override fun build(ctx: SkillContext): Skill<*>? = null
}

/**
 * A [RecognizeEverythingSkill] that forwards every user utterance to the [LlmOrchestrator], which
 * decides whether to answer directly or call a tool (a wrapped Dicio skill).
 */
class OrchestratorSkill(
    private val orchestrator: LlmOrchestrator,
) : RecognizeEverythingSkill(OrchestratorInfo) {
    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        return orchestrator.handle(ctx, inputData)
    }
}
