package code.name.monkey.retromusic.fragments

import android.graphics.Color
import android.os.Environment
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
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
                
                // Back to basic stable configuration (360p fallback)
                request.addOption("--no-check-certificate")
                request.addOption("--no-cache-dir")
                request.addOption("--no-update")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                // 'android' and 'web' are the safest to at least get 360p without PO Token
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Mostrar título en Azul inmediatamente
                withContext(Dispatchers.Main) {
                    val titleSpannable = SpannableString("Downloading: $videoTitle")
                    titleSpannable.setSpan(ForegroundColorSpan(Color.BLUE), 13, titleSpannable.length, 0)
                    _status.value = titleSpannable
                }

                val request = YoutubeDLRequest(url)
                request.addOption("--no-check-certificate")
                request.addOption("--no-cache-dir")
                request.addOption("--no-update")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                request.addOption("--extractor-args", "youtube:player-client=android,web;player-skip=web_embedded_player,web_music_player")

                if (isVideo) {
                    if (formatId != null) {
                        request.addOption("-f", "$formatId+bestaudio/best")
                    } else {
                        request.addOption("-f", "bestvideo+bestaudio/best")
                    }
                    request.addOption("--merge-output-format", "mp4")
                } else {
                    request.addOption("-f", "bestaudio")
                    request.addOption("--extract-audio")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "0")
                }
                
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val downloadFolder = File(downloadDir, "0 VIDEO")
                if (!downloadFolder.exists()) downloadFolder.mkdirs()
                
                request.addOption("-o", "${downloadFolder.absolutePath}/%(title)s.%(ext)s")

                YoutubeDL.getInstance().execute(request) { progress, _, line ->
                    if (isActive) {
                        val p = progress.toInt().coerceIn(0, 100)
                        _progress.postValue(p)
                        // Mostrar progreso en tiempo real también en el log
                        if (line.contains("%") || line.contains("Destination:")) {
                            _status.postValue(line.trim())
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    val successSpannable = SpannableString("Success: Download completed in 0 VIDEO")
                    successSpannable.setSpan(ForegroundColorSpan(Color.GREEN), 0, 7, 0)
                    _status.value = successSpannable
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
