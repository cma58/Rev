package app.rev360

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.rev360.databinding.ActivityGalleryBinding
import app.rev360.databinding.ItemMediaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    private lateinit var views: ActivityGalleryBinding
    private val entries = mutableListOf<MediaEntry>()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(views.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = Adapter()
        views.list.layoutManager = LinearLayoutManager(this)
        views.list.adapter = adapter
        views.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        views.refresh.setOnRefreshListener { load() }

        load()
    }

    private fun load() {
        val client = Session.client
        val info = Session.info
        if (client == null || info == null) {
            views.summary.text = "Niet verbonden met een camera."
            views.refresh.isRefreshing = false
            return
        }
        views.refresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { client.listMedia(info) }
                entries.clear()
                entries.addAll(found)
                adapter.notifyDataSetChanged()
                views.summary.text = if (found.isEmpty()) {
                    "De camera meldt geen bestanden."
                } else {
                    val bytes = found.sumOf { it.size }
                    "${found.size} bestanden · ${gigabytes(bytes)}\nTik om te downloaden, houd ingedrukt om te verwijderen."
                }
            } catch (e: Exception) {
                views.summary.text = "Ophalen mislukte: ${e.message}"
            } finally {
                views.refresh.isRefreshing = false
            }
        }
    }

    private fun gigabytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
        else -> "$bytes B"
    }

    private fun download(entry: MediaEntry) {
        val client = Session.client ?: return
        views.summary.text = "Download ${entry.name}…"
        lifecycleScope.launch {
            try {
                val where = withContext(Dispatchers.IO) {
                    val target = MediaSaver.open(this@GalleryActivity, entry.name, entry.isVideo)
                    client.download(entry.fileUrl, target.stream)
                    target.finish()
                    target.describe
                }
                views.summary.text = "Opgeslagen in $where"
            } catch (e: Exception) {
                views.summary.text = "Download mislukte: ${e.message}"
            }
        }
    }

    private fun confirmDelete(entry: MediaEntry) {
        AlertDialog.Builder(this)
            .setTitle("Verwijderen van de camera?")
            .setMessage("${entry.name} wordt van de kaart gewist. Dit kan niet ongedaan worden gemaakt.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verwijderen") { _, _ -> doDelete(entry) }
            .show()
    }

    private fun doDelete(entry: MediaEntry) {
        val client = Session.client ?: return
        val info = Session.info ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.delete(info, listOf(entry.fileUrl)) }
                views.summary.text = "${entry.name} verwijderd."
                load()
            } catch (e: Exception) {
                views.summary.text = "Verwijderen mislukte: ${e.message}"
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.binding.name.text = entry.name
            holder.binding.meta.text = buildString {
                append(if (entry.isVideo) "Video" else "Foto")
                append(" · ").append(gigabytes(entry.size))
                if (entry.width > 0) append(" · ").append(entry.width).append("×").append(entry.height)
                entry.taken?.let { append(" · ").append(it) }
            }
            // Thumbnails load lazily, and the tag guards against a recycled
            // row showing the picture of the row it used to be.
            holder.binding.thumb.setImageDrawable(null)
            holder.binding.thumb.tag = entry.fileUrl
            if (!entry.isVideo) {
                val cached = ImageLoader.cached(entry.fileUrl, THUMB_PX)
                if (cached != null) {
                    holder.binding.thumb.setImageBitmap(cached)
                } else {
                    lifecycleScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            ImageLoader.load(Session.network, entry.fileUrl, THUMB_PX)
                        }
                        if (bitmap != null && holder.binding.thumb.tag == entry.fileUrl) {
                            holder.binding.thumb.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            holder.binding.root.setOnClickListener { download(entry) }
            holder.binding.root.setOnLongClickListener {
                confirmDelete(entry)
                true
            }
        }
    }

    private inner class Holder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        /** Small enough to keep dozens in memory, big enough to recognise. */
        const val THUMB_PX = 256
    }
}
