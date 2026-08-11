package code.name.monkey.retromusic.activities.tageditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.network.MBTrack

class MetadataReferenceAdapter(private var items: List<MBTrack>) :
    RecyclerView.Adapter<MetadataReferenceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInfo: TextView = view.findViewById(R.id.tvMbTrackInfo)
        val tvDuration: TextView = view.findViewById(R.id.tvMbDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_metadata_reference, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvInfo.text = "${item.position}. ${item.title}"
        
        val durationMs = item.length ?: 0
        if (durationMs > 0) {
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            holder.tvDuration.text = String.format("%02d:%02d", minutes, seconds)
        } else {
            holder.tvDuration.text = "--:--"
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<MBTrack>) {
        items = newItems
        notifyDataSetChanged()
    }
}
