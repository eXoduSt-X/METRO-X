package code.name.monkey.retromusic.activities.tageditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import code.name.monkey.retromusic.network.MusicBrainzService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class BatchTagEditorViewModel(private val musicBrainzService: MusicBrainzService) : ViewModel() {

    private val _songs = MutableLiveData<List<BatchSongItem>>(emptyList())
    val songs: LiveData<List<BatchSongItem>> = _songs

    private val _mbTracks = MutableLiveData<List<code.name.monkey.retromusic.network.MBTrack>>(emptyList())
    val mbTracks: LiveData<List<code.name.monkey.retromusic.network.MBTrack>> = _mbTracks

    private val _status = MutableLiveData<String>()
    val status: LiveData<String> = _status

    // Carátula sugerida encontrada junto con la info del álbum
    private val _suggestedCoverArt = MutableLiveData<Bitmap?>()
    val suggestedCoverArt: LiveData<Bitmap?> = _suggestedCoverArt

    private var currentAlbum: String? = null
    private var currentArtist: String? = null
    private var currentDate: String? = null

    fun setSongs(newSongs: List<BatchSongItem>) {
        _songs.value = newSongs
    }

    fun setAllSelected(selected: Boolean) {
        val currentList = _songs.value?.toMutableList() ?: return
        currentList.forEach { it.isSelected = selected }
        _songs.value = currentList
    }

    fun batchApplyManualTags(manualTags: TagFields) {
        val currentSongs = _songs.value?.toMutableList() ?: return
        currentSongs.forEach { item ->
            if (item.isSelected && !item.isPlaceholder) {
                // Aplicar solo los campos que no son nulos ni vacíos en manualTags
                val current = item.pendingTags ?: TagFields()
                item.pendingTags = current.copy(
                    artist = if (!manualTags.artist.isNullOrBlank()) manualTags.artist else current.artist,
                    album = if (!manualTags.album.isNullOrBlank()) manualTags.album else current.album,
                    year = if (!manualTags.year.isNullOrBlank()) manualTags.year else current.year,
                    genre = if (!manualTags.genre.isNullOrBlank()) manualTags.genre else current.genre,
                    albumArtist = if (!manualTags.albumArtist.isNullOrBlank()) manualTags.albumArtist else current.albumArtist,
                    composer = if (!manualTags.composer.isNullOrBlank()) manualTags.composer else current.composer,
                    discNumber = if (!manualTags.discNumber.isNullOrBlank()) manualTags.discNumber else current.discNumber,
                    comment = if (!manualTags.comment.isNullOrBlank()) manualTags.comment else current.comment
                )
            }
        }
        _songs.value = currentSongs
    }

    fun updateSingleItemTags(index: Int, newTags: TagFields) {
        val currentSongs = _songs.value?.toMutableList() ?: return
        if (index in currentSongs.indices) {
            currentSongs[index].pendingTags = newTags
            _songs.value = currentSongs
        }
    }

    fun applyPattern(pattern: String) {
        val currentList = _songs.value ?: return
        currentList.forEach { item ->
            if (item.isPlaceholder || item.document == null) return@forEach
            item.pendingTags = PatternEngine.filenameToTags(pattern, item.document.name ?: "")
        }
        _songs.value = currentList
    }

    fun sortAlphabetically() {
        val currentList = _songs.value?.toMutableList() ?: return
        currentList.sortBy { it.document?.name ?: "" }
        _songs.value = currentList
    }

    fun clearPendingTags() {
        val currentList = _songs.value?.toMutableList() ?: return
        // Clear también elimina los huecos vacíos añadidos con "+"
        val filtered = currentList.filterNot { it.isPlaceholder }
        filtered.forEach { it.pendingTags = null }
        _songs.value = filtered
    }

    /**
     * Agrega un ítem "placeholder" (sin archivo real) a la lista local.
     * Sirve para saltar pistas que faltan físicamente pero sí existen en la
     * metadata del álbum, para no desalinear el resto del orden.
     */
    fun addPlaceholder() {
        val currentList = _songs.value?.toMutableList() ?: mutableListOf()
        currentList.add(
            BatchSongItem(
                document = null,
                durationText = "--:--",
                isPlaceholder = true
            )
        )
        _songs.value = currentList
        applyMetadataToCurrentOrder()
    }

    /**
     * Numera el campo TRACK de forma secuencial según el orden ACTUAL de la lista
     * (el que se ve en pantalla, incluyendo cualquier reordenamiento manual por drag).
     * Padding automático: "01".."09" con 2 dígitos, pasa a 3 dígitos si hay 100+ canciones.
     */
    fun numberTracksSequentially() {
        val currentList = _songs.value?.toMutableList() ?: return
        val padding = maxOf(2, currentList.size.toString().length)
        currentList.forEachIndexed { index, item ->
            val trackNumber = (index + 1).toString().padStart(padding, '0')
            item.pendingTags = (item.pendingTags ?: TagFields()).copy(track = trackNumber)
        }
        _songs.value = currentList
    }

    fun fetchFromMusicBrainz(albumName: String, artistName: String) {
        currentArtist = artistName
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _status.postValue("Searching $albumName...")
                val query = "release:\"$albumName\" AND artist:\"$artistName\""
                val response = musicBrainzService.searchRelease(query)

                val release = response.releases.firstOrNull()
                if (release != null) {
                    currentAlbum = release.title
                    currentDate = release.date
                    val details = musicBrainzService.getReleaseDetails(release.id)
                    val tracks = details.media?.firstOrNull()?.tracks ?: emptyList()

                    withContext(Dispatchers.Main) {
                        _mbTracks.value = tracks
                        _status.value = "Album found: ${release.title}"
                        applyMetadataToCurrentOrder()
                    }

                    // Busca la carátula en paralelo a la info del álbum
                    fetchCoverArt(release.id)
                } else {
                    _status.postValue("No album found")
                    _suggestedCoverArt.postValue(null)
                }
            } catch (e: Exception) {
                _status.postValue("Error: ${e.message}")
            }
        }
    }

    /**
     * Intenta descargar la carátula del release desde Cover Art Archive
     * usando el mismo MBID que ya devuelve MusicBrainz. Si no existe, deja
     * el LiveData en null y la UI simplemente no muestra la miniatura.
     */
    private fun fetchCoverArt(releaseId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://coverartarchive.org/release/$releaseId/front-500")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    connect()
                }
                if (connection.responseCode in 200..299) {
                    val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    _suggestedCoverArt.postValue(bitmap)
                } else {
                    _suggestedCoverArt.postValue(null)
                }
            } catch (e: Exception) {
                _suggestedCoverArt.postValue(null)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun applyMetadataToCurrentOrder() {
        val currentSongs = _songs.value?.toMutableList() ?: return
        val metadata = _mbTracks.value ?: return

        currentSongs.forEachIndexed { index, item ->
            if (index < metadata.size) {
                val mbTrack = metadata[index]
                item.pendingTags = TagFields(
                    title = mbTrack.title,
                    artist = currentArtist, // Usar el que el usuario buscó
                    album = currentAlbum,
                    track = mbTrack.position.toString(),
                    year = currentDate?.take(4)
                )
            } else {
                item.pendingTags = null // No hay metadatos para esta posición
            }
        }
        _songs.value = currentSongs
    }
}
