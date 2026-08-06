package code.name.monkey.retromusic.fragments.home

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadVideoAdapter(
    private val videos: List<Pair<String, Uri>>, // Guardamos Nombre y Uri
    private val onItemClick: (Uri) -> Unit
) : RecyclerView.Adapter<DownloadVideoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (name, uri) = videos[position]
        holder.tvName.text = name
        holder.tvName.setOnClickListener { onItemClick(uri) }
    }

    override fun getItemCount() = videos.size
}
