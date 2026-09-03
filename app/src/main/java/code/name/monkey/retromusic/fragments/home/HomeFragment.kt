package code.name.monkey.retromusic.fragments.home

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.adapter.VideoFrameAdapter
import code.name.monkey.retromusic.databinding.FragmentHomeBinding
import code.name.monkey.retromusic.dialogs.CreatePlaylistDialog
import code.name.monkey.retromusic.dialogs.ImportPlaylistDialog
import code.name.monkey.retromusic.extensions.dip
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.profileBannerOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.userProfileOptions
import code.name.monkey.retromusic.interfaces.IScrollHelper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.bumptech.glide.Glide
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import java.io.File
import java.io.FileOutputStream
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.*
import code.name.monkey.retromusic.fragments.ReloadType
import android.media.MediaExtractor
import android.media.MediaFormat

data class Subtitle(val startTime: Long, val endTime: Long, val original: String, val translation: String?)

class HomeFragment : AbsMainActivityFragment(R.layout.fragment_home), IScrollHelper {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var savedPosition: Int = 0
    private var exoPlayer: ExoPlayer? = null
    private var wasPlayingBeforePause = false
    private var selectedFolderUri: Uri? = null
    private val videoPlaylist = mutableListOf<Uri>()
    private var currentIndex = 0
    private val downloadVideoList = mutableListOf<Pair<String, Uri>>()
    private val subtitleList = mutableListOf<Subtitle>()
    private val handler = Handler(Looper.getMainLooper())
    private var selectedSubtitleUri: Uri? = null

    private var selectedAssSubtitleUri: Uri? = null
    private var isProcessing = false
    private var selectedAudioUri: Uri? = null
    private var selectedAudioUris = mutableListOf<Uri>()

    private val multiplexAudioUris = mutableListOf<Uri>()
    private val multiplexSubtitleUris = mutableListOf<Uri>()

    private val mAudioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            multiplexAudioUris.add(it)
            askForAnotherAudio()
        } ?: askForSubtitleStep()
    }

    private val mSubPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            multiplexSubtitleUris.add(it)
            askForAnotherSubtitle()
        } ?: generateMultiplexMKV()
    }

    private var workshopSubtitleIndex = -1

    private var isFullscreen = false
    private lateinit var fullscreenGestureDetector: GestureDetector

    private val multiaudioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedAudioUris = uris.toMutableList()
            mostrarSelectorCalidad(uris)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) loadVideosFromDownloads() else Toast.makeText(requireContext(), R.string.permiso_denegado_videos, Toast.LENGTH_SHORT).show()
    }

    private var slideshowImages = mutableListOf<Uri>()
    private var filmstripAdapter: VideoFrameAdapter? = null

    private val photosPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            slideshowImages.addAll(uris)
            updateSlideshowFilmstrip()
        }
    }

    private fun updateSlideshowFilmstrip() {
        val frames = slideshowImages.mapIndexed { index, uri ->
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(requireContext().contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.setTargetSize(120, 70)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
                }
            } catch (e: Exception) { null }
            VideoFrameAdapter.VideoFrame(index.toLong(), bitmap, "Img ${index + 1}", canRemove = true)
        }.toMutableList()

        frames.add(VideoFrameAdapter.VideoFrame(0, null, "", isAddButton = true))

        if (_binding != null) {
            binding.homeContent.rvFilmstrip.apply {
                if (filmstripAdapter == null || adapter != filmstripAdapter) {
                    filmstripAdapter = VideoFrameAdapter(frames, { /* No seek */ }, {
                        photosPickerLauncher.launch("image/*")
                    }, { pos ->
                        if (pos < slideshowImages.size) {
                            slideshowImages.removeAt(pos)
                            updateSlideshowFilmstrip()
                        }
                    })
                    layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                    adapter = filmstripAdapter
                } else {
                    filmstripAdapter?.updateFrames(frames)
                }
                visibility = View.VISIBLE
            }
        }
    }

    private var mergeVideosUris = mutableListOf<Uri>()

    private val mergePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            mergeVideosUris.addAll(uris)
            updateMergeFilmstrip()
        }
    }

    private fun updateMergeFilmstrip() {
        binding.homeContent.rvFilmstrip.visibility = View.GONE

        Thread {
            val frames = mergeVideosUris.mapIndexed { index, uri ->
                val retriever = MediaMetadataRetriever()
                val bitmap = try {
                    retriever.setDataSource(requireContext(), uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 70)
                    } else {
                        retriever.getFrameAtTime(1000000)
                    }
                } catch (e: Exception) { null } finally {
                    retriever.release()
                }

                val name = getBetterName(uri).substringBeforeLast(".")
                VideoFrameAdapter.VideoFrame(index.toLong(), bitmap, name, canRemove = true)
            }.toMutableList()

            frames.add(VideoFrameAdapter.VideoFrame(0, null, "", isAddButton = true))

            requireActivity().runOnUiThread {
                filmstripAdapter = VideoFrameAdapter(frames, { /* Preview? */ }, {
                    mergePickerLauncher.launch("video/*")
                }, { pos ->
                    if (pos < mergeVideosUris.size) {
                        mergeVideosUris.removeAt(pos)
                        updateMergeFilmstrip()
                    }
                })
                binding.homeContent.rvFilmstrip.apply {
                    layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                    adapter = filmstripAdapter
                    visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private var isUserTouchingFilmstrip = false
    private var lastManualScrollTime = 0L
    private var pendingSeekMs: Long = -1L
    private val hideResolutionRunnable = Runnable {
        _binding?.homeContent?.tvResolutionOverlay?.visibility = View.GONE
    }
    private val updateSubtitleTask = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                val currentPos = player.currentPosition.toInt()

                if (!isProcessing) {
                    // ... (lógica de subtítulos mantenida)
                    val currentSub = subtitleList.find { currentPos.toLong() in it.startTime..it.endTime }

                    if (currentSub != null) {
                        _binding?.homeContent?.tvSubtitleOverlay?.let { tv ->
                            val subText = if (currentSub.translation != null) "${currentSub.original}\n${currentSub.translation}" else currentSub.original
                            if (tv.text != subText) tv.text = subText
                            tv.visibility = View.VISIBLE
                        }
                    } else {
                        _binding?.homeContent?.tvSubtitleOverlay?.let { tv ->
                            val currentText = tv.text.toString()
                            if (!currentText.contains("%") &&
                                !currentText.contains(getString(R.string.procesando_archivo)) &&
                                !currentText.contains("Convirtiendo")) {
                                if (currentText.isNotEmpty()) tv.text = ""
                            }
                        }
                    }
                }

                if (player.isPlaying) {
                    binding.homeContent.videoSeekBar.max = player.duration.toInt()
                    binding.homeContent.videoSeekBar.progress = currentPos
                    binding.homeContent.tvCurrentTime.text = formatTime(currentPos)
                    binding.homeContent.tvTotalTime.text = formatTime(player.duration.toInt())

                    // Sincronización con la timeline: scrollTo() directo, sin diffs ni
                    // umbrales — es barato e idempotente, y solo corre cuando el usuario
                    // no está arrastrando, así que nunca compite con su gesto.
                    if (!isUserTouchingFilmstrip && System.currentTimeMillis() - lastManualScrollTime > 300) {
                        val timeline = binding.homeContent.filmstripTimeline
                        if (timeline.pxPerMs > 0f) {
                            binding.homeContent.hsvFilmstrip.scrollTo(timeline.timeMsToPx(currentPos.toLong()), 0)
                        }
                    }
                }
            }
            handler.postDelayed(this, 50) // Alta frecuencia (20fps) para fluidez total
        }
    }
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selectedFolderUri = uri
                saveFolderUri(uri)
                loadVideosFromSelectedFolder(uri)
            }
        }
    }

    private val subtitlePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedSubtitleUri = null
            selectedAssSubtitleUri = null
            try {
                when (getFileExtension(it)) {
                    "ass", "ssa" -> {
                        selectedAssSubtitleUri = it
                        requireContext().contentResolver.openInputStream(it)?.use { stream -> parseAss(stream) }
                    }
                    "lrc" -> {
                        requireContext().contentResolver.openInputStream(it)?.use { stream -> parseLrc(stream) }
                    }
                    else -> { // srt por defecto
                        selectedSubtitleUri = it
                        requireContext().contentResolver.openInputStream(it)?.use { stream -> parseSrt(stream) }
                    }
                }
            } catch (e: Exception) {
                Log.e("parseSubtitles", "Error al leer el archivo: ${e.message}")
            }

            if (subtitleList.isNotEmpty()) {
                Toast.makeText(requireContext(), R.string.subtitulos_cargados, Toast.LENGTH_SHORT).show()
                workshopSubtitleIndex = 0
                val lrcStyleText = StringBuilder()
                subtitleList.forEach { sub -> lrcStyleText.append("${formatTimeLrc(sub.startTime.toInt())} ${sub.original}\n") }
                binding.homeContent.etSubtitleWorkshop.setText(lrcStyleText.toString())
            } else {
                Toast.makeText(requireContext(), "No se reconocieron subtítulos en el archivo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val audioForVideoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            if (videoPlaylist.isNotEmpty()) {
                agregarAudioAVideo(videoPlaylist[currentIndex], it)
            }
        }
    }

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            videoPlaylist.clear()
            videoPlaylist.addAll(uris)
            currentIndex = 0
            reproducirVideoActual()

            if (uris.size > 1) {
                mostrarDialogoUnirVideos(uris)
            }
        }
    }

    private fun detectarStreams(file: File): Pair<List<Int>, List<Int>> {
        val audioIndices = mutableListOf<Int>()
        val subtitleIndices = mutableListOf<Int>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("audio/") -> audioIndices.add(i)
                    mime.startsWith("text/") || mime.startsWith("application/") -> subtitleIndices.add(i)
                }
            }
        } catch (e: Exception) {
            Log.e("MediaExtractorCheck", "Error leyendo tracks: ${e.message}")
        } finally {
            extractor.release()
        }
        return audioIndices to subtitleIndices
    }

    private fun refrescarListaVideos() {
        val folderUri = selectedFolderUri ?: loadSavedFolderUri()
        if (folderUri != null) {
            loadVideosFromSelectedFolder(folderUri)
        } else {
            val youtubeFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "0 VIDEO")
            loadVideosFromDirectory(youtubeFolder)
        }
    }
    private fun getBetterName(uri: Uri): String {
        var name: String? = null
        val context = requireContext()
        val resolver = context.contentResolver

        var uriSize = 0L
        try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) uriSize = cursor.getLong(0)
            }
        } catch (e: Exception) {}

        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DATA)
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0) ?: ""
                    val title = cursor.getString(1) ?: ""
                    val path = cursor.getString(2) ?: ""

                    name = if (displayName.matches(Regex("^\\d+\\..*")) && title.isNotBlank() && !title.matches(Regex("^\\d+$"))) {
                        if (title.contains(".")) title else "$title.mp4"
                    } else {
                        displayName
                    }

                    if (name!!.matches(Regex("^\\d+\\..*")) && path.isNotBlank()) {
                        val file = File(path)
                        if (file.exists() && !file.name.matches(Regex("^\\d+\\..*"))) {
                            name = file.name
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        if (name == null || name!!.matches(Regex("^\\d+\\..*"))) {
            val youtubeFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "0 VIDEO")
            if (youtubeFolder.exists() && uriSize > 0) {
                youtubeFolder.listFiles()?.find { it.length() == uriSize }?.let {
                    name = it.name
                }
            }
        }

        if (name == null || name!!.matches(Regex("^\\d+\\..*"))) {
            try {
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                name = doc?.name
            } catch (e: Exception) {}
        }

        return name ?: uri.lastPathSegment ?: "Video_Editor.mp4"
    }

    private fun mostrarDialogoUnirVideos(uris: List<Uri>) {
        val videoItems = uris.map { uri ->
            val name = getBetterName(uri)
            val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(requireContext(), uri)
            val size = doc?.length() ?: 0L
            val sizeMb = "%.2f MB".format(size / (1024.0 * 1024.0))
            MergeVideoItem(uri, name, sizeMb)
        }.toMutableList()

        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setPadding(0, 12, 0, 12)
            clipToPadding = false
        }

        val adapter = MergeVideoAdapter(videoItems)
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                Collections.swap(videoItems, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(recyclerView)

        AlertDialog.Builder(requireContext())
            .setTitle("Reordenar Videos para Unir")
            .setView(recyclerView)
            .setPositiveButton("UNIR") { _, _ ->
                unirVideos(videoItems.map { it.uri })
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    data class MergeVideoItem(val uri: Uri, val name: String, val details: String)

    inner class MergeVideoAdapter(private val items: List<MergeVideoItem>) : RecyclerView.Adapter<MergeVideoAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvFilename)
            val tvDetails: TextView = v.findViewById(R.id.tvDetails)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_batch_song, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvDetails.text = item.details
        }
        override fun getItemCount() = items.size
    }

    private fun mostrarSelectorCalidad(uris: List<Uri>) {
        val calidades = arrayOf(
            "Excelente (320kbps) - Archivo grande",
            "Muy buena (256kbps) - Calidad alta",
            "Buena (192kbps) - Balance ideal",
            "Estándar (128kbps) - Tamaño pequeño",
            "Baja (64kbps) - Mínimo tamaño"
        )
        val valoresCalidad = arrayOf(0, 2, 4, 5, 7)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.calidad_mp3)
            .setItems(calidades) { _, which ->
                val calidad = valoresCalidad[which]
                convertirAudiosAMp3(uris, calidad)
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                selectedAudioUris.clear()
            }
            .show()
    }

    private fun unirVideos(uris: List<Uri>) {
        Toast.makeText(requireContext(), "Uniendo ${uris.size} videos...", Toast.LENGTH_LONG).show()
        val totalDuration = uris.sumOf { getMediaDuration(it) }
        mostrarProgreso(totalDuration)

        Thread {
            try {
                val archivos = uris.mapIndexed { i, uri ->
                    cacheUriToFile(uri, "merge_input_$i.mp4")
                }

                val listaFile = File(requireContext().cacheDir, "merge_list.txt")
                listaFile.writeText(archivos.joinToString("\n") { "file '${it.absolutePath}'" })

                val nombreSalida = "Video_Unido_${System.currentTimeMillis()}.mp4"
                val outputFile = File(requireContext().cacheDir, "merge_output.mp4")
                if (outputFile.exists()) {
                    outputFile.delete()
                }

                val command = "-f concat -safe 0 -i \"${listaFile.absolutePath}\" -c copy \"${outputFile.absolutePath}\""
                Log.d("FFmpegMerge", "Comando: $command")

                FFmpegKit.executeAsync(command, { session ->
                    if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                        saveToDownloads(outputFile, nombreSalida, "video/mp4")
                    } else {
                        Log.e("FFmpegMerge", session.allLogsAsString)
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), R.string.error_al_unir, Toast.LENGTH_SHORT).show()
                        }
                    }
                    archivos.forEach { it.delete() }
                    listaFile.delete()
                    if (outputFile.exists()) outputFile.delete()
                    ocultarProgreso()
                }, { stats ->
                    actualizarProgreso(stats.time, totalDuration)
                })
            } catch (exception: Exception) {
                Log.e("FFmpegMerge", "Error preparando archivos: ${exception.message}")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), R.string.error_preparando_archivos, Toast.LENGTH_SHORT).show()
                }
                ocultarProgreso()
            }
        }.start()
    }


    private fun getVideoWidth(file: File): Int {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 624
            retriever.release()
            width
        } catch (exception: Exception) {
            624
        }
    }
    private fun parseAss(inputStream: java.io.InputStream) {
        subtitleList.clear()
        val cues = LinkedHashMap<Pair<Long, Long>, Subtitle>()

        inputStream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (!line.startsWith("Dialogue:")) return@forEachLine

            // Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            val parts = line.removePrefix("Dialogue:").trim().split(",", limit = 10)
            if (parts.size < 10) return@forEachLine

            val start = parseAssTimeToMillis(parts[1].trim())
            val end = parseAssTimeToMillis(parts[2].trim())
            val style = parts[3].trim()
            if (style == "Watermark") return@forEachLine

            val text = cleanAssText(parts[9])
            val key = start to end
            val existing = cues[key]

            cues[key] = if (style == "Translation") {
                existing?.copy(translation = text) ?: Subtitle(start, end, "", text)
            } else {
                existing?.copy(original = text) ?: Subtitle(start, end, text, existing?.translation)
            }
        }

        subtitleList.addAll(cues.values.sortedBy { it.startTime })
        Log.d("parseAss", "Subtítulos ASS cargados: ${subtitleList.size}")
    }

    private fun parseAssTimeToMillis(time: String): Long {
        return try {
            // Formato H:MM:SS.cc (centésimas)
            val parts = time.split(":")
            val h = parts[0].trim().toLong()
            val m = parts[1].trim().toLong()
            val secParts = parts[2].trim().split(".")
            val s = secParts[0].toLong()
            val centis = if (secParts.size > 1) secParts[1].toLong() else 0L
            (h * 3_600_000) + (m * 60_000) + (s * 1_000) + (centis * 10)
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanAssText(raw: String): String {
        return raw
            .replace(Regex("\\{[^}]*\\}"), "") // quita override tags {\...}
            .replace("\\N", " ")
            .replace("\\n", " ")
            .trim()
    }
    private fun parseSrt(inputStream: java.io.InputStream) {
        subtitleList.clear()
        val lines = inputStream.bufferedReader().readLines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains(" --> ")) {
                val times = line.split(" --> ")
                val start = parseTimeToMillis(times[0].trim())
                val end = parseTimeToMillis(times[1].trim())
                val textLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank()) {
                    textLines.add(lines[j])
                    j++
                }
                if (textLines.isNotEmpty()) {
                    val original = textLines[0]
                    val translation = if (textLines.size > 1) textLines.drop(1).joinToString(" ") else null
                    subtitleList.add(Subtitle(start, end, original, translation))
                }
                i = j
            } else {
                i++
            }
        }
        Log.d("parseSrt", "Subtítulos cargados: ${subtitleList.size}")
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
        } catch (e: Exception) {
            0L
        }
    }

    private fun loadVideosFromDirectory(directory: File) {
        downloadVideoList.clear()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && isVideoFile(file)) {
                downloadVideoList.add(Pair(file.name, Uri.fromFile(file)))
            }
        }
        binding.homeContent.rvDownloads.adapter = DownloadVideoAdapter(downloadVideoList) { clickedUri ->
            videoPlaylist.clear()
            videoPlaylist.addAll(downloadVideoList.map { it.second })
            currentIndex = downloadVideoList.indexOfFirst { it.second == clickedUri }.coerceAtLeast(0)
            reproducirVideoActual()
        }
    }

    private fun isVideoFile(file: File): Boolean {
        val extensions = arrayOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm")
        return extensions.any { file.extension.lowercase() == it }
    }
    private fun isVideoExtension(name: String): Boolean {
        val extensions = arrayOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm")
        return extensions.any { name.substringAfterLast('.', "").lowercase() == it }
    }
    private fun loadVideosFromSelectedFolder(uri: Uri) {
        downloadVideoList.clear()
        val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)
        pickedDir?.listFiles()?.forEach { file ->
            val name = file.name
            if (name != null && (file.type?.startsWith("video/") == true || isVideoExtension(name))) {
                downloadVideoList.add(Pair(name, file.uri))
            }
        }
        binding.homeContent.rvDownloads.adapter = DownloadVideoAdapter(downloadVideoList) { clickedUri ->
            videoPlaylist.clear()
            videoPlaylist.addAll(downloadVideoList.map { it.second })
            currentIndex = downloadVideoList.indexOfFirst { it.second == clickedUri }.coerceAtLeast(0)
            reproducirVideoActual()
        }
    }

    private fun loadVideosFromDownloads() {
        downloadVideoList.clear()
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media._ID)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        requireContext().contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                downloadVideoList.add(Pair(name, contentUri))
            }
        }
        binding.homeContent.rvDownloads.adapter = DownloadVideoAdapter(downloadVideoList) { clickedUri ->
            videoPlaylist.clear()
            videoPlaylist.addAll(downloadVideoList.map { it.second })
            currentIndex = downloadVideoList.indexOfFirst { it.second == clickedUri }.coerceAtLeast(0)
            reproducirVideoActual()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        updateSlideshowFilmstrip()

        binding.imageLayout.visibility = View.GONE
        binding.homeContent.rvDownloads.layoutManager = LinearLayoutManager(requireContext())

        val savedUri = loadSavedFolderUri()
        if (savedUri != null) {
            loadVideosFromSelectedFolder(savedUri)
        } else {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
                val youtubeFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "0 VIDEO")
                if (!youtubeFolder.exists()) youtubeFolder.mkdirs()
                loadVideosFromDirectory(youtubeFolder)
            } else {
                requestPermissionLauncher.launch(permission)
            }
        }

        binding.homeContent.tvSubtitleOverlay.setShadowLayer(3f, 2f, 2f, Color.BLACK)
        handler.post(updateSubtitleTask)
        setupListeners()
        initializePlayer()
        setupVideoListeners()

        setFixedIcon(binding.homeContent.btnPrevVideo, R.drawable.ic_skip_previous)
        setFixedIcon(binding.homeContent.btnNextVideo, R.drawable.ic_skip_next)
        setFixedIcon(binding.homeContent.btnMergeVideos, R.drawable.ic_unir)
        setFixedIcon(binding.homeContent.btnSplit, R.drawable.ic_cut)
        setFixedIcon(binding.homeContent.btnYoutubeDownload, R.drawable.ic_youtube, 130, 29)
        setFixedIcon(binding.homeContent.btnApplyFade, R.drawable.ic_fade)
        setPlayPauseIcon(false)

        fullscreenGestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = binding.homeContent.videoContainer.width
                val x = e.x
                if (x < width * 0.35) {
                    exoPlayer?.let { it.seekTo((it.currentPosition - 7000).coerceAtLeast(0)) }
                    Toast.makeText(requireContext(), "-7s", Toast.LENGTH_SHORT).show()
                } else if (x > width * 0.65) {
                    exoPlayer?.let { it.seekTo((it.currentPosition + 7000).coerceAtMost(it.duration)) }
                    Toast.makeText(requireContext(), "+7s", Toast.LENGTH_SHORT).show()
                } else {
                    toggleFullscreen()
                }
                return true
            }
        })
        binding.homeContent.videoContainer.setOnTouchListener { v, event ->
            fullscreenGestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }

        binding.imageLayout.titleWelcome.text = getString(R.string.supported_formats)
        enterTransition = MaterialFadeThrough().addTarget(binding.contentContainer)
        reenterTransition = MaterialFadeThrough().addTarget(binding.contentContainer)

        checkForMargins()
        loadProfile()
        setupTitle()

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }
        view.doOnLayout { adjustPlaylistButtons() }
    }

    private fun initializePlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(requireContext()).build().also { player ->
                binding.homeContent.videoPlayer.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            binding.homeContent.videoSeekBar.max = player.duration.toInt()
                            binding.homeContent.tvTotalTime.text = formatTime(player.duration.toInt())
                            actualizarVisibilidadSelectorPistas()
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        setPlayPauseIcon(isPlaying)
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        _binding?.homeContent?.tvResolutionOverlay?.let { tv ->
                            tv.text = "${videoSize.width}x${videoSize.height}"
                            tv.visibility = View.VISIBLE
                        }
                        handler.removeCallbacks(hideResolutionRunnable)
                        handler.postDelayed(hideResolutionRunnable, 3000)
                    }
                })
            }
        }
    }

    private fun setupVideoListeners() {
        
        binding.homeContent.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) exoPlayer?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.homeContent.btnPlayPause.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressTriggered = false

        binding.homeContent.btnPrevVideo.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressHandler.postDelayed({
                        longPressTriggered = true
                        exoPlayer?.let { it.seekTo((it.currentPosition - 5000).coerceAtLeast(0)) }
                    }, 1000)
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (!longPressTriggered) {
                        if (currentIndex > 0) {
                            currentIndex--
                            reproducirVideoActual()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> longPressHandler.removeCallbacksAndMessages(null)
            }
            true
        }

        binding.homeContent.btnNextVideo.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressHandler.postDelayed({
                        longPressTriggered = true
                        exoPlayer?.let { it.seekTo((it.currentPosition + 5000).coerceAtMost(it.duration)) }
                    }, 1000)
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (!longPressTriggered) {
                        if (currentIndex < videoPlaylist.size - 1) {
                            currentIndex++
                            reproducirVideoActual()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> longPressHandler.removeCallbacksAndMessages(null)
            }
            true
        }

        
        binding.homeContent.btnSetStart.setOnClickListener {
            exoPlayer?.let { binding.homeContent.etStartTime.setText(formatTime(it.currentPosition.toInt())) }
        }
        binding.homeContent.btnSetEnd.setOnClickListener {
            exoPlayer?.let { binding.homeContent.etEndTime.setText(formatTime(it.currentPosition.toInt())) }
        }
        binding.homeContent.btnSplit.setOnClickListener {
            val startTime = binding.homeContent.etStartTime.text.toString()
            val endTime = binding.homeContent.etEndTime.text.toString()
            if (startTime.isNotEmpty() && endTime.isNotEmpty() && videoPlaylist.isNotEmpty()) {
                splitVideo(videoPlaylist[currentIndex], startTime, endTime)
            } else {
                Toast.makeText(requireContext(), "Define los tiempos de corte", Toast.LENGTH_SHORT).show()
            }
        }

        

        binding.homeContent.btnMergeVideos.setOnClickListener {
            if (mergeVideosUris.isEmpty()) mergePickerLauncher.launch("video/*") else unirVideos(mergeVideosUris)
        }
  
        binding.homeContent.btnYoutubeDownload.setOnClickListener {
            findNavController().navigate(R.id.youtube_downloader_fragment)
        }

        binding.homeContent.btnApplyFade.setOnClickListener { aplicarFadeCombinado() }

        binding.homeContent.btnTrackSelector.setOnClickListener { mostrarSelectorPistas() }

        setupSubtitleWorkshopListeners()
        setupToolStrip()
    }

    private fun setupToolStrip() {
        val tools = listOf(
            ToolButtonItem("folder", R.drawable.ic_vfolder, "Carpeta"),
            ToolButtonItem("video", R.drawable.ic_vid, "Video"),
            ToolButtonItem("gif", R.drawable.ic_gif, "GIF"),
            ToolButtonItem("slideshow", R.drawable.ic_slide, "Slideshow"),
            ToolButtonItem("tageditor", R.drawable.ic_dashboard, "Mp3Tag"),
            ToolButtonItem("tomp3", R.drawable.ic_mp3, "A MP3"),
            ToolButtonItem("subs", R.drawable.ic_srt, "Subs"),
            ToolButtonItem("demux", R.drawable.ic_restore, "Demux"),
            ToolButtonItem("ass", R.drawable.ic_ass, "ASS"),
            ToolButtonItem("workshop", R.drawable.ic_edit, "Workshop"),
            ToolButtonItem("remux", R.drawable.ic_replace, "Remux"),
            ToolButtonItem("multiplex", R.drawable.ic_mkv, "Multiplex")
        )

        binding.homeContent.rvToolStrip.apply {
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            adapter = ToolButtonAdapter(tools) { id -> handleToolClick(id) }
            setHasFixedSize(true)
        }
    }

    private fun handleToolClick(id: String) {
        when (id) {
            "folder" -> folderPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            "video" -> videoPickerLauncher.launch("video/*")
            "gif" -> {
                if (videoPlaylist.isNotEmpty()) convertirVideoAGif(videoPlaylist[currentIndex])
                else Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            }
            "slideshow" -> {
                if (slideshowImages.isEmpty()) photosPickerLauncher.launch("image/*")
                else crearVideoDesdeFotos(slideshowImages)
            }
            "tageditor" -> startActivity(
                Intent(requireContext(), code.name.monkey.retromusic.activities.tageditor.BatchTagEditorActivity::class.java)
            )
            "tomp3" -> multiaudioPickerLauncher.launch("audio/*")
            "subs" -> subtitlePickerLauncher.launch("*/*")
            "demux" -> startDemuxFlow()
            "ass" -> {
                if (videoPlaylist.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
                    return
                }
                if (subtitleList.isEmpty()) {
                    Toast.makeText(requireContext(), "Primero cargá subtítulos con SUBS para previsualizarlos", Toast.LENGTH_SHORT).show()
                    return
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.incrustar_subtitulos_title)
                    .setMessage(R.string.incrustar_subtitulos_message)
                    .setPositiveButton(R.string.done) { _, _ -> hardcodearSubtitulosAss() }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            "workshop" -> toggleSubtitleWorkshop()
            "remux" -> {
                if (videoPlaylist.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
                    return
                }
                audioForVideoPickerLauncher.launch("audio/*")
            }
            "multiplex" -> {
                if (videoPlaylist.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
                    return
                }
                startMultiplexFlow()
            }
        }
    }

    private fun toggleSubtitleWorkshop() {
        val workshop = binding.homeContent.subtitleWorkshopContainer
        if (workshop.visibility == View.VISIBLE) {
            workshop.visibility = View.GONE
        } else {
            workshop.visibility = View.VISIBLE
            if (subtitleList.isNotEmpty()) {
                val lrcStyleText = StringBuilder()
                subtitleList.forEach {
                    lrcStyleText.append("${formatTimeLrc(it.startTime.toInt())} ${it.original}\n")
                }
                binding.homeContent.etSubtitleWorkshop.setText(lrcStyleText.toString())
            }
            binding.homeContent.btnWorkshopExportAss.setOnClickListener {
                exportWorkshopToAss()
            }
            binding.homeContent.cbWorkshopMtvInfo.setOnCheckedChangeListener { _, isChecked ->
                binding.homeContent.mtvInfoRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupSubtitleWorkshopListeners() {
        binding.homeContent.btnWorkshopStamp.setOnClickListener {
            handleWorkshopMarking()
        }

        binding.homeContent.btnWorkshopClearStamps.setOnClickListener {
            val currentText = binding.homeContent.etSubtitleWorkshop.text.toString()
            val cleanText = currentText.replace("\\[[^\\]]+\\]".toRegex(), "").trim()
            binding.homeContent.etSubtitleWorkshop.setText(cleanText)
        }

        binding.homeContent.btnWorkshopSave.setOnClickListener {
            exportWorkshopToSrt()
        }
    }


    private fun handleWorkshopMarking() {
        val et = binding.homeContent.etSubtitleWorkshop
        val pos = et.selectionStart
        val text = et.text.toString().replace("\r\n", "\n").replace("\r", "\n")

        if (text.isEmpty()) return

        // Encontrar los límites de la línea actual de forma precisa
        val lineStart = if (pos == 0) 0 else text.lastIndexOf("\n", pos - 1) + 1
        var lineEnd = text.indexOf("\n", pos)
        if (lineEnd == -1) lineEnd = text.length

        val fullLine = text.substring(lineStart, lineEnd)
        
        // Limpiar cualquier timestamp previo o espacio al inicio
        val cleanLine = fullLine.replace("\\[[^\\]]+\\]".toRegex(), "").trim()

        // Capturar la posición exacta del player en este instante
        val currentMs = exoPlayer?.currentPosition?.toInt() ?: 0
        val timeStamp = formatTimeLrc(currentMs)
        val newLineText = "$timeStamp $cleanLine"

        val updatedText = StringBuilder(text)
        updatedText.replace(lineStart, lineEnd, newLineText)
        
        et.setText(updatedText.toString())
        et.requestFocus()

        // Salto garantizado a la siguiente línea
        val nextLineStart = lineStart + newLineText.length + 1
        if (nextLineStart <= updatedText.length) {
            et.setSelection(nextLineStart)
        } else {
            // Si es la última línea, simplemente ir al final
            et.setSelection(updatedText.length)
        }
    }

    private fun formatTimeLrc(millis: Int): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (millis % 1000) / 10
        return String.format(Locale.getDefault(), "[%02d:%02d.%02d]", minutes, seconds, hundredths)
    }

    private fun lrcTimeToMs(time: String): Int {
        return try {
            val cleanTime = time.replace("[", "").replace("]", "")
            val parts = cleanTime.split(":")
            val minutes = parts[0].toInt()
            val secondsParts = parts[1].split(".")
            val seconds = secondsParts[0].toInt()
            val hundredths = secondsParts[1].toInt()
            (minutes * 60 * 1000) + (seconds * 1000) + (hundredths * 10)
        } catch (e: Exception) { 0 }
    }
    private data class WorkshopEntry(val startMs: Int, val endMs: Int, val text: String, val translation: String? = null)

    private fun buildWorkshopEntries(): List<WorkshopEntry> {
        val text = binding.homeContent.etSubtitleWorkshop.text.toString()
        if (text.isBlank()) return emptyList()

        val stampRegex = "\\[(\\d{2}:\\d{2}\\.\\d{2})\\]".toRegex()

        val translationLines = binding.homeContent.etSubtitleWorkshopTranslation.text.toString()
            .split("\n").filter { it.isNotBlank() }
            .map { it.replace(stampRegex, "").trim() }  // <- limpia el timestamp también aquí

        val lines = text.split("\n").filter { it.isNotBlank() }
        val entries = mutableListOf<WorkshopEntry>()
        var entryIndex = 0

        for (i in lines.indices) {
            val currentLine = lines[i]
            val match = stampRegex.find(currentLine) ?: continue
            val startTimeMs = lrcTimeToMs(match.value)
            val endTimeMs = if (i < lines.size - 1) {
                val nextMatch = stampRegex.find(lines[i + 1])
                if (nextMatch != null) lrcTimeToMs(nextMatch.value) else startTimeMs + 2000
            } else startTimeMs + 2000
            val subtitleText = currentLine.replace(stampRegex, "").trim()
            val translation = translationLines.getOrNull(entryIndex)
            entries.add(WorkshopEntry(startTimeMs, endTimeMs, subtitleText, translation))
            entryIndex++
        }
        return entries
    }
    private fun exportWorkshopToSrt() {
        val entries = buildWorkshopEntries()
        if (entries.isEmpty()) return

        val srtContent = StringBuilder()
        entries.forEachIndexed { index, entry ->
            srtContent.append("${index + 1}\n")
            srtContent.append("${formatTimeSrt(entry.startMs)} --> ${formatTimeSrt(entry.endMs)}\n")
            srtContent.append("${entry.text}\n\n")
        }

        val fileName = "Workshop_${System.currentTimeMillis()}.srt"
        val tempFile = File(requireContext().cacheDir, fileName)
        tempFile.writeText(srtContent.toString())
        saveToDownloads(tempFile, fileName, "text/plain")
    }

    private fun getVideoResolution(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(requireContext(), uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 360
            Pair(w, h)
        } catch (e: Exception) {
            Pair(640, 360)
        } finally {
            retriever.release()
        }
    }

    private fun exportWorkshopToAss() {
        val entries = buildWorkshopEntries()
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), "No hay texto con timestamps para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        val (playResX, playResY) = if (videoPlaylist.isNotEmpty()) {
            getVideoResolution(videoPlaylist[currentIndex])
        } else {
            Pair(640, 360)
        }
        // Tamaño proporcional al alto real del video, no un número fijo.
// ~6% del alto para el texto principal (estándar legible en subtítulos),
// ~4.5% para el watermark (más discreto). Clamps para evitar extremos
// en resoluciones muy chicas o muy grandes.
        val subtitleFontSize = (playResY * 0.06).toInt().coerceIn(16, 60)
        val watermarkFontSize = (playResY * 0.045).toInt().coerceIn(12, 45)
        val addMtvInfo = binding.homeContent.cbWorkshopMtvInfo.isChecked
        val artist = binding.homeContent.etMtvArtist.text.toString().trim()
        val song = binding.homeContent.etMtvSong.text.toString().trim()
        val album = binding.homeContent.etMtvYear.text.toString().trim()
        val originalItalic = if (binding.homeContent.cbOriginalItalic.isChecked) -1 else 0
        val translationItalic = if (binding.homeContent.cbTranslationItalic.isChecked) -1 else 0

        fun msToAss(ms: Int): String {
            val totalSeconds = ms / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            val centis = (ms % 1000) / 10
            return String.format(Locale.getDefault(), "%d:%02d:%02d.%02d", h, m, s, centis)
        }

        val ass = StringBuilder()
        ass.append("[Script Info]\n")
        ass.append("Title: Workshop Export\n")
        ass.append("ScriptType: v4.00+\n")
        ass.append("PlayResX: $playResX\n")
        ass.append("PlayResY: $playResY\n")
        ass.append("WrapStyle: 0\n")
        ass.append("ScaledBorderAndShadow: yes\n\n")
        ass.append("[V4+ Styles]\n")
        ass.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        // Original: abajo, blanco
        ass.append("Style: Original,Roboto,$subtitleFontSize,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,$originalItalic,0,0,100,100,0,0,1,2,1,2,20,20,25,1\n")
        ass.append("Style: Translation,Roboto,$subtitleFontSize,&H0000FFFF,&H000000FF,&H00000000,&H00000000,0,$translationItalic,0,0,100,100,0,0,1,2,1,8,20,20,15,1\n")
        if (addMtvInfo) {
            ass.append("Style: Watermark,Roboto,$watermarkFontSize,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,1,20,10,20,1\n")
        }
        ass.append("\n[Events]\n")
        ass.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")

        if (addMtvInfo && (artist.isNotBlank() || song.isNotBlank() || album.isNotBlank())) {
            val watermarkLines = listOfNotNull(
                artist.takeIf { it.isNotBlank() },
                song.takeIf { it.isNotBlank() },
                album.takeIf { it.isNotBlank() }
            )
            val watermarkText = watermarkLines.joinToString("\\N") // \N = salto de línea duro en ASS
            ass.append("Dialogue: 0,${msToAss(3000)},${msToAss(8000)},Watermark,,0,0,0,,$watermarkText\n")
        }

        entries.forEach { entry ->
            val start = msToAss(entry.startMs)
            val end = msToAss(entry.endMs)
            ass.append("Dialogue: 0,$start,$end,Original,,0,0,0,,${entry.text}\n")
            if (!entry.translation.isNullOrBlank()) {
                ass.append("Dialogue: 0,$start,$end,Translation,,0,0,0,,${entry.translation}\n")
            }
        }

        val fileName = "Workshop_${System.currentTimeMillis()}.ass"
        val tempFile = File(requireContext().cacheDir, fileName)
        tempFile.writeText(ass.toString())
        saveToDownloads(tempFile, fileName, "application/octet-stream")
        Toast.makeText(requireContext(), "ASS exportado (${playResX}x${playResY})", Toast.LENGTH_SHORT).show()
    }

    private fun formatTimeSrt(millis: Int): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val ms = millis % 1000
        return String.format(Locale.getDefault(), "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms)
    }

    private fun aplicarFadeCombinado() {
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            return
        }
        val fadeInSec = binding.homeContent.etFadeInDuration.text.toString().toDoubleOrNull() ?: 0.0
        val fadeOutSec = binding.homeContent.etFadeOutDuration.text.toString().toDoubleOrNull() ?: 0.0

        if (fadeInSec <= 0.0 && fadeOutSec <= 0.0) {
            Toast.makeText(requireContext(), "Define al menos un tiempo de fade in o fade out", Toast.LENGTH_SHORT).show()
            return
        }

        val videoUri = videoPlaylist[currentIndex]
        val totalDurationMs = getMediaDuration(videoUri)
        val totalDurationSec = totalDurationMs / 1000.0

        if (fadeInSec + fadeOutSec > totalDurationSec) {
            Toast.makeText(requireContext(), "La suma de fade in + fade out es mayor que la duración del video", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Aplicando fades...", Toast.LENGTH_LONG).show()
        mostrarProgreso(totalDurationMs)

        Thread {
            val videoFile = cacheUriToFile(videoUri, "input_fade.mp4")
            val outputFile = File(requireContext().cacheDir, "output_fade.mp4")
            if (outputFile.exists()) outputFile.delete()

            val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
            val fileName = "${originalName.substringBeforeLast(".")}_fade.mp4"

            // Una sola pasada: fade in y fade out en el mismo filtergraph, video y audio juntos.
            val videoFilters = mutableListOf<String>()
            val audioFilters = mutableListOf<String>()

            if (fadeInSec > 0.0) {
                videoFilters.add("fade=t=in:st=0:d=$fadeInSec")
                audioFilters.add("afade=t=in:st=0:d=$fadeInSec")
            }
            if (fadeOutSec > 0.0) {
                val fadeOutStart = (totalDurationSec - fadeOutSec).coerceAtLeast(0.0)
                videoFilters.add("fade=t=out:st=$fadeOutStart:d=$fadeOutSec")
                audioFilters.add("afade=t=out:st=$fadeOutStart:d=$fadeOutSec")
            }

            // "null"/"anull" son los filtros de paso directo (no confundir con -c copy,
            // que es un flag de codec y no existe dentro de un filtergraph).
            val vChain = if (videoFilters.isNotEmpty()) "[0:v]${videoFilters.joinToString(",")}[v]" else "[0:v]null[v]"
            val aChain = if (audioFilters.isNotEmpty()) "[0:a]${audioFilters.joinToString(",")}[a]" else "[0:a]anull[a]"
            val filterComplex = "$vChain;$aChain"

            val filterScriptFile = File(requireContext().cacheDir, "fade_filter.txt").apply { writeText(filterComplex) }

            val command = "-y -i \"${videoFile.absolutePath}\" -filter_complex_script \"${filterScriptFile.absolutePath}\" " +
                    "-map \"[v]\" -map \"[a]\" -c:v h264_mediacodec -b:v 2M -c:a aac \"${outputFile.absolutePath}\""

            FFmpegKit.executeAsync(command, { session ->
                if (ReturnCode.isSuccess(session.returnCode)) saveToDownloads(outputFile, fileName)
                else Log.e("FFmpegFade", session.allLogsAsString)
                ocultarProgreso()
                videoFile.delete()
                filterScriptFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, totalDurationMs) })
        }.start()
    }
    private fun buildAssFromSubtitleList(videoUri: Uri): File {
        val (playResX, playResY) = getVideoResolution(videoUri)
        val subtitleFontSize = (playResY * 0.06).toInt().coerceIn(16, 60)

        fun msToAss(ms: Long): String {
            val totalSeconds = ms / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            val centis = (ms % 1000) / 10
            return String.format(Locale.getDefault(), "%d:%02d:%02d.%02d", h, m, s, centis)
        }

        val ass = StringBuilder()
        ass.append("[Script Info]\n")
        ass.append("Title: Burn Export\n")
        ass.append("ScriptType: v4.00+\n")
        ass.append("PlayResX: $playResX\n")
        ass.append("PlayResY: $playResY\n")
        ass.append("WrapStyle: 0\n")
        ass.append("ScaledBorderAndShadow: yes\n\n")
        ass.append("[V4+ Styles]\n")
        ass.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        ass.append("Style: Original,Roboto,$subtitleFontSize,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,25,1\n")
        ass.append("Style: Translation,Roboto,$subtitleFontSize,&H0000FFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,8,20,20,15,1\n")
        ass.append("\n[Events]\n")
        ass.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")

        subtitleList.forEach { sub ->
            val start = msToAss(sub.startTime)
            val end = msToAss(sub.endTime)
            if (sub.original.isNotBlank()) {
                ass.append("Dialogue: 0,$start,$end,Original,,0,0,0,,${sub.original}\n")
            }
            if (!sub.translation.isNullOrBlank()) {
                ass.append("Dialogue: 0,$start,$end,Translation,,0,0,0,,${sub.translation}\n")
            }
        }

        val tempFile = File(requireContext().cacheDir, "burn_${System.currentTimeMillis()}.ass")
        tempFile.writeText(ass.toString())
        return tempFile
    }

    private fun clearSubtitles() {
        selectedSubtitleUri = null
        selectedAssSubtitleUri = null
        subtitleList.clear()
        _binding?.homeContent?.tvSubtitleOverlay?.text = ""
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val activity = requireActivity()
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        if (isFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            mainActivity.setBottomNavVisibility(visible = false, hideBottomSheet = true)
            scrollToTop()
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            mainActivity.setBottomNavVisibility(visible = true, hideBottomSheet = false)
        }
        setUiVisibilityForFullscreen(isFullscreen)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setUiVisibilityForFullscreen(fullscreen: Boolean) {
        val visibility = if (fullscreen) View.GONE else View.VISIBLE
        binding.appBarLayout.visibility = visibility
        binding.imageLayout.visibility = View.GONE
        binding.homeContent.absPlaylists.root.visibility = visibility
        binding.homeContent.cutRow.visibility = visibility
        binding.homeContent.rvToolStrip.visibility = visibility
        binding.homeContent.rvDownloads.visibility = visibility
        binding.homeContent.btnYoutubeDownload.visibility = visibility
        if (fullscreen) {
            binding.homeContent.rvFilmstrip.visibility = View.GONE
            binding.homeContent.hsvFilmstrip.visibility = View.GONE
            binding.homeContent.vFilmstripIndicator.visibility = View.GONE
        } else {
            // Restaurar solo la vista que corresponde al modo activo: la timeline de
            // video si hay una generada, o la lista de selección si estamos en modo
            // slideshow/unir. La otra debe seguir oculta.
            if (binding.homeContent.filmstripTimeline.pxPerMs > 0f) {
                binding.homeContent.hsvFilmstrip.visibility = View.VISIBLE
                binding.homeContent.vFilmstripIndicator.visibility = View.VISIBLE
            } else if (slideshowImages.isNotEmpty() || mergeVideosUris.isNotEmpty()) {
                binding.homeContent.rvFilmstrip.visibility = View.VISIBLE
            }
        }
        binding.homeContent.videoSeekBar.visibility = visibility
        binding.homeContent.tvCurrentTime.parent.let { if (it is View) it.visibility = visibility }
        binding.homeContent.btnPrevVideo.parent.let { if (it is View) it.visibility = visibility }

        val padding = if (fullscreen) 0 else (16 * resources.displayMetrics.density).toInt()
        binding.homeContent.contentPadding.setPadding(padding, padding, padding, padding)

        binding.homeContent.videoContainer.updateLayoutParams {
            height = if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else (250 * resources.displayMetrics.density).toInt()
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }

        binding.root.setBackgroundColor(if (fullscreen) Color.BLACK else "#1E1E1E".toColorInt())
        binding.homeContent.root.setBackgroundColor(if (fullscreen) Color.BLACK else "#1E1E1E".toColorInt())

        val containerParams = binding.container.layoutParams as CoordinatorLayout.LayoutParams
        containerParams.behavior = if (fullscreen) null else com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        binding.root.fitsSystemWindows = !fullscreen
        binding.container.layoutParams = containerParams
        binding.container.isNestedScrollingEnabled = !fullscreen
        binding.container.overScrollMode = if (fullscreen) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_ALWAYS

        binding.homeContent.videoPlayer.resizeMode = if (fullscreen) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        checkForMargins()
        binding.homeContent.videoContainer.requestLayout()
        if (fullscreen) scrollToTop()
    }

    private fun reproducirVideoActual() {
        if (videoPlaylist.isNotEmpty()) {
            clearSubtitles()
            savedPosition = 0
            val uri = videoPlaylist[currentIndex]
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                play()
            }
            binding.homeContent.btnPlayPause.text = getString(R.string.pause)
            generateFilmstrip(uri)
        }
    }

    /**
     * Cablea la timeline UNA vez por filmstrip generado: un touch listener (para
     * pausar/reanudar y avisarle al padre que no intercepte el gesto) y un único
     * callback de scroll que convierte scrollX -> tiempo con filmstripTimeline.pxToTimeMs().
     * No hay una segunda fuente de posición en ningún lado: mientras el usuario
     * toca, este callback es la única verdad; cuando no toca, el scroll viene de
     * nuestro propio scrollTo() al reproducir (ver updateSubtitleTask) y no hace
     * falta reaccionar a él.
     */
    private fun setupFilmstripScrubbing() {
        val hsv = binding.homeContent.hsvFilmstrip
        val timeline = binding.homeContent.filmstripTimeline

        hsv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Evita que el CoordinatorLayout padre robe el gesto a mitad de
                    // camino (causaba zigzag y layouts forzados a mitad del drag).
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    isUserTouchingFilmstrip = true
                    exoPlayer?.pause()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    isUserTouchingFilmstrip = false
                    lastManualScrollTime = System.currentTimeMillis()
                    if (pendingSeekMs >= 0) {
                        exoPlayer?.seekTo(pendingSeekMs)
                        pendingSeekMs = -1L
                    }
                    v.performClick()
                }
            }
            false // no consumir: dejar que el scroll nativo del HorizontalScrollView actúe
        }

        hsv.onScrollXChanged = { scrollX ->
            if (isUserTouchingFilmstrip && _binding != null) {
                val timeMs = timeline.pxToTimeMs(scrollX)
                pendingSeekMs = timeMs
                // Solo UI liviana durante el drag: el seek real pasa recién en
                // ACTION_UP, para no meter trabajo pesado de decodificación en
                // medio del gesto de scroll.
                binding.homeContent.tvCurrentTime.text = formatTime(timeMs.toInt())
                binding.homeContent.videoSeekBar.progress = timeMs.toInt()
            }
        }
    }

    private fun generateFilmstrip(uri: Uri) {
        binding.homeContent.rvFilmstrip.visibility = View.GONE
        binding.homeContent.hsvFilmstrip.visibility = View.GONE
        binding.homeContent.vFilmstripIndicator.visibility = View.GONE
        slideshowImages.clear()
        mergeVideosUris.clear()
        filmstripAdapter = null

        val context = requireContext()
        val density = resources.displayMetrics.density
        val thumbWidthPx = (70 * density).toInt()

        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                val durationSec = durationMs / 1000

                // CÁLCULO DINÁMICO: 1 frame cada 5 segundos. Mínimo 20, Máximo 100.
                val frameCount = (durationSec / 5).toInt().coerceIn(20, 100)
                val totalWidthPx = frameCount * thumbWidthPx
                val interval = durationMs / frameCount

                val frames = mutableListOf<code.name.monkey.retromusic.views.FilmstripTimelineView.Frame>()
                slideshowImages.clear()

                // Fallback: si un frame puntual falla (común cerca del final de videos
                // largos, donde no siempre hay un frame decodificable exacto en el
                // timestamp pedido), reusamos el último bitmap válido en vez de dejar
                // null. Un solo fallo no debe tirar abajo la tira entera.
                var lastGoodBitmap: Bitmap? = null

                fun extractFrameSafe(atMs: Long): Bitmap? {
                    return try {
                        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 70)
                        } else {
                            retriever.getFrameAtTime(atMs * 1000)
                        }
                        if (b != null) lastGoodBitmap = b
                        b ?: lastGoodBitmap
                    } catch (e: Exception) {
                        Log.w("Filmstrip", "Fallo extrayendo frame en ${atMs}ms: ${e.message}")
                        lastGoodBitmap
                    }
                }

                for (i in 0 until frameCount) {
                    val timeMs = i * interval
                    val bitmap = extractFrameSafe(timeMs)
                    frames.add(code.name.monkey.retromusic.views.FilmstripTimelineView.Frame(timeMs, bitmap))
                }

                // Último frame: pedir el timestamp EXACTO de duración suele fallar (no
                // hay frame decodificable en el último microsegundo). Retrocedemos medio
                // segundo, margen seguro para este problema conocido.
                val safeLastTimeMs = (durationMs - 500).coerceAtLeast(0)
                val lastBitmap = extractFrameSafe(safeLastTimeMs)
                frames.add(code.name.monkey.retromusic.views.FilmstripTimelineView.Frame(durationMs, lastBitmap))

                requireActivity().runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.homeContent.hsvFilmstrip.apply {
                        setPadding(0, 0, resources.displayMetrics.widthPixels, 0)
                        visibility = View.VISIBLE
                    }
                    binding.homeContent.filmstripTimeline.setTimeline(frames, durationMs, totalWidthPx)
                    setupFilmstripScrubbing()
                    binding.homeContent.vFilmstripIndicator.visibility = View.VISIBLE
                    generateWaveform(uri, totalWidthPx)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }.start()
    }

    private fun generateWaveform(uri: Uri, widthPx: Int) {
        val videoFile = cacheUriToFile(uri, "input_waveform.mp4")
        val outputFile = File(requireContext().cacheDir, "waveform.png")
        if (outputFile.exists()) outputFile.delete()

        // Comando FFmpeg para generar una imagen del espectro de audio
        val command = "-y -i \"${videoFile.absolutePath}\" -filter_complex \"aformat=channel_layouts=mono,showwavespic=s=${widthPx}x80:colors=#00BFFF\" -frames:v 1 \"${outputFile.absolutePath}\""

        FFmpegKit.executeAsync(command, { session ->
            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(outputFile.absolutePath)
                requireActivity().runOnUiThread {
                    // El waveform se agrega al MISMO canvas que el filmstrip: no hay
                    // una segunda vista que pueda desincronizarse.
                    _binding?.homeContent?.filmstripTimeline?.setWaveform(bitmap)
                }
            }
            videoFile.delete()
        })
    }


    private fun getFontDir(): File {
        val fontDir = File(requireContext().cacheDir, "subtitle_fonts").apply { if (!exists()) mkdirs() }
        val fontFile = File(fontDir, "roboto_regular.ttf")
        if (!fontFile.exists()) resources.openRawResource(R.raw.roboto_regular).use { input -> fontFile.outputStream().use { output -> input.copyTo(output) } }
        return fontDir
    }

    private fun getItalicFontDir(): File {
        val fontDir = File(requireContext().cacheDir, "subtitle_fonts").apply { if (!exists()) mkdirs() }
        val fontFile = File(fontDir, "roboto_italic.ttf")
        if (!fontFile.exists()) resources.openRawResource(R.raw.roboto_italic).use { input -> fontFile.outputStream().use { output -> input.copyTo(output) } }
        return fontDir
    }

    private fun buildDrawtextFilters(subtitles: List<Subtitle>, fontFileRegular: String, fontFileItalic: String, fontSize: Int): String {
        fun escape(text: String) = text.replace("\\", "\\\\").replace("'", "'\\\\\\''").replace(":", "\\\\:").replace(",", "\\\\,").replace("%", "%%")
        val escapedFontRegular = fontFileRegular.replace(":", "\\:").replace("\\", "/")
        val escapedFontItalic = fontFileItalic.replace(":", "\\:").replace("\\", "/")
        return subtitles.joinToString(",") { sub ->
            val startSec = sub.startTime / 1000.0
            val endSec = sub.endTime / 1000.0
            val originalFilter = "drawtext=fontfile='$escapedFontRegular':text='${escape(sub.original)}':enable='between(t,$startSec,$endSec)':x=(w-text_w)/2:y=h*0.85:fontsize=$fontSize:fontcolor=white:shadowcolor=black:shadowx=2:shadowy=2"
            if (sub.translation != null) {
                val translationFilter = "drawtext=fontfile='$escapedFontItalic':text='${escape(sub.translation)}':enable='between(t,$startSec,$endSec)':x=(w-text_w)/2:y=h*0.10:fontsize=$fontSize:fontcolor=yellow:shadowcolor=black:shadowx=2:shadowy=2"
                "$originalFilter,$translationFilter"
            } else originalFilter
        }
    }

    private fun mostrarProgreso(totalDurationMs: Long = 0) {
        isProcessing = true
        activity?.runOnUiThread {
            _binding?.homeContent?.progressBar?.let { pb -> pb.progress = 0; pb.isIndeterminate = totalDurationMs == 0L; pb.visibility = View.VISIBLE }
            _binding?.homeContent?.tvSubtitleOverlay?.let { tv -> tv.text = getString(R.string.procesando_archivo); tv.visibility = View.VISIBLE }
        }
    }

    private fun actualizarProgreso(currentTimeMs: Double, totalDurationMs: Long) {
        if (totalDurationMs <= 0) return
        val progress = ((currentTimeMs / totalDurationMs) * 100).toInt().coerceIn(0, 100)
        requireActivity().runOnUiThread {
            _binding?.homeContent?.progressBar?.let { pb -> 
                pb.isIndeterminate = false
                pb.progress = progress 
            }
            val currentText = _binding?.homeContent?.tvSubtitleOverlay?.text?.toString() ?: ""
            if (currentText.contains("Convirtiendo")) {
                // Preservar el prefijo de "Convirtiendo (X/Y)"
                val prefix = currentText.substringBefore(":")
                val name = currentText.substringAfter(":").substringBeforeLast("(").trim()
                _binding?.homeContent?.tvSubtitleOverlay?.text = "$prefix: $name ($progress%)"
            } else {
                _binding?.homeContent?.tvSubtitleOverlay?.text = "${getString(R.string.procesando_archivo)} ($progress%)"
            }
        }
    }

    private fun ocultarProgreso() {
        isProcessing = false
        activity?.runOnUiThread {
            _binding?.homeContent?.progressBar?.visibility = View.GONE
            _binding?.homeContent?.tvSubtitleOverlay?.text = ""
            _binding?.homeContent?.tvSubtitleOverlay?.visibility = View.GONE
        }
    }

    private fun getMediaDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(requireContext(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) { 0L } finally { retriever.release() }
    }

    private fun agregarAudioAVideo(videoUri: Uri, audioUri: Uri) {
        Toast.makeText(requireContext(), R.string.agregando_audio_msg, Toast.LENGTH_LONG).show()
        val duration = getMediaDuration(videoUri)
        mostrarProgreso(duration)
        Thread {
            val videoFile = cacheUriToFile(videoUri, "input_addaudio.mp4")
            val audioFile = cacheUriToFile(audioUri, "input_addaudio_track")
            val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
            val fileName = "${originalName.substringBeforeLast(".")}_audio.mp4"
            val outputFile = File(requireContext().cacheDir, "output_addaudio.mp4")
            if (outputFile.exists()) outputFile.delete()
            val command = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -map 0:v -map 1:a -c:v copy -c:a aac -shortest \"${outputFile.absolutePath}\""
            FFmpegKit.executeAsync(command, { session ->
                if (ReturnCode.isSuccess(session.returnCode)) saveToDownloads(outputFile, fileName)
                ocultarProgreso(); videoFile.delete(); audioFile.delete(); if (outputFile.exists()) outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, duration) })
        }.start()
    }

    private fun convertirVideoAGif(videoUri: Uri, fps: Int = 10, anchoMax: Int = 480) {
        Toast.makeText(requireContext(), R.string.creando_gif_msg, Toast.LENGTH_LONG).show()
        val duration = getMediaDuration(videoUri)
        mostrarProgreso(duration)
        Thread {
            val videoFile = cacheUriToFile(videoUri, "input_gif.mp4")
            val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
            val fileName = "${originalName.substringBeforeLast(".")}.gif"
            val outputFile = File(requireContext().cacheDir, "output_gif.gif")
            if (outputFile.exists()) outputFile.delete()
            val filterComplex = "[0:v]fps=$fps,scale=$anchoMax:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse"
            val filterScriptFile = File(requireContext().cacheDir, "gif_filter.txt").apply { writeText(filterComplex) }
            val command = "-y -i \"${videoFile.absolutePath}\" -filter_complex_script \"${filterScriptFile.absolutePath}\" \"${outputFile.absolutePath}\""
            FFmpegKit.executeAsync(command, { session ->
                if (ReturnCode.isSuccess(session.returnCode)) saveToDownloads(outputFile, fileName, "image/gif")
                ocultarProgreso(); videoFile.delete(); filterScriptFile.delete(); if (outputFile.exists()) outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, duration) })
        }.start()
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds) else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun setPlayPauseIcon(isPlaying: Boolean) {
        val sizePx = (18 * resources.displayMetrics.density).toInt()
        val icon = ContextCompat.getDrawable(requireContext(), if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        icon?.setBounds(0, 0, sizePx, sizePx)
        binding.homeContent.btnPlayPause.text = null
        binding.homeContent.btnPlayPause.setCompoundDrawables(null, icon, null, null)
        binding.homeContent.btnPlayPause.gravity = android.view.Gravity.CENTER
        binding.homeContent.btnPlayPause.setPadding(0, 0, 0, 0)
        binding.homeContent.btnPlayPause.compoundDrawablePadding = 0
        binding.homeContent.btnPlayPause.post {
            val verticalPad = ((binding.homeContent.btnPlayPause.height - sizePx) / 2).coerceAtLeast(0)
            binding.homeContent.btnPlayPause.setPadding(0, verticalPad, 0, verticalPad)
        }
    }

    private fun setFixedIcon(button: android.widget.Button, drawableRes: Int, widthDp: Int = 18, heightDp: Int = widthDp) {
        val density = resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val icon = ContextCompat.getDrawable(requireContext(), drawableRes)
        icon?.setBounds(0, 0, widthPx, heightPx)
        button.setAllCaps(false); button.maxLines = 1
        if (button.text.isNullOrEmpty()) {
            button.text = null; button.setCompoundDrawables(null, icon, null, null); button.gravity = android.view.Gravity.CENTER; button.setPadding(0, 0, 0, 0); button.compoundDrawablePadding = 0
            button.post { val verticalPad = ((button.height - heightPx) / 2).coerceAtLeast(0); button.setPadding(0, verticalPad, 0, verticalPad) }
        } else {
            button.setCompoundDrawables(null, icon, null, null)
            val verticalPaddingPx = (1.5 * density).toInt()
            button.compoundDrawablePadding = (0.8 * density).toInt()
            button.setPadding(0, verticalPaddingPx, 0, verticalPaddingPx); button.gravity = android.view.Gravity.CENTER
        }
    }

    private fun adjustPlaylistButtons() {
        val buttons = listOf(binding.homeContent.absPlaylists.history, binding.homeContent.absPlaylists.lastAdded, binding.homeContent.absPlaylists.topPlayed, binding.homeContent.absPlaylists.actionShuffle)
        buttons.maxOf { it.lineCount }.let { maxLineCount -> buttons.forEach { it.setLines(maxLineCount) } }
    }

    private fun setupListeners() {
        binding.imageLayout.bannerImage?.setOnClickListener { findNavController().navigate(R.id.user_info_fragment, null, null, FragmentNavigatorExtras(binding.imageLayout.userImage to "user_image")); reenterTransition = null }
        binding.homeContent.absPlaylists.lastAdded.setOnClickListener { findNavController().navigate(R.id.detailListFragment, bundleOf(EXTRA_PLAYLIST_TYPE to LAST_ADDED_PLAYLIST)); setSharedAxisYTransitions() }
        binding.homeContent.absPlaylists.topPlayed.setOnClickListener { findNavController().navigate(R.id.detailListFragment, bundleOf(EXTRA_PLAYLIST_TYPE to TOP_PLAYED_PLAYLIST)); setSharedAxisYTransitions() }
        binding.homeContent.absPlaylists.actionShuffle.setOnClickListener { libraryViewModel.shuffleSongs() }
        binding.homeContent.absPlaylists.history.setOnClickListener { findNavController().navigate(R.id.detailListFragment, bundleOf(EXTRA_PLAYLIST_TYPE to HISTORY_PLAYLIST)); setSharedAxisYTransitions() }
        binding.imageLayout.userImage.setOnClickListener { findNavController().navigate(R.id.user_info_fragment, null, null, FragmentNavigatorExtras(binding.imageLayout.userImage to "user_image")) }
    }

    private fun setupTitle() { binding.appBarLayout.toolbar.setNavigationOnClickListener { findNavController().navigate(R.id.action_search, null, navOptions) }; binding.appBarLayout.title = getString(R.string.video_editor) }
    private fun loadProfile() { binding.imageLayout.bannerImage?.let { Glide.with(requireContext()).load(RetroGlideExtension.getBannerModel()).profileBannerOptions(RetroGlideExtension.getBannerModel()).into(it) }; Glide.with(requireActivity()).load(RetroGlideExtension.getUserModel()).userProfileOptions(RetroGlideExtension.getUserModel(), requireContext()).into(binding.imageLayout.userImage) }
    private fun checkForMargins() { binding.container.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = if (mainActivity.isBottomNavVisible && !isFullscreen) dip(R.dimen.bottom_nav_height) else 0 } }
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) { inflater.inflate(R.menu.menu_main, menu); menu.removeItem(R.id.action_grid_size); menu.removeItem(R.id.action_layout_type); menu.removeItem(R.id.action_sort_order); menu.findItem(R.id.action_settings)?.setShowAsAction(1); val toolbar = binding.appBarLayout.toolbar; ToolbarContentTintHelper.handleOnCreateOptionsMenu(requireContext(), toolbar, menu, ATHToolbarActivity.getToolbarBackgroundColor(toolbar)) }
    override fun scrollToTop() { binding.container.scrollTo(0, 0); binding.appBarLayout.setExpanded(true) }
    fun setSharedAxisYTransitions() { exitTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true).addTarget(CoordinatorLayout::class.java); reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false) }
    private fun saveFolderUri(uri: Uri) { requireContext().getSharedPreferences("video_prefs", Context.MODE_PRIVATE).edit { putString(PREF_SELECTED_FOLDER_URI, uri.toString()) } }
    private fun loadSavedFolderUri(): Uri? = requireContext().getSharedPreferences("video_prefs", Context.MODE_PRIVATE).getString(PREF_SELECTED_FOLDER_URI, null)?.toUri()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig);
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val visibility = if (isLandscape) View.GONE else View.VISIBLE

        // Recalcular el padding derecho de la timeline para el nuevo ancho de pantalla
        // (el padding derecho es lo que permite scrollear hasta que el último frame
        // llegue al indicador fijo de la izquierda).
        if (binding.homeContent.hsvFilmstrip.visibility == View.VISIBLE) {
            binding.homeContent.hsvFilmstrip.setPadding(0, 0, resources.displayMetrics.widthPixels, 0)
        }

        binding.appBarLayout.visibility = visibility
        val playbackVisibility = if (isLandscape && !isFullscreen) View.GONE else View.VISIBLE; binding.homeContent.videoSeekBar.visibility = playbackVisibility; binding.homeContent.btnPrevVideo.parent.let { if (it is View) it.visibility = playbackVisibility }; binding.homeContent.tvCurrentTime.parent.let { if (it is View) it.visibility = playbackVisibility }
        if (!isFullscreen) { binding.homeContent.videoContainer.layoutParams.height = if (isLandscape) ViewGroup.LayoutParams.MATCH_PARENT else (250 * resources.displayMetrics.density).toInt(); binding.homeContent.videoPlayer.resizeMode = if (isLandscape) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
        else { binding.homeContent.videoContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT; binding.homeContent.videoContainer.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT; binding.homeContent.videoPlayer.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) { R.id.action_settings -> { findNavController().navigate(R.id.settings_fragment, null, navOptions); true }; R.id.action_import_playlist -> { ImportPlaylistDialog().show(childFragmentManager, "ImportPlaylist"); true }; R.id.action_add_to_playlist -> { CreatePlaylistDialog.create(emptyList()).show(childFragmentManager, "ShowCreatePlaylistDialog"); true }; else -> false }
    override fun onPrepareMenu(menu: Menu) { super.onPrepareMenu(menu); ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), binding.appBarLayout.toolbar) }
    override fun onPause() { super.onPause(); exoPlayer?.let { savedPosition = it.currentPosition.toInt() } }
    override fun onResume() { super.onResume(); checkForMargins(); exitTransition = null; refrescarListaVideos(); libraryViewModel.forceReload(ReloadType.Songs); libraryViewModel.forceReload(ReloadType.Albums); if (_binding != null && videoPlaylist.isNotEmpty() && savedPosition > 0) { exoPlayer?.apply { seekTo(savedPosition.toLong()); if (wasPlayingBeforePause) { play(); setPlayPauseIcon(true) } } } }

    override fun onDestroyView() {
        if (isFullscreen) { requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView).show(WindowInsetsCompat.Type.systemBars()); mainActivity.setBottomNavVisibility(visible = true, hideBottomSheet = false) }
        exoPlayer?.let { if (it.isPlaying) savedPosition = it.currentPosition.toInt(); it.release() }; exoPlayer = null; handler.removeCallbacks(updateSubtitleTask); handler.removeCallbacks(hideResolutionRunnable); _binding = null; super.onDestroyView()
    }



    private fun createMkvWithSubtitles(videoUri: Uri, subtitleUri: Uri, audioUri: Uri? = null) {
        val videoFile = cacheUriToFile(videoUri, "input_video.mp4"); val subFile = cacheUriToFile(subtitleUri, "input_sub.srt"); val fileName = "Video_Subtitulado_${System.currentTimeMillis()}.mkv"; val contentValues = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName); put(MediaStore.MediaColumns.MIME_TYPE, "video/x-matroska"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/0 VIDEO") }
        val resolver = requireContext().contentResolver; val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI; val uri = resolver.insert(collectionUri, contentValues)
        if (uri != null) {
            val outputFile = File(requireContext().cacheDir, "temp_output.mkv"); if (outputFile.exists()) outputFile.delete()
            val command = if (audioUri != null) { val audioFile = cacheUriToFile(audioUri, "input_audio.mp3"); "-y -i \"${videoFile.absolutePath}\" -i \"${subFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -map 0:v -map 2:a -map 1:s -c copy -c:s srt -disposition:a:0 default -disposition:s:0 default \"${outputFile.absolutePath}\"" } else "-y -i \"${videoFile.absolutePath}\" -i \"${subFile.absolutePath}\" -c copy -c:s srt -disposition:s:0 default \"${outputFile.absolutePath}\""
            val duration = getMediaDuration(videoUri); mostrarProgreso(duration)
            FFmpegKit.executeAsync(command, { session ->
                if (ReturnCode.isSuccess(session.returnCode)) try { resolver.openOutputStream(uri)?.use { out -> outputFile.inputStream().use { it.copyTo(out) } }; requireActivity().runOnUiThread { Toast.makeText(requireContext(), R.string.guardado_en_downloads, Toast.LENGTH_SHORT).show() } } catch (e: Exception) { Log.e("FFmpegError", e.message ?: "") }
                ocultarProgreso(); videoFile.delete(); subFile.delete(); if (outputFile.exists()) outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, duration) })
        }
    }
    private fun getFileExtension(uri: Uri): String {
        val name = requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    }
    private fun parseLrc(inputStream: java.io.InputStream) {
        subtitleList.clear()
        val stampRegex = "\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\]".toRegex()
        val lines = inputStream.bufferedReader().readLines().filter { stampRegex.containsMatchIn(it) }

        for (i in lines.indices) {
            val match = stampRegex.find(lines[i]) ?: continue
            val startMs = lrcTimeToMs(match.value).toLong()
            val endMs = if (i < lines.size - 1) {
                val nextMatch = stampRegex.find(lines[i + 1])
                if (nextMatch != null) lrcTimeToMs(nextMatch.value).toLong() else startMs + 2000
            } else startMs + 2000
            val text = lines[i].replace(stampRegex, "").trim()
            if (text.isNotEmpty()) subtitleList.add(Subtitle(startMs, endMs, text, null))
        }
        Log.d("parseLrc", "Subtítulos LRC cargados: ${subtitleList.size}")
    }
    private fun hardcodearSubtitulosAss() {
        if (videoPlaylist.isEmpty() || subtitleList.isEmpty()) {
            Toast.makeText(requireContext(), R.string.selecciona_video_y_srt, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), R.string.incrustando_subtitulos_msg, Toast.LENGTH_LONG).show()
        val videoUri = videoPlaylist[currentIndex]
        val duration = getMediaDuration(videoUri)
        mostrarProgreso(duration)

        Thread {
            val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
            val fileName = "${originalName.substringBeforeLast(".")}_ass.mp4"

            val videoFile = cacheUriToFile(videoUri, "input_ass.mp4")
            val subFile = buildAssFromSubtitleList(videoUri)
            val outputFile = File(requireContext().cacheDir, "output_ass.mp4")
            if (outputFile.exists()) outputFile.delete()

            val vFilter = "subtitles=${subFile.absolutePath}:fontsdir=${getFontDir().absolutePath}"
            val command = "-y -i ${videoFile.absolutePath} -vf $vFilter -c:v h264_mediacodec -b:v 2M -c:a copy ${outputFile.absolutePath}"

            FFmpegKit.executeAsync(command, { session ->
                Log.d("FFmpegAssSubs", session.allLogsAsString)
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName)
                } else {
                    Log.e("FFmpegAssSubs", session.allLogsAsString)
                }
                ocultarProgreso()
                videoFile.delete()
                subFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, duration) })
        }.start()
    }

    private fun crearVideoDesdeFotos(uris: List<Uri>) {
        Toast.makeText(requireContext(), getString(R.string.creando_slideshow_msg, uris.size), Toast.LENGTH_LONG).show(); mostrarProgreso()
        Thread {
            try {
                val carpetaTemp = File(requireContext().cacheDir, "slideshow_${System.currentTimeMillis()}").apply { mkdirs() }
                uris.forEachIndexed { index, uri -> val destino = File(carpetaTemp, "img%03d.jpg".format(index)); requireContext().contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(destino).use { output -> input.copyTo(output) } } }
                val fileName = "Slideshow_${System.currentTimeMillis()}.mp4"; val outputFile = File(requireContext().cacheDir, "output_slideshow.mp4"); if (outputFile.exists()) outputFile.delete()
                val inputArgs = StringBuilder(); val filterComplex = StringBuilder()
                uris.forEachIndexed { index, _ -> val imgPath = File(carpetaTemp, "img%03d.jpg".format(index)).absolutePath; inputArgs.append("-loop 1 -t 3 -i $imgPath "); filterComplex.append("[$index:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,fps=30[v$index];") }
                for (index in uris.indices) filterComplex.append("[v$index]")
                filterComplex.append("concat=n=${uris.size}:v=1:a=0[outv]")
                val filterScriptFile = File(requireContext().cacheDir, "slideshow_filter.txt").apply { writeText(filterComplex.toString()) }
                val command = "-y $inputArgs-filter_complex_script \"${filterScriptFile.absolutePath}\" -map [outv] -c:v mpeg4 -q:v 3 \"${outputFile.absolutePath}\""; val totalDuration = (uris.size * 3000).toLong(); mostrarProgreso(totalDuration)
                FFmpegKit.executeAsync(command, { session ->
                    if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) saveToDownloads(outputFile, fileName)
                    ocultarProgreso(); carpetaTemp.deleteRecursively(); filterScriptFile.delete(); if (outputFile.exists()) outputFile.delete()
                }, { stats -> actualizarProgreso(stats.time, totalDuration) })
            } catch (e: Exception) { ocultarProgreso() }
        }.start()
    }

    private fun splitVideo(videoUri: Uri, startTime: String, endTime: String) {
        val videoFile = cacheUriToFile(videoUri, "input_split.mp4"); val fileName = "Clip_${System.currentTimeMillis()}.mp4"; val contentValues = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/0 VIDEO") }
        val resolver = requireContext().contentResolver; val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI; val destUri = resolver.insert(collectionUri, contentValues)
        val duration = try { val start = parseTimeToMillis(startTime); val end = parseTimeToMillis(endTime); end - start } catch (e: Exception) { 0L }; mostrarProgreso(duration)
        if (destUri != null) {
            val outputFile = File(requireContext().cacheDir, "output_split.mp4")
            val command = "-y -i \"${videoFile.absolutePath}\" -ss $startTime -to $endTime -c copy \"${outputFile.absolutePath}\""
            FFmpegKit.executeAsync(command, { session ->
                ocultarProgreso(); if (ReturnCode.isSuccess(session.returnCode)) try { resolver.openOutputStream(destUri)?.use { out -> outputFile.inputStream().use { it.copyTo(out) } }; requireActivity().runOnUiThread { Toast.makeText(requireContext(), R.string.clip_guardado, Toast.LENGTH_LONG).show() } } catch (e: Exception) { Log.e("FFmpegError", e.message ?: "") }
                videoFile.delete(); if (outputFile.exists()) outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, duration) })
        }
    }

    private fun cacheUriToFile(uri: Uri, name: String): File { val file = File(requireContext().cacheDir, name); if (file.exists()) file.delete(); try { requireContext().contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(file).use { out -> input.copyTo(out) } } } catch (e: Exception) { e.printStackTrace() }; return file }

    private fun saveToDownloads(file: File, fileName: String, mimeType: String = "video/mp4") {
        val contentValues = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName); put(MediaStore.MediaColumns.MIME_TYPE, mimeType); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/0 VIDEO") }
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else if (mimeType.startsWith("audio/")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else if (mimeType.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = requireContext().contentResolver.insert(collectionUri, contentValues)
        if (uri != null) try { requireContext().contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }; requireActivity().runOnUiThread { Toast.makeText(requireContext(), getString(R.string.guardado_en_downloads), Toast.LENGTH_SHORT).show() } } catch (e: Exception) { requireActivity().runOnUiThread { Toast.makeText(requireContext(), "Error al guardar $fileName", Toast.LENGTH_SHORT).show() } }
    }

    private fun startMultiplexFlow() {
        multiplexAudioUris.clear()
        multiplexSubtitleUris.clear()

        val workshopText = binding.homeContent.etSubtitleWorkshop.text.toString()
        if (workshopText.contains("[")) {
            AlertDialog.Builder(requireContext())
                .setTitle("Subtítulos del Workshop")
                .setMessage("¿Deseas incluir los subtítulos escritos en el Workshop?")
                .setPositiveButton("SÍ") { _, _ ->
                    val fileName = "Temp_Workshop_${System.currentTimeMillis()}.srt"
                    val tempFile = File(requireContext().cacheDir, fileName)
                    val entries = buildWorkshopEntries()
                    val srtContent = StringBuilder()
                    entries.forEachIndexed { index, entry ->
                        srtContent.append("${index + 1}\n${formatTimeSrt(entry.startMs)} --> ${formatTimeSrt(entry.endMs)}\n${entry.text}\n\n")
                    }
                    tempFile.writeText(srtContent.toString())
                    multiplexSubtitleUris.add(Uri.fromFile(tempFile))
                    askForAudioStep()
                }
                .setNegativeButton("NO") { _, _ -> askForAudioStep() }
                .show()
        } else {
            askForAudioStep()
        }
    }

    private fun askForAudioStep() {
        AlertDialog.Builder(requireContext())
            .setTitle("Agregar Audio")
            .setMessage("¿Deseas agregar una pista de audio externa?")
            .setPositiveButton("AGREGAR") { _, _ -> mAudioPickerLauncher.launch("audio/*") }
            .setNegativeButton("SALTAR") { _, _ -> askForSubtitleStep() }
            .show()
    }

    private fun askForAnotherAudio() {
        AlertDialog.Builder(requireContext())
            .setTitle("Audio Agregado")
            .setMessage("¿Deseas agregar otra pista de audio?")
            .setPositiveButton("SÍ") { _, _ -> mAudioPickerLauncher.launch("audio/*") }
            .setNegativeButton("NO") { _, _ -> askForSubtitleStep() }
            .show()
    }

    private fun askForSubtitleStep() {
        AlertDialog.Builder(requireContext())
            .setTitle("Agregar Subtítulos")
            .setMessage("¿Deseas agregar un archivo de subtítulos?")
            .setPositiveButton("AGREGAR") { _, _ -> mSubPickerLauncher.launch("*/*") }
            .setNegativeButton("SALTAR") { _, _ -> generateMultiplexMKV() }
            .show()
    }

    private fun askForAnotherSubtitle() {
        AlertDialog.Builder(requireContext())
            .setTitle("Subtítulo Agregado")
            .setMessage("¿Deseas agregar otro archivo de subtítulos?")
            .setPositiveButton("SÍ") { _, _ -> mSubPickerLauncher.launch("*/*") }
            .setNegativeButton("NO") { _, _ -> generateMultiplexMKV() }
            .show()
    }

    private fun generateMultiplexMKV() {
        if (multiplexAudioUris.isEmpty() && multiplexSubtitleUris.isEmpty()) {
            Toast.makeText(requireContext(), "No seleccionaste pistas adicionales", Toast.LENGTH_SHORT).show()
            return
        }

        val videoUri = videoPlaylist[currentIndex]
        val duration = getMediaDuration(videoUri)
        mostrarProgreso(duration)

        Thread {
            try {
                val inputFiles = mutableListOf<File>()
                val command = StringBuilder("-y ")

                // 1. Video Input (index 0)
                val videoFile = cacheUriToFile(videoUri, "mplex_video.mp4")
                inputFiles.add(videoFile)
                command.append("-i \"${videoFile.absolutePath}\" ")

                // 2. Audio Inputs
                multiplexAudioUris.forEachIndexed { i, uri ->
                    val aFile = cacheUriToFile(uri, "mplex_audio_$i.tmp")
                    inputFiles.add(aFile)
                    command.append("-i \"${aFile.absolutePath}\" ")
                }

                // 3. Subtitle Inputs
                multiplexSubtitleUris.forEachIndexed { i, uri ->
                    val ext = getFileExtension(uri)
                    val sFile = cacheUriToFile(uri, "mplex_sub_$i.$ext")
                    inputFiles.add(sFile)
                    command.append("-i \"${sFile.absolutePath}\" ")
                }

                // 4. Mapping
                command.append("-map 0:v ") // Video original
                // Si agregamos audios, ignoramos el audio original del video (opcional, pero suele ser lo deseado en Multiplex)
                // O podemos mantenerlo como primera pista. Vamos a mantener el original + los nuevos.
                command.append("-map 0:a? ") 
                
                var currentInputIndex = 1
                repeat(multiplexAudioUris.size) {
                    command.append("-map ${currentInputIndex}:a ")
                    currentInputIndex++
                }
                repeat(multiplexSubtitleUris.size) {
                    command.append("-map ${currentInputIndex}:s ")
                    currentInputIndex++
                }

                // 5. Output settings
                val outputFile = File(requireContext().cacheDir, "mplex_output.mkv")
                if (outputFile.exists()) outputFile.delete()
                
                command.append("-c copy -c:s srt \"${outputFile.absolutePath}\"")

                Log.d("FFmpegMultiplex", "Comando: $command")

                FFmpegKit.executeAsync(command.toString(), { session ->
                    if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                        val fileName = "Multiplex_${System.currentTimeMillis()}.mkv"
                        saveToDownloads(outputFile, fileName, "video/x-matroska")
                    } else {
                        Log.e("FFmpegMultiplex", session.allLogsAsString)
                    }
                    ocultarProgreso()
                    inputFiles.forEach { it.delete() }
                    if (outputFile.exists()) outputFile.delete()
                }, { stats -> actualizarProgreso(stats.time, duration) })

            } catch (e: Exception) {
                Log.e("FFmpegMultiplex", "Error: ${e.message}")
                ocultarProgreso()
            }
        }.start()
    }

    private fun startDemuxFlow() {
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            videoPickerLauncher.launch("video/*")
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Demux Video")
            .setMessage("¿Deseas extraer todas las pistas de audio?")
            .setPositiveButton("SÍ") { _, _ -> askDemuxSubtitles(true) }
            .setNegativeButton("NO") { _, _ -> askDemuxSubtitles(false) }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun askDemuxSubtitles(extractAudio: Boolean) {
        AlertDialog.Builder(requireContext())
            .setTitle("Demux Video")
            .setMessage("¿Deseas extraer todos los subtítulos?")
            .setPositiveButton("SÍ") { _, _ -> executeDemux(extractAudio, true) }
            .setNegativeButton("NO") { _, _ -> executeDemux(extractAudio, false) }
            .show()
    }

    private fun executeDemux(audio: Boolean, subs: Boolean) {
        if (!audio && !subs) {
            Toast.makeText(requireContext(), "No seleccionaste nada para extraer", Toast.LENGTH_SHORT).show()
            return
        }

        val videoUri = videoPlaylist[currentIndex]
        val duration = getMediaDuration(videoUri)
        mostrarProgreso(duration)

        Thread {
            try {
                val videoFile = cacheUriToFile(videoUri, "demux_input.mp4")
                val (audioStreams, subStreams) = detectarStreams(videoFile)
                val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
                val baseName = originalName.substringBeforeLast(".")

                if (audio) {
                    if (audioStreams.isEmpty()) {
                        Log.w("Demux", "No se detectaron streams de audio. Log crudo arriba (tag Demux) para diagnosticar.")
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "No se encontraron audios (revisa Logcat tag 'Demux' para el detalle)", Toast.LENGTH_LONG).show()
                        }
                    }
                    audioStreams.forEach { streamIdx ->
                        val outName = "${baseName}_track_${streamIdx}.mp3"
                        val outFile = File(requireContext().cacheDir, outName)
                        val cmd = "-y -i \"${videoFile.absolutePath}\" -map 0:$streamIdx -c:a libmp3lame -q:a 2 \"${outFile.absolutePath}\""
                        val extractSession = FFmpegKit.execute(cmd)
                        if (!ReturnCode.isSuccess(extractSession.returnCode)) {
                            Log.e("Demux", "Falló extrayendo audio stream $streamIdx: ${extractSession.allLogsAsString}")
                        }
                        if (outFile.exists() && outFile.length() > 0) saveToDownloads(outFile, outName, "audio/mpeg")
                        outFile.delete()
                    }
                }

                if (subs) {
                    if (subStreams.isEmpty()) {
                        requireActivity().runOnUiThread { Toast.makeText(requireContext(), "No se encontraron subtítulos", Toast.LENGTH_SHORT).show() }
                    }
                    subStreams.forEach { streamIdx ->
                        val outName = "${baseName}_sub_${streamIdx}.srt"
                        val outFile = File(requireContext().cacheDir, outName)
                        val cmd = "-y -i \"${videoFile.absolutePath}\" -map 0:$streamIdx \"${outFile.absolutePath}\""
                        FFmpegKit.execute(cmd)
                        if (outFile.exists() && outFile.length() > 0) saveToDownloads(outFile, outName, "text/plain")
                        outFile.delete()
                    }
                }

                ocultarProgreso()
                videoFile.delete()
            } catch (e: Exception) {
                Log.e("Demux", "Error: ${e.message}")
                ocultarProgreso()
            }
        }.start()
    }

    private fun actualizarVisibilidadSelectorPistas() {
        exoPlayer?.let { player ->
            val hasMultipleTracks = player.currentTracks.groups.any { group ->
                group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO || group.type == androidx.media3.common.C.TRACK_TYPE_TEXT
            }
            binding.homeContent.btnTrackSelector.visibility = if (hasMultipleTracks) View.VISIBLE else View.GONE
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mostrarSelectorPistas() {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks
        val options = mutableListOf<String>()
        val trackInfos = mutableListOf<Pair<Int, Int>>() // Pair(GroupIndex, TrackIndex)

        tracks.groups.forEachIndexed { groupIdx, group ->
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = "Audio: ${format.language ?: "Desconocido"} (${format.label ?: format.bitrate.let { if (it > 0) "${it / 1000}kbps" else "estándar" }})"
                    options.add(if (group.isTrackSelected(i)) "✓ $label" else label)
                    trackInfos.add(groupIdx to i)
                }
            } else if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = "Sub: ${format.language ?: "Desconocido"} (${format.label ?: "interno"})"
                    options.add(if (group.isTrackSelected(i)) "✓ $label" else label)
                    trackInfos.add(groupIdx to i)
                }
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "No se encontraron múltiples pistas", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar Pista")
            .setItems(options.toTypedArray()) { _, which ->
                val (groupIdx, trackIdx) = trackInfos[which]
                val group = tracks.groups[groupIdx]
                
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, trackIdx))
                    .build()
            }
            .setNeutralButton("Desactivar Subtítulos") { _, _ ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .build()
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .build()
            }
            .show()
    }

    private fun detectarCodecPortada(inputFile: File): String? {
        // FFprobeKit no está disponible en este build (ClassNotFoundException:
        // com.arthenica.ffmpegkit.FFprobeKit) — probablemente el APK no incluye
        // ese módulo por separado, o quedó desincronizado por un build
        // incremental. En vez de depender de él, usamos FFmpegKit puro: al
        // pedirle solo "-i archivo" sin indicar salida, ffmpeg igual imprime la
        // info completa de todos los streams (incluido el de video/portada
        // embebida) en su log, justo antes de fallar por "At least one output
        // file must be specified". Ese log es lo único que necesitamos.
        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-i \"${inputFile.absolutePath}\""
        )
        val log = session.allLogsAsString ?: return null

        // Busca una línea tipo: "Stream #0:1: Video: mjpeg, ..." o "Video: png, ..."
        val regex = Regex("""Stream #\d+:\d+.*?: Video: (\w+)""")
        val match = regex.find(log) ?: return null
        return match.groupValues[1].trim()
    }

    private fun convertirAudiosAMp3(uris: List<Uri>, calidad: Int = 2) {
        Toast.makeText(requireContext(), "Iniciando conversión masiva...", Toast.LENGTH_LONG).show(); val totalUris = uris.size
        Thread {
            var exitosos = 0; var fallidos = 0
            var primerError: String? = null
            uris.forEachIndexed { index, uri ->
                val originalName = requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Audio_${System.currentTimeMillis()}_$index"
                val fileName = "${originalName.substringBeforeLast(".")}.mp3"; val inputFile = cacheUriToFile(uri, "temp_input_audio_$index.tmp")
                if (!inputFile.exists() || inputFile.length() == 0L) {
                    fallidos++
                    if (primerError == null) primerError = "$originalName: no se pudo leer el archivo de origen (0 bytes o no existe)"
                    return@forEachIndexed
                }
                val outputFile = File(requireContext().cacheDir, "output_temp_$index.mp3"); if (outputFile.exists()) outputFile.delete()
                val duration = getMediaDuration(uri); requireActivity().runOnUiThread { mostrarProgreso(duration); _binding?.homeContent?.tvSubtitleOverlay?.text = "Convirtiendo ($index/$totalUris): ${originalName.substringBeforeLast(".")}" }

                // Detectamos el códec de la portada embebida (si hay) para decidir si
                // se puede copiar tal cual o si hay que reencodearla a un formato que
                // ID3v2/APIC soporte (jpg o png). Esto evita el bug de inflado por PNG
                // sin comprimir Y el caso de formatos no soportados (webp, etc).
                val codecPortada = detectarCodecPortada(inputFile)
                val tieneVideoStream = !codecPortada.isNullOrBlank()
                val filtroVideo = when {
                    !tieneVideoStream -> ""
                    codecPortada == "mjpeg" || codecPortada == "png" -> "-c:v copy"
                    else -> "-c:v mjpeg" // webp u otro formato no soportado por APIC -> reencode
                }
                val mapVideo = if (tieneVideoStream) "-map 0:v?" else ""
                val dispositionFlag = if (tieneVideoStream) "-disposition:v attached_pic" else ""

                val command = "-y -i \"${inputFile.absolutePath}\" " +
                        "-map_metadata 0 -map 0:a $mapVideo " +
                        "-c:a libmp3lame -q:a $calidad $filtroVideo $dispositionFlag " +
                        "-id3v2_version 3 \"${outputFile.absolutePath}\""

                val session = FFmpegKit.execute(command) { stats -> actualizarProgreso(stats.time, duration) }
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName, "audio/mpeg"); exitosos++
                } else {
                    fallidos++
                    // Antes esto se perdía en silencio. Logueamos el log completo de
                    // FFmpeg (logcat, tag "ConvertirMp3") y guardamos el primer error
                    // para mostrarlo en el Toast final -- así "X fallidos" deja de ser
                    // una caja negra.
                    val logCompleto = session.allLogsAsString
                    Log.e("ConvertirMp3", "Falló convirtiendo $originalName (returnCode=${session.returnCode}):\n$logCompleto")
                    if (primerError == null) {
                        val ultimaLinea = logCompleto?.trim()?.lines()?.lastOrNull { it.isNotBlank() }
                        primerError = "$originalName: ${ultimaLinea ?: "returnCode=${session.returnCode}"}"
                    }
                }
                inputFile.delete(); outputFile.delete()
            }
            requireActivity().runOnUiThread {
                ocultarProgreso()
                val base = "Conversión: $exitosos ok, $fallidos fallidos"
                val mensaje = if (fallidos > 0 && primerError != null) "$base\nPrimer error: $primerError" else base
                Toast.makeText(requireContext(), mensaje, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    companion object { const val PREF_SELECTED_FOLDER_URI = "pref_selected_folder_uri"; const val TAG: String = "BannerHomeFragment"; @JvmStatic fun newInstance(): HomeFragment = HomeFragment() }
}
