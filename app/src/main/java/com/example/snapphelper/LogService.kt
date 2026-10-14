package com.example.snapphelper

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LogService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return

        val sb = StringBuilder()
        collect(root, sb, 0)
        val dump = "[$pkg]\n$sb"

        val ed = getSharedPreferences("log", MODE_PRIVATE).edit()
        ed.putString("any", dump)
        if (sb.contains("قبول سفر")) ed.putString("trip", dump)
        ed.apply()
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 40) return
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        if (!t.isNullOrBlank()) sb.append("T: ").append(t).append('\n')
        if (!d.isNullOrBlank()) sb.append("D: ").append(d).append('\n')
        for (i in 0 until n.childCount) collect(n.getChild(i), sb, depth + 1)
    }

    override fun onInterrupt() {}
}
