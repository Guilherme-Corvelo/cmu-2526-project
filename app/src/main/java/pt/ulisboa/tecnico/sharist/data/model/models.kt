package pt.ulisboa.tecnico.sharist.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class User(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val balance: Double = 0.0,
    val rating: Double = 5.0,
    val ratingCount: Int = 0,
    val isDriver: Boolean = false
)

data class RideRequest(
    @DocumentId val id: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val origin: String = "",
    val destination: String = "",
    @ServerTimestamp val requestedTime: Date? = null,
    val estimatedPrice: Double = 0.0,
    val driverId: String? = null,
    val driverName: String? = null,
    val driverRating: Double = 5.0,
    val status: RequestStatus = RequestStatus.OPEN,
    val reviewed: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)

enum class RequestStatus { OPEN, ACCEPTED, COMPLETED, CANCELLED }

data class Review(
    @DocumentId val id: String = "",
    val requestId: String = "",
    val driverId: String = "",
    val passengerId: String = "",
    val rating: Int = 5,
    val comment: String = "",
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
    val pricePerSeat: Double = 0.0,
    val status: RideStatus = RideStatus.OPEN,
    val weatherCondition: WeatherCondition = WeatherCondition(),
    @ServerTimestamp val createdAt: Date? = null
)

enum class RideStatus { OPEN, FULL, COMPLETED, CANCELLED }

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
    val passengerId: String = "",
    val passengerName: String = "",
    val seatsRequested: Int = 1,
    val totalPrice: Double = 0.0,
    val status: BookingStatus = BookingStatus.PENDING,
    @ServerTimestamp val createdAt: Date? = null
)

enum class BookingStatus { PENDING, ACCEPTED, CONFIRMED, CANCELLED, REJECTED }

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
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun Ride.toEntity() = RideEntity(
    id, driverId, driverName, driverPhotoUrl, driverRating, origin, destination,
    departureTime?.time ?: 0L, seatsTotal, seatsAvailable, pricePerSeat, status.name,
    weatherCondition.type.name, weatherCondition.threshold
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
    )
)

@Entity(tableName = "requests_cache")

data class RideRequestEntity(
    @PrimaryKey val id: String,
    val passengerId: String,
    val passengerName: String,
    val origin: String,
    val destination: String,
    val requestedTimeMs: Long,
    val estimatedPrice: Double,
    val driverId: String?,
    val driverName: String?,
    val driverRating: Double,
    val status: String,
    val reviewed: Boolean,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun RideRequest.toEntity() = RideRequestEntity(
    id, passengerId, passengerName, origin, destination,
    requestedTime?.time ?: 0L, estimatedPrice,
    driverId, driverName, driverRating, status.name, reviewed
)

fun RideRequestEntity.toDomain() = RideRequest(
    id = id,
    passengerId = passengerId,
    passengerName = passengerName,
    origin = origin,
    destination = destination,
    requestedTime = Date(requestedTimeMs),
    estimatedPrice = estimatedPrice,
    driverId = driverId,
    driverName = driverName,
    driverRating = driverRating,
    status = RequestStatus.valueOf(status),
    reviewed = reviewed
)

@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val type: String,
    val payload: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    var synced: Boolean = false
)
