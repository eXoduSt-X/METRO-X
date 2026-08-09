package code.name.monkey.retromusic.fragments.home

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.*
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
import java.util.Locale

data class Subtitle(val startTime: Long, val endTime: Long, val original: String, val translation: String?)

class HomeFragment : AbsMainActivityFragment(R.layout.fragment_home), IScrollHelper {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var savedPosition: Int = 0
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

    private val photosPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            crearVideoDesdeFotos(uris)
        } else {
            Toast.makeText(requireContext(), "Selecciona al menos una foto", Toast.LENGTH_SHORT).show()
        }
    }

    private val updateSubtitleTask = object : Runnable {
        override fun run() {
            if (binding.homeContent.videoPlayer.isPlaying) {
                val player = binding.homeContent.videoPlayer
                val currentPos = player.currentPosition
                val currentSub = subtitleList.find { currentPos.toLong() in it.startTime..it.endTime }
                binding.homeContent.tvSubtitleOverlay.text = if (currentSub != null) {
                    if (currentSub.translation != null) {
                        "${currentSub.original}\n${currentSub.translation}"
                    } else {
                        currentSub.original
                    }
                } else {
                    ""
                }
                binding.homeContent.videoSeekBar.max = player.duration
                binding.homeContent.videoSeekBar.progress = currentPos
                binding.homeContent.tvCurrentTime.text = formatTime(currentPos)
                binding.homeContent.tvTotalTime.text = formatTime(player.duration)
            }
            handler.postDelayed(this, 500)
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

    private fun mostrarDialogoUnirVideos(uris: List<Uri>) {
        val nombres = uris.map { uri ->
            requireContext().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment ?: "Video" } ?: "Video"
        }

        val listaTexto = nombres.mapIndexed { i, nombre -> "${i + 1}. $nombre" }.joinToString("\n")

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.unir_videos_title, uris.size))
            .setMessage(getString(R.string.unir_videos_message, listaTexto))
            .setPositiveButton(R.string.action_set) { _, _ ->
                unirVideos(uris)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        mostrarProgreso()

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
                requireActivity().runOnUiThread { mostrarProgreso() }

                FFmpegKit.executeAsync(command) { session ->
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
                }
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
            if (lines[i].contains("-->")) {
                val times = lines[i].split(" --> ")
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
    }

    private fun parseTimeToMillis(time: String): Long {
        val parts = time.replace(",", ":").split(":")
        return (parts[0].toLong() * 3600000) + (parts[1].toLong() * 60000) + (parts[2].toLong() * 1000) + parts[3].toLong()
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
                loadVideosFromDownloads()
            } else {
                requestPermissionLauncher.launch(permission)
            }
        }

        binding.homeContent.tvSubtitleOverlay.setShadowLayer(3f, 2f, 2f, Color.BLACK)
        handler.post(updateSubtitleTask)
        setupListeners()
        setupVideoListeners()

        setFixedIcon(binding.homeContent.btnPrevVideo, R.drawable.ic_skip_previous)
        setFixedIcon(binding.homeContent.btnNextVideo, R.drawable.ic_skip_next)
        setPlayPauseIcon(false)

        fullscreenGestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreen()
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

    private fun setupVideoListeners() {
        binding.homeContent.btnOpenFile.setOnClickListener { videoPickerLauncher.launch("video/*") }
        binding.homeContent.btnLoadSubtitles.setOnClickListener { subtitlePickerLauncher.launch("*/*") }
        binding.homeContent.btnChooseFolder.setOnClickListener {
            folderPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }

        binding.homeContent.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.homeContent.videoPlayer.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.homeContent.videoPlayer.setOnPreparedListener { mp ->
            mp.seekTo(savedPosition)
            mp.start()
            binding.homeContent.videoSeekBar.max = mp.duration
            binding.homeContent.tvTotalTime.text = formatTime(mp.duration)
            setPlayPauseIcon(true)
        }

        binding.homeContent.btnPlayPause.setOnClickListener {
            val player = binding.homeContent.videoPlayer
            if (player.isPlaying) {
                player.pause()
                setPlayPauseIcon(false)
            } else {
                player.start()
                setPlayPauseIcon(true)
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
                        binding.homeContent.videoPlayer.seekTo((binding.homeContent.videoPlayer.currentPosition - 5000).coerceAtLeast(0))
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
                        binding.homeContent.videoPlayer.seekTo((binding.homeContent.videoPlayer.currentPosition + 5000).coerceAtMost(binding.homeContent.videoPlayer.duration))
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
            binding.homeContent.etStartTime.setText(formatTime(binding.homeContent.videoPlayer.currentPosition))
        }
        binding.homeContent.btnSetEnd.setOnClickListener {
            binding.homeContent.etEndTime.setText(formatTime(binding.homeContent.videoPlayer.currentPosition))
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
            if (selectedSubtitleUri != null) {
                mostrarConfirmacionIncrustarSubtitulos()
            } else {
                pendingHardcodeBurn = true
                subtitlePickerLauncher.launch("*/*")
            }
        }
        binding.homeContent.btnCreateVideoFromPhotos.setOnClickListener {
            photosPickerLauncher.launch("image/*")
        }

        binding.homeContent.btnMergeVideos.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }
        binding.homeContent.btnConvertAudio.setOnClickListener {
            multiaudioPickerLauncher.launch("audio/*")
        }

        binding.homeContent.btnCreateGif.setOnClickListener {
            if (videoPlaylist.isNotEmpty()) {
                convertirVideoAGif(videoPlaylist[currentIndex])
            } else {
                Toast.makeText(requireContext(), R.string.carga_video_primero, Toast.LENGTH_SHORT).show()
            }
        }

        binding.homeContent.btnTagEditor.setOnClickListener {
            // Placeholder
        }

        binding.homeContent.btnYoutubeDownload.setOnClickListener {
            findNavController().navigate(R.id.youtube_downloader_fragment)
        }
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

    private fun setUiVisibilityForFullscreen(fullscreen: Boolean) {
        val visibility = if (fullscreen) View.GONE else View.VISIBLE

        binding.appBarLayout.visibility = visibility
        binding.homeContent.absPlaylists.root.visibility = visibility
        binding.homeContent.toolsRow.visibility = visibility
        binding.homeContent.cutRow.visibility = visibility
        binding.homeContent.extraActionsContainer.visibility = visibility
        binding.homeContent.rvDownloads.visibility = visibility

        val padding = if (fullscreen) 0 else (16 * resources.displayMetrics.density).toInt()
        binding.homeContent.contentPadding.setPadding(padding, padding, padding, padding)

        val videoParams = binding.homeContent.videoContainer.layoutParams
        videoParams.height = if (fullscreen) {
            (resources.displayMetrics.heightPixels * 0.35).toInt()
        } else {
            (250 * resources.displayMetrics.density).toInt()
        }
        videoParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        binding.homeContent.videoContainer.layoutParams = videoParams

        binding.root.setBackgroundColor(if (fullscreen) Color.BLACK else "#1E1E1E".toColorInt())
        binding.homeContent.root.setBackgroundColor(if (fullscreen) Color.BLACK else "#1E1E1E".toColorInt())

        binding.container.isNestedScrollingEnabled = !fullscreen

        binding.homeContent.videoContainer.requestLayout()
    }

    private fun reproducirVideoActual() {
        if (videoPlaylist.isNotEmpty()) {
            clearSubtitles()
            savedPosition = 0
            binding.homeContent.videoPlayer.setVideoURI(videoPlaylist[currentIndex])
            binding.homeContent.videoPlayer.start()
            binding.homeContent.btnPlayPause.text = getString(R.string.pause)
        }
    }

    private fun getFontDir(): File {
        val fontDir = File(requireContext().cacheDir, "subtitle_fonts")
        if (!fontDir.exists()) fontDir.mkdirs()
        val fontFile = File(fontDir, "roboto_regular.ttf")
        if (!fontFile.exists()) {
            resources.openRawResource(R.raw.roboto_regular).use { input ->
                fontFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return fontDir
    }

    private fun getItalicFontDir(): File {
        val fontDir = File(requireContext().cacheDir, "subtitle_fonts")
        if (!fontDir.exists()) fontDir.mkdirs()
        val fontFile = File(fontDir, "roboto_italic.ttf")
        if (!fontFile.exists()) {
            resources.openRawResource(R.raw.roboto_italic).use { input ->
                fontFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return fontDir
    }

    private fun buildDrawtextFilters(
        subtitles: List<Subtitle>,
        fontFileRegular: String,
        fontFileItalic: String,
        fontSize: Int
    ): String {
        fun escape(text: String) = text
            .replace("\\", "\\\\")
            .replace("'", "'\\\\\\''")
            .replace(":", "\\\\:")
            .replace(",", "\\\\,")
            .replace("%", "%%")

        // En Android (Linux), las rutas no tienen C:, pero por si acaso escapamos el colón
        val escapedFontRegular = fontFileRegular.replace(":", "\\:").replace("\\", "/")
        val escapedFontItalic = fontFileItalic.replace(":", "\\:").replace("\\", "/")

        return subtitles.joinToString(",") { sub ->
            val startSec = sub.startTime / 1000.0
            val endSec = sub.endTime / 1000.0
            val originalFilter = "drawtext=fontfile='$escapedFontRegular':text='${escape(sub.original)}':" +
                    "enable='between(t,$startSec,$endSec)':x=(w-text_w)/2:y=h*0.85:" +
                    "fontsize=$fontSize:fontcolor=white:shadowcolor=black:shadowx=2:shadowy=2"

            if (sub.translation != null) {
                val translationFilter = "drawtext=fontfile='$escapedFontItalic':text='${escape(sub.translation)}':" +
                        "enable='between(t,$startSec,$endSec)':x=(w-text_w)/2:y=h*0.10:" +
                        "fontsize=$fontSize:fontcolor=yellow:shadowcolor=black:shadowx=2:shadowy=2"
                "$originalFilter,$translationFilter"
            } else {
                originalFilter
            }
        }
    }

    private fun mostrarProgreso() {
        requireActivity().runOnUiThread {
            _binding?.homeContent?.progressBar?.visibility = View.VISIBLE
            _binding?.homeContent?.tvSubtitleOverlay?.text = getString(R.string.procesando_archivo)
            _binding?.homeContent?.tvSubtitleOverlay?.visibility = View.VISIBLE
        }
    }

    private fun ocultarProgreso() {
        requireActivity().runOnUiThread {
            _binding?.homeContent?.progressBar?.visibility = View.GONE
            _binding?.homeContent?.tvSubtitleOverlay?.text = ""
            _binding?.homeContent?.tvSubtitleOverlay?.visibility = View.GONE
        }
    }

    private fun agregarAudioAVideo(videoUri: Uri, audioUri: Uri) {
        Toast.makeText(requireContext(), R.string.agregando_audio_msg, Toast.LENGTH_LONG).show()
        mostrarProgreso()

        Thread {
            val videoFile = cacheUriToFile(videoUri, "input_addaudio.mp4")
            val audioFile = cacheUriToFile(audioUri, "input_addaudio_track")

            val originalName = requireContext().contentResolver.query(
                videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "Video_${System.currentTimeMillis()}"
            val baseName = originalName.substringBeforeLast(".")
            val fileName = "${baseName}_audio.mp4"

            val outputFile = File(requireContext().cacheDir, "output_addaudio.mp4")
            if (outputFile.exists()) outputFile.delete()

            val command = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -map 0:v -map 1:a -c:v copy -c:a aac -shortest \"${outputFile.absolutePath}\""
            Log.d("FFmpegAddAudio", "Comando: $command")

            FFmpegKit.executeAsync(command) { session ->
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName, "video/mp4")
                } else {
                    Log.e("FFmpegAddAudio", session.allLogsAsString)
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), R.string.error_al_agregar_audio, Toast.LENGTH_SHORT).show()
                    }
                }
                ocultarProgreso()
                videoFile.delete()
                audioFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }
        }.start()
    }

    private fun convertirVideoAGif(videoUri: Uri, fps: Int = 10, anchoMax: Int = 480) {
        Toast.makeText(requireContext(), R.string.creando_gif_msg, Toast.LENGTH_LONG).show()
        mostrarProgreso()

        Thread {
            val videoFile = cacheUriToFile(videoUri, "input_gif.mp4")
            val originalName = requireContext().contentResolver.query(
                videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "Video_${System.currentTimeMillis()}"
            val baseName = originalName.substringBeforeLast(".")
            val fileName = "$baseName.gif"

            val outputFile = File(requireContext().cacheDir, "output_gif.gif")
            if (outputFile.exists()) outputFile.delete()

            val filterComplex = "[0:v]fps=$fps,scale=$anchoMax:-1:flags=lanczos,split[a][b];" +
                    "[a]palettegen[p];[b][p]paletteuse"

            val filterScriptFile = File(requireContext().cacheDir, "gif_filter.txt")
            filterScriptFile.writeText(filterComplex)

            val command = "-y -i \"${videoFile.absolutePath}\" -filter_complex_script \"${filterScriptFile.absolutePath}\" \"${outputFile.absolutePath}\""
            Log.d("FFmpegGif", "Comando: $command")

            FFmpegKit.executeAsync(command) { session ->
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName, "image/gif")
                }
                ocultarProgreso()
                videoFile.delete()
                filterScriptFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }
        }.start()
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun setPlayPauseIcon(isPlaying: Boolean) {
        val sizePx = (20 * resources.displayMetrics.density).toInt()
        val drawableRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val icon = ContextCompat.getDrawable(requireContext(), drawableRes)
        icon?.setBounds(0, 0, sizePx, sizePx)
        binding.homeContent.btnPlayPause.setCompoundDrawables(null, icon, null, null)
        binding.homeContent.btnPlayPause.text = if (isPlaying) getString(R.string.pause).uppercase() else getString(R.string.play).uppercase()
    }

    private fun setFixedIcon(button: android.widget.Button, drawableRes: Int) {
        val sizePx = (20 * resources.displayMetrics.density).toInt()
        val icon = ContextCompat.getDrawable(requireContext(), drawableRes)
        icon?.setBounds(0, 0, sizePx, sizePx)
        button.setCompoundDrawables(null, icon, null, null)
    }

    private fun adjustPlaylistButtons() {
        val buttons = listOf(
            binding.homeContent.absPlaylists.history,
            binding.homeContent.absPlaylists.lastAdded,
            binding.homeContent.absPlaylists.topPlayed,
            binding.homeContent.absPlaylists.actionShuffle
        )
        buttons.maxOf { it.lineCount }.let { maxLineCount -> buttons.forEach { it.setLines(maxLineCount) } }
    }

    private fun setupListeners() {
        binding.imageLayout.bannerImage?.setOnClickListener {
            findNavController().navigate(R.id.user_info_fragment, null, null, FragmentNavigatorExtras(binding.imageLayout.userImage to "user_image"))
            reenterTransition = null
        }
        binding.homeContent.absPlaylists.lastAdded.setOnClickListener {
            findNavController().navigate(R.id.detailListFragment, bundleOf(EXTRA_PLAYLIST_TYPE to LAST_ADDED_PLAYLIST))
            setSharedAxisYTransitions()
        }
        binding.homeContent.absPlaylists.topPlayed.setOnClickListener {
            findNavController().navigate(R.id.detailListFragment, bundleOf(EXTRA_PLAYLIST_TYPE to TOP_PLAYED_PLAYLIST))
            setSharedAxisYTransitions()
        }
        binding.homeContent.absPlaylists.actionShuffle.setOnClickListener { libraryViewModel.shuffleSongs() }
        binding.homeContent.absPlaylists.history.setOnClickListener {
            findNavController().navigate(R.id.detailListFragment, bundleOf(EXTRA_PLAYLIST_TYPE to HISTORY_PLAYLIST))
            setSharedAxisYTransitions()
        }

        binding.imageLayout.userImage.setOnClickListener {
            findNavController().navigate(R.id.user_info_fragment, null, null, FragmentNavigatorExtras(binding.imageLayout.userImage to "user_image"))
        }
    }

    private fun setupTitle() {
        binding.appBarLayout.toolbar.setNavigationOnClickListener { findNavController().navigate(R.id.action_search, null, navOptions) }
        binding.appBarLayout.title = getString(R.string.video_editor)
    }

    private fun loadProfile() {
        binding.imageLayout.bannerImage?.let {
            Glide.with(requireContext()).load(RetroGlideExtension.getBannerModel()).profileBannerOptions(RetroGlideExtension.getBannerModel()).into(it)
        }
        Glide.with(requireActivity()).load(RetroGlideExtension.getUserModel()).userProfileOptions(RetroGlideExtension.getUserModel(), requireContext()).into(binding.imageLayout.userImage)
    }

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            binding.container.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dip(R.dimen.bottom_nav_height)
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        menu.removeItem(R.id.action_grid_size)
        menu.removeItem(R.id.action_layout_type)
        menu.removeItem(R.id.action_sort_order)
        menu.findItem(R.id.action_settings)?.setShowAsAction(1)
        val toolbar = binding.appBarLayout.toolbar
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(requireContext(), toolbar, menu, ATHToolbarActivity.getToolbarBackgroundColor(toolbar))
    }

    override fun scrollToTop() {
        binding.container.scrollTo(0, 0)
        binding.appBarLayout.setExpanded(true)
    }

    fun setSharedAxisYTransitions() {
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true).addTarget(CoordinatorLayout::class.java)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
    }

    private fun saveFolderUri(uri: Uri) {
        requireContext().getSharedPreferences("video_prefs", Context.MODE_PRIVATE).edit {
            putString(PREF_SELECTED_FOLDER_URI, uri.toString())
        }
    }

    private fun loadSavedFolderUri(): Uri? {
        val uriString = requireContext().getSharedPreferences("video_prefs", Context.MODE_PRIVATE)
            .getString(PREF_SELECTED_FOLDER_URI, null)
        return uriString?.toUri()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val visibility = if (isLandscape) View.GONE else View.VISIBLE
        binding.appBarLayout.visibility = visibility
        binding.homeContent.btnOpenFile.visibility = visibility
        binding.homeContent.btnLoadSubtitles.visibility = visibility
        binding.homeContent.btnChooseFolder.visibility = visibility

        val playbackVisibility = if (isLandscape && !isFullscreen) View.GONE else View.VISIBLE
        binding.homeContent.videoSeekBar.visibility = playbackVisibility
        binding.homeContent.btnPrevVideo.visibility = playbackVisibility
        binding.homeContent.btnNextVideo.visibility = playbackVisibility
        binding.homeContent.btnPlayPause.visibility = playbackVisibility
        binding.homeContent.btnFullscreen.visibility = playbackVisibility
        binding.homeContent.tvCurrentTime.visibility = playbackVisibility
        binding.homeContent.tvTotalTime.visibility = playbackVisibility

        if (!isFullscreen) {
            binding.homeContent.videoContainer.layoutParams.height = if (isLandscape) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                (250 * resources.displayMetrics.density).toInt()
            }
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                findNavController().navigate(R.id.settings_fragment, null, navOptions)
                true
            }
            R.id.action_import_playlist -> {
                ImportPlaylistDialog().show(childFragmentManager, "ImportPlaylist")
                true
            }
            R.id.action_add_to_playlist -> {
                CreatePlaylistDialog.create(emptyList()).show(childFragmentManager, "ShowCreatePlaylistDialog")
                true
            }
            else -> false
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), binding.appBarLayout.toolbar)
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            val player = binding.homeContent.videoPlayer
            wasPlayingBeforePause = player.isPlaying
            savedPosition = player.currentPosition
            if (player.isPlaying) player.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        checkForMargins()
        exitTransition = null
        if (_binding != null && videoPlaylist.isNotEmpty() && savedPosition > 0) {
            val player = binding.homeContent.videoPlayer
            player.seekTo(savedPosition)
            if (wasPlayingBeforePause) {
                player.start()
                setPlayPauseIcon(true)
            }
        }
    }

    override fun onDestroyView() {
        if (isFullscreen) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(requireActivity().window, requireActivity().window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            mainActivity.setBottomNavVisibility(visible = true, hideBottomSheet = false)
        }
        if (binding.homeContent.videoPlayer.isPlaying) {
            savedPosition = binding.homeContent.videoPlayer.currentPosition
        }
        handler.removeCallbacks(updateSubtitleTask)
        binding.homeContent.videoPlayer.stopPlayback()
        _binding = null
        super.onDestroyView()
    }

    private fun createMkvWithSubtitles(videoUri: Uri, subtitleUri: Uri, audioUri: Uri? = null) {
        val videoFile = cacheUriToFile(videoUri, "input_video.mp4")
        val subFile = cacheUriToFile(subtitleUri, "input_sub.srt")
        val fileName = "Video_Subtitulado_${System.currentTimeMillis()}.mkv"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/x-matroska")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val resolver = requireContext().contentResolver
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collectionUri, contentValues)

        if (uri != null) {
            val outputFile = File(requireContext().cacheDir, "temp_output.mkv")
            if (outputFile.exists()) outputFile.delete()

            val command = if (audioUri != null) {
                val audioFile = cacheUriToFile(audioUri, "input_audio.mp3")
                "-y -i \"${videoFile.absolutePath}\" -i \"${subFile.absolutePath}\" -i \"${audioFile.absolutePath}\" " +
                        "-map 0:v -map 2:a -map 1:s " +
                        "-c copy -c:s srt -disposition:a:0 default -disposition:s:0 default \"${outputFile.absolutePath}\""
            } else {
                "-y -i \"${videoFile.absolutePath}\" -i \"${subFile.absolutePath}\" -c copy -c:s srt -disposition:s:0 default \"${outputFile.absolutePath}\""
            }

            FFmpegKit.executeAsync(command) { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    try {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            outputFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), R.string.guardado_en_downloads, Toast.LENGTH_SHORT).show()
                        }
                    } catch (exception: Exception) {
                        Log.e("FFmpegError", "Error al copiar archivo: ${exception.message}")
                    }
                } else {
                    Log.e("FFmpegError", session.allLogsAsString)
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), R.string.error_en_ffmpeg, Toast.LENGTH_SHORT).show()
                    }
                }
                videoFile.delete()
                subFile.delete()
                audioUri?.let { File(requireContext().cacheDir, "input_audio.mp3").delete() }
                if (outputFile.exists()) outputFile.delete()
            }
        }
    }

    private fun hardcodearSubtitulos() {
        val subUri = selectedSubtitleUri
        if (videoPlaylist.isEmpty() || subUri == null) {
            Toast.makeText(requireContext(), R.string.selecciona_video_y_srt, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), R.string.incrustando_subtitulos_msg, Toast.LENGTH_LONG).show()
        requireActivity().runOnUiThread { mostrarProgreso() }

        val videoUri = videoPlaylist[currentIndex]
        Thread {
            val originalName = requireContext().contentResolver.query(
                videoUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "Video_${System.currentTimeMillis()}"

            val baseName = originalName.substringBeforeLast(".")
            val fileName = "${baseName}_sub.mp4"

            val videoFile = cacheUriToFile(videoUri, "input_hardcode.mp4")
            val outputFile = File(requireContext().cacheDir, "output_hardcode.mp4")

            if (outputFile.exists()) {
                outputFile.delete()
            }

            val fontFileRegular = File(getFontDir(), "roboto_regular.ttf").absolutePath
            val fontFileItalic = File(getItalicFontDir(), "roboto_italic.ttf").absolutePath

            val videoWidth = getVideoWidth(videoFile)
            val fontSize = (videoWidth / 22).coerceIn(12, 36)
            val drawtextFilter = buildDrawtextFilters(subtitleList, fontFileRegular, fontFileItalic, fontSize)

            if (drawtextFilter.isBlank()) {
                Log.e("FFmpegHardcode", "subtitleList está vacía, no se generó ningún filtro drawtext")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), R.string.no_hay_subtitulos_msg, Toast.LENGTH_SHORT).show()
                }
                ocultarProgreso()
                videoFile.delete()
                return@Thread
            }

            // Usamos h264_mediacodec con pix_fmt yuv420p para máxima compatibilidad con el hardware de Android
            val command = "-y -i \"${videoFile.absolutePath}\" -vf \"$drawtextFilter,format=yuv420p\" -c:v h264_mediacodec -b:v 2M -c:a copy \"${outputFile.absolutePath}\""
            Log.d("FFmpegHardcode", "Comando ejecutando: $command")

            FFmpegKit.executeAsync(command) { session ->
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName, "video/mp4")
                } else {
                    Log.e("FFmpegHardcode", "FALLÓ la incrustación. Código: ${session.resultCode}")
                }
                requireActivity().runOnUiThread { clearSubtitles() }
                ocultarProgreso()
                videoFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }
        }.start()
    }

    private fun crearVideoDesdeFotos(uris: List<Uri>) {
        Toast.makeText(requireContext(), getString(R.string.creando_slideshow_msg, uris.size), Toast.LENGTH_LONG).show()
        mostrarProgreso()

        Thread {
            try {
                val carpetaTemp = File(requireContext().cacheDir, "slideshow_${System.currentTimeMillis()}").apply { mkdirs() }
                uris.forEachIndexed { index, uri ->
                    val destino = File(carpetaTemp, "img%03d.jpg".format(index))
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destino).use { output -> input.copyTo(output) }
                    }
                    Log.d("FFmpegSlideshow", "img%03d.jpg".format(index) + " -> ${destino.length()} bytes")
                }

                val fileName = "Slideshow_${System.currentTimeMillis()}.mp4"
                val outputFile = File(requireContext().cacheDir, "output_slideshow.mp4")
                if (outputFile.exists()) outputFile.delete()

                val inputArgs = StringBuilder()
                val filterComplex = StringBuilder()
                uris.forEachIndexed { index, _ ->
                    val imgPath = File(carpetaTemp, "img%03d.jpg".format(index)).absolutePath
                    inputArgs.append("-loop 1 -t 3 -i $imgPath ")
                    filterComplex.append("[$index:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,fps=30[v$index];")
                }
                for (index in uris.indices) {
                    filterComplex.append("[v$index]")
                }
                filterComplex.append("concat=n=${uris.size}:v=1:a=0[outv]")

                val filterScriptFile = File(requireContext().cacheDir, "slideshow_filter.txt")
                filterScriptFile.writeText(filterComplex.toString())

                val command = "-y $inputArgs-filter_complex_script \"${filterScriptFile.absolutePath}\" -map [outv] -c:v mpeg4 -q:v 3 \"${outputFile.absolutePath}\""
                Log.d("FFmpegSlideshow", "Comando: $command")

                FFmpegKit.executeAsync(command) { session ->
                    if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                        saveToDownloads(outputFile, fileName, "video/mp4")
                    }
                    ocultarProgreso()
                    carpetaTemp.deleteRecursively()
                    filterScriptFile.delete()
                    if (outputFile.exists()) outputFile.delete()
                }
            } catch (exception: Exception) {
                ocultarProgreso()
            }
        }.start()
    }

    private fun splitVideo(videoUri: Uri, startTime: String, endTime: String) {
        val videoFile = cacheUriToFile(videoUri, "input_split.mp4")
        val fileName = "Clip_${System.currentTimeMillis()}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val resolver = requireContext().contentResolver
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val destUri = resolver.insert(collectionUri, contentValues)
        requireActivity().runOnUiThread { mostrarProgreso() }

        if (destUri != null) {
            val outputFile = File(requireContext().cacheDir, "output_split.mp4")

            val command = "-y -i \"${videoFile.absolutePath}\" -ss $startTime -to $endTime -c copy \"${outputFile.absolutePath}\""

            FFmpegKit.executeAsync(command) { session ->
                requireActivity().runOnUiThread { ocultarProgreso() }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    try {
                        resolver.openOutputStream(destUri)?.use { outputStream ->
                            outputFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), R.string.clip_guardado, Toast.LENGTH_LONG).show()
                        }
                    } catch (exception: Exception) {
                        Log.e("FFmpegError", "Error al copiar: ${exception.message}")
                    }
                } else {
                    Log.e("FFmpegError", session.allLogsAsString)
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), R.string.error_al_cortar, Toast.LENGTH_SHORT).show()
                    }
                }
                videoFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }
        }
    }


    private fun cacheUriToFile(uri: Uri, name: String): File {
        val file = File(requireContext().cacheDir, name)
        if (file.exists()) file.delete()
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
        return file
    }

    private fun saveToDownloads(file: File, fileName: String, mimeType: String = "video/mp4") {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val isAudio = mimeType.startsWith("audio/")
        val isImage = mimeType.startsWith("image/")
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else if (isAudio) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = requireContext().contentResolver.insert(collectionUri, contentValues)

        if (uri != null) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.guardado_en_downloads), Toast.LENGTH_SHORT).show()
                }
            } catch (exception: Exception) {
                Log.e("SaveToDownloads", "Error copiando $fileName: ${exception.message}")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Error al guardar $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.e("SaveToDownloads", "insert() devolvió null para $fileName (mime=$mimeType)")
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "No se pudo crear $fileName en Descargas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun convertirAudiosAMp3(uris: List<Uri>, calidad: Int = 2) {
        Toast.makeText(requireContext(), "Iniciando conversión masiva...", Toast.LENGTH_LONG).show()
        Thread {
            var exitosos = 0
            var fallidos = 0
            uris.forEachIndexed { index, uri ->
                val originalName = requireContext().contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: "Audio_${System.currentTimeMillis()}_$index"
                val baseName = originalName.substringBeforeLast(".")
                val fileName = "$baseName.mp3"
                val inputFile = cacheUriToFile(uri, "temp_input_audio_$index.tmp")
                if (!inputFile.exists() || inputFile.length() == 0L) {
                    Log.e("ConvertMp3", "Archivo de entrada vacío o inexistente: $uri")
                    fallidos++
                    return@forEachIndexed
                }
                val outputFile = File(requireContext().cacheDir, "output_temp_$index.mp3")
                if (outputFile.exists()) outputFile.delete()

                val command = "-y -i \"${inputFile.absolutePath}\" -map_metadata 0 -id3v2_version 3 -c:a libmp3lame -q:a $calidad \"${outputFile.absolutePath}\""

                val session = FFmpegKit.execute(command)
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    saveToDownloads(outputFile, fileName, "audio/mpeg")
                    exitosos++
                } else {
                    fallidos++
                    Log.e("ConvertMp3", "FALLÓ para $uri: ${session.allLogsAsString}")
                }
                if (inputFile.exists()) inputFile.delete()
                if (outputFile.exists()) outputFile.delete()
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "Conversión: $exitosos ok, $fallidos fallidos", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    companion object {
        const val PREF_SELECTED_FOLDER_URI = "pref_selected_folder_uri"
        const val TAG: String = "BannerHomeFragment"
        @JvmStatic
        fun newInstance(): HomeFragment = HomeFragment()
    }
}
