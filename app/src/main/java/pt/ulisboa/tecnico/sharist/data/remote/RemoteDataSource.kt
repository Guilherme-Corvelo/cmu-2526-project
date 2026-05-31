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
    fun observeReviewsForUser(userId: String): Flow<List<Review>>
    
    // Rides
    fun observeRides(filter: RideFilter): Flow<List<Ride>>
    suspend fun getRide(rideId: String): Ride?
    fun observeDriverRides(driverId: String): Flow<List<Ride>>
    suspend fun createRide(ride: Ride): String
    suspend fun cancelRide(rideId: String)
    suspend fun completeRide(rideId: String)
    suspend fun startRide(rideId: String)
    suspend fun decrementSeat(rideId: String)
    
    // Bookings
    suspend fun createBooking(booking: Booking): String
    suspend fun updateBookingStatus(bookingId: String, status: BookingStatus)
    fun observePassengerBookings(passengerId: String): Flow<List<Booking>>
    fun observeRideBookings(rideId: String): Flow<List<Booking>>
    fun observeDriverBookings(driverId: String): Flow<List<Booking>>

    // Ride Requests (Legacy/Alternative flow)
    fun observeOpenRequests(): Flow<List<RideRequest>>
    fun observePassengerRequests(passengerId: String): Flow<List<RideRequest>>
    fun observeDriverRequests(driverId: String): Flow<List<RideRequest>>
    suspend fun createRequest(request: RideRequest): String
    suspend fun cancelRequest(requestId: String)
    suspend fun completeRequest(requestId: String)
    suspend fun updateRequestStatus(requestId: String, status: RequestStatus)
    suspend fun denyRequest(requestId: String, driverId: String)
    suspend fun rejectDriver(requestId: String, driverId: String)
    suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double)
    suspend fun processRideReputation(rideId: String)
    fun clearListeners()
}
