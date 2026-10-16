package com.example.snapphelper

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataStore {
    const val OBS = "observations.jsonl"
    const val TRIPS = "trips.jsonl"

    private val OBS_COLS = listOf(
        "id", "ts", "local_time", "dow", "hour", "kind", "stops",
        "price_toman", "net_toman",
        "pickup_min", "pickup_km", "trip_min", "trip_km",
        "total_min", "total_min_with_wait", "total_km", "has_km",
        "net_per_min", "net_per_min_with_wait", "net_per_km",
        "surge_toman", "phone_deduction_toman", "wait_min_low", "wait_min_high",
        "pro", "suggested", "long_trip", "tags",
        "origin", "origin_area", "destination", "dest_area", "destinations"
    )

    private val TRIP_COLS = listOf(
        "id", "offer_id", "accept_ts", "arrive_ts", "end_ts", "local_time", "dow", "hour",
        "kind", "stops", "price_toman", "net_toman",
        "est_pickup_min", "est_pickup_km", "est_trip_min", "est_trip_km",
        "real_pickup_min", "real_trip_min", "real_total_min",
        "real_to_est_pickup", "real_to_est_trip", "net_per_real_min",
        "wait_min_low", "wait_min_high",
        "origin", "origin_area", "destination", "dest_area", "destinations", "valid"
    )

    @Synchronized
    fun append(ctx: Context, file: String, obj: JSONObject) {
        File(ctx.filesDir, file).appendText(obj.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun readAll(ctx: Context, file: String): List<JSONObject> {
        val f = File(ctx.filesDir, file)
        if (!f.exists()) return emptyList()
        val out = ArrayList<JSONObject>()
        for (line in f.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            try {
                out.add(JSONObject(line))
            } catch (e: Exception) {
            }
        }
        return out
    }

    // cards: if the same id appears again (km upgrade), the last version wins
    fun readObs(ctx: Context): List<JSONObject> {
        val m = LinkedHashMap<String, JSONObject>()
        for ((i, o) in readAll(ctx, OBS).withIndex()) {
            val id = o.optString("id", "x$i")
            m[id] = o
        }
        return m.values.toList()
    }

    @Synchronized
    fun clearAll(ctx: Context) {
        File(ctx.filesDir, OBS).delete()
        File(ctx.filesDir, TRIPS).delete()
    }

    private fun toJson(cards: List<JSONObject>, trips: List<JSONObject>): String {
        val root = JSONObject()
        root.put("schema", 4)
        root.put("source", "snapp-helper")
        root.put("exported_at", System.currentTimeMillis())
        val a = JSONArray()
        for (o in cards) a.put(o)
        val b = JSONArray()
        for (o in trips) b.put(o)
        root.put("cards", a)
        root.put("trips", b)
        return root.toString(1)
    }

    private fun toCsv(list: List<JSONObject>, cols: List<String>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(cols.joinToString(",")).append("\r\n")
        for (o in list) {
            sb.append(cols.joinToString(",") { cell(o, it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun cell(o: JSONObject, k: String): String {
        val v = o.opt(k)
        val s = when {
            v == null || v == JSONObject.NULL -> ""
            v is JSONArray -> (0 until v.length()).joinToString(";") { v.get(it).toString() }
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    private fun saveToDownloads(ctx: Context, name: String, mime: String, content: String): Boolean {
        return try {
            val values = ContentValues()
            values.put(MediaStore.Downloads.DISPLAY_NAME, name)
            values.put(MediaStore.Downloads.MIME_TYPE, mime)
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val os = ctx.contentResolver.openOutputStream(uri) ?: return false
            os.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // returns a message for the user
    fun export(ctx: Context): String {
        val cards = readObs(ctx)
        val trips = readAll(ctx, TRIPS)
        if (cards.isEmpty() && trips.isEmpty()) return "No data yet"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val ok1 = saveToDownloads(ctx, "snapp_data_$stamp.json", "application/json", toJson(cards, trips))
        val ok2 = saveToDownloads(ctx, "snapp_cards_$stamp.csv", "text/csv", toCsv(cards, OBS_COLS))
        val ok3 = saveToDownloads(ctx, "snapp_trips_$stamp.csv", "text/csv", toCsv(trips, TRIP_COLS))
        return if (ok1 && ok2 && ok3) "Saved 3 files to Downloads" else "Export failed"
    }
}
