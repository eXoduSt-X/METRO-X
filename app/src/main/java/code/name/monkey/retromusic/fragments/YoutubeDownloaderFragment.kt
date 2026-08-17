package code.name.monkey.retromusic.fragments

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentYoutubeDownloaderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.androidx.viewmodel.ext.android.viewModel

class YoutubeDownloaderFragment : Fragment(R.layout.fragment_youtube_downloader) {

    private val viewModel: YoutubeDownloaderViewModel by viewModel()
    private var _binding: FragmentYoutubeDownloaderBinding? = null
    private val binding get() = _binding!!

    private var isMp3Request = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentYoutubeDownloaderBinding.bind(view)

        binding.statusText.movementMethod = ScrollingMovementMethod()

        binding.urlInputLayout.setStartIconOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if ((clip != null) && (clip.itemCount > 0)) {
                val text = clip.getItemAt(0).text
                binding.urlEditText.setText(text)
            }
        }

        binding.downloadMp3Button.setOnClickListener {
            val url = binding.urlEditText.text.toString()
            if (url.isNotBlank()) {
                isMp3Request = true
                viewModel.fetchVideoInfo(url)
            }
        }

        binding.downloadVideoButton.setOnClickListener {
            val url = binding.urlEditText.text.toString()
            if (url.isNotBlank()) {
                isMp3Request = false
                viewModel.fetchVideoInfo(url)
            }
        }

        viewModel.videoInfo.observe(viewLifecycleOwner) { info ->
            info?.let {
                if (isMp3Request) {
                    viewModel.download(it.webpageUrl ?: it.url!!, it.title ?: "Audio", null, false)
                    isMp3Request = false
                } else {
                    showQualityDialog(it)
                }
            }
        }

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                binding.progressBar.setProgress(progress, true)
            } else {
                binding.progressBar.progress = progress
            }
        }

        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.statusText.text = status
        }

        viewModel.isDownloading.observe(viewLifecycleOwner) { isDownloading ->
            binding.progressBar.isVisible = isDownloading
            binding.downloadMp3Button.isEnabled = !isDownloading
            binding.downloadVideoButton.isEnabled = !isDownloading
            binding.urlEditText.isEnabled = !isDownloading
        }
    }

    private fun showQualityDialog(info: com.yausername.youtubedl_android.mapper.VideoInfo) {
        val formats = info.formats?.filter {
            // Incluimos todos los que tengan video (vcodec != "none"), incluso si no tienen audio
            // YouTube suele separar el video de alta calidad del audio
            it.vcodec != "none" && it.height > 0
        }?.distinctBy { it.height } // Evitamos duplicados de la misma resolución
         ?.sortedByDescending { it.height } ?: emptyList()

        if (formats.isEmpty()) {
            viewModel.download(info.webpageUrl ?: info.url!!, info.title ?: "Video", null, true)
            return
        }

        val qualityOptions = formats.map {
            val note = it.formatNote ?: ""
            val ext = it.ext ?: ""
            "${it.height}p ($ext) $note".trim()
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_quality)
            .setItems(qualityOptions) { _, which ->
                val selectedFormat = formats[which]
                viewModel.download(info.webpageUrl ?: info.url!!, info.title ?: "Video", selectedFormat.formatId, true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
