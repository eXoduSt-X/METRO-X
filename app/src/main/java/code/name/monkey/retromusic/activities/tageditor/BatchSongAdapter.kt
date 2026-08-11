package code.name.monkey.retromusic.activities.tageditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import androidx.documentfile.provider.DocumentFile
import java.util.Collections

data class BatchSongItem(
    val document: DocumentFile,
    var pendingTags: TagFields? = null,
    val durationText: String? = null
)

class BatchSongAdapter(private var items: MutableList<BatchSongItem>) :
    RecyclerView.Adapter<BatchSongAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFilename: TextView = view.findViewById(R.id.tvFilename)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val ivDragHandle: ImageView = view.findViewById(R.id.ivDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvFilename.text = item.document.name
        
        val durationInfo = if (!item.durationText.isNullOrEmpty()) " [${item.durationText}]" else ""
        
        val tags = item.pendingTags
        if (tags != null) {
            val trackInfo = if (!tags.track.isNullOrEmpty()) "${tags.track}. " else ""
            holder.tvDetails.text = "$trackInfo${tags.title ?: ""}$durationInfo"
            holder.tvDetails.setTextColor(0xFF00BFFF.toInt())
        } else {
            holder.tvDetails.text = "No tags matched$durationInfo"
            holder.tvDetails.setTextColor(0xFFAAAAAA.toInt())
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<BatchSongItem>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getItems(): List<BatchSongItem> = items
}
