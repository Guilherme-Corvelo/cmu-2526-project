package pt.ulisboa.tecnico.sharist.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import pt.ulisboa.tecnico.sharist.data.model.*
import java.util.Date
import java.util.UUID

class MockRemoteDataSource : RemoteDataSource {
    private var _uid: String? = null
    override val currentUid: String? get() = _uid

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

    override suspend fun signIn(email: String, pass: String): com.google.firebase.auth.AuthResult? {
        val matched = users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }
        _uid = matched?.uid ?: "mock_uid_${email.substringBefore("@").ifBlank { "user" }}"
        return null
    }

    override suspend fun register(email: String, pass: String): com.google.firebase.auth.AuthResult? {
        _uid = "mock_uid_${email.substringBefore("@").ifBlank { "user" }}"
        return null
    }

    override fun signOut() {
        _uid = null
    }

    override suspend fun createUserProfile(user: User) {
        users[user.uid] = user
    }

    override suspend fun getUser(uid: String): User? = users[uid]

    override suspend fun updateBalance(uid: String, delta: Double) {
        val current = users[uid] ?: return
        users[uid] = current.copy(balance = current.balance + delta)
    }

    override suspend fun submitReview(review: Review) = Unit

    override fun observeRides(filter: RideFilter): Flow<List<Ride>> {
        return ridesFlow.map { rides ->
            rides.filter { ride ->
                matchesText(ride.origin, filter.origin) &&
                    matchesText(ride.destination, filter.destination) &&
                    ride.seatsAvailable >= filter.minSeats &&
                    (filter.maxPrice == null || ride.pricePerSeat <= filter.maxPrice)
            }
        }
    }

    override suspend fun getRide(rideId: String): Ride? = ridesFlow.value.firstOrNull { it.id == rideId }

    override fun observeDriverRides(driverId: String): Flow<List<Ride>> =
        ridesFlow.map { rides -> rides.filter { it.driverId == driverId } }

    override suspend fun createRide(ride: Ride): String {
        val id = ride.id.ifBlank { "mock_ride_${UUID.randomUUID()}" }
        ridesFlow.value = (ridesFlow.value + ride.copy(id = id)).toMutableList()
        return id
    }

    override suspend fun decrementSeat(rideId: String) {
        ridesFlow.value = ridesFlow.value.map { ride ->
            if (ride.id == rideId && ride.seatsAvailable > 0) {
                val newSeats = ride.seatsAvailable - 1
                ride.copy(
                    seatsAvailable = newSeats,
                    status = if (newSeats == 0) RideStatus.FULL else RideStatus.OPEN
                )
            } else ride
        }.toMutableList()
    }

    override suspend fun createBooking(booking: Booking): String {
        val id = booking.id.ifBlank { "mock_booking_${UUID.randomUUID()}" }
        bookingsFlow.value = (bookingsFlow.value + booking.copy(id = id)).toMutableList()
        return id
    }

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        bookingsFlow.value = bookingsFlow.value.map { booking ->
            if (booking.id == bookingId) booking.copy(status = status) else booking
        }.toMutableList()
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.passengerId == passengerId } }

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        bookingsFlow.map { bookings -> bookings.filter { it.rideId == rideId } }

    private fun matchesText(field: String, query: String): Boolean {
        if (query.isBlank()) return true
        return field.contains(query.trim(), ignoreCase = true)
    }

    companion object {
        private val DEMO_CLIENT = User(
            uid = "demo_client_uid",
            displayName = "Demo Client",
            email = "client@demo.app",
            isDriver = false,
            balance = 25.0
        )

        private val DEMO_DRIVER = User(
            uid = "demo_driver_uid",
            displayName = "Demo Driver",
            email = "driver@demo.app",
            isDriver = true,
            rating = 4.8,
            ratingCount = 36
        )
    }
}
