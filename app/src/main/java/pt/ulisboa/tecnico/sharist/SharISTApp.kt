package pt.ulisboa.tecnico.sharist

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
import pt.ulisboa.tecnico.sharist.data.remote.FirebaseDataSource
import pt.ulisboa.tecnico.sharist.data.remote.MockRemoteDataSource
import pt.ulisboa.tecnico.sharist.data.remote.RemoteDataSource
import pt.ulisboa.tecnico.sharist.data.repository.RideRepository
import pt.ulisboa.tecnico.sharist.data.repository.UserRepository
import pt.ulisboa.tecnico.sharist.utils.ConnectionType
import pt.ulisboa.tecnico.sharist.utils.NetworkMonitor

class SharISTApp : Application() {

    val networkMonitor by lazy { NetworkMonitor(this) }

    private val db by lazy { SharISTDatabase.getInstance(this) }

    private val localDataSource by lazy { LocalDataSource(db) }

    val remoteDataSource: RemoteDataSource by lazy {
        // Check if google-services.json was applied by looking for the generated resource
        val hasConfig = try {
            val resId = resources.getIdentifier("google_app_id", "string", packageName)
            resId != 0
        } catch (e: Exception) {
            false
        }

        if (hasConfig) {
            try {
                FirebaseApp.initializeApp(this)
                FirebaseDataSource(
                    auth = FirebaseAuth.getInstance(),
                    db = FirebaseFirestore.getInstance()
                )
            } catch (e: Throwable) {
                Log.e("SharISTApp", "Firebase initialization failed even with config", e)
                MockRemoteDataSource()
            }
        } else {
            Log.w("SharISTApp", "google-services.json not found. Using MockRemoteDataSource.")
            MockRemoteDataSource()
        }
    }

    val rideRepository by lazy {
        RideRepository(remoteDataSource, localDataSource, networkMonitor)
    }
    val userRepository by lazy {
        UserRepository(remoteDataSource)
    }

    override fun onCreate() {
        super.onCreate()

        val appScope = ProcessLifecycleOwner.get().lifecycleScope

        networkMonitor.connectionFlow
            .onEach { connectionType ->
                try {
                    when (connectionType) {
                        ConnectionType.WIFI -> {
                            appScope.launch { rideRepository.syncPendingOperations() }
                            appScope.launch { rideRepository.preloadOnWifi() }
                        }
                        ConnectionType.METERED -> {
                            appScope.launch { rideRepository.syncPendingOperations() }
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.e("SharISTApp", "Error during sync", e)
                }
            }
            .launchIn(appScope)
    }
}
