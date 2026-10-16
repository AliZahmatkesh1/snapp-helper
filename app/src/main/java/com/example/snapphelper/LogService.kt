package com.example.snapphelper

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.Executors

class LogService : AccessibilityService() {

    private val SNAPP = "cab.snapp.driver"
    private val HIDE_MS = 15_000L
    private val FINALIZE_MS = 90_000L

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var overlay: Overlay? = null

    private var lastRun = 0L
    private var scheduled = false
    private var nodeCount = 0
    private var lastUnparsed = ""

    // offers
    private var lastOffer: JSONObject? = null
    private var lastOfferTs = 0L
    private var batchKey = ""
    private var batchId = 0L
    private val pend = HashMap<String, Runnable>()
    private val pendFull = HashMap<String, String>()
    private val seenTs = HashMap<String, Long>()
    private val seenKm = HashMap<String, Boolean>()

    // overlay
    private var lastKey = ""
    private var lastResult: Analyzer.Result? = null
    private val hideR = Runnable { overlay?.hide() }

    // active trip: 0 none, 1 going to pickup, 2 trip in progress
    private var tripState = 0
    private var activeOffer: JSONObject? = null
    private var tAccept = 0L
    private var tArrive = 0L
    private var leaveTs = 0L
    private var lastTripSeen = 0L
    private var finalizeRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = Overlay(this)
        loadActive()
        exec.execute {
            try {
                Analyzer.ensureLoaded(applicationContext)
            } catch (e: Throwable) {
                note("load error: $e")
            }
        }
        if (tripState == 2 && leaveTs != 0L) scheduleFinalize()
        note("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.packageName?.toString() != SNAPP) return
            val now = System.currentTimeMillis()
            val wait = 300 - (now - lastRun)
            if (wait <= 0) {
                process()
            } else if (!scheduled) {
                scheduled = true
                handler.postDelayed({
                    scheduled = false
                    try {
                        process()
                    } catch (e: Throwable) {
                        note("process error: " + e + " " + (e.stackTrace.firstOrNull() ?: ""))
                    }
                }, wait)
            }
        } catch (e: Throwable) {
            note("event error: " + e + " " + (e.stackTrace.firstOrNull() ?: ""))
        }
    }

    private fun process() {
        val now = System.currentTimeMillis()
        lastRun = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != SNAPP) return

        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        nodeCount = 0
        collect(root, texts, descs, 0)
        if (texts.size + descs.size < 3) return

        val all = ArrayList<String>()
        for (t in texts) all.add(OfferParser.normalizeDigits(t))
        for (t in descs) all.add(OfferParser.normalizeDigits(t))

        val isOffer = texts.any { it.contains("قبول سفر") }
        val atDest = all.any { it.contains("رسیدم") && it.contains("مقصد") }
        val atOrigin = all.any { it.contains("رسیدم") && it.contains("مبد") }

        when {
            isOffer -> handleOffer(texts, descs, now)
            atDest -> handleState(2, texts, descs, now)
            atOrigin -> handleState(1, texts, descs, now)
            else -> handleOther(texts, descs, now)
        }
    }

    // ---------- offers ----------

    private fun handleOffer(texts: List<String>, descs: List<String>, now: Long) {
        if (texts.any { it.contains("اشتراکی") }) {
            hideNow()
            return
        }
        if (tripState != 0) endActiveOnOffer()

        val obj = OfferParser.parse(texts, now)
        if (obj == null) {
            saveUnparsed(texts, descs)
            return
        }

        // cards of the same offer round share a batch id
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

        val key = OfferParser.keyOf(obj)
        val base = OfferParser.baseKeyOf(obj)
        obj.put("batch_id", batchId)
        obj.put("card_key", base)
        lastOffer = obj
        lastOfferTs = now

        showFor(obj, key)
        recordCard(obj, key, base, now)
    }

    private fun showFor(obj: JSONObject, key: String) {
        if (key == lastKey) {
            val r = lastResult
            if (r != null) {
                if (overlay?.isShown() != true) overlay?.show(r.text, r.level)
                scheduleHide()
            }
            return
        }
        lastKey = key
        lastResult = null
        exec.execute {
            val r = try {
                Analyzer.evaluate(obj)
            } catch (e: Throwable) {
                note("eval error: $e")
                null
            }
            handler.post {
                if (key == lastKey && r != null) {
                    lastResult = r
                    overlay?.show(r.text, r.level)
                    scheduleHide()
                }
            }
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideR)
        handler.postDelayed(hideR, HIDE_MS)
    }

    private fun hideNow() {
        handler.removeCallbacks(hideR)
        overlay?.hide()
    }

    private fun recordCard(obj: JSONObject, key: String, base: String, now: Long) {
        val hasKm = obj.optBoolean("has_km", false)
        val kind = obj.optString("kind")
        val needsKm = (kind == "normal" || kind == "phone") && !hasKm

        val savedTs = seenTs[base]
        val savedKm = seenKm[base] == true
        if (savedTs != null && now - savedTs < 600_000 && (savedKm || !hasKm)) return
        if (pendFull[base] == key) return

        pend[base]?.let { handler.removeCallbacks(it) }
        pendFull[base] = key
        val delay = if (needsKm) 3500L else 700L
        val r = Runnable {
            try {
                pend.remove(base)
                pendFull.remove(base)
                saveObs(obj, base)
            } catch (e: Throwable) {
                note("save error: $e")
            }
        }
        pend[base] = r
        handler.postDelayed(r, delay)
    }

    private fun saveObs(obj: JSONObject, base: String) {
        DataStore.append(applicationContext, DataStore.OBS, obj)
        Analyzer.addObs(obj)
        if (seenTs.size > 500) {
            seenTs.clear()
            seenKm.clear()
        }
        seenTs[base] = System.currentTimeMillis()
        seenKm[base] = obj.optBoolean("has_km", false)
    }

    private fun flushOffer(off: JSONObject) {
        val base = OfferParser.baseKeyOf(off)
        val p = pend[base]
        if (p != null) {
            handler.removeCallbacks(p)
            pend.remove(base)
            pendFull.remove(base)
        }
        val saved = seenTs[base] != null && (seenKm[base] == true || !off.optBoolean("has_km", false))
        if (!saved) saveObs(off, base)
    }

    // ---------- trip states ----------

    private fun handleState(s: Int, texts: List<String>, descs: List<String>, now: Long) {
        hideNow()
        lastKey = ""
        if (s == 1) {
            if (tripState == 0) {
                val off = lastOffer
                activeOffer = if (off != null && now - lastOfferTs < 120_000) off else null
                val a = activeOffer
                if (a != null) flushOffer(a)
                lastOffer = null
                tripState = 1
                tAccept = now
                tArrive = 0
                leaveTs = 0
                putScreen("scr_pickup", texts, descs)
                saveActive()
            }
        } else {
            if (tripState == 0) {
                tripState = 2
                tArrive = now
                lastTripSeen = now
                leaveTs = 0
                activeOffer = null
                putScreen("scr_trip", texts, descs)
                saveActive()
            } else if (tripState == 1) {
                tripState = 2
                tArrive = now
                lastTripSeen = now
                leaveTs = 0
                putScreen("scr_trip", texts, descs)
                saveActive()
            } else {
                lastTripSeen = now
                if (leaveTs != 0L) {
                    leaveTs = 0
                    cancelFinalize()
                    saveActive()
                }
            }
        }
    }

    private fun handleOther(texts: List<String>, descs: List<String>, now: Long) {
        hideNow()
        if (tripState == 2 && leaveTs == 0L) {
            leaveTs = now
            putScreen("scr_after", texts, descs)
            saveActive()
            scheduleFinalize()
        }
    }

    private fun endActiveOnOffer() {
        if (tripState == 2) {
            val end = when {
                leaveTs != 0L -> leaveTs
                lastTripSeen != 0L -> lastTripSeen
                else -> System.currentTimeMillis()
            }
            finalizeTrip(end)
        } else {
            resetTrip()
        }
    }

    private fun scheduleFinalize() {
        cancelFinalize()
        val r = Runnable {
            try {
                if (tripState == 2 && leaveTs != 0L) finalizeTrip(leaveTs)
            } catch (e: Throwable) {
                note("finalize error: $e")
            }
        }
        finalizeRunnable = r
        handler.postDelayed(r, FINALIZE_MS)
    }

    private fun cancelFinalize() {
        finalizeRunnable?.let { handler.removeCallbacks(it) }
        finalizeRunnable = null
    }

    private fun finalizeTrip(end: Long) {
        val off = activeOffer
        if (off != null && tArrive > 0 && tAccept > 0) {
            val t = Analyzer.buildTrip(off, tAccept, tArrive, if (end > tArrive) end else tArrive)
            DataStore.append(applicationContext, DataStore.TRIPS, t)
            Analyzer.addTrip(t)
        }
        resetTrip()
    }

    private fun resetTrip() {
        cancelFinalize()
        tripState = 0
        activeOffer = null
        tAccept = 0
        tArrive = 0
        leaveTs = 0
        lastTripSeen = 0
        try {
            getSharedPreferences("log", MODE_PRIVATE).edit().remove("active").apply()
        } catch (e: Throwable) {
        }
    }

    private fun saveActive() {
        try {
            val j = JSONObject()
            j.put("state", tripState)
            j.put("accept", tAccept)
            j.put("arrive", tArrive)
            j.put("leave", leaveTs)
            j.put("lastSeen", lastTripSeen)
            val off = activeOffer
            if (off != null) {
                val c = JSONObject(off.toString())
                c.remove("raw")
                j.put("offer", c)
            }
            getSharedPreferences("log", MODE_PRIVATE).edit().putString("active", j.toString()).apply()
        } catch (e: Throwable) {
            note("saveActive error: $e")
        }
    }

    private fun loadActive() {
        try {
            val sv = getSharedPreferences("log", MODE_PRIVATE).getString("active", null) ?: return
            val j = JSONObject(sv)
            tripState = j.optInt("state")
            tAccept = j.optLong("accept")
            tArrive = j.optLong("arrive")
            leaveTs = j.optLong("leave")
            lastTripSeen = j.optLong("lastSeen")
            activeOffer = j.optJSONObject("offer")
        } catch (e: Throwable) {
            note("loadActive error: $e")
        }
    }

    // ---------- helpers ----------

    private fun putScreen(key: String, texts: List<String>, descs: List<String>) {
        try {
            val sb = StringBuilder()
            for (t in texts) sb.append("T: ").append(t).append('\n')
            for (d in descs) sb.append("D: ").append(d).append('\n')
            var s = sb.toString()
            if (s.length > 3000) s = s.substring(0, 3000)
            getSharedPreferences("log", MODE_PRIVATE).edit().putString(key, s).apply()
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

    private fun note(msg: String) {
        try {
            getSharedPreferences("log", MODE_PRIVATE).edit()
                .putString("status", System.currentTimeMillis().toString() + " " + msg)
                .apply()
        } catch (e: Throwable) {
        }
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
            val c = try {
                n.getChild(i)
            } catch (e: Throwable) {
                null
            }
            collect(c, texts, descs, depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        note("destroyed")
        try {
            overlay?.hide()
            exec.shutdown()
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }
}
