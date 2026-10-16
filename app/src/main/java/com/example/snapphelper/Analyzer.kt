package com.example.snapphelper

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

object Analyzer {
    const val LEARN_N = 30
    private const val BASE_MAX = 300

    private var loaded = false
    private val obs = ArrayList<JSONObject>()
    private val trips = ArrayList<JSONObject>()

    private val BANDS = arrayOf("۰ تا ۶", "۶ تا ۹", "۹ تا ۱۳", "۱۳ تا ۱۷", "۱۷ تا ۲۱", "۲۱ تا ۲۴")

    // level: 0 learning/unknown, 1 great, 2 medium, 3 weak
    class Result(val text: String, val level: Int, val score: Int)

    private class Fac(val v: Double, val conf: Int, val n: Int)
    private class Row(
        val npm: Double, val npk: Double?, val price: Double,
        val band: Int, val tMin: Double, val tKm: Double?
    )
    private class Leg(val min: Double, val km: Double?, val conf: Int)
    private class Base(
        val npm: Double, val npk: Double?, val price: Double,
        val tMin: Double, val tKm: Double?
    )

    // ---------- data ----------

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        obs.clear()
        trips.clear()
        for (o in DataStore.readObs(ctx)) {
            o.remove("raw")
            obs.add(o)
        }
        for (t in DataStore.readAll(ctx, DataStore.TRIPS)) trips.add(t)
        loaded = true
    }

    @Synchronized
    fun clear() {
        obs.clear()
        trips.clear()
        loaded = true
    }

    // same id = upgraded version of the same card (km arrived later)
    @Synchronized
    fun addObs(o: JSONObject) {
        val c = JSONObject(o.toString())
        c.remove("raw")
        val id = c.optString("id")
        var idx = -1
        var i = obs.size - 1
        while (i >= 0 && i >= obs.size - 200) {
            if (obs[i].optString("id") == id) {
                idx = i
                break
            }
            i--
        }
        if (idx >= 0) obs[idx] = c else obs.add(c)
    }

    @Synchronized
    fun addTrip(t: JSONObject) {
        val id = t.optString("id")
        if (trips.any { it.optString("id") == id }) return
        trips.add(t)
    }

    // ---------- helpers ----------

    private fun d(o: JSONObject, k: String): Double? {
        if (!o.has(k) || o.isNull(k)) return null
        val v = o.optDouble(k)
        return if (v.isNaN()) null else v
    }

    private fun s(o: JSONObject, k: String): String? {
        if (!o.has(k) || o.isNull(k)) return null
        val v = o.optString(k)
        return if (v.isBlank()) null else v
    }

    private fun median(l: List<Double>): Double {
        val x = l.sorted()
        val n = x.size
        return if (n % 2 == 1) x[n / 2] else (x[n / 2 - 1] + x[n / 2]) / 2.0
    }

    private fun cl(x: Double, a: Double, b: Double): Double = Math.max(a, Math.min(b, x))

    private fun sc(r: Double): Double = cl(50.0 + (r - 1.0) * 100.0, 0.0, 100.0)

    private fun r2(x: Double): Double = Math.round(x * 100) / 100.0

    private fun band(h: Int): Int = when {
        h < 6 -> 0
        h < 9 -> 1
        h < 13 -> 2
        h < 17 -> 3
        h < 21 -> 4
        else -> 5
    }

    private fun waitAvg(o: JSONObject): Double {
        val lo = d(o, "wait_min_low")
        val hi = d(o, "wait_min_high")
        return if (lo != null && hi != null) (lo + hi) / 2.0 else 0.0
    }

    private fun fa(n: Long): String {
        val neg = n < 0
        val str = Math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, c) in str.withIndex()) {
            if (i > 0 && (str.length - i) % 3 == 0) sb.append('٬')
            sb.append('۰' + (c - '0'))
        }
        return (if (neg) "-" else "") + sb.toString()
    }

    private fun pct(r: Double): String {
        val p = Math.round((r - 1.0) * 100)
        return if (p >= 0) fa(p) + "٪ بالاتر" else fa(-p) + "٪ پایین‌تر"
    }

    private fun okTrip(t: JSONObject): Boolean {
        val k = t.optString("kind")
        return t.optBoolean("valid", false) && (k == "normal" || k == "phone")
    }

    // real time / estimated time ratio, per hour band when there is enough data
    private fun factor(key: String, b: Int): Fac {
        val inBand = ArrayList<Double>()
        val all = ArrayList<Double>()
        for (t in trips) {
            if (!t.optBoolean("valid", false)) continue
            val v = d(t, key) ?: continue
            if (v <= 0.0) continue
            all.add(v)
            if (band(t.optInt("hour")) == b) inBand.add(v)
        }
        return when {
            inBand.size >= 4 -> Fac(cl(median(inBand), 0.5, 6.0), 3, inBand.size)
            all.size >= 4 -> Fac(cl(median(all), 0.5, 6.0), 2, all.size)
            else -> Fac(1.0, 1, all.size)
        }
    }

    private fun rows(): List<Row> {
        val fps = Array(6) { factor("real_to_est_pickup", it).v }
        val fts = Array(6) { factor("real_to_est_trip", it).v }
        val out = ArrayList<Row>()
        for (o in obs) {
            val k = o.optString("kind")
            if (k != "normal" && k != "phone") continue
            if (o.optBoolean("long_trip", false)) continue
            val pm = d(o, "pickup_min") ?: continue
            val tm = d(o, "trip_min") ?: continue
            val net = d(o, "net_toman") ?: continue
            val b = band(o.optInt("hour"))
            val real = pm * fps[b] + tm * fts[b] + waitAvg(o)
            if (real <= 0.5 || net <= 0.0) continue
            val pk = d(o, "pickup_km")
            val tk = d(o, "trip_km")
            val km: Double? = if (pk != null && tk != null) pk + tk else null
            out.add(
                Row(
                    net / real,
                    if (km != null && km > 0.0) net / km else null,
                    d(o, "price_toman") ?: 0.0,
                    b, tm, tk
                )
            )
        }
        return out
    }

    private fun baseOf(rows: List<Row>): Base? {
        if (rows.isEmpty()) return null
        val r = rows.takeLast(BASE_MAX)
        val npks = r.mapNotNull { it.npk }
        val tks = r.mapNotNull { it.tKm }
        return Base(
            median(r.map { it.npm }),
            if (npks.size >= 5) median(npks) else null,
            median(r.map { it.price }),
            median(r.map { it.tMin }),
            if (tks.size >= 3) median(tks) else null
        )
    }

    private fun legEstimate(from: String?, to: String?, b: Int): Leg? {
        if (from == null || to == null) return null
        val direct = trips.filter { t ->
            okTrip(t) && ((s(t, "origin_area") == from && s(t, "dest_area") == to) ||
                (s(t, "origin_area") == to && s(t, "dest_area") == from))
        }
        if (direct.isNotEmpty()) {
            val sb = direct.filter { band(it.optInt("hour")) == b }
            val use = if (sb.isNotEmpty()) sb else direct
            val mins = use.mapNotNull { d(it, "real_trip_min") }
            if (mins.isNotEmpty()) {
                val kms = use.mapNotNull { d(it, "est_trip_km") }
                return Leg(median(mins), if (kms.isNotEmpty()) median(kms) else null, if (use.size >= 2) 3 else 2)
            }
        }
        val touch = trips.filter { t ->
            okTrip(t) && (s(t, "origin_area") == from || s(t, "dest_area") == to ||
                s(t, "origin_area") == to || s(t, "dest_area") == from)
        }
        val tm = touch.mapNotNull { d(it, "real_trip_min") }
        if (tm.size >= 2) {
            val kms = touch.mapNotNull { d(it, "est_trip_km") }
            return Leg(median(tm), if (kms.size >= 2) median(kms) else null, 2)
        }
        return null
    }

    private fun guessLeg(base: Base?, b: Int): Leg? {
        val ok = trips.filter { okTrip(it) }
        val rt = ok.mapNotNull { d(it, "real_trip_min") }
        val ek = ok.mapNotNull { d(it, "est_trip_km") }
        if (rt.size >= 3) return Leg(median(rt), if (ek.size >= 3) median(ek) else base?.tKm, 1)
        if (base == null) return null
        return Leg(base.tMin * factor("real_to_est_trip", b).v, base.tKm, 1)
    }

    // ---------- evaluation of a card ----------

    @Synchronized
    fun evaluate(o: JSONObject): Result {
        val price = d(o, "price_toman") ?: 0.0
        val net = d(o, "net_toman") ?: (price * OfferParser.NET_RATE)
        val b = band(o.optInt("hour"))
        val pMin = d(o, "pickup_min") ?: 0.0
        val pKm = d(o, "pickup_km")
        val tMinSnapp = d(o, "trip_min")
        val tKmSnapp = d(o, "trip_km")
        val w = waitAvg(o)
        val oa = s(o, "origin_area") ?: OfferParser.areaOf(s(o, "origin"))
        val dests = ArrayList<String?>()
        val da = o.optJSONArray("destinations")
        if (da != null) {
            for (i in 0 until da.length()) dests.add(OfferParser.areaOf(da.optString(i)))
        }
        val lastDest: String? = if (dests.isNotEmpty()) dests[dests.size - 1] else null

        val rows = rows()
        val base = baseOf(rows)
        val fp = factor("real_to_est_pickup", b)
        val ft = factor("real_to_est_trip", b)
        val pickupReal = pMin * fp.v

        var tripReal: Double? = null
        var tripKm: Double? = null
        var conf = 3
        var guessed = false
        var src = ""

        if (tMinSnapp != null) {
            var done = false
            if (dests.size <= 1 && oa != null && lastDest != null) {
                val route = trips.filter {
                    okTrip(it) && s(it, "origin_area") == oa && s(it, "dest_area") == lastDest
                }
                if (route.isNotEmpty()) {
                    val sb = route.filter { band(it.optInt("hour")) == b }
                    val use = if (sb.isNotEmpty()) sb else route
                    val mins = use.mapNotNull { d(it, "real_trip_min") }
                    if (mins.isNotEmpty()) {
                        tripReal = median(mins)
                        conf = if (use.size >= 2) 3 else 2
                        src = "همین مسیر (" + fa(use.size.toLong()) + " بار)"
                        done = true
                    }
                }
            }
            if (!done) {
                tripReal = tMinSnapp * ft.v
                conf = ft.conf
                src = if (ft.conf >= 2) "تصحیح با ضریب واقعیت" else "زمان اسنپ بدون تصحیح"
            }
            tripKm = tKmSnapp
        } else {
            val areas = ArrayList<String?>()
            areas.add(oa)
            areas.addAll(dests)
            var sumMin = 0.0
            var sumKm: Double? = 0.0
            var minConf = 3
            var ok = dests.isNotEmpty()
            for (i in 0 until dests.size) {
                val leg = legEstimate(areas[i], areas[i + 1], b) ?: guessLeg(base, b)
                if (leg == null) {
                    ok = false
                    break
                }
                sumMin += leg.min
                val lk = leg.km
                val sk = sumKm
                sumKm = if (sk != null && lk != null) sk + lk else null
                if (leg.conf < minConf) minConf = leg.conf
            }
            if (ok) {
                tripReal = sumMin
                tripKm = sumKm
                conf = minConf
                guessed = minConf == 1
                src = if (guessed) "حدسی" else "از سفرهای قبلی"
            }
        }

        val tr = tripReal
        val nAll = rows.size
        val snappMin: Double? = if (tMinSnapp != null) pMin + tMinSnapp + w else null
        val sn = if (snappMin != null) fa(Math.round(snappMin)) else "؟"

        if (tr == null) {
            val t = "اطلاعات کافی ندارم\nمبلغ " + fa(Math.round(price)) + " | خالص " + fa(Math.round(net))
            return Result(t, 0, 0)
        }

        val kmTotal: Double? = if (pKm != null && tripKm != null) pKm + tripKm else null
        val totalReal = Math.max(pickupReal + tr + w, 0.5)
        val npm = net / totalReal
        val npk: Double? = if (kmTotal != null && kmTotal > 0.0) net / kmTotal else null

        val line2 = "خالص " + fa(Math.round(net)) + " | " + fa(Math.round(npm)) + " بر دقیقه | " +
            (if (npk != null) fa(Math.round(npk)) + " بر کیلومتر" else "کیلومتر نامشخص")
        val line4 = "زمان واقعی تخمینی " + fa(Math.round(totalReal)) + " دقیقه (اسنپ " + sn + ") | " + src

        if (base == null || nAll < LEARN_N) {
            val t = "📚 در حال یادگیری (" + fa(nAll.toLong()) + " از " + fa(LEARN_N.toLong()) + ")\n" +
                line2 + "\n" + line4
            return Result(t, 0, 0)
        }

        var sum = 0.0
        var ws = 0.0
        val rMin = npm / base.npm
        sum += 50.0 * sc(rMin)
        ws += 50.0
        var kmTxt = ""
        val bn = base.npk
        if (npk != null && bn != null) {
            val rk = npk / bn
            sum += 15.0 * sc(rk)
            ws += 15.0
            kmTxt = " | کیلومتر " + pct(rk)
        }

        // origin / destination areas: realized earnings per minute of my own trips
        val gl = trips.filter { it.optBoolean("valid", false) }.mapNotNull { d(it, "net_per_real_min") }
        if (gl.size >= 5) {
            val gm = median(gl)
            if (gm > 0.0) {
                if (lastDest != null) {
                    val x = trips.filter { it.optBoolean("valid", false) && s(it, "dest_area") == lastDest }
                        .mapNotNull { d(it, "net_per_real_min") }
                    if (x.size >= 2) {
                        sum += 12.0 * sc(median(x) / gm)
                        ws += 12.0
                    }
                }
                if (oa != null) {
                    val x = trips.filter { it.optBoolean("valid", false) && s(it, "origin_area") == oa }
                        .mapNotNull { d(it, "net_per_real_min") }
                    if (x.size >= 2) {
                        sum += 8.0 * sc(median(x) / gm)
                        ws += 8.0
                    }
                }
            }
        }
        val sg = d(o, "surge_toman")
        if (sg != null && price > 0.0) {
            sum += 10.0 * cl(50.0 + (sg / price) * 100.0, 0.0, 100.0)
            ws += 10.0
        }
        if (base.price > 0.0 && price > 0.0) {
            sum += 5.0 * sc(price / base.price)
            ws += 5.0
        }

        val score = Math.round(sum / ws).toInt()
        val level = when {
            score >= 60 -> 1
            score <= 40 -> 3
            else -> 2
        }
        val head = when (level) {
            1 -> "✅ عالی — قبول کن"
            2 -> "🟡 متوسط — تصمیم با خودت"
            else -> "🔴 ضعیف — نکن"
        } + "  (" + fa(score.toLong()) + ")"

        var c = conf
        if (nAll < 100 && c > 2) c = 2
        val confTxt = when (c) {
            3 -> "خوب"
            2 -> "متوسط"
            else -> "کم"
        }
        val line3 = "نسبت به میانگین: دقیقه " + pct(rMin) + kmTxt
        val t = head + "\n" + line2 + "\n" + line3 + "\n" + line4 + " | اعتماد: " + confTxt
        return Result(t, level, score)
    }

    // ---------- trip record ----------

    fun buildTrip(off: JSONObject, accept: Long, arrive: Long, end: Long): JSONObject {
        val pr = (arrive - accept) / 60000.0
        val tr = (end - arrive) / 60000.0
        val t = JSONObject()
        t.put("schema", 1)
        t.put("id", UUID.randomUUID().toString())
        t.put("offer_id", off.optString("id"))
        t.put("accept_ts", accept)
        t.put("arrive_ts", arrive)
        t.put("end_ts", end)
        val dt = Date(accept)
        t.put("local_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(dt))
        t.put("dow", SimpleDateFormat("EEE", Locale.US).format(dt).uppercase(Locale.US))
        t.put("hour", Calendar.getInstance().apply { timeInMillis = accept }.get(Calendar.HOUR_OF_DAY))
        for (k in listOf(
            "kind", "stops", "price_toman", "net_toman", "wait_min_low", "wait_min_high",
            "origin", "origin_area", "destination", "dest_area", "destinations"
        )) {
            t.put(k, if (off.has(k)) off.get(k) else JSONObject.NULL)
        }
        val estP = d(off, "pickup_min")
        val estT = d(off, "trip_min")
        t.put("est_pickup_min", estP ?: JSONObject.NULL)
        t.put("est_pickup_km", d(off, "pickup_km") ?: JSONObject.NULL)
        t.put("est_trip_min", estT ?: JSONObject.NULL)
        t.put("est_trip_km", d(off, "trip_km") ?: JSONObject.NULL)
        t.put("real_pickup_min", r2(pr))
        t.put("real_trip_min", r2(tr))
        t.put("real_total_min", r2(pr + tr))
        t.put("real_to_est_pickup", if (estP != null && estP > 0.0) r2(pr / estP) else JSONObject.NULL)
        t.put("real_to_est_trip", if (estT != null && estT > 0.0) r2(tr / estT) else JSONObject.NULL)
        val net = d(off, "net_toman")
        t.put("net_per_real_min", if (net != null && pr + tr > 0.0) Math.round(net / (pr + tr)) else JSONObject.NULL)
        t.put("valid", pr >= 0.2 && pr <= 120.0 && tr >= 0.5 && tr <= 400.0)
        return t
    }

    // ---------- texts for the app screen ----------

    @Synchronized
    fun summary(): String {
        val rows = rows()
        val base = baseOf(rows)
        val validTrips = trips.count { it.optBoolean("valid", false) }
        val sb = StringBuilder()
        if (rows.size < LEARN_N) {
            sb.append("وضعیت: در حال یادگیری (").append(fa(rows.size.toLong())).append(" از ")
                .append(fa(LEARN_N.toLong())).append(")\n")
        } else {
            sb.append("وضعیت: فعال — حکم عالی/متوسط/ضعیف\n")
        }
        sb.append("کارت‌های قابل استفاده: ").append(fa(rows.size.toLong())).append('\n')
        sb.append("سفرهای واقعی معتبر: ").append(fa(validTrips.toLong()))
            .append(" از ").append(fa(trips.size.toLong())).append('\n')
        if (base != null) {
            sb.append("میانه خالص بر دقیقه: ").append(fa(Math.round(base.npm)))
            val bn = base.npk
            if (bn != null) sb.append(" | بر کیلومتر: ").append(fa(Math.round(bn)))
            sb.append(" | مبلغ: ").append(fa(Math.round(base.price))).append('\n')
        }
        val fp = factor("real_to_est_pickup", -1)
        val ft = factor("real_to_est_trip", -1)
        sb.append("ضریب واقعیت (واقعی ÷ اسنپ): تا مبدأ ×")
            .append(String.format(Locale.US, "%.2f", fp.v))
            .append(" | سفر ×").append(String.format(Locale.US, "%.2f", ft.v))
        if (ft.conf < 2) sb.append("  (هنوز کافی نیست، ۱ یعنی همان تخمین اسنپ)")
        sb.append('\n')
        sb.append("میانه خالص بر دقیقه در هر بازه ساعتی:\n")
        for (b in 0..5) {
            val r = rows.filter { it.band == b }
            if (r.isNotEmpty()) {
                sb.append("  ساعت ").append(BANDS[b]).append(": ")
                    .append(fa(Math.round(median(r.map { it.npm })))).append(" (")
                    .append(fa(r.size.toLong())).append(" کارت)\n")
            }
        }
        return sb.toString()
    }

    private fun f(o: JSONObject, k: String, fmt: String): String {
        val v = d(o, k) ?: return "-"
        return String.format(Locale.US, fmt, v)
    }

    @Synchronized
    fun listText(): String {
        val sb = StringBuilder()
        sb.append("--- last cards ---\n")
        for (o in obs.takeLast(8).reversed()) {
            val lt = o.optString("local_time")
            val hm = if (lt.length >= 16) lt.substring(11, 16) else lt
            sb.append(
                String.format(
                    Locale.US,
                    "%s %s | %,d (net %,d) | %s+%s min | %s+%s km | %s/min | %s/km%s\n",
                    hm, o.optString("dow"), o.optLong("price_toman"), o.optLong("net_toman"),
                    f(o, "pickup_min", "%.0f"), f(o, "trip_min", "%.0f"),
                    f(o, "pickup_km", "%.1f"), f(o, "trip_km", "%.1f"),
                    f(o, "net_per_min", "%,.0f"), f(o, "net_per_km", "%,.0f"),
                    if (o.optString("kind") == "multi_stop") "  [multi x" + o.optInt("stops") + "]" else ""
                )
            )
        }
        sb.append("\n--- last real trips ---\n")
        for (t in trips.takeLast(5).reversed()) {
            val lt = t.optString("local_time")
            val hm = if (lt.length >= 16) lt.substring(11, 16) else lt
            sb.append(
                String.format(
                    Locale.US,
                    "%s %s | %,d | est %s+%s -> real %s+%s min | %s/min%s\n",
                    hm, t.optString("dow"), t.optLong("price_toman"),
                    f(t, "est_pickup_min", "%.0f"), f(t, "est_trip_min", "%.0f"),
                    f(t, "real_pickup_min", "%.1f"), f(t, "real_trip_min", "%.1f"),
                    f(t, "net_per_real_min", "%,.0f"),
                    if (t.optBoolean("valid", false)) "" else "  [invalid]"
                )
            )
        }
        return sb.toString()
    }
}
