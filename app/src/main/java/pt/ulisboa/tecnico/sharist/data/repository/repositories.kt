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

private const val TAG = "RideRepository"
private val gson = Gson()

class RideRepository(
    private val remote: RemoteDataSource,
    private val local: LocalDataSource,
    private val network: NetworkMonitor
) {

    // ── Rides: offline-first ──────────────────────────────────────────────────

    fun searchRides(filter: RideFilter): Flow<List<Ride>> {
        if (network.isConnected) {
            CoroutineScope(Dispatchers.IO).launch {
                remote.observeRides(filter)
                    .catch { Log.e(TAG, "Remote error", it) }
                    .collect { rides ->
                        local.rideDao.upsertRides(rides.map { it.toEntity() })
                        local.evictStaleCache()
                    }
            }
        }

        return local.rideDao.searchRides(
            origin   = filter.origin,
            dest     = filter.destination,
            minSeats = filter.minSeats,
            maxPrice = filter.maxPrice ?: -1.0
        ).map { entities -> entities.map { it.toRide() } }
    }

    suspend fun getRide(rideId: String): Ride? {
        local.rideDao.getRideById(rideId)?.toRide()?.let { return it }
        return remote.getRide(rideId)?.also { ride ->
            local.rideDao.upsertRide(ride.toEntity())
        }
    }

    fun getDriverRides(driverId: String): Flow<List<Ride>> {
        if (network.isConnected) {
            CoroutineScope(Dispatchers.IO).launch {
                remote.observeDriverRides(driverId)
                    .catch { Log.e(TAG, "Driver rides error", it) }
                    .collect { rides ->
                        local.rideDao.upsertRides(rides.map { it.toEntity() })
                    }
            }
        }
        return local.rideDao.getRidesByDriver(driverId)
            .map { it.map { e -> e.toRide() } }
    }

    suspend fun createRide(ride: Ride): Result<String> {
        if (!network.isConnected) {
            val op = PendingOperation(
                type    = "CREATE_RIDE",
                payload = gson.toJson(ride)
            )
            local.pendingDao.insert(op)
            return Result.success("pending_${op.localId}")
        }
        return runCatching { remote.createRide(ride) }
    }

    suspend fun bookRide(booking: Booking): Result<String> {
        if (!network.isConnected) {
            val op = PendingOperation(
                type    = "BOOK_RIDE",
                payload = gson.toJson(booking)
            )
            local.pendingDao.insert(op)
            return Result.success("pending_${op.localId}")
        }
        return runCatching {
            remote.decrementSeat(booking.rideId)
            remote.createBooking(booking)
        }
    }

    suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) =
        remote.updateBookingStatus(bookingId, status)

    fun getPassengerBookings(passengerId: String) =
        remote.observePassengerBookings(passengerId)

    fun getRideBookings(rideId: String) =
        remote.observeRideBookings(rideId)

    suspend fun syncPendingOperations() {
        val pending = local.pendingDao.getPending()
        Log.d(TAG, "Syncing ${pending.size} pending operation(s)")

        pending.forEach { op ->
            try {
                when (op.type) {
                    "CREATE_RIDE" -> {
                        val ride = gson.fromJson(op.payload, Ride::class.java)
                        remote.createRide(ride)
                    }
                    "BOOK_RIDE" -> {
                        val booking = gson.fromJson(op.payload, Booking::class.java)
                        remote.decrementSeat(booking.rideId)
                        remote.createBooking(booking)
                    }
                    "CANCEL_BOOKING" -> {
                        val booking = gson.fromJson(op.payload, Booking::class.java)
                        remote.updateBookingStatus(booking.id, BookingStatus.CANCELLED)
                    }
                }
                op.synced = true
                local.pendingDao.update(op)
                Log.d(TAG, "Synced op ${op.localId} (${op.type})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync op ${op.localId}: ${e.message}")
            }
        }
        local.pendingDao.clearSynced()
    }

    suspend fun preloadOnWifi() {
        if (!network.isWifi) return
        Log.d(TAG, "Wi-Fi detected – pre-loading rides")
        remote.observeRides(RideFilter())
            .take(1)
            .catch { Log.e(TAG, "Pre-load error", it) }
            .collect { rides ->
                local.rideDao.upsertRides(rides.map { it.toEntity() })
                local.evictStaleCache()
            }
    }
}

class UserRepository(
    private val remote: RemoteDataSource
) {
    val currentUid get() = remote.currentUid

    suspend fun signIn(email: String, password: String) = remote.signIn(email, password)
    suspend fun register(email: String, password: String) = remote.register(email, password)
    fun signOut() = remote.signOut()

    suspend fun createProfile(user: User) = remote.createUserProfile(user)
    suspend fun getUser(uid: String) = remote.getUser(uid)
    suspend fun updateBalance(uid: String, delta: Double) = remote.updateBalance(uid, delta)

    suspend fun submitReview(review: Review) = remote.submitReview(review)
}
