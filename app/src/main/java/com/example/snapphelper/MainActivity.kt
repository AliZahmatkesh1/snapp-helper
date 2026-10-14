package com.example.snapphelper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(24, 48, 24, 24)

        val b1 = Button(this)
        b1.text = "Open Accessibility Settings"
        b1.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val b2 = Button(this)
        b2.text = "Refresh"
        b2.setOnClickListener { show() }

        tv = TextView(this)
        tv.setTextIsSelectable(true)
        val sv = ScrollView(this)
        sv.addView(tv)

        ll.addView(b1)
        ll.addView(b2)
        ll.addView(sv)
        setContentView(ll)
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun show() {
        val p = getSharedPreferences("log", MODE_PRIVATE)
        tv.text = "=== TRIP ===\n" + p.getString("trip", "(none)") +
            "\n\n=== LAST ANY ===\n" + p.getString("any", "(none)")
    }
}
