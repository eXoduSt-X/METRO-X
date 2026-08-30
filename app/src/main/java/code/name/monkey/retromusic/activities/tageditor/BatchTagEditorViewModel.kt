package code.name.monkey.retromusic.activities.tageditor

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import code.name.monkey.retromusic.network.MusicBrainzService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchTagEditorViewModel(private val musicBrainzService: MusicBrainzService) : ViewModel() {

    private val _songs = MutableLiveData<List<BatchSongItem>>(emptyList())
    val songs: LiveData<List<BatchSongItem>> = _songs

    private val _mbTracks = MutableLiveData<List<code.name.monkey.retromusic.network.MBTrack>>(emptyList())
    val mbTracks: LiveData<List<code.name.monkey.retromusic.network.MBTrack>> = _mbTracks

    private val _status = MutableLiveData<String>()
    val status: LiveData<String> = _status

    private var currentAlbum: String? = null
    private var currentArtist: String? = null
    private var currentDate: String? = null

    fun setSongs(newSongs: List<BatchSongItem>) {
        _songs.value = newSongs
    }

    fun applyPattern(pattern: String) {
        val currentList = _songs.value ?: return
        currentList.forEach { item ->
            item.pendingTags = PatternEngine.filenameToTags(pattern, item.document.name ?: "")
        }
        _songs.value = currentList
    }

    fun sortAlphabetically() {
        val currentList = _songs.value?.toMutableList() ?: return
        currentList.sortBy { it.document.name }
        _songs.value = currentList
    }

    fun clearPendingTags() {
        val currentList = _songs.value?.toMutableList() ?: return
        currentList.forEach { it.pendingTags = null }
        _songs.value = currentList
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
                } else {
                    _status.postValue("No album found")
                }
            } catch (e: Exception) {
                _status.postValue("Error: ${e.message}")
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
