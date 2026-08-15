package org.stypox.dicio.health

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parses a Gadgetbridge SQLite export database into [DailyMetric]s.
 *
 * Gadgetbridge stores samples in per-device tables whose names end in `ACTIVITY_SAMPLE`
 * (e.g. `MI_BAND_ACTIVITY_SAMPLE`, `HUAMI_EXTENDED_ACTIVITY_SAMPLE`, `PINE_TIME_ACTIVITY_SAMPLE`…).
 * The exact schema differs per device, but the columns we need are consistently named:
 *  - `TIMESTAMP` (unix seconds)
 *  - `STEPS`
 *  - `HEART_RATE` (255 / -1 / 0 mean "no reading")
 *
 * We discover the sample tables dynamically from `sqlite_master`, read those three columns where
 * present, and aggregate per day. Sleep is intentionally left out here because it is encoded
 * differently across devices (via activity "kind" bitfields) — see the comment in
 * [readSampleTable] for where to add device-specific sleep decoding.
 *
 * Opening arbitrary SQLite files read-only is safe; if a table lacks a column we skip it.
 */
object GadgetbridgeImporter {

    private val TAG = GadgetbridgeImporter::class.simpleName
    private val DAY_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    /** Accumulator for a single day while scanning samples. */
    private class DayAgg {
        var steps = 0
        var hrSum = 0L
        var hrCount = 0
        var hrMin = Int.MAX_VALUE
        var hrMax = Int.MIN_VALUE
    }

    fun importDatabase(dbFile: File): List<DailyMetric> {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val sampleTables = findSampleTables(db)
            if (sampleTables.isEmpty()) {
                Log.w(TAG, "No *ACTIVITY_SAMPLE tables found in Gadgetbridge DB")
                return emptyList()
            }
            val perDay = HashMap<String, DayAgg>()
            for (table in sampleTables) {
                readSampleTable(db, table, perDay)
            }
            perDay.entries
                .sortedByDescending { it.key }
                .map { (date, agg) -> agg.toMetric(date) }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read Gadgetbridge database", t)
            emptyList()
        } finally {
            db?.close()
        }
    }

    private fun findSampleTables(db: SQLiteDatabase): List<String> {
        val tables = ArrayList<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%ACTIVITY_SAMPLE'",
            null
        ).use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        return tables
    }

    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) cols.add(c.getString(nameIdx).uppercase())
        }
        return cols
    }

    private fun readSampleTable(
        db: SQLiteDatabase,
        table: String,
        perDay: HashMap<String, DayAgg>,
    ) {
        val cols = columnsOf(db, table)
        if ("TIMESTAMP" !in cols) return
        val hasSteps = "STEPS" in cols
        val hasHr = "HEART_RATE" in cols
        if (!hasSteps && !hasHr) return

        val selected = buildList {
            add("TIMESTAMP")
            if (hasSteps) add("STEPS")
            if (hasHr) add("HEART_RATE")
        }.joinToString(", ") { "`$it`" }

        db.rawQuery("SELECT $selected FROM `$table`", null).use { c ->
            val tsIdx = c.getColumnIndex("TIMESTAMP")
            val stepsIdx = if (hasSteps) c.getColumnIndex("STEPS") else -1
            val hrIdx = if (hasHr) c.getColumnIndex("HEART_RATE") else -1
            while (c.moveToNext()) {
                val tsSeconds = c.getLong(tsIdx)
                if (tsSeconds <= 0) continue
                val day = DAY_FORMAT.format(Instant.ofEpochSecond(tsSeconds))
                val agg = perDay.getOrPut(day) { DayAgg() }
                if (stepsIdx >= 0) {
                    val steps = c.getInt(stepsIdx)
                    if (steps in 1..100_000) agg.steps += steps
                }
                if (hrIdx >= 0) {
                    val hr = c.getInt(hrIdx)
                    // 0 / -1 / 255 are Gadgetbridge sentinels for "no valid reading"
                    if (hr in 25..250) {
                        agg.hrSum += hr
                        agg.hrCount++
                        if (hr < agg.hrMin) agg.hrMin = hr
                        if (hr > agg.hrMax) agg.hrMax = hr
                    }
                }
                // To add sleep: decode the device-specific activity-kind column here (e.g.
                // RAW_KIND / RAW_INTENSITY) and count minutes classified as light/deep sleep.
            }
        }
    }

    private fun DayAgg.toMetric(date: String): DailyMetric = DailyMetric(
        date = date,
        steps = steps.takeIf { it > 0 },
        avgHeartRate = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
        minHeartRate = hrMin.takeIf { it != Int.MAX_VALUE },
        maxHeartRate = hrMax.takeIf { it != Int.MIN_VALUE },
        sleepMinutes = null,
    )
}
