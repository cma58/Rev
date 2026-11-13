package app.rev360

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Writes a file the camera handed us into the phone's own Pictures folder.
 *
 * Goes through MediaStore, so it needs no storage permission on Android 10
 * and later, and the photo shows up in the gallery straight away.
 */
object MediaSaver {

    data class Target(val stream: OutputStream, val finish: () -> Unit, val describe: String)

    fun open(context: Context, name: String, isVideo: Boolean): Target {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Rev360")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw OscException("Kon $name niet aanmaken in de galerij")
        val stream = resolver.openOutputStream(uri)
            ?: throw OscException("Kon niet schrijven naar $name")
        return Target(
            stream = stream,
            finish = {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            },
            describe = "$folder/Rev360/$name",
        )
    }
}
