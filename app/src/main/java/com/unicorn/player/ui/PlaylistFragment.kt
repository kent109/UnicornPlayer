package com.unicorn.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.PlaylistSongsActivity
import com.unicorn.player.adapter.PlaylistAdapter
import com.unicorn.player.databinding.FragmentPlaylistBinding
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.viewmodel.PlaylistViewModel
import com.unicorn.player.viewmodel.PlaylistViewModelFactory

/**
 * 歌单标签页
 *
 * - 懒加载：首次切到该 tab 才查 DB（PlaylistViewModel.hasLoadedOnce 守卫），后续切回不重查
 * - 下拉刷新：强制重查
 * - 左滑 item 露出「编辑 / 删除」；右下角 FAB 新建歌单
 */
class PlaylistFragment : Fragment(), PlaylistAdapter.OnPlaylistClickListener {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlaylistViewModel
    private lateinit var adapter: PlaylistAdapter

    private var itemTouchHelper: ItemTouchHelper? = null

    companion object {
        fun newInstance() = PlaylistFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewModel()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        observeData()
    }

    override fun onStart() {
        super.onStart()
        // 首次可见时触发懒加载
        viewModel.loadPlaylists()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    private fun setupViewModel() {
        val repository = MusicRepository(requireContext())
        val factory = PlaylistViewModelFactory(repository, requireActivity().application)
        viewModel = ViewModelProvider(this, factory)[PlaylistViewModel::class.java]
    }

    private fun setupRecyclerView() {
        adapter = PlaylistAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PlaylistFragment.adapter
        }
        attachSwipeHelper()
    }

    /**
     * 附加 ItemTouchHelper：左滑露出操作按钮
     * - onSwiped 触发 adapter.openSwipe 让卡片保持滑开
     * - 已滑开项再次滑动时复位
     */
    private fun attachSwipeHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                if (adapter.isSwiped(pos)) {
                    adapter.resetSwipedItem()
                } else {
                    adapter.openSwipe(pos)
                }
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                // 较大阈值，避免轻扫即删除
                return 0.35f
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.recyclerView)
        }
    }

    private fun setupSwipeRefresh() {
        binding.smartRefreshLayout.apply {
            setOnRefreshListener {
                viewModel.refreshPlaylists()
            }
        }
    }

    private fun observeData() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            adapter.submitList(playlists)
            binding.smartRefreshLayout.finishRefresh(200)

            if (playlists.isEmpty()) {
                binding.ivNoData.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.ivNoData.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            // 首次加载通过刷新控件提示；后续手动刷新已通过 setOnRefreshListener 控制
            if (!isLoading) {
                binding.smartRefreshLayout.finishRefresh()
            }
        }
    }

    private fun setupFab() {
        binding.fabAddPlaylist.setOnClickListener {
            showNewPlaylistDialog()
        }
    }

    private fun showNewPlaylistDialog(initialName: String = "", editId: Long = -1L) {
        NewPlaylistDialog(
            context = requireContext(),
            initialName = initialName,
            onConfirm = { name ->
                if (editId != -1L) {
                    viewModel.renamePlaylist(editId, name)
                } else {
                    viewModel.createPlaylist(name)
                }
                // 写操作返回后立即刷新列表
                viewModel.refreshPlaylists()
            }
        ).show()
    }

    // ==================== OnPlaylistClickListener ====================

    override fun onPlaylistClick(
        playlist: PlaylistViewModel.PlaylistInfo,
        position: Int
    ) {
        startActivity(
            PlaylistSongsActivity.newIntent(requireContext(), playlist.id, playlist.name)
        )
    }

    override fun onEditClicked(
        playlist: PlaylistViewModel.PlaylistInfo,
        position: Int
    ) {
        showNewPlaylistDialog(initialName = playlist.name, editId = playlist.id)
    }

    override fun onDeleteClicked(
        playlist: PlaylistViewModel.PlaylistInfo,
        position: Int
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除歌单")
            .setMessage("确定要删除歌单「${playlist.name}」吗？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewModel.deletePlaylist(playlist.id)
                viewModel.refreshPlaylists()
            }
            .show()
    }

    override fun onSwipedOpened(position: Int) {
        // 占位：如需在此处理滑动打开后的日志或统计可在此扩展
    }
}
