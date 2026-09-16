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
import android.widget.EditText
import android.widget.ImageButton
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import code.name.monkey.retromusic.fragments.home.HomeEditorViewModel.Orientation

data class Subtitle(val startTime: Long, val endTime: Long, val original: String, val translation: String?)

data class MediaMixItem(val uri: Uri, var durationMs: Long, val isVideo: Boolean)


class HomeFragment : AbsMainActivityFragment(R.layout.fragment_home), IScrollHelper {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var editorViewModel: HomeEditorViewModel
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
    private var selectedAudioUris = mutableListOf<Uri>()

    private var slideshowAudioUri: Uri? = null
    private val slideshowStampTimestamps = mutableListOf<Long>() // boundaries marcadas por el usuario (ms)
    private val slideshowImageDurations = mutableListOf<Long>()  // duración calculada por imagen (ms)
    private var isStampingMode = false

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

    private val slideshowAudioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            slideshowAudioUri = it
            Toast.makeText(requireContext(), "Audio de referencia cargado. Tocá de nuevo para iniciar el marcado.", Toast.LENGTH_LONG).show()
        }
    }

    private val productionAudioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            tempProductionAudioUri = it
            askForSubtitlesStep(videoPlaylist[currentIndex], it)
        }
    }

    private val productionSubPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { executeFinalProduction(videoPlaylist[currentIndex], tempProductionAudioUri, it) }
    }

    private var tempProductionAudioUri: Uri? = null

    private var combinedMediaItems = mutableListOf<MediaMixItem>()
    private val combinedPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val isVideo = getFileExtension(uri).let { it != "jpg" && it != "jpeg" && it != "png" && it != "webp" }
                val duration = if (isVideo) getMediaDuration(uri) else 3000L
                combinedMediaItems.add(MediaMixItem(uri, duration, isVideo))
            }
            updateCombinedFilmstrip()
        }
    }

    private fun updateCombinedFilmstrip() {
        binding.homeContent.hsvFilmstrip.visibility = View.GONE
        binding.homeContent.vFilmstripIndicator.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val frames = combinedMediaItems.mapIndexed { index, item ->
                val retriever = MediaMetadataRetriever()
                val bitmap = try {
                    retriever.setDataSource(requireContext(), item.uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 70)
                    } else {
                        retriever.getFrameAtTime(1000000)
                    }
                } catch (e: Exception) {
                    if (!item.isVideo) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val source = android.graphics.ImageDecoder.createSource(requireContext().contentResolver, item.uri)
                                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                    decoder.setTargetSize(120, 70)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Media.getBitmap(requireContext().contentResolver, item.uri)
                            }
                        } catch (e2: Exception) { null }
                    } else null
                } finally {
                    retriever.release()
                }

                val name = getBetterName(item.uri).substringBeforeLast(".")
                VideoFrameAdapter.VideoFrame(
                    timestampMs = index.toLong(),
                    bitmap = bitmap,
                    timeLabel = name,
                    canRemove = true,
                    durationMs = item.durationMs,
                    canResize = !item.isVideo // Solo permitir estirar fotos por ahora
                )
            }.toMutableList()

            frames.add(VideoFrameAdapter.VideoFrame(0, null, "", isAddButton = true))

            requireActivity().runOnUiThread {
                filmstripAdapter = VideoFrameAdapter(frames, { /* Preview? */ }, {
                    combinedPickerLauncher.launch("*/*")
                }, { pos ->
                    if (pos < combinedMediaItems.size) {
                        combinedMediaItems.removeAt(pos)
                        updateCombinedFilmstrip()
                    }
                }, { pos, newDuration ->
                    if (pos < combinedMediaItems.size) {
                        combinedMediaItems[pos].durationMs = newDuration
                    }
                })
                binding.homeContent.rvFilmstrip.apply {
                    layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                    adapter = filmstripAdapter
                    visibility = if (combinedMediaItems.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun startCombinedJoin() {
        if (combinedMediaItems.isEmpty()) return
        Toast.makeText(requireContext(), "Procesando mezcla de medios...", Toast.LENGTH_LONG).show()
        editorViewModel.joinCombinedMedia(combinedMediaItems.toList(), getSelectedMpeg4Quality().args)
        combinedMediaItems.clear()
        requireActivity().runOnUiThread { updateCombinedFilmstrip() }
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

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
        }
    }
    private val hideResolutionRunnable = Runnable {
        _binding?.homeContent?.tvResolutionOverlay?.visibility = View.GONE
    }

    /**
     * Sincroniza la UI (subtítulos, seekbar, timeline) con la posición del
     * reproductor a ~20fps. Solo se reprograma a sí mismo mientras el video
     * está reproduciéndose: si está en pausa o no hay nada cargado, el loop
     * simplemente no se vuelve a encolar, así no seguimos despertando la CPU
     * cada 50ms sin necesidad. onIsPlayingChanged() se encarga de reactivarlo
     * apenas arranca la reproducción de nuevo (ver startSubtitleUpdateLoop()).
     */
    private val updateSubtitleTask = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player != null && player.isPlaying) {
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

                binding.homeContent.videoSeekBar.max = player.duration.toInt()
                binding.homeContent.videoSeekBar.progress = currentPos
                binding.homeContent.tvCurrentTime.text = formatTime(currentPos)
                binding.homeContent.tvTotalTime.text = formatTime(player.duration.toInt())

                // Sincronización con la timeline: scrollTo() directo, sin diffs ni
                // umbrales — es barato e idempotente, y solo corre cuando el usuario
                // no está arrastrando, así que nunca compite con su gesto.
                handler.postDelayed(this, 50) // Alta frecuencia (20fps), pero solo mientras se reproduce
            }
        }
    }

    /** Reinicia el loop de sincronización de UI. Llamar solo cuando arranca la reproducción. */
    private fun startSubtitleUpdateLoop() {
        handler.removeCallbacks(updateSubtitleTask)
        handler.post(updateSubtitleTask)
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
        // Análisis rápido (solo lee metadata, no procesa video)
        val analysis = editorViewModel.analyzeMergeCompatibility(uris)

        when {
            // Caso 1: hay mezcla de orientaciones -> avisar y pedir confirmación
            analysis.mismatchedIndices.isNotEmpty() -> {
                val listaDesalineados = analysis.mismatchedIndices.joinToString("\n") { idx ->
                    val meta = analysis.metas[idx]
                    val orient = when (meta.orientation) {
                        Orientation.PORTRAIT -> "Vertical"
                        Orientation.LANDSCAPE -> "Horizontal"
                        Orientation.SQUARE -> "Cuadrado"
                    }
                    "• ${meta.name} — $orient"
                }
                val targetLabel = when (analysis.majorityOrientation) {
                    Orientation.PORTRAIT -> "vertical (720x1280)"
                    Orientation.LANDSCAPE -> "horizontal (1280x720)"
                    Orientation.SQUARE -> "cuadrado (720x720)"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Formatos diferentes detectados")
                    .setMessage(
                        "Estos videos no coinciden con el formato mayoritario:\n\n" +
                                "$listaDesalineados\n\n" +
                                "Si continuás, todos se normalizarán al formato mayoritario: $targetLabel.\n" +
                                "El proceso tardará más porque hay que reencodear."
                    )
                    .setPositiveButton("CONTINUAR") { _, _ ->
                        editorViewModel.mergeVideos(uris, analysis.majorityOrientation, getSelectedMpeg4Quality().args)
                        mergeVideosUris.clear()
                        binding.homeContent.rvFilmstrip.visibility = View.GONE
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }

            // Caso 2: todos iguales, pero no son idénticos en resolución/códec -> reencode silencioso
            !analysis.canStreamCopy -> {
                Toast.makeText(
                    requireContext(),
                    "Uniendo videos (reencode necesario, puede tardar)...",
                    Toast.LENGTH_LONG
                ).show()
                editorViewModel.mergeVideos(uris, analysis.majorityOrientation, getSelectedMpeg4Quality().args)
                mergeVideosUris.clear()
                binding.homeContent.rvFilmstrip.visibility = View.GONE
            }

            // Caso 3: todo homogéneo, -c copy directo
            else -> {
                Toast.makeText(requireContext(), "Uniendo ${uris.size} videos...", Toast.LENGTH_LONG).show()
                editorViewModel.mergeVideos(uris, analysis.majorityOrientation, getSelectedMpeg4Quality().args)
                mergeVideosUris.clear()
                binding.homeContent.rvFilmstrip.visibility = View.GONE
            }
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

        editorViewModel = ViewModelProvider(this)[HomeEditorViewModel::class.java]
        observeEditorViewModel()

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
        setupListeners()
        initializePlayer()
        setupVideoListeners()

        setFixedIcon(binding.homeContent.btnPrevVideo, R.drawable.ic_skip_previous)
        setFixedIcon(binding.homeContent.btnNextVideo, R.drawable.ic_skip_next)
        binding.homeContent.btnMergeVideos.visibility = View.GONE
        binding.homeContent.cutRow.visibility = View.GONE
        setFixedIcon(binding.homeContent.btnYoutubeDownload, R.drawable.ic_youtube, 130, 29)
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
    private fun observeEditorViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            editorViewModel.progress.collectLatest { p ->
                if (p == null) {
                    ocultarProgreso()
                } else {
                    isProcessing = true
                    _binding?.homeContent?.progressBar?.let { pb ->
                        pb.isIndeterminate = p.totalMs == 0L
                        pb.visibility = View.VISIBLE
                        if (p.totalMs > 0L) {
                            val pct = ((p.currentMs / p.totalMs) * 100).toInt().coerceIn(0, 100)
                            pb.progress = pct
                        }
                    }
                    _binding?.homeContent?.tvSubtitleOverlay?.let { tv ->
                        tv.visibility = View.VISIBLE
                        val prefix = getString(R.string.procesando_archivo)
                        tv.text = if (p.label != null) {
                            val pct = if (p.totalMs > 0) " (${((p.currentMs / p.totalMs) * 100).toInt()}%)" else ""
                            "${p.label}$pct"
                        } else if (p.totalMs > 0L) {
                            "$prefix (${((p.currentMs / p.totalMs) * 100).toInt()}%)"
                        } else prefix
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            editorViewModel.events.collectLatest { ev ->
                when (ev) {
                    is EditorEvent.Saved -> Toast.makeText(
                        requireContext(),
                        getString(R.string.guardado_en_downloads) + ": ${ev.fileName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    is EditorEvent.Error -> Toast.makeText(requireContext(), ev.message, Toast.LENGTH_LONG).show()
                    is EditorEvent.Info -> Toast.makeText(requireContext(), ev.message, Toast.LENGTH_LONG).show()
                    EditorEvent.Finished -> {
                        // Nada acá: el StateFlow de progress pasa a null y ocultarProgreso()
                        // se llama solo. Este evento queda por si en el futuro querés
                        // disparar refresh de la lista de downloads, etc.
                    }
                }
            }
        }
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
                        // El loop de sincronización de UI (subtítulos/seekbar/timeline) solo
                        // debe correr mientras hay reproducción activa: lo arrancamos acá y
                        // dejamos que se auto-detenga (ver updateSubtitleTask) al pausar.
                        if (isPlaying) {
                            startSubtitleUpdateLoop()
                        } else {
                            handler.removeCallbacks(updateSubtitleTask)
                        }
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


        binding.homeContent.btnMergeVideos.setOnClickListener {
            startCombinedJoin()
        }

        binding.homeContent.btnYoutubeDownload.setOnClickListener {
            findNavController().navigate(R.id.youtube_downloader_fragment)
        }

        binding.homeContent.btnTrackSelector.setOnClickListener { mostrarSelectorPistas() }

        setupSubtitleWorkshopListeners()
        setupToolStrip()
    }

    private fun setupToolStrip() {
        val tools = listOf(
            ToolButtonItem("folder", R.drawable.ic_vfolder, "Carpeta"),
            ToolButtonItem("quality", R.drawable.ic_dashboard, "Calidad"),  // ← NUEVO
            ToolButtonItem("mix", R.drawable.ic_vid, "MIX"),
            ToolButtonItem("merge", R.drawable.ic_unir, "Unir Videos"),
            ToolButtonItem("split", R.drawable.ic_cut, "Cortar"),
            ToolButtonItem("fade", R.drawable.ic_fade, "Fade"),
            ToolButtonItem("gif", R.drawable.ic_gif, "GIF"),
            ToolButtonItem("tageditor", R.drawable.ic_dashboard, "Mp3Tag"),
            ToolButtonItem("tomp3", R.drawable.ic_mp3, "A MP3"),
            ToolButtonItem("subs", R.drawable.ic_srt, "Subs"),
            ToolButtonItem("ass", R.drawable.ic_ass, "ASS"),
            ToolButtonItem("workshop", R.drawable.ic_edit, "Workshop"),
            ToolButtonItem("demux", R.drawable.ic_restore, "Demux"),
            ToolButtonItem("remux", R.drawable.ic_replace, "Remux"),
            ToolButtonItem("multiplex", R.drawable.ic_mkv, "Multiplex"),
            ToolButtonItem("stampsync", R.drawable.ic_metrox_new, "Sync Fotos"),
            ToolButtonItem("production", R.drawable.ic_metrox_new, "Producir")
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
            "mix" -> {
                if (combinedMediaItems.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Creador de Secuencias (MIX)")
                        .setMessage("Con esta herramienta puedes:\n\n" +
                                "• Unir varios vídeos en uno solo.\n" +
                                "• Crear pases de diapositivas con fotos.\n" +
                                "• Mezclar fotos y vídeos en la misma línea de tiempo.\n" +
                                "• Ajustar cuánto tiempo se muestra cada foto.\n\n" +
                                "Todo se unificará a HD (720p). Si quieres añadir música de fondo o subtítulos a tu mezcla, usa 'Multiplex' una vez termines el vídeo aquí.")
                        .setPositiveButton("Seleccionar Medios") { _, _ ->
                            combinedPickerLauncher.launch("*/*")
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                } else {
                    startCombinedJoin()
                }
            }
            "merge" -> {
                if (mergeVideosUris.isEmpty()) {
                    mergePickerLauncher.launch("video/*")
                } else {
                    mostrarDialogoUnirVideos(mergeVideosUris)
                }
            }
            "split" -> mostrarDialogoCortar()
            "quality" -> mostrarSelectorCalidadMpeg4()
            "fade" -> mostrarDialogoFade()
            "gif" -> {
                if (videoPlaylist.isNotEmpty()) convertirVideoAGif(videoPlaylist[currentIndex])
                else Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            }
            "slideshow" -> {
                if (slideshowImages.isEmpty()) photosPickerLauncher.launch("image/*")
                else crearVideoDesdeFotos(
                    slideshowImages,
                    slideshowImageDurations.takeIf { it.size == slideshowImages.size },
                    slideshowAudioUri
                )
            }
            "stampsync" -> {
                when {
                    slideshowImages.isEmpty() -> photosPickerLauncher.launch("image/*")
                    slideshowAudioUri == null -> slideshowAudioPickerLauncher.launch("audio/*")
                    slideshowImageDurations.isNotEmpty() -> crearVideoDesdeFotos(
                        slideshowImages,
                        slideshowImageDurations,
                        slideshowAudioUri
                    )
                    else -> startStampingSession()
                }
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
            "production" -> startProductionFlow()
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

    private data class CutRange(val start: String, val end: String)

    private fun mostrarDialogoCortar() {
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        val inputView = inflater.inflate(R.layout.dialog_split_video, null)
        val etStart = inputView.findViewById<EditText>(R.id.etStartTime)
        val etEnd = inputView.findViewById<EditText>(R.id.etEndTime)
        val btnSetStart = inputView.findViewById<ImageButton>(R.id.btnSetStart)
        val btnSetEnd = inputView.findViewById<ImageButton>(R.id.btnSetEnd)

        btnSetStart.setOnClickListener { exoPlayer?.let { etStart.setText(formatTime(it.currentPosition.toInt())) } }
        btnSetEnd.setOnClickListener { exoPlayer?.let { etEnd.setText(formatTime(it.currentPosition.toInt())) } }

        val rangesList = mutableListOf<CutRange>()
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setPadding(0, 12, 0, 12)
            clipToPadding = false
        }

        lateinit var adapter: CutRangeAdapter
        adapter = CutRangeAdapter(rangesList) { pos ->
            rangesList.removeAt(pos)
            adapter.notifyItemRemoved(pos)
        }
        recyclerView.adapter = adapter

        val btnAdd = Button(requireContext()).apply {
            text = "AGREGAR A LA LISTA"
            setOnClickListener {
                val start = etStart.text.toString().trim()
                val end = etEnd.text.toString().trim()
                if (start.isEmpty() || end.isEmpty()) {
                    Toast.makeText(requireContext(), "Definí inicio y fin antes de agregar", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                rangesList.add(CutRange(start, end))
                adapter.notifyItemInserted(rangesList.size - 1)
                etStart.setText("")
                etEnd.setText("")
            }
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(inputView)
            addView(btnAdd)
            addView(recyclerView)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Cortar Video en Varias Partes")
            .setView(container)
            .setPositiveButton("CORTAR TODO") { _, _ ->
                if (rangesList.isEmpty()) {
                    Toast.makeText(requireContext(), "No agregaste ningún corte a la lista", Toast.LENGTH_SHORT).show()
                } else {
                    splitVideoMultiple(videoPlaylist[currentIndex], rangesList.toList())
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private inner class CutRangeAdapter(
        private val items: List<CutRange>,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<CutRangeAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvFilename)
            val tvDetails: TextView = v.findViewById(R.id.tvDetails)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_batch_song, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = "Corte ${position + 1} (tocar para quitar)"
            holder.tvDetails.text = "${item.start}  →  ${item.end}"
            holder.itemView.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
        }
        override fun getItemCount() = items.size
    }
    private fun splitVideoMultiple(videoUri: Uri, ranges: List<CutRange>) {
        Toast.makeText(requireContext(), "Cortando ${ranges.size} clips...", Toast.LENGTH_LONG).show()
        editorViewModel.splitVideoMultiple(
            videoUri,
            ranges.map { HomeEditorViewModel.CutRange(it.start, it.end) }
        )
    }
    private fun mostrarDialogoFade() {
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_fade_video, null)
        val etIn = view.findViewById<EditText>(R.id.etFadeIn)
        val etOut = view.findViewById<EditText>(R.id.etFadeOut)

        AlertDialog.Builder(requireContext())
            .setTitle("Aplicar Efecto Fade")
            .setView(view)
            .setPositiveButton("APLICAR") { _, _ ->
                val fadeIn = etIn.text.toString().toDoubleOrNull() ?: 0.0
                val fadeOut = etOut.text.toString().toDoubleOrNull() ?: 0.0
                aplicarFadeCombinado(fadeIn, fadeOut)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun aplicarFadeCombinado(fadeInSec: Double, fadeOutSec: Double) {
        if (fadeInSec <= 0.0 && fadeOutSec <= 0.0) {
            Toast.makeText(requireContext(), "Define al menos un tiempo de fade", Toast.LENGTH_SHORT).show()
            return
        }
        val videoUri = videoPlaylist[currentIndex]
        val totalSec = getMediaDuration(videoUri) / 1000.0
        if (fadeInSec + fadeOutSec > totalSec) {
            Toast.makeText(requireContext(), "La suma de fades supera la duración del video", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), "Aplicando fades...", Toast.LENGTH_LONG).show()
        editorViewModel.applyFade(videoUri, fadeInSec, fadeOutSec, getSelectedMpeg4Quality().args)
    }
    private fun buildAssFromSubtitleList(videoUri: Uri): File {
        val (playResX, playResY) = getVideoResolution(videoUri)
        val subtitleFontSize = (playResY * 0.06).toInt().coerceIn(16, 60)
        val watermarkFontSize = (playResY * 0.045).toInt().coerceIn(12, 45)

        val originalItalic = if (binding.homeContent.cbOriginalItalic.isChecked) -1 else 0
        val translationItalic = if (binding.homeContent.cbTranslationItalic.isChecked) -1 else 0
        val addMtvInfo = binding.homeContent.cbWorkshopMtvInfo.isChecked
        val artist = binding.homeContent.etMtvArtist.text.toString().trim()
        val song = binding.homeContent.etMtvSong.text.toString().trim()
        val album = binding.homeContent.etMtvYear.text.toString().trim()

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
            val watermarkText = watermarkLines.joinToString("\\N")
            ass.append("Dialogue: 0,${msToAss(3000)},${msToAss(8000)},Watermark,,0,0,0,,$watermarkText\n")
        }

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
            // Solo restauramos la lista de selección si estamos en un modo que la usa
            // (slideshow, unir videos, MIX). El filmstrip grande se retiró.
            if (slideshowImages.isNotEmpty() || mergeVideosUris.isNotEmpty() || combinedMediaItems.isNotEmpty()) {
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
            // Ya no generamos filmstrip: no aporta funcionalidad real.
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
        editorViewModel.addAudioToVideo(videoUri, audioUri)
    }

    private fun convertirVideoAGif(videoUri: Uri, fps: Int = 10, anchoMax: Int = 480) {
        Toast.makeText(requireContext(), R.string.creando_gif_msg, Toast.LENGTH_LONG).show()
        editorViewModel.videoToGif(videoUri, fps, anchoMax)
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
    enum class Mpeg4Quality(
        val label: String,
        val description: String,
        val args: String
    ) {
        LOW("Baja", "Compartir por WhatsApp, pruebas rápidas", "-q:v 5"),
        MEDIUM("Media", "Balance tamaño/calidad, redes sociales", "-b:v 2M"),
        HIGH("Alta (recomendada)", "Ver en tele, uso general", "-b:v 6M"),
        VERY_HIGH("Muy alta", "Archivo personal, máxima calidad", "-b:v 12M"),
        MAX("Máxima", "Forzar calidad máxima sin límite de bitrate", "-q:v 1")
    }

    private fun getSelectedMpeg4Quality(): Mpeg4Quality {
        val saved = requireContext()
            .getSharedPreferences("video_prefs", Context.MODE_PRIVATE)
            .getString(PREF_MPEG4_QUALITY, Mpeg4Quality.HIGH.name)
        return try {
            Mpeg4Quality.valueOf(saved ?: Mpeg4Quality.HIGH.name)
        } catch (e: Exception) {
            Mpeg4Quality.HIGH
        }
    }

    private fun saveSelectedMpeg4Quality(quality: Mpeg4Quality) {
        requireContext()
            .getSharedPreferences("video_prefs", Context.MODE_PRIVATE)
            .edit { putString(PREF_MPEG4_QUALITY, quality.name) }
    }

    private fun mostrarSelectorCalidadMpeg4() {
        val calidades = Mpeg4Quality.values()
        val actual = getSelectedMpeg4Quality()
        val labels = calidades.map { q ->
            val check = if (q == actual) "✓ " else "   "
            "$check${q.label}\n      ${q.description}  →  ${q.args}"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Calidad de codificación (mpeg4)")
            .setItems(labels) { _, which ->
                val elegida = calidades[which]
                saveSelectedMpeg4Quality(elegida)
                Toast.makeText(
                    requireContext(),
                    "Calidad: ${elegida.label}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Cerrar", null)
            .show()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig);
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val visibility = if (isLandscape) View.GONE else View.VISIBLE

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
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            return
        }
        val videoUri = videoPlaylist[currentIndex]

        // Preferimos SIEMPRE el .ass original si el usuario cargó uno con SUBS.
        // Eso preserva estilos, colores, posiciones y el banner MTV.
        // Si no hay .ass pero sí subtitleList (de SRT/LRC), regeneramos como antes.
        val assFile: File = when {
            selectedAssSubtitleUri != null -> {
                val safeAss = File(requireContext().cacheDir, "original_${System.currentTimeMillis()}.ass")
                requireContext().contentResolver.openInputStream(selectedAssSubtitleUri!!)?.use { input ->
                    safeAss.outputStream().use { out -> input.copyTo(out) }
                }
                safeAss
            }
            subtitleList.isNotEmpty() -> buildAssFromSubtitleList(videoUri)
            else -> {
                Toast.makeText(requireContext(), "No hay subtítulos cargados", Toast.LENGTH_SHORT).show()
                return
            }
        }

        Toast.makeText(requireContext(), R.string.incrustando_subtitulos_msg, Toast.LENGTH_LONG).show()
        editorViewModel.burnAssSubtitles(videoUri, assFile, getFontDir(), getSelectedMpeg4Quality().args)
    }

    private fun startStampingSession() {
        val audioUri = slideshowAudioUri ?: return
        slideshowStampTimestamps.clear()
        slideshowImageDurations.clear()
        initializePlayer()
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(audioUri))
            prepare()
            play()
        }
        isStampingMode = true

        binding.homeContent.btnStampMark.visibility = View.VISIBLE
        binding.homeContent.btnStampMark.text = if (slideshowImages.size > 1) "SIGUIENTE FOTO" else "FINALIZAR"
        binding.homeContent.tvSubtitleOverlay.visibility = View.VISIBLE
        binding.homeContent.tvSubtitleOverlay.text = "Foto 1/${slideshowImages.size} — tocá cuando quieras pasar a la siguiente"
        highlightStampImage(0)

        binding.homeContent.btnStampMark.setOnClickListener { onStampButtonTapped() }
    }

    private fun onStampButtonTapped() {
        if (!isStampingMode) return
        val currentMs = exoPlayer?.currentPosition ?: return
        slideshowStampTimestamps.add(currentMs)

        val marcadas = slideshowStampTimestamps.size // cantidad de boundaries ya marcadas
        if (marcadas >= slideshowImages.size) {
            finishStampingSession()
        } else {
            val currentIndex = marcadas // índice de la foto que arranca ahora
            highlightStampImage(currentIndex)
            val esUltima = marcadas == slideshowImages.size - 1
            binding.homeContent.btnStampMark.text = if (esUltima) "FINALIZAR" else "SIGUIENTE FOTO"
            binding.homeContent.tvSubtitleOverlay.text = "Foto ${currentIndex + 1}/${slideshowImages.size}"
        }
    }

    private fun finishStampingSession() {
        exoPlayer?.pause()
        isStampingMode = false
        binding.homeContent.btnStampMark.visibility = View.GONE
        binding.homeContent.tvSubtitleOverlay.visibility = View.GONE
        computeDurationsFromStamps()
        Toast.makeText(requireContext(), "Sincronización lista (${slideshowImageDurations.size} fotos). Tocá Sync Fotos de nuevo para generar el video.", Toast.LENGTH_LONG).show()
    }

    private fun computeDurationsFromStamps() {
        slideshowImageDurations.clear()
        var previous = 0L
        // Mínimo 200ms por foto para evitar duraciones nulas por doble-tap accidental
        slideshowStampTimestamps.forEach { t ->
            slideshowImageDurations.add((t - previous).coerceAtLeast(200L))
            previous = t
        }
    }

    private fun highlightStampImage(index: Int) {
        binding.homeContent.rvFilmstrip.scrollToPosition(index)
    }

    private fun crearVideoDesdeFotos(uris: List<Uri>, durationsMs: List<Long>? = null, audioUri: Uri? = null) {
        Toast.makeText(requireContext(), getString(R.string.creando_slideshow_msg, uris.size), Toast.LENGTH_LONG).show()
        editorViewModel.createSlideshow(uris.toList(), durationsMs?.toList(), audioUri,getSelectedMpeg4Quality().args)
        // Limpieza de estado de la herramienta (punto 3 de las notas)
        slideshowImages.clear()
        slideshowImageDurations.clear()
        slideshowStampTimestamps.clear()
        slideshowAudioUri = null
        binding.homeContent.rvFilmstrip.visibility = View.GONE
    }

    private fun splitVideo(videoUri: Uri, startTime: String, endTime: String) {
        val videoFile = cacheUriToFile(videoUri, "input_split.mp4"); val fileName = "Clip_${System.currentTimeMillis()}.mp4"; val contentValues = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/0 VIDEO") }
        val resolver = requireContext().contentResolver; val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI; val destUri = resolver.insert(collectionUri, contentValues)
        val duration = try { val start = parseTimeToMillis(startTime); val end = parseTimeToMillis(endTime); end - start } catch (e: Exception) { 0L }; mostrarProgreso(duration)
        if (destUri != null) {
            val outputFile = File(requireContext().cacheDir, "output_split.mp4")
            val command = "-y -i \"${videoFile.absolutePath}\" -ss $startTime -to $endTime " +
                    "-c:v libx264 -preset veryfast -crf 18 -c:a copy " +
                    "\"${outputFile.absolutePath}\""
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
        editorViewModel.generateMultiplexMKV(
            videoUri,
            multiplexAudioUris.toList(),
            multiplexSubtitleUris.toList()
        )
        multiplexAudioUris.clear()
        multiplexSubtitleUris.clear()
    }

    private fun startProductionFlow() {
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            return
        }

        val videoUri = videoPlaylist[currentIndex]

        // Asistente Paso 1: Audio
        val audioOptions = arrayOf("Mantener audio original", "Elegir música/audio externo", "Silenciar video")
        AlertDialog.Builder(requireContext())
            .setTitle("Producción Final - Paso 1: Audio")
            .setItems(audioOptions) { _, which ->
                when (which) {
                    0 -> askForSubtitlesStep(videoUri, null) // Original
                    1 -> {
                        // Lanzar picker y continuar en el callback
                        productionAudioPickerLauncher.launch("audio/*")
                    }
                    2 -> askForSubtitlesStep(videoUri, Uri.parse("silence")) // Silencio
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    private fun askForSubtitlesStep(videoUri: Uri, audioUri: Uri?) {
        AlertDialog.Builder(requireContext())
            .setTitle("Producción Final - Paso 2: Subtítulos")
            .setMessage("¿Deseas incrustar (quemar) subtítulos en el video final?")
            .setPositiveButton("SÍ") { _, _ ->
                productionSubPickerLauncher.launch("*/*")
            }
            .setNegativeButton("NO") { _, _ ->
                executeFinalProduction(videoUri, audioUri, null)
            }
            .show()
    }

    private fun executeFinalProduction(videoUri: Uri, audioUri: Uri?, subtitleUri: Uri?) {
        Toast.makeText(requireContext(), "Generando producción final...", Toast.LENGTH_LONG).show()
        // Traducir el sentinel "silence" al URI pactado con el VM
        val normalizedAudioUri = if (audioUri != null && audioUri.toString() == "silence") {
            Uri.parse(HomeEditorViewModel.SILENCE_URI)
        } else audioUri
        editorViewModel.finalProduction(videoUri, normalizedAudioUri, subtitleUri, getFontDir(), getSelectedMpeg4Quality().args)
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
        editorViewModel.demux(videoUri, audio, subs)
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
    private fun convertirAudiosAMp3(uris: List<Uri>, calidad: Int = 2) {
        Toast.makeText(requireContext(), "Iniciando conversión masiva...", Toast.LENGTH_LONG).show()
        editorViewModel.convertAudiosToMp3(uris.toList(), calidad)
        selectedAudioUris.clear()
    }

    companion object { const val PREF_SELECTED_FOLDER_URI = "pref_selected_folder_uri"; const val PREF_MPEG4_QUALITY = "pref_mpeg4_quality"; const val TAG: String = "BannerHomeFragment"; @JvmStatic fun newInstance(): HomeFragment = HomeFragment() }
}