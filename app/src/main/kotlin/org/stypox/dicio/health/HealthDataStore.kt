package org.stypox.dicio.health

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Persists imported [HealthData] fully offline and exposes it to:
 *  - the **user** as a human-readable Markdown summary ([summaryMarkdown] / [markdownFile]),
 *  - the **model** as a compact prompt context ([promptContext]) and via the health query tool.
 *
 * Storage is a small JSON file in the app's private files dir (nothing leaves the device). New
 * imports are merged with existing data (activities de-duplicated by start time + distance, daily
 * metrics replaced per date).
 */
class HealthDataStore(appContext: Context) {

    private val jsonFile = File(appContext.filesDir, "health-data.json")

    /** Markdown mirror of the summary, for the user to view/export. */
    val markdownFile = File(appContext.filesDir, "health-summary.md")

    private val mutex = Mutex()
    private val _data = MutableStateFlow(HealthData())
    val data: StateFlow<HealthData> = _data

    init {
        _data.value = try {
            if (jsonFile.exists()) fromJson(jsonFile.readText()) else HealthData()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read health data", e)
            HealthData()
        }
    }

    /** Merges freshly imported [incoming] data with what is already stored, and persists. */
    suspend fun merge(incoming: HealthData) = mutex.withLock {
        val current = _data.value

        // merge activities, de-duplicating on (startEpochMillis, rounded distance)
        val activityKey = { a: ActivityTrack -> "${a.startEpochMillis}:${a.distanceMeters.roundToInt()}" }
        val mergedActivities = (current.activities + incoming.activities)
            .associateBy(activityKey)
            .values
            .sortedByDescending { it.startEpochMillis ?: 0L }

        // merge daily metrics, newer import wins per date
        val mergedMetrics = (current.dailyMetrics.associateBy { it.date } +
            incoming.dailyMetrics.associateBy { it.date })
            .values
            .sortedByDescending { it.date }

        val merged = HealthData(
            activities = mergedActivities,
            dailyMetrics = mergedMetrics,
            sources = (current.sources + incoming.sources).distinct(),
            importedAtMillis = incoming.importedAtMillis.takeIf { it > 0 }
                ?: current.importedAtMillis,
        )
        _data.value = merged
        persist(merged)
    }

    /** Deletes all imported health data. */
    suspend fun clear() = mutex.withLock {
        _data.value = HealthData()
        withContext(Dispatchers.IO) {
            jsonFile.delete()
            markdownFile.delete()
        }
    }

    /**
     * Compact summary injected into the LLM prompt (only recent data to stay within the context
     * budget). Empty when nothing has been imported.
     */
    fun promptContext(): String {
        val d = _data.value
        if (d.isEmpty) return ""
        val sb = StringBuilder("The user's imported fitness & health data (offline):\n")
        d.dailyMetrics.take(14).forEach { m ->
            sb.append("- ").append(m.date).append(": ")
            val parts = buildList {
                m.steps?.let { add("$it steps") }
                m.avgHeartRate?.let { add("avg HR ${it}bpm") }
                m.sleepMinutes?.let { add("sleep ${it / 60}h${it % 60}m") }
            }
            sb.append(if (parts.isEmpty()) "no metrics" else parts.joinToString(", "))
            sb.append('\n')
        }
        d.activities.take(10).forEach { a ->
            sb.append("- activity ").append(a.name).append(": ")
                .append("%.1f km".format(a.distanceMeters / 1000))
            a.durationSeconds?.let { sb.append(", ").append(formatDuration(it)) }
            if (a.elevationGainMeters >= 1) sb.append(", +${a.elevationGainMeters.roundToInt()}m")
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Full human-readable Markdown for the settings viewer. */
    fun summaryMarkdown(): String {
        val d = _data.value
        val sb = StringBuilder("# Imported fitness & health data\n\n")
        if (d.isEmpty) {
            sb.append("_No data imported yet. Import a Gadgetbridge ZIP export or GPX files " +
                "from settings._\n")
            return sb.toString()
        }
        d.importedAtMillis.takeIf { it > 0 }?.let {
            sb.append("_Last import: ")
                .append(DateTimeFormatter.ISO_LOCAL_DATE
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(it)))
                .append("_\n\n")
        }
        if (d.sources.isNotEmpty()) {
            sb.append("Sources: ").append(d.sources.joinToString(", ")).append("\n\n")
        }
        if (d.dailyMetrics.isNotEmpty()) {
            sb.append("## Daily metrics\n\n| Date | Steps | Avg HR | Min/Max HR |\n")
            sb.append("|------|-------|--------|------------|\n")
            d.dailyMetrics.take(60).forEach { m ->
                sb.append("| ").append(m.date)
                    .append(" | ").append(m.steps?.toString() ?: "-")
                    .append(" | ").append(m.avgHeartRate?.let { "$it" } ?: "-")
                    .append(" | ").append(
                        if (m.minHeartRate != null || m.maxHeartRate != null)
                            "${m.minHeartRate ?: "-"}/${m.maxHeartRate ?: "-"}" else "-"
                    )
                    .append(" |\n")
            }
            sb.append('\n')
        }
        if (d.activities.isNotEmpty()) {
            sb.append("## Activities\n\n| Activity | Distance | Duration | Elev. gain |\n")
            sb.append("|----------|----------|----------|-----------|\n")
            d.activities.take(60).forEach { a ->
                sb.append("| ").append(a.name)
                    .append(" | ").append("%.2f km".format(a.distanceMeters / 1000))
                    .append(" | ").append(a.durationSeconds?.let { formatDuration(it) } ?: "-")
                    .append(" | ").append("${a.elevationGainMeters.roundToInt()} m")
                    .append(" |\n")
            }
        }
        return sb.toString()
    }

    private suspend fun persist(data: HealthData) = withContext(Dispatchers.IO) {
        try {
            jsonFile.writeText(toJson(data))
            markdownFile.writeText(summaryMarkdown())
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist health data", e)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h${m}m" else "${m}m"
    }

    // ----- (de)serialization via org.json to avoid extra schema dependencies -----

    private fun toJson(d: HealthData): String {
        val root = JSONObject()
        root.put("importedAtMillis", d.importedAtMillis)
        root.put("sources", JSONArray(d.sources))
        root.put("activities", JSONArray().apply {
            d.activities.forEach { a ->
                put(JSONObject().apply {
                    put("name", a.name)
                    put("startEpochMillis", a.startEpochMillis ?: JSONObject.NULL)
                    put("distanceMeters", a.distanceMeters)
                    put("durationSeconds", a.durationSeconds ?: JSONObject.NULL)
                    put("elevationGainMeters", a.elevationGainMeters)
                    put("pointCount", a.pointCount)
                })
            }
        })
        root.put("dailyMetrics", JSONArray().apply {
            d.dailyMetrics.forEach { m ->
                put(JSONObject().apply {
                    put("date", m.date)
                    put("steps", m.steps ?: JSONObject.NULL)
                    put("avgHeartRate", m.avgHeartRate ?: JSONObject.NULL)
                    put("minHeartRate", m.minHeartRate ?: JSONObject.NULL)
                    put("maxHeartRate", m.maxHeartRate ?: JSONObject.NULL)
                    put("sleepMinutes", m.sleepMinutes ?: JSONObject.NULL)
                })
            }
        })
        return root.toString()
    }

    private fun fromJson(text: String): HealthData {
        val root = JSONObject(text)
        val activities = root.optJSONArray("activities")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ActivityTrack(
                    name = o.optString("name"),
                    startEpochMillis = o.optLongOrNull("startEpochMillis"),
                    distanceMeters = o.optDouble("distanceMeters", 0.0),
                    durationSeconds = o.optLongOrNull("durationSeconds"),
                    elevationGainMeters = o.optDouble("elevationGainMeters", 0.0),
                    pointCount = o.optInt("pointCount", 0),
                )
            }
        } ?: emptyList()
        val metrics = root.optJSONArray("dailyMetrics")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DailyMetric(
                    date = o.optString("date"),
                    steps = o.optIntOrNull("steps"),
                    avgHeartRate = o.optIntOrNull("avgHeartRate"),
                    minHeartRate = o.optIntOrNull("minHeartRate"),
                    maxHeartRate = o.optIntOrNull("maxHeartRate"),
                    sleepMinutes = o.optIntOrNull("sleepMinutes"),
                )
            }
        } ?: emptyList()
        val sources = root.optJSONArray("sources")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        return HealthData(activities, metrics, sources, root.optLong("importedAtMillis", 0L))
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    companion object {
        private val TAG = HealthDataStore::class.simpleName
    }
}
