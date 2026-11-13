package app.rev360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.rev360.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var views: ActivityMainBinding
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var intervalJob: Job? = null

    private var askForDiagnose = false

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val forDiagnose = askForDiagnose
            askForDiagnose = false
            if (granted) {
                if (forDiagnose) {
                    startActivity(Intent(this, DiagnoseActivity::class.java))
                    return@registerForActivityResult
                }
                connectToAccessPoint()
            } else {
                log("Toestemming geweigerd. Zonder die toestemming mag de app geen " +
                    "netwerk van de camera aanvragen. Je kunt wel handmatig op het " +
                    "wifi van de camera gaan en daarna de tweede knop gebruiken.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)

        views.ssid.setText(PREFILLED_SSID)

        views.connectAp.setOnClickListener {
            val permission = nearbyPermission()
            if (ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                connectToAccessPoint()
            } else {
                askPermission.launch(permission)
            }
        }

        views.useCurrentWifi.setOnClickListener { useCurrentWifi() }
        views.refreshState.setOnClickListener { readState() }
        views.shutter.setOnClickListener { takeOne() }
        views.intervalToggle.setOnClickListener { toggleInterval() }
        views.openGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        // The diagnosis needs the same nearby-devices permission as the
        // Wi-Fi Direct read it performs, so ask before opening it.
        views.openDiagnose.setOnClickListener {
            val permission = nearbyPermission()
            if (ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                askForDiagnose = true
                askPermission.launch(permission)
            } else {
                startActivity(Intent(this, DiagnoseActivity::class.java))
            }
        }

        log("Klaar. Zet de camera in Street View-modus en verbind.")
    }

    override fun onDestroy() {
        intervalJob?.cancel()
        super.onDestroy()
    }

    private fun nearbyPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun log(line: String) {
        val stamp = clock.format(Date())
        views.log.text = buildString {
            append(views.log.text)
            if (views.log.text.isNotEmpty()) append('\n')
            append(stamp).append("  ").append(line)
        }
    }

    private fun connectToAccessPoint() {
        val ssid = views.ssid.text.toString().trim()
        if (ssid.isEmpty()) {
            log("Vul eerst de netwerknaam van de camera in.")
            return
        }
        val passphrase = views.passphrase.text.toString().trim().ifEmpty { null }
        log("Vraag verbinding met \"$ssid\"…")
        views.connectAp.isEnabled = false

        val link = CameraLink(this)
        Session.link = link
        link.requestCameraAp(ssid, passphrase) { result ->
            views.connectAp.isEnabled = true
            result.fold(
                onSuccess = { network ->
                    log("Verbonden met \"$ssid\".")
                    onNetwork(network, link)
                },
                onFailure = { error ->
                    log("Mislukt: ${error.message}")
                },
            )
        }
    }

    private fun useCurrentWifi() {
        val link = Session.link ?: CameraLink(this).also { Session.link = it }
        val network = link.currentWifi()
        if (network == null) {
            log("De telefoon zit op geen enkel wifi-netwerk.")
            return
        }
        log("Gebruik het wifi waar de telefoon nu op zit.")
        onNetwork(network, link)
    }

    private fun onNetwork(network: Network, link: CameraLink) {
        val address = link.cameraAddress(network)
        if (address == null) {
            log("Kon het adres van de camera niet bepalen uit dit netwerk.")
            return
        }
        Session.network = network
        Session.host = address
        log("Camera-adres: $address")
        handshake()
    }

    /** Ask the camera who it is, then read its battery and card. */
    private fun handshake() {
        val client = Session.client ?: return
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { client.info() }
                Session.info = info
                views.cameraInfo.text = buildString {
                    append(info.manufacturer).append(' ').append(info.model).append('\n')
                    append("Firmware ").append(info.firmware).append('\n')
                    append("OSC-niveau ").append(info.apiLevels.joinToString(", "))
                    if (info.needsSession) append("  (gebruikt sessies)")
                }
                log("Camera meldt zich: ${info.model}, firmware ${info.firmware}.")
                log("Ondersteunde commando's: ${info.supportedCommands.size}")
                views.refreshState.isEnabled = true
                views.shutter.isEnabled = true
                views.intervalToggle.isEnabled = true
                views.openGallery.isEnabled = true
                readState()
            } catch (e: Exception) {
                log("Handdruk mislukte: ${e.message}")
                (e as? OscException)?.detail?.let { log("Antwoord: $it") }
            }
        }
    }

    private fun readState() {
        val client = Session.client ?: return
        lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { client.state() }
                views.cameraInfo.text = buildString {
                    append(views.cameraInfo.text.toString().lineSequence().take(3).joinToString("\n"))
                    append('\n').append(describeState(state))
                }
                log("Status gelezen.")
            } catch (e: Exception) {
                log("Status lezen mislukte: ${e.message}")
            }
        }
    }

    private fun describeState(state: JSONObject): String {
        val battery = state.opt("batteryLevel")
        val remaining = state.opt("remainingPictures") ?: state.opt("_remainingPictures")
        val space = state.opt("remainingSpace") ?: state.opt("_remainingSpace")
        return buildString {
            if (battery != null) {
                val percent = (battery.toString().toDoubleOrNull() ?: 0.0)
                append("Accu ")
                append(if (percent <= 1.0) "${(percent * 100).toInt()}%" else "${percent.toInt()}%")
            }
            if (space != null) {
                val bytes = space.toString().toDoubleOrNull() ?: 0.0
                if (isNotEmpty()) append(" · ")
                append("Vrij ").append(String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0))
            }
            if (remaining != null) {
                if (isNotEmpty()) append(" · ")
                append(remaining).append(" foto's")
            }
            if (isEmpty()) append("Camera gaf geen accu- of opslaggegevens.")
        }
    }

    private fun takeOne() {
        val client = Session.client ?: return
        val info = Session.info ?: return
        views.shutter.isEnabled = false
        lifecycleScope.launch {
            try {
                val url = withContext(Dispatchers.IO) { client.takePicture(info) }
                if (url != null) log("Foto gemaakt: $url") else log("Foto gemaakt.")
            } catch (e: Exception) {
                log("Sluiter mislukte: ${e.message}")
                (e as? OscException)?.detail?.let { log("Antwoord: $it") }
            } finally {
                views.shutter.isEnabled = true
            }
        }
    }

    /**
     * Timelapse, driven from the phone. The camera has its own interval mode,
     * but it is not reachable over OSC, so we simply keep pressing the shutter.
     */
    private fun toggleInterval() {
        intervalJob?.let {
            it.cancel()
            intervalJob = null
            views.intervalToggle.text = "Timelapse"
            log("Timelapse gestopt.")
            return
        }
        val seconds = views.intervalSeconds.text.toString().toIntOrNull()
        if (seconds == null || seconds < 2) {
            log("Kies een interval van minstens 2 seconden.")
            return
        }
        val client = Session.client ?: return
        val info = Session.info ?: return
        views.intervalToggle.text = "Stop"
        log("Timelapse gestart, elke $seconds seconden.")
        intervalJob = lifecycleScope.launch {
            var count = 0
            while (isActive) {
                try {
                    withContext(Dispatchers.IO) { client.takePicture(info) }
                    count++
                    log("Timelapse: foto $count")
                } catch (e: Exception) {
                    log("Timelapse stopte: ${e.message}")
                    break
                }
                delay(seconds * 1000L)
            }
            views.intervalToggle.text = "Timelapse"
            intervalJob = null
        }
    }

    private companion object {
        /** What the 2016 camera calls its Street View network, as a starting point. */
        const val PREFILLED_SSID = ""
    }
}
