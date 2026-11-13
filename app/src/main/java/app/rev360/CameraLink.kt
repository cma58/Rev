package app.rev360

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * Gets the phone onto the camera's own access point and keeps hold of that
 * [Network].
 *
 * The camera's network has no internet. Android therefore keeps mobile data as
 * the default route, and a plain `URL.openConnection()` would leave the phone
 * and never arrive. Two ways in:
 *
 *  - [requestCameraAp] asks Android for a scoped connection to one SSID. Other
 *    apps keep their mobile data; ours gets the camera. Needs no trip to
 *    Settings, and the system shows its own approval dialog.
 *  - [currentWifi] takes the Wi-Fi the phone is already on, for when you joined
 *    the camera from Settings yourself.
 */
class CameraLink(private val context: Context) {

    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    /** The Wi-Fi the phone is on right now, if any. */
    fun currentWifi(): Network? = manager.allNetworks.firstOrNull { network ->
        manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /**
     * The camera is the only other host on its network, so its address is the
     * gateway. Reading it beats hardcoding 192.168.107.1, which differs
     * between firmware versions.
     */
    fun cameraAddress(network: Network): String? {
        val properties: LinkProperties = manager.getLinkProperties(network) ?: return null
        properties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?.let { return it }
        // No default route on a network without internet: derive .1 from our own address.
        val own = properties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull() ?: return null
        val parts = own.hostAddress?.split('.') ?: return null
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.1"
    }

    /**
     * Ask Android for a scoped connection to the camera's access point.
     * [onResult] runs on the main thread, exactly once per call.
     */
    fun requestCameraAp(ssid: String, passphrase: String?, onResult: (Result<Network>) -> Unit) {
        release()
        val specifier = WifiNetworkSpecifier.Builder().apply {
            setSsid(ssid)
            if (!passphrase.isNullOrBlank()) setWpa2Passphrase(passphrase)
        }.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var settled = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (settled) return
                settled = true
                main.post { onResult(Result.success(network)) }
            }

            override fun onUnavailable() {
                if (settled) return
                settled = true
                main.post {
                    onResult(
                        Result.failure(
                            OscException(
                                "Geen verbinding met \"$ssid\". Staat de camera aan en in " +
                                    "Street View-modus, en is het wachtwoord juist?"
                            )
                        )
                    )
                }
            }

            override fun onLost(network: Network) {
                // Nothing to do: the request stays open and Android reconnects.
            }
        }
        callback = cb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.requestNetwork(request, cb, 40_000)
        } else {
            manager.requestNetwork(request, cb)
        }
    }

    /** Hands the camera's network back to the system. */
    fun release() {
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
    }
}
