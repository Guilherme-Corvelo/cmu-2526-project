package pt.ulisboa.tecnico.sharist.data.remote

import kotlinx.coroutines.flow.Flow
import pt.ulisboa.tecnico.sharist.data.model.*

interface RemoteDataSource {
    val currentUid: String?
    suspend fun signIn(email: String, pass: String): com.google.firebase.auth.AuthResult?
    suspend fun register(email: String, pass: String): com.google.firebase.auth.AuthResult?
    fun signOut()
    suspend fun createUserProfile(user: User)
    suspend fun getUser(uid: String): User?
    suspend fun updateBalance(uid: String, delta: Double)
    suspend fun submitReview(review: Review)
    fun observeRides(filter: RideFilter): Flow<List<Ride>>
    suspend fun getRide(rideId: String): Ride?
    fun observeDriverRides(driverId: String): Flow<List<Ride>>
    suspend fun createRide(ride: Ride): String
    suspend fun decrementSeat(rideId: String)
    suspend fun createBooking(booking: Booking): String
    suspend fun updateBookingStatus(bookingId: String, status: BookingStatus)
    fun observePassengerBookings(passengerId: String): Flow<List<Booking>>
    fun observeRideBookings(rideId: String): Flow<List<Booking>>
}
