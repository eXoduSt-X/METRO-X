package code.name.monkey.retromusic.fragments.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R

data class ToolButtonItem(
    val id: String,
    @DrawableRes val iconRes: Int,
    val label: String
)

class ToolButtonAdapter(
    private val items: List<ToolButtonItem>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<ToolButtonAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivToolIcon)
        val label: TextView = view.findViewById(R.id.tvToolLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tool_button, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.label.text = item.label
        holder.itemView.setOnClickListener { onClick(item.id) }
    }

    override fun getItemCount() = items.size
}