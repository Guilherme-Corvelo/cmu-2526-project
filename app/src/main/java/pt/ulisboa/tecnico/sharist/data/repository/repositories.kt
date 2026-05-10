package pt.ulisboa.tecnico.sharist.data.repository

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.data.local.LocalDataSource
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.data.remote.FirebaseDataSource
import pt.ulisboa.tecnico.sharist.utils.NetworkMonitor

private const val TAG = "Repository"
private val gson = Gson()

class RideRequestRepository(
    private val remote: FirebaseDataSource,
    private val local: LocalDataSource,
    private val network: NetworkMonitor
) {
    fun getOpenRequests(): Flow<List<RideRequest>> {
        if (network.isConnected) syncToCache { remote.observeOpenRequests() }
        return local.requestDao.observeOpenRequests().map { it.map { e -> e.toDomain() } }
    }

    fun getPassengerRequests(uid: String): Flow<List<RideRequest>> {
        if (network.isConnected) syncToCache { remote.observePassengerRequests(uid) }
        return local.requestDao.observePassengerRequests(uid).map { it.map { e -> e.toDomain() } }
    }

    fun getDriverRequests(uid: String): Flow<List<RideRequest>> {
        if (network.isConnected) syncToCache { remote.observeDriverRequests(uid) }
        return local.requestDao.observeDriverRequests(uid).map { it.map { e -> e.toDomain() } }
    }

    private fun syncToCache(source: () -> Flow<List<RideRequest>>) {
        CoroutineScope(Dispatchers.IO).launch {
            source().catch { Log.e(TAG, "Remote sync error", it) }
                .collect { list -> local.requestDao.upsert(list.map { it.toEntity() }); local.evictStale() }
        }
    }

    suspend fun createRequest(request: RideRequest): Result<String> {
        if (!network.isConnected) {
            val id = local.pendingDao.insert(PendingOperation(type = "CREATE_REQUEST", payload = gson.toJson(request)))
            return Result.success("pending_$id")
        }
        return runCatching { remote.createRequest(request) }
    }

    suspend fun cancelRequest(requestId: String): Result<Unit> = runCatching { remote.cancelRequest(requestId) }

    suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double): Result<Unit> {
        if (!network.isConnected) return Result.failure(Exception("No connection — try again when online"))
        return runCatching { remote.acceptRequest(requestId, driverId, driverName, driverRating) }
    }

    suspend fun completeRequest(requestId: String): Result<Unit> = runCatching { remote.completeRequest(requestId) }
    suspend fun submitReview(review: Review): Result<Unit> = runCatching { remote.submitReview(review) }

    suspend fun syncPending() {
        local.pendingDao.getPending().forEach { op ->
            try {
                when (op.type) {
                    "CREATE_REQUEST" -> remote.createRequest(gson.fromJson(op.payload, RideRequest::class.java))
                    "CANCEL_REQUEST" -> remote.cancelRequest(op.payload)
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
        remote.observeOpenRequests().take(1).catch { Log.e(TAG, "Preload error", it) }
            .collect { list -> local.requestDao.upsert(list.map { it.toEntity() }) }
    }
}

class UserRepository(private val remote: FirebaseDataSource) {
    val currentUid get() = remote.currentUid
    suspend fun signIn(email: String, password: String) = remote.signIn(email, password)
    suspend fun register(email: String, password: String) = remote.register(email, password)
    fun signOut() = remote.signOut()
    suspend fun createProfile(user: User) = remote.createUserProfile(user)
    suspend fun getUser(uid: String) = remote.getUser(uid)
    suspend fun updateBalance(uid: String, delta: Double) = remote.updateBalance(uid, delta)
}
