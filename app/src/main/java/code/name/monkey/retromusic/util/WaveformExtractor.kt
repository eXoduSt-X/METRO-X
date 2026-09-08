package code.name.monkey.retromusic.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Extrae picos de amplitud reales de un archivo de audio, decodificando el PCM
 * con MediaCodec (sin necesidad de FFmpegKit) y reduciéndolo a un número fijo
 * de muestras (`resolution`) distribuidas uniformemente en toda la duración.
 *
 * Este array se calcula UNA sola vez por clip de audio. La tira de la línea de
 * tiempo (VideoFrameAdapter.bindWaveform) simplemente re-muestrea este array
 * según el zoom actual — así el waveform siempre corresponde al audio real y
 * el zoom es instantáneo (sin volver a decodificar nada).
 */
object WaveformExtractor {

    fun extract(context: Context, uri: Uri, resolution: Int = 600): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex == -1 || format == null) return FloatArray(resolution)

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L
            val bucketDurationUs = (durationUs / resolution).coerceAtLeast(1L)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val peaks = FloatArray(resolution)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var currentBucket = 0
            var bucketPeak = 0f

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val sampleSize = buffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0 && bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null) {
                            outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val bucket = (bufferInfo.presentationTimeUs / bucketDurationUs).toInt().coerceIn(0, resolution - 1)
                            if (bucket != currentBucket) {
                                peaks[currentBucket] = bucketPeak
                                // Si saltamos varios buckets sin datos (silencio/gap), quedan en 0f, correcto.
                                currentBucket = bucket
                                bucketPeak = 0f
                            }
                            while (outBuffer.remaining() >= 2) {
                                val sample = outBuffer.short
                                val v = abs(sample / 32768f)
                                if (v > bucketPeak) bucketPeak = v
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Nada que hacer: seguimos esperando buffers de salida.
                }
            }
            peaks[currentBucket] = bucketPeak
            codec.stop()
            codec.release()

            val maxPeak = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
            for (i in peaks.indices) peaks[i] = (peaks[i] / maxPeak).coerceIn(0f, 1f)
            return peaks
        } finally {
            extractor.release()
        }
    }
}
