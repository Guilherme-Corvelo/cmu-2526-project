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
) : RemoteDataSource {
    private val requestsCol = db.collection("ride_requests")
    private val usersCol = db.collection("users")
    private val reviewsCol = db.collection("reviews")
    private val ridesCol = db.collection("rides")
    private val bookingsCol = db.collection("bookings")

    override val currentUid: String? get() = auth.currentUser?.uid

    override suspend fun signIn(email: String, password: String): com.google.firebase.auth.AuthResult? = auth.signInWithEmailAndPassword(email, password).await()
    override suspend fun register(email: String, password: String): com.google.firebase.auth.AuthResult? = auth.createUserWithEmailAndPassword(email, password).await()
    override fun signOut() = auth.signOut()
    override suspend fun createUserProfile(user: User) { usersCol.document(user.uid).set(user).await() }
    override suspend fun getUser(uid: String): User? = usersCol.document(uid).get().await().toObject(User::class.java)

    override suspend fun updateBalance(uid: String, delta: Double) {
        db.runTransaction { tx ->
            val ref = usersCol.document(uid)
            val current = tx.get(ref).toObject(User::class.java)?.balance ?: 0.0
            tx.update(ref, "balance", current + delta)
        }.await()
    }

    override fun observeRides(filter: RideFilter): Flow<List<Ride>> = callbackFlow {
        // Basic implementation of filtering in Firestore
        var query: Query = ridesCol.whereEqualTo("status", RideStatus.OPEN.name)
        
        val listener = query.addSnapshotListener { snap, err ->
            if (err != null) close(err)
            else {
                val allRides = snap?.toObjects(Ride::class.java)?.filterNotNull() ?: emptyList()
                // Apply further filters that are harder to do in a single Firestore query
                val filtered = allRides.filter { ride ->
                    (filter.origin.isBlank() || ride.origin.contains(filter.origin, ignoreCase = true)) &&
                    (filter.destination.isBlank() || ride.destination.contains(filter.destination, ignoreCase = true)) &&
                    ride.seatsAvailable >= filter.minSeats &&
                    (filter.maxPrice == null || ride.pricePerSeat <= filter.maxPrice)
                }
                trySend(filtered)
            }
        }
        awaitClose { listener.remove() }
    }

    override suspend fun getRide(rideId: String): Ride? = ridesCol.document(rideId).get().await().toObject(Ride::class.java)

    override fun observeDriverRides(driverId: String): Flow<List<Ride>> = callbackFlow {
        val listener = ridesCol.whereEqualTo("driverId", driverId)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Ride::class.java)?.filterNotNull() ?: emptyList()) }
        awaitClose { listener.remove() }
    }

    override suspend fun createRide(ride: Ride): String {
        val ref = ridesCol.document()
        ref.set(ride.copy(id = ref.id)).await()
        return ref.id
    }

    override suspend fun decrementSeat(rideId: String) {
        db.runTransaction { tx ->
            val ref = ridesCol.document(rideId)
            val ride = tx.get(ref).toObject(Ride::class.java) ?: return@runTransaction
            if (ride.seatsAvailable > 0) {
                val newSeats = ride.seatsAvailable - 1
                tx.update(ref, "seatsAvailable", newSeats)
                if (newSeats == 0) tx.update(ref, "status", RideStatus.FULL.name)
            }
        }.await()
    }

    override suspend fun createBooking(booking: Booking): String {
        val ref = bookingsCol.document()
        ref.set(booking.copy(id = ref.id)).await()
        return ref.id
    }

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        db.runTransaction { tx ->
            val bookingRef = bookingsCol.document(bookingId)
            val booking = tx.get(bookingRef).toObject(Booking::class.java)
                ?: error("Booking not found")

            if (booking.status != BookingStatus.PENDING) return@runTransaction

            if (status == BookingStatus.ACCEPTED) {
                val rideRef = ridesCol.document(booking.rideId)
                val ride = tx.get(rideRef).toObject(Ride::class.java)
                    ?: error("Ride not found")

                if (ride.seatsAvailable < booking.seatsRequested) {
                    tx.update(bookingRef, "status", BookingStatus.REJECTED.name)
                    return@runTransaction
                }

                val newSeats = ride.seatsAvailable - booking.seatsRequested
                tx.update(rideRef, "seatsAvailable", newSeats)
                tx.update(rideRef, "status", if (newSeats == 0) RideStatus.FULL.name else RideStatus.OPEN.name)
            }

            tx.update(bookingRef, "status", status.name)
        }.await()
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> = callbackFlow {
        val listener = bookingsCol.whereEqualTo("passengerId", passengerId)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Booking::class.java)?.filterNotNull() ?: emptyList()) }
        awaitClose { listener.remove() }
    }

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> = callbackFlow {
        val listener = bookingsCol.whereEqualTo("rideId", rideId)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Booking::class.java)?.filterNotNull() ?: emptyList()) }
        awaitClose { listener.remove() }
    }

    override fun observeOpenRequests(): Flow<List<RideRequest>> = callbackFlow {
        val listener = requestsCol.whereEqualTo("status", RequestStatus.OPEN.name)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) close(err)
                else {
                    val requests = snap?.toObjects(RideRequest::class.java)?.filterNotNull() ?: emptyList()
                    trySend(requests.sortedBy { it.requestedTime?.time ?: Long.MAX_VALUE })
                }
            }
        awaitClose { listener.remove() }
    }

    override fun observePassengerRequests(passengerId: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = requestsCol.whereEqualTo("passengerId", passengerId)
            .addSnapshotListener { snap, err ->
                if (err != null) close(err)
                else {
                    val requests = snap?.toObjects(RideRequest::class.java)?.filterNotNull() ?: emptyList()
                    trySend(requests.sortedByDescending { it.createdAt?.time ?: 0L })
                }
            }
        awaitClose { listener.remove() }
    }

    override fun observeDriverRequests(driverId: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = requestsCol.whereEqualTo("driverId", driverId)
            .addSnapshotListener { snap, err ->
                if (err != null) close(err)
                else {
                    val requests = snap?.toObjects(RideRequest::class.java)?.filterNotNull() ?: emptyList()
                    trySend(requests.sortedByDescending { it.createdAt?.time ?: 0L })
                }
            }
        awaitClose { listener.remove() }
    }

    override suspend fun createRequest(request: RideRequest): String {
        val ref = requestsCol.document()
        // We set the ID explicitly so the object inside the document also has it
        val toSave = request.copy(
            id = ref.id,
            createdAt = null // Let Firestore set this via @ServerTimestamp
        )
        ref.set(toSave).await()
        return ref.id
    }
    override suspend fun cancelRequest(requestId: String) { requestsCol.document(requestId).update("status", RequestStatus.CANCELLED.name).await() }
    override suspend fun completeRequest(requestId: String) { requestsCol.document(requestId).update("status", RequestStatus.COMPLETED.name).await() }
    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus) {
        requestsCol.document(requestId).update("status", status.name).await()
    }

    override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")
            if (req.status != RequestStatus.OPEN) error("Request already taken")
            tx.update(ref, mapOf("status" to RequestStatus.ACCEPTED.name, "driverId" to driverId, "driverName" to driverName, "driverRating" to driverRating))
        }.await()
    }

    override suspend fun submitReview(review: Review) {
        db.runTransaction { tx ->
            val reviewRef = reviewsCol.document()
            val requestRef = requestsCol.document(review.requestId)

            // We no longer update the driver's user document directly because passengers 
            // don't have permission to write to other users' profiles (Security Rules).
            // The rating will be calculated client-side in the ProfileFragment.

            tx.set(reviewRef, review.copy(id = reviewRef.id))
            tx.update(requestRef, "reviewed", true)
        }.await()
    }

    override fun observeReviewsForUser(userId: String): Flow<List<Review>> = callbackFlow {
        val listener = reviewsCol.whereEqualTo("driverId", userId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                } else {
                    val reviews = snap?.toObjects(Review::class.java)?.filterNotNull() ?: emptyList()
                    // Sort client-side to avoid needing a composite index
                    trySend(reviews.sortedByDescending { it.createdAt?.time ?: 0L })
                }
            }
        awaitClose { listener.remove() }
    }
}
