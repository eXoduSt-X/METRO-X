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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentYoutubeDownloaderBinding.bind(view)

        binding.statusText.movementMethod = ScrollingMovementMethod()

        binding.urlInputLayout.setEndIconOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if ((clip != null) && (clip.itemCount > 0)) {
                val text = clip.getItemAt(0).text
                binding.urlEditText.setText(text)
            }
        }

        binding.downloadMp3Button.setOnClickListener {
            val url = binding.urlEditText.text.toString()
            viewModel.download(url, formatId = null, isVideo = false)
        }

        binding.downloadVideoButton.setOnClickListener {
            val url = binding.urlEditText.text.toString()
            viewModel.fetchVideoInfo(url)
        }

        viewModel.videoInfo.observe(viewLifecycleOwner) { info ->
            info?.let {
                showQualityDialog(it)
            }
        }

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            binding.progressBar.progress = progress
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
            // Filtrar formatos que tengan video y una resolución decente
            it.ext == "mp4" && it.vcodec != "none"
        }?.sortedByDescending { it.height } ?: emptyList()

        if (formats.isEmpty()) {
            viewModel.download(info.url!!, null, true)
            return
        }

        val qualityOptions = formats.map { 
            "${it.height}p (${it.ext}) - ${it.formatNote ?: ""}" 
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_quality)
            .setItems(qualityOptions) { _, which ->
                val selectedFormat = formats[which]
                viewModel.download(info.webpageUrl ?: info.url!!, selectedFormat.formatId, true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
