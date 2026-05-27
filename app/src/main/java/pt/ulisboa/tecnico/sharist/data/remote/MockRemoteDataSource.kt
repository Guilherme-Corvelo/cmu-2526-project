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

    override suspend fun cancelRide(rideId: String) {
        DemoRideStore.cancelRide(rideId)
    }

    override suspend fun completeRide(rideId: String) {
        DemoRideStore.completeRide(rideId)
    }

    override suspend fun startRide(rideId: String) {
        DemoRideStore.startRide(rideId)
    }

    override suspend fun decrementSeat(rideId: String) {
        DemoRideStore.decrementSeat(rideId)
    }

    override suspend fun createBooking(booking: Booking): String = DemoRideStore.createBooking(booking)

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        DemoRideStore.updateBookingStatus(bookingId, status, currentUid)
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> =
        DemoRideStore.observePassengerBookings(passengerId)

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        DemoRideStore.observeRideBookings(rideId)

    override fun observeDriverBookings(driverId: String): Flow<List<Booking>> =
        DemoRideStore.observeDriverBookings(driverId)

    // Ride Requests
    override fun observeOpenRequests(): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.status == RequestStatus.OPEN } }

    override fun observePassengerRequests(passengerId: String): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.passengerId == passengerId } }

    override fun observeDriverRequests(driverId: String): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.driverId == driverId } }

    override suspend fun createRequest(request: RideRequest): String {
        val id = request.id.ifBlank { "mock_req_${UUID.randomUUID()}" }
        // Upfront payment for the request
        DemoRideStore.updateBalance(request.passengerId, -request.estimatedPrice)
        
        val finalRequest = request.copy(
            id = id,
            passengerPaid = true,
            passengerRefunded = false
        )
        DemoRequestStore.requests.value = (listOf(finalRequest) + DemoRequestStore.requests.value)
        return id
    }

    override suspend fun cancelRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.CANCELLED)
    }

    override suspend fun completeRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.COMPLETED)
    }

    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map { req ->
            if (req.id == requestId) {
                if (status == RequestStatus.CANCELLED && currentUid == req.driverId && req.driverId != null) {
                    // Driver cancels acceptance -> Return to OPEN so other drivers can pick it up
                    req.copy(
                        status = RequestStatus.OPEN,
                        driverId = null,
                        driverName = null,
                        driverRating = 5.0
                    )
                } else {
                    var updated = req.copy(status = status)
                    
                    if (status == RequestStatus.COMPLETED) {
                        // Driver gets paid
                        if (currentUid == updated.driverId && !updated.driverPaid) {
                            DemoRideStore.updateBalance(updated.driverId!!, updated.estimatedPrice)
                            updated = updated.copy(driverPaid = true)
                        }
                    } else if (status == RequestStatus.CANCELLED && updated.passengerPaid && !updated.passengerRefunded) {
                        // Passenger gets refund
                        if (currentUid == updated.passengerId) {
                            DemoRideStore.updateBalance(updated.passengerId, updated.estimatedPrice)
                            updated = updated.copy(passengerRefunded = true)
                        }
                    }
                    updated
                }
            } else req
        }
    }

    override suspend fun denyRequest(requestId: String, driverId: String) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map { req ->
            if (req.id == requestId) {
                req.copy(deniedBy = req.deniedBy + driverId)
            } else req
        }
    }

    override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == requestId && it.status == RequestStatus.OPEN) it.copy(
                status = RequestStatus.ACCEPTED,
                driverId = driverId,
                driverName = driverName,
                driverRating = driverRating
            ) else it
        }
    }

    override fun clearListeners() {
        // No-op for mock
    }
}
