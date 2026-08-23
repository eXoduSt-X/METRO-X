package code.name.monkey.retromusic.views

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * HorizontalScrollView que reporta cada cambio de scrollX vía [onScrollXChanged],
 * sin depender de setOnScrollChangeListener (API 23+). Es la única fuente de
 * eventos de scroll para el filmstrip: no hay un segundo listener ni un segundo
 * cálculo de posición compitiendo con este.
 */
class ScrubHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    var onScrollXChanged: ((scrollX: Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollXChanged?.invoke(l)
    }
}
