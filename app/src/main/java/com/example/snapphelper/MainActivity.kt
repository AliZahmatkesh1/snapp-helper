package com.example.snapphelper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(24, 48, 24, 24)

        val b1 = Button(this)
        b1.text = "Open Accessibility Settings"
        b1.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        val b2 = Button(this)
        b2.text = "Refresh"
        b2.setOnClickListener { show() }

        val b3 = Button(this)
        b3.text = "Export to Downloads (JSON + CSV)"
        b3.setOnClickListener { export() }

        val b4 = Button(this)
        b4.text = "Delete all data"
        b4.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete all saved data?")
                .setPositiveButton("Delete") { _, _ ->
                    DataStore.clear(this)
                    show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        tv = TextView(this)
        tv.setTextIsSelectable(true)
        val sv = ScrollView(this)
        sv.addView(tv)

        ll.addView(b1)
        ll.addView(b2)
        ll.addView(b3)
        ll.addView(b4)
        ll.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(ll)
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun export() {
        val list = DataStore.readAll(this)
        if (list.isEmpty()) {
            Toast.makeText(this, "No data yet", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val ok1 = DataStore.saveToDownloads(this, "snapp_data_$stamp.json", "application/json", DataStore.toJson(list))
        val ok2 = DataStore.saveToDownloads(this, "snapp_data_$stamp.csv", "text/csv", DataStore.toCsv(list))
        Toast.makeText(this, if (ok1 && ok2) "Saved to Downloads" else "Export failed", Toast.LENGTH_LONG).show()
    }

    private fun fmt(o: JSONObject, k: String, f: String): String {
        val v = o.optDouble(k)
        return if (v.isNaN()) "-" else String.format(Locale.US, f, v)
    }

    private fun show() {
        val list = DataStore.readAll(this)
        val sb = StringBuilder()
        sb.append("Records: ").append(list.size).append('\n')
        val npm = list.map { it.optDouble("net_per_min") }.filter { !it.isNaN() }
        val npk = list.map { it.optDouble("net_per_km") }.filter { !it.isNaN() }
        if (npm.isNotEmpty()) sb.append(String.format(Locale.US, "Avg net per min: %,.0f\n", npm.average()))
        if (npk.isNotEmpty()) sb.append(String.format(Locale.US, "Avg net per km: %,.0f\n", npk.average()))
        val st = getSharedPreferences("log", MODE_PRIVATE).getString("status", "(none)")
        sb.append("Service status: ").append(st).append('\n')
        sb.append('\n')
        for (o in list.takeLast(10).reversed()) {
            val lt = o.optString("local_time")
            val hm = if (lt.length >= 16) lt.substring(11, 16) else lt
            sb.append(
                String.format(
                    Locale.US,
                    "%s %s | %,d (net %,d) | %s+%s min | %s+%s km | %s/min | %s/km\n",
                    hm, o.optString("dow"), o.optLong("price_toman"), o.optLong("net_toman"),
                    fmt(o, "pickup_min", "%.0f"), fmt(o, "trip_min", "%.0f"),
                    fmt(o, "pickup_km", "%.1f"), fmt(o, "trip_km", "%.1f"),
                    fmt(o, "net_per_min", "%,.0f"), fmt(o, "net_per_km", "%,.0f")
                )
            )
        }
        val un = getSharedPreferences("log", MODE_PRIVATE).getString("unparsed", null)
        if (un != null) sb.append("\n=== LAST UNPARSED OFFER SCREEN ===\n").append(un)
        tv.text = sb.toString()
    }
}
