package pt.ulisboa.tecnico.sharist.utils

import kotlin.math.abs
import kotlin.math.roundToInt

object PriceCalculator {
    private const val BASE_FARE = 1.50
    private const val RATE_PER_KM = 0.42

    fun estimate(origin: String, destination: String): Double {
        val combined = origin.trim().lowercase() + destination.trim().lowercase()
        val hash = abs(combined.hashCode())
        val estimatedKm = (hash % 79 + 2).toDouble()
        val raw = BASE_FARE + estimatedKm * RATE_PER_KM
        return (raw * 100.0).roundToInt() / 100.0
    }

    fun format(price: Double): String = "€ %.2f".format(price)
}
