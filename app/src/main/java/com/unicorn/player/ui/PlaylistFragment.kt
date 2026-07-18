package com.unicorn.player.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.MainActivity
import com.unicorn.player.PlaylistSongsActivity
import com.unicorn.player.adapter.PlaylistAdapter
import com.unicorn.player.databinding.FragmentPlaylistBinding
import com.unicorn.player.R
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
            // 禁用 item 的 change 动画（notifyItemChanged 触发的交叉淡入淡出）；
            // 否则展开/关闭操作按钮时 item 会闪一下。保留 add/remove/move 动画。
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
                ?.supportsChangeAnimations = false
            // 由 [SwipeRevealListener] 接管手势（点击/长按/左右拖动、松手决策），
            // 不用 ItemTouchHelper，避免 SWIPE_SUCCESS 路径产生 RecoverAnimation 孤儿，
            // 从而避免"首次点击无法关闭"的 bug。
            addOnItemTouchListener(SwipeRevealListener())
        }
    }

    /**
     * 把手势完全从 ItemTouchHelper 搬到 RecyclerView.OnItemTouchListener：
     * - 水平拖动：实时 clamp [PlaylistAdapter.setCardX]；
     * - 松手决策：[-aW*0.4, ∞) 补开到全闭/全开，按当前位置方向翻转到目标；
     * - 拖动过程中调用 setViewPagerSwipe(false) 防止 ViewPager2 切页；
     * - 不复用 ItemTouchHelper 的 SWIPE_SUCCESS，故没有 RecoverAnimation 孤儿。
     */
    private inner class SwipeRevealListener : RecyclerView.OnItemTouchListener {
        private val TOUCH_SLOP = 16f
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var lastDx = 0f
        private var draggedHolder: PlaylistAdapter.PlaylistHolder? = null
        private var startTx = 0f

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    draggedHolder = null; dragging = false; startTx = 0f; lastDx = 0f
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX;
                    val dy = e.y - downY
                    if (!dragging && kotlin.math.abs(dx) > TOUCH_SLOP && kotlin.math.abs(dx) >= kotlin.math.abs(
                            dy
                        )
                    ) {
                        // 找到手指下的 item，确认其 cardContent
                        val child = rv.findChildViewUnder(downX, downY) ?: return false
                        val vh =
                            rv.findContainingViewHolder(child) as? PlaylistAdapter.PlaylistHolder
                                ?: return false
                        val pos = vh.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return false
                        // 已展开时只允许右滑关闭，未开时只允许左滑打开
                        if (adapter.isSwiped(pos) && dx < 0) return false
                        if (!adapter.isSwiped(pos) && dx > 0) return false
                        draggedHolder = vh
                        startTx = vh.cardContent().translationX
                        dragging = true
                        setViewPagerSwipe(false)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            val vh = draggedHolder ?: return
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    lastDx = dx
                    val aW = adapter.actionWidthPx
                    val target = (startTx + dx).coerceIn(-aW.toFloat(), 0f)
                    vh.setCardX(target)
                    setViewPagerSwipe(false)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val pos = vh.bindingAdapterPosition
                        val aW = adapter.actionWidthPx
                        if (pos != RecyclerView.NO_POSITION && aW > 0) {
                            val openThreshold = -aW * 0.4f
                            val curX = vh.cardContent().translationX
                            val targetOpen = if (adapter.isSwiped(pos)) {
                                // 已开，做 close 判定
                                !(lastDx > aW * 0.2f || curX > -aW * 0.6f)
                            } else {
                                // 未开，做 open 判定
                                lastDx < -aW * 0.2f || curX < openThreshold
                            }
                            if (targetOpen && !adapter.isSwiped(pos)) {
                                vh.setCardX(-aW.toFloat())
                                adapter.openSwipe(pos)
                            } else if (!targetOpen && adapter.isSwiped(pos)) {
                                vh.setCardX(0f)
                                adapter.resetSwipedItem()
                            } else {
                                vh.animateCardTo(
                                    if (adapter.isSwiped(pos)) -aW.toFloat() else 0f,
                                    120L
                                )
                            }
                        }
                    }
                    dragging = false
                    draggedHolder = null
                    setViewPagerSwipe(true)
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
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
        val isRename = editId != -1L
        NewPlaylistDialog(
            context = requireContext(),
            initialName = initialName,
            // 在 [onDuplicate] 里关闭对话框，避免先关闭再弹 Toast 的歧义
            onConfirm = { name ->
                // 统一在 ViewModel 内部 [trim] + 校验同名；同名时调用 [onDuplicate] 弹 Toast，
                // 写库成功后 ViewModel 内部会自动 refreshPlaylistsInternal()，这里无需手动刷新。
                val duplicateHandler: () -> Unit = {
                    // 关闭输入对话框，避免其遮挡 Toast 并提示用户可重新输入
                    if (view != null) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.playlist_name_exists, name.trim()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                if (isRename) {
                    viewModel.renamePlaylist(editId, name, duplicateHandler)
                } else {
                    viewModel.createPlaylist(name, duplicateHandler)
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
