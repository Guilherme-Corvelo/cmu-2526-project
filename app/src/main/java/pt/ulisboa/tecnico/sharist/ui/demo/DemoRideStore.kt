package pt.ulisboa.tecnico.sharist.ui.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import pt.ulisboa.tecnico.sharist.data.model.*
import java.util.Date
import java.util.UUID

object DemoRideStore {
    private val DEMO_CLIENT = User(
        uid = DemoRequestStore.DEMO_CLIENT_ID,
        displayName = DemoRequestStore.DEMO_CLIENT_NAME,
        email = "client@demo.app",
        driver = false,
        balance = 25.0
    )

    private val DEMO_DRIVER = User(
        uid = DemoRequestStore.DEMO_DRIVER_ID,
        displayName = DemoRequestStore.DEMO_DRIVER_NAME,
        email = "driver@demo.app",
        driver = true,
        rating = 4.8,
        ratingCount = 36
    )

    private val users = linkedMapOf(
        DEMO_CLIENT.uid to DEMO_CLIENT,
        DEMO_DRIVER.uid to DEMO_DRIVER
    )

    private val ridesFlow = MutableStateFlow(
        mutableListOf(
            Ride(
                id = "ride_demo_1",
                driverId = DEMO_DRIVER.uid,
                driverName = DEMO_DRIVER.displayName,
                origin = "IST Alameda",
                destination = "Saldanha",
                departureTime = Date(System.currentTimeMillis() + 45 * 60 * 1000),
                seatsTotal = 3,
                seatsAvailable = 2,
                pricePerSeat = 2.5,
                status = RideStatus.OPEN
            ),
            Ride(
                id = "ride_demo_2",
                driverId = DEMO_DRIVER.uid,
                driverName = DEMO_DRIVER.displayName,
                origin = "Campo Grande",
                destination = "Oriente",
                departureTime = Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000),
                seatsTotal = 4,
                seatsAvailable = 4,
                pricePerSeat = 3.0,
                status = RideStatus.OPEN
            )
        )
    )

    private val bookingsFlow = MutableStateFlow(mutableListOf<Booking>())
    private val reviewsFlow = MutableStateFlow(mutableListOf<Review>())

    fun resetDemoData() {
        bookingsFlow.value = mutableListOf()
        reviewsFlow.value = mutableListOf()
    }

    fun createOrUpdateUser(user: User) {
        users[user.uid] = user
    }

    fun getUser(uid: String): User? = users[uid]

    fun findUserByEmail(email: String): User? =
        users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    fun updateBalance(uid: String, delta: Double) {
        val current = users[uid] ?: return
        users[uid] = current.copy(balance = current.balance + delta)
    }

    fun addReview(review: Review) {
        val id = review.id.ifBlank { "mock_review_${UUID.randomUUID()}" }
        val finalReview = review.copy(id = id, createdAt = Date())
        reviewsFlow.value = (reviewsFlow.value + finalReview).toMutableList()
        
        // Update user rating
        users[review.driverId]?.let { user ->
            val newCount = user.ratingCount + 1
            val newRating = ((user.rating * user.ratingCount) + review.rating) / newCount
            users[review.driverId] = user.copy(rating = newRating, ratingCount = newCount)
        }

        // Update the reviewed status on the request or booking
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == review.requestId) {
                if (it.driverId == review.passengerId) it.copy(driverReviewed = true)
                else it.copy(passengerReviewed = true)
            } else it
        }

        bookingsFlow.value = bookingsFlow.value.map {
            if (it.id == review.requestId) {
                if (it.driverId == review.passengerId) it.copy(driverReviewed = true)
                else it.copy(passengerReviewed = true)
            } else it
        }.toMutableList()
    }

    fun observeReviewsForUser(userId: String): Flow<List<Review>> =
        reviewsFlow.map { reviews -> reviews.filter { it.driverId == userId }.sortedByDescending { it.createdAt?.time ?: 0L } }

    fun observeRides(filter: RideFilter): Flow<List<Ride>> {
        return ridesFlow.map { rides ->
            rides.filter { ride ->
                matchesText(ride.origin, filter.origin) &&
                    matchesText(ride.destination, filter.destination) &&
                    ride.seatsAvailable >= filter.minSeats &&
                    (filter.maxPrice == null || ride.pricePerSeat <= filter.maxPrice)
            }
        }
    }

    fun getRide(rideId: String): Ride? = ridesFlow.value.firstOrNull { it.id == rideId }

    fun observeDriverRides(driverId: String): Flow<List<Ride>> =
        ridesFlow.map { rides -> rides.filter { it.driverId == driverId } }

    fun createRide(ride: Ride): String {
        val id = ride.id.ifBlank { "mock_ride_${UUID.randomUUID()}" }
        ridesFlow.value = (ridesFlow.value + ride.copy(id = id)).toMutableList()
        return id
    }

    fun decrementSeat(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId && ride.seatsAvailable > 0) {
                val newSeats = ride.seatsAvailable - 1
                ride.copy(
                    seatsAvailable = newSeats,
                    status = if (newSeats == 0) RideStatus.FULL else RideStatus.OPEN
                )
            } else {
                ride
            }
        }.toMutableList()
    }

    fun cancelRide(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) ride.copy(status = RideStatus.CANCELLED) else ride
        }.toMutableList()
    }

    fun completeRide(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId) ride.copy(status = RideStatus.COMPLETED) else ride
        }.toMutableList()
    }

    fun createBooking(booking: Booking): String {
        val id = booking.id.ifBlank { "mock_booking_${UUID.randomUUID()}" }
        bookingsFlow.value = (bookingsFlow.value + booking.copy(id = id)).toMutableList()
        return id
    }

    fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        bookingsFlow.value = bookingsFlow.value.map { booking ->
            if (booking.id == bookingId) booking.copy(status = status) else booking
        }.toMutableList()
    }

    fun observePassengerBookings(passengerId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.passengerId == passengerId } }

    fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.rideId == rideId } }

    fun observeDriverBookings(driverId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.driverId == driverId } }

    private fun matchesText(field: String, query: String): Boolean {
        if (query.isBlank()) return true
        return field.contains(query.trim(), ignoreCase = true)
    }
}
