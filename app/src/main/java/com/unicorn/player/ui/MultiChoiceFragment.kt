package com.unicorn.player.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.R
import com.unicorn.player.databinding.FragmentMultiChoiceBinding
import com.unicorn.player.model.Album
import com.unicorn.player.model.Artist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.util.PinyinUtil
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import com.unicorn.player.viewmodel.PlaylistViewModel
import com.unicorn.player.viewmodel.PlaylistViewModel.PlaylistInfo
import com.unicorn.player.viewmodel.PlaylistViewModelFactory

class MultiChoiceFragment : Fragment(), MultiChoiceFragmentAdapter.OnCheckChangedListener {

    private var _binding: FragmentMultiChoiceBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MusicViewModel
    private lateinit var playlistViewModel: PlaylistViewModel
    private lateinit var adapter: MultiChoiceFragmentAdapter
    private var selectedIds = mutableSetOf<Long>()
    private var selectedSongIds = mutableSetOf<Long>()
    private var initList: List<Any> = emptyList()
    private var currentFragmentType: Int = 0

    interface OnMultiChoiceActionListener {
        fun onDeleteSelected(selectedSongIds: Set<Long>)
        fun onAddToPlaylist(selectedSongIds: Set<Long>)
        fun onCancel()
    }

    private var actionListener: OnMultiChoiceActionListener? = null

    private fun selectAll() {
        selectedIds.clear()
        selectedSongIds.clear()
        for (item in initList) {
            when (item) {
                is Song -> {
                    selectedIds.add(item.id)
                    selectedSongIds.add(item.id)
                }

                is Album -> {
                    selectedIds.add(item.name.hashCode().toLong())
                    selectedSongIds.addAll(item.songList.mapTo(HashSet()) { it.id })
                }

                is PlaylistInfo -> {
                    selectedIds.add(item.id)
                    selectedSongIds.add(item.id)
                }

                is Artist -> {
                    selectedIds.add(item.name.hashCode().toLong())
                    selectedSongIds.addAll(item.songList.mapTo(HashSet()) { it.id })
                }
            }
        }
        val adapter = binding.recyclerView.adapter as? MultiChoiceFragmentAdapter
        adapter?.notifyDataSetChanged()
    }

    private fun unSelectAll() {
        selectedIds.clear()
        selectedSongIds.clear()
        val adapter = binding.recyclerView.adapter as? MultiChoiceFragmentAdapter
        adapter?.notifyDataSetChanged()
    }

    private fun updateSelectUI(allSelected: Boolean) {
        if (allSelected) {
            binding.tvSelectAll.tag = '1'
            binding.tvSelectAll.text = "取消全选"
        } else {
            binding.tvSelectAll.tag = null
            binding.tvSelectAll.text = "全选"
        }
        binding.tvSelectedCount.text = getString(R.string.selected_count, selectedIds.size)
    }

    private fun updateButtonStatus(enabled: Boolean) {
        binding.ivDelete.isEnabled = enabled
        binding.ivDelete.alpha = if (enabled) 1.0f else 0.5f
        binding.ivAddToPlaylist.isEnabled = enabled
        binding.ivAddToPlaylist.alpha = if (enabled) 1.0f else 0.5f
    }

    /**
     * 双击搜索框时滚动到顶部（由宿主 Activity 调用）
     */
    fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.itemCount > 0) {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }

    fun getClickView(): View {
        return binding.topButtonsLayout
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        arguments?.let {
            this.currentFragmentType = requireArguments().getInt("type")
        }
        if (context is OnMultiChoiceActionListener) {
            this.actionListener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMultiChoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
        setViewModelData()
        initList = getCurrentList()
        updateSelectUI(false)
        initButtonLayout()
    }

    private fun setupViewModel() {
        val repository = MusicRepository(requireContext())
        val factory = MusicViewModelFactory(repository, requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        val playlistFactory = PlaylistViewModelFactory(repository, requireActivity().application)
        playlistViewModel =
            ViewModelProvider(requireActivity(), playlistFactory)[PlaylistViewModel::class.java]
    }

    private fun setViewModelData() {
        when (currentFragmentType) {
            0 -> adapter.submitList(viewModel.allSongs.value)
            1 -> adapter.submitList(getArtistList())
            2 -> adapter.submitList(viewModel.albums.value)
            3 -> {
                adapter.submitList(playlistViewModel.playlists.value)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = MultiChoiceFragmentAdapter(selectedIds, selectedSongIds, currentFragmentType)
        adapter.setOnCheckChangedListener(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MultiChoiceFragment.adapter
        }
    }

    private fun initButtonLayout() {
        binding.tvCancel.setOnClickListener {
            actionListener?.onCancel()
        }

        binding.tvSelectAll.setOnClickListener {
            val tag = binding.tvSelectAll.tag
            if (tag == null) {
                selectAll()
                updateSelectUI(true)
                updateButtonStatus(true)
            } else {
                unSelectAll()
                binding.tvSelectAll.tag = null
                updateSelectUI(false)
                updateButtonStatus(false)
            }
        }

        binding.ivDelete.isEnabled = false
        binding.ivDelete.setOnClickListener {
            actionListener?.onDeleteSelected(selectedSongIds)
        }

        binding.ivAddToPlaylist.isEnabled = false
        binding.ivAddToPlaylist.setOnClickListener {
            actionListener?.onAddToPlaylist(selectedSongIds)
        }

        when (currentFragmentType) {
            1, 2 -> binding.flDelete.visibility = View.GONE
            3 -> binding.flAddToPlaylist.visibility = View.GONE
        }
    }

    private fun getArtistList(): List<Artist> {
        val songs = viewModel.allSongs.value ?: emptyList()
        val artistMap = mutableMapOf<String, Artist>()
        for (song in songs) {
            val key = song.artist.lowercase()
            // 获取或创建 Artist，并添加歌曲
            val artist = artistMap.getOrPut(key) {
                // 只有在 map 中不存在该 key 时才会执行这里
                Artist(song.artist, PinyinUtil.getPinyinString(song.artist), 0, mutableListOf())
            }
            // 直接将当前歌曲加入列表
            artist.songList.add(song)
            // 增加计数
            artist.songCount++
        }
        return artistMap.values.toList().sortedBy { it.pinyinName }
    }

    private fun getCurrentList(): List<Any> {
        return when (currentFragmentType) {
            0 -> viewModel.allSongs.value ?: emptyList()
            1 -> getArtistList()
            2 -> viewModel.albums.value ?: emptyList()
            3 -> playlistViewModel.playlists.value ?: emptyList()
            else -> emptyList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    override fun onCheckChanged() {
        updateButtonStatus(selectedIds.isNotEmpty())
        updateSelectUI(selectedIds.size >= initList.size)
    }
}
