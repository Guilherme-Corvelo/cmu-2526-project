package pt.ulisboa.tecnico.sharist.data.remote

import kotlinx.coroutines.flow.Flow
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRideStore

class MockRemoteDataSource : RemoteDataSource {
    private var _uid: String? = null
    override val currentUid: String? get() = _uid

    override suspend fun signIn(email: String, pass: String): com.google.firebase.auth.AuthResult? {
        val matched = DemoRideStore.findUserByEmail(email)
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
        DemoRideStore.createOrUpdateUser(user)
    }

    override suspend fun getUser(uid: String): User? = DemoRideStore.getUser(uid)

    override suspend fun updateBalance(uid: String, delta: Double) {
        DemoRideStore.updateBalance(uid, delta)
    }

    override suspend fun submitReview(review: Review) {
        DemoRideStore.addReview(review)
    }

    override fun observeRides(filter: RideFilter): Flow<List<Ride>> = DemoRideStore.observeRides(filter)

    override suspend fun getRide(rideId: String): Ride? = DemoRideStore.getRide(rideId)

    override fun observeDriverRides(driverId: String): Flow<List<Ride>> = DemoRideStore.observeDriverRides(driverId)

    override suspend fun createRide(ride: Ride): String = DemoRideStore.createRide(ride)

    override suspend fun decrementSeat(rideId: String) {
        DemoRideStore.decrementSeat(rideId)
    }

    override suspend fun createBooking(booking: Booking): String = DemoRideStore.createBooking(booking)

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        DemoRideStore.updateBookingStatus(bookingId, status)
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> =
        DemoRideStore.observePassengerBookings(passengerId)

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        DemoRideStore.observeRideBookings(rideId)
}
