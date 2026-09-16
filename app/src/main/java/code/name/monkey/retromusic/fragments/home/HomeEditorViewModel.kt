package code.name.monkey.retromusic.fragments.home

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Estado de progreso de una operación larga. totalMs == 0 significa "indeterminado".
 * Al terminar, el fragment recibe un EditorEvent en el SharedFlow.
 */
data class EditorProgress(val currentMs: Double, val totalMs: Long, val label: String? = null)

sealed class EditorEvent {
    data class Saved(val fileName: String, val mimeType: String) : EditorEvent()
    data class Error(val message: String) : EditorEvent()
    data class Info(val message: String) : EditorEvent()
    object Finished : EditorEvent()
}

class HomeEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _progress = MutableStateFlow<EditorProgress?>(null)
    val progress: StateFlow<EditorProgress?> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()
    // ---------------------------------------------------------------------
// Tipos de análisis de merge
// ---------------------------------------------------------------------
    enum class Orientation { PORTRAIT, LANDSCAPE, SQUARE }

    data class VideoMeta(
        val uri: Uri,
        val name: String,
        val width: Int,
        val height: Int,
        val orientation: Orientation,
        val fps: String?,
        val rotation: Int,
        val hasAudio: Boolean,
        /** firma cruda para comparar homogeneidad real (codec + dims exactas + rot) */
        val rawSignature: String?
    )

    data class MergeAnalysis(
        val metas: List<VideoMeta>,
        /** true si TODOS comparten exactamente la misma firma (c-copy seguro) */
        val canStreamCopy: Boolean,
        /** orientación mayoritaria */
        val majorityOrientation: Orientation,
        /** índices (0-based) de videos que NO coinciden con la mayoría */
        val mismatchedIndices: List<Int>
    )

    fun analyzeMergeCompatibility(uris: List<Uri>): MergeAnalysis {
        val metas = uris.map { uri ->
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(ctx(), uri)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                // Aplicar rotación para saber la orientación real percibida
                val displayW = if (rot == 90 || rot == 270) h else w
                val displayH = if (rot == 90 || rot == 270) w else h
                val orientation = when {
                    displayW == 0 || displayH == 0 -> Orientation.LANDSCAPE
                    displayW > displayH -> Orientation.LANDSCAPE
                    displayH > displayW -> Orientation.PORTRAIT
                    else -> Orientation.SQUARE
                }
                val fps = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                val hasAudio = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val sig = "$w|$h|$rot|$fps|$hasAudio"
                VideoMeta(uri, getDisplayName(uri), w, h, orientation, fps, rot, hasAudio, sig)
            } catch (e: Exception) {
                Log.w("EditorVM/Analyze", "No pude leer $uri: ${e.message}")
                VideoMeta(uri, getDisplayName(uri), 0, 0, Orientation.LANDSCAPE, null, 0, false, null)
            } finally {
                r.release()
            }
        }

        val sigs = metas.map { it.rawSignature }
        val canStreamCopy = sigs.isNotEmpty() && sigs.all { it != null && it == sigs.first() }

        val counts = metas.groupingBy { it.orientation }.eachCount()
        val majorityOrientation = counts.maxByOrNull { it.value }?.key ?: Orientation.LANDSCAPE

        val mismatched = metas.mapIndexedNotNull { i, m ->
            if (m.orientation != majorityOrientation) i else null
        }

        return MergeAnalysis(metas, canStreamCopy, majorityOrientation, mismatched)
    }
    private fun ctx(): Context = getApplication()

    // ---------------------------------------------------------------------
    // Helpers compartidos (antes vivían en el Fragment)
    // ---------------------------------------------------------------------

    private fun cacheUriToFile(uri: Uri, name: String): File {
        val file = File(ctx().cacheDir, name)
        if (file.exists()) file.delete()
        try {
            ctx().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e("EditorVM", "cacheUriToFile falló para $uri: ${e.message}")
        }
        return file
    }

    private fun getMediaDuration(uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx(), uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) { 0L } finally { retriever.release() }
    }

    private fun getFileExtension(uri: Uri): String {
        val name = ctx().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
        return name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    }

    private fun getDisplayName(uri: Uri): String {
        return ctx().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
    }

    private fun saveToDownloads(file: File, fileName: String, mimeType: String = "video/mp4") {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/0 VIDEO")
            }
        }
        val collectionUri = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = ctx().contentResolver.insert(collectionUri, values)
        if (uri != null) {
            try {
                ctx().contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                _events.tryEmit(EditorEvent.Saved(fileName, mimeType))
            } catch (e: Exception) {
                _events.tryEmit(EditorEvent.Error("Error al guardar $fileName: ${e.message}"))
            }
        } else {
            _events.tryEmit(EditorEvent.Error("No se pudo crear el destino para $fileName"))
        }
    }

    private fun parseTimeToMillis(time: String): Long {
        return try {
            val normalizedTime = time.replace(",", ":").replace(".", ":")
            val parts = normalizedTime.split(":")
            when (parts.size) {
                4 -> {
                    val h = parts[0].trim().toLong()
                    val m = parts[1].trim().toLong()
                    val s = parts[2].trim().toLong()
                    val ms = parts[3].trim().toLong()
                    (h * 3600000) + (m * 60000) + (s * 1000) + ms
                }
                3 -> {
                    val m = parts[0].trim().toLong()
                    val s = parts[1].trim().toLong()
                    val ms = parts[2].trim().toLong()
                    (m * 60000) + (s * 1000) + ms
                }
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun reportProgress(currentMs: Double, totalMs: Long) {
        _progress.value = EditorProgress(currentMs, totalMs)
    }

    private fun clearProgress() {
        _progress.value = null
    }

    // ---------------------------------------------------------------------
    // 1. Mezcla de medios (MIX)
    // ---------------------------------------------------------------------
    fun joinCombinedMedia(items: List<MediaMixItem>, qualityArgs: String = "-b:v 6M") {
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val targetW = 1280; val targetH = 720
            val totalMs = items.sumOf { it.durationMs }
            reportProgress(0.0, totalMs)

            val inputFiles = mutableListOf<File>()
            val filterFile = File(ctx().cacheDir, "mix_filter.txt")
            val outputFile = File(ctx().cacheDir, "mix_output.mp4")
            try {
                items.forEachIndexed { i, item ->
                    inputFiles.add(cacheUriToFile(item.uri, "mix_input_$i.${getFileExtension(item.uri)}"))
                }

                val filterComplex = StringBuilder()
                val inputArgs = StringBuilder()

                inputFiles.forEachIndexed { i, file ->
                    val item = items[i]
                    inputArgs.append("-i \"${file.absolutePath}\" ")
                    if (item.isVideo) {
                        filterComplex.append("[$i:v]scale=$targetW:$targetH:force_original_aspect_ratio=decrease,pad=$targetW:$targetH:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v$i];")
                        filterComplex.append("[$i:a]aformat=sample_rates=44100:channel_layouts=stereo[a$i];")
                    } else {
                        val durSec = item.durationMs / 1000.0
                        filterComplex.append("[$i:v]loop=loop=-1:size=1:start=0,scale=$targetW:$targetH:force_original_aspect_ratio=decrease,pad=$targetW:$targetH:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p,trim=duration=$durSec[v$i];")
                        filterComplex.append("anullsrc=r=44100:cl=stereo[a${i}_silence]; [a${i}_silence]atrim=duration=$durSec[a$i];")
                    }
                }
                for (i in inputFiles.indices) filterComplex.append("[v$i][a$i]")
                filterComplex.append("concat=n=${inputFiles.size}:v=1:a=1[outv][outa]")

                if (outputFile.exists()) outputFile.delete()
                filterFile.writeText(filterComplex.toString())

                val command = "-y $inputArgs -filter_complex_script \"${filterFile.absolutePath}\" " +
                        "-map \"[outv]\" -map \"[outa]\" -c:v mpeg4 $qualityArgs -pix_fmt yuv420p " +
                        "-c:a aac -b:a 128k \"${outputFile.absolutePath}\""

                val session = FFmpegKit.execute(command) { stats -> reportProgress(stats.time, totalMs) }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    saveToDownloads(outputFile, "Mix_${System.currentTimeMillis()}.mp4")
                } else {
                    Log.e("EditorVM/Mix", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló la mezcla de medios"))
                }
            } catch (e: Exception) {
                Log.e("EditorVM/Mix", "Error: ${e.message}")
                _events.tryEmit(EditorEvent.Error("Error en mezcla: ${e.message}"))
            } finally {
                inputFiles.forEach { it.delete() }
                filterFile.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 2. Unir videos (merge)
    // ---------------------------------------------------------------------
    /**
     * @param targetOrientation orientación a la que se normaliza si hay que reencodear.
     *                          Si todos son homogéneos y compatibles, se ignora.
     */
    fun mergeVideos(uris: List<Uri>, targetOrientation: Orientation = Orientation.LANDSCAPE, qualityArgs: String = "-b:v 6M") {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val totalMs = uris.sumOf { getMediaDuration(it) }
            reportProgress(0.0, totalMs)
            val archivos = mutableListOf<File>()
            val listaFile = File(ctx().cacheDir, "merge_list.txt")
            val filterFile = File(ctx().cacheDir, "merge_filter.txt")
            val outputFileCopy = File(ctx().cacheDir, "merge_output_copy.mp4")
            val outputFileReenc = File(ctx().cacheDir, "merge_output_reenc.mp4")
            try {
                uris.forEachIndexed { i, uri ->
                    val ext = getFileExtension(uri).ifBlank { "mp4" }
                    archivos.add(cacheUriToFile(uri, "merge_input_$i.$ext"))
                }

                // Re-analizamos con los archivos cacheados (más confiable que releer URIs)
                val canCopy = canStreamCopyFiles(archivos)
                Log.d("EditorVM/Merge", "canStreamCopy=$canCopy, target=$targetOrientation")

                if (canCopy) {
                    if (outputFileCopy.exists()) outputFileCopy.delete()
                    listaFile.writeText(archivos.joinToString("\n") { "file '${it.absolutePath}'" })
                    val cmd = "-f concat -safe 0 -i \"${listaFile.absolutePath}\" -c copy \"${outputFileCopy.absolutePath}\""
                    val session = FFmpegKit.execute(cmd) { stats -> reportProgress(stats.time, totalMs) }
                    if (ReturnCode.isSuccess(session.returnCode) && outputFileCopy.exists() && outputFileCopy.length() > 0) {
                        saveToDownloads(outputFileCopy, "Video_Unido_${System.currentTimeMillis()}.mp4")
                        return@launch
                    }
                    Log.e("EditorVM/Merge", "Falló concat copy, cayendo a reencode:\n${session.allLogsAsString}")
                }

                mergeVideosReencode(archivos, targetOrientation, filterFile, outputFileReenc, totalMs, qualityArgs)
            } catch (e: Exception) {
                Log.e("EditorVM/Merge", "Error: ${e.message}")
                _events.tryEmit(EditorEvent.Error("Error al unir: ${e.message}"))
            } finally {
                archivos.forEach { it.delete() }
                listaFile.delete()
                filterFile.delete()
                if (outputFileCopy.exists()) outputFileCopy.delete()
                if (outputFileReenc.exists()) outputFileReenc.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    /**
     * Determina si los archivos comparten resolución + rotación + presencia de audio.
     * No chequeamos códec exacto (MediaMetadataRetriever no siempre lo expone bien),
     * pero si fps/rot/dims/audio coinciden, -c copy suele andar. Si falla, el caller
     * cae a reencode de todos modos.
     */
    private fun canStreamCopyFiles(files: List<File>): Boolean {
        if (files.isEmpty()) return false
        val sigs = files.map { f ->
            try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(f.absolutePath)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0"
                val hasAudio = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                r.release()
                "$w|$h|$rot|$hasAudio"
            } catch (e: Exception) { null }
        }
        return sigs.all { it != null && it == sigs.first() }
    }

    /**
     * Reencode con normalización. El target W/H sale de la orientación decidida.
     */
    private fun mergeVideosReencode(
        archivos: List<File>,
        targetOrientation: Orientation,
        filterFile: File,
        outputFile: File,
        totalMs: Long,
        qualityArgs: String
    ) {
        // Elegimos resolución target según orientación.
        // Portrait  -> 720x1280
        // Landscape -> 1280x720
        // Square    -> 720x720
        val (targetW, targetH) = when (targetOrientation) {
            Orientation.PORTRAIT -> 720 to 1280
            Orientation.LANDSCAPE -> 1280 to 720
            Orientation.SQUARE -> 720 to 720
        }
        val targetFps = 30

        val inputArgs = StringBuilder()
        val filter = StringBuilder()

        archivos.forEachIndexed { i, f ->
            inputArgs.append("-i \"${f.absolutePath}\" ")
            filter.append("[$i:v]scale=$targetW:$targetH:force_original_aspect_ratio=decrease,")
            filter.append("pad=$targetW:$targetH:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=$targetFps,")
            filter.append("format=yuv420p,settb=AVTB,setpts=PTS-STARTPTS[v$i];")
            // Audio: si el input no tiene audio, usamos silencio del largo del video.
            // Necesitamos saber si tiene audio — lo consultamos por metadata.
            if (hasAudioStream(f)) {
                filter.append("[$i:a]aformat=sample_rates=44100:channel_layouts=stereo,asetpts=PTS-STARTPTS[a$i];")
            } else {
                val durSec = getMediaDurationFile(f) / 1000.0
                filter.append("anullsrc=r=44100:cl=stereo,atrim=duration=$durSec,asetpts=PTS-STARTPTS[a$i];")
            }
        }
        for (i in archivos.indices) filter.append("[v$i][a$i]")
        filter.append("concat=n=${archivos.size}:v=1:a=1[outv][outa]")

        if (outputFile.exists()) outputFile.delete()
        filterFile.writeText(filter.toString())

        val cmd = "-y $inputArgs -filter_complex_script \"${filterFile.absolutePath}\" " +
                "-map \"[outv]\" -map \"[outa]\" " +
                "-c:v mpeg4 $qualityArgs -pix_fmt yuv420p " +
                "-c:a aac -b:a 128k -movflags +faststart \"${outputFile.absolutePath}\""

        val session = FFmpegKit.execute(cmd) { stats -> reportProgress(stats.time, totalMs) }
        if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
            saveToDownloads(outputFile, "Video_Unido_${System.currentTimeMillis()}.mp4")
        } else {
            Log.e("EditorVM/Merge", "Falló reencode:\n${session.allLogsAsString}")
            _events.tryEmit(EditorEvent.Error("Falló la unión"))
        }
    }

    private fun hasAudioStream(f: File): Boolean {
        return try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val has = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            r.release()
            has
        } catch (e: Exception) { false }
    }

    private fun getMediaDurationFile(f: File): Long {
        return try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            d
        } catch (e: Exception) { 0L }
    }
    // ---------------------------------------------------------------------
    // 3. Cortar en varias partes (split multiple)
    // ---------------------------------------------------------------------
    data class CutRange(val start: String, val end: String)

    fun splitVideoMultiple(videoUri: Uri, ranges: List<CutRange>) {
        if (ranges.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val totalMs = ranges.sumOf {
                try { (parseTimeToMillis(it.end) - parseTimeToMillis(it.start)).coerceAtLeast(0) }
                catch (e: Exception) { 0L }
            }
            reportProgress(0.0, totalMs)
            val videoFile = cacheUriToFile(videoUri, "input_split_multi.mp4")
            val baseName = getDisplayName(videoUri).substringBeforeLast(".")
            var exitosos = 0; var fallidos = 0; var elapsedBefore = 0L
            try {
                ranges.forEachIndexed { index, range ->
                    val outputFile = File(ctx().cacheDir, "output_split_multi_$index.mp4")
                    if (outputFile.exists()) outputFile.delete()
                    val rangeDuration = try {
                        (parseTimeToMillis(range.end) - parseTimeToMillis(range.start)).coerceAtLeast(0)
                    } catch (e: Exception) { 0L }

                    val cmd = "-y -i \"${videoFile.absolutePath}\" -ss ${range.start} -to ${range.end} -c copy \"${outputFile.absolutePath}\""
                    val captured = elapsedBefore
                    val session = FFmpegKit.execute(cmd) { stats -> reportProgress(captured + stats.time, totalMs) }

                    if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                        saveToDownloads(outputFile, "${baseName}_parte${index + 1}.mp4")
                        exitosos++
                    } else {
                        Log.e("EditorVM/SplitMulti", "Falló corte ${index + 1}: ${session.allLogsAsString}")
                        fallidos++
                    }
                    outputFile.delete()
                    elapsedBefore += rangeDuration
                }
                _events.tryEmit(EditorEvent.Info("Cortes: $exitosos ok, $fallidos fallidos"))
            } finally {
                videoFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 4. Fade
    // ---------------------------------------------------------------------
    fun applyFade(videoUri: Uri, fadeInSec: Double, fadeOutSec: Double, qualityArgs: String = "-b:v 6M") {
        if (fadeInSec <= 0.0 && fadeOutSec <= 0.0) {
            _events.tryEmit(EditorEvent.Error("Define al menos un tiempo de fade"))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val totalMs = getMediaDuration(videoUri)
            val totalSec = totalMs / 1000.0
            if (fadeInSec + fadeOutSec > totalSec) {
                _events.tryEmit(EditorEvent.Error("La suma de fades supera la duración"))
                return@launch
            }
            reportProgress(0.0, totalMs)
            val videoFile = cacheUriToFile(videoUri, "input_fade.mp4")
            val outputFile = File(ctx().cacheDir, "output_fade.mp4")
            val filterScriptFile = File(ctx().cacheDir, "fade_filter.txt")
            try {
                if (outputFile.exists()) outputFile.delete()
                val fileName = "${getDisplayName(videoUri).substringBeforeLast(".")}_fade.mp4"

                val vFilters = mutableListOf<String>()
                val aFilters = mutableListOf<String>()
                if (fadeInSec > 0.0) {
                    vFilters.add("fade=t=in:st=0:d=$fadeInSec")
                    aFilters.add("afade=t=in:st=0:d=$fadeInSec")
                }
                if (fadeOutSec > 0.0) {
                    val st = (totalSec - fadeOutSec).coerceAtLeast(0.0)
                    vFilters.add("fade=t=out:st=$st:d=$fadeOutSec")
                    aFilters.add("afade=t=out:st=$st:d=$fadeOutSec")
                }
                val vChain = if (vFilters.isNotEmpty()) "[0:v]${vFilters.joinToString(",")}[v]" else "[0:v]null[v]"
                val aChain = if (aFilters.isNotEmpty()) "[0:a]${aFilters.joinToString(",")}[a]" else "[0:a]anull[a]"
                filterScriptFile.writeText("$vChain;$aChain")

                val command = "-y -i \"${videoFile.absolutePath}\" -filter_complex_script \"${filterScriptFile.absolutePath}\" " +
                        "-map \"[v]\" -map \"[a]\" -c:v mpeg4 $qualityArgs -pix_fmt yuv420p -c:a aac \"${outputFile.absolutePath}\""
                val session = FFmpegKit.execute(command) { stats -> reportProgress(stats.time, totalMs) }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    saveToDownloads(outputFile, fileName)
                } else {
                    Log.e("EditorVM/Fade", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló el fade"))
                }
            } finally {
                videoFile.delete()
                filterScriptFile.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 5. Video -> GIF
    // ---------------------------------------------------------------------
    fun videoToGif(videoUri: Uri, fps: Int = 10, anchoMax: Int = 480) {
        viewModelScope.launch(Dispatchers.IO) {
            val totalMs = getMediaDuration(videoUri)
            reportProgress(0.0, totalMs)
            val videoFile = cacheUriToFile(videoUri, "input_gif.mp4")
            val outputFile = File(ctx().cacheDir, "output_gif.gif")
            val filterScriptFile = File(ctx().cacheDir, "gif_filter.txt")
            try {
                if (outputFile.exists()) outputFile.delete()
                val fileName = "${getDisplayName(videoUri).substringBeforeLast(".")}.gif"
                val filterComplex = "[0:v]fps=$fps,scale=$anchoMax:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse"
                filterScriptFile.writeText(filterComplex)
                val command = "-y -i \"${videoFile.absolutePath}\" -filter_complex_script \"${filterScriptFile.absolutePath}\" \"${outputFile.absolutePath}\""
                val session = FFmpegKit.execute(command) { stats -> reportProgress(stats.time, totalMs) }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    saveToDownloads(outputFile, fileName, "image/gif")
                } else {
                    Log.e("EditorVM/Gif", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló la creación del GIF"))
                }
            } finally {
                videoFile.delete()
                filterScriptFile.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 6. Slideshow desde fotos (con/sin audio)
    // ---------------------------------------------------------------------
    fun createSlideshow(uris: List<Uri>, durationsMs: List<Long>?, audioUri: Uri?, qualityArgs: String = "-b:v 6M") {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val effective = if (durationsMs != null && durationsMs.size == uris.size) durationsMs
                            else List(uris.size) { 3000L }
            val carpetaTemp = File(ctx().cacheDir, "slideshow_${System.currentTimeMillis()}").apply { mkdirs() }
            val filterScriptFile = File(ctx().cacheDir, "slideshow_filter.txt")
            val outputFile = File(ctx().cacheDir, "output_slideshow.mp4")
            var audioFile: File? = null
            try {
                uris.forEachIndexed { index, uri ->
                    val destino = File(carpetaTemp, "img%03d.jpg".format(index))
                    ctx().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destino).use { out -> input.copyTo(out) }
                    }
                }
                if (outputFile.exists()) outputFile.delete()

                val inputArgs = StringBuilder()
                val filterComplex = StringBuilder()
                uris.forEachIndexed { index, _ ->
                    val imgPath = File(carpetaTemp, "img%03d.jpg".format(index)).absolutePath
                    val durSec = effective[index] / 1000.0
                    inputArgs.append("-loop 1 -t $durSec -i $imgPath ")
                    filterComplex.append("[$index:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,fps=30[v$index];")
                }
                for (index in uris.indices) filterComplex.append("[v$index]")
                filterComplex.append("concat=n=${uris.size}:v=1:a=0[outv]")
                filterScriptFile.writeText(filterComplex.toString())

                val totalMs = effective.sum()
                reportProgress(0.0, totalMs)

                audioFile = audioUri?.let { cacheUriToFile(it, "slideshow_audio.tmp") }

                val command = if (audioFile != null) {
                    "-y $inputArgs-i \"${audioFile.absolutePath}\" -filter_complex_script \"${filterScriptFile.absolutePath}\" " +
                            "-map [outv] -map ${uris.size}:a -c:v mpeg4 $qualityArgs -pix_fmt yuv420p -c:a aac -shortest \"${outputFile.absolutePath}\""
                } else {
                    "-y $inputArgs-filter_complex_script \"${filterScriptFile.absolutePath}\" -map [outv] -c:v mpeg4 $qualityArgs -pix_fmt yuv420p \"${outputFile.absolutePath}\""
                }

                val session = FFmpegKit.execute(command) { stats -> reportProgress(stats.time, totalMs) }
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, "Slideshow_${System.currentTimeMillis()}.mp4")
                } else {
                    Log.e("EditorVM/Slideshow", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló el slideshow"))
                }
            } catch (e: Exception) {
                Log.e("EditorVM/Slideshow", "Error: ${e.message}")
                _events.tryEmit(EditorEvent.Error("Error en slideshow: ${e.message}"))
            } finally {
                carpetaTemp.deleteRecursively()
                filterScriptFile.delete()
                audioFile?.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 7. Agregar audio a video
    // ---------------------------------------------------------------------
    fun addAudioToVideo(videoUri: Uri, audioUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val duration = getMediaDuration(videoUri)
            reportProgress(0.0, duration)
            val videoFile = cacheUriToFile(videoUri, "input_addaudio.mp4")
            val audioFile = cacheUriToFile(audioUri, "input_addaudio_track")
            val outputFile = File(ctx().cacheDir, "output_addaudio.mp4")
            try {
                if (outputFile.exists()) outputFile.delete()
                val fileName = "${getDisplayName(videoUri).substringBeforeLast(".")}_audio.mp4"
                val command = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" " +
                        "-map 0:v -map 1:a -c:v copy -c:a aac -shortest \"${outputFile.absolutePath}\""
                val session = FFmpegKit.execute(command) { stats -> reportProgress(stats.time, duration) }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    saveToDownloads(outputFile, fileName)
                } else {
                    Log.e("EditorVM/AddAudio", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló agregar audio"))
                }
            } finally {
                videoFile.delete(); audioFile.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 8. Hardcodear subtítulos (ASS)
    // ---------------------------------------------------------------------
    /**
     * El fragment construye el .ass (necesita referencias al binding para
     * opciones de estilo) y nos pasa el archivo listo para quemar.
     */
    fun burnAssSubtitles(videoUri: Uri, assFile: File, fontsDir: File, qualityArgs: String = "-b:v 6M") {
        viewModelScope.launch(Dispatchers.IO) {
            val duration = getMediaDuration(videoUri)
            reportProgress(0.0, duration)

            val videoFile = cacheUriToFile(videoUri, "input_ass.${getFileExtension(videoUri).ifBlank { "mp4" }}")
            val outputFile = File(ctx().cacheDir, "output_ass.mp4")

            try {
                if (outputFile.exists()) outputFile.delete()
                val fileName = "${getDisplayName(videoUri).substringBeforeLast(".")}_ass.mp4"

                fun esc(p: String): String = p
                    .replace("\\", "\\\\")
                    .replace(":", "\\:")
                    .replace("'", "\\'")
                    .replace("[", "\\[")
                    .replace("]", "\\]")

                // Usamos el .ass TAL CUAL. Sin regenerar, sin tocar estilos.
                val filter = "subtitles=f='${esc(assFile.absolutePath)}':fontsdir='${esc(fontsDir.absolutePath)}':charenc=UTF-8"
                val command = "-y -i \"${videoFile.absolutePath}\" -vf \"$filter\" " +
                        "-c:v mpeg4 $qualityArgs -pix_fmt yuv420p -c:a copy \"${outputFile.absolutePath}\""

                Log.d("EditorVM/BurnAss", "Comando: $command")
                val session = FFmpegKit.execute(command) { stats -> reportProgress(stats.time, duration) }
                Log.d("EditorVM/BurnAss", session.allLogsAsString ?: "")

                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName)
                } else {
                    val log = session.allLogsAsString ?: ""
                    val diagnostico = when {
                        log.contains("No such filter: 'subtitles'", ignoreCase = true) ->
                            "Tu build de ffmpeg-kit no incluye el filtro 'subtitles'."
                        log.contains("No such file or directory", ignoreCase = true) ->
                            "No se encontró el .ass o la carpeta de fuentes."
                        log.contains("Fontconfig error", ignoreCase = true) ->
                            "Problema con las fuentes. Verificá fontsdir."
                        else -> "Falló incrustar subtítulos. Ver Logcat tag 'EditorVM/BurnAss'."
                    }
                    Log.e("EditorVM/BurnAss", log)
                    _events.tryEmit(EditorEvent.Error(diagnostico))
                }
            } finally {
                videoFile.delete()
                // Borramos el .ass del cache. Si vino de buildAssFromSubtitleList, es nuestro.
                // Si vino de selectedAssSubtitleUri, también lo copiamos a cache, así que es nuestro.
                assFile.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 9. Multiplex -> MKV
    // ---------------------------------------------------------------------
    fun generateMultiplexMKV(videoUri: Uri, audioUris: List<Uri>, subUris: List<Uri>) {
        if (audioUris.isEmpty() && subUris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val duration = getMediaDuration(videoUri)
            reportProgress(0.0, duration)
            val inputFiles = mutableListOf<File>()
            val outputFile = File(ctx().cacheDir, "mplex_output.mkv")
            try {
                val command = StringBuilder("-y ")
                val videoFile = cacheUriToFile(videoUri, "mplex_video.mp4")
                inputFiles.add(videoFile)
                command.append("-i \"${videoFile.absolutePath}\" ")

                audioUris.forEachIndexed { i, uri ->
                    val f = cacheUriToFile(uri, "mplex_audio_$i.tmp")
                    inputFiles.add(f)
                    command.append("-i \"${f.absolutePath}\" ")
                }
                subUris.forEachIndexed { i, uri ->
                    val ext = getFileExtension(uri)
                    val f = cacheUriToFile(uri, "mplex_sub_$i.$ext")
                    inputFiles.add(f)
                    command.append("-i \"${f.absolutePath}\" ")
                }

                command.append("-map 0:v -map 0:a? ")
                var idx = 1
                repeat(audioUris.size) { command.append("-map ${idx}:a "); idx++ }
                repeat(subUris.size) { command.append("-map ${idx}:s "); idx++ }

                if (outputFile.exists()) outputFile.delete()
                command.append("-c copy -c:s srt \"${outputFile.absolutePath}\"")

                val session = FFmpegKit.execute(command.toString()) { stats -> reportProgress(stats.time, duration) }
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                    saveToDownloads(outputFile, "Multiplex_${System.currentTimeMillis()}.mkv", "video/x-matroska")
                } else {
                    Log.e("EditorVM/Multiplex", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló el multiplex"))
                }
            } finally {
                inputFiles.forEach { it.delete() }
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 10. Producción final (audio + subs opcionales)
    // ---------------------------------------------------------------------
    /**
     * audioUri == SILENCE_URI  -> silenciar
     * audioUri == null         -> mantener audio original
     * audioUri real            -> reemplazar
     * subtitleUri != null      -> quemar subtítulos (necesita reencode)
     */
    fun finalProduction(videoUri: Uri, audioUri: Uri?, subtitleUri: Uri?, fontsDir: File,
                        qualityArgs: String = "-b:v 6M") {
        viewModelScope.launch(Dispatchers.IO) {
            val duration = getMediaDuration(videoUri)
            reportProgress(0.0, duration)

            val videoFile = cacheUriToFile(videoUri, "prod_video.mp4")
            val isSilence = audioUri != null && audioUri.toString() == SILENCE_URI
            val audioFile = if (audioUri != null && !isSilence) cacheUriToFile(audioUri, "prod_audio.tmp") else null
            val subFile = subtitleUri?.let {
                val ext = getFileExtension(it).ifBlank { "srt" }
                cacheUriToFile(it, "prod_sub.$ext")
            }
            val outputFile = File(ctx().cacheDir, "prod_output.mp4")
            try {
                if (outputFile.exists()) outputFile.delete()
                val baseName = getDisplayName(videoUri).substringBeforeLast(".")
                val fileName = "${baseName}_Produccion.mp4"

                val command = StringBuilder("-y -i \"${videoFile.absolutePath}\" ")
                if (audioFile != null) command.append("-i \"${audioFile.absolutePath}\" ")

                val vFilter = subFile?.let {
                    "subtitles=${it.absolutePath.replace(":", "\\:")}:fontsdir=${fontsDir.absolutePath}"
                }
                if (vFilter != null) command.append("-vf \"$vFilter\" ")

                when {
                    isSilence -> command.append("-map 0:v -an ")
                    audioFile != null -> command.append("-map 0:v -map 1:a -c:a aac -shortest ")
                    else -> command.append("-map 0:v -map 0:a? -c:a copy ")
                }

                if (vFilter != null) {
                    command.append("-c:v mpeg4 $qualityArgs -pix_fmt yuv420p ")
                } else {
                    command.append("-c:v copy ")
                }
                command.append("\"${outputFile.absolutePath}\"")

                val session = FFmpegKit.execute(command.toString()) { stats -> reportProgress(stats.time, duration) }
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName)
                } else {
                    Log.e("EditorVM/Production", session.allLogsAsString)
                    _events.tryEmit(EditorEvent.Error("Falló la producción final"))
                }
            } catch (e: Exception) {
                Log.e("EditorVM/Production", "Error: ${e.message}")
                _events.tryEmit(EditorEvent.Error("Error en producción: ${e.message}"))
            } finally {
                videoFile.delete(); audioFile?.delete(); subFile?.delete()
                if (outputFile.exists()) outputFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 11. Demux (audio + subs)
    // ---------------------------------------------------------------------
    private fun detectarStreams(file: File): Pair<List<Int>, List<Int>> {
        val audio = mutableListOf<Int>()
        val subs = mutableListOf<Int>()
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("audio/") -> audio.add(i)
                    mime.startsWith("text/") || mime.startsWith("application/") -> subs.add(i)
                }
            }
        } catch (e: Exception) {
            Log.e("EditorVM/Demux", "Error tracks: ${e.message}")
        } finally { extractor.release() }
        return audio to subs
    }

    fun demux(videoUri: Uri, extractAudio: Boolean, extractSubs: Boolean) {
        if (!extractAudio && !extractSubs) return
        viewModelScope.launch(Dispatchers.IO) {
            val duration = getMediaDuration(videoUri)
            reportProgress(0.0, duration)
            val videoFile = cacheUriToFile(videoUri, "demux_input.mp4")
            try {
                val (audioStreams, subStreams) = detectarStreams(videoFile)
                val baseName = getDisplayName(videoUri).substringBeforeLast(".")

                if (extractAudio) {
                    if (audioStreams.isEmpty()) {
                        _events.tryEmit(EditorEvent.Info("No se encontraron audios"))
                    }
                    audioStreams.forEach { idx ->
                        val outName = "${baseName}_track_$idx.mp3"
                        val outFile = File(ctx().cacheDir, outName)
                        val cmd = "-y -i \"${videoFile.absolutePath}\" -map 0:$idx -c:a libmp3lame -q:a 2 \"${outFile.absolutePath}\""
                        val s = FFmpegKit.execute(cmd)
                        if (!ReturnCode.isSuccess(s.returnCode)) {
                            Log.e("EditorVM/Demux", "audio $idx: ${s.allLogsAsString}")
                        }
                        if (outFile.exists() && outFile.length() > 0) saveToDownloads(outFile, outName, "audio/mpeg")
                        outFile.delete()
                    }
                }
                if (extractSubs) {
                    if (subStreams.isEmpty()) {
                        _events.tryEmit(EditorEvent.Info("No se encontraron subtítulos"))
                    }
                    subStreams.forEach { idx ->
                        val outName = "${baseName}_sub_$idx.srt"
                        val outFile = File(ctx().cacheDir, outName)
                        val cmd = "-y -i \"${videoFile.absolutePath}\" -map 0:$idx \"${outFile.absolutePath}\""
                        FFmpegKit.execute(cmd)
                        if (outFile.exists() && outFile.length() > 0) saveToDownloads(outFile, outName, "text/plain")
                        outFile.delete()
                    }
                }
            } finally {
                videoFile.delete()
                clearProgress()
                _events.tryEmit(EditorEvent.Finished)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 12. Conversión masiva a MP3
    // ---------------------------------------------------------------------
    fun convertAudiosToMp3(uris: List<Uri>, calidad: Int = 2) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var exitosos = 0; var fallidos = 0
            var primerError: String? = null
            val totalUris = uris.size

            uris.forEachIndexed { index, uri ->
                val originalName = getDisplayName(uri)
                val fileName = "${originalName.substringBeforeLast(".")}.mp3"
                val inputFile = cacheUriToFile(uri, "temp_input_audio_$index.tmp")

                if (!inputFile.exists() || inputFile.length() == 0L) {
                    fallidos++
                    if (primerError == null) primerError = "$originalName: no se pudo leer el origen"
                    return@forEachIndexed
                }
                val outputFile = File(ctx().cacheDir, "output_temp_$index.mp3")
                if (outputFile.exists()) outputFile.delete()
                val duration = getMediaDuration(uri)
                _progress.value = EditorProgress(0.0, duration, "Convirtiendo ($index/$totalUris): ${originalName.substringBeforeLast(".")}")

                val codecPortada = detectarCodecPortada(inputFile)
                val tieneVideo = !codecPortada.isNullOrBlank()
                val filtroVideo = when {
                    !tieneVideo -> ""
                    codecPortada == "mjpeg" || codecPortada == "png" -> "-c:v copy"
                    else -> "-c:v mjpeg"
                }
                val mapVideo = if (tieneVideo) "-map 0:v?" else ""
                val dispositionFlag = if (tieneVideo) "-disposition:v attached_pic" else ""

                val command = "-y -i \"${inputFile.absolutePath}\" " +
                        "-map_metadata 0 -map 0:a $mapVideo " +
                        "-c:a libmp3lame -q:a $calidad $filtroVideo $dispositionFlag " +
                        "-id3v2_version 3 \"${outputFile.absolutePath}\""

                val session = FFmpegKit.execute(command) { stats ->
                    _progress.value = EditorProgress(stats.time, duration, "Convirtiendo ($index/$totalUris): ${originalName.substringBeforeLast(".")}")
                }
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName, "audio/mpeg")
                    exitosos++
                } else {
                    fallidos++
                    val logCompleto = session.allLogsAsString
                    Log.e("EditorVM/ConvertMp3", "Falló $originalName:\n$logCompleto")
                    if (primerError == null) {
                        val ultima = logCompleto?.trim()?.lines()?.lastOrNull { it.isNotBlank() }
                        primerError = "$originalName: ${ultima ?: "returnCode=${session.returnCode}"}"
                    }
                }
                inputFile.delete()
                outputFile.delete()
            }
            val base = "Conversión: $exitosos ok, $fallidos fallidos"
            val msg = if (fallidos > 0 && primerError != null) "$base\nPrimer error: $primerError" else base
            _events.tryEmit(EditorEvent.Info(msg))
            clearProgress()
            _events.tryEmit(EditorEvent.Finished)
        }
    }

    private fun detectarCodecPortada(inputFile: File): String? {
        val session = FFmpegKit.execute("-i \"${inputFile.absolutePath}\"")
        val log = session.allLogsAsString ?: return null
        val regex = Regex("""Stream #\d+:\d+.*?: Video: (\w+)""")
        return regex.find(log)?.groupValues?.get(1)?.trim()
    }

    companion object {
        const val SILENCE_URI = "content://editor/silence"
    }
}