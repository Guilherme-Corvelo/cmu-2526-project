package pt.ulisboa.tecnico.sharist.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
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

    private val activeListeners = mutableListOf<ListenerRegistration>()

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
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
    }

    override suspend fun getRide(rideId: String): Ride? = ridesCol.document(rideId).get().await().toObject(Ride::class.java)

    override fun observeDriverRides(driverId: String): Flow<List<Ride>> = callbackFlow {
        val listener = ridesCol.whereEqualTo("driverId", driverId)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Ride::class.java)?.filterNotNull() ?: emptyList()) }
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
    }

    override suspend fun createRide(ride: Ride): String {
        val ref = ridesCol.document()
        val toSave = ride.copy(
            id = ref.id,
            createdAt = null // Let Firestore set this
        )
        ref.set(toSave).await()
        return ref.id
    }

    override suspend fun cancelRide(rideId: String) {
        db.runTransaction { tx ->
            val rideRef = ridesCol.document(rideId)
            tx.update(rideRef, "status", RideStatus.CANCELLED.name)
        }.await()
        
        // Update bookings associated with this ride
        val uid = currentUid ?: return
        val bookings = bookingsCol.whereEqualTo("rideId", rideId).whereEqualTo("driverId", uid).get().await()
        db.runBatch { batch ->
            for (doc in bookings.documents) {
                val status = doc.getString("status")
                if (status == BookingStatus.PENDING.name || status == BookingStatus.ACCEPTED.name || 
                    status == BookingStatus.EN_ROUTE.name || status == BookingStatus.PICKED_UP.name) {
                    batch.update(doc.reference, "status", BookingStatus.CANCELLED.name)
                }
            }
        }.await()
    }

    override suspend fun completeRide(rideId: String) {
        db.runTransaction { tx ->
            val rideRef = ridesCol.document(rideId)
            tx.update(rideRef, "status", RideStatus.COMPLETED.name)
        }.await()
        
        // Note: For bookings, we rely on the individual status updates 
        // that happen through the "My Activities" screen or mass finish logic in the UI
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

            // State machine for Bookings
            val current = booking.status
            val valid = when (status) {
                BookingStatus.ACCEPTED, BookingStatus.REJECTED -> current == BookingStatus.PENDING
                BookingStatus.EN_ROUTE -> current == BookingStatus.ACCEPTED
                BookingStatus.PICKED_UP -> current == BookingStatus.EN_ROUTE
                BookingStatus.COMPLETED -> current == BookingStatus.PICKED_UP || current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE
                BookingStatus.CANCELLED -> current != BookingStatus.COMPLETED && current != BookingStatus.REJECTED
                else -> false
            }
            if (!valid) return@runTransaction

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

            if (status == BookingStatus.COMPLETED) {
                val amount = booking.totalPrice
                val uid = currentUid
                
                // Only update the balance of the user performing the action to avoid PERMISSION_DENIED
                // Ideally this should be handled by a Cloud Function.
                if (uid == booking.driverId) {
                    val dRef = usersCol.document(booking.driverId)
                    val dBal = tx.get(dRef).getDouble("balance") ?: 0.0
                    tx.update(dRef, "balance", dBal + amount)
                } else if (uid == booking.passengerId) {
                    val pRef = usersCol.document(booking.passengerId)
                    val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
                    tx.update(pRef, "balance", pBal - amount)
                }
            } else if (status == BookingStatus.CANCELLED && (current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE || current == BookingStatus.PICKED_UP)) {
                // Return seats if the booking was already subtracting them from the ride
                val rideRef = ridesCol.document(booking.rideId)
                val rideSnap = tx.get(rideRef)
                if (rideSnap.exists()) {
                    val available = rideSnap.getLong("seatsAvailable") ?: 0L
                    val newSeats = available + booking.seatsRequested
                    tx.update(rideRef, "seatsAvailable", newSeats)
                    tx.update(rideRef, "status", RideStatus.OPEN.name)
                }
            }

            tx.update(bookingRef, "status", status.name)
        }.await()
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> = callbackFlow {
        val listener = bookingsCol.whereEqualTo("passengerId", passengerId)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Booking::class.java)?.filterNotNull() ?: emptyList()) }
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
    }

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> = callbackFlow {
        val uid = currentUid ?: return@callbackFlow
        val listener = bookingsCol
            .whereEqualTo("rideId", rideId)
            .whereEqualTo("driverId", uid)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Booking::class.java)?.filterNotNull() ?: emptyList()) }
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
    }

    override fun observeDriverBookings(driverId: String): Flow<List<Booking>> = callbackFlow {
        val listener = bookingsCol.whereEqualTo("driverId", driverId)
            .addSnapshotListener { snap, err -> if (err != null) close(err) else trySend(snap?.toObjects(Booking::class.java)?.filterNotNull() ?: emptyList()) }
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
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
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
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
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
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
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
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
    override suspend fun cancelRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.CANCELLED)
    }

    override suspend fun completeRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.COMPLETED)
    }

    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")

            val current = req.status
            if (current == RequestStatus.COMPLETED || current == RequestStatus.CANCELLED) return@runTransaction

            if (status == RequestStatus.COMPLETED) {
                val driverId = req.driverId ?: return@runTransaction // Safety
                val amount = req.estimatedPrice
                val uid = currentUid

                if (uid == driverId) {
                    val dRef = usersCol.document(driverId)
                    val dBal = tx.get(dRef).getDouble("balance") ?: 0.0
                    tx.update(dRef, "balance", dBal + amount)
                } else if (uid == req.passengerId) {
                    val pRef = usersCol.document(req.passengerId)
                    val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
                    tx.update(pRef, "balance", pBal - amount)
                }
            }

            tx.update(ref, "status", status.name)
        }.await()
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
        if (review.requestId.isBlank()) return
        val uid = currentUid ?: return

        db.runTransaction { tx ->
            val reviewRef = reviewsCol.document()
            tx.set(reviewRef, review.copy(id = reviewRef.id))
        }.await()

        // Update the correct flag (driverReviewed or passengerReviewed) on the parent document
        try {
            val reqRef = requestsCol.document(review.requestId)
            val reqSnap = reqRef.get().await()
            if (reqSnap.exists()) {
                val driverId = reqSnap.getString("driverId")
                // If reviewer is driver, set passengerReviewed = true. Else set driverReviewed = true.
                val field = if (uid == driverId) "passengerReviewed" else "driverReviewed"
                reqRef.update(field, true).await()
                return
            }
        } catch (e: Exception) {
            android.util.Log.d("FirebaseDataSource", "Request update failed: ${e.message}")
        }
        
        try {
            val bookRef = bookingsCol.document(review.requestId)
            val bookSnap = bookRef.get().await()
            if (bookSnap.exists()) {
                val driverId = bookSnap.getString("driverId")
                // If reviewer is driver, set passengerReviewed = true. Else set driverReviewed = true.
                val field = if (uid == driverId) "passengerReviewed" else "driverReviewed"
                bookRef.update(field, true).await()
            }
        } catch (e: Exception) {
            android.util.Log.d("FirebaseDataSource", "Booking update failed: ${e.message}")
        }
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
        activeListeners.add(listener)
        awaitClose { 
            listener.remove()
            activeListeners.remove(listener)
        }
    }

    override fun clearListeners() {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()
    }
}
