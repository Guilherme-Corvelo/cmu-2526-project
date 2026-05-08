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

    /**
     * Fetches today's forecast for the given IPMA location ID.
     * Real endpoint: https://api.ipma.pt/open-data/forecast/meteorology/cities/daily/{locationId}.json
     */
    suspend fun getForecast(locationId: Int): Result<WeatherForecast> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.ipma.pt/open-data/forecast/meteorology/cities/daily/$locationId.json"
                val json = URL(url).readText()
                parseIpmaResponse(json)
            }
        }

    private fun parseIpmaResponse(json: String): WeatherForecast {
        val root = JSONObject(json)
        val data = root.getJSONArray("data").getJSONObject(0) // today

        val tMin      = data.optDouble("tMin", 0.0)
        val tMax      = data.optDouble("tMax", 0.0)
        val precipProb = data.optInt("precipitaProb", 0)
        val windClass  = data.optInt("classWindSpeed", 1)
        val weatherId  = data.optInt("idWeatherType", 1)
        val date       = data.optString("forecastDate", "")

        val description = weatherDescription(weatherId)
        val willRain    = precipProb >= 40 || weatherId in listOf(6, 7, 8, 9, 10, 11, 12, 13, 14)

        return WeatherForecast(tMin, tMax, precipProb, windClass, description, willRain, date)
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
