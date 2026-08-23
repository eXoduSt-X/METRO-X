package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * Timeline continua de un único View: dibuja los thumbnails del filmstrip Y el
 * waveform del audio en el MISMO canvas, con una única función lineal
 * tiempo -> píxel (pxPerMs) como fuente de verdad.
 *
 * Por qué esto en vez de un RecyclerView + HorizontalScrollView separados:
 * - Filmstrip y waveform no pueden desincronizarse entre sí porque son
 *   literalmente el mismo contenido dibujado en el mismo lienzo, dentro del
 *   mismo scroll padre. No hay dos vistas que sincronizar a mano.
 * - Cada thumbnail se dibuja en la posición EXACTA de su timestamp real
 *   (x = timestampMs * pxPerMs), no en una posición aproximada por índice de
 *   ítem. Eso elimina cualquier desfase que se acumule con la posición.
 * - No hay snapping por ítems, no hay findViewByPosition(), no hay medición
 *   de anchos de celda: un solo número (pxPerMs) convierte scroll <-> tiempo
 *   en ambas direcciones, siempre exacto.
 */
class FilmstripTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Frame(val timestampMs: Long, val bitmap: Bitmap?)

    private var frames: List<Frame> = emptyList()
    private var waveform: Bitmap? = null
    private var durationMs: Long = 1L
    private var contentWidthPx: Int = 0

    /** Píxeles por milisegundo. Única fuente de verdad para convertir tiempo <-> scroll. */
    var pxPerMs: Float = 0f
        private set

    private val thumbWidthPx = (70 * resources.displayMetrics.density).toInt()
    private val thumbHeightPx = (60 * resources.displayMetrics.density).toInt()
    private val waveformHeightPx = (36 * resources.displayMetrics.density).toInt()

    private val bgPaint = Paint().apply { color = Color.parseColor("#222222") }
    private val waveformPaint = Paint().apply { alpha = 153 } // ~0.6 alpha, igual que antes
    private val srcRect = Rect()
    private val dstRect = Rect()

    /**
     * Arma la timeline completa. [totalWidthPx] define el ancho total (el "zoom"
     * general de la tira); pxPerMs se deriva de ese ancho y de la duración real.
     */
    fun setTimeline(frames: List<Frame>, durationMs: Long, totalWidthPx: Int) {
        this.frames = frames
        this.durationMs = durationMs.coerceAtLeast(1L)
        this.contentWidthPx = totalWidthPx.coerceAtLeast(1)
        this.pxPerMs = this.contentWidthPx.toFloat() / this.durationMs.toFloat()

        val lp = layoutParams
        if (lp != null) {
            lp.width = this.contentWidthPx
            layoutParams = lp
        } else {
            layoutParams = ViewGroup.LayoutParams(this.contentWidthPx, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        requestLayout()
        invalidate()
    }

    /** Actualiza solo el waveform (llega async, después de que el filmstrip ya se armó). */
    fun setWaveform(bitmap: Bitmap?) {
        waveform = bitmap
        invalidate()
    }

    fun timeMsToPx(timeMs: Long): Int = (timeMs * pxPerMs).roundToInt()

    fun pxToTimeMs(px: Int): Long {
        if (pxPerMs <= 0f) return 0L
        return (px / pxPerMs).toLong().coerceIn(0, durationMs)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (contentWidthPx > 0) contentWidthPx else MeasureSpec.getSize(widthMeasureSpec)
        val h = thumbHeightPx + waveformHeightPx
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), thumbHeightPx.toFloat(), bgPaint)

        for (frame in frames) {
            val bmp = frame.bitmap ?: continue
            val x = timeMsToPx(frame.timestampMs)
            if (x + thumbWidthPx < 0 || x > width) continue // fuera de rango visible, no dibujar
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(x, 0, x + thumbWidthPx, thumbHeightPx)
            canvas.drawBitmap(bmp, srcRect, dstRect, null)
        }

        waveform?.let { wf ->
            srcRect.set(0, 0, wf.width, wf.height)
            dstRect.set(0, thumbHeightPx, width, thumbHeightPx + waveformHeightPx)
            canvas.drawBitmap(wf, srcRect, dstRect, waveformPaint)
        }
    }
}
