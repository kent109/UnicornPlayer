package com.unicorn.player.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.MainActivity
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
 * - 写操作主动刷新：
 *   · 本地增/删/改播放列表 → 操作返回后立即 refreshPlaylists()
 *   · 从歌单详情页（PlaylistSongsActivity）返回 → ActivityResult 回调中 refreshPlaylists()
 */
class PlaylistFragment : Fragment(), PlaylistAdapter.OnPlaylistClickListener {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlaylistViewModel
    private lateinit var adapter: PlaylistAdapter

    private var itemTouchHelper: ItemTouchHelper? = null

    /**
     * 启动歌单详情页的 launcher；返回 RESULT_OK 时表示歌曲有改动，需刷新列表
     */
    private lateinit var playlistSongsLauncher: ActivityResultLauncher<Intent>

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
        setupPlaylistSongsLauncher()
        setupViewModel()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        observeData()
    }

    override fun onStart() {
        super.onStart()
        // 首次可见时触发懒加载；切回 tab 时 hasLoadedOnce 守卫会阻止重查
        viewModel.loadPlaylists()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    /**
     * 注册歌单详情页返回回调：
     * 仅当歌曲列表有改动（RESULT_OK）时才主动刷新，否则保持懒加载。
     */
    private fun setupPlaylistSongsLauncher() {
        playlistSongsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // 歌单内歌曲有改动 → 主动刷新列表（更新歌曲数量、更新时间）
                viewModel.refreshPlaylists()
            }
        }
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
    /**
     * 侧滑回调，类级字段以便 [getSwipeDirs] 查询当前已展开项。
     *
     * 方向策略：
     * - 没有展开项时，只允许 LEFT（打开操作区）；
     * - 有展开项时，只允许 RIGHT（关闭展开项）。
     *
     * 配合 [setViewPagerSwipe]：拖拽过程中禁用 ViewPager2 切页，
     * 手势结束（onSwiped / clearView）后恢复。这样用户不会因为
     * 横向拖拽 playlist item 而误切主界面的标签页。
     */
    private val swipeCallback = object : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun getSwipeDirs(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return 0
            // 已展开的项只允许反方向（RIGHT）关闭，避免继续向左时误触别的操作
            return if (adapter.isSwiped(pos)) {
                ItemTouchHelper.RIGHT
            } else {
                ItemTouchHelper.LEFT
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            if (adapter.isSwiped(pos)) {
                adapter.resetSwipedItem()
            } else {
                adapter.openSwipe(pos)
            }
            // 侧滑手势结束（已 open/reset 到位），恢复主界面 ViewPager2 切页
            setViewPagerSwipe(true)
        }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
            // 较大阈值，避免轻扫即误触
            return 0.35f
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            // 手指仍在拖拽 item（横向滑动）的过程中，禁用 ViewPager2 切页，
            // 避免与主界面分页手势冲突。
            if (isCurrentlyActive && dX != 0f) {
                setViewPagerSwipe(false)
            }
            super.onChildDraw(
                c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive
            )
        }

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ) {
            // 手指离开、ItemTouchHelper 即将进入回弹动画；
            // onSwiped 是否触发都在此处恢复 ViewPager2，确保不遗留禁用态。
            setViewPagerSwipe(true)
            super.clearView(recyclerView, viewHolder)
        }
    }

    private fun attachSwipeHelper() {
        itemTouchHelper = ItemTouchHelper(swipeCallback).also {
            it.attachToRecyclerView(binding.recyclerView)
        }
    }

    /**
     * 切换主界面 ViewPager2 的切页滑动开关。
     * 在歌单 tag 之外（未 attach 时）调用是安全的。
     */
    private fun setViewPagerSwipe(enabled: Boolean) {
        (activity as? MainActivity)?.setViewPagerSwipeEnabled(enabled)
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
            // 编辑 / 删除 / 新建 / 刷新后，重置 swiped 状态，
            // 防止 DiffUtil 让新滑入该位置的 item 继承旧平移出现"关不上"。
            adapter.forceResetSwipeState()
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
                // ViewModel 内部会在写库完成后自动调用 refreshPlaylistsInternal()，这里无需手动刷新
                if (editId != -1L) {
                    viewModel.renamePlaylist(editId, name)
                } else {
                    viewModel.createPlaylist(name)
                }
            }
        ).show()
    }

    // ==================== OnPlaylistClickListener ====================

    override fun onPlaylistClick(
        playlist: PlaylistViewModel.PlaylistInfo,
        position: Int
    ) {
        // 使用 launcher 启动，以便在歌曲改动后（RESULT_OK）触发刷新
        playlistSongsLauncher.launch(
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
                // ViewModel 内部会在写库完成后自动调用 refreshPlaylistsInternal()
                viewModel.deletePlaylist(playlist.id)
            }
            .show()
    }

    override fun onSwipedOpened(position: Int) {
        // 占位：如需在此处理滑动打开后的日志或统计可在此扩展
    }
}
