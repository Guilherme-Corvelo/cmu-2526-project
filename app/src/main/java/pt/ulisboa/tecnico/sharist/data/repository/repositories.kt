package pt.ulisboa.tecnico.sharist.data.repository

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun getRides(filter: RideFilter): Flow<List<Ride>> {
        if (network.isConnected) syncRidesToCache(filter)
        return local.rideDao.observeOpenRides().map { it.map { e -> e.toDomain() } }
    }

    // Alias for getRides to match some UI usages
    fun searchRides(filter: RideFilter) = getRides(filter)

    fun getDriverRides(uid: String): Flow<List<Ride>> {
        if (network.isConnected) syncDriverRidesToCache(uid)
        return local.rideDao.observeDriverRides(uid).map { it.map { e -> e.toDomain() } }
    }

    private fun syncRidesToCache(filter: RideFilter) {
        CoroutineScope(Dispatchers.IO).launch {
            remote.observeRides(filter)
                .catch { Log.e(TAG, "Remote sync error", it) }
                .collect { list -> 
                    local.rideDao.upsert(list.map { it.toEntity() })
                    local.evictStale()
                }
        }
    }

    private fun syncDriverRidesToCache(uid: String) {
        CoroutineScope(Dispatchers.IO).launch {
            remote.observeDriverRides(uid)
                .catch { Log.e(TAG, "Remote sync error", it) }
                .collect { list -> 
                    local.rideDao.upsert(list.map { it.toEntity() })
                }
        }
    }

    suspend fun createRide(ride: Ride): Result<String> {
        if (!network.isConnected) {
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_RIDE", payload = gson.toJson(ride)))
            return Result.success("pending_$id")
        }
        return runCatching { remote.createRide(ride) }
    }

    suspend fun getRide(rideId: String): Ride? = remote.getRide(rideId)

    fun getRideBookings(rideId: String): Flow<List<Booking>> = remote.observeRideBookings(rideId)

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

    fun getPassengerRequests(passengerId: String): Flow<List<RideRequest>> =
        remote.observePassengerRequests(passengerId)

    suspend fun createRequest(request: RideRequest): Result<String> = runCatching {
        remote.createRequest(request)
    }

    suspend fun cancelRequest(requestId: String) {
        remote.cancelRequest(requestId)
    }

    suspend fun submitReview(review: Review) {
        remote.submitReview(review)
    }
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
