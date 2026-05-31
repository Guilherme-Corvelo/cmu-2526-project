package pt.ulisboa.tecnico.sharist.utils

import java.security.MessageDigest

object SecurityUtils {
    private const val SALT = "sharist_secure_salt_2024"

    /**
     * Hashes sensitive identifiers like Phone Numbers or Device IDs 
     * before storing them to protect user privacy while maintaining 
     * the ability to detect duplicate/rogue accounts.
     */
    fun hashIdentifier(input: String): String {
        val saltedInput = input + SALT
        return MessageDigest.getInstance("SHA-256")
            .digest(saltedInput.toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }

    /**
     * Statistical calculation for standard deviation to detect outlier ratings.
     */
    fun calculateStdDev(numbers: List<Double>): Double {
        if (numbers.isEmpty()) return 0.0
        val avg = numbers.average()
        return Math.sqrt(numbers.map { Math.pow(it - avg, 2.0) }.sum() / numbers.size)
    }

    /**
     * Detects if a value is an outlier based on standard deviation.
     * Requirement 4.2: Ratings significantly different from the mean are flagged.
     */
    fun isOutlier(value: Double, numbers: List<Double>, threshold: Double = 2.0): Boolean {
        if (numbers.size < 5) return false // Need a minimum sample size for statistical significance
        val avg = numbers.average()
        val stdDev = calculateStdDev(numbers)
        if (stdDev == 0.0) return false
        return Math.abs(value - avg) > threshold * stdDev
    }
}
