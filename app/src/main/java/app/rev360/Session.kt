package app.rev360

import android.net.Network

/**
 * The live connection, shared between screens.
 *
 * Deliberately a singleton: the camera's network is granted to the whole
 * process, and handing a [Network] through an Intent is not possible.
 */
object Session {
    var network: Network? = null
    var host: String? = null
    var info: CameraInfo? = null
    var link: CameraLink? = null

    val client: OscClient?
        get() = host?.let { OscClient(it, network) }

    fun clear() {
        link?.release()
        link = null
        network = null
        host = null
        info = null
    }
}
