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

    override suspend fun updateUserProfile(user: User) {
        DemoRideStore.createOrUpdateUser(user)
    }

    override suspend fun getUser(uid: String): User? = DemoRideStore.getUser(uid)

    override suspend fun updateBalance(uid: String, delta: Double) {
        DemoRideStore.updateBalance(uid, delta)
    }

    override suspend fun submitReview(review: Review): String? {
        val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(review.passengerId ?: "")
        val finalReview = review.copy(hashedPassengerId = hashedPid)
        DemoRideStore.addReview(finalReview)
        maybePublishNextPeriodicDemoRequest(review.requestId)
        return finalReview.rideId.ifBlank { review.requestId.ifBlank { "mock_ride" } }
    }

    private fun maybePublishNextPeriodicDemoRequest(requestId: String) {
        val uid = currentUid ?: return
        val req = DemoRequestStore.requests.value.firstOrNull { it.id == requestId } ?: return
        if (!req.periodic || req.status != RequestStatus.COMPLETED) return
        if (uid != req.driverId && uid != req.passengerId) return
        if (DemoRequestStore.requests.value.any { it.previousRequestId == req.id }) return

        val reviewedRequest = if (uid == req.driverId) {
            req.copy(passengerReviewed = true)
        } else {
            req.copy(driverReviewed = true)
        }
        val nextRequest = reviewedRequest.copy(
            id = "mock_req_${UUID.randomUUID()}",
            status = RequestStatus.OPEN,
            requestedTime = calculateNextOccurrence(reviewedRequest.requestedTime, reviewedRequest.periodicLabel),
            previousRequestId = reviewedRequest.id,
            driverId = null,
            hashedDriverId = "",
            driverName = null,
            driverRating = 5.0,
            passengerPaid = false,
            passengerRefunded = false,
            driverPaid = false,
            driverReviewed = false,
            passengerReviewed = false,
            deniedBy = emptyList(),
            deniedDrivers = emptyList(),
            createdAt = java.util.Date()
        )
        DemoRequestStore.requests.value = listOf(nextRequest) + DemoRequestStore.requests.value.map {
            if (it.id == reviewedRequest.id) reviewedRequest.copy(origin = "anonymized", destination = "anonymized") else it
        }
    }

    private fun calculateNextOccurrence(currentDate: java.util.Date?, label: String): java.util.Date {
        val cal = java.util.Calendar.getInstance()
        if (currentDate != null) cal.time = currentDate
        when (label.lowercase()) {
            "daily" -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            "weekdays" -> {
                do {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                } while (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY || cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY)
            }
            "weekly" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            "biweekly" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 2)
            "monthly" -> cal.add(java.util.Calendar.MONTH, 1)
            else -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.time
    }

    override fun observeReviewsForUser(userId: String): Flow<List<Review>> =
        DemoRideStore.observeReviewsForUser(userId)

    override fun observeRides(filter: RideFilter): Flow<List<Ride>> = DemoRideStore.observeRides(filter)

    override suspend fun getRide(rideId: String): Ride? = DemoRideStore.getRide(rideId)

    override fun observeDriverRides(driverId: String): Flow<List<Ride>> = DemoRideStore.observeDriverRides(driverId)

    override suspend fun createRide(ride: Ride): String = DemoRideStore.createRide(ride)

    override suspend fun cancelRide(rideId: String, reschedule: Boolean) {
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

    override suspend fun createBooking(booking: Booking): String {
        val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(booking.passengerId ?: "")
        return DemoRideStore.createBooking(booking.copy(hashedPassengerId = hashedPid))
    }

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        DemoRideStore.updateBookingStatus(bookingId, status, currentUid)
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> {
        val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(passengerId)
        return DemoRideStore.observePassengerBookings(hashedPid)
    }

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        DemoRideStore.observeRideBookings(rideId)

    override fun observeDriverBookings(driverId: String): Flow<List<Booking>> =
        DemoRideStore.observeDriverBookings(driverId)

    // Ride Requests
    override fun observeOpenRequests(): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.status == RequestStatus.OPEN } }

    override fun observePassengerRequests(passengerId: String): Flow<List<RideRequest>> {
        val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(passengerId)
        return DemoRequestStore.requests.map { list -> list.filter { it.hashedPassengerId == hashedPid } }
    }

    override fun observeDriverRequests(driverId: String): Flow<List<RideRequest>> =
        DemoRequestStore.requests.map { list -> list.filter { it.driverId == driverId } }

    override suspend fun createRequest(request: RideRequest): String {
        val id = request.id.ifBlank { "mock_req_${UUID.randomUUID()}" }
        // Upfront payment for the request
        DemoRideStore.updateBalance(request.passengerId ?: "", -request.estimatedPrice)
        
        val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(request.passengerId ?: "")
        val finalRequest = request.copy(
            id = id,
            passengerPaid = true,
            passengerRefunded = false,
            hashedPassengerId = hashedPid
        )
        DemoRequestStore.requests.value = (listOf(finalRequest) + DemoRequestStore.requests.value)
        return id
    }

    override suspend fun cancelRequest(requestId: String, reschedule: Boolean) {
        updateRequestStatus(requestId, RequestStatus.CANCELLED, reschedule)
    }

    override suspend fun completeRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.COMPLETED)
    }

    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus, reschedule: Boolean) {
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
                    } else if (status == RequestStatus.CANCELLED) {
                        if (currentUid == updated.passengerId) {
                            if (updated.passengerPaid && !updated.passengerRefunded) {
                                DemoRideStore.updateBalance(updated.passengerId ?: "", updated.estimatedPrice)
                                updated = updated.copy(passengerRefunded = true)
                            }
                            val penaltyFreeUntil = updated.requestedTime?.let { java.util.Date(it.time - 60 * 60_000L) }
                            if ((req.status == RequestStatus.ACCEPTED || req.status == RequestStatus.EN_ROUTE || req.status == RequestStatus.PICKED_UP) &&
                                penaltyFreeUntil != null && java.util.Date().after(penaltyFreeUntil)
                            ) {
                                val user = DemoRideStore.getUser(updated.passengerId ?: "")
                                if (user != null) {
                                    DemoRideStore.updateUserTrustScore(user.uid, (user.trustScore - 0.10).coerceAtLeast(0.0))
                                }
                            }
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

    override suspend fun rejectDriver(requestId: String, driverId: String) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map { req ->
            if (req.id == requestId) {
                req.copy(
                    status = RequestStatus.OPEN,
                    driverId = null,
                    driverName = null,
                    driverRating = 5.0,
                    deniedDrivers = req.deniedDrivers + driverId
                )
            } else req
        }
    }

    override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        DemoRequestStore.requests.value = DemoRequestStore.requests.value.map {
            if (it.id == requestId && it.status == RequestStatus.OPEN) {
                val isGeneratedPeriodicRequest = it.periodic && it.previousRequestId.isNotBlank()
                if (!it.passengerPaid && !isGeneratedPeriodicRequest && it.passengerId != null) {
                    DemoRideStore.updateBalance(it.passengerId, -it.estimatedPrice)
                }
                it.copy(
                    status = RequestStatus.ACCEPTED,
                    driverId = driverId,
                    driverName = driverName,
                    driverRating = driverRating,
                    passengerPaid = true,
                    passengerRefunded = false
                )
            } else it
        }
    }

    override suspend fun processRideReputation(rideId: String) {
        // Mock implementation for outlier detection could be added here if needed for Demo
    }

    override fun clearListeners() {
        // No-op for mock
    }

    // Favorite Locations
    private val mockFavorites = mutableListOf<FavoriteLocation>()
    override fun observeFavorites(userId: String): Flow<List<FavoriteLocation>> = 
        kotlinx.coroutines.flow.flowOf(mockFavorites.filter { it.userId == userId })

    override suspend fun addFavorite(favorite: FavoriteLocation) {
        mockFavorites.add(favorite)
    }

    override suspend fun deleteFavorite(id: String) {
        mockFavorites.removeAll { it.id == id }
    }

    override suspend fun getAllUsers(): List<User> = DemoRideStore.getAllUsers()
    override suspend fun getReviewsForUserSync(userId: String): List<Review> = DemoRideStore.getReviewsForUserSync(userId)
    override suspend fun flagReviewAsOutlier(reviewId: String) { DemoRideStore.flagReviewAsOutlier(reviewId) }
    override suspend fun updateUserTrustScore(userId: String, trustScore: Double) { DemoRideStore.updateUserTrustScore(userId, trustScore) }
}
