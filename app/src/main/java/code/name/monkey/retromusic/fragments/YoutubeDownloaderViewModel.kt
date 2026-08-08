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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class YoutubeDownloaderViewModel : ViewModel() {

    private val _progress = MutableLiveData<Int>()
    val progress: LiveData<Int> = _progress

    private val _status = MutableLiveData<String>()
    val status: LiveData<String> = _status

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
                
                // Opciones críticas para evitar el Error 403 y fallos de n-challenge
                request.addOption("--no-check-certificate")
                request.addOption("--no-cache-dir")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

    fun download(url: String, formatId: String?, isVideo: Boolean) {
        val initError = ensureInitialized()
        if (initError != null) {
            _status.value = "Init Error: $initError"
            return
        }

        _isDownloading.value = true
        _status.value = "Starting download..."
        _progress.value = 0

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url)
                
                request.addOption("--no-check-certificate")
                request.addOption("--no-cache-dir")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                request.addOption("--extractor-args", "youtube:player-client=android,web")

                if (isVideo) {
                    if (formatId != null) {
                        // Descarga el formato específico seleccionado + el mejor audio
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
                
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    if (isVideo) {
                        Environment.DIRECTORY_MOVIES
                    } else {
                        Environment.DIRECTORY_MUSIC
                    },
                )
                val downloadFolder = File(downloadDir, "METROX_Downloads")
                if (!downloadFolder.exists()) downloadFolder.mkdirs()
                
                request.addOption("-o", "${downloadFolder.absolutePath}/%(title)s.%(ext)s")

                val response = YoutubeDL.getInstance().execute(request) { progress, _, line ->
                    if (isActive) {
                        _progress.postValue(progress.toInt())
                        _status.postValue(line)
                    }
                }

                withContext(Dispatchers.Main) {
                    _status.value = "Success: ${response.out}"
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
