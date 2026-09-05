package code.name.monkey.retromusic.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
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
    private val onRemoveClick: ((Int) -> Unit)? = null,
    private val onDurationChanged: ((Int, Long) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class VideoFrame(
        val timestampMs: Long,
        val bitmap: Bitmap?,
        val timeLabel: String,
        val isAddButton: Boolean = false,
        val canRemove: Boolean = false,
        var durationMs: Long = 3000,
        val canResize: Boolean = false
    )

    companion object {
        private const val TYPE_FRAME = 0
        private const val TYPE_ADD = 1
        private const val PX_PER_SECOND = 50 // Escala de la línea de tiempo
    }

    class FrameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFrame: ImageView = view.findViewById(R.id.ivFrame)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val btnRemove: ImageView = view.findViewById(R.id.btnRemove)
        val vHandle: View = view.findViewById(R.id.vResizeHandle)
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
            // Ajustar ancho según duración
            val params = holder.ivFrame.layoutParams
            params.width = (frame.durationMs / 1000f * PX_PER_SECOND * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(100)
            holder.ivFrame.layoutParams = params

            holder.ivFrame.setImageBitmap(frame.bitmap)
            holder.tvTime.text = if (frame.canResize) "${frame.durationMs / 1000f}s" else frame.timeLabel
            holder.itemView.setOnClickListener { onItemClick(frame.timestampMs) }

            if (frame.canRemove) {
                holder.btnRemove.visibility = View.VISIBLE
                holder.btnRemove.setOnClickListener { onRemoveClick?.invoke(holder.bindingAdapterPosition) }
            } else {
                holder.btnRemove.visibility = View.GONE
            }

            if (frame.canResize) {
                holder.vHandle.visibility = View.VISIBLE
                setupResizeTouch(holder, position)
            } else {
                holder.vHandle.visibility = View.GONE
            }

        } else if (holder is AddViewHolder) {
            val params = holder.ivFrame.layoutParams
            params.width = (60 * holder.itemView.resources.displayMetrics.density).toInt()
            holder.ivFrame.layoutParams = params
            holder.ivFrame.setImageResource(R.drawable.ic_playlist_add)
            holder.ivFrame.scaleType = ImageView.ScaleType.CENTER_INSIDE
            holder.btnRemove.visibility = View.GONE
            holder.itemView.setOnClickListener { onAddClick?.invoke() }
        }
    }

    private fun setupResizeTouch(holder: FrameViewHolder, position: Int) {
        var startX = 0f
        var startDuration = 0L
        val density = holder.itemView.resources.displayMetrics.density

        holder.vHandle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startDuration = frames[position].durationMs
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    val deltaMs = (deltaX / (PX_PER_SECOND * density) * 1000).toLong()
                    val newDuration = (startDuration + deltaMs).coerceIn(500, 30000) // 0.5s a 30s
                    
                    if (newDuration != frames[position].durationMs) {
                        frames[position].durationMs = newDuration
                        // Actualizar UI localmente para fluidez
                        val params = holder.ivFrame.layoutParams
                        params.width = (newDuration / 1000f * PX_PER_SECOND * density).toInt()
                        holder.ivFrame.layoutParams = params
                        holder.tvTime.text = "${newDuration / 1000f}s"
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDurationChanged?.invoke(position, frames[position].durationMs)
                }
            }
            true
        }
    }

    override fun getItemCount() = frames.size

    fun contentItemCount(): Int = frames.count { !it.isAddButton }

    fun updateFrames(newFrames: List<VideoFrame>) {
        frames.clear()
        frames.addAll(newFrames)
        notifyDataSetChanged()
    }
}
