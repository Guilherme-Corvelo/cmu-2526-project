package pt.ulisboa.tecnico.sharist.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
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

    private companion object {
        const val LATE_CANCELLATION_TRUST_PENALTY = 0.10
        const val DEFAULT_REQUEST_CANCELLATION_LIMIT_MINUTES = 60
    }

    override val currentUid: String? get() = auth.currentUser?.uid

    override suspend fun signIn(email: String, pass: String): com.google.firebase.auth.AuthResult? = auth.signInWithEmailAndPassword(email, pass).await()
    override suspend fun register(email: String, pass: String): com.google.firebase.auth.AuthResult? = auth.createUserWithEmailAndPassword(email, pass).await()
    override fun signOut() = auth.signOut()
    override suspend fun createUserProfile(user: User) { 
        val hashedUid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(user.uid)
        usersCol.document(user.uid).set(user.copy(hashedUid = hashedUid)).await() 
    }

    override suspend fun updateUserProfile(user: User) {
        val hashedUid = user.hashedUid.ifBlank { pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(user.uid) }
        usersCol.document(user.uid).set(user.copy(hashedUid = hashedUid), SetOptions.merge()).await()
    }

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
        val query: Query = ridesCol.whereIn("status", listOf(RideStatus.OPEN.name, RideStatus.FULL.name))
        
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

    override suspend fun cancelRide(rideId: String, reschedule: Boolean) {
        val uid = currentUid ?: return
        val bookingsSnapshot = bookingsCol.whereEqualTo("rideId", rideId).whereEqualTo("driverId", uid).get().await()

        db.runTransaction { tx ->
            // 1. ALL READS
            val rideRef = ridesCol.document(rideId)
            val rideSnap = tx.get(rideRef)
            val ride = rideSnap.toObject(Ride::class.java) ?: return@runTransaction
            if (ride.driverId != uid) return@runTransaction

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
                        val pId = booking.passengerId ?: continue
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

            val nextRideRef = if (reschedule && ride.periodic) ridesCol.document() else null
            val nextDate = if (reschedule && ride.periodic) calculateNextOccurrence(ride.departureTime, ride.periodicLabel) else null

            for ((bRef, booking) in bookingsToCancel) {
                tx.update(bRef, "status", BookingStatus.CANCELLED.name)

                if (booking.passengerPaid && !booking.passengerRefunded) {
                    val pId = booking.passengerId ?: continue
                    val currentBal = passengerBalances[pId] ?: 0.0
                    val newBal = currentBal + booking.totalPrice
                    
                    val pRef = usersCol.document(pId)
                    tx.update(pRef, "balance", newBal)
                    tx.update(bRef, "passengerRefunded", true)
                    
                    // Update local map in case the same passenger has multiple bookings
                    passengerBalances[pId] = newBal
                }

                // Reschedule recurring bookings if requested
                if (reschedule && ride.periodic && booking.recurring) {
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
                        passengerRefunded = false
                    )
                    tx.set(nextBookingRef, nextBooking)
                }
            }

            if (reschedule && ride.periodic && nextRideRef != null) {
                val nextRide = ride.copy(
                    id = nextRideRef.id,
                    status = RideStatus.OPEN,
                    departureTime = nextDate,
                    seatsAvailable = ride.seatsTotal,
                    createdAt = null
                )
                tx.set(nextRideRef, nextRide)
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
            ensurePeriodicRideDepartureReached(ride)

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
                        val pId = booking.passengerId ?: continue
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
                    // Privacy cleanup for completed booking
                    tx.update(bRef, "origin", "anonymized")
                    tx.update(bRef, "destination", "anonymized")
                    // tx.update(bRef, "passengerId", "anonymized") // Removed to fix My Rides visibility
                } else if (booking.status == BookingStatus.PENDING) {
                    tx.update(bRef, "status", BookingStatus.REJECTED.name)
                    // Process refund for rejected pending bookings
                    if (booking.passengerPaid && !booking.passengerRefunded) {
                        val pId = booking.passengerId ?: continue
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
            
            // Privacy cleanup for ride
            tx.update(rideRef, "origin", "anonymized")
            tx.update(rideRef, "destination", "anonymized")
        }.await()
    }

    override suspend fun startRide(rideId: String) {
        val uid = currentUid ?: return
        val bookingsSnapshot = getDriverBookingsForRide(rideId, uid)
        db.runTransaction { tx ->
            // 1. ALL READS
            val rideRef = ridesCol.document(rideId)
            val rideSnap = tx.get(rideRef)
            
            val ride = rideSnap.toObject(Ride::class.java) ?: return@runTransaction
            if (!rideSnap.exists() || ride.driverId != uid) return@runTransaction
            ensurePeriodicRideDepartureReached(ride)
            
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

    private fun ensurePeriodicRideDepartureReached(ride: Ride) {
        val departure = ride.departureTime
        if (ride.periodic && departure != null && Date().before(departure)) {
            throw IllegalStateException("This periodic ride cannot start or finish before its scheduled departure time.")
        }
    }

    private fun ensurePeriodicRequestDepartureReached(req: RideRequest, targetStatus: RequestStatus) {
        val movingForward = targetStatus == RequestStatus.EN_ROUTE ||
            targetStatus == RequestStatus.PICKED_UP ||
            targetStatus == RequestStatus.COMPLETED
        val requestedTime = req.requestedTime
        if (req.periodic && movingForward && requestedTime != null && Date().before(requestedTime)) {
            throw IllegalStateException("This periodic ride request cannot start or finish before its scheduled departure time.")
        }
    }

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
            val passengerId = booking.passengerId ?: throw Exception("Passenger ID is required")
            val pRef = usersCol.document(passengerId)
            val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
            if (pBal < booking.totalPrice) throw Exception("Insufficient balance")
            
            tx.update(pRef, "balance", pBal - booking.totalPrice)
            
            // 2. Create the booking as paid
            val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(passengerId)
            val toSave = booking.copy(
                id = bookingId,
                passengerId = passengerId, // Ensure it's set
                hashedPassengerId = hashedPid,
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
                BookingStatus.CANCELLED -> (current != BookingStatus.COMPLETED && current != BookingStatus.REJECTED)
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
            var passengerPenalty: Pair<DocumentReference, Double>? = null
            var rideToReturnSeats: Pair<DocumentReference, Long>? = null
            val uid = currentUid
            val cancellingActiveBooking = status == BookingStatus.CANCELLED &&
                (current == BookingStatus.ACCEPTED || current == BookingStatus.EN_ROUTE || current == BookingStatus.PICKED_UP)

            if (status == BookingStatus.COMPLETED) {
                if (uid == booking.driverId && !booking.driverPaid) {
                    val dRef = usersCol.document(booking.driverId)
                    val dBal = tx.get(dRef).getDouble("balance") ?: 0.0
                    driverToPay = dRef to dBal
                }
            }

            var rideForCancellation: Ride? = null
            if (cancellingActiveBooking) {
                val rideRef = ridesCol.document(booking.rideId)
                val rideSnap = tx.get(rideRef)
                if (rideSnap.exists()) {
                    rideForCancellation = rideSnap.toObject(Ride::class.java)
                    rideToReturnSeats = rideRef to (rideSnap.getLong("seatsAvailable") ?: 0L)
                }
            }

            val shouldRefundPassenger = (status == BookingStatus.CANCELLED || status == BookingStatus.REJECTED) &&
                booking.passengerPaid && !booking.passengerRefunded
            val shouldPenalizePassenger = status == BookingStatus.CANCELLED &&
                uid == booking.passengerId &&
                cancellingActiveBooking &&
                isAfterPenaltyFreeWindow(
                    rideForCancellation?.departureTime ?: booking.departureTime,
                    rideForCancellation?.cancellationLimitMinutes ?: 60
                )

            if (shouldRefundPassenger || shouldPenalizePassenger) {
                val pId = booking.passengerId ?: return@runTransaction
                val pRef = usersCol.document(pId)
                val pSnap = tx.get(pRef)
                if (shouldRefundPassenger) {
                    passengerToRefund = pRef to (pSnap.getDouble("balance") ?: 0.0)
                }
                if (shouldPenalizePassenger) {
                    passengerPenalty = pRef to (pSnap.getDouble("trustScore") ?: 1.0)
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

            if (passengerPenalty != null) {
                val newTrustScore = (passengerPenalty.second - LATE_CANCELLATION_TRUST_PENALTY).coerceAtLeast(0.0)
                tx.update(passengerPenalty.first, "trustScore", newTrustScore)
                tx.update(bookingRef, "lateCancellationPenaltyApplied", true)
            }
            
            if (rideToReturnSeats != null) {
                val (rideRef, available) = rideToReturnSeats
                tx.update(rideRef, "seatsAvailable", available + booking.seatsRequested)
                tx.update(rideRef, "status", RideStatus.OPEN.name)
            }

            tx.update(bookingRef, "status", status.name)
        }.await()
    }

    private fun isAfterPenaltyFreeWindow(departureTime: Date?, cancellationLimitMinutes: Int, now: Date = Date()): Boolean {
        val departure = departureTime ?: return false
        val penaltyFreeUntil = Date(departure.time - cancellationLimitMinutes.coerceAtLeast(0) * 60_000L)
        return now.after(penaltyFreeUntil)
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> = callbackFlow {
        // Query by passengerId instead of hashedPid to ensure user can see their own history
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
        // Query by passengerId instead of hashedPid to ensure user can see their own history
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
            val passengerId = request.passengerId ?: throw Exception("Passenger ID is required")
            val pRef = usersCol.document(passengerId)
            val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
            if (pBal < request.estimatedPrice) throw Exception("Insufficient balance")
            
            tx.update(pRef, "balance", pBal - request.estimatedPrice)
            
            // 2. Create the request as paid
            val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(passengerId)
            val toSave = request.copy(
                id = requestId,
                passengerId = passengerId, // Ensure it's set
                hashedPassengerId = hashedPid,
                passengerPaid = true,
                passengerRefunded = false,
                createdAt = null // Firestore @ServerTimestamp
            )
            tx.set(ref, toSave)
        }.await()
        
        return requestId
    }
    override suspend fun cancelRequest(requestId: String, reschedule: Boolean) {
        updateRequestStatus(requestId, RequestStatus.CANCELLED, reschedule)
    }

    override suspend fun completeRequest(requestId: String) {
        updateRequestStatus(requestId, RequestStatus.COMPLETED, false)
    }

    override suspend fun updateRequestStatus(requestId: String, status: RequestStatus, reschedule: Boolean) {
        db.runTransaction { tx ->
            // 1. ALL READS
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")

            val current = req.status
            val uid = currentUid

            var driverToPay: Pair<DocumentReference, Double>? = null
            var passengerToRefund: Pair<DocumentReference, Double>? = null
            var passengerPenalty: Pair<DocumentReference, Double>? = null

            ensurePeriodicRequestDepartureReached(req, status)

            if (status == RequestStatus.COMPLETED) {
                val driverId = req.driverId
                if (driverId != null && uid == driverId && !req.driverPaid) {
                    val dRef = usersCol.document(driverId)
                    val dBal = tx.get(dRef).getDouble("balance") ?: 0.0
                    driverToPay = dRef to dBal
                }
            }

            val shouldRefundPassenger = status == RequestStatus.CANCELLED && req.passengerPaid && !req.passengerRefunded
            val shouldPenalizePassenger = status == RequestStatus.CANCELLED &&
                uid == req.passengerId &&
                (current == RequestStatus.ACCEPTED || current == RequestStatus.EN_ROUTE || current == RequestStatus.PICKED_UP) &&
                isAfterPenaltyFreeWindow(req.requestedTime, DEFAULT_REQUEST_CANCELLATION_LIMIT_MINUTES)

            if (shouldRefundPassenger || shouldPenalizePassenger) {
                val pId = req.passengerId ?: return@runTransaction
                val pRef = usersCol.document(pId)
                val pSnap = tx.get(pRef)
                if (shouldRefundPassenger) {
                    passengerToRefund = pRef to (pSnap.getDouble("balance") ?: 0.0)
                }
                if (shouldPenalizePassenger) {
                    passengerPenalty = pRef to (pSnap.getDouble("trustScore") ?: 1.0)
                }
            }

            // 2. ALL WRITES
            // Special case: Driver cancels an accepted request -> release it back to the
            // open request pool for other drivers, while hiding it from this driver.
            if (status == RequestStatus.CANCELLED && uid == req.driverId && req.driverId != null) {
                val updatedDeniedBy = req.deniedBy.toMutableList()
                if (uid != null && !updatedDeniedBy.contains(uid)) {
                    updatedDeniedBy.add(uid)
                }
                tx.update(ref, "status", RequestStatus.OPEN.name)
                tx.update(ref, "driverId", null)
                tx.update(ref, "hashedDriverId", "")
                tx.update(ref, "driverName", null)
                tx.update(ref, "driverRating", 5.0)
                tx.update(ref, "deniedBy", updatedDeniedBy)
                return@runTransaction
            }

            // Allow idempotent transitions for auto-refund
            val valid = when (status) {
                RequestStatus.COMPLETED -> current != RequestStatus.CANCELLED
                RequestStatus.CANCELLED -> current != RequestStatus.COMPLETED
                else -> true
            }
            if (!valid) return@runTransaction

            // Reschedule logic for periodic requests cancelled by the passenger/system.
            // Completed passenger-created periodic requests publish their next
            // occurrence after an involved user reviews the completed ride in
            // submitReview(), so the old completed request stays available for
            // post-ride review.
            if (req.periodic && reschedule && status == RequestStatus.CANCELLED) {
                val nextRef = requestsCol.document()
                val nextDate = calculateNextOccurrence(req.requestedTime, req.periodicLabel)
                val nextReq = req.copy(
                    id = nextRef.id,
                    status = RequestStatus.OPEN,
                    requestedTime = nextDate,
                    driverId = null,
                    hashedDriverId = "",
                    driverName = null,
                    driverRating = 5.0,
                    passengerPaid = req.passengerPaid,
                    passengerRefunded = false,
                    driverPaid = false,
                    driverReviewed = false,
                    passengerReviewed = false,
                    previousRequestId = req.id,
                    deniedBy = emptyList(),
                    deniedDrivers = emptyList(),
                    createdAt = null
                )
                tx.set(nextRef, nextReq)
            }

            if (driverToPay != null) {
                tx.update(driverToPay.first, "balance", driverToPay.second + req.estimatedPrice)
                tx.update(ref, "driverPaid", true)
            }

            if (passengerToRefund != null) {
                tx.update(passengerToRefund.first, "balance", passengerToRefund.second + req.estimatedPrice)
                tx.update(ref, "passengerRefunded", true)
            }

            if (passengerPenalty != null) {
                val newTrustScore = (passengerPenalty.second - LATE_CANCELLATION_TRUST_PENALTY).coerceAtLeast(0.0)
                tx.update(passengerPenalty.first, "trustScore", newTrustScore)
                tx.update(ref, "lateCancellationPenaltyApplied", true)
            }

            if (status == RequestStatus.COMPLETED && !req.periodic) {
                tx.update(ref, "origin", "anonymized")
                tx.update(ref, "destination", "anonymized")
            }

            tx.update(ref, "status", status.name)
        }.await()

        if (status == RequestStatus.CANCELLED) {
            runCatching { deleteSyntheticRequestBookingsForCurrentUser(requestId) }
                .onFailure { android.util.Log.w("FirebaseDataSource", "Synthetic request booking cleanup failed for $requestId", it) }
        }
    }

    private suspend fun deleteSyntheticRequestBookingsForCurrentUser(requestId: String) {
        val uid = currentUid ?: return
        val requestRideId = "requested_$requestId"
        val driverBookings = bookingsCol
            .whereEqualTo("rideId", requestRideId)
            .whereEqualTo("driverId", uid)
            .get()
            .await()
        val passengerBookings = bookingsCol
            .whereEqualTo("rideId", requestRideId)
            .whereEqualTo("passengerId", uid)
            .get()
            .await()

        val deletedBookingIds = mutableSetOf<String>()
        for (doc in driverBookings.documents + passengerBookings.documents) {
            if (deletedBookingIds.add(doc.id)) {
                doc.reference.delete().await()
            }
        }
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
            tx.update(ref, "hashedDriverId", "")
            tx.update(ref, "driverName", null)
            tx.update(ref, "driverRating", 5.0)
            tx.update(ref, "deniedDrivers", currentDeniedDrivers)

            // Also delete the associated booking created during acceptance
            // We search for bookings where rideId == "requested_$requestId"
        }.await()

        // Transactions can't perform queries easily, so we handle the booking deletion separately 
        // or we need to find the booking ID. 
        // Since we don't store the booking ID in the request, we query for it.
        val bookings = bookingsCol
            .whereEqualTo("rideId", "requested_$requestId")
            .whereEqualTo("driverId", driverId)
            .get()
            .await()
        
        for (doc in bookings.documents) {
            doc.reference.delete().await()
        }
    }

    override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) {
        db.runTransaction { tx ->
            val ref = requestsCol.document(requestId)
            val req = tx.get(ref).toObject(RideRequest::class.java) ?: error("Request not found")
            if (req.status != RequestStatus.OPEN) error("Request already taken")

            val passengerId = req.passengerId ?: "unknown"
            val isGeneratedPeriodicRequest = req.periodic && req.previousRequestId.isNotBlank()
            val requestIsPrepaid = req.passengerPaid || isGeneratedPeriodicRequest
            var passengerToCharge: Pair<DocumentReference, Double>? = null
            if (!requestIsPrepaid && passengerId != "unknown") {
                val pRef = usersCol.document(passengerId)
                val pBal = tx.get(pRef).getDouble("balance") ?: 0.0
                if (pBal < req.estimatedPrice) error("Passenger has insufficient balance")
                passengerToCharge = pRef to pBal
            }

            // Convert Request to Booking
            val bookingRef = bookingsCol.document()
            val booking = Booking(
                id = bookingRef.id,
                rideId = "requested_${req.id}", // Marker for requests-based rides
                passengerId = passengerId,
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
                passengerPaid = requestIsPrepaid,
                passengerRefunded = false,
                weatherCondition = req.weatherCondition
            )
            
            tx.set(bookingRef, booking)
            val requestUpdates = mutableMapOf<String, Any>(
                "status" to RequestStatus.ACCEPTED.name,
                "driverId" to driverId,
                "driverName" to driverName,
                "driverRating" to driverRating
            )
            if (passengerToCharge != null) {
                tx.update(passengerToCharge.first, "balance", passengerToCharge.second - req.estimatedPrice)
                requestUpdates["passengerPaid"] = true
                requestUpdates["passengerRefunded"] = false
            } else if (isGeneratedPeriodicRequest && !req.passengerPaid) {
                // Match recurring bookings: generated periodic occurrences carry
                // the existing payment commitment forward, so a driver accepting
                // one must not try to debit the passenger's balance.
                requestUpdates["passengerPaid"] = true
                requestUpdates["passengerRefunded"] = false
            }
            tx.update(ref, requestUpdates)
        }.await()
    }

    override suspend fun submitReview(review: Review): String? {
        if (review.requestId.isBlank()) return null
        val uid = currentUid ?: return null

        var targetRideId: String? = null
        var periodicRequestToReschedule: RideRequest? = null

        db.runTransaction { tx ->
            // 1. ALL READS
            val reqRef = requestsCol.document(review.requestId)
            val reqSnap = tx.get(reqRef)
            
            var fieldToUpdate: String? = null
            var docToUpdate: DocumentReference? = null
            
            if (reqSnap.exists()) {
                val driverId = reqSnap.getString("driverId")
                val passengerId = reqSnap.getString("passengerId")
                fieldToUpdate = if (uid == driverId) "passengerReviewed" else "driverReviewed"
                docToUpdate = reqRef
                targetRideId = review.requestId // For requests, rideId is requestId
                
                // Ensure review has correct IDs
                val finalDriverId = driverId ?: ""
                val finalPassengerId = passengerId ?: ""
                
                val reviewRef = reviewsCol.document()
                val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(finalPassengerId)
                
                tx.set(reviewRef, review.copy(
                    id = reviewRef.id,
                    driverId = finalDriverId,
                    passengerId = finalPassengerId,
                    hashedPassengerId = hashedPid,
                    rideId = targetRideId ?: ""
                ))
            } else {
                val bookRef = bookingsCol.document(review.requestId)
                val bookSnap = tx.get(bookRef)
                if (bookSnap.exists()) {
                    val driverId = bookSnap.getString("driverId")
                    val passengerId = bookSnap.getString("passengerId")
                    fieldToUpdate = if (uid == driverId) "passengerReviewed" else "driverReviewed"
                    docToUpdate = bookRef
                    targetRideId = bookSnap.getString("rideId")
                    
                    val finalDriverId = driverId ?: ""
                    val finalPassengerId = passengerId ?: ""
                    
                    val reviewRef = reviewsCol.document()
                    val hashedPid = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.hashIdentifier(finalPassengerId)
                    
                    tx.set(reviewRef, review.copy(
                        id = reviewRef.id,
                        driverId = finalDriverId,
                        passengerId = finalPassengerId,
                        hashedPassengerId = hashedPid,
                        rideId = targetRideId ?: ""
                    ))
                }
            }

            if (docToUpdate != null && fieldToUpdate != null) {
                if (docToUpdate == reqRef) {
                    val req = reqSnap.toObject(RideRequest::class.java)
                    val isInvolvedUser = uid == req?.driverId || uid == req?.passengerId
                    val alreadyReviewed = when (fieldToUpdate) {
                        "passengerReviewed" -> req?.passengerReviewed == true
                        "driverReviewed" -> req?.driverReviewed == true
                        else -> false
                    }
                    if (req?.periodic == true && req.status == RequestStatus.COMPLETED && isInvolvedUser && !alreadyReviewed) {
                        periodicRequestToReschedule = req
                    }
                    tx.update(docToUpdate, fieldToUpdate, true)
                    if (req?.periodic == true && req.status == RequestStatus.COMPLETED) {
                        tx.update(docToUpdate, "origin", "anonymized")
                        tx.update(docToUpdate, "destination", "anonymized")
                    }
                } else {
                    tx.update(docToUpdate, fieldToUpdate, true)
                }
            }
        }.await()

        periodicRequestToReschedule?.let { req ->
            try {
                publishNextPeriodicRequest(req)
            } catch (e: Exception) {
                android.util.Log.e("FirebaseDataSource", "Review submitted, but failed to publish next periodic request ${req.id}", e)
            }
        }
        
        targetRideId?.let { rideId ->
            try {
                processRideReputation(rideId)
            } catch (e: Exception) {
                android.util.Log.e("FirebaseDataSource", "Error processing reputation for ride $rideId", e)
            }
        }
        
        return targetRideId
    }

    private suspend fun publishNextPeriodicRequest(req: RideRequest) {
        val existingNext = requestsCol
            .whereEqualTo("previousRequestId", req.id)
            .limit(1)
            .get()
            .await()
        if (!existingNext.isEmpty) return

        val nextRef = requestsCol.document()
        val nextReq = req.copy(
            id = nextRef.id,
            status = RequestStatus.OPEN,
            requestedTime = calculateNextOccurrence(req.requestedTime, req.periodicLabel),
            previousRequestId = req.id,
            driverId = null,
            hashedDriverId = "",
            driverName = null,
            driverRating = 5.0,
            // The periodic request was already prepaid by the passenger.
            // Keeping that prepaid state prevents a driver from trying to
            // debit another user's balance when accepting the generated
            // occurrence, which Firestore rules correctly reject.
            passengerPaid = req.passengerPaid,
            passengerRefunded = req.passengerRefunded,
            driverPaid = false,
            driverReviewed = false,
            passengerReviewed = false,
            deniedBy = emptyList(),
            deniedDrivers = emptyList(),
            createdAt = null
        )
        nextRef.set(nextReq).await()
    }

    override fun observeReviewsForUser(userId: String): Flow<List<Review>> = callbackFlow {
        // Query reviews by driverId (to see what others said about this driver)
        // OR by passengerId (to see reviews left by this passenger)
        // We'll use a listener that fetches reviews where either driverId OR passengerId matches.
        // Since Firestore doesn't support OR across different fields easily without composite indices,
        // we'll observe by driverId for the profile reputation. 
        // Note: For reputation/rating logic, driverId is the primary target.
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

    override suspend fun getAllUsers(): List<User> = 
        usersCol.get().await().toObjects(User::class.java).filterNotNull()

    override suspend fun getReviewsForUserSync(userId: String): List<Review> =
        reviewsCol.whereEqualTo("driverId", userId).get().await().toObjects(Review::class.java).filterNotNull()

    override suspend fun flagReviewAsOutlier(reviewId: String) {
        reviewsCol.document(reviewId).update("isOutlier", true).await()
    }

    override suspend fun updateUserTrustScore(userId: String, trustScore: Double) {
        usersCol.document(userId).update("trustScore", trustScore).await()
    }

    override fun observeFavorites(userId: String): Flow<List<FavoriteLocation>> = callbackFlow {
        val listener = db.collection("favorites")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    if (err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        android.util.Log.w("FirebaseDataSource", "Permission denied for favorites, likely missing rules. Emitting empty list.")
                        trySend(emptyList())
                    } else {
                        close(err)
                    }
                } else {
                    trySend(snap?.toObjects(FavoriteLocation::class.java)?.filterNotNull() ?: emptyList())
                }
            }
        activeListeners.add(listener)
        awaitClose {
            listener.remove()
            activeListeners.remove(listener)
        }
    }

    override suspend fun addFavorite(favorite: FavoriteLocation) {
        val ref = if (favorite.id.isBlank()) db.collection("favorites").document() else db.collection("favorites").document(favorite.id)
        ref.set(favorite.copy(id = ref.id)).await()
    }

    override suspend fun deleteFavorite(id: String) {
        db.collection("favorites").document(id).delete().await()
    }

    override suspend fun processRideReputation(rideId: String) {
        // Implementation for outlier detection and reputation update
        val rideRef = ridesCol.document(rideId)
        val ride = rideRef.get().await().toObject(Ride::class.java) ?: return
        val driverId = ride.driverId

        // Fetch reviews outside the transaction to avoid query restrictions
        val reviews = reviewsCol.whereEqualTo("driverId", driverId).get().await().toObjects(Review::class.java)
        if (reviews.isEmpty()) return

        db.runTransaction { tx ->
            val ratings = reviews.map { it.rating.toDouble() }

            var validSum = 0.0
            var validCount = 0
            
            for (review in reviews) {
                val isOutlier = pt.ulisboa.tecnico.sharist.utils.SecurityUtils.isOutlier(review.rating.toDouble(), ratings)
                if (isOutlier != review.isOutlier) {
                    tx.update(reviewsCol.document(review.id), "isOutlier", isOutlier)
                }
                if (!isOutlier) {
                    validSum += review.rating.toDouble()
                    validCount++
                }
            }

            if (validCount > 0) {
                val newRating = validSum / validCount
                val userRef = usersCol.document(driverId)
                tx.update(userRef, mapOf(
                    "rating" to newRating,
                    "ratingCount" to validCount,
                    "trustScore" to (validCount.toDouble() / (validCount + 5)) // Simple trust score formula
                ))
            }
        }.await()
    }

    override fun clearListeners() {
        val listeners = ArrayList(activeListeners)
        activeListeners.clear()
        listeners.forEach { 
            try { it.remove() } catch (e: Exception) { android.util.Log.e("FirebaseDataSource", "Error removing listener", e) }
        }
    }
}
