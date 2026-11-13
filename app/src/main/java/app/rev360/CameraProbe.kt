package app.rev360

import android.net.Network
import java.net.InetSocketAddress
import java.net.URL

/**
 * Finds out what the camera actually serves, instead of assuming.
 *
 * Evidence from the phone says the Gear 360 brings up more than one kind of
 * access point, and that at least one of them carries a Samsung UPnP media
 * server on port 7676 rather than the Open Spherical Camera API. Rather than
 * guess which, knock on every door and report what answers.
 */
object CameraProbe {

    /** Ports worth knocking on, and why. */
    val PORTS = linkedMapOf(
        80 to "HTTP / OSC",
        8080 to "HTTP (alternatief)",
        7676 to "Samsung UPnP mediaserver",
        8090 to "HTTP (alternatief)",
        554 to "RTSP",
        8554 to "RTSP (alternatief)",
        15740 to "PTP/IP",
    )

    data class Finding(val port: Int, val label: String, val detail: String)

    /** A TCP connect is enough to tell open from closed. */
    private fun isOpen(network: Network?, host: String, port: Int, timeoutMs: Int = 1200): Boolean =
        try {
            val socket = network?.socketFactory?.createSocket() ?: java.net.Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }

    /** First line of whatever an HTTP-ish port returns, bounded. */
    private fun peek(network: Network?, host: String, port: Int, path: String): String? = try {
        val url = URL("http://$host:$port$path")
        val connection = (network?.openConnection(url) ?: url.openConnection())
                as java.net.HttpURLConnection
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        "HTTP $code" + if (body.isNotBlank()) " · ${body.replace(Regex("\\s+"), " ").take(160)}" else ""
    } catch (e: Exception) {
        null
    }

    /**
     * Walks the ports and asks each open one a question that fits it.
     * Returns a readable report, line by line, for the log.
     */
    fun inspect(network: Network?, host: String, onLine: (String) -> Unit): List<Finding> {
        val findings = mutableListOf<Finding>()
        onLine("Poorten aftasten op $host …")
        for ((port, label) in PORTS) {
            if (!isOpen(network, host, port)) continue
            onLine("  $port OPEN ($label)")

            val probes = when (port) {
                7676 -> listOf("/smp_2_", "/dmr/SamsungMRDesc.xml", "/")
                554, 8554 -> emptyList()
                15740 -> emptyList()
                else -> listOf("/osc/info", "/")
            }
            var described = "open"
            for (path in probes) {
                val answer = peek(network, host, port, path) ?: continue
                onLine("    $path → $answer")
                described = "$path → $answer"
                if (path == "/osc/info" && answer.startsWith("HTTP 2")) break
            }
            findings.add(Finding(port, label, described))
        }
        if (findings.isEmpty()) {
            onLine("  Geen enkele poort open. Staat het wifi van de camera nog aan?")
        }
        return findings
    }
}
