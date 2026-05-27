package pt.ulisboa.tecnico.sharist.data.repository

import android.util.Log
import com.google.gson.Gson
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
        if (!network.isConnected) {
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_RIDE", payload = gson.toJson(ride)))
            return Result.success("pending_$id")
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

    fun getRideBookings(rideId: String): Flow<List<Booking>> = remote.observeRideBookings(rideId)

    fun getDriverBookings(uid: String): Flow<List<Booking>> = remote.observeDriverBookings(uid)

    fun getPassengerBookings(uid: String): Flow<List<Booking>> = remote.observePassengerBookings(uid)
        .onEach { bookings ->
            // Passive Reconciliation: Automatically trigger refund for rejected/cancelled bookings
            bookings.forEach { booking ->
                if (booking.passengerId == uid && 
                    (booking.status == BookingStatus.REJECTED || booking.status == BookingStatus.CANCELLED) &&
                    booking.passengerPaid && !booking.passengerRefunded) {
                    
                    Log.d(TAG, "Auto-reconciling refund for booking ${booking.id}")
                    // We launch this in a separate scope or just fire-and-forget
                    // Since this is a repository, we can't easily access a viewModelScope.
                    // However, we can use a global or pass a scope. 
                    // For now, let's assume we can use a simple CoroutineScope(Dispatchers.IO)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            remote.updateBookingStatus(booking.id, booking.status)
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-refund failed", e)
                        }
                    }
                }
            }
        }

    suspend fun bookRide(booking: Booking): Result<String> {
        if (!network.isConnected) {
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_BOOKING", payload = gson.toJson(booking)))
            return Result.success("pending_$id")
        }
        return runCatching { remote.createBooking(booking) }
    }

    suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        remote.updateBookingStatus(bookingId, status)
    }

    suspend fun syncPendingOperations() {
        local.pendingDao.getPending().forEach { op ->
            try {
                when (op.type) {
                    "CREATE_RIDE" -> remote.createRide(gson.fromJson(op.payload, Ride::class.java))
                    "CREATE_BOOKING" -> remote.createBooking(gson.fromJson(op.payload, Booking::class.java))
                }
                op.synced = true
                local.pendingDao.update(op)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for op ${op.localId}: ${e.message}")
            }
        }
        local.pendingDao.clearSynced()
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
            list.filter { !it.deniedBy.contains(uid) }
        }

    fun getPassengerRequests(passengerId: String): Flow<List<RideRequest>> =
        remote.observePassengerRequests(passengerId)
            .onEach { requests ->
                // Passive Reconciliation: Automatically trigger refund for cancelled requests
                requests.forEach { req ->
                    if (req.passengerId == passengerId &&
                        req.status == RequestStatus.CANCELLED &&
                        req.passengerPaid && !req.passengerRefunded) {
                        
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

    fun getDriverRequests(driverId: String): Flow<List<RideRequest>> =
        remote.observeDriverRequests(driverId)

    suspend fun createRequest(request: RideRequest): Result<String> = runCatching {
        remote.createRequest(request)
    }

    suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double): Result<Unit> = runCatching {
        remote.acceptRequest(requestId, driverId, driverName, driverRating)
    }

    suspend fun denyRequest(requestId: String, driverId: String): Result<Unit> = runCatching {
        remote.denyRequest(requestId, driverId)
    }

    suspend fun cancelRequest(requestId: String): Result<Unit> = runCatching {
        remote.cancelRequest(requestId)
    }

    suspend fun updateRequestStatus(requestId: String, status: RequestStatus): Result<Unit> = runCatching {
        remote.updateRequestStatus(requestId, status)
    }

    suspend fun submitReview(review: Review) {
        remote.submitReview(review)
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
