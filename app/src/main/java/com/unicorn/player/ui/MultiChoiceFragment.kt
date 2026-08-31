package com.unicorn.player.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.databinding.FragmentMultiChoiceBinding
import com.unicorn.player.model.Album
import com.unicorn.player.model.Artist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
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
    private var currentFragmentType: Int = 0

    interface OnMultiChoiceActionListener {
        fun onSelectionChanged(selectedIds: Set<Long>)
        fun onDeleteSelected()
        fun onAddToPlaylist(selectedIds: Set<Long>)
        fun onCancel()
    }

    private var actionListener: OnMultiChoiceActionListener? = null

    fun setActionListener(listener: OnMultiChoiceActionListener) {
        this.actionListener = listener
    }

    private fun selectAll() {
        val currentList = getCurrentList()
        for (item in currentList) {
            when (item) {
                is Song -> selectedIds.add(item.id)
                is Album -> selectedIds.add(item.name.hashCode().toLong())
                is PlaylistInfo -> selectedIds.add(item.id)
                is Artist -> selectedIds.add(item.name.hashCode().toLong())
            }
        }
        val adapter = binding.recyclerView.adapter as? MultiChoiceFragmentAdapter
        adapter?.notifyDataSetChanged()
    }

    private fun unSelectAll() {
        selectedIds.clear()
        val adapter = binding.recyclerView.adapter as? MultiChoiceFragmentAdapter
        adapter?.notifyDataSetChanged()
    }

    private fun updateSelectAllUI(allSelected: Boolean) {
        if (allSelected) {
            binding.tvSelectAll.tag = '1'
            binding.tvSelectAll.text = "取消全选"
        } else {
            binding.tvSelectAll.tag = null
            binding.tvSelectAll.text = "全选"
        }
    }

    private fun updateButtonStatus(enabled: Boolean) {
        binding.ivDelete.isEnabled = enabled
        binding.ivDelete.alpha = if (enabled) 1.0f else 0.5f
        binding.ivAddToPlaylist.isEnabled = enabled
        binding.ivAddToPlaylist.alpha = if (enabled) 1.0f else 0.5f
    }

    fun clearSelection() {
        selectedIds.clear()
    }

    fun getSelectedIds(): Set<Long> {
        return selectedIds
    }

    fun hasSelection(): Boolean {
        return selectedIds.isNotEmpty()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        arguments?.let {
            this.currentFragmentType = requireArguments().getInt("type")
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
        adapter = MultiChoiceFragmentAdapter(selectedIds, currentFragmentType)
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
                updateSelectAllUI(true)
                updateButtonStatus(true)
            } else {
                unSelectAll()
                binding.tvSelectAll.tag = null
                updateSelectAllUI(false)
                updateButtonStatus(false)
            }
            actionListener?.onSelectionChanged(selectedIds)
        }

        binding.ivDelete.isEnabled = false
        binding.ivDelete.setOnClickListener {
            actionListener?.onDeleteSelected()
        }

        binding.ivAddToPlaylist.isEnabled = false
        binding.ivAddToPlaylist.setOnClickListener {
            actionListener?.onAddToPlaylist(getSelectedIds())
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
            val artist = artistMap.getOrPut(song.artist) { Artist(song.artist, 0) }
            artistMap[song.artist] = Artist(artist.name, artist.songCount + 1)
        }
        return artistMap.values.toList().sortedBy { it.name.lowercase() }
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

    override fun onCheckChanged(selectedIds: Set<Long>) {
        updateButtonStatus(selectedIds.isNotEmpty())
        val currentList = getCurrentList()
        updateSelectAllUI(selectedIds.size >= currentList.size)
        actionListener?.onSelectionChanged(selectedIds)
    }
}
