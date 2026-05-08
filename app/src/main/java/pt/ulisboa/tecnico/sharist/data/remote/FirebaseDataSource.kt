package pt.ulisboa.tecnico.sharist.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import pt.ulisboa.tecnico.sharist.data.model.*

class FirebaseDataSource(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : RemoteDataSource {

    override val currentUid: String? get() = auth.currentUser?.uid

    // ── Auth ──────────────────────────────────────────────────────────────────

    override suspend fun signIn(email: String, pass: String) =
        auth.signInWithEmailAndPassword(email, pass).await()

    override suspend fun register(email: String, pass: String) =
        auth.createUserWithEmailAndPassword(email, pass).await()

    override fun signOut() = auth.signOut()

    // ── User Profiles ─────────────────────────────────────────────────────────

    override suspend fun createUserProfile(user: User) {
        db.collection("users").document(user.uid).set(user).await()
    }

    override suspend fun getUser(uid: String): User? =
        db.collection("users").document(uid).get().await().toObject(User::class.java)

    override suspend fun updateBalance(uid: String, delta: Double) {
        db.collection("users").document(uid)
            .update("balance", FieldValue.increment(delta))
            .await()
    }

    override suspend fun submitReview(review: Review) {
        db.collection("reviews").add(review).await()
    }

    // ── Rides ─────────────────────────────────────────────────────────────────

    override fun observeRides(filter: RideFilter): Flow<List<Ride>> {
        var query: Query = db.collection("rides")

        filter.origin.takeIf { it.isNotBlank() }?.let {
            query = query.whereEqualTo("origin", it)
        }
        filter.destination.takeIf { it.isNotBlank() }?.let {
            query = query.whereEqualTo("destination", it)
        }
        filter.maxPrice?.let {
            query = query.whereLessThanOrEqualTo("pricePerSeat", it)
        }

        return query.snapshots().map { snapshot ->
            snapshot.toObjects(Ride::class.java).filter { ride ->
                ride.seatsAvailable >= filter.minSeats
            }
        }
    }

    override suspend fun getRide(rideId: String): Ride? =
        db.collection("rides").document(rideId).get().await().toObject(Ride::class.java)

    override fun observeDriverRides(driverId: String): Flow<List<Ride>> =
        db.collection("rides")
            .whereEqualTo("driverId", driverId)
            .snapshots()
            .map { it.toObjects(Ride::class.java) }

    override suspend fun createRide(ride: Ride): String {
        val doc = db.collection("rides").document()
        val rideWithId = ride.copy(id = doc.id)
        doc.set(rideWithId).await()
        return doc.id
    }

    override suspend fun decrementSeat(rideId: String) {
        db.collection("rides").document(rideId)
            .update("seatsAvailable", FieldValue.increment(-1))
            .await()
    }

    // ── Bookings ──────────────────────────────────────────────────────────────

    override suspend fun createBooking(booking: Booking): String {
        val doc = db.collection("bookings").document()
        val bookingWithId = booking.copy(id = doc.id)
        doc.set(bookingWithId).await()
        return doc.id
    }

    override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) {
        db.collection("bookings").document(bookingId)
            .update("status", status)
            .await()
    }

    override fun observePassengerBookings(passengerId: String): Flow<List<Booking>> =
        db.collection("bookings")
            .whereEqualTo("passengerId", passengerId)
            .snapshots()
            .map { it.toObjects(Booking::class.java) }

    override fun observeRideBookings(rideId: String): Flow<List<Booking>> =
        db.collection("bookings")
            .whereEqualTo("rideId", rideId)
            .snapshots()
            .map { it.toObjects(Booking::class.java) }
}
