package code.name.monkey.retromusic.fragments.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.VideoView

/**
 * VideoView que ignora la relación de aspecto original del video y ocupa
 * exactamente el tamaño que le da su contenedor (estira/aplasta el video
 * si hace falta). Usado en fullscreen para que el área visible sea siempre
 * la misma sin importar la resolución del video cargado.
 */
class StretchVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VideoView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            View.MeasureSpec.getSize(widthMeasureSpec),
            View.MeasureSpec.getSize(heightMeasureSpec)
        )
    }
}
