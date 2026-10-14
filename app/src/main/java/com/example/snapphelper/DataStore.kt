package com.example.snapphelper

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DataStore {
    private const val FILE = "observations.jsonl"

    private val COLS = listOf(
        "id", "ts", "local_time", "dow", "hour", "batch_id", "kind",
        "price_toman", "net_toman", "other_prices_toman",
        "pickup_min", "pickup_km", "trip_min", "trip_km",
        "total_min", "total_km", "net_per_min", "net_per_km",
        "surge_toman", "phone_deduction_toman", "origin", "destination"
    )

    @Synchronized
    fun append(ctx: Context, obj: JSONObject) {
        File(ctx.filesDir, FILE).appendText(obj.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun readAll(ctx: Context): List<JSONObject> {
        val f = File(ctx.filesDir, FILE)
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

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }

    fun toJson(list: List<JSONObject>): String {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("source", "snapp-helper")
        root.put("exported_at", System.currentTimeMillis())
        val arr = JSONArray()
        for (o in list) arr.put(o)
        root.put("records", arr)
        return root.toString(1)
    }

    fun toCsv(list: List<JSONObject>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(COLS.joinToString(",")).append("\r\n")
        for (o in list) {
            sb.append(COLS.joinToString(",") { cell(o, it) }).append("\r\n")
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

    fun saveToDownloads(ctx: Context, name: String, mime: String, content: String): Boolean {
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
}
