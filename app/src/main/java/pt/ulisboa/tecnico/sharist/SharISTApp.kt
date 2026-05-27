package pt.ulisboa.tecnico.sharist

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import pt.ulisboa.tecnico.sharist.data.local.LocalDataSource
import pt.ulisboa.tecnico.sharist.data.local.SharISTDatabase
import pt.ulisboa.tecnico.sharist.data.model.*
import pt.ulisboa.tecnico.sharist.data.remote.FirebaseDataSource
import pt.ulisboa.tecnico.sharist.data.remote.MockRemoteDataSource
import pt.ulisboa.tecnico.sharist.data.remote.RemoteDataSource
import pt.ulisboa.tecnico.sharist.data.repository.RideRepository
import pt.ulisboa.tecnico.sharist.data.repository.RideRequestRepository
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.utils.ConnectionType
import pt.ulisboa.tecnico.sharist.utils.NetworkMonitor
import pt.ulisboa.tecnico.sharist.utils.SessionManager

@SuppressLint("DiscouragedApi")
class SharISTApp : Application() {

    val networkMonitor by lazy { NetworkMonitor(this) }
    val sessionManager by lazy { SessionManager(this) }

    private val db by lazy { SharISTDatabase.getInstance(this) }

    private val localDataSource by lazy { LocalDataSource(db) }

    val remoteDataSource: RemoteDataSource by lazy {
        val firebaseInstance = lazy {
            try {
                val app = if (FirebaseApp.getApps(this).isEmpty()) {
                    FirebaseApp.initializeApp(this)
                } else {
                    FirebaseApp.getInstance()
                }
                
                if (app != null) {
                    FirebaseDataSource(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
                } else null
            } catch (e: Exception) {
                Log.e("SharISTApp", "Firebase init failed: ${e.message}")
                null
            }
        }

        val mockInstance = lazy { MockRemoteDataSource() }

        object : RemoteDataSource {
            private val delegate: RemoteDataSource
                get() {
                    val firebase = firebaseInstance.value
                    if (firebase != null) {
                        try {
                            if (FirebaseAuth.getInstance().currentUser != null && sessionManager.forceDemoMode) {
                                Log.i("SharISTApp", "Detected real user, disabling forceDemoMode.")
                                sessionManager.forceDemoMode = false
                            }
                        } catch (e: Exception) {
                            Log.e("SharISTApp", "Error checking current user", e)
                        }
                    }

                    val useFirebase = !sessionManager.forceDemoMode && firebase != null
                    return if (useFirebase) firebase else mockInstance.value
                }

            override val currentUid: String? get() = delegate.currentUid
            override suspend fun signIn(email: String, pass: String) = delegate.signIn(email, pass)
            override suspend fun register(email: String, pass: String) = delegate.register(email, pass)
            override fun signOut() = delegate.signOut()
            override suspend fun createUserProfile(user: User) = delegate.createUserProfile(user)
            override suspend fun getUser(uid: String) = delegate.getUser(uid)
            override suspend fun updateBalance(uid: String, delta: Double) = delegate.updateBalance(uid, delta)
            override suspend fun submitReview(review: Review) = delegate.submitReview(review)
            override fun observeReviewsForUser(userId: String) = delegate.observeReviewsForUser(userId)
            override fun observeRides(filter: RideFilter) = delegate.observeRides(filter)
            override suspend fun getRide(rideId: String) = delegate.getRide(rideId)
            override fun observeDriverRides(driverId: String) = delegate.observeDriverRides(driverId)
            override suspend fun createRide(ride: Ride) = delegate.createRide(ride)
            override suspend fun cancelRide(rideId: String) = delegate.cancelRide(rideId)
            override suspend fun completeRide(rideId: String) = delegate.completeRide(rideId)
            override suspend fun startRide(rideId: String) = delegate.startRide(rideId)
            override suspend fun decrementSeat(rideId: String) = delegate.decrementSeat(rideId)
            override suspend fun createBooking(booking: Booking) = delegate.createBooking(booking)
            override suspend fun updateBookingStatus(bookingId: String, status: BookingStatus) = delegate.updateBookingStatus(bookingId, status)
            override fun observePassengerBookings(passengerId: String) = delegate.observePassengerBookings(passengerId)
            override fun observeRideBookings(rideId: String) = delegate.observeRideBookings(rideId)
            override fun observeDriverBookings(driverId: String) = delegate.observeDriverBookings(driverId)
            override fun observeOpenRequests() = delegate.observeOpenRequests()
            override fun observePassengerRequests(passengerId: String) = delegate.observePassengerRequests(passengerId)
            override fun observeDriverRequests(driverId: String) = delegate.observeDriverRequests(driverId)
            override suspend fun createRequest(request: RideRequest) = delegate.createRequest(request)
            override suspend fun cancelRequest(requestId: String) = delegate.cancelRequest(requestId)
            override suspend fun completeRequest(requestId: String) = delegate.completeRequest(requestId)
            override suspend fun updateRequestStatus(requestId: String, status: RequestStatus) = delegate.updateRequestStatus(requestId, status)
            override suspend fun denyRequest(requestId: String, driverId: String) = delegate.denyRequest(requestId, driverId)
            override suspend fun acceptRequest(requestId: String, driverId: String, driverName: String, driverRating: Double) =
                delegate.acceptRequest(requestId, driverId, driverName, driverRating)

            override fun clearListeners() = delegate.clearListeners()
        }
    }

    val rideRepository by lazy { RideRepository(remoteDataSource, localDataSource, networkMonitor) }
    val requestRepository by lazy { RideRequestRepository(remoteDataSource, localDataSource, networkMonitor) }
    val userRepository by lazy { UserRepository(remoteDataSource) }

    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
        } catch (e: Exception) {
            Log.e("SharISTApp", "Firebase init failed", e)
        }

        val appScope = ProcessLifecycleOwner.get().lifecycleScope
        networkMonitor.connectionFlow
            .onEach { connectionType ->
                try {
                    if (connectionType == ConnectionType.WIFI || connectionType == ConnectionType.METERED) {
                        appScope.launch { rideRepository.syncPendingOperations() }
                    }
                } catch (e: Exception) { Log.e("SharISTApp", "Sync error", e) }
            }
            .launchIn(appScope)
    }
}
