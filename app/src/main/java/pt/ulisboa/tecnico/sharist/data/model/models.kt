package pt.ulisboa.tecnico.sharist.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// ─── User ────────────────────────────────────────────────────────────────────

data class User(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val balance: Double = 0.0,       // used when PayPal is not chosen
    val rating: Double = 5.0,
    val ratingCount: Int = 0,
    val isDriver: Boolean = false
)

// ─── Ride ─────────────────────────────────────────────────────────────────────

data class Ride(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverPhotoUrl: String? = null,
    val driverRating: Double = 5.0,
    val origin: String = "",
    val originLat: Double = 0.0,
    val originLng: Double = 0.0,
    val destination: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    @ServerTimestamp val departureTime: Date? = null,
    val seatsTotal: Int = 4,
    val seatsAvailable: Int = 4,
    val pricePerSeat: Double = 0.0,
    val carPhotoUrl: String? = null,
    val status: RideStatus = RideStatus.OPEN,
    val weatherCondition: WeatherCondition? = null, // optional cancellation rule
    @ServerTimestamp val createdAt: Date? = null
)

enum class RideStatus { OPEN, FULL, CANCELLED, COMPLETED }

data class WeatherCondition(
    val type: WeatherType = WeatherType.NONE,
    val threshold: Double? = null  // e.g. temp > 35°C cancels
)

enum class WeatherType { NONE, RAIN, TOO_HOT, TOO_COLD }

// ─── Booking ──────────────────────────────────────────────────────────────────

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

enum class BookingStatus { PENDING, ACCEPTED, REJECTED, CANCELLED }

// ─── Review ───────────────────────────────────────────────────────────────────

data class Review(
    @DocumentId val id: String = "",
    val targetUserId: String = "",  // driver or passenger being reviewed
    val reviewerId: String = "",
    val rideId: String = "",
    val rating: Int = 5,            // 1–5
    val comment: String = "",
    @ServerTimestamp val createdAt: Date? = null
)

// ─── Room cache entities ──────────────────────────────────────────────────────

@Entity(tableName = "rides_cache")
data class RideEntity(
    @PrimaryKey val id: String,
    val driverId: String,
    val driverName: String,
    val driverPhotoUrl: String?,
    val driverRating: Double,
    val origin: String,
    val originLat: Double,
    val originLng: Double,
    val destination: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val departureTimeMs: Long,
    val seatsAvailable: Int,
    val pricePerSeat: Double,
    val carPhotoUrl: String?,
    val status: String,
    val cachedAtMs: Long = System.currentTimeMillis()
)

fun Ride.toEntity() = RideEntity(
    id, driverId, driverName, driverPhotoUrl, driverRating,
    origin, originLat, originLng,
    destination, destinationLat, destinationLng,
    departureTime?.time ?: 0L,
    seatsAvailable, pricePerSeat, carPhotoUrl,
    status.name
)

fun RideEntity.toRide() = Ride(
    id = id, driverId = driverId, driverName = driverName,
    driverPhotoUrl = driverPhotoUrl, driverRating = driverRating,
    origin = origin, originLat = originLat, originLng = originLng,
    destination = destination, destinationLat = destinationLat, destinationLng = destinationLng,
    departureTime = Date(departureTimeMs),
    seatsAvailable = seatsAvailable, pricePerSeat = pricePerSeat,
    carPhotoUrl = carPhotoUrl, status = RideStatus.valueOf(status)
)

// ─── Search filter ────────────────────────────────────────────────────────────

data class RideFilter(
    val origin: String = "",
    val destination: String = "",
    val date: Date? = null,
    val minSeats: Int = 1,
    val maxPrice: Double? = null
)

// ─── Pending operation (for disconnected mode) ────────────────────────────────

@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val type: String,          // "CREATE_RIDE" | "BOOK_RIDE" | "CANCEL_BOOKING"
    val payload: String,       // JSON-serialised ride/booking
    val createdAtMs: Long = System.currentTimeMillis(),
    var synced: Boolean = false
)
