package pt.ulisboa.tecnico.sharist.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import pt.ulisboa.tecnico.sharist.data.model.*

class FirebaseDataSource(
    val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val requestsCol = db.collection("ride_requests")
    private val usersCol = db.collection("users")
    private val reviewsCol = db.collection("reviews")
    val currentUid: String? get() = auth.currentUser?.uid

    suspend fun signIn(email: String, password: String) = auth.signInWithEmailAndPassword(email, password).await()
    suspend fun register(email: String, password: String) = auth.createUserWithEmailAndPassword(email, password).await()
    fun signOut() = auth.signOut()
    suspend fun createUserProfile(user: User) = usersCol.document(user.uid).set(user).await()
    suspend fun getUser(uid: String): User? = usersCol.document(uid).get().await().toObject(User::class.java)

    suspend fun updateBalance(uid: String, delta: Double) {
        db.runTransaction { tx ->
            val ref = usersCol.document(uid)
            val current = tx.get(ref).toObject(User::class.java)?.balance ?: 0.0
            tx.update(ref, "balance", current + delta)
        }.await()
    }

    fun observeOpenRequests(): Flow<List<RideRequest>> = callbackFlow {
        val listener = requestsCol.whereEqualTo("status", RequestStatus.OPEN.name)
            .orderBy("requestedTime", Query.Direction.ASCENDING).limit(50)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(RideRequest::class.java) ?: emptyList()) }
        awaitClose { listener.remove() }
    }

    fun observePassengerRequests(passengerId: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = requestsCol.whereEqualTo("passengerId", passengerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(RideRequest::class.java) ?: emptyList()) }
        awaitClose { listener.remove() }
    }

    fun observeDriverRequests(driverId: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = requestsCol.whereEqualTo("driverId", driverId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(RideRequest::class.java) ?: emptyList()) }
        awaitClose { listener.remove() }
    }

    suspend fun createRequest(request: RideRequest): String { val ref = requestsCol.document(); ref.set(request).await(); return ref.id }
    suspend fun cancelRequest(requestId: String) { requestsCol.document(requestId).update("status", RequestStatus.CANCELLED.name).await() }
    suspend fun completeRequest(requestId: String) { requestsCol.document(requestId).update("status", RequestStatus.COMPLETED.name).await() }

    suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")
            if (req.status != RequestStatus.OPEN) error("Request already taken")
            tx.update(ref, mapOf("status" to RequestStatus.ACCEPTED.name, "driverId" to driverId, "driverName" to driverName, "driverRating" to driverRating))
        }.await()
    }

    suspend fun submitReview(review: Review) {
        val ref = reviewsCol.document(); ref.set(review).await()
        requestsCol.document(review.requestId).update("reviewed", true).await()
    }
}
