package pt.ulisboa.tecnico.sharist.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRequestStore
import pt.ulisboa.tecnico.sharist.ui.demo.DemoRideStore
import java.util.UUID

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

    override fun observeReviewsForUser(userId: String): Flow<List<Review>> =
        DemoRideStore.observeReviewsForUser(userId)

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

    // Ride Requests
    override fun observeOpenRequests(): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.status == RequestStatus.OPEN } }

    override fun observePassengerRequests(passengerId: String): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.passengerId == passengerId } }

    override fun observeDriverRequests(driverId: String): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.driverId == driverId } }

    override suspend fun createRequest(request: RideRequest): String {
        val id = request.id.ifBlank { "mock_req_${UUID.randomUUID()}" }
        DemoRequestStore.requests.value = (listOf(request.copy(id = id)) + DemoRequestStore.requests.value)
        return id
    }

    override suspend fun cancelRequest(requestId: String) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == requestId) it.copy(status = RequestStatus.CANCELLED) else it
        }
    }

    override suspend fun completeRequest(requestId: String) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == requestId) it.copy(status = RequestStatus.COMPLETED) else it
        }
    }

    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == requestId) it.copy(status = status) else it
        }
    }

    override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == requestId) it.copy(
                status = RequestStatus.ACCEPTED,
                driverId = driverId,
                driverName = driverName,
                driverRating = driverRating
            ) else it
        }
    }
}
