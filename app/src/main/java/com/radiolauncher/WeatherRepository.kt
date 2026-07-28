package com.radiolauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One upcoming day in the forecast. dateIso is "yyyy-MM-dd" (formatted by the UI). */
data class DailyForecast(val dateIso: String, val iconRes: Int, val high: String, val low: String)

/** Current weather plus a short multi-day forecast. */
data class Weather(
    val iconRes: Int,
    val description: String,
    val temp: String,
    val forecast: List<DailyForecast>
)

/**
 * Fetches current weather from Open-Meteo (https://open-meteo.com) — a free API
 * that needs **no key**. Location comes from the platform LocationManager (no
 * Google Play Services), keeping this usable on the cheapest / oldest head units.
 */
object WeatherRepository {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Loads weather on a background thread; delivers result (or null) on the UI thread. */
    fun load(context: Context, useFahrenheit: Boolean, callback: (Weather?) -> Unit) {
        val loc = lastKnownLocation(context)
        if (loc == null) {
            callback(null)
            return
        }
        val unit = if (useFahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${loc.latitude}&longitude=${loc.longitude}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=4&temperature_unit=$unit&timezone=auto"

        Thread {
            val weather = try {
                val json = (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000
                    readTimeout = 5000
                    inputStream.bufferedReader().use { it.readText() }
                }
                parse(context, json)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(weather) }
        }.start()
    }

    private fun parse(context: Context, json: String): Weather? {
        val root = JSONObject(json)
        val current = root.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val code = current.optInt("weather_code", -1)
        val (iconRes, descRes) = describe(code)
        return Weather(
            iconRes = iconRes,
            description = context.getString(descRes),
            temp = "${Math.round(temp)}°",
            forecast = parseDaily(root.optJSONObject("daily"))
        )
    }

    /** Upcoming days only (skip index 0 = today). Up to 3 days. */
    private fun parseDaily(daily: JSONObject?): List<DailyForecast> {
        daily ?: return emptyList()
        val times = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val highs = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val lows = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val result = mutableListOf<DailyForecast>()
        var i = 1
        while (i < times.length() && result.size < 3) {
            val (icon, _) = describe(codes.optInt(i, -1))
            result.add(
                DailyForecast(
                    dateIso = times.optString(i),
                    iconRes = icon,
                    high = "${Math.round(highs.optDouble(i))}°",
                    low = "${Math.round(lows.optDouble(i))}°"
                )
            )
            i++
        }
        return result
    }

    /** Map a WMO weather code to an icon + description. */
    private fun describe(code: Int): Pair<Int, Int> = when (code) {
        0 -> R.drawable.ic_wx_clear to R.string.wx_clear
        1 -> R.drawable.ic_wx_clear to R.string.wx_mainly_clear
        2 -> R.drawable.ic_wx_partly to R.string.wx_partly_cloudy
        3 -> R.drawable.ic_wx_cloudy to R.string.wx_overcast
        45, 48 -> R.drawable.ic_wx_fog to R.string.wx_fog
        51, 53, 55, 56, 57 -> R.drawable.ic_wx_rain to R.string.wx_drizzle
        61, 63, 65, 66, 67 -> R.drawable.ic_wx_rain to R.string.wx_rain
        80, 81, 82 -> R.drawable.ic_wx_rain to R.string.wx_showers
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_wx_snow to R.string.wx_snow
        95, 96, 99 -> R.drawable.ic_wx_thunder to R.string.wx_thunderstorm
        else -> R.drawable.ic_wx_cloudy to R.string.wx_overcast
    }

    private fun lastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return try {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).asSequence()
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }
}
