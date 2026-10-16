package com.example.snapphelper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var tvSum: TextView
    private lateinit var tvList: TextView

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
        b3.text = "Export to Downloads (JSON + 2 CSV)"
        b3.setOnClickListener {
            Thread {
                val msg = try {
                    DataStore.export(applicationContext)
                } catch (e: Throwable) {
                    "Export failed: $e"
                }
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }

        val b4 = Button(this)
        b4.text = "Delete all data"
        b4.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete all saved data?")
                .setPositiveButton("Delete") { _, _ ->
                    DataStore.clearAll(this)
                    Analyzer.clear()
                    getSharedPreferences("log", MODE_PRIVATE).edit()
                        .remove("unparsed").remove("scr_pickup").remove("scr_trip").remove("scr_after")
                        .remove("active").apply()
                    show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        tvSum = TextView(this)
        tvSum.textSize = 14f
        tvSum.layoutDirection = View.LAYOUT_DIRECTION_RTL
        tvSum.textDirection = View.TEXT_DIRECTION_RTL
        tvSum.setPadding(0, 16, 0, 16)

        tvList = TextView(this)
        tvList.textSize = 12f
        tvList.setTextIsSelectable(true)

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.addView(tvSum)
        inner.addView(tvList)
        val sv = ScrollView(this)
        sv.addView(inner)

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

    private fun show() {
        Thread {
            val res: Pair<String, String> = try {
                Analyzer.ensureLoaded(applicationContext)
                val p = getSharedPreferences("log", MODE_PRIVATE)
                val sb = StringBuilder()
                sb.append("Service status: ").append(p.getString("status", "(none)")).append('\n')
                val act = p.getString("active", null)
                if (act != null) {
                    val st = try {
                        JSONObject(act).optInt("state")
                    } catch (e: Throwable) {
                        -1
                    }
                    sb.append("Active trip state: ").append(st).append(" (1 pickup, 2 trip)\n")
                }
                sb.append('\n').append(Analyzer.listText())
                val sp = p.getString("scr_pickup", null)
                val stp = p.getString("scr_trip", null)
                val saf = p.getString("scr_after", null)
                if (sp != null) sb.append("\n=== scr_pickup ===\n").append(sp)
                if (stp != null) sb.append("\n=== scr_trip ===\n").append(stp)
                if (saf != null) sb.append("\n=== scr_after ===\n").append(saf)
                val un = p.getString("unparsed", null)
                if (un != null) sb.append("\n=== LAST UNPARSED OFFER SCREEN ===\n").append(un)
                Pair(Analyzer.summary(), sb.toString())
            } catch (e: Throwable) {
                Pair("error: $e", "")
            }
            runOnUiThread {
                tvSum.text = res.first
                tvList.text = res.second
            }
        }.start()
    }
}
