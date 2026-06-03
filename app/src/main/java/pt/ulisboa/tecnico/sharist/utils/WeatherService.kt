package pt.ulisboa.tecnico.sharist.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pt.ulisboa.tecnico.sharist.data.model.WeatherCondition
import pt.ulisboa.tecnico.sharist.data.model.WeatherType
import java.net.URL

private const val TAG = "WeatherService"

// IPMA district codes (common Portuguese cities)
object IpmaDistrict {
    const val LISBOA   = 1110600
    const val PORTO    = 1131200
    const val FARO     = 1080500
    const val COIMBRA  = 1060300
    const val BRAGA    = 1010500
}

data class WeatherForecast(
    val tempMin: Double,
    val tempMax: Double,
    val precipProb: Int,     // 0-100 %
    val windSpeed: Int,      // km/h class 1-9
    val description: String,
    val willRain: Boolean,
    val date: String
)

class WeatherService {

    private val cache = mutableMapOf<Int, Pair<Long, List<WeatherForecast>>>()
    private val CACHE_DURATION = 30 * 60 * 1000 // 30 minutes

    /**
     * Fetches forecast for the given IPMA location ID.
     */
    suspend fun getForecasts(locationId: Int): Result<List<WeatherForecast>> =
        withContext(Dispatchers.IO) {
            val cached = cache[locationId]
            if (cached != null && System.currentTimeMillis() - cached.first < CACHE_DURATION) {
                return@withContext Result.success(cached.second)
            }

            runCatching {
                val url = "https://api.ipma.pt/open-data/forecast/meteorology/cities/daily/$locationId.json"
                val json = URL(url).readText()
                val forecasts = parseIpmaResponse(json)
                cache[locationId] = System.currentTimeMillis() to forecasts
                forecasts
            }
        }

    private fun parseIpmaResponse(json: String): List<WeatherForecast> {
        val root = JSONObject(json)
        val dataArray = root.getJSONArray("data")
        val forecasts = mutableListOf<WeatherForecast>()
        
        for (i in 0 until dataArray.length()) {
            val data = dataArray.getJSONObject(i)
            val tMin      = data.optDouble("tMin", 0.0)
            val tMax      = data.optDouble("tMax", 0.0)
            val precipProb = data.optInt("precipitaProb", 0)
            val windClass  = data.optInt("classWindSpeed", 1)
            val weatherId  = data.optInt("idWeatherType", 1)
            val date       = data.optString("forecastDate", "")

            val description = weatherDescription(weatherId)
            val willRain    = precipProb >= 40 || weatherId in listOf(6, 7, 8, 9, 10, 11, 12, 13, 14)

            forecasts.add(WeatherForecast(tMin, tMax, precipProb, windClass, description, willRain, date))
        }
        return forecasts
    }

    suspend fun getForecast(locationId: Int): Result<WeatherForecast> =
        getForecasts(locationId).map { it.first() }

    /**
     * Checks whether the ride should be warned/cancelled based on its
     * WeatherCondition and the requested date.
     */
    suspend fun checkWeatherViolation(
        city: String,
        date: java.util.Date?,
        condition: WeatherCondition
    ): WeatherWarning {
        if (condition.type == WeatherType.NONE) return WeatherWarning.NONE
        
        val forecasts = getForecasts(getLocationId(city)).getOrNull() ?: return WeatherWarning.NONE
        
        val targetDateStr = date?.let { 
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(it)
        } ?: ""

        val forecast = forecasts.find { it.date == targetDateStr } ?: forecasts.firstOrNull() 
            ?: return WeatherWarning.NONE

        return evaluateCondition(condition, forecast)
    }

    private fun weatherDescription(id: Int) = when (id) {
        1  -> "Clear sky"
        2  -> "Partly cloudy"
        3  -> "Cloudy"
        4  -> "Overcast"
        5  -> "Fog"
        6  -> "Light rain"
        7  -> "Rain"
        8  -> "Heavy rain"
        9  -> "Rain and thunder"
        10 -> "Showers"
        11 -> "Thunderstorm"
        12 -> "Snow showers"
        13 -> "Light snow"
        14 -> "Snow"
        else -> "Unknown"
    }

    /**
     * Attempts to find an IPMA location ID from a string.
     */
    fun getLocationId(city: String): Int {
        val normalized = city.trim().lowercase()
        return when {
            normalized.contains("lisboa") || normalized.contains("lisbon") -> IpmaDistrict.LISBOA
            normalized.contains("porto") -> IpmaDistrict.PORTO
            normalized.contains("faro") -> IpmaDistrict.FARO
            normalized.contains("coimbra") -> IpmaDistrict.COIMBRA
            normalized.contains("braga") -> IpmaDistrict.BRAGA
            else -> IpmaDistrict.LISBOA // Default to Lisboa
        }
    }

    /**
     * Checks whether the ride should be warned/cancelled based on its
     * WeatherCondition and the current forecast.
     */
    fun evaluateCondition(
        condition: WeatherCondition?,
        forecast: WeatherForecast
    ): WeatherWarning {
        if (condition == null || condition.type == WeatherType.NONE)
            return WeatherWarning.NONE

        return when (condition.type) {
            WeatherType.RAIN ->
                if (forecast.willRain) WeatherWarning.WILL_CANCEL
                else WeatherWarning.NONE

            WeatherType.TOO_HOT -> {
                val threshold = condition.threshold ?: 35.0
                if (forecast.tempMax >= threshold) WeatherWarning.WILL_CANCEL
                else WeatherWarning.NONE
            }

            WeatherType.TOO_COLD -> {
                val threshold = condition.threshold ?: 5.0
                if (forecast.tempMin <= threshold) WeatherWarning.WILL_CANCEL
                else WeatherWarning.NONE
            }

            WeatherType.NONE -> WeatherWarning.NONE
        }
    }
}

enum class WeatherWarning {
    NONE,           // Ride goes ahead
    WILL_CANCEL     // Ride will be cancelled per driver's condition
}
