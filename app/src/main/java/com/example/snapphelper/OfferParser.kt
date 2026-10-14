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
    private val KM = Regex(NUM + "\\s*کیلومتر")
    private val METER = Regex(NUM + "\\s*متر")
    private val MIN = Regex(NUM + "\\s*دقیقه")
    private val HOUR = Regex(NUM + "\\s*ساعت")
    private val ANY_NUM = Regex("[0-9]+(?:[.٫/][0-9]+)?")
    private val AMOUNT = Regex("[0-9][0-9’'‘٬,،]*")
    private val PICKUP = Regex("تا\\s*مبد[أا]")

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
            val tok = k.groupValues[1]
            kmToken = tok
            km = num(tok)
        } else {
            val m = METER.find(line)
            if (m != null) {
                val tok = m.groupValues[1]
                kmToken = tok
                km = num(tok)?.div(1000.0)
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

    fun keyOf(o: JSONObject): String =
        listOf("price_toman", "pickup_min", "pickup_km", "trip_min", "trip_km", "origin", "destination")
            .joinToString("|") { o.opt(it).toString() }

    fun parse(texts: List<String>, now: Long): JSONObject? {
        val lines = texts.map { normalizeDigits(it.trim()) }
        if (lines.none { it.contains("قبول سفر") }) return null

        val pickupIdx = lines.indexOfFirst { PICKUP.containsMatchIn(it) && it.contains("دقیقه") }
        if (pickupIdx < 0) return null

        // all prices on screen: a number line followed by a "تومان" line
        val prices = ArrayList<Pair<Int, Long>>()
        for (i in 0 until lines.size - 1) {
            if (lines[i + 1] == "تومان" && PRICE_LINE.matches(lines[i])) {
                val digits = lines[i].filter { it.isDigit() }
                if (digits.length in 4..9) prices.add(Pair(i, digits.toLong()))
            }
        }
        // the selected card's price is the last price before the pickup line
        val selected = prices.lastOrNull { it.first < pickupIdx } ?: return null
        val others = prices.filter { it.first != selected.first }.map { it.second }

        val tripIdx = lines.indices.firstOrNull {
            it > pickupIdx && lines[it].contains("دقیقه") &&
                (KM.containsMatchIn(lines[it]) || METER.containsMatchIn(lines[it]))
        } ?: return null

        val pickup = segment(lines[pickupIdx])
        val trip = segment(lines[tripIdx])
        val pMin = pickup.first ?: return null
        val tMin = trip.first ?: return null
        val tKm = trip.second ?: return null
        val pKm = pickup.second

        val origin = lines.getOrNull(pickupIdx + 1)
            ?.takeIf { !it.contains("دقیقه") && !it.contains("قبول") }
        val destination = lines.getOrNull(tripIdx + 1)
            ?.takeIf { !it.contains("قبول") }

        var surge: Long? = null
        var phoneDed: Long? = null
        for (l in lines) {
            if (l.contains("هزینه معمول")) {
                val a = amountIn(l)
                if (a != null) surge = if (l.contains("کمتر")) -a else a
            }
            if (l.contains("خدمات تلفنی") && l.contains("کسر")) phoneDed = amountIn(l)
        }

        val price = selected.second
        val net = Math.round(price * NET_RATE)
        val totalMin = pMin + tMin
        val totalKm: Double? = if (pKm != null) r2(pKm + tKm) else null

        val d = Date(now)
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d)
        val dow = SimpleDateFormat("EEE", Locale.US).format(d).uppercase(Locale.US)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

        val o = JSONObject()
        o.put("schema", 1)
        o.put("id", UUID.randomUUID().toString())
        o.put("ts", now)
        o.put("local_time", local)
        o.put("dow", dow)
        o.put("hour", hour)
        o.put("kind", if (lines.any { it.contains("قبول سفر تلفنی") }) "phone" else "normal")
        o.put("price_toman", price)
        o.put("net_toman", net)
        o.put("other_prices_toman", JSONArray(others))
        o.put("pickup_min", pMin)
        o.put("pickup_km", pKm ?: JSONObject.NULL)
        o.put("trip_min", tMin)
        o.put("trip_km", tKm)
        o.put("total_min", totalMin)
        o.put("total_km", totalKm ?: JSONObject.NULL)
        o.put("net_per_min", if (totalMin > 0) Math.round(net / totalMin) else JSONObject.NULL)
        o.put("net_per_km", if (totalKm != null && totalKm > 0) Math.round(net / totalKm) else JSONObject.NULL)
        o.put("surge_toman", surge ?: JSONObject.NULL)
        o.put("phone_deduction_toman", phoneDed ?: JSONObject.NULL)
        o.put("origin", origin ?: JSONObject.NULL)
        o.put("destination", destination ?: JSONObject.NULL)
        o.put("raw", JSONArray(texts))
        return o
    }
}
