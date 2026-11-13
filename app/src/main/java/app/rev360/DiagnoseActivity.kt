package app.rev360

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import app.rev360.databinding.ActivityDiagnoseBinding

/**
 * Finds out why live view fails, on the phone that actually has the problem.
 *
 * The suspicion is the Wi-Fi Direct device address. Samsung's app has to hand
 * the phone's own P2P address to the camera so the camera can bring the video
 * link up. Since Android 10 a non-privileged app is given the placeholder
 * 02:00:00:00:00:00 instead of the real one, which makes that handshake
 * impossible. This screen reads that address and reports it, so the guess
 * becomes a measurement.
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var views: ActivityDiagnoseBinding
    private val report = StringBuilder()

    private var p2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityDiagnoseBinding.inflate(layoutInflater)
        setContentView(views.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        views.copy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Rev360 diagnose", report.toString()))
            views.copy.text = "Gekopieerd"
        }

        run()
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun line(text: String) {
        report.append(text).append('\n')
        views.output.text = report.toString()
    }

    private fun run() {
        report.clear()
        line("== Rev360 diagnose ==")
        line("Telefoon: ${Build.MANUFACTURER} ${Build.MODEL}")
        line("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("")

        checkLocation()
        line("")
        checkWifi()
        line("")
        checkInstalledApps()
        line("")
        checkP2pAddress()
    }

    private fun checkLocation() {
        line("-- Locatie --")
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val on = manager.isLocationEnabled
        line("Locatie systeembreed: ${if (on) "AAN" else "UIT"}")
        if (!on) {
            line("  !! Wi-Fi Direct-ontdekking werkt niet met locatie uit.")
            line("     Dit alleen al kan live beeld blokkeren.")
        }
    }

    private fun checkWifi() {
        line("-- Wi-Fi --")
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        line("Wi-Fi: ${if (wifi.isWifiEnabled) "AAN" else "UIT"}")
        line("Wi-Fi Direct ondersteund: ${
            packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        }")
    }

    /** Which of Samsung's pieces are present, and what the official app may do. */
    private fun checkInstalledApps() {
        line("-- Samsung-onderdelen --")
        val interesting = listOf(
            "com.samsung.accessory" to "Samsung Accessory Framework",
            "com.samsung.android.samsunggear360manager" to "Gear 360 Manager (2016)",
            "com.samsung.android.gear360manager" to "Gear 360 (New, 2017)",
            "com.samsung.android.app.watchmanager" to "Galaxy Wearable",
        )
        for ((id, label) in interesting) {
            val info = packageInfo(id)
            if (info == null) {
                line("$label: niet gevonden")
                continue
            }
            line("$label: v${info.versionName}")
            info.applicationInfo?.let { line("  targetSdk ${it.targetSdkVersion}") }
            reportPermissions(info)
        }
    }

    private fun packageInfo(id: String): PackageInfo? = runCatching {
        packageManager.getPackageInfo(id, PackageManager.GET_PERMISSIONS)
    }.getOrNull()

    /** Shows whether the official app actually holds what live view needs. */
    private fun reportPermissions(info: PackageInfo) {
        val wanted = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
        )
        val requested = info.requestedPermissions ?: return
        val flags = info.requestedPermissionsFlags
        for (i in requested.indices) {
            val name = requested[i]
            if (name !in wanted) continue
            val granted = flags != null && i < flags.size &&
                (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            line("  ${name.removePrefix("android.permission.")}: ${if (granted) "TOEGEKEND" else "GEWEIGERD"}")
        }
    }

    /**
     * The measurement that matters: what address does this phone hand out for
     * its own Wi-Fi Direct interface?
     */
    private fun checkP2pAddress() {
        line("-- Wi-Fi Direct adres --")
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            line("Wi-Fi Direct niet beschikbaar op dit toestel.")
            return
        }
        p2pManager = manager
        channel = manager.initialize(this, Looper.getMainLooper(), null)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            line("Android ouder dan 10: het echte adres is nog leesbaar.")
            return
        }

        line("Opvragen…")
        try {
            manager.requestDeviceInfo(channel!!) { device: WifiP2pDevice? ->
                if (device == null) {
                    line("Geen antwoord. Meestal betekent dat: toestemming")
                    line("'Apparaten in de buurt' of locatie ontbreekt voor DEZE app.")
                    return@requestDeviceInfo
                }
                val address = device.deviceAddress ?: "(leeg)"
                line("Naam: ${device.deviceName}")
                line("Adres: $address")
                if (address == PLACEHOLDER || address.startsWith("02:00:00:00")) {
                    line("")
                    line(">> DIT IS DE PLACEHOLDER.")
                    line("   Android geeft deze app niet het echte P2P-adres.")
                    line("   Precies dit maakt live beeld onmogelijk: de camera")
                    line("   krijgt een adres waarop niets te bereiken is.")
                } else {
                    line("")
                    line(">> Dit lijkt een ECHT adres.")
                    line("   Dan is de placeholder niet de oorzaak en zit het")
                    line("   probleem elders. Stuur dit rapport door.")
                }
            }
        } catch (e: SecurityException) {
            line("Geweigerd: ${e.message}")
            line("Geef deze app 'Apparaten in de buurt' en probeer opnieuw.")
        }
    }

    private companion object {
        const val PLACEHOLDER = "02:00:00:00:00:00"
    }
}
