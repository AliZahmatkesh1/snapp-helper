package com.example.snapphelper

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LogService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingKey: String? = null
    private var pendingRunnable: Runnable? = null
    private val seen = HashMap<String, Long>()
    private var batchKey = ""
    private var batchId = 0L
    private var lastOfferTs = 0L
    private var lastUnparsed = ""
    private var lastRun = 0L
    private var nodeCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        note("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val ev = event ?: return
            if (ev.packageName?.toString() != "cab.snapp.driver") return

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastRun < 300) return
            lastRun = nowMs

            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != "cab.snapp.driver") return

            val texts = ArrayList<String>()
            val descs = ArrayList<String>()
            nodeCount = 0
            collect(root, texts, descs, 0)
            if (texts.none { it.contains("قبول سفر") }) return

            val obj = OfferParser.parse(texts, nowMs)
            if (obj == null) {
                saveUnparsed(texts, descs)
                return
            }

            val prices = ArrayList<Long>()
            prices.add(obj.getLong("price_toman"))
            val arr = obj.getJSONArray("other_prices_toman")
            for (i in 0 until arr.length()) prices.add(arr.getLong(i))
            prices.sort()
            val bk = prices.joinToString(",")
            if (bk != batchKey || nowMs - lastOfferTs > 120_000) {
                batchKey = bk
                batchId = nowMs
            }
            lastOfferTs = nowMs
            obj.put("batch_id", batchId)

            val key = OfferParser.keyOf(obj)
            if (key == pendingKey) return

            val last = seen[key]
            if (last != null && nowMs - last < 600_000) {
                pendingRunnable?.let { handler.removeCallbacks(it) }
                pendingKey = null
                return
            }

            pendingRunnable?.let { handler.removeCallbacks(it) }
            pendingKey = key
            val r = Runnable {
                try {
                    DataStore.append(applicationContext, obj)
                    if (seen.size > 500) seen.clear()
                    seen[key] = System.currentTimeMillis()
                    if (pendingKey == key) pendingKey = null
                } catch (e: Throwable) {
                    note("save error: " + e)
                }
            }
            pendingRunnable = r
            handler.postDelayed(r, 700)
        } catch (e: Throwable) {
            note("event error: " + e + " " + (e.stackTrace.firstOrNull() ?: ""))
        }
    }

    private fun note(msg: String) {
        try {
            getSharedPreferences("log", MODE_PRIVATE).edit()
                .putString("status", System.currentTimeMillis().toString() + " " + msg)
                .apply()
        } catch (e: Throwable) {
        }
    }

    private fun saveUnparsed(texts: List<String>, descs: List<String>) {
        val sb = StringBuilder()
        for (t in texts) sb.append("T: ").append(t).append('\n')
        for (d in descs) sb.append("D: ").append(d).append('\n')
        val s = sb.toString()
        if (s == lastUnparsed) return
        lastUnparsed = s
        getSharedPreferences("log", MODE_PRIVATE).edit().putString("unparsed", s).apply()
    }

    private fun collect(
        n: AccessibilityNodeInfo?,
        texts: MutableList<String>,
        descs: MutableList<String>,
        depth: Int
    ) {
        if (n == null || depth > 40 || nodeCount > 600) return
        nodeCount++
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        if (!t.isNullOrBlank()) texts.add(t)
        if (!d.isNullOrBlank()) descs.add(d)
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (e: Throwable) { null }
            collect(c, texts, descs, depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        note("destroyed")
        super.onDestroy()
    }
}
