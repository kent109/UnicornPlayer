package com.unicorn.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.ArtistSongsActivity
import com.unicorn.player.adapter.ArtistAdapter
import com.unicorn.player.databinding.FragmentArtistBinding
import com.unicorn.player.model.Artist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory

/**
 * 歌手标签页 Fragment，按歌手名分组显示歌曲数量
 */
class ArtistFragment : Fragment(), ArtistAdapter.OnArtistClickListener {

    private var _binding: FragmentArtistBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MusicViewModel
    private lateinit var artistAdapter: ArtistAdapter

    companion object {
        fun newInstance() = ArtistFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArtistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(requireContext()), requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        viewModel.allSongs.observe(viewLifecycleOwner) { songs ->
            submitArtists(songs)
        }
    }

    private fun setupRecyclerView() {
        artistAdapter = ArtistAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = artistAdapter
        }
        setupScrollListener()
    }

    /**
     * 监听滚动，滚到底部时显示歌手数量（参考 SongsFragment）
     */
    private fun setupScrollListener() {
        binding.recyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                val isAtBottom = if (lastVisibleItem == totalItemCount - 1) {
                    val lastItemView = layoutManager.findViewByPosition(lastVisibleItem)
                    lastItemView != null && lastItemView.bottom <= recyclerView.bottom
                } else {
                    false
                }

                binding.tvArtistCount.visibility = if (isAtBottom) View.VISIBLE else View.GONE
            }
        })
    }

    /**
     * 按歌手名分组统计歌曲数量（不区分大小写），按歌手名排序后提交
     */
    private fun submitArtists(songs: List<Song>) {
        // 以 lowerCase 名作为分组键，保留首次出现的原始大小写用于显示
        val displayCase = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        for (song in songs) {
            val key = song.artist.lowercase()
            if (!displayCase.containsKey(key)) {
                displayCase[key] = song.artist
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        val artists = counts.map { (key, count) ->
            Artist(displayCase[key] ?: key, count)
        }.sortedBy { it.name.lowercase() }
        artistAdapter.submitList(artists)
        updateArtistCount(artists.size)
    }

    private fun updateArtistCount(count: Int) {
        binding.tvArtistCount.text = "共 $count 位歌手"
    }

    override fun onArtistClick(artist: Artist, position: Int) {
        startActivity(ArtistSongsActivity.newIntent(requireContext(), artist.name))
    }
}
