package code.name.monkey.retromusic.activities.tageditor

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.ActivityBatchTagEditorBinding
//import code.name.monkey.retromusic.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.provider.MediaStore
class BatchTagEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchTagEditorBinding
    private val viewModel: BatchTagEditorViewModel by viewModel()
    private lateinit var adapter: BatchSongAdapter
    private lateinit var metadataAdapter: MetadataReferenceAdapter
    private var selectedArtBitmap: Bitmap? = null
    private var lastSaveError: String? = null

    private val artPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                val bytes = stream.readBytes()

                // 1) Solo medir dimensiones, sin cargar el bitmap completo
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

                // 2) Calcular el factor de reducción real
                var calculatedSampleSize = 1
                val maxDimension = 800
                if (boundsOptions.outHeight > maxDimension || boundsOptions.outWidth > maxDimension) {
                    val halfHeight = boundsOptions.outHeight / 2
                    val halfWidth = boundsOptions.outWidth / 2
                    while (halfHeight / calculatedSampleSize >= maxDimension &&
                        halfWidth / calculatedSampleSize >= maxDimension
                    ) {
                        calculatedSampleSize *= 2
                    }
                }

                // 3) Decodificar ya reducido
                val finalOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculatedSampleSize
                }
                selectedArtBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, finalOptions)
                binding.ivAlbumArt.setImageBitmap(selectedArtBitmap)
            }
        }
    }

    private val patterns = listOf(
        "Add Tag...",
        "%track%",
        "%title%",
        "%artist%",
        "%album%",
        "%year%",
        "--- Patterns ---",
        "%track% - %title%",
        "%artist% - %title%",
        "%track% - %artist% - %title%",
        "%title%"
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
        adapter = BatchSongAdapter(mutableListOf()) { index, item ->
            showEditDialog(index, item)
        }
        binding.rvSongs.layoutManager = LinearLayoutManager(this)
        binding.rvSongs.adapter = adapter

        // Listener para editar ítem individual (Título/Track) al hacer click
        // En una app real esto abriría un diálogo, por ahora lo simplificamos
        // implementándolo en el adapter si es necesario, o usando una interfaz.

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
                viewModel.setSongs(adapter.getItems())
                viewModel.applyMetadataToCurrentOrder()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvSongs)
    }

    private fun setupObservers() {
        viewModel.songs.observe(this, Observer { songs ->
            adapter.updateItems(songs)
            binding.tvFileCount.text = "${songs.count { !it.isPlaceholder }} files"
        })

        viewModel.mbTracks.observe(this, Observer { tracks ->
            if (tracks.isNotEmpty()) {
                binding.rvMetadata.visibility = View.VISIBLE
                binding.tvWebRefLabel.visibility = View.VISIBLE
                binding.vListDivider.visibility = View.VISIBLE
                metadataAdapter.updateItems(tracks)
            } else {
                binding.rvMetadata.visibility = View.GONE
                binding.tvWebRefLabel.visibility = View.GONE
                binding.vListDivider.visibility = View.GONE
            }
        })

        viewModel.status.observe(this, Observer { status ->
            if (!status.isNullOrBlank()) {
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            }
        })

        viewModel.suggestedCoverArt.observe(this, Observer { bitmap ->
            if (bitmap != null) {
                binding.ivSuggestedCover.setImageBitmap(bitmap)
                binding.ivSuggestedCover.visibility = View.VISIBLE
            } else {
                binding.ivSuggestedCover.visibility = View.GONE
            }
        })
    }

    private fun setupListeners() {
        binding.btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAllSelected(isChecked)
        }

        binding.btnApplyManualTags.setOnClickListener {
            val manualTags = TagFields(
                artist = binding.etArtistBatch.text.toString().takeIf { it.isNotBlank() },
                album = binding.etAlbumBatch.text.toString().takeIf { it.isNotBlank() },
                year = binding.etYearBatch.text.toString().takeIf { it.isNotBlank() }
            )
            viewModel.batchApplyManualTags(manualTags)
            Toast.makeText(this, "Manual tags applied to selected", Toast.LENGTH_SHORT).show()
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

        binding.btnExtractArt.setOnClickListener {
            extractArt()
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

        binding.btnAddPlaceholder.setOnClickListener {
            viewModel.addPlaceholder()
        }

        binding.ivSuggestedCover.setOnClickListener {
            val bmp = viewModel.suggestedCoverArt.value ?: return@setOnClickListener
            selectedArtBitmap = bmp
            binding.ivAlbumArt.setImageBitmap(bmp)
            Toast.makeText(
                this,
                "Suggested cover applied — will overwrite on Save",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupPatternSpinner() {
        val patternAdapter = ArrayAdapter(this, R.layout.spinner_item_white, patterns)
        patternAdapter.setDropDownViewResource(R.layout.spinner_item_white)
        binding.spinnerPatterns.adapter = patternAdapter

        binding.spinnerPatterns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0 || patterns[position].startsWith("---")) return
                
                val selected = patterns[position]
                val editable = binding.etPattern.text
                val cursorPosition = binding.etPattern.selectionStart
                
                if (cursorPosition >= 0) {
                    editable.insert(cursorPosition, selected)
                } else {
                    editable.append(selected)
                }
                
                // Reset selector to "Add Tag..."
                binding.spinnerPatterns.setSelection(0)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadFolder(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
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
                    BatchSongItem(document = doc, durationText = duration)
                } ?: emptyList()
                viewModel.setSongs(items)
                detectCommonMetadata(items)
            }
        }
    }

    private fun detectCommonMetadata(items: List<BatchSongItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var commonArtBytes: ByteArray? = null
            var artInconsistent = false
            var anyArtFound = false

            var commonArtist: String? = null
            var artistInconsistent = false
            var anyArtistFound = false

            var commonAlbum: String? = null
            var albumInconsistent = false
            var anyAlbumFound = false

            var commonYear: String? = null
            var yearInconsistent = false
            var anyYearFound = false

            for (item in items) {
                if (item.isPlaceholder || item.document == null) continue

                try {
                    val originalName = DocumentFile.fromSingleUri(this@BatchTagEditorActivity, item.document.uri)?.name ?: "temp.mp3"
                    val extension = if (originalName.contains(".")) originalName.substring(originalName.lastIndexOf(".")) else ".mp3"
                    val tempFile = File.createTempFile("meta_check", extension, cacheDir)
                    contentResolver.openInputStream(item.document.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val audioFile = AudioFileIO.read(tempFile)
                    val tag = audioFile.tag

                    // Check Art
                    val bytes = tag?.firstArtwork?.binaryData
                    if (bytes != null) {
                        if (!anyArtFound) {
                            commonArtBytes = bytes
                            anyArtFound = true
                        } else if (!artInconsistent && !bytes.contentEquals(commonArtBytes)) {
                            artInconsistent = true
                        }
                    }

                    // Check Artist
                    val artist = tag?.getFirst(FieldKey.ARTIST)
                    if (!artist.isNullOrBlank()) {
                        if (!anyArtistFound) {
                            commonArtist = artist
                            anyArtistFound = true
                        } else if (!artistInconsistent && artist != commonArtist) {
                            artistInconsistent = true
                        }
                    }

                    // Check Album
                    val album = tag?.getFirst(FieldKey.ALBUM)
                    if (!album.isNullOrBlank()) {
                        if (!anyAlbumFound) {
                            commonAlbum = album
                            anyAlbumFound = true
                        } else if (!albumInconsistent && album != commonAlbum) {
                            albumInconsistent = true
                        }
                    }

                    // Check Year
                    val year = tag?.getFirst(FieldKey.YEAR)
                    if (!year.isNullOrBlank()) {
                        if (!anyYearFound) {
                            commonYear = year
                            anyYearFound = true
                        } else if (!yearInconsistent && year != commonYear) {
                            yearInconsistent = true
                        }
                    }

                    tempFile.delete()
                } catch (_: Exception) { }
            }

            withContext(Dispatchers.Main) {
                // Update UI Artwork
                if (anyArtFound && !artInconsistent && commonArtBytes != null) {
                    selectedArtBitmap = BitmapFactory.decodeByteArray(commonArtBytes, 0, commonArtBytes.size)
                    binding.ivAlbumArt.setImageBitmap(selectedArtBitmap)
                } else {
                    selectedArtBitmap = null
                    binding.ivAlbumArt.setImageResource(R.drawable.ic_album)
                }

                // Update UI Fields
                binding.etArtistBatch.setText(if (artistInconsistent) "--" else commonArtist ?: "")
                binding.etAlbumBatch.setText(if (albumInconsistent) "--" else commonAlbum ?: "")
                binding.etYearBatch.setText(if (yearInconsistent) "--" else commonYear ?: "")
                
                // Auto-fill search fields too
                if (!artistInconsistent && !commonArtist.isNullOrBlank()) {
                    binding.etArtistSearch.setText(commonArtist)
                }
                if (!albumInconsistent && !commonAlbum.isNullOrBlank()) {
                    binding.etAlbumSearch.setText(commonAlbum)
                }
            }
        }
    }

    private fun extractArt() {
        val bitmap = selectedArtBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No artwork to extract", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "Extracted_Cover_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RetroMusic")
                    }
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BatchTagEditorActivity, "Artwork saved to Pictures/RetroMusic", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BatchTagEditorActivity, "Error extracting art: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getDuration(uri: Uri): String {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
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
            lastSaveError = null

            var artworkFile: File? = null
            selectedArtBitmap?.let { bmp ->
                try {
                    artworkFile = File(cacheDir, "temp_batch_art.jpg")
                    FileOutputStream(artworkFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                } catch (e: Exception) {
                    lastSaveError = e.message
                }
            }

            val savedUris = mutableListOf<Uri>()
            songs.forEach { item ->
                if (item.isPlaceholder || item.document == null) return@forEach

                val tags = item.pendingTags
                if (tags != null || artworkFile != null) {
                    if (saveItemTags(item, tags, artworkFile)) {
                        successCount++
                        savedUris.add(item.document.uri)
                    } else {
                        errorCount++
                    }
                }
            }

            if (savedUris.isNotEmpty()) {
                scanFiles(savedUris)
            }

            withContext(Dispatchers.Main) {
                if (errorCount == 0) {
                    Toast.makeText(this@BatchTagEditorActivity, "Successfully saved $successCount songs!", Toast.LENGTH_SHORT).show()
                } else {
                    val reason = lastSaveError?.let { " ($it)" } ?: ""
                    Toast.makeText(this@BatchTagEditorActivity, "Saved $successCount, failed $errorCount$reason", Toast.LENGTH_LONG).show()
                }
                viewModel.clearPendingTags()
            }
        }
    }

    private fun saveItemTags(item: BatchSongItem, tags: TagFields?, artFile: File?): Boolean {
        val document = item.document ?: return false
        try {
            val uri = document.uri

            // jaudiotagger necesita la extensión real (.mp3/.m4a) para
            // detectar el formato; un temporal".tmp" hace fallar AudioFileIO.read()
            val originalName = document.name ?: "temp.mp3"
            val extension = if (originalName.contains(".")) {
                originalName.substring(originalName.lastIndexOf("."))
            } else {
                ".mp3"
            }
            val tempFile = File.createTempFile("edit", extension, cacheDir)

            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tagOrCreateAndSetDefault

            tags?.let {
                if (!it.title.isNullOrBlank()) tag.setField(FieldKey.TITLE, it.title)
                if (!it.artist.isNullOrBlank()) tag.setField(FieldKey.ARTIST, it.artist)
                if (!it.album.isNullOrBlank()) tag.setField(FieldKey.ALBUM, it.album)
                if (!it.track.isNullOrBlank()) tag.setField(FieldKey.TRACK, it.track)
                if (!it.year.isNullOrBlank()) tag.setField(FieldKey.YEAR, it.year)
                if (!it.genre.isNullOrBlank()) tag.setField(FieldKey.GENRE, it.genre)
                if (!it.albumArtist.isNullOrBlank()) tag.setField(FieldKey.ALBUM_ARTIST, it.albumArtist)
                if (!it.composer.isNullOrBlank()) tag.setField(FieldKey.COMPOSER, it.composer)
                if (!it.discNumber.isNullOrBlank()) tag.setField(FieldKey.DISC_NO, it.discNumber)
                if (!it.comment.isNullOrBlank()) tag.setField(FieldKey.COMMENT, it.comment)
            }

            artFile?.let {
                try {
                    val artwork = AndroidArtwork.createArtworkFromFile(it)
                    // 3 = "Cover (front)" según el estándar ID3v2 APIC (frame de imagen adjunta)
                    artwork.pictureType = 3
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                } catch (e: Exception) {
                    lastSaveError = e.message
                }
            }

            audioFile.commit()

            contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            }

            tempFile.delete()
            return true
        } catch (e: Exception) {
            lastSaveError = e.message ?: e.javaClass.simpleName
            return false
        }
    }

    private fun scanFiles(uris: List<Uri>) {
        val realPaths = uris.mapNotNull { getRealPathFromDocumentUri(it) }

        // Forzamos el mtime del archivo real en disco: en algunos dispositivos,
        // una escritura vía SAF con ParcelFileDescriptor no siempre lo actualiza
        // de forma confiable a través de la capa FUSE. Sin un mtime nuevo,
        // MediaStore (cualquier app que lea de ahí) sigue
        // sirviendo la miniatura cacheada vieja.
        realPaths.forEach { path ->
            try {
                File(path).setLastModified(System.currentTimeMillis())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (realPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                this,
                realPaths.toTypedArray(),
                arrayOf("audio/mpeg")
            ) { _, _ ->
                contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
            }
        }

        uris.forEach { uri ->
            try {
                contentResolver.notifyChange(uri, null)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Convierte un URI SAF (content://.../document/primary:Music/...) a una
     * ruta de archivo real en disco, cuando el documento vive en el
     * almacenamiento interno principal. Devuelve null si no se puede resolver
     * (por ejemplo, si el archivo está en una tarjeta SD externa).
     */
    private fun showEditDialog(index: Int, item: BatchSongItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_tag_single, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.etTitle)
        val etTrack = dialogView.findViewById<EditText>(R.id.etTrack)
        val etArtist = dialogView.findViewById<EditText>(R.id.etArtist)

        val currentTags = item.pendingTags ?: TagFields()
        etTitle.setText(currentTags.title ?: "")
        etTrack.setText(currentTags.track ?: "")
        etArtist.setText(currentTags.artist ?: "")

        AlertDialog.Builder(this)
            .setTitle("Edit Individual Track")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val newTags = currentTags.copy(
                    title = etTitle.text.toString(),
                    track = etTrack.text.toString(),
                    artist = etArtist.text.toString()
                )
                viewModel.updateSingleItemTags(index, newTags)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getRealPathFromDocumentUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            if (split.size >= 2 && split[0].equals("primary", ignoreCase = true)) {
                "${Environment.getExternalStorageDirectory()}/${split[1]}"
            } else {
                null
            }
        } catch (_: Exception) {
            null
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
                if (item.isPlaceholder || item.document == null) return@forEach

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
                adapter.updateItems(viewModel.songs.value ?: emptyList())
            }
        }
    }
}