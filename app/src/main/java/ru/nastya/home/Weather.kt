package ru.nastya.home

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class Weather(
    val temp: Int,
    val emoji: String,
    val description: String,
    val min: Int?,
    val max: Int?,
    val windMs: Int?,
    val fetchedAt: Long,
) {
    val tempText: String get() = formatTemp(temp)

    val detailsText: String
        get() {
            val parts = ArrayList<String>()
            parts.add(description)
            if (min != null && max != null) parts.add("${formatTemp(min)}…${formatTemp(max)}")
            if (windMs != null) parts.add("ветер $windMs м/с")
            return parts.joinToString(" · ")
        }

    fun toJson(): String = JSONObject()
        .put("temp", temp).put("emoji", emoji).put("description", description)
        .put("min", min ?: JSONObject.NULL).put("max", max ?: JSONObject.NULL)
        .put("wind", windMs ?: JSONObject.NULL).put("fetchedAt", fetchedAt).toString()

    companion object {
        fun formatTemp(t: Int): String = when {
            t > 0 -> "+$t°"
            t < 0 -> "−${-t}°"
            else -> "0°"
        }

        fun fromJson(s: String): Weather? = try {
            val o = JSONObject(s)
            Weather(
                o.getInt("temp"), o.getString("emoji"), o.getString("description"),
                if (o.isNull("min")) null else o.getInt("min"),
                if (o.isNull("max")) null else o.getInt("max"),
                if (o.isNull("wind")) null else o.getInt("wind"),
                o.getLong("fetchedAt"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Погода в Калининграде. Open-Meteo с проектора недоступен (TLS reset), поэтому основной источник —
 * api.met.no (норвежский Yr, без ключа, нужен User-Agent), запасной — 7timer.info.
 */
object WeatherRepository {
    private const val TAG = "NastyaHome"
    private const val LAT = "54.71"
    private const val LON = "20.51"
    private const val PREFS = "weather"
    private val tz: TimeZone = TimeZone.getTimeZone("Europe/Kaliningrad")

    fun cached(ctx: Context): Weather? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("json", null)?.let { Weather.fromJson(it) }

    /** Блокирующая загрузка; результат кэшируется. */
    fun fetch(ctx: Context): Weather? {
        val w = try {
            fetchMetNo()
        } catch (e: Exception) {
            Log.w(TAG, "met.no failed: $e")
            try {
                fetch7Timer()
            } catch (e2: Exception) {
                Log.w(TAG, "7timer failed: $e2")
                null
            }
        }
        if (w != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", w.toJson()).apply()
        }
        return w
    }

    private fun fetchMetNo(): Weather {
        val text = Http.get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$LAT&lon=$LON")
        val series = JSONObject(text).getJSONObject("properties").getJSONArray("timeseries")
        require(series.length() > 0) { "met.no: пустой прогноз" }

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val localDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val today = localDay.format(Calendar.getInstance(tz).time)

        val first = series.getJSONObject(0)
        val instant = first.getJSONObject("data").getJSONObject("instant").getJSONObject("details")
        val temp = instant.getDouble("air_temperature").roundToInt()
        val wind = instant.optDouble("wind_speed", Double.NaN).takeIf { !it.isNaN() }?.roundToInt()
        val symbol = first.getJSONObject("data").optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
            ?: first.getJSONObject("data").optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code")
            ?: "cloudy"

        var min: Int? = null
        var max: Int? = null
        for (i in 0 until series.length()) {
            val o = series.getJSONObject(i)
            val t = utc.parse(o.getString("time")) ?: continue
            if (localDay.format(t) != today) continue
            val v = o.getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").roundToInt()
            min = if (min == null) v else minOf(min, v)
            max = if (max == null) v else maxOf(max, v)
        }
        val (emoji, description) = describeMetNo(symbol)
        return Weather(temp, emoji, description, min, max, wind, System.currentTimeMillis())
    }

    private fun describeMetNo(code: String): Pair<String, String> {
        val night = code.endsWith("_night")
        val base = code.substringBefore('_')
        return when (base) {
            "clearsky" -> (if (night) "🌙" else "☀️") to "Ясно"
            "fair" -> (if (night) "🌙" else "🌤️") to "Малооблачно"
            "partlycloudy" -> (if (night) "☁️" else "⛅") to "Переменная облачность"
            "cloudy" -> "☁️" to "Облачно"
            "fog" -> "🌫️" to "Туман"
            "lightrain", "lightrainshowers" -> "🌦️" to "Небольшой дождь"
            "rain", "rainshowers" -> "🌧️" to "Дождь"
            "heavyrain", "heavyrainshowers" -> "🌧️" to "Сильный дождь"
            "lightsleet", "sleet", "heavysleet", "lightsleetshowers", "sleetshowers", "heavysleetshowers" -> "🌨️" to "Мокрый снег"
            "lightsnow", "snow", "heavysnow", "lightsnowshowers", "snowshowers", "heavysnowshowers" -> "❄️" to "Снег"
            else -> if (base.contains("thunder")) "⛈️" to "Гроза" else "🌥️" to "Пасмурно"
        }
    }

    private fun fetch7Timer(): Weather {
        val text = Http.get("https://www.7timer.info/bin/api.pl?lon=$LON&lat=$LAT&product=civil&output=json")
        val o = JSONObject(text)
        val init = o.getString("init") // yyyyMMddHH, UTC
        val initCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(init.substring(0, 4).toInt(), init.substring(4, 6).toInt() - 1, init.substring(6, 8).toInt(), init.substring(8, 10).toInt(), 0, 0)
        }
        val localDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val today = localDay.format(Calendar.getInstance(tz).time)
        val series = o.getJSONArray("dataseries")
        require(series.length() > 0) { "7timer: пустой прогноз" }
        val first = series.getJSONObject(0)
        var min: Int? = null
        var max: Int? = null
        for (i in 0 until series.length()) {
            val p = series.getJSONObject(i)
            val c = initCal.clone() as Calendar
            c.add(Calendar.HOUR_OF_DAY, p.getInt("timepoint"))
            if (localDay.format(c.time) != today) continue
            val v = p.getInt("temp2m")
            min = if (min == null) v else minOf(min, v)
            max = if (max == null) v else maxOf(max, v)
        }
        val (emoji, description) = describe7Timer(first.getString("weather"))
        return Weather(first.getInt("temp2m"), emoji, description, min, max, null, System.currentTimeMillis())
    }

    private fun describe7Timer(code: String): Pair<String, String> {
        val night = code.endsWith("night")
        val base = code.removeSuffix("day").removeSuffix("night")
        return when (base) {
            "clear" -> (if (night) "🌙" else "☀️") to "Ясно"
            "pcloudy" -> (if (night) "☁️" else "⛅") to "Переменная облачность"
            "mcloudy" -> "🌥️" to "Облачно с прояснениями"
            "cloudy" -> "☁️" to "Облачно"
            "humid" -> "🌫️" to "Влажно, туман"
            "lightrain", "oshower", "ishower" -> "🌦️" to "Небольшой дождь"
            "rain" -> "🌧️" to "Дождь"
            "lightsnow", "snow" -> "❄️" to "Снег"
            "rainsnow" -> "🌨️" to "Мокрый снег"
            "ts", "tsrain" -> "⛈️" to "Гроза"
            else -> "🌥️" to "Пасмурно"
        }
    }
}
