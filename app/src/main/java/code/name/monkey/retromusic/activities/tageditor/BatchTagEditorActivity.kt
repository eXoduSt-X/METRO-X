package code.name.monkey.retromusic.activities.tageditor

import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchTagEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchTagEditorBinding
    private val viewModel: BatchTagEditorViewModel by viewModel()
    private lateinit var adapter: BatchSongAdapter
    private lateinit var metadataAdapter: MetadataReferenceAdapter

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

        binding.btnRenameFiles.setOnClickListener {
            renameFiles()
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
        Toast.makeText(this, "Tags saved (Simulation)", Toast.LENGTH_SHORT).show()
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
