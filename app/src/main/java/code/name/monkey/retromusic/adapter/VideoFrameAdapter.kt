package code.name.monkey.retromusic.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R

class VideoFrameAdapter(
    private var frames: MutableList<VideoFrame>,
    private val onItemClick: (Long) -> Unit,
    private val onAddClick: (() -> Unit)? = null,
    private val onRemoveClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class VideoFrame(val timestampMs: Long, val bitmap: Bitmap?, val timeLabel: String, val isAddButton: Boolean = false, val canRemove: Boolean = false)

    companion object {
        private const val TYPE_FRAME = 0
        private const val TYPE_ADD = 1
    }

    class FrameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFrame: ImageView = view.findViewById(R.id.ivFrame)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val btnRemove: ImageView = view.findViewById(R.id.btnRemove)
    }

    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFrame: ImageView = view.findViewById(R.id.ivFrame)
        val btnRemove: ImageView = view.findViewById(R.id.btnRemove)
    }

    override fun getItemViewType(position: Int): Int {
        return if (frames[position].isAddButton) TYPE_ADD else TYPE_FRAME
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_frame, parent, false)
        return if (viewType == TYPE_ADD) AddViewHolder(view) else FrameViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val frame = frames[position]
        if (holder is FrameViewHolder) {
            holder.ivFrame.setImageBitmap(frame.bitmap)
            holder.tvTime.text = frame.timeLabel
            holder.itemView.setOnClickListener { onItemClick(frame.timestampMs) }
            
            if (frame.canRemove) {
                holder.btnRemove.visibility = View.VISIBLE
                holder.btnRemove.setOnClickListener { onRemoveClick?.invoke(holder.bindingAdapterPosition) }
            } else {
                holder.btnRemove.visibility = View.GONE
            }
        } else if (holder is AddViewHolder) {
            holder.ivFrame.setImageResource(R.drawable.ic_playlist_add)
            holder.ivFrame.scaleType = ImageView.ScaleType.CENTER_INSIDE
            holder.btnRemove.visibility = View.GONE
            holder.itemView.setOnClickListener { onAddClick?.invoke() }
        }
    }

    override fun getItemCount() = frames.size

    fun updateFrames(newFrames: List<VideoFrame>) {
        frames.clear()
        frames.addAll(newFrames)
        notifyDataSetChanged()
    }
}
