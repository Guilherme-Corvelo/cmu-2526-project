package pt.ulisboa.tecnico.sharist.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import pt.ulisboa.tecnico.sharist.data.model.*

class MockRemoteDataSource : RemoteDataSource {
    private var _uid: String? = null
    override val currentUid: String? get() = _uid

    override suspend fun signIn(email: String, pass: String): com.google.firebase.auth.AuthResult? {
        _uid = "mock_uid_${email.substringBefore("@")}"
        return null 
    }

    override suspend fun register(email: String, pass: String): com.google.firebase.auth.AuthResult? {
        _uid = "mock_uid_${email.substringBefore("@")}"
        return null
    }

    override fun signOut() {
        _uid = null
    }
    override suspend fun createUserProfile(user: User) {}
    override suspend fun getUser(uid: String): User? = null
    override suspend fun updateBalance(uid: String, delta: Double) {}
    override suspend fun submitReview(review: Review) {}
    override fun observeRides(filter: RideFilter): Flow<List<Ride>> = flowOf(emptyList())
    override suspend fun getRide(rideId: String): Ride? = null
    override fun observeDriverRides(driverId: String): Flow<List<Ride>> = flowOf(emptyList())
    override suspend fun createRide(ride: Ride): String = "mock_ride_id"
    override suspend fun decrementSeat(rideId: String) {}
    override suspend fun createBooking(booking: Booking): String = "mock_booking_id"
    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {}
    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> = flowOf(emptyList())
    override fun observeRideBookings(rideId: String): Flow<List<Booking>> = flowOf(emptyList())
}
