package com.example.snapphelper

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

object OfferParser {
    // 15% commission + 1.5% VAT, both taken from the full fare
    const val NET_RATE = 0.835

    private val PRICE_LINE = Regex("^[0-9][0-9’'‘٬,،. ]*$")
    private const val NUM = "([0-9]+(?:[.٫/][0-9]+)?)"
    private val KM = Regex(NUM + "\\s*(?:کیلومتر|ک\\.?\\s*م)")
    private val METER = Regex(NUM + "\\s*(?:متر|م)(?![\\u0600-\\u06FF])")
    private val MIN = Regex(NUM + "\\s*دقیقه")
    private val HOUR = Regex(NUM + "\\s*ساعت")
    private val ANY_NUM = Regex("[0-9]+(?:[.٫/][0-9]+)?")
    private val AMOUNT = Regex("[0-9][0-9’'‘٬,،]*")
    private val PICKUP = Regex("تا\\s*مبد[أا]")
    private val WAIT = Regex("([0-9]+)\\s*تا\\s*([0-9]+)\\s*دقیقه\\s*توقف")

    fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun num(s: String): Double? =
        s.replace('٫', '.').replace('/', '.').toDoubleOrNull()

    private fun r2(x: Double): Double = Math.round(x * 100) / 100.0

    private fun amountIn(l: String): Long? {
        val m = AMOUNT.find(l) ?: return null
        val d = m.value.filter { it.isDigit() }
        return if (d.length in 1..9) d.toLong() else null
    }

    // returns (minutes, km)
    private fun segment(line: String): Pair<Double?, Double?> {
        var km: Double? = null
        var kmToken: String? = null
        val k = KM.find(line)
        if (k != null) {
            kmToken = k.groupValues[1]
            km = num(kmToken)
        } else {
            val m = METER.find(line)
            if (m != null) {
                kmToken = m.groupValues[1]
                km = num(kmToken)?.div(1000.0)
            }
        }
        var minutes: Double? = null
        val h = HOUR.find(line)
        val mi = MIN.find(line)
        if (h != null || mi != null) {
            minutes = (h?.let { num(it.groupValues[1]) } ?: 0.0) * 60 +
                (mi?.let { num(it.groupValues[1]) } ?: 0.0)
        } else if (line.contains("دقیقه")) {
            val rest = ANY_NUM.findAll(line).map { it.value }.toMutableList()
            kmToken?.let { rest.remove(it) }
            if (rest.size == 1) minutes = num(rest[0])
        }
        return Pair(minutes, km)
    }

    private fun isAddress(l: String): Boolean =
        l.startsWith("مشهد") || (l.contains("،") && !l.contains("دقیقه"))

    // neighbourhood name taken from the address text
    fun areaOf(addr: String?): String? {
        if (addr == null) return null
        val s = addr.replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ').trim()
        val parts = s.split('،', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val first = parts[0]
        return if (first == "مشهد" && parts.size > 1) parts[1] else first
    }

    // identity of a card: everything except km (km appears later after "details")
    fun idKey(o: JSONObject): String =
        listOf("price_toman", "pickup_min", "trip_min", "origin", "destinations")
            .joinToString("|") { o.opt(it).toString() }

    // full content, used to detect screen changes (includes km)
    fun fullKey(o: JSONObject): String =
        listOf("price_toman", "pickup_min", "pickup_km", "trip_min", "trip_km", "origin", "destinations")
            .joinToString("|") { o.opt(it).toString() }

    fun parse(texts: List<String>, now: Long): JSONObject? {
        val lines = texts.map { normalizeDigits(it.trim()) }
        if (lines.none { it.contains("قبول سفر") }) return null
        // shared trips are ignored completely
        if (lines.any { it.contains("اشتراکی") }) return null

        val pickupIdx = lines.indexOfFirst { PICKUP.containsMatchIn(it) && it.contains("دقیقه") }
        if (pickupIdx < 0) return null

        // only the opened card: the last price before its pickup line
        var selected: Long? = null
        for (i in 0 until pickupIdx) {
            if (i + 1 < lines.size && lines[i + 1] == "تومان" && PRICE_LINE.matches(lines[i])) {
                val digits = lines[i].filter { it.isDigit() }
                if (digits.length in 4..9) selected = digits.toLong()
            }
        }
        val price = selected ?: return null

        val pickup = segment(lines[pickupIdx])
        val pMin = pickup.first ?: return null
        val pKm = pickup.second

        val acceptIdx = lines.indexOfFirst { it.contains("قبول سفر") }
        val endIdx = if (acceptIdx > pickupIdx) acceptIdx else lines.size

        val legIdx = ArrayList<Int>()
        for (i in pickupIdx + 1 until endIdx) {
            val l = lines[i]
            if (l.contains("دقیقه") && !WAIT.containsMatchIn(l) && !PICKUP.containsMatchIn(l)) legIdx.add(i)
        }

        val addresses = ArrayList<String>()
        for (i in pickupIdx + 1 until endIdx) {
            val l = lines[i]
            if (isAddress(l) && !l.contains("دقیقه")) addresses.add(l)
        }
        val origin = addresses.getOrNull(0)
        val destinations = if (addresses.size > 1) addresses.drop(1) else emptyList()

        var waitLow: Int? = null
        var waitHigh: Int? = null
        val tags = ArrayList<String>()
        var pro = false
        for (i in pickupIdx + 1 until endIdx) {
            val l = lines[i]
            val w = WAIT.find(l)
            if (w != null) {
                waitLow = w.groupValues[1].toIntOrNull()
                waitHigh = w.groupValues[2].toIntOrNull()
            } else if (l.contains("پرو")) {
                pro = true
            } else if (!l.contains("دقیقه") && !isAddress(l) && !l.contains("جزئیات") &&
                !PRICE_LINE.matches(l) && l != "تومان" && l.length < 40
            ) {
                tags.add(l)
            }
        }
        val suggested = lines.any { it.contains("سفر پیشنهادی") }

        var surge: Long? = null
        var phoneDed: Long? = null
        for (l in lines) {
            if (l.contains("هزینه معمول")) {
                val a = amountIn(l)
                if (a != null) surge = if (l.contains("کمتر")) -a else a
            }
            if (l.contains("خدمات تلفنی") && l.contains("کسر")) phoneDed = amountIn(l)
        }

        // trip legs (sum of all legs that show minutes)
        var tMinVar: Double? = null
        var tKmVar: Double? = null
        if (legIdx.isNotEmpty()) {
            var sumMin = 0.0
            var sumKm = 0.0
            var kmKnown = true
            for (i in legIdx) {
                val sg = segment(lines[i])
                sumMin += sg.first ?: 0.0
                val k = sg.second
                if (k != null) sumKm += k else kmKnown = false
            }
            if (sumMin > 0) {
                tMinVar = sumMin
                tKmVar = if (kmKnown) r2(sumKm) else null
            }
        }
        val tMin: Double? = tMinVar
        val tKm: Double? = tKmVar

        val noTime = tMin == null
        if (noTime && destinations.isEmpty()) return null
        val multi = destinations.size >= 2

        val net = Math.round(price * NET_RATE)
        val waitAvg = if (waitLow != null && waitHigh != null) (waitLow + waitHigh) / 2.0 else 0.0

        val totalMin: Double? = if (tMin != null) pMin + tMin else null
        val totalMinWithWait: Double? = if (totalMin != null) totalMin + waitAvg else null
        val totalKm: Double? =
            if (tMin != null && pKm != null && tKm != null) r2(pKm + tKm) else null

        val phone = lines.any { it.contains("قبول سفر تلفنی") }
        val kind = when {
            multi -> "multi_stop"
            noTime -> "no_time"
            phone -> "phone"
            else -> "normal"
        }
        val longTrip = totalMin != null && totalMin > 60.0

        val d = Date(now)
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d)
        val dow = SimpleDateFormat("EEE", Locale.US).format(d).uppercase(Locale.US)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

        val o = JSONObject()
        o.put("schema", 4)
        o.put("id", UUID.randomUUID().toString())
        o.put("ts", now)
        o.put("local_time", local)
        o.put("dow", dow)
        o.put("hour", hour)
        o.put("kind", kind)
        o.put("stops", maxOf(destinations.size, 1))
        o.put("long_trip", longTrip)
        o.put("pro", pro)
        o.put("suggested", suggested)
        o.put("tags", JSONArray(tags))
        o.put("wait_min_low", waitLow ?: JSONObject.NULL)
        o.put("wait_min_high", waitHigh ?: JSONObject.NULL)
        o.put("price_toman", price)
        o.put("net_toman", net)
        o.put("pickup_min", pMin)
        o.put("pickup_km", pKm ?: JSONObject.NULL)
        o.put("trip_min", tMin ?: JSONObject.NULL)
        o.put("trip_km", tKm ?: JSONObject.NULL)
        o.put("total_min", totalMin ?: JSONObject.NULL)
        o.put("total_min_with_wait", totalMinWithWait ?: JSONObject.NULL)
        o.put("total_km", totalKm ?: JSONObject.NULL)
        o.put("has_km", totalKm != null)
        o.put("net_per_min", if (totalMin != null && totalMin > 0) Math.round(net / totalMin) else JSONObject.NULL)
        o.put(
            "net_per_min_with_wait",
            if (totalMinWithWait != null && totalMinWithWait > 0) Math.round(net / totalMinWithWait) else JSONObject.NULL
        )
        o.put("net_per_km", if (totalKm != null && totalKm > 0) Math.round(net / totalKm) else JSONObject.NULL)
        o.put("surge_toman", surge ?: JSONObject.NULL)
        o.put("phone_deduction_toman", phoneDed ?: JSONObject.NULL)
        o.put("origin", origin ?: JSONObject.NULL)
        o.put("origin_area", areaOf(origin) ?: JSONObject.NULL)
        o.put("destination", if (destinations.isNotEmpty()) destinations[0] else JSONObject.NULL)
        o.put("dest_area", if (destinations.isNotEmpty()) (areaOf(destinations[destinations.size - 1]) ?: JSONObject.NULL) else JSONObject.NULL)
        o.put("destinations", JSONArray(destinations))
        o.put("dest_areas", JSONArray(destinations.map { areaOf(it) ?: "" }))
        o.put("raw", JSONArray(texts))
        return o
    }
}
