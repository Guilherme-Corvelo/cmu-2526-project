package pt.ulisboa.tecnico.sharist.data.repository

import android.util.Log
import com.google.gson.Gson
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.data.local.LocalDataSource
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.data.remote.RemoteDataSource
import pt.ulisboa.tecnico.sharist.utils.NetworkMonitor

private const val TAG = "Repository"
private val gson = Gson()

class RideRepository(
    private val remote: RemoteDataSource,
    private val local: LocalDataSource,
    private val network: NetworkMonitor
) {
    /**
     * Observes rides matching the filter.
     * It returns a flow that combines local database observation with background remote sync.
     */
    fun getRides(filter: RideFilter): Flow<List<Ride>> = callbackFlow {
        val localJob = launch {
            local.rideDao.observeFilteredRides(filter.origin, filter.destination)
                .map { it.map { e -> e.toDomain() }.filter { r -> r.status == RideStatus.OPEN } }
                .collect { trySend(it) }
        }

        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observeRides(filter)
                    .catch { Log.e(TAG, "Remote sync error", it) }
                    .collect { list ->
                        local.rideDao.upsert(list.map { it.toEntity() })
                        local.evictStale()
                    }
            }
        }

        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    // Alias for getRides to match some UI usages
    fun searchRides(filter: RideFilter) = getRides(filter)

    /**
     * Observes rides created by a specific driver.
     */
    fun getDriverRides(uid: String): Flow<List<Ride>> = callbackFlow {
        val localJob = launch {
            local.rideDao.observeDriverRides(uid)
                .map { it.map { e -> e.toDomain() }.filter { r -> r.status != RideStatus.CANCELLED } }
                .collect { trySend(it) }
        }

        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observeDriverRides(uid)
                    .catch { Log.e(TAG, "Remote driver sync error", it) }
                    .collect { list ->
                        local.rideDao.upsert(list.map { it.toEntity() })
                    }
            }
        }

        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    suspend fun createRide(ride: Ride): Result<String> {
        if (ride.origin.equals(ride.destination, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Origin and Destination cannot be the same"))
        }

        if (ride.periodic) {
            // Requirement 3.4 & 3.3: Recurring rides must be handled carefully.
            // For now, we allow offline creation of recurring rides just like normal ones.
            // The sync logic will handle the actual creation.
        }

        if (!network.isConnected) {
            val pendingRide = ride.copy(isPending = true, createdAt = Date())
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_RIDE", payload = gson.toJson(pendingRide)))
            val tempId = "pending_$id"
            local.rideDao.upsertOne(pendingRide.copy(id = tempId).toEntity())
            return Result.success(tempId)
        }
        return runCatching {
            val id = remote.createRide(ride)
            local.rideDao.upsertOne(ride.copy(id = id).toEntity())
            id
        }
    }

    suspend fun getRide(rideId: String): Ride? {
        val remoteRide = runCatching { remote.getRide(rideId) }.getOrNull()
        if (remoteRide != null) {
            local.rideDao.upsertOne(remoteRide.toEntity())
            return remoteRide
        }
        return local.rideDao.getById(rideId)?.toDomain()
    }

    suspend fun cancelRide(rideId: String): Result<Unit> = runCatching {
        remote.cancelRide(rideId)
        local.rideDao.getById(rideId)?.let {
            local.rideDao.upsertOne(it.copy(status = RideStatus.CANCELLED.name))
        }
    }

    suspend fun completeRide(rideId: String): Result<Unit> = runCatching {
        remote.completeRide(rideId)
        local.rideDao.getById(rideId)?.let {
            local.rideDao.upsertOne(it.copy(status = RideStatus.COMPLETED.name))
        }
    }

    suspend fun startRide(rideId: String): Result<Unit> = runCatching {
        remote.startRide(rideId)
        local.rideDao.getById(rideId)?.let {
            local.rideDao.upsertOne(it.copy(status = RideStatus.EN_ROUTE.name))
        }
    }

    fun getRideBookings(rideId: String): Flow<List<Booking>> = callbackFlow {
        val localJob = launch {
            local.bookingDao.observeRideBookings(rideId)
                .map { it.map { e -> e.toDomain() } }
                .collect { trySend(it) }
        }
        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observeRideBookings(rideId)
                    .catch { Log.e(TAG, "Remote bookings sync error", it) }
                    .collect { list ->
                        local.bookingDao.upsert(list.map { it.toEntity() })
                    }
            }
        }
        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    fun getDriverBookings(uid: String): Flow<List<Booking>> = callbackFlow {
        val localJob = launch {
            local.bookingDao.observeDriverBookings(uid)
                .map { it.map { e -> e.toDomain() } }
                .collect { trySend(it) }
        }
        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observeDriverBookings(uid)
                    .catch { Log.e(TAG, "Remote driver bookings sync error", it) }
                    .collect { list ->
                        local.bookingDao.upsert(list.map { it.toEntity() })
                    }
            }
        }
        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    fun getPassengerBookings(uid: String): Flow<List<Booking>> = callbackFlow {
        val localJob = launch {
            local.bookingDao.observePassengerBookings(uid)
                .map { it.map { e -> e.toDomain() } }
                .collect { trySend(it) }
        }
        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observePassengerBookings(uid)
                    .onEach { bookings ->
                        // Passive Reconciliation
                        bookings.forEach { booking ->
                            if (booking.passengerId == uid && 
                                (booking.status == BookingStatus.REJECTED || booking.status == BookingStatus.CANCELLED) &&
                                booking.passengerPaid && !booking.passengerRefunded) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    updateBookingStatus(booking.id, booking.status)
                                }
                            }
                        }
                    }
                    .catch { Log.e(TAG, "Remote passenger bookings sync error", it) }
                    .collect { list ->
                        local.bookingDao.upsert(list.map { it.toEntity() })
                    }
            }
        }
        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    suspend fun bookRide(booking: Booking): Result<String> {
        if (!network.isConnected) {
            val pendingBooking = booking.copy(isPending = true, createdAt = Date())
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_BOOKING", payload = gson.toJson(pendingBooking)))
            val tempId = "pending_$id"
            local.bookingDao.upsertOne(pendingBooking.copy(id = tempId).toEntity())
            return Result.success(tempId)
        }
        return runCatching { 
            val id = remote.createBooking(booking)
            local.bookingDao.upsertOne(booking.copy(id = id).toEntity())
            id
        }
    }

    suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) = runCatching {
        remote.updateBookingStatus(bookingId, status)
        local.bookingDao.getById(bookingId)?.let {
            local.bookingDao.upsertOne(it.copy(status = status.name))
        }
    }

    suspend fun syncPendingOperations(): List<Pair<String, Boolean>> {
        val pending = local.pendingDao.getPending()
        if (pending.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Boolean>>()
        pending.forEach { op ->
            try {
                when (op.type) {
                    "CREATE_RIDE" -> {
                        val ride = gson.fromJson(op.payload, Ride::class.java)
                        val newId = remote.createRide(ride)
                        local.rideDao.getById("pending_${op.localId}")?.let {
                            local.rideDao.deleteById("pending_${op.localId}")
                            local.rideDao.upsertOne(it.copy(id = newId, isPending = false))
                        }
                    }
                    "CREATE_BOOKING" -> {
                        val booking = gson.fromJson(op.payload, Booking::class.java)
                        val newId = remote.createBooking(booking)
                        local.bookingDao.getById("pending_${op.localId}")?.let {
                            local.bookingDao.deleteById("pending_${op.localId}")
                            local.bookingDao.upsertOne(it.copy(id = newId, isPending = false))
                        }
                    }
                    "CREATE_REQUEST" -> {
                        val request = gson.fromJson(op.payload, RideRequest::class.java)
                        val newId = remote.createRequest(request)
                        local.requestDao.getById("pending_${op.localId}")?.let {
                            // Instead of just updating, we delete the pending one and insert the new one
                            // to avoid ID conflicts if the UI is still observing the old ID.
                            // However, local.requestDao.upsertOne uses @Upsert which replaces if @PrimaryKey matches.
                            // Since the ID changed from "pending_X" to "newId", we should delete the old entry.
                            local.requestDao.deleteById("pending_${op.localId}")
                            local.requestDao.upsertOne(it.copy(id = newId, isPending = false))
                        }
                    }
                }
                op.synced = true
                local.pendingDao.update(op)
                results.add(op.type to true)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for op ${op.localId}: ${e.message}")
                op.errorMessage = e.message
                local.pendingDao.update(op)
                results.add(op.type to false)
            }
        }
        local.pendingDao.clearSynced()
        return results
    }

    suspend fun preloadOnWifi() {
        if (!network.isWifi) return
        remote.observeRides(RideFilter()).take(1)
            .catch { Log.e(TAG, "Preload error", it) }
            .collect { list -> local.rideDao.upsert(list.map { it.toEntity() }) }
    }
}

class RideRequestRepository(
    private val remote: RemoteDataSource,
    private val local: LocalDataSource,
    private val network: NetworkMonitor
) {
    fun getOpenRequests(): Flow<List<RideRequest>> = remote.observeOpenRequests()
        .map { list -> 
            val uid = remote.currentUid
            list.filter { !it.deniedBy.contains(uid) && !it.deniedDrivers.contains(uid) }
        }

    fun getPassengerRequests(passengerId: String): Flow<List<RideRequest>> = callbackFlow {
        val localJob = launch {
            local.requestDao.observePassengerRequests(passengerId)
                .map { it.map { e -> e.toDomain() } }
                .collect { trySend(it) }
        }
        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observePassengerRequests(passengerId)
                    .onEach { requests ->
                        // Passive Reconciliation: Automatically trigger refund for cancelled requests
                        requests.forEach { req ->
                            if (req.passengerId == passengerId &&
                                req.status == RequestStatus.CANCELLED &&
                                req.passengerPaid && !req.passengerRefunded
                            ) {
                                Log.d("RideRequestRepository", "Auto-reconciling refund for request ${req.id}")
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        remote.updateRequestStatus(req.id, req.status)
                                    } catch (e: Exception) {
                                        Log.e("RideRequestRepository", "Auto-refund failed", e)
                                    }
                                }
                            }
                        }
                    }
                    .catch { Log.e("RideRequestRepository", "Remote passenger requests error", it) }
                    .collect { list ->
                        local.requestDao.upsert(list.map { it.toEntity() })
                    }
            }
        }
        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    fun getDriverRequests(driverId: String): Flow<List<RideRequest>> = callbackFlow {
        val localJob = launch {
            local.requestDao.observeDriverRequests(driverId)
                .map { it.map { e -> e.toDomain() } }
                .collect { trySend(it) }
        }
        var remoteJob: kotlinx.coroutines.Job? = null
        if (network.isConnected) {
            remoteJob = launch(Dispatchers.IO) {
                remote.observeDriverRequests(driverId)
                    .catch { Log.e("RideRequestRepository", "Remote driver requests error", it) }
                    .collect { list ->
                        local.requestDao.upsert(list.map { it.toEntity() })
                    }
            }
        }
        awaitClose {
            localJob.cancel()
            remoteJob?.cancel()
        }
    }

    suspend fun createRequest(request: RideRequest): Result<String> {
        if (request.origin.equals(request.destination, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Origin and Destination cannot be the same"))
        }
        if (!network.isConnected) {
            val pendingRequest = request.copy(isPending = true, createdAt = Date())
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_REQUEST", payload = gson.toJson(pendingRequest)))
            val tempId = "pending_$id"
            local.requestDao.upsertOne(pendingRequest.copy(id = tempId).toEntity())
            return Result.success(tempId)
        }
        return runCatching {
            val id = remote.createRequest(request)
            local.requestDao.upsertOne(request.copy(id = id).toEntity())
            id
        }
    }

    suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double): Result<Unit> = runCatching {
        remote.acceptRequest(requestId, driverId, driverName, driverRating)
    }

    suspend fun denyRequest(requestId: String, driverId: String): Result<Unit> = runCatching {
        remote.denyRequest(requestId, driverId)
    }

    suspend fun rejectDriver(requestId: String, driverId: String): Result<Unit> = runCatching {
        remote.rejectDriver(requestId, driverId)
    }

    suspend fun cancelRequest(requestId: String): Result<Unit> = runCatching {
        remote.cancelRequest(requestId)
    }

    suspend fun updateRequestStatus(requestId: String, status: RequestStatus): Result<Unit> = runCatching {
        remote.updateRequestStatus(requestId, status)
        local.requestDao.getById(requestId)?.let {
            local.requestDao.upsertOne(it.copy(status = status.name))
        }
    }

    suspend fun submitReview(review: Review) {
        remote.submitReview(review)
        local.bookingDao.getById(review.requestId)?.let {
            val uid = remote.currentUid
            val updated = if (uid == it.driverId) it.copy(passengerReviewed = true)
                          else it.copy(driverReviewed = true)
            local.bookingDao.upsertOne(updated)
        }
        local.requestDao.getById(review.requestId)?.let {
            val uid = remote.currentUid
            val updated = if (uid == it.driverId) it.copy(passengerReviewed = true)
                          else it.copy(driverReviewed = true)
            local.requestDao.upsertOne(updated)
        }
    }

    fun getReviewsForUser(userId: String): Flow<List<Review>> = remote.observeReviewsForUser(userId)
}

class UserRepository(private val remote: RemoteDataSource) {
    val currentUid get() = remote.currentUid
    suspend fun signIn(email: String, password: String) = remote.signIn(email, password)
    suspend fun register(email: String, password: String) = remote.register(email, password)
    fun signOut() = remote.signOut()
    suspend fun createProfile(user: User) = remote.createUserProfile(user)
    suspend fun getUser(uid: String) = remote.getUser(uid)
    suspend fun updateBalance(uid: String, delta: Double) = remote.updateBalance(uid, delta)
}
