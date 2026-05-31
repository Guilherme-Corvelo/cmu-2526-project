package pt.ulisboa.tecnico.sharist.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class User(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val balance: Double = 1000.0,
    val rating: Double = 5.0,
    val ratingCount: Int = 0,
    val driver: Boolean = false,
    val vehicleType: VehicleType = VehicleType.NONE,
    val vehiclePlate: String = "",
    val preferredPassengerRating: Double = 0.0,
    val trustScore: Double = 1.0, // Scale 0.0 to 1.0
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
    val origin: String = "",
    val destination: String = "",
    val requestedTime: Date? = null,
    val estimatedPrice: Double = 0.0,
    val driverId: String? = null,
    val driverName: String? = null,
    val driverRating: Double = 5.0,
    val status: RequestStatus = RequestStatus.OPEN,
    val reviewed: Boolean = false, // Shared flag for backward compatibility or simple UI
    val passengerReviewed: Boolean = false,
    val driverReviewed: Boolean = false,
    val passengerPaid: Boolean = false,
    val driverPaid: Boolean = false,
    val passengerRefunded: Boolean = false,
    val deniedBy: List<String> = emptyList(), // Drivers who ignored this request
    val deniedDrivers: List<String> = emptyList(), // Drivers rejected by the passenger
    val weatherCondition: WeatherCondition = WeatherCondition(),
    @get:PropertyName("pending") @set:PropertyName("pending") var isPending: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)

enum class RequestStatus { OPEN, ACCEPTED, EN_ROUTE, PICKED_UP, COMPLETED, CANCELLED }

data class Review(
    @DocumentId val id: String = "",
    val requestId: String = "",
    val driverId: String = "",
    val passengerId: String? = null,
    val hashedPassengerId: String = "",
    val rating: Int = 5,
    val comment: String = "",
    val isOutlier: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)

data class Ride(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverPhotoUrl: String? = null,
    val carPhotoUrl: String? = null,
    val driverRating: Double = 5.0,
    val origin: String = "",
    val destination: String = "",
    val departureTime: Date? = null,
    val seatsTotal: Int = 1,
    val seatsAvailable: Int = 1,
    val periodic: Boolean = false,
    val periodicLabel: String = "",
    val pricePerSeat: Double = 0.0,
    val status: RideStatus = RideStatus.OPEN,
    val weatherCondition: WeatherCondition = WeatherCondition(),
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
    val passengerId: String? = null, // Can be anonymized post-ride
    val hashedPassengerId: String = "",
    val passengerName: String = "",
    val passengerRating: Double = 5.0,
    val passengerPhotoUrl: String? = null,
    val seatsRequested: Int = 1,
    val totalPrice: Double = 0.0,
    val status: BookingStatus = BookingStatus.PENDING,
    @ServerTimestamp val createdAt: Date? = null,
    val origin: String = "",
    val destination: String = "",
    val departureTime: Date? = null,
    val driverName: String = "",
    val driverId: String = "",
    val recurring: Boolean = false,
    val reviewed: Boolean = false, // Shared flag
    val passengerReviewed: Boolean = false,
    val driverReviewed: Boolean = false,
    val passengerPaid: Boolean = false,
    val driverPaid: Boolean = false,
    val passengerRefunded: Boolean = false,
    val weatherCondition: WeatherCondition = WeatherCondition(),
    @get:PropertyName("pending") @set:PropertyName("pending") var isPending: Boolean = false
)

enum class BookingStatus { PENDING, ACCEPTED, EN_ROUTE, PICKED_UP, COMPLETED, CANCELLED, REJECTED }

@Entity(tableName = "rides_cache")
data class RideEntity(
    @PrimaryKey val id: String,
    val driverId: String,
    val driverName: String,
    val driverPhotoUrl: String?,
    val driverRating: Double,
    val origin: String,
    val destination: String,
    val departureTimeMs: Long,
    val seatsTotal: Int,
    val seatsAvailable: Int,
    val pricePerSeat: Double,
    val status: String,
    val weatherType: String = "NONE",
    val weatherThreshold: Double? = null,
    val isPending: Boolean = false,
    val hasNewRequests: Boolean = false,
    val createdAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun Ride.toEntity() = RideEntity(
    id, driverId, driverName, driverPhotoUrl, driverRating, origin, destination,
    departureTime?.time ?: 0L, seatsTotal, seatsAvailable, pricePerSeat, status.name,
    weatherCondition.type.name, weatherCondition.threshold, isPending,
    hasNewRequests, createdAt?.time ?: System.currentTimeMillis()
)

fun RideEntity.toDomain() = Ride(
    id = id, driverId = driverId, driverName = driverName,
    driverPhotoUrl = driverPhotoUrl, driverRating = driverRating,
    origin = origin, destination = destination,
    departureTime = Date(departureTimeMs),
    seatsTotal = seatsTotal, seatsAvailable = seatsAvailable,
    pricePerSeat = pricePerSeat, status = RideStatus.valueOf(status),
    weatherCondition = WeatherCondition(
        type = WeatherType.valueOf(weatherType),
        threshold = weatherThreshold
    ),
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
    val origin: String,
    val destination: String,
    val requestedTimeMs: Long,
    val estimatedPrice: Double,
    val driverId: String?,
    val driverName: String?,
    val driverRating: Double,
    val status: String,
    val passengerReviewed: Boolean,
    val driverReviewed: Boolean,
    val weatherType: String = "NONE",
    val weatherThreshold: Double? = null,
    val isPending: Boolean = false,
    val createdAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun RideRequest.toEntity() = RideRequestEntity(
    id, passengerId, hashedPassengerId, passengerName, origin, destination,
    requestedTime?.time ?: 0L, estimatedPrice,
    driverId, driverName, driverRating, status.name, passengerReviewed, driverReviewed,
    weatherCondition.type.name, weatherCondition.threshold, isPending,
    createdAt?.time ?: System.currentTimeMillis()
)

fun RideRequestEntity.toDomain() = RideRequest(
    id = id,
    passengerId = passengerId,
    hashedPassengerId = hashedPassengerId,
    passengerName = passengerName,
    origin = origin,
    destination = destination,
    requestedTime = Date(requestedTimeMs),
    estimatedPrice = estimatedPrice,
    driverId = driverId,
    driverName = driverName,
    driverRating = driverRating,
    status = RequestStatus.valueOf(status),
    passengerReviewed = passengerReviewed,
    driverReviewed = driverReviewed,
    weatherCondition = WeatherCondition(
        type = WeatherType.valueOf(weatherType),
        threshold = weatherThreshold
    ),
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
    val destination: String,
    val departureTimeMs: Long,
    val driverName: String,
    val driverId: String,
    val recurring: Boolean,
    val passengerReviewed: Boolean,
    val driverReviewed: Boolean,
    val passengerPaid: Boolean,
    val driverPaid: Boolean,
    val passengerRefunded: Boolean,
    val weatherType: String = "NONE",
    val weatherThreshold: Double? = null,
    val isPending: Boolean = false,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun Booking.toEntity() = BookingEntity(
    id, rideId, passengerId, hashedPassengerId, passengerName, passengerRating, passengerPhotoUrl,
    seatsRequested, totalPrice, status.name, createdAt?.time ?: System.currentTimeMillis(),
    origin, destination, departureTime?.time ?: 0L, driverName, driverId,
    recurring, passengerReviewed, driverReviewed, passengerPaid, driverPaid, passengerRefunded,
    weatherCondition.type.name, weatherCondition.threshold, isPending
)

fun BookingEntity.toDomain() = Booking(
    id = id, rideId = rideId, passengerId = passengerId, hashedPassengerId = hashedPassengerId,
    passengerName = passengerName,
    passengerRating = passengerRating, passengerPhotoUrl = passengerPhotoUrl,
    seatsRequested = seatsRequested, totalPrice = totalPrice, status = BookingStatus.valueOf(status),
    createdAt = Date(createdAtMs), origin = origin, destination = destination,
    departureTime = Date(departureTimeMs), driverName = driverName, driverId = driverId,
    recurring = recurring, passengerReviewed = passengerReviewed, driverReviewed = driverReviewed,
    passengerPaid = passengerPaid, driverPaid = driverPaid, passengerRefunded = passengerRefunded,
    weatherCondition = WeatherCondition(
        type = WeatherType.valueOf(weatherType),
        threshold = weatherThreshold
    ),
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
