package code.name.monkey.retromusic.activities.tageditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.databinding.ActivityBatchTagEditorBinding
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class BatchTagEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchTagEditorBinding
    private val viewModel: BatchTagEditorViewModel by viewModel()
    private lateinit var adapter: BatchSongAdapter
    private lateinit var metadataAdapter: MetadataReferenceAdapter
    private var selectedArtBitmap: Bitmap? = null

    private val artPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                selectedArtBitmap = BitmapFactory.decodeStream(stream)
                binding.ivAlbumArt.setImageBitmap(selectedArtBitmap)
            }
        }
    }

    private val patterns = listOf(
        "%track% - %title%",
        "%artist% - %title%",
        "%track% - %artist% - %title%",
        "%title%",
        "Custom..."
    )

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            loadFolder(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchTagEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupDragAndDrop()
        setupObservers()
        setupListeners()
        setupPatternSpinner()
    }

    private fun setupRecyclerViews() {
        adapter = BatchSongAdapter(mutableListOf())
        binding.rvSongs.layoutManager = LinearLayoutManager(this)
        binding.rvSongs.adapter = adapter

        metadataAdapter = MetadataReferenceAdapter(emptyList())
        binding.rvMetadata.layoutManager = LinearLayoutManager(this)
        binding.rvMetadata.adapter = metadataAdapter
    }

    private fun setupDragAndDrop() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                adapter.onItemMove(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Actualizar el ViewModel solo al soltar el elemento
                viewModel.setSongs(adapter.getItems())
                viewModel.applyMetadataToCurrentOrder()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvSongs)
    }

    private fun setupObservers() {
        viewModel.songs.observe(this, Observer { songs ->
            adapter.updateItems(songs)
        })

        viewModel.mbTracks.observe(this, Observer { tracks ->
            metadataAdapter.updateItems(tracks)
        })

        viewModel.status.observe(this, Observer { status ->
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        })
    }

    private fun setupListeners() {
        binding.btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnApplyPattern.setOnClickListener {
            val pattern = if (binding.spinnerPatterns.selectedItem == "Custom...") {
                binding.etPattern.text.toString()
            } else {
                binding.spinnerPatterns.selectedItem.toString()
            }
            viewModel.applyPattern(pattern)
        }

        binding.btnSortAlpha.setOnClickListener {
            viewModel.sortAlphabetically()
        }

        binding.btnClearTags.setOnClickListener {
            viewModel.clearPendingTags()
        }

        binding.btnSaveAll.setOnClickListener {
            saveChanges()
        }

        binding.btnSelectArt.setOnClickListener {
            artPickerLauncher.launch("image/*")
        }

        binding.btnRenameFiles.setOnClickListener {
            renameFiles()
        }

        binding.btnNumberTracks.setOnClickListener {
            viewModel.numberTracksSequentially()
        }
        
        binding.btnFetchInternet.setOnClickListener {
            val album = binding.etAlbumSearch.text.toString()
            val artist = binding.etArtistSearch.text.toString()
            
            if (album.isNotBlank()) {
                viewModel.fetchFromMusicBrainz(album, artist)
            } else {
                Toast.makeText(this, "Enter album name at least", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPatternSpinner() {
        val patternAdapter = ArrayAdapter(this, code.name.monkey.retromusic.R.layout.spinner_item_white, patterns)
        patternAdapter.setDropDownViewResource(code.name.monkey.retromusic.R.layout.spinner_item_white)
        binding.spinnerPatterns.adapter = patternAdapter
        
        binding.spinnerPatterns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = patterns[position] == "Custom..."
                binding.etPattern.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (!isCustom) {
                    binding.etPattern.setText(patterns[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadFolder(uri: Uri) {
        val folderName = uri.path?.split("/")?.last() ?: ""
        binding.tvCurrentFolder.text = uri.path
        binding.etAlbumSearch.setText(folderName)
        
        lifecycleScope.launch(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(this@BatchTagEditorActivity, uri)
            val files = tree?.listFiles()?.filter { 
                it.isFile && (it.name?.endsWith(".mp3", true) == true || it.name?.endsWith(".m4a", true) == true)
            }?.sortedBy { it.name }
            
            withContext(Dispatchers.Main) {
                val items = files?.map { doc ->
                    val duration = getDuration(doc.uri)
                    BatchSongItem(doc, durationText = duration) 
                } ?: emptyList()
                viewModel.setSongs(items)
            }
        }
    }

    private fun getDuration(uri: Uri): String {
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val durationStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        } catch (_: Exception) {
            "--:--"
        }
    }

    private fun saveChanges() {
        val songs = viewModel.songs.value ?: return
        if (songs.isEmpty()) {
            Toast.makeText(this, "No songs to save", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Saving tags and artwork...", Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var errorCount = 0

            // Preparar artwork si existe
            var artworkFile: File? = null
            if (selectedArtBitmap != null) {
                try {
                    artworkFile = File(cacheDir, "temp_batch_art.jpg")
                    val out = FileOutputStream(artworkFile)
                    selectedArtBitmap?.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            songs.forEach { item ->
                val tags = item.pendingTags
                // Solo procedemos si hay etiquetas pendientes O si hay una nueva portada
                if (tags != null || artworkFile != null) {
                    if (saveItemTags(item, tags, artworkFile)) {
                        successCount++
                    } else {
                        errorCount++
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (errorCount == 0) {
                    Toast.makeText(this@BatchTagEditorActivity, "Successfully saved $successCount songs!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@BatchTagEditorActivity, "Saved $successCount, failed $errorCount", Toast.LENGTH_LONG).show()
                }
                // Limpiar etiquetas pendientes después de guardar con éxito
                viewModel.clearPendingTags()
            }
        }
    }

    private fun saveItemTags(item: BatchSongItem, tags: TagFields?, artFile: File?): Boolean {
        try {
            val uri = item.document.uri
            // Creamos un archivo temporal para que jaudiotagger pueda trabajar
            val tempFile = File.createTempFile("edit", ".tmp", cacheDir)
            
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tagOrCreateAndSetDefault

            // Aplicar etiquetas si existen
            tags?.let {
                if (!it.title.isNullOrBlank()) tag.setField(FieldKey.TITLE, it.title)
                if (!it.artist.isNullOrBlank()) tag.setField(FieldKey.ARTIST, it.artist)
                if (!it.album.isNullOrBlank()) tag.setField(FieldKey.ALBUM, it.album)
                if (!it.track.isNullOrBlank()) tag.setField(FieldKey.TRACK, it.track)
                if (!it.year.isNullOrBlank()) tag.setField(FieldKey.YEAR, it.year)
                if (!it.genre.isNullOrBlank()) tag.setField(FieldKey.GENRE, it.genre)
            }

            // Aplicar Portada si existe
            artFile?.let {
                val artwork = AndroidArtwork.createArtworkFromFile(it)
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            audioFile.commit()

            // Escribir de vuelta al archivo original vía SAF
            val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "rw")
            pfd?.use {
                val fos = FileOutputStream(it.fileDescriptor)
                val fis = FileInputStream(tempFile)
                fos.write(FileUtil.readBytes(fis))
                fos.close()
                fis.close()
            }

            tempFile.delete()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun renameFiles() {
        val pattern = if (binding.spinnerPatterns.selectedItem == "Custom...") {
            binding.etPattern.text.toString()
        } else {
            binding.spinnerPatterns.selectedItem.toString()
        }

        if (pattern.isEmpty() || pattern == "Custom...") {
            Toast.makeText(this, "Select or enter a valid rename pattern", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val songs = viewModel.songs.value ?: return@launch
            var successCount = 0
            songs.forEach { item ->
                val tags = item.pendingTags
                if (tags != null) {
                    val currentName = item.document.name ?: return@forEach
                    val extension = if (currentName.contains(".")) currentName.substring(currentName.lastIndexOf(".")) else ".mp3"
                    val newName = PatternEngine.tagsToFilename(pattern, tags) + extension
                    
                    if (item.document.renameTo(newName)) {
                        successCount++
                    }
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@BatchTagEditorActivity, "Renamed $successCount files!", Toast.LENGTH_SHORT).show()
                // Recargar para ver los nuevos nombres
                adapter.notifyDataSetChanged()
            }
        }
    }
}
