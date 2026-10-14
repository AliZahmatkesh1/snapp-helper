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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "cab.snapp.driver") return

        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        collect(root, texts, descs, 0)
        if (texts.none { it.contains("قبول سفر") }) return

        val now = System.currentTimeMillis()
        val obj = OfferParser.parse(texts, now)
        if (obj == null) {
            saveUnparsed(texts, descs)
            return
        }

        // group cards of the same offer round into one batch
        val prices = ArrayList<Long>()
        prices.add(obj.getLong("price_toman"))
        val arr = obj.getJSONArray("other_prices_toman")
        for (i in 0 until arr.length()) prices.add(arr.getLong(i))
        prices.sort()
        val bk = prices.joinToString(",")
        if (bk != batchKey || now - lastOfferTs > 120_000) {
            batchKey = bk
            batchId = now
        }
        lastOfferTs = now
        obj.put("batch_id", batchId)

        val key = OfferParser.keyOf(obj)
        if (key == pendingKey) return

        val last = seen[key]
        if (last != null && now - last < 600_000) {
            // screen went back to an already recorded card
            pendingRunnable?.let { handler.removeCallbacks(it) }
            pendingKey = null
            return
        }

        // wait until the card stays unchanged for 0.7s before recording
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingKey = key
        val r = Runnable {
            DataStore.append(applicationContext, obj)
            if (seen.size > 500) seen.clear()
            seen[key] = System.currentTimeMillis()
            if (pendingKey == key) pendingKey = null
        }
        pendingRunnable = r
        handler.postDelayed(r, 700)
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
        if (n == null || depth > 40) return
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        if (!t.isNullOrBlank()) texts.add(t)
        if (!d.isNullOrBlank()) descs.add(d)
        for (i in 0 until n.childCount) collect(n.getChild(i), texts, descs, depth + 1)
    }

    override fun onInterrupt() {}
}
