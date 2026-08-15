package org.stypox.dicio.llm.orchestrator.tools

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.health.HealthDataStore
import org.stypox.dicio.llm.LlmToolDef
import org.stypox.dicio.llm.LlmToolParam
import org.stypox.dicio.llm.orchestrator.LlmAnswerOutput
import org.stypox.dicio.llm.orchestrator.LlmTool
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Exposes the user's imported fitness/health data (from Gadgetbridge ZIP or GPX imports, see
 * [HealthDataStore]) to the model, so questions like "how many steps did I do this week?" or
 * "how long was my last run?" can be answered fully offline.
 *
 * The model calls this tool with an optional metric and day range; the tool computes a concise
 * textual answer from the locally stored data. All processing stays on device.
 */
class HealthQueryTool(
    private val store: HealthDataStore,
) : LlmTool {
    override val definition = LlmToolDef(
        name = "health_data",
        description = "Look up the user's imported fitness and health data (steps, heart rate, " +
            "sleep, and GPS activities like runs/rides). Use for any question about the user's " +
            "own activity, workouts or health metrics.",
        params = listOf(
            LlmToolParam(
                name = "metric",
                type = "string",
                description = "One of: steps, heart_rate, sleep, activities, summary.",
                required = false,
            ),
            LlmToolParam(
                name = "days",
                type = "number",
                description = "How many recent days to consider (default 7).",
                required = false,
            ),
        ),
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): SkillOutput {
        val data = store.data.value
        if (data.isEmpty) {
            return LlmAnswerOutput(
                "You haven't imported any fitness data yet. You can import a Gadgetbridge ZIP " +
                    "export or GPX files from settings."
            )
        }
        val days = args["days"]?.toIntOrNull()?.coerceIn(1, 365) ?: 7
        val metric = args["metric"]?.lowercase()?.trim().orEmpty()

        val cutoff = try {
            LocalDate.now().minusDays((days - 1).toLong()).toString()
        } catch (e: Exception) {
            null
        }
        val recentMetrics = data.dailyMetrics.filter { cutoff == null || it.date >= cutoff }

        val answer = when {
            metric.startsWith("step") -> {
                val total = recentMetrics.mapNotNull { it.steps }.sum()
                val avg = recentMetrics.mapNotNull { it.steps }
                    .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                "In the last $days days you took $total steps" +
                    (avg?.let { ", about $it per day." } ?: ".")
            }
            metric.contains("heart") -> {
                val hrs = recentMetrics.mapNotNull { it.avgHeartRate }
                if (hrs.isEmpty()) "No heart-rate data in the last $days days."
                else "Your average heart rate over the last $days days was " +
                    "${hrs.average().roundToInt()} bpm."
            }
            metric.startsWith("sleep") -> {
                val sleeps = recentMetrics.mapNotNull { it.sleepMinutes }
                if (sleeps.isEmpty()) "No sleep data was found in the imported data."
                else {
                    val avg = sleeps.average().roundToInt()
                    "You slept on average ${avg / 60}h ${avg % 60}m over the last $days days."
                }
            }
            metric.startsWith("activit") -> summarizeActivities(data.activities.take(5))
            else -> summary(recentMetrics, data.activities.take(3), days)
        }
        return LlmAnswerOutput(answer)
    }

    private fun summary(
        metrics: List<org.stypox.dicio.health.DailyMetric>,
        activities: List<org.stypox.dicio.health.ActivityTrack>,
        days: Int,
    ): String {
        val totalSteps = metrics.mapNotNull { it.steps }.sum()
        val sb = StringBuilder("In the last $days days: $totalSteps steps")
        metrics.mapNotNull { it.avgHeartRate }.takeIf { it.isNotEmpty() }?.let {
            sb.append(", avg heart rate ${it.average().roundToInt()} bpm")
        }
        sb.append('.')
        if (activities.isNotEmpty()) {
            sb.append(" Recent activities: ").append(summarizeActivities(activities))
        }
        return sb.toString()
    }

    private fun summarizeActivities(
        activities: List<org.stypox.dicio.health.ActivityTrack>,
    ): String {
        if (activities.isEmpty()) return "no recorded activities."
        return activities.joinToString("; ") { a ->
            val dist = "%.1f km".format(a.distanceMeters / 1000)
            val dur = a.durationSeconds?.let { s ->
                val h = s / 3600; val m = (s % 3600) / 60
                if (h > 0) " in ${h}h${m}m" else " in ${m}m"
            } ?: ""
            "${a.name}: $dist$dur"
        }
    }
}
