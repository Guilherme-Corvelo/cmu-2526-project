package pt.ulisboa.tecnico.sharist.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class User(
    @DocumentId val uid: String = "",
    val hashedUid: String = "",
    val isVerified: Boolean = false,
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val carPhotoUrl: String? = null,
    val balance: Double = 1000.0,
    val rating: Double = 5.0,
    val ratingCount: Int = 0,
    val rating1Count: Int = 0,
    val rating2Count: Int = 0,
    val rating3Count: Int = 0,
    val rating4Count: Int = 0,
    val rating5Count: Int = 0,
    val driver: Boolean = false,
    val vehicleType: VehicleType = VehicleType.NONE,
    val vehiclePlate: String = "",
    val preferredPassengerRating: Double = 0.0,
    val trustScore: Double = 1.0, // Scale 0.0 to 1.0 for meta-moderation
    val totalRidesParticipated: Int = 0
)

enum class VehicleType(val displayName: String, val maxSeats: Int) {
    NONE("No vehicle", 0),
    CITY_COMPACT("City Compact", 3),
    SEDAN("Sedan", 4),
    SUV("SUV", 6),
    VAN("Van", 8);
}

data class RideRequest(
    @DocumentId val id: String = "",
    val passengerId: String? = null, // Can be anonymized post-ride
    val hashedPassengerId: String = "",
    val passengerName: String = "",
    val passengerPhotoUrl: String? = null,
    val origin: String = "",
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val originRadius: Double = 500.0, // walking distance/radius requirement
    val originPhotoUrl: String? = null,
    val destination: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val destinationRadius: Double = 500.0, // walking distance/radius requirement
    val destinationPhotoUrl: String? = null,
    val requestedTime: Date? = null,
    val timeToleranceBefore: Int = 15, // in minutes
    val timeToleranceAfter: Int = 15, // in minutes
    val periodic: Boolean = false,
    val periodicLabel: String = "", // "Daily", "Weekly", etc.
    val estimatedPrice: Double = 0.0,
    val driverId: String? = null,
    val hashedDriverId: String = "",
    val driverName: String? = null,
    val driverRating: Double = 5.0,
    val status: RequestStatus = RequestStatus.OPEN,
    val passengerReviewed: Boolean = false,
    val driverReviewed: Boolean = false,
    val passengerPaid: Boolean = false,
    val driverPaid: Boolean = false,
    val passengerRefunded: Boolean = false,
    val deniedBy: List<String> = emptyList(), // Drivers who ignored this request
    val deniedDrivers: List<String> = emptyList(), // Drivers rejected by the passenger
    val weatherCondition: WeatherCondition = WeatherCondition(),
    val weatherWarning: Boolean = false,
    @get:PropertyName("pending") @set:PropertyName("pending") var isPending: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)

enum class RequestStatus { OPEN, ACCEPTED, EN_ROUTE, PICKED_UP, COMPLETED, CANCELLED }

data class Review(
    @DocumentId val id: String = "",
    val requestId: String = "",
    val driverId: String = "",
    val hashedDriverId: String = "",
    val passengerId: String? = null,
    val hashedPassengerId: String = "",
    val rideId: String = "",
    val rating: Int = 5,
    val comment: String = "",
    val isOutlier: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)

data class Ride(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val hashedDriverId: String = "",
    val driverName: String = "",
    val driverPhotoUrl: String? = null,
    val carPhotoUrl: String? = null,
    val driverRating: Double = 5.0,
    val origin: String = "",
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val originPhotoUrl: String? = null,
    val destination: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val destinationPhotoUrl: String? = null,
    val departureTime: Date? = null,
    val estimatedArrivalTime: Date? = null,
    val cancellationLimitMinutes: Int = 60,
    val seatsTotal: Int = 1,
    val seatsAvailable: Int = 1,
    val periodic: Boolean = false,
    val periodicLabel: String = "",
    val pricePerSeat: Double = 0.0,
    val status: RideStatus = RideStatus.OPEN,
    val weatherCondition: WeatherCondition = WeatherCondition(),
    val weatherWarning: Boolean = false,
    @get:PropertyName("pending") @set:PropertyName("pending") var isPending: Boolean = false,
    val hasNewRequests: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)

enum class RideStatus { OPEN, FULL, EN_ROUTE, COMPLETED, CANCELLED }

enum class WeatherType { NONE, RAIN, TOO_HOT, TOO_COLD }

data class WeatherCondition(
    val type: WeatherType = WeatherType.NONE,
    val threshold: Double? = null
)

data class RideFilter(
    val origin: String = "",
    val destination: String = "",
    val minSeats: Int = 1,
    val maxPrice: Double? = null,
    val date: Date? = null
)

data class Booking(
    @DocumentId val id: String = "",
    val rideId: String = "",
    val passengerId: String? = null,
    val hashedPassengerId: String = "",
    val passengerName: String = "",
    val passengerRating: Double = 5.0,
    val passengerPhotoUrl: String? = null,
    val seatsRequested: Int = 1,
    val totalPrice: Double = 0.0,
    val status: BookingStatus = BookingStatus.PENDING,
    @ServerTimestamp val createdAt: Date? = null,
    val origin: String = "",
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val destination: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val departureTime: Date? = null,
    val driverName: String = "",
    val driverId: String = "",
    val hashedDriverId: String = "",
    val recurring: Boolean = false,
    val passengerReviewed: Boolean = false,
    val driverReviewed: Boolean = false,
    val passengerPaid: Boolean = false,
    val driverPaid: Boolean = false,
    val passengerRefunded: Boolean = false,
    val weatherCondition: WeatherCondition = WeatherCondition(),
    val weatherWarning: Boolean = false,
    @get:PropertyName("pending") @set:PropertyName("pending") var isPending: Boolean = false
)

enum class BookingStatus { PENDING, ACCEPTED, EN_ROUTE, PICKED_UP, COMPLETED, CANCELLED, REJECTED }

@Entity(tableName = "favorite_locations")
data class FavoriteLocation(
    @PrimaryKey val id: String = "",
    val userId: String = "",
    val name: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val isStartingLocation: Boolean = false
)

@Entity(tableName = "users_cache")
data class UserEntity(
    @PrimaryKey val uid: String,
    val hashedUid: String,
    val isVerified: Boolean,
    val displayName: String,
    val email: String,
    val photoUrl: String?,
    val carPhotoUrl: String?,
    val balance: Double,
    val rating: Double,
    val ratingCount: Int,
    val rating1Count: Int,
    val rating2Count: Int,
    val rating3Count: Int,
    val rating4Count: Int,
    val rating5Count: Int,
    val driver: Boolean,
    val vehicleType: String,
    val vehiclePlate: String,
    val preferredPassengerRating: Double,
    val trustScore: Double,
    val totalRidesParticipated: Int
)

fun User.toEntity() = UserEntity(
    uid, hashedUid, isVerified, displayName, email, photoUrl, carPhotoUrl, balance, rating, ratingCount,
    rating1Count, rating2Count, rating3Count, rating4Count, rating5Count,
    driver, vehicleType.name, vehiclePlate, preferredPassengerRating, trustScore, totalRidesParticipated
)

fun UserEntity.toDomain() = User(
    uid = uid, hashedUid = hashedUid, isVerified = isVerified, displayName = displayName,
    email = email, photoUrl = photoUrl, carPhotoUrl = carPhotoUrl, balance = balance, rating = rating, ratingCount = ratingCount,
    rating1Count = rating1Count, rating2Count = rating2Count, rating3Count = rating3Count,
    rating4Count = rating4Count, rating5Count = rating5Count,
    driver = driver, vehicleType = VehicleType.valueOf(vehicleType), vehiclePlate = vehiclePlate,
    preferredPassengerRating = preferredPassengerRating, trustScore = trustScore,
    totalRidesParticipated = totalRidesParticipated
)

@Entity(tableName = "rides_cache")
data class RideEntity(
    @PrimaryKey val id: String,
    val driverId: String,
    val hashedDriverId: String = "",
    val driverName: String,
    val driverPhotoUrl: String?,
    val carPhotoUrl: String? = null,
    val driverRating: Double,
    val origin: String,
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val originPhotoUrl: String? = null,
    val destination: String,
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val destinationPhotoUrl: String? = null,
    val departureTimeMs: Long,
    val estimatedArrivalTimeMs: Long = 0L,
    val cancellationLimitMinutes: Int = 60,
    val seatsTotal: Int,
    val seatsAvailable: Int,
    val pricePerSeat: Double,
    val status: String,
    val weatherType: String = "NONE",
    val weatherThreshold: Double? = null,
    val weatherWarning: Boolean = false,
    val isPending: Boolean = false,
    val hasNewRequests: Boolean = false,
    val createdAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun Ride.toEntity() = RideEntity(
    id, driverId, hashedDriverId, driverName, driverPhotoUrl, carPhotoUrl, driverRating,
    origin, originLat, originLng, originPhotoUrl,
    destination, destinationLat, destinationLng, destinationPhotoUrl,
    departureTime?.time ?: 0L, estimatedArrivalTime?.time ?: 0L, cancellationLimitMinutes, seatsTotal, seatsAvailable, pricePerSeat, status.name,
    weatherCondition.type.name, weatherCondition.threshold, weatherWarning, isPending,
    hasNewRequests, createdAt?.time ?: System.currentTimeMillis()
)

fun RideEntity.toDomain() = Ride(
    id = id, driverId = driverId, hashedDriverId = hashedDriverId, driverName = driverName,
    driverPhotoUrl = driverPhotoUrl, carPhotoUrl = carPhotoUrl, driverRating = driverRating,
    origin = origin, originLat = originLat, originLng = originLng, originPhotoUrl = originPhotoUrl,
    destination = destination, destinationLat = destinationLat, destinationLng = destinationLng, destinationPhotoUrl = destinationPhotoUrl,
    departureTime = Date(departureTimeMs),
    estimatedArrivalTime = if (estimatedArrivalTimeMs > 0) Date(estimatedArrivalTimeMs) else null,
    cancellationLimitMinutes = cancellationLimitMinutes,
    seatsTotal = seatsTotal, seatsAvailable = seatsAvailable,
    pricePerSeat = pricePerSeat, status = RideStatus.valueOf(status),
    weatherCondition = WeatherCondition(
        type = WeatherType.valueOf(weatherType),
        threshold = weatherThreshold
    ),
    weatherWarning = weatherWarning,
    isPending = isPending,
    hasNewRequests = hasNewRequests,
    createdAt = if (createdAtMs > 0) Date(createdAtMs) else null
)

@Entity(tableName = "requests_cache")
data class RideRequestEntity(
    @PrimaryKey val id: String,
    val passengerId: String?,
    val hashedPassengerId: String = "",
    val passengerName: String,
    val passengerPhotoUrl: String? = null,
    val origin: String,
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val originRadius: Double = 500.0,
    val originPhotoUrl: String? = null,
    val destination: String,
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val destinationRadius: Double = 500.0,
    val destinationPhotoUrl: String? = null,
    val requestedTimeMs: Long,
    val timeToleranceBefore: Int = 15,
    val timeToleranceAfter: Int = 15,
    val periodic: Boolean = false,
    val periodicLabel: String = "",
    val estimatedPrice: Double,
    val driverId: String?,
    val hashedDriverId: String = "",
    val driverName: String?,
    val driverRating: Double,
    val status: String,
    val passengerReviewed: Boolean,
    val driverReviewed: Boolean,
    val weatherType: String = "NONE",
    val weatherThreshold: Double? = null,
    val weatherWarning: Boolean = false,
    val isPending: Boolean = false,
    val createdAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun RideRequest.toEntity() = RideRequestEntity(
    id, passengerId, hashedPassengerId, passengerName, passengerPhotoUrl,
    origin, originLat, originLng, originRadius, originPhotoUrl,
    destination, destinationLat, destinationLng, destinationRadius, destinationPhotoUrl,
    requestedTime?.time ?: 0L, timeToleranceBefore, timeToleranceAfter, periodic, periodicLabel, estimatedPrice,
    driverId, hashedDriverId, driverName, driverRating, status.name, passengerReviewed, driverReviewed,
    weatherCondition.type.name, weatherCondition.threshold, weatherWarning, isPending,
    createdAt?.time ?: System.currentTimeMillis()
)

fun RideRequestEntity.toDomain() = RideRequest(
    id = id,
    passengerId = passengerId,
    hashedPassengerId = hashedPassengerId,
    passengerName = passengerName,
    passengerPhotoUrl = passengerPhotoUrl,
    origin = origin, originLat = originLat, originLng = originLng, originRadius = originRadius, originPhotoUrl = originPhotoUrl,
    destination = destination, destinationLat = destinationLat, destinationLng = destinationLng, destinationRadius = destinationRadius, destinationPhotoUrl = destinationPhotoUrl,
    requestedTime = Date(requestedTimeMs),
    timeToleranceBefore = timeToleranceBefore,
    timeToleranceAfter = timeToleranceAfter,
    periodic = periodic,
    periodicLabel = periodicLabel,
    estimatedPrice = estimatedPrice,
    driverId = driverId,
    hashedDriverId = hashedDriverId,
    driverName = driverName,
    driverRating = driverRating,
    status = RequestStatus.valueOf(status),
    passengerReviewed = passengerReviewed,
    driverReviewed = driverReviewed,
    weatherCondition = WeatherCondition(
        type = WeatherType.valueOf(weatherType),
        threshold = weatherThreshold
    ),
    weatherWarning = weatherWarning,
    isPending = isPending,
    createdAt = if (createdAtMs > 0) Date(createdAtMs) else null
)

@Entity(tableName = "bookings_cache")
data class BookingEntity(
    @PrimaryKey val id: String,
    val rideId: String,
    val passengerId: String?,
    val hashedPassengerId: String = "",
    val passengerName: String,
    val passengerRating: Double,
    val passengerPhotoUrl: String?,
    val seatsRequested: Int,
    val totalPrice: Double,
    val status: String,
    val createdAtMs: Long,
    val origin: String,
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val destination: String,
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val departureTimeMs: Long,
    val driverName: String,
    val driverId: String,
    val hashedDriverId: String = "",
    val recurring: Boolean,
    val passengerReviewed: Boolean,
    val driverReviewed: Boolean,
    val passengerPaid: Boolean,
    val driverPaid: Boolean,
    val passengerRefunded: Boolean,
    val weatherType: String = "NONE",
    val weatherThreshold: Double? = null,
    val weatherWarning: Boolean = false,
    val isPending: Boolean = false,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun Booking.toEntity() = BookingEntity(
    id, rideId, passengerId, hashedPassengerId, passengerName, passengerRating, passengerPhotoUrl,
    seatsRequested, totalPrice, status.name, createdAt?.time ?: System.currentTimeMillis(),
    origin, originLat, originLng, destination, destinationLat, destinationLng, departureTime?.time ?: 0L, driverName, driverId, hashedDriverId,
    recurring, passengerReviewed, driverReviewed, passengerPaid, driverPaid, passengerRefunded,
    weatherCondition.type.name, weatherCondition.threshold, weatherWarning, isPending
)

fun BookingEntity.toDomain() = Booking(
    id = id, rideId = rideId, passengerId = passengerId, hashedPassengerId = hashedPassengerId,
    passengerName = passengerName,
    passengerRating = passengerRating, passengerPhotoUrl = passengerPhotoUrl,
    seatsRequested = seatsRequested, totalPrice = totalPrice, status = BookingStatus.valueOf(status),
    createdAt = Date(createdAtMs),
    origin = origin, originLat = originLat, originLng = originLng,
    destination = destination, destinationLat = destinationLat, destinationLng = destinationLng,
    departureTime = Date(departureTimeMs), driverName = driverName, driverId = driverId,
    hashedDriverId = hashedDriverId,
    recurring = recurring, passengerReviewed = passengerReviewed, driverReviewed = driverReviewed,
    passengerPaid = passengerPaid, driverPaid = driverPaid, passengerRefunded = passengerRefunded,
    weatherCondition = WeatherCondition(
        type = WeatherType.valueOf(weatherType),
        threshold = weatherThreshold
    ),
    weatherWarning = weatherWarning,
    isPending = isPending
)

@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val type: String,
    val payload: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    var synced: Boolean = false,
    var errorMessage: String? = null
)
