package org.stypox.dicio.health

/**
 * A single recorded activity/track, typically parsed from a GPX file (a run, ride, walk…).
 *
 * @param name a label for the activity (often the file name or GPX track name)
 * @param startEpochMillis start time in epoch millis, or null if unknown
 * @param distanceMeters total distance in meters
 * @param durationSeconds moving/total duration in seconds, or null if unknown
 * @param elevationGainMeters cumulative positive elevation gain in meters
 * @param pointCount number of track points
 */
data class ActivityTrack(
    val name: String,
    val startEpochMillis: Long?,
    val distanceMeters: Double,
    val durationSeconds: Long?,
    val elevationGainMeters: Double,
    val pointCount: Int,
)

/**
 * Aggregated health metrics for a single day, typically parsed from a Gadgetbridge database export.
 *
 * @param date ISO date (yyyy-MM-dd)
 * @param steps step count for the day, or null if unavailable
 * @param avgHeartRate average heart rate (bpm) for the day, or null
 * @param minHeartRate minimum non-zero heart rate (bpm), or null
 * @param maxHeartRate maximum heart rate (bpm), or null
 * @param sleepMinutes total detected sleep minutes, or null
 */
data class DailyMetric(
    val date: String,
    val steps: Int? = null,
    val avgHeartRate: Int? = null,
    val minHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val sleepMinutes: Int? = null,
)

/**
 * The full imported health dataset, kept entirely offline.
 *
 * @param activities parsed GPX activities, newest first
 * @param dailyMetrics per-day metrics, newest first
 * @param sources human-readable list of imported source files
 * @param importedAtMillis when the last import happened
 */
data class HealthData(
    val activities: List<ActivityTrack> = emptyList(),
    val dailyMetrics: List<DailyMetric> = emptyList(),
    val sources: List<String> = emptyList(),
    val importedAtMillis: Long = 0L,
) {
    val isEmpty: Boolean get() = activities.isEmpty() && dailyMetrics.isEmpty()
}
