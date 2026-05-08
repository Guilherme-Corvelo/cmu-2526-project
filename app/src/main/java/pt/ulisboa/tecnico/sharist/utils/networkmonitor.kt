package pt.ulisboa.tecnico.sharist.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ConnectionType { NONE, METERED, WIFI }

class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    val isConnected: Boolean
        get() {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    val isWifi: Boolean
        get() {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

    val isMetered: Boolean get() = isConnected && !isWifi

    val connectionType: ConnectionType
        get() = when {
            !isConnected -> ConnectionType.NONE
            isWifi       -> ConnectionType.WIFI
            else         -> ConnectionType.METERED
        }

    /** Live Flow of connection changes — use in ViewModel or WorkManager trigger. */
    val connectionFlow: Flow<ConnectionType> = callbackFlow {
        trySend(connectionType)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network)  { trySend(connectionType) }
            override fun onLost(network: Network)       { trySend(ConnectionType.NONE) }
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) { trySend(connectionType) }
        }

        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
