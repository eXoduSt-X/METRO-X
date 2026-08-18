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
    private var selectedAudioUri: Uri? = null
    private var selectedAudioUris = mutableListOf<Uri>()

    private var pendingHardcodeBurn = false

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

    private var isUserScrollingFilmstrip = false

    private val updateSubtitleTask = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                val currentPos = player.currentPosition.toInt()
                // ... (lógica de subtítulos mantenida)
                val currentSub = subtitleList.find { currentPos.toLong() in it.startTime..it.endTime }

                if (currentSub != null) {
                    _binding?.homeContent?.tvSubtitleOverlay?.let { tv ->
                        val subText = if (currentSub.translation != null) {
                            "${currentSub.original}\n${currentSub.translation}"
                        } else {
                            currentSub.original
                        }
                        if (tv.text != subText) tv.text = subText
                        tv.visibility = View.VISIBLE
                    }
                } else {
                    _binding?.homeContent?.tvSubtitleOverlay?.let { tv ->
                        val currentText = tv.text.toString()
                        if (!currentText.contains("%") && !currentText.contains(getString(R.string.procesando_archivo))) {
                            if (currentText.isNotEmpty()) tv.text = ""
                        }
                    }
                }

                if (player.isPlaying) {
                    binding.homeContent.videoSeekBar.max = player.duration.toInt()
                    binding.homeContent.videoSeekBar.progress = currentPos
                    binding.homeContent.tvCurrentTime.text = formatTime(currentPos)
                    binding.homeContent.tvTotalTime.text = formatTime(player.duration.toInt())
                    
                    // Sincronizar tira SOLO si el usuario no la está tocando
                    if (!isUserScrollingFilmstrip) {
                        syncFilmstripScroll(currentPos.toLong(), player.duration)
                    }
                }
            }
            handler.postDelayed(this, 250)
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
            selectedSubtitleUri = it
            try {
                requireContext().contentResolver.openInputStream(it)?.use { stream -> parseSrt(stream) }
                Toast.makeText(requireContext(), R.string.subtitulos_cargados, Toast.LENGTH_SHORT).show()

                if (pendingHardcodeBurn) {
                    pendingHardcodeBurn = false
                    mostrarConfirmacionIncrustarSubtitulos()
                }
            } catch (exception: Exception) {
                Toast.makeText(requireContext(), R.string.error_al_leer_subtitulos, Toast.LENGTH_SHORT).show()
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

    private fun mostrarConfirmacionIncrustarSubtitulos() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.incrustar_subtitulos_title)
            .setMessage(R.string.incrustar_subtitulos_message)
            .setPositiveButton(R.string.done) { _, _ -> hardcodearSubtitulos() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

    private fun loadVideosFromSelectedFolder(uri: Uri) {
        downloadVideoList.clear()
        val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)
        pickedDir?.listFiles()?.forEach { file ->
            if (file.type?.startsWith("video/") == true) {
                downloadVideoList.add(Pair(file.name ?: "Video", file.uri))
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
        setFixedIcon(binding.homeContent.btnMixVideo, R.drawable.ic_mkv)
        setFixedIcon(binding.homeContent.btnFullscreen, R.drawable.ic_replace)
        setFixedIcon(binding.homeContent.btnLoadSubtitles, R.drawable.ic_srt)
        setFixedIcon(binding.homeContent.btnOpenFile, R.drawable.ic_vid)
        setFixedIcon(binding.homeContent.btnChooseFolder, R.drawable.ic_vfolder)
        setFixedIcon(binding.homeContent.btnConvertAudio, R.drawable.ic_mp3)
        setFixedIcon(binding.homeContent.btnHardcodeSubtitles, R.drawable.ic_subs)
        setFixedIcon(binding.homeContent.btnCreateVideoFromPhotos, R.drawable.ic_slide)
        setFixedIcon(binding.homeContent.btnCreateGif, R.drawable.ic_gif)
        setFixedIcon(binding.homeContent.btnYoutubeDownload, R.drawable.ic_youtube, 130, 29)
        setFixedIcon(binding.homeContent.btnTagEditor, R.drawable.ic_dashboard)
        setFixedIcon(binding.homeContent.btnFadeIn, R.drawable.ic_keyboard_arrow_up)
        setFixedIcon(binding.homeContent.btnFadeOut, R.drawable.ic_keyboard_arrow_down)

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
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        setPlayPauseIcon(isPlaying)
                    }
                })
            }
        }
    }

    private fun setupVideoListeners() {
        binding.homeContent.btnOpenFile.setOnClickListener { videoPickerLauncher.launch("video/*") }
        binding.homeContent.btnLoadSubtitles.setOnClickListener { subtitlePickerLauncher.launch("*/*") }
        binding.homeContent.btnChooseFolder.setOnClickListener {
            folderPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }

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

        binding.homeContent.btnMixVideo.setOnClickListener {
            val subUri = selectedSubtitleUri
            if (videoPlaylist.isNotEmpty() && subUri != null) {
                createMkvWithSubtitles(videoPlaylist[currentIndex], subUri, selectedAudioUri)
            } else {
                Toast.makeText(requireContext(), R.string.selecciona_video_y_subtitulos, Toast.LENGTH_SHORT).show()
            }
        }

        binding.homeContent.btnFullscreen.setOnClickListener {
            if (videoPlaylist.isEmpty()) {
                Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            audioForVideoPickerLauncher.launch("audio/*")
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

        binding.homeContent.btnHardcodeSubtitles.setOnClickListener {
            if (videoPlaylist.isEmpty()) {
                Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedSubtitleUri != null) mostrarConfirmacionIncrustarSubtitulos() else {
                pendingHardcodeBurn = true
                subtitlePickerLauncher.launch("*/*")
            }
        }
        binding.homeContent.btnCreateVideoFromPhotos.setOnClickListener {
            if (slideshowImages.isEmpty()) photosPickerLauncher.launch("image/*") else crearVideoDesdeFotos(slideshowImages)
        }

        binding.homeContent.btnMergeVideos.setOnClickListener {
            if (mergeVideosUris.isEmpty()) mergePickerLauncher.launch("video/*") else unirVideos(mergeVideosUris)
        }
        binding.homeContent.btnConvertAudio.setOnClickListener { multiaudioPickerLauncher.launch("audio/*") }

        binding.homeContent.btnCreateGif.setOnClickListener {
            if (videoPlaylist.isNotEmpty()) convertirVideoAGif(videoPlaylist[currentIndex]) else Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
        }

        binding.homeContent.btnTagEditor.setOnClickListener {
            startActivity(Intent(requireContext(), code.name.monkey.retromusic.activities.tageditor.BatchTagEditorActivity::class.java))
        }

        binding.homeContent.btnYoutubeDownload.setOnClickListener {
            findNavController().navigate(R.id.youtube_downloader_fragment)
        }

        binding.homeContent.btnFadeIn.setOnClickListener { aplicarFade(true) }
        binding.homeContent.btnFadeOut.setOnClickListener { aplicarFade(false) }
    }

    private fun aplicarFade(fadeIn: Boolean) {
        if (videoPlaylist.isEmpty()) {
            Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            return
        }
        val fadeDuration = binding.homeContent.etFadeDuration.text.toString().toDoubleOrNull() ?: 2.0
        val videoUri = videoPlaylist[currentIndex]
        val totalDurationMs = getMediaDuration(videoUri)
        val totalDurationSec = totalDurationMs / 1000.0

        if (fadeDuration > totalDurationSec) {
            Toast.makeText(requireContext(), "Fade duration is longer than video", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Aplicando Fade ${if (fadeIn) "In" else "Out"}...", Toast.LENGTH_LONG).show()
        mostrarProgreso(totalDurationMs)

        Thread {
            val videoFile = cacheUriToFile(videoUri, "input_fade.mp4")
            val outputFile = File(requireContext().cacheDir, "output_fade.mp4")
            if (outputFile.exists()) outputFile.delete()

            val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
            val fileName = "${originalName.substringBeforeLast(".")}_fade${if (fadeIn) "in" else "out"}.mp4"

            val st = if (fadeIn) 0.0 else (totalDurationSec - fadeDuration).coerceAtLeast(0.0)
            val vFilter = "fade=${if (fadeIn) "in" else "out"}:st=$st:d=$fadeDuration"
            val aFilter = "afade=${if (fadeIn) "in" else "out"}:st=$st:d=$fadeDuration"

            val cmd = "-y -i \"${videoFile.absolutePath}\" -vf \"$vFilter\" -af \"$aFilter\" -c:v h264_mediacodec -b:v 2M -c:a aac \"${outputFile.absolutePath}\""
            FFmpegKit.executeAsync(cmd, { session ->
                if (ReturnCode.isSuccess(session.returnCode)) saveToDownloads(outputFile, fileName)
                ocultarProgreso()
                videoFile.delete()
                outputFile.delete()
            }, { stats -> actualizarProgreso(stats.time, totalDurationMs) })
        }.start()
    }

    private fun clearSubtitles() {
        selectedSubtitleUri = null
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
        binding.homeContent.toolsRow.visibility = visibility
        binding.homeContent.cutRow.visibility = visibility
        binding.homeContent.extraActionsContainer.visibility = visibility
        binding.homeContent.rvDownloads.visibility = visibility
        binding.homeContent.btnYoutubeDownload.visibility = visibility
        binding.homeContent.rvFilmstrip.visibility = visibility
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

    private fun syncFilmstripScroll(currentPosMs: Long, durationMs: Long) {
        if (durationMs <= 0 || slideshowImages.isNotEmpty() || mergeVideosUris.isNotEmpty()) return
        
        val recyclerView = binding.homeContent.rvFilmstrip
        if (recyclerView.visibility != View.VISIBLE) return

        val totalRange = recyclerView.computeHorizontalScrollRange() - recyclerView.width
        if (totalRange <= 0) return

        val progress = currentPosMs.toFloat() / durationMs
        val targetScrollX = progress * totalRange

        val currentScrollX = recyclerView.computeHorizontalScrollOffset()
        val diff = targetScrollX.toInt() - currentScrollX
        
        if (Math.abs(diff) > 2) {
            recyclerView.scrollBy(diff, 0)
        }
    }

    private fun generateFilmstrip(uri: Uri) {
        binding.homeContent.rvFilmstrip.visibility = View.GONE
        binding.homeContent.vFilmstripIndicator.visibility = View.GONE
        slideshowImages.clear()
        mergeVideosUris.clear()
        filmstripAdapter = null
        
        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(requireContext(), uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                val frames = mutableListOf<VideoFrameAdapter.VideoFrame>()
                val frameCount = 24
                val interval = durationMs / frameCount
                for (i in 0 until frameCount) {
                    val timeMs = i * interval
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 70) else retriever.getFrameAtTime(timeMs * 1000)
                    frames.add(VideoFrameAdapter.VideoFrame(timeMs, bitmap, formatTime(timeMs.toInt())))
                }
                requireActivity().runOnUiThread {
                    binding.homeContent.rvFilmstrip.apply {
                        val halfWidth = resources.displayMetrics.widthPixels / 2
                        setPadding(halfWidth, 0, halfWidth, 0)
                        
                        layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                        adapter = VideoFrameAdapter(frames, { seekTime -> 
                            exoPlayer?.seekTo(seekTime)
                        })
                        
                        // Escuchar el scroll manual para actualizar el video
                        addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                                val wasScrolling = isUserScrollingFilmstrip
                                isUserScrollingFilmstrip = newState != RecyclerView.SCROLL_STATE_IDLE
                                
                                if (wasScrolling && !isUserScrollingFilmstrip) {
                                    exoPlayer?.let { player ->
                                        val duration = player.duration
                                        if (duration > 0) {
                                            val scrollX = computeHorizontalScrollOffset()
                                            val totalRange = computeHorizontalScrollRange() - width
                                            val progress = (scrollX.toFloat() / totalRange).coerceIn(0f, 1f)
                                            player.seekTo((progress * duration).toLong())
                                        }
                                    }
                                }
                            }
                        })

                        visibility = View.VISIBLE
                    }
                    binding.homeContent.vFilmstripIndicator.visibility = View.VISIBLE
                }
            } catch (e: Exception) { e.printStackTrace() } finally { retriever.release() }
        }.start()
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
        requireActivity().runOnUiThread {
            _binding?.homeContent?.progressBar?.let { pb -> pb.progress = 0; pb.isIndeterminate = totalDurationMs <= 0; pb.visibility = View.VISIBLE }
            _binding?.homeContent?.tvSubtitleOverlay?.let { tv -> tv.text = getString(R.string.procesando_archivo); tv.visibility = View.VISIBLE }
        }
    }

    private fun actualizarProgreso(currentTimeMs: Double, totalDurationMs: Long) {
        if (totalDurationMs <= 0) return
        val progress = ((currentTimeMs / totalDurationMs) * 100).toInt().coerceIn(0, 100)
        requireActivity().runOnUiThread {
            _binding?.homeContent?.progressBar?.let { pb -> pb.isIndeterminate = false; pb.progress = progress }
            _binding?.homeContent?.tvSubtitleOverlay?.text = "${getString(R.string.procesando_archivo)} ($progress%)"
        }
    }

    private fun ocultarProgreso() {
        requireActivity().runOnUiThread {
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
        super.onConfigurationChanged(newConfig); val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE; val visibility = if (isLandscape) View.GONE else View.VISIBLE; binding.appBarLayout.visibility = visibility; binding.homeContent.btnOpenFile.visibility = visibility; binding.homeContent.btnLoadSubtitles.visibility = visibility; binding.homeContent.btnChooseFolder.visibility = visibility
        val playbackVisibility = if (isLandscape && !isFullscreen) View.GONE else View.VISIBLE; binding.homeContent.videoSeekBar.visibility = playbackVisibility; binding.homeContent.btnPrevVideo.parent.let { if (it is View) it.visibility = playbackVisibility }; binding.homeContent.tvCurrentTime.parent.let { if (it is View) it.visibility = playbackVisibility }
        if (!isFullscreen) { binding.homeContent.videoContainer.layoutParams.height = if (isLandscape) ViewGroup.LayoutParams.MATCH_PARENT else (250 * resources.displayMetrics.density).toInt(); binding.homeContent.videoPlayer.resizeMode = if (isLandscape) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
        else { binding.homeContent.videoContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT; binding.homeContent.videoContainer.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT; binding.homeContent.videoPlayer.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) { R.id.action_settings -> { findNavController().navigate(R.id.settings_fragment, null, navOptions); true }; R.id.action_import_playlist -> { ImportPlaylistDialog().show(childFragmentManager, "ImportPlaylist"); true }; R.id.action_add_to_playlist -> { CreatePlaylistDialog.create(emptyList()).show(childFragmentManager, "ShowCreatePlaylistDialog"); true }; else -> false }
    override fun onPrepareMenu(menu: Menu) { super.onPrepareMenu(menu); ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), binding.appBarLayout.toolbar) }
    override fun onPause() { super.onPause(); exoPlayer?.let { savedPosition = it.currentPosition.toInt() } }
    override fun onResume() { super.onResume(); checkForMargins(); exitTransition = null; if (_binding != null && videoPlaylist.isNotEmpty() && savedPosition > 0) { exoPlayer?.apply { seekTo(savedPosition.toLong()); if (wasPlayingBeforePause) { play(); setPlayPauseIcon(true) } } } }

    override fun onDestroyView() {
        if (isFullscreen) { requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView).show(WindowInsetsCompat.Type.systemBars()); mainActivity.setBottomNavVisibility(visible = true, hideBottomSheet = false) }
        exoPlayer?.let { if (it.isPlaying) savedPosition = it.currentPosition.toInt(); it.release() }; exoPlayer = null; handler.removeCallbacks(updateSubtitleTask); _binding = null; super.onDestroyView()
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

    private fun hardcodearSubtitulos() {
        val subUri = selectedSubtitleUri; if (videoPlaylist.isEmpty() || subUri == null) { Toast.makeText(requireContext(), R.string.selecciona_video_y_srt, Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(requireContext(), R.string.incrustando_subtitulos_msg, Toast.LENGTH_LONG).show(); requireActivity().runOnUiThread { mostrarProgreso() }
        val videoUri = videoPlaylist[currentIndex]; val duration = getMediaDuration(videoUri); mostrarProgreso(duration)
        Thread {
            val originalName = requireContext().contentResolver.query(videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video_${System.currentTimeMillis()}"
            val fileName = "${originalName.substringBeforeLast(".")}_sub.mp4"; val videoFile = cacheUriToFile(videoUri, "input_hardcode.mp4"); val outputFile = File(requireContext().cacheDir, "output_hardcode.mp4"); if (outputFile.exists()) outputFile.delete()
            val fontFileRegular = File(getFontDir(), "roboto_regular.ttf").absolutePath; val fontFileItalic = File(getItalicFontDir(), "roboto_italic.ttf").absolutePath; val fontSize = (getVideoWidth(videoFile) / 22).coerceIn(12, 36); val drawtextFilter = buildDrawtextFilters(subtitleList, fontFileRegular, fontFileItalic, fontSize)
            if (drawtextFilter.isBlank()) { requireActivity().runOnUiThread { Toast.makeText(requireContext(), R.string.no_hay_subtitulos_msg, Toast.LENGTH_SHORT).show() }; ocultarProgreso(); videoFile.delete(); return@Thread }
            val command = "-y -i \"${videoFile.absolutePath}\" -vf \"$drawtextFilter,format=yuv420p\" -c:v h264_mediacodec -b:v 2M -c:a copy \"${outputFile.absolutePath}\""
            FFmpegKit.executeAsync(command, { session ->
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) saveToDownloads(outputFile, fileName)
                requireActivity().runOnUiThread { clearSubtitles() }; ocultarProgreso(); videoFile.delete(); if (outputFile.exists()) outputFile.delete()
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

    private fun convertirAudiosAMp3(uris: List<Uri>, calidad: Int = 2) {
        Toast.makeText(requireContext(), "Iniciando conversión masiva...", Toast.LENGTH_LONG).show(); val totalUris = uris.size
        Thread {
            var exitosos = 0; var fallidos = 0
            uris.forEachIndexed { index, uri ->
                val originalName = requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Audio_${System.currentTimeMillis()}_$index"
                val fileName = "${originalName.substringBeforeLast(".")}.mp3"; val inputFile = cacheUriToFile(uri, "temp_input_audio_$index.tmp")
                if (!inputFile.exists() || inputFile.length() == 0L) { fallidos++; return@forEachIndexed }
                val outputFile = File(requireContext().cacheDir, "output_temp_$index.mp3"); if (outputFile.exists()) outputFile.delete()
                val duration = getMediaDuration(uri); requireActivity().runOnUiThread { mostrarProgreso(duration); _binding?.homeContent?.tvSubtitleOverlay?.text = "Convirtiendo ($index/$totalUris): ${originalName.substringBeforeLast(".")}" }
                val command = "-y -i \"${inputFile.absolutePath}\" -map_metadata 0 -id3v2_version 3 -c:a libmp3lame -q:a $calidad \"${outputFile.absolutePath}\""
                val session = FFmpegKit.execute(command) { stats -> actualizarProgreso(stats.time, duration) }
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) { saveToDownloads(outputFile, fileName, "audio/mpeg"); exitosos++ } else fallidos++
                inputFile.delete(); outputFile.delete()
            }
            requireActivity().runOnUiThread { ocultarProgreso(); Toast.makeText(requireContext(), "Conversión: $exitosos ok, $fallidos fallidos", Toast.LENGTH_LONG).show() }
        }.start()
    }

    companion object { const val PREF_SELECTED_FOLDER_URI = "pref_selected_folder_uri"; const val TAG: String = "BannerHomeFragment"; @JvmStatic fun newInstance(): HomeFragment = HomeFragment() }
}
