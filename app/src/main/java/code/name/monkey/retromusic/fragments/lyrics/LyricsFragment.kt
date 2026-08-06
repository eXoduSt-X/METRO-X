package code.name.monkey.retromusic.fragments.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentLyricsBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.openUrl
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.LrcView
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.LyricUtil
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class LyricsFragment : AbsMainActivityFragment(R.layout.fragment_lyrics),
    MusicProgressViewUpdateHelper.Callback {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!
    private lateinit var song: Song

    private var lyricsType: LyricsType = LyricsType.NORMAL_LYRICS
    private var currentProgressMillis: Int = 0
    private var isVideoLoaded = false

    private lateinit var updateHelper: MusicProgressViewUpdateHelper
    private lateinit var videoPickerLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inicializador del selector de video nativo (.mp4, etc)
        videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadTargetVideo(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = Fade()
        exitTransition = Fade()
        
        _binding = FragmentLyricsBinding.bind(view)
        updateHelper = MusicProgressViewUpdateHelper(this, 50, 50)
        
        updateTitleSong()
        setupLyricsView()
        loadLyrics()

        setupViews()
        setupToolbar()
        setupSincroControls()
    }

    private fun setupLyricsView() {
        binding.lyricsView.apply {
            setCurrentColor(accentColor())
            setTimeTextColor(accentColor())
            setTimelineColor(accentColor())
            setTimelineTextColor(accentColor())
            setDraggable(true, LrcView.OnPlayClickListener {
                seekToProgress(it.toInt())
                return@OnPlayClickListener true
            })
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        if (!isVideoLoaded) {
            currentProgressMillis = progress
            binding.lyricsView.updateTime(progress.toLong())
            binding.tvCurrentTime.text = formatTimeLrc(progress)
            binding.btnPlayPause.text = if (MusicPlayerRemote.isPlaying) "Pause" else "Play"
        }
    }

    private fun setupViews() {

    binding.saveFab.accentColor()

    val currentContent =
        if (lyricsType == LyricsType.SYNCED_LYRICS) {
            LyricUtil.getStringFromLrc(
                LyricUtil.getSyncedLyricsFile(song)
            ) ?: getEmbeddedLyricsText()
        } else {
            getEmbeddedLyricsText()
        }

    if (binding.etLyrics.text.isNullOrEmpty()) {
        binding.etLyrics.setText(currentContent)
    }

binding.saveFab.setOnClickListener {
        val lyricsText = binding.etLyrics.text.toString()
        val songFile = File(song.data)
        val lrcFile = File(songFile.parentFile, songFile.nameWithoutExtension + ".lrc")

        try {
            // Con el permiso MANAGE_EXTERNAL_STORAGE, esto funcionará en cualquier carpeta.
            lrcFile.writeText(lyricsText, Charsets.UTF_8)
            
            lyricsType = LyricsType.SYNCED_LYRICS
            
            // 4. Recargamos la vista de letras
            binding.lyricsView.loadLrc(lrcFile)

            Toast.makeText(requireContext(), "Guardado exitosamente", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            // Si llegara a fallar (por ejemplo, si el usuario no concedió el permiso),
            // notificamos con un mensaje claro.
            Toast.makeText(
                requireContext(), 
                "Error al guardar: ${e.localizedMessage}. Asegúrate de haber concedido el acceso a archivos.", 
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

    private fun setupSincroControls() {
        val allButtons = listOf(
            binding.btnRew, binding.btnFwd, binding.btnMark, binding.btnPlayPause,
            binding.btnLeft, binding.btnRight, binding.btnUp, binding.btnDown,
            binding.btnSrt, binding.btnLoadVideo, binding.btnHome, binding.btnEnd
        )
        allButtons.forEach { view ->
            view.isFocusable = false
            view.isFocusableInTouchMode = false
        }

        binding.btnPlayPause.setOnClickListener {
            if (isVideoLoaded) {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                    binding.btnPlayPause.text = "Play"
                } else {
                    binding.videoView.start()
                    binding.btnPlayPause.text = "Pause"
                    updateVideoTimerLoop()
                }
            } else {
                if (MusicPlayerRemote.isPlaying) MusicPlayerRemote.pauseSong() else MusicPlayerRemote.resumePlaying()
                binding.btnPlayPause.text = if (MusicPlayerRemote.isPlaying) "Pause" else "Play"
            }
        }

        binding.btnRew.setOnClickListener {
            val newPos = max(currentProgressMillis - 5000, 0)
            seekToProgress(newPos)
        }

        binding.btnFwd.setOnClickListener {
            val totalDuration = if (isVideoLoaded) binding.videoView.duration else MusicPlayerRemote.songDurationMillis
            val newPos = min(currentProgressMillis + 5000, totalDuration)
            seekToProgress(newPos)
        }

        binding.btnMark.setOnClickListener {
            handleMarking()
            binding.lyricsView.loadLrc(binding.etLyrics.text.toString())
            binding.lyricsView.updateTime(currentProgressMillis.toLong())
            binding.etLyrics.requestFocus()
        }

        binding.btnSrt.setOnClickListener { exportToSrtFile() }
        binding.btnLoadVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }

        binding.btnLeft.setOnClickListener {
            val pos = binding.etLyrics.selectionStart
            if (pos > 0) binding.etLyrics.setSelection(pos - 1)
            binding.etLyrics.requestFocus()
        }

        binding.btnRight.setOnClickListener {
            val pos = binding.etLyrics.selectionStart
            if (pos < binding.etLyrics.text.length) binding.etLyrics.setSelection(pos + 1)
            binding.etLyrics.requestFocus()
        }

        binding.btnUp.setOnClickListener { moveCursorLine(-1); binding.etLyrics.requestFocus() }
        binding.btnDown.setOnClickListener { moveCursorLine(1); binding.etLyrics.requestFocus() }
        binding.btnHome.setOnClickListener { moveCursorToLineStart() }
        binding.btnEnd.setOnClickListener { moveCursorToLineEnd() }
     }

    private fun moveCursorToLineStart() {
    val pos = binding.etLyrics.selectionStart
    val text = binding.etLyrics.text.toString()
    if (text.isEmpty()) return

    val currentLineStart = text.lastIndexOf("\n", pos - 1) + 1
    binding.etLyrics.setSelection(currentLineStart)
    binding.etLyrics.requestFocus()
}

private fun moveCursorToLineEnd() {
    val pos = binding.etLyrics.selectionStart
    val text = binding.etLyrics.text.toString()
    if (text.isEmpty()) return

    var currentLineEnd = text.indexOf("\n", pos)
    if (currentLineEnd == -1) currentLineEnd = text.length

    binding.etLyrics.setSelection(currentLineEnd)
    binding.etLyrics.requestFocus()
}

    private fun seekToProgress(ms: Int) {
        currentProgressMillis = ms
        if (isVideoLoaded) {
            binding.videoView.seekTo(ms)
            binding.tvCurrentTime.text = formatTimeLrc(ms)
            binding.lyricsView.updateTime(ms.toLong())
        } else {
            MusicPlayerRemote.seekTo(ms)
            binding.lyricsView.updateTime(ms.toLong())
            binding.tvCurrentTime.text = formatTimeLrc(ms)
        }
    }

    private fun loadTargetVideo(uri: Uri) {
        if (MusicPlayerRemote.isPlaying) {
            MusicPlayerRemote.pauseSong()
        }
        isVideoLoaded = true
        binding.videoContainer.visibility = View.VISIBLE
        binding.videoView.setVideoURI(uri)
        
        binding.videoView.setOnPreparedListener { mp ->
            binding.videoView.seekTo(1)
            currentProgressMillis = 0
            binding.tvCurrentTime.text = formatTimeLrc(0)
            binding.btnPlayPause.text = "Play"
            Toast.makeText(requireContext(), "Video acoplado para sincronización", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateVideoTimerLoop() {
        if (isVideoLoaded && binding.videoView.isPlaying) {
            val pos = binding.videoView.currentPosition
            currentProgressMillis = pos
            binding.tvCurrentTime.text = formatTimeLrc(pos)
            binding.lyricsView.updateTime(pos.toLong())
            
            binding.tvCurrentTime.postDelayed({ updateVideoTimerLoop() }, 100)
        }
    }

    private fun handleMarking() {

    val pos = binding.etLyrics.selectionStart

    val text = binding.etLyrics.text.toString()
        .replace("\r\n", "\n")
        .replace("\r", "\n")

    if (text.isEmpty()) return

    val lineStart = text.lastIndexOf("\n", pos - 1) + 1

    var lineEnd = text.indexOf("\n", pos)
    if (lineEnd == -1) lineEnd = text.length

    val fullLine = text.substring(lineStart, lineEnd)

    // Eliminar TODOS los timestamps existentes
    val cleanLine = fullLine
        .replace("\\[[^\\]]+\\]".toRegex(), "")
        .trim()

    val timeStamp = formatTimeLrc(currentProgressMillis)

    val newLine = "$timeStamp $cleanLine"

    val updatedText =
        text.substring(0, lineStart) +
        newLine +
        text.substring(lineEnd)

    binding.etLyrics.setText(updatedText)

    // Avanzar SIEMPRE a la siguiente línea
    val nextLineStart = updatedText.indexOf('\n', lineStart)

    if (nextLineStart != -1) {
        binding.etLyrics.setSelection(nextLineStart + 1)
    } else {
        binding.etLyrics.setSelection(updatedText.length)
    }
}

    private fun exportToSrtFile() {
        try {
            val downloadFolder = File("/sdcard/Download")
            if (!downloadFolder.exists()) downloadFolder.mkdirs()

            val srtFile = File(downloadFolder, "${song.title}.srt")
            val pw = PrintWriter(FileWriter(srtFile))
            val lines = binding.etLyrics.text.toString().split("\n")
            var index = 1

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.matches("^\\[\\d{2}:\\d{2}\\.\\d{2}\\].*".toRegex())) {
                    val timestampLrc = line.substring(0, 10)
                    val text = line.substring(10).trim()
                    val startMs = lrcTimeToMs(timestampLrc)
                    var endMs: Int

                    if (i + 1 < lines.size && lines[i + 1].trim().matches("^\\[\\d{2}:\\d{2}\\.\\d{2}\\].*".toRegex())) {
                        endMs = lrcTimeToMs(lines[i + 1].trim().substring(0, 10))
                    } else {
                        endMs = startMs + 4000
                        val maxDuration = if (isVideoLoaded) binding.videoView.duration else MusicPlayerRemote.songDurationMillis
                        if (maxDuration > 0 && endMs > maxDuration) {
                            endMs = maxDuration
                        }
                    }

                    pw.println(index)
                    pw.println("${formatTimeSrt(startMs)} --> ${formatTimeSrt(endMs)}")
                    pw.println(text)
                    pw.println()
                    index++
                }
            }
            pw.close()
            Toast.makeText(requireContext(), "SRT Creado: ${song.title}.srt en Descargas", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error al exportar SRT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTimeLrc(ms: Int): String {
        val m = (ms / 1000) / 60
        val s = (ms / 1000) % 60
        val mm = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", m, s, mm)
    }

    private fun formatTimeSrt(ms: Int): String {
        val h = (ms / 1000) / 3600
        val m = ((ms / 1000) % 3600) / 60
        val s = (ms / 1000) % 60
        val msec = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msec)
    }

    private fun lrcTimeToMs(timestamp: String): Int {
        return try {
            val clean = timestamp.replace("[", "").replace("]", "")
            val parts = clean.split(":")
            val min = parts[0].toInt()
            val secParts = parts[1].split('.')
            val sec = secParts[0].toInt()
            val msPart = secParts[1].toInt() * 10
            (min * 60 * 1000) + (sec * 1000) + msPart
        } catch (e: Exception) {
            0
        }
    }

    private fun moveCursorLine(direction: Int) {
        val pos = binding.etLyrics.selectionStart
        val text = binding.etLyrics.text.toString()
        if (text.isEmpty()) return

        val currentLineStart = text.lastIndexOf("\n", pos - 1) + 1
        var currentLineEnd = text.indexOf("\n", pos)
        if (currentLineEnd == -1) currentLineEnd = text.length

        val column = pos - currentLineStart

        if (direction == -1) {
            if (currentLineStart <= 0) return
            val prevLineStart = text.lastIndexOf("\n", currentLineStart - 2) + 1
            val prevLineEnd = currentLineStart - 1
            val prevLineLength = prevLineEnd - prevLineStart
            val targetPos = prevLineStart + min(column, prevLineLength)
            binding.etLyrics.setSelection(targetPos)
        } else if (direction == 1) {
            if (currentLineEnd >= text.length) return
            val nextLineStart = currentLineEnd + 1
            var nextLineEnd = text.indexOf("\n", nextLineStart)
            if (nextLineEnd == -1) nextLineEnd = text.length
            val nextLineLength = nextLineEnd - nextLineStart
            val targetPos = nextLineStart + min(column, nextLineLength)
            binding.etLyrics.setSelection(targetPos)
        }
    }

    private fun getEmbeddedLyricsText(): String {
        return try {
            val file = File(song.data)
            if (!file.exists()) return ""
            val tag = org.jaudiotagger.audio.AudioFileIO.read(file).tagOrCreateDefault
            tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun loadLyrics() {

    val lrcFile = LyricUtil.getSyncedLyricsFile(song)

    when {

        lrcFile != null && lrcFile.exists() -> {

            binding.lyricsView.loadLrc(lrcFile)

            try {
                binding.etLyrics.setText(lrcFile.readText())
            } catch (e: Exception) {
                binding.etLyrics.setText("")
            }

            lyricsType = LyricsType.SYNCED_LYRICS
        }

        else -> {

            binding.etLyrics.setText("")

            lyricsType = LyricsType.NORMAL_LYRICS

            Toast.makeText(
                requireContext(),
                "No existe archivo LRC. Puedes crear uno manualmente.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    binding.etLyrics.isVisible = true
    binding.normalLyrics.isVisible = false
    }

    private fun updateTitleSong() { song = MusicPlayerRemote.currentSong }
    private fun setupToolbar() {
        mainActivity.setSupportActionBar(binding.toolbar)
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_lyrics, menu)
        for (i in 0 until menu.size()) menu.getItem(i).icon?.setTint(Color.WHITE)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_search) openUrl(googleSearchLrcUrl)
        return false
    }

    private val googleSearchLrcUrl: String
        get() = "http://www.google.com/search?q=${(song.title + "+" + song.artistName).replace(" ", "+")}+lyrics"

    override fun onResume() { super.onResume(); updateTitleSong(); updateHelper.start() }
    override fun onPause() { super.onPause(); updateHelper.stop() }
    override fun onDestroyView() { _binding = null; super.onDestroyView() }

    enum class LyricsType { NORMAL_LYRICS, SYNCED_LYRICS }
    }
    
