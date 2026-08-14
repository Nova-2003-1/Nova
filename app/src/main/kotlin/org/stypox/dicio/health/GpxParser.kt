package org.stypox.dicio.health

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal, dependency-free GPX parser. Reads `<trkpt>` points (lat/lon, optional `<ele>` and
 * `<time>`) and aggregates them into a single [ActivityTrack]: total distance (haversine between
 * consecutive points), positive elevation gain, duration and point count.
 *
 * Works for GPX exported by Gadgetbridge, Strava, OsmAnd, etc. Multiple track segments are merged.
 */
object GpxParser {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun parse(input: InputStream, fallbackName: String): ActivityTrack? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var name: String? = null
        var pointCount = 0
        var distance = 0.0
        var elevationGain = 0.0
        var firstTime: Long? = null
        var lastTime: Long? = null

        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevEle: Double? = null

        // per-point temporaries
        var curLat: Double? = null
        var curLon: Double? = null
        var curEle: Double? = null
        var curTime: Long? = null
        var readingTrkpt = false
        var textTarget: String? = null // "ele" or "time"

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "trkpt", "rtept" -> {
                        readingTrkpt = true
                        curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        curEle = null
                        curTime = null
                    }
                    "ele" -> if (readingTrkpt) textTarget = "ele"
                    "time" -> if (readingTrkpt) textTarget = "time"
                    "name" -> if (!readingTrkpt && name == null) textTarget = "name"
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    when (textTarget) {
                        "ele" -> curEle = text.toDoubleOrNull()
                        "time" -> curTime = parseTime(text)
                        "name" -> if (text.isNotBlank()) name = text
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "ele", "time", "name" -> textTarget = null
                    "trkpt", "rtept" -> {
                        val lat = curLat
                        val lon = curLon
                        if (lat != null && lon != null) {
                            pointCount++
                            if (prevLat != null && prevLon != null) {
                                distance += haversine(prevLat!!, prevLon!!, lat, lon)
                            }
                            if (curEle != null && prevEle != null && curEle!! > prevEle!!) {
                                elevationGain += curEle!! - prevEle!!
                            }
                            curTime?.let { t ->
                                if (firstTime == null) firstTime = t
                                lastTime = t
                            }
                            prevLat = lat
                            prevLon = lon
                            if (curEle != null) prevEle = curEle
                        }
                        readingTrkpt = false
                    }
                }
            }
            event = parser.next()
        }

        if (pointCount == 0) return null

        val duration = if (firstTime != null && lastTime != null && lastTime!! > firstTime!!) {
            (lastTime!! - firstTime!!) / 1000
        } else {
            null
        }

        return ActivityTrack(
            name = name ?: fallbackName,
            startEpochMillis = firstTime,
            distanceMeters = distance,
            durationSeconds = duration,
            elevationGainMeters = elevationGain,
            pointCount = pointCount,
        )
    }

    private fun parseTime(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
