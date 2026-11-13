package app.rev360

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One file on the camera's card. */
data class MediaEntry(
    val name: String,
    val fileUrl: String,
    val size: Long,
    val taken: String?,
    val width: Int,
    val height: Int,
) {
    val isVideo: Boolean get() = name.endsWith(".mp4", true) || name.endsWith(".mov", true)
}

/** What /osc/info told us about the camera. */
data class CameraInfo(
    val manufacturer: String,
    val model: String,
    val serial: String,
    val firmware: String,
    val apiLevels: List<Int>,
    val supportedCommands: List<String>,
) {
    /** Level 2 drops sessions; level 1 needs one around every call. */
    val needsSession: Boolean get() = !apiLevels.contains(2)
    fun supports(command: String) = supportedCommands.any { it.endsWith(command) }
}

class OscException(message: String, val detail: String? = null) : Exception(message)

/**
 * Open Spherical Camera client, spoken over the camera's own Wi-Fi.
 *
 * Every request goes through the [Network] we were handed, not the phone's
 * default route: the camera's access point has no internet, so Android keeps
 * mobile data as the default and a plain connection would never reach it.
 */
class OscClient(
    private val host: String,
    private val network: Network?,
) {
    private var sessionId: String? = null

    private fun open(path: String): HttpURLConnection {
        val url = URL("http://$host$path")
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 20000
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream: InputStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return ""
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun post(path: String, body: JSONObject?): JSONObject {
        val connection = open(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val payload = (body ?: JSONObject()).toString().toByteArray()
        connection.setRequestProperty("Content-Length", payload.size.toString())
        connection.outputStream.use { it.write(payload) }
        val text = readBody(connection)
        val code = connection.responseCode
        connection.disconnect()
        if (text.isBlank()) throw OscException("De camera gaf een leeg antwoord op $path (HTTP $code)")
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw OscException("Onleesbaar antwoord op $path (HTTP $code)", text.take(400))
        }
        if (code !in 200..299) {
            val error = json.optJSONObject("error")
            throw OscException(
                error?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP $code op $path",
                error?.optString("code"),
            )
        }
        return json
    }

    fun info(): CameraInfo {
        val connection = open("/osc/info")
        connection.requestMethod = "GET"
        val text = readBody(connection)
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299 || text.isBlank()) {
            throw OscException("/osc/info gaf HTTP $code", text.take(400))
        }
        val json = JSONObject(text)
        val levels = mutableListOf<Int>()
        json.optJSONArray("apiLevel")?.let { array ->
            for (i in 0 until array.length()) levels.add(array.optInt(i))
        }
        if (levels.isEmpty()) levels.add(1)
        val commands = mutableListOf<String>()
        json.optJSONArray("api")?.let { array ->
            for (i in 0 until array.length()) commands.add(array.optString(i))
        }
        return CameraInfo(
            manufacturer = json.optString("manufacturer", "onbekend"),
            model = json.optString("model", "onbekend"),
            serial = json.optString("serialNumber", ""),
            firmware = json.optString("firmwareVersion", ""),
            apiLevels = levels,
            supportedCommands = commands,
        )
    }

    /** Battery and storage, straight from the camera. */
    fun state(): JSONObject = post("/osc/state", null).optJSONObject("state") ?: JSONObject()

    private fun startSession(): String {
        val result = execute("camera.startSession", JSONObject().put("timeout", 180), session = false)
        return result.optString("sessionId").also { sessionId = it }
    }

    private fun ensureSession(info: CameraInfo): String? {
        if (!info.needsSession) return null
        sessionId?.let { return it }
        return startSession()
    }

    fun closeSession() {
        val id = sessionId ?: return
        runCatching { execute("camera.closeSession", JSONObject().put("sessionId", id), session = false) }
        sessionId = null
    }

    /**
     * Run one command and wait for it. A camera may answer `inProgress` and
     * make us poll; `camera.takePicture` on the Gear 360 does exactly that
     * while the shutter and the save are still running.
     */
    fun execute(name: String, parameters: JSONObject?, session: Boolean = true, info: CameraInfo? = null): JSONObject {
        val params = parameters ?: JSONObject()
        if (session && info != null) {
            ensureSession(info)?.let { params.put("sessionId", it) }
        }
        val body = JSONObject().put("name", name)
        if (params.length() > 0) body.put("parameters", params)
        var answer = post("/osc/commands/execute", body)
        var guard = 0
        while (answer.optString("state") == "inProgress" && guard < 120) {
            val id = answer.optString("id").ifBlank { answer.optString("name") }
            Thread.sleep(500)
            answer = post("/osc/commands/status", JSONObject().put("id", id))
            guard++
        }
        when (answer.optString("state")) {
            "error" -> {
                val error = answer.optJSONObject("error")
                throw OscException(
                    error?.optString("message")?.takeIf { it.isNotBlank() } ?: "$name mislukte",
                    error?.optString("code"),
                )
            }
            "inProgress" -> throw OscException("$name was na een minuut nog niet klaar")
        }
        return answer.optJSONObject("results") ?: JSONObject()
    }

    /** Returns the URL of the photo the camera just wrote, when it reports one. */
    fun takePicture(info: CameraInfo): String? {
        val results = execute("camera.takePicture", JSONObject(), info = info)
        results.optString("fileUrl").takeIf { it.isNotBlank() }?.let { return it }
        val list = results.optJSONArray("fileUrls")
        if (list != null && list.length() > 0) return list.optString(0)
        return null
    }

    fun listMedia(info: CameraInfo, limit: Int = 60): List<MediaEntry> {
        // Level 2 calls it listFiles; level 1 cameras only know listImages.
        val useListFiles = info.supports("camera.listFiles")
        val params = if (useListFiles) {
            JSONObject()
                .put("fileType", "all")
                .put("entryCount", limit)
                .put("maxThumbSize", JSONObject.NULL)
                .put("startPosition", 0)
        } else {
            JSONObject()
                .put("entryCount", limit)
                .put("maxSize", JSONObject.NULL)
                .put("includeThumb", false)
        }
        val command = if (useListFiles) "camera.listFiles" else "camera.listImages"
        val results = execute(command, params, info = info)
        val entries: JSONArray = results.optJSONArray("entries")
            ?: results.optJSONArray("results")
            ?: return emptyList()
        val out = mutableListOf<MediaEntry>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val url = e.optString("fileUrl").ifBlank { e.optString("uri") }
            if (url.isBlank()) continue
            out.add(
                MediaEntry(
                    name = e.optString("name").ifBlank { url.substringAfterLast('/') },
                    fileUrl = url,
                    size = e.optLong("size", e.optLong("_size", 0L)),
                    taken = e.optString("dateTimeZone").ifBlank { e.optString("dateTime") }.takeIf { it.isNotBlank() },
                    width = e.optInt("width", 0),
                    height = e.optInt("height", 0),
                )
            )
        }
        return out
    }

    fun getOptions(info: CameraInfo, names: List<String>): JSONObject {
        val array = JSONArray().apply { names.forEach { put(it) } }
        val results = execute("camera.getOptions", JSONObject().put("optionNames", array), info = info)
        return results.optJSONObject("options") ?: JSONObject()
    }

    fun setOptions(info: CameraInfo, options: JSONObject) {
        execute("camera.setOptions", JSONObject().put("options", options), info = info)
    }

    fun delete(info: CameraInfo, fileUrls: List<String>) {
        val array = JSONArray().apply { fileUrls.forEach { put(it) } }
        val supportsPlural = info.supports("camera.delete")
        if (supportsPlural) {
            execute("camera.delete", JSONObject().put("fileUrls", array), info = info)
        } else {
            fileUrls.forEach { execute("camera.delete", JSONObject().put("fileUri", it), info = info) }
        }
    }

    /** Streams one file off the camera. The URL comes from the camera itself. */
    fun download(fileUrl: String, sink: java.io.OutputStream, onProgress: ((Long) -> Unit)? = null): Long {
        val url = URL(fileUrl)
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 60000
        try {
            if (connection.responseCode !in 200..299) {
                throw OscException("Downloaden gaf HTTP ${connection.responseCode}")
            }
            var total = 0L
            connection.inputStream.use { input ->
                BufferedOutputStream(sink).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress?.invoke(total)
                    }
                    output.flush()
                }
            }
            return total
        } finally {
            connection.disconnect()
        }
    }
}
