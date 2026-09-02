package com.unicorn.player.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.R
import com.unicorn.player.databinding.SongFragmentMultiChoiceBinding
import com.unicorn.player.model.Song

class SongMultiChoiceFragment : Fragment(), SongMultiChoiceFragmentAdapter.OnCheckChangedListener {

    private var _binding: SongFragmentMultiChoiceBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SongMultiChoiceFragmentAdapter
    private var playlistId: Long = -1L
    private var selectedSongIds = mutableSetOf<Long>()
    private var initList: List<Song> = emptyList()
    private var currentFragmentType: Int = FT_NONE

    interface OnMultiChoiceActionListener {
        fun onDeleteSelected(playlistId: Long, selectedSongIds: Set<Long>)
        fun onAddToPlaylist(selectedSongIds: Set<Long>)
        fun onCancel()
    }

    companion object {
        const val FT_NONE: Int = 0
        const val FT_ARTIST: Int = 111
        const val FT_ALBUM: Int = 222
        const val FT_PLAYLIST: Int = 333
    }

    private var actionListener: OnMultiChoiceActionListener? = null

    private fun selectAll() {
        selectedSongIds.clear()
        for (item in initList) {
            selectedSongIds.add(item.id)
        }
        val adapter = binding.recyclerView.adapter as? SongMultiChoiceFragmentAdapter
        adapter?.notifyDataSetChanged()
    }

    private fun unSelectAll() {
        selectedSongIds.clear()
        val adapter = binding.recyclerView.adapter as? SongMultiChoiceFragmentAdapter
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
        binding.tvSelectedCount.text = getString(R.string.selected_count, selectedSongIds.size)
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
            val args = requireArguments()
            this.currentFragmentType = args.getInt("type")
            this.initList = args.getParcelableArrayList("list") ?: emptyList()
            this.playlistId = args.getLong("playlistId", -1)
        }
        if (context is OnMultiChoiceActionListener) {
            this.actionListener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SongFragmentMultiChoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setViewModelData()
        updateSelectUI(false)
        initButtonLayout()
    }

    private fun setViewModelData() {
        adapter.submitList(initList)
    }

    private fun setupRecyclerView() {
        adapter = SongMultiChoiceFragmentAdapter(selectedSongIds)
        adapter.setOnCheckChangedListener(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SongMultiChoiceFragment.adapter
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
            actionListener?.onDeleteSelected(playlistId, selectedSongIds)
        }

        binding.ivAddToPlaylist.isEnabled = false
        binding.ivAddToPlaylist.setOnClickListener {
            actionListener?.onAddToPlaylist(selectedSongIds)
        }

        when (currentFragmentType) {
            FT_ARTIST, FT_ALBUM -> binding.flDelete.visibility = View.GONE
            FT_PLAYLIST -> binding.flAddToPlaylist.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    override fun onCheckChanged() {
        updateButtonStatus(selectedSongIds.isNotEmpty())
        updateSelectUI(selectedSongIds.size >= initList.size)
    }
}
