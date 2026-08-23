package code.name.monkey.retromusic.fragments

import android.os.Environment
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class YoutubeDownloaderViewModel : ViewModel() {

    private val _progress = MutableLiveData<Int>()
    val progress: LiveData<Int> = _progress

    private val _status = MutableLiveData<CharSequence>()
    val status: LiveData<CharSequence> = _status

    private val _isDownloading = MutableLiveData<Boolean>()
    val isDownloading: LiveData<Boolean> = _isDownloading

    private val _videoInfo = MutableLiveData<VideoInfo?>()
    val videoInfo: LiveData<VideoInfo?> = _videoInfo

    private fun ensureInitialized(): String? {
        return try {
            YoutubeDL.getInstance().init(code.name.monkey.retromusic.App.getContext())
            null
        } catch (e: Exception) {
            Log.e("YoutubeDL", "Failed to initialize in ViewModel", e)
            e.message ?: "Unknown initialization error"
        }
    }

    fun fetchVideoInfo(url: String) {
        if (url.isBlank()) {
            _status.value = "URL cannot be empty"
            return
        }

        val initError = ensureInitialized()
        if (initError != null) {
            _status.value = "Init Error: $initError"
            return
        }

        _isDownloading.value = true
        _status.value = "Fetching video info..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-check-certificate")
                request.addOption("--no-cache-dir")
                request.addOption("--no-update")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                request.addOption("--extractor-args", "youtube:player-client=android,web;player-skip=web_embedded_player,web_music_player")

                val info = YoutubeDL.getInstance().getInfo(request)
                withContext(Dispatchers.Main) {
                    _videoInfo.value = info
                    _isDownloading.value = false
                    _status.value = "Info fetched: ${info.title}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _status.value = "Error fetching info: ${e.message}"
                    _isDownloading.value = false
                }
            }
        }
    }

    fun download(url: String, videoTitle: String, formatId: String?, isVideo: Boolean) {
        val initError = ensureInitialized()
        if (initError != null) {
            _status.value = "Init Error: $initError"
            return
        }

        _isDownloading.value = true
        _progress.value = 0
        _status.value = "Downloading: $videoTitle"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-check-certificate")
                request.addOption("--no-cache-dir")
                request.addOption("--no-update")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                request.addOption("--extractor-args", "youtube:player-client=android,web;player-skip=web_embedded_player,web_music_player")

                // Tanto para video como para audio pedimos el MISMO stream de video+audio.
                // Para audio (isVideo=false) extraemos el audio localmente con FFmpegKit más abajo,
                // en vez de pedirle a yt-dlp un formato audio-only (esos itags están cayendo con
                // el mismo bloqueo de PO Token, y así solo peleamos la restricción una vez).
                if (formatId != null) {
                    request.addOption("-f", "$formatId+bestaudio/best")
                } else {
                    request.addOption("-f", "bestvideo+bestaudio/best")
                }
                request.addOption("--merge-output-format", "mp4")

                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val downloadFolder = File(downloadDir, "0 VIDEO")
                if (!downloadFolder.exists()) downloadFolder.mkdirs()

                request.addOption("-o", "${downloadFolder.absolutePath}/%(title)s.%(ext)s")

                var downloadedFile: File? = null

                YoutubeDL.getInstance().execute(request) { progress, _, line ->
                    if (isActive) {
                        val p = progress.toInt().coerceIn(0, 100)
                        _progress.postValue(p)
                        if (line.contains("Destination:")) {
                            val path = line.substringAfter("Destination:").trim()
                            downloadedFile = File(path)
                        }
                        if (line.contains("%")) {
                            _status.postValue(line.trim())
                        }
                    }
                }

                if (!isVideo && downloadedFile != null && downloadedFile!!.exists()) {
                    // Iniciamos conversión manual a MP3 usando FFmpegKit
                    withContext(Dispatchers.Main) { _status.value = "Converting to MP3..." }
                    val mp3File = File(downloadedFile!!.parent, downloadedFile!!.nameWithoutExtension + ".mp3")
                    val cmd = "-y -i \"${downloadedFile!!.absolutePath}\" -c:a libmp3lame -q:a 0 \"${mp3File.absolutePath}\""

                    val session = FFmpegKit.execute(cmd)
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        downloadedFile!!.delete() // Borrar el mp4 original, ya que solo queríamos el audio
                    }
                }

                withContext(Dispatchers.Main) {
                    _status.value = "Success: Download completed in 0 VIDEO"
                    _isDownloading.value = false
                    _progress.value = 100
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _status.value = "Error: ${e.message}"
                    _isDownloading.value = false
                }
            }
        }
    }
}
