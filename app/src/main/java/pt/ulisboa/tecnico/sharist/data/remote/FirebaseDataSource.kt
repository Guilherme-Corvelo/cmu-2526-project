package pt.ulisboa.tecnico.sharist.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.*
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
        // We observe all OPEN and FULL rides because a FULL periodic ride 
        // will transition to a new OPEN one once completed.
        var query: Query = ridesCol.whereIn("status", listOf(RideStatus.OPEN.name, RideStatus.FULL.name))
        
        val listener = query.addSnapshotListener { snap, err ->
            if (err != null) close(err)
            else {
                val allRides = snap?.toObjects(Ride::class.java)?.filterNotNull() ?: emptyList()
                // Apply further filters that are harder to do in a single Firestore query
                val now = Date()
                val filtered = allRides.filter { ride ->
                    val isFuture = ride.departureTime?.after(now) ?: false
                    isFuture &&
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
        val uid = currentUid ?: return
        val bookingsSnapshot = bookingsCol.whereEqualTo("rideId", rideId).whereEqualTo("driverId", uid).get().await()

        db.runTransaction { tx ->
            // 1. ALL READS
            val rideRef = ridesCol.document(rideId)
            val rideSnap = tx.get(rideRef)
            if (!rideSnap.exists() || rideSnap.getString("driverId") != uid) return@runTransaction

            val bookingsToCancel = mutableListOf<Pair<DocumentReference, Booking>>()
            val passengerBalances = mutableMapOf<String, Double>()

            for (doc in bookingsSnapshot.documents) {
                val bSnap = tx.get(doc.reference)
                val booking = bSnap.toObject(Booking::class.java) ?: continue
                
                val status = booking.status
                if (status == BookingStatus.PENDING || status == BookingStatus.ACCEPTED ||
                    status == BookingStatus.EN_ROUTE || status == BookingStatus.PICKED_UP) {
                    
                    bookingsToCancel.add(doc.reference to booking)

                    if (booking.passengerPaid && !booking.passengerRefunded) {
                        val pId = booking.passengerId
                        if (!passengerBalances.containsKey(pId)) {
                            val pRef = usersCol.document(pId)
                            val pSnap = tx.get(pRef)
                            passengerBalances[pId] = pSnap.getDouble("balance") ?: 0.0
                        }
                    }
                }
            }

            // 2. ALL WRITES
            tx.update(rideRef, "status", RideStatus.CANCELLED.name)

            for ((bRef, booking) in bookingsToCancel) {
                tx.update(bRef, "status", BookingStatus.CANCELLED.name)

                if (booking.passengerPaid && !booking.passengerRefunded) {
                    val pId = booking.passengerId
                    val currentBal = passengerBalances[pId] ?: 0.0
                    val newBal = currentBal + booking.totalPrice
                    
                    val pRef = usersCol.document(pId)
                    tx.update(pRef, "balance", newBal)
                    tx.update(bRef, "passengerRefunded", true)
                    
                    // Update local map in case the same passenger has multiple bookings (rare but possible)
                    passengerBalances[pId] = newBal
                }
            }
        }.await()
    }

    override suspend fun completeRide(rideId: String) {
        val uid = currentUid ?: return
        val bookingsSnapshot = getDriverBookingsForRide(rideId, uid)

        db.runTransaction { tx ->
            // 1. ALL READS
            val rideRef = ridesCol.document(rideId)
            val ride = tx.get(rideRef).toObject(Ride::class.java)
                ?: return@runTransaction
            
            if (ride.driverId != uid) return@runTransaction

            val dRef = usersCol.document(uid)
            val currentBal = tx.get(dRef).getDouble("balance") ?: 0.0

            val bookingStates = mutableListOf<Pair<DocumentReference, Booking>>()
            val passengerBalances = mutableMapOf<String, Double>()

            for (doc in bookingsSnapshot?.documents ?: emptyList()) {
                val bSnap = tx.get(doc.reference)
                val booking = bSnap.toObject(Booking::class.java) ?: continue
                if (booking.driverId != uid) continue
                
                bookingStates.add(doc.reference to booking)

                if (booking.status == BookingStatus.PENDING) {
                    if (booking.passengerPaid && !booking.passengerRefunded) {
                        val pId = booking.passengerId
                        if (!passengerBalances.containsKey(pId)) {
                            val pRef = usersCol.document(pId)
                            val pSnap = tx.get(pRef)
                            passengerBalances[pId] = pSnap.getDouble("balance") ?: 0.0
                        }
                    }
                }
            }

            // 2. ALL WRITES
            tx.update(rideRef, "status", RideStatus.COMPLETED.name)

            var totalEarnings = 0.0
            val nextRideRef = if (ride.periodic) ridesCol.document() else null
            val nextDate = if (ride.periodic) calculateNextOccurrence(ride.departureTime, ride.periodicLabel) else null

            for ((bRef, booking) in bookingStates) {
                val isActive = booking.status == BookingStatus.ACCEPTED || 
                               booking.status == BookingStatus.PICKED_UP || 
                               booking.status == BookingStatus.EN_ROUTE
                
                if (isActive) {
                    tx.update(bRef, "status", BookingStatus.COMPLETED.name)
                    if (!booking.driverPaid) {
                        totalEarnings += booking.totalPrice
                        tx.update(bRef, "driverPaid", true)
                    }
                } else if (booking.status == BookingStatus.PENDING) {
                    tx.update(bRef, "status", BookingStatus.REJECTED.name)
                    // Process refund for rejected pending bookings
                    if (booking.passengerPaid && !booking.passengerRefunded) {
                        val pId = booking.passengerId
                        val pRef = usersCol.document(pId)
                        val pBal = passengerBalances[pId] ?: 0.0
                        val newBal = pBal + booking.totalPrice
                        tx.update(pRef, "balance", newBal)
                        tx.update(bRef, "passengerRefunded", true)
                        passengerBalances[pId] = newBal
                    }
                }

                // Carry over recurring bookings
                if (ride.periodic && booking.recurring && (isActive || booking.status == BookingStatus.COMPLETED)) {
                    val nextBookingRef = bookingsCol.document()
                    val nextBooking = booking.copy(
                        id = nextBookingRef.id,
                        rideId = nextRideRef!!.id,
                        status = BookingStatus.PENDING, 
                        departureTime = nextDate,
                        createdAt = null,
                        passengerReviewed = false,
                        driverReviewed = false,
                        driverPaid = false,
                        passengerRefunded = false,
                        weatherCondition = booking.weatherCondition // Carry over weather preferences
                    )
                    tx.set(nextBookingRef, nextBooking)
                }
            }

            if (ride.periodic && nextRideRef != null) {
                val nextRide = ride.copy(
                    id = nextRideRef.id,
                    status = RideStatus.OPEN,
                    departureTime = nextDate,
                    seatsAvailable = ride.seatsTotal,
                    createdAt = null
                )
                tx.set(nextRideRef, nextRide)
            }

            if (totalEarnings > 0) {
                tx.update(dRef, "balance", currentBal + totalEarnings)
            }
        }.await()
    }

    override suspend fun startRide(rideId: String) {
        val uid = currentUid ?: return
        val bookingsSnapshot = getDriverBookingsForRide(rideId, uid)
        db.runTransaction { tx ->
            // 1. ALL READS
            val rideRef = ridesCol.document(rideId)
            val rideSnap = tx.get(rideRef)
            
            if (!rideSnap.exists() || rideSnap.getString("driverId") != uid) return@runTransaction
            
            val bookingRefsToUpdate = mutableListOf<DocumentReference>()
            for (doc in bookingsSnapshot?.documents ?: emptyList()) {
                val bSnap = tx.get(doc.reference)
                if (bSnap.getString("driverId") == uid && bSnap.getString("status") == BookingStatus.ACCEPTED.name) {
                    bookingRefsToUpdate.add(doc.reference)
                }
            }

            // 2. ALL WRITES
            tx.update(rideRef, "status", RideStatus.EN_ROUTE.name)
            for (ref in bookingRefsToUpdate) {
                tx.update(ref, "status", BookingStatus.EN_ROUTE.name)
            }
        }.await()
    }

    private suspend fun getDriverBookingsForRide(rideId: String, driverId: String) =
        bookingsCol
            .whereEqualTo("rideId", rideId)
            .whereEqualTo("driverId", driverId)
            .get()
            .await()

    private fun calculateNextOccurrence(currentDate: Date?, label: String): Date {
        val cal = Calendar.getInstance()
        if (currentDate != null) cal.time = currentDate
        
        when (label.lowercase()) {
            "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "weekdays" -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                } while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
            "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            "biweekly" -> cal.add(Calendar.WEEK_OF_YEAR, 2)
            "monthly" -> cal.add(Calendar.MONTH, 1)
            else -> cal.add(Calendar.DAY_OF_YEAR, 1) // Default to next day
        }
        return cal.time
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
        val bookingId = ref.id
        
        db.runTransaction { tx ->
            // 1. Deduct balance from passenger upfront
            val pRef = usersCol.document(booking.passengerId)
            val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
            if (pBal < booking.totalPrice) throw Exception("Insufficient balance")
            
            tx.update(pRef, "balance", pBal - booking.totalPrice)
            
            // 2. Create the booking as paid
            val toSave = booking.copy(
                id = bookingId,
                passengerPaid = true,
                passengerRefunded = false
            )
            tx.set(ref, toSave)
        }.await()
        
        return bookingId
    }

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        db.runTransaction { tx ->
            // 1. ALL READS
            val bookingRef = bookingsCol.document(bookingId)
            val booking = tx.get(bookingRef).toObject(Booking::class.java)
                ?: error("Booking not found")

            val current = booking.status
            val valid = when (status) {
                BookingStatus.ACCEPTED -> current == BookingStatus.PENDING
                BookingStatus.REJECTED -> current == BookingStatus.PENDING || current == BookingStatus.REJECTED
                BookingStatus.EN_ROUTE -> current == BookingStatus.ACCEPTED
                BookingStatus.PICKED_UP -> current == BookingStatus.EN_ROUTE
                BookingStatus.COMPLETED -> current == BookingStatus.PICKED_UP || current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE || current == BookingStatus.COMPLETED
                BookingStatus.CANCELLED -> (current != BookingStatus.COMPLETED && current != BookingStatus.REJECTED) || current == BookingStatus.CANCELLED
                else -> false
            }
            if (!valid) return@runTransaction

            var rideToUpdate: Pair<DocumentReference, Ride>? = null
            if (status == BookingStatus.ACCEPTED) {
                val rideRef = ridesCol.document(booking.rideId)
                val ride = tx.get(rideRef).toObject(Ride::class.java)
                    ?: error("Ride not found")
                rideToUpdate = rideRef to ride
            }

            var driverToPay: Pair<DocumentReference, Double>? = null
            var passengerToRefund: Pair<DocumentReference, Double>? = null
            if (status == BookingStatus.COMPLETED) {
                val uid = currentUid
                if (uid == booking.driverId && !booking.driverPaid) {
                    val dRef = usersCol.document(booking.driverId)
                    val dBal = tx.get(dRef).getDouble("balance") ?: 0.0
                    driverToPay = dRef to dBal
                }
            } else if ((status == BookingStatus.CANCELLED || status == BookingStatus.REJECTED) && 
                booking.passengerPaid && !booking.passengerRefunded) {
                val pRef = usersCol.document(booking.passengerId)
                val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
                passengerToRefund = pRef to pBal
            }
            
            var rideToReturnSeats: Pair<DocumentReference, Long>? = null
            if (status == BookingStatus.CANCELLED && (current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE || current == BookingStatus.PICKED_UP)) {
                val rideRef = ridesCol.document(booking.rideId)
                val rideSnap = tx.get(rideRef)
                if (rideSnap.exists()) {
                    rideToReturnSeats = rideRef to (rideSnap.getLong("seatsAvailable") ?: 0L)
                }
            }

            // 2. ALL WRITES
            if (status == BookingStatus.ACCEPTED && rideToUpdate != null) {
                val (rideRef, ride) = rideToUpdate
                if (ride.seatsAvailable < booking.seatsRequested) {
                    tx.update(bookingRef, "status", BookingStatus.REJECTED.name)
                    return@runTransaction
                }

                val newSeats = ride.seatsAvailable - booking.seatsRequested
                tx.update(rideRef, "seatsAvailable", newSeats)
                tx.update(rideRef, "status", if (newSeats == 0) RideStatus.FULL.name else RideStatus.OPEN.name)
            }

            if (driverToPay != null) {
                tx.update(driverToPay.first, "balance", driverToPay.second + booking.totalPrice)
                tx.update(bookingRef, "driverPaid", true)
            }

            if (passengerToRefund != null) {
                tx.update(passengerToRefund.first, "balance", passengerToRefund.second + booking.totalPrice)
                tx.update(bookingRef, "passengerRefunded", true)
            }
            
            if (rideToReturnSeats != null) {
                val (rideRef, available) = rideToReturnSeats
                tx.update(rideRef, "seatsAvailable", available + booking.seatsRequested)
                tx.update(rideRef, "status", RideStatus.OPEN.name)
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
        val requestId = ref.id
        
        db.runTransaction { tx ->
            // 1. Deduct balance from passenger upfront
            val pRef = usersCol.document(request.passengerId)
            val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
            if (pBal < request.estimatedPrice) throw Exception("Insufficient balance")
            
            tx.update(pRef, "balance", pBal - request.estimatedPrice)
            
            // 2. Create the request as paid
            val toSave = request.copy(
                id = requestId,
                passengerPaid = true,
                passengerRefunded = false,
                createdAt = null // Firestore @ServerTimestamp
            )
            tx.set(ref, toSave)
        }.await()
        
        return requestId
    }
    override suspend fun cancelRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.CANCELLED)
    }

    override suspend fun completeRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.COMPLETED)
    }

    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus) {
        db.runTransaction { tx ->
            // 1. ALL READS
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")

            val current = req.status
            val uid = currentUid

            var driverToPay: Pair<DocumentReference, Double>? = null
            var passengerToRefund: Pair<DocumentReference, Double>? = null

            if (status == RequestStatus.COMPLETED) {
                val driverId = req.driverId
                if (driverId != null && uid == driverId && !req.driverPaid) {
                    val dRef = usersCol.document(driverId)
                    val dBal = tx.get(dRef).getDouble("balance") ?: 0.0
                    driverToPay = dRef to dBal
                }
            } else if (status == RequestStatus.CANCELLED && req.passengerPaid && !req.passengerRefunded) {
                val pRef = usersCol.document(req.passengerId)
                val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
                passengerToRefund = pRef to pBal
            }

            // 2. ALL WRITES
            // Special case: Driver cancels an accepted request -> Reset to OPEN
            if (status == RequestStatus.CANCELLED && uid == req.driverId && req.driverId != null) {
                tx.update(ref, "status", RequestStatus.OPEN.name)
                tx.update(ref, "driverId", null)
                tx.update(ref, "driverName", null)
                tx.update(ref, "driverRating", 5.0)
                return@runTransaction
            }

            // Allow idempotent transitions for auto-refund
            val valid = when (status) {
                RequestStatus.COMPLETED -> current != RequestStatus.CANCELLED
                RequestStatus.CANCELLED -> current != RequestStatus.COMPLETED || current == RequestStatus.CANCELLED
                else -> true
            }
            if (!valid) return@runTransaction

            if (driverToPay != null) {
                tx.update(driverToPay.first, "balance", driverToPay.second + req.estimatedPrice)
                tx.update(ref, "driverPaid", true)
            }

            if (passengerToRefund != null) {
                tx.update(passengerToRefund.first, "balance", passengerToRefund.second + req.estimatedPrice)
                tx.update(ref, "passengerRefunded", true)
            }

            tx.update(ref, "status", status.name)
        }.await()
    }

    override suspend fun denyRequest(requestId: String, driverId: String) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: return@runTransaction
            val currentDenied = req.deniedBy.toMutableList()
            if (!currentDenied.contains(driverId)) {
                currentDenied.add(driverId)
                tx.update(ref, "deniedBy", currentDenied)
            }
        }.await()
    }

    override suspend fun rejectDriver(requestId: String, driverId: String) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: return@runTransaction
            
            val currentDeniedDrivers = req.deniedDrivers.toMutableList()
            if (!currentDeniedDrivers.contains(driverId)) {
                currentDeniedDrivers.add(driverId)
            }
            
            tx.update(ref, "status", RequestStatus.OPEN.name)
            tx.update(ref, "driverId", null)
            tx.update(ref, "driverName", null)
            tx.update(ref, "driverRating", 5.0)
            tx.update(ref, "deniedDrivers", currentDeniedDrivers)
        }.await()
    }

    override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")
            if (req.status != RequestStatus.OPEN) error("Request already taken")

            // Convert Request to Booking
            val bookingRef = bookingsCol.document()
            val booking = Booking(
                id = bookingRef.id,
                rideId = "requested_${req.id}", // Marker for requests-based rides
                passengerId = req.passengerId,
                passengerName = req.passengerName,
                passengerRating = 5.0, // Should be fetched from user
                seatsRequested = 1,
                totalPrice = req.estimatedPrice,
                status = BookingStatus.ACCEPTED,
                origin = req.origin,
                destination = req.destination,
                departureTime = req.requestedTime,
                driverName = driverName,
                driverId = driverId,
                weatherCondition = req.weatherCondition
            )
            
            tx.set(bookingRef, booking)
            tx.update(ref, mapOf("status" to RequestStatus.ACCEPTED.name, "driverId" to driverId, "driverName" to driverName, "driverRating" to driverRating))
        }.await()
    }

    override suspend fun submitReview(review: Review) {
        if (review.requestId.isBlank()) return
        val uid = currentUid ?: return

        db.runTransaction { tx ->
            // 1. ALL READS
            val reqRef = requestsCol.document(review.requestId)
            val reqSnap = tx.get(reqRef)
            
            var fieldToUpdate: String? = null
            var docToUpdate: DocumentReference? = null
            
            if (reqSnap.exists()) {
                val driverId = reqSnap.getString("driverId")
                fieldToUpdate = if (uid == driverId) "passengerReviewed" else "driverReviewed"
                docToUpdate = reqRef
            } else {
                val bookRef = bookingsCol.document(review.requestId)
                val bookSnap = tx.get(bookRef)
                if (bookSnap.exists()) {
                    val driverId = bookSnap.getString("driverId")
                    fieldToUpdate = if (uid == driverId) "passengerReviewed" else "driverReviewed"
                    docToUpdate = bookRef
                }
            }

            // 2. ALL WRITES
            val reviewRef = reviewsCol.document()
            tx.set(reviewRef, review.copy(id = reviewRef.id))
            
            if (docToUpdate != null && fieldToUpdate != null) {
                tx.update(docToUpdate, fieldToUpdate, true)
            }
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
