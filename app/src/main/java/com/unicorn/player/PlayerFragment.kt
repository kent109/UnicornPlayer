package com.unicorn.player

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.unicorn.player.adapter.PlayQueueAdapter
import com.unicorn.player.databinding.DialogPlayQueueBinding
import com.unicorn.player.databinding.DialogSortOptionsBinding
import com.unicorn.player.databinding.FragmentPlayerBinding
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.ui.EmptyPlaylistActionDialog
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.ui.SelectPlaylistDialog
import com.unicorn.player.util.AudioTagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private var currentSong: Song? = null

    // 当前打开的播放队列适配器引用，用于生命周期回调暂停/恢复旋转动画
    private var queueAdapter: com.unicorn.player.adapter.PlayQueueAdapter? = null

    // 弹窗期间观察播放状态/当前歌曲的观察者，dismiss 时移除
    private var queueIsPlayingObserver: androidx.lifecycle.Observer<Boolean>? = null
    private var queueCurrentSongObserver: androidx.lifecycle.Observer<Song?>? = null

    // 歌曲信息弹窗帮助类（ivMore 点击后弹出），从 PlayerActivity 迁移
    private lateinit var songInfoHelper: SongInfoHelper

    // 写入权限请求（编辑音频标签时可能需要）
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            songInfoHelper.retryPendingWrite()
        }
    }

    companion object {
        private const val ARG_SONG = "song"

        // 排序设置（与 MusicViewModel 使用的 SharedPreferences 一致）
        private const val SORT_PREFS_NAME = "sort_mode_prefs"
        private const val SORT_MODE_KEY = "sort_mode"

        fun newInstance(song: Song): PlayerFragment {
            return PlayerFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_SONG, song)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        binding.root.tag = this
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            currentSong = it.getParcelable(ARG_SONG)
        }
        Log.d("PlayerFragment", "onViewCreated: currentSong = ${currentSong?.title}")

        // 歌曲信息弹窗帮助类（从 PlayerActivity 迁移）
        songInfoHelper = SongInfoHelper(requireContext())
        songInfoHelper.writePermissionCallback = object : AudioTagEditor.WritePermissionCallback {
            override fun onRequestWritePermission(
                intentSender: android.content.IntentSender,
                requestCode: Int
            ) {
                writePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        }
        songInfoHelper.onAddToPlaylistListener = object : SongInfoHelper.OnAddToPlaylistListener {
            override fun onAddToPlaylist(song: Song) {
                showSelectPlaylistDialog(song)
            }
        }

        setupClickListeners()
        observeServiceState()
        // 立即显示当前Fragment的歌曲信息
        currentSong?.let {
            updateSongInfo(it)
            updatePlayPauseState(false)
        }
    }

    private fun setupClickListeners() {
        val service = getService() ?: return

        binding.playPauseButton.setOnClickListener {
            val isPlaying = service.isPlaying.value ?: false
            if (isPlaying) {
                service.pause()
            } else {
                service.requestAudioFocusAndPlay()
            }
            service.updateNotification()
        }

        binding.nextButton.setOnClickListener {
            service.requestAudioFocusAndPlayNext()
            service.updateNotification()
            (activity as? PlayerActivity)?.applyPageTransformer()
        }

        binding.previousButton.setOnClickListener {
            service.requestAudioFocusAndPlayPrevious()
            service.updateNotification()
            (activity as? PlayerActivity)?.applyPageTransformer()
        }

        // 循环模式切换：与 MainActivity 的 ivLoop 行为一致。
        // 图标刷新由 playModeLiveData 观察者统一驱动（MainActivity 同样观察该 LiveData），
        // 因此两个界面的图标始终同步
        binding.ivLoop.setOnClickListener {
            val currentMode = service.getPlayMode()
            val nextMode = when (currentMode) {
                MusicService.PlayMode.ALL_LOOP -> MusicService.PlayMode.SINGLE_LOOP
                MusicService.PlayMode.SINGLE_LOOP -> MusicService.PlayMode.RANDOM
                MusicService.PlayMode.RANDOM -> MusicService.PlayMode.SEQUENCE
                MusicService.PlayMode.SEQUENCE -> MusicService.PlayMode.ALL_LOOP
            }
            service.setPlayMode(nextMode)
            val modeText = when (nextMode) {
                MusicService.PlayMode.ALL_LOOP -> "全部循环"
                MusicService.PlayMode.SINGLE_LOOP -> "单曲循环"
                MusicService.PlayMode.RANDOM -> "随机播放"
                MusicService.PlayMode.SEQUENCE -> "顺序播放"
            }
            Toast.makeText(requireContext(), modeText, Toast.LENGTH_SHORT).show()
        }

        // 播放队列按钮：弹出 BottomSheetDialog 展示 MusicService 当前歌曲列表
        binding.ivSongList.setOnClickListener {
            showPlayQueueDialog()
        }

        // 排序按钮：弹出 BottomSheetDialog 选择排序方式（与 MainActivity 排序设置同步）
        binding.ivSort.setOnClickListener {
            showSortDialog()
        }

        // 更多按钮：弹出该页面歌曲的信息弹窗（与 SongAdapter 更多按钮一致，不显示删除）
        binding.ivMore.setOnClickListener {
            val song = currentSong ?: getService()?.currentSong?.value
            song?.let {
                songInfoHelper.showSongInfoDialog(
                    it,
                    showDeleteOption = false,
                    showAddToPlaylist = true
                )
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 只在用户拖动且当前Fragment的歌曲正在播放时更新播放进度
                if (fromUser) {
                    val service = getService() ?: return
                    val playingSong = service.currentSong.value
                    val isCurrentSongPlaying = currentSong?.id == playingSong?.id

                    if (isCurrentSongPlaying) {
                        service.seekTo(progress)
                        // 通知 PlayerActivity：抑制进度观察者（异步 seek 期间
                        // 可能读到旧位置）并取消滚动动画；拖动中歌词保持原位
                        (activity as? PlayerActivity)?.onUserSeeking()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 松手：歌词按库自身的 400ms 动画平滑滚动到目标位置
                val progress = seekBar?.progress ?: return
                (activity as? PlayerActivity)?.onUserSeekEnd(progress)
            }
        })
    }

    private fun observeServiceState() {
        val service = getService() ?: return

        // 观察当前播放的歌曲变化，只更新播放状态
        service.currentSong.observe(viewLifecycleOwner) { playingSong ->
            // 检查当前Fragment的歌曲是否正在播放
            val isCurrentSongPlaying = currentSong?.id == playingSong?.id
            updatePlayPauseState(isCurrentSongPlaying)
        }

        // 观察播放状态变化
        service.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            val service = getService() ?: return@observe
            val playingSong = service.currentSong.value
            val isCurrentSongPlaying = currentSong?.id == playingSong?.id
            updatePlayPauseState(isCurrentSongPlaying && isPlaying)
        }

        // 观察播放位置变化（只当前歌曲播放时）
        service.currentPosition.observe(viewLifecycleOwner) { position ->
            updateSeekBar(position)
        }

        // 观察播放模式变化，刷新 ivLoop 图标（与 MainActivity 的 ivLoop 图标保持同步）
        service.playModeLiveData.observe(viewLifecycleOwner) { mode ->
            updateLoopIcon(mode)
        }
    }

    /**
     * 根据播放模式更新 ivLoop 图标（播放页使用 _s 小尺寸版本图标）
     */
    private fun updateLoopIcon(mode: MusicService.PlayMode) {
        val iconRes = when (mode) {
            MusicService.PlayMode.ALL_LOOP -> R.drawable.ic_loop_all_s
            MusicService.PlayMode.SINGLE_LOOP -> R.drawable.ic_loop_one_s
            MusicService.PlayMode.RANDOM -> R.drawable.ic_random_s
            MusicService.PlayMode.SEQUENCE -> R.drawable.ic_sequence_s
        }
        _binding?.ivLoop?.setImageResource(iconRes)
    }

    /**
     * 弹出播放队列 BottomSheetDialog，展示 MusicService 当前歌曲列表。
     * 点击某首歌曲时切换播放并关闭弹窗；当前播放歌曲高亮。
     */
    private fun showPlayQueueDialog() {
        val service = getService() ?: return
        // 必须拷贝一份：getSongList() 返回 Service 内部可变列表本身，
        // 若直接把它传回 setSongList()，内部 clear() 会连带清空传入的列表（同一引用）
        val songs = service.getSongList().toList()
        if (songs.isEmpty()) {
            Toast.makeText(requireContext(), "当前播放队列为空", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheetDialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val dialogBinding = DialogPlayQueueBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.tvQueueTitle.text = "播放队列 (${songs.size} 首)"
        bottomSheetDialog.setContentView(dialogBinding.root)

        val adapter = PlayQueueAdapter { _, position ->
            val clickedSong = songs[position]
            // 点击的就是当前歌曲：播放中不做处理；暂停中则恢复播放（与 MainActivity 一致）
            if (service.currentSong.value?.id == clickedSong.id) {
                if (service.isPlaying.value != true) {
                    service.requestAudioFocusAndPlay()
                    service.updateNotification()
                }
                bottomSheetDialog.dismiss()
                return@PlayQueueAdapter
            }
            // 切换到指定歌曲播放（播放来源不变，仅重设列表起点）
            service.setSongList(songs, position)
            service.requestAudioFocusAndPlayCurrentSong()
            service.updateNotification()
            bottomSheetDialog.dismiss()
        }
        adapter.currentPlayingSong = service.currentSong.value
        adapter.isPlaying = service.isPlaying.value == true
        adapter.submitList(songs)
        queueAdapter = adapter

        dialogBinding.rvPlayQueue.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvPlayQueue.adapter = adapter

        // 弹窗显示期间观察播放状态：通知栏/耳机按键暂停时动画同步暂停或恢复
        val isPlayingObserver = androidx.lifecycle.Observer<Boolean> { playing ->
            adapter.isPlaying = playing
            if (playing) {
                adapter.resumeCurrentSongAnimation()
            } else {
                adapter.pauseCurrentSongAnimation()
            }
        }
        // 观察当前歌曲变化（弹窗未关闭时由其他入口切歌），同步高亮与动画位置
        val currentSongObserver = androidx.lifecycle.Observer<Song?> { song ->
            adapter.currentPlayingSong = song
            adapter.notifyDataSetChanged()
        }
        service.isPlaying.observe(viewLifecycleOwner, isPlayingObserver)
        service.currentSong.observe(viewLifecycleOwner, currentSongObserver)
        queueIsPlayingObserver = isPlayingObserver
        queueCurrentSongObserver = currentSongObserver

        // 捕获当前 Activity 引用：dismiss 监听是 Handler 异步回调，
        // 触发时 Fragment 可能已 detach，不能再调用 requireActivity()
        val hostActivity = requireActivity()

        // 弹窗动画的暂停/恢复绑定 Activity 生命周期，而非 Fragment 的 onPause：
        // 自动切歌时 PlayerActivity 会同步翻动 ViewPager，持有弹窗的 Fragment 会收到
        // onPause，但弹窗是独立窗口仍可见，此时不应暂停，否则新歌曲的动画永不启动
        val activityLifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                // 整个播放页进入后台：暂停并保留角度
                adapter.isPaused = true
                adapter.pauseCurrentSongAnimation()
            }

            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                // 回到前台：从保存的角度继续
                adapter.isPaused = false
                adapter.resumeCurrentSongAnimation()
            }
        }
        hostActivity.lifecycle.addObserver(activityLifecycleObserver)

        bottomSheetDialog.setOnDismissListener {
            service.isPlaying.removeObserver(isPlayingObserver)
            service.currentSong.removeObserver(currentSongObserver)
            hostActivity.lifecycle.removeObserver(activityLifecycleObserver)
            queueIsPlayingObserver = null
            queueCurrentSongObserver = null
            queueAdapter = null
        }

        // 滚动到当前播放歌曲位置
        val currentIndex = songs.indexOfFirst { it.id == service.currentSong.value?.id }
        if (currentIndex >= 0) {
            dialogBinding.rvPlayQueue.post {
                (dialogBinding.rvPlayQueue.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }

        bottomSheetDialog.show()

        // 设置圆角背景与行为：完全展开、下滑直接消失
        val designBottomSheet =
            bottomSheetDialog.findViewById<android.view.ViewGroup>(
                com.google.android.material.R.id.design_bottom_sheet
            )
        designBottomSheet?.post {
            val behavior = BottomSheetBehavior.from(designBottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.isHideable = true
            behavior.skipCollapsed = true

            val cornerRadius = 32f * designBottomSheet.resources.displayMetrics.density
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(designBottomSheet.context.getColor(R.color.surface))
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius,
                    0f, 0f, 0f, 0f
                )
            }
            designBottomSheet.background = drawable
            designBottomSheet.clipToOutline = true
            designBottomSheet.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
    }

    /**
     * 弹出排序方式 BottomSheetDialog（替代 MainActivity 的 PopupWindow）。
     * 当前排序方式显示主题色文字与对钩
     */
    private fun showSortDialog() {
        val bottomSheetDialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val sortBinding = DialogSortOptionsBinding.inflate(LayoutInflater.from(requireContext()))
        bottomSheetDialog.setContentView(sortBinding.root)

        // 从与 MusicViewModel 相同的 SharedPreferences 读取当前排序方式
        val prefs = requireContext().getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)
        val savedOrdinal = prefs.getInt(SORT_MODE_KEY, 0)
        val currentMode = MusicService.Companion.SortMode.entries
            .getOrElse(savedOrdinal) { MusicService.Companion.SortMode.BY_TITLE }
        val checkColor = ThemeSettingActivity.resolveHighlightColor(requireContext())
        val normalColor = ContextCompat.getColor(requireContext(), R.color.text_primary)

        fun setupSortItem(textView: TextView, isSelected: Boolean) {
            textView.setTextColor(if (isSelected) checkColor else normalColor)
            textView.setCompoundDrawablesWithIntrinsicBounds(
                0, 0, if (isSelected) R.drawable.ic_check else 0, 0
            )
            if (isSelected) {
                textView.compoundDrawables[2]?.setTint(checkColor)
            }
        }

        setupSortItem(sortBinding.tvSortByTime, currentMode == MusicService.Companion.SortMode.BY_TIME)
        setupSortItem(sortBinding.tvSortByTitle, currentMode == MusicService.Companion.SortMode.BY_TITLE)
        setupSortItem(sortBinding.tvSortByArtist, currentMode == MusicService.Companion.SortMode.BY_ARTIST)

        sortBinding.tvSortByTime.setOnClickListener {
            applySortMode(MusicService.Companion.SortMode.BY_TIME)
            bottomSheetDialog.dismiss()
        }
        sortBinding.tvSortByTitle.setOnClickListener {
            applySortMode(MusicService.Companion.SortMode.BY_TITLE)
            bottomSheetDialog.dismiss()
        }
        sortBinding.tvSortByArtist.setOnClickListener {
            applySortMode(MusicService.Companion.SortMode.BY_ARTIST)
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()

        // 圆角背景：与其他 BottomSheetDialog 保持一致
        val designBottomSheet =
            bottomSheetDialog.findViewById<android.view.ViewGroup>(
                com.google.android.material.R.id.design_bottom_sheet
            )
        designBottomSheet?.post {
            val behavior = BottomSheetBehavior.from(designBottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.isHideable = true
            behavior.skipCollapsed = true

            val cornerRadius = 32f * designBottomSheet.resources.displayMetrics.density
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(designBottomSheet.context.getColor(R.color.surface))
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius,
                    0f, 0f, 0f, 0f
                )
            }
            designBottomSheet.background = drawable
            designBottomSheet.clipToOutline = true
            designBottomSheet.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
    }

    /**
     * 应用排序方式：
     * 1. 写入与 MusicViewModel 相同的 SharedPreferences（两边设置同步）；
     * 2. 重排 MusicService 当前播放队列（当前歌曲保持播放，仅位置改变）；
     * 3. 刷新播放页 ViewPager 的页面顺序
     */
    private fun applySortMode(mode: MusicService.Companion.SortMode) {
        val service = getService() ?: return
        requireContext().getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putInt(SORT_MODE_KEY, mode.ordinal)
            }

        val list = service.getSongList()
        val currentId = service.currentSong.value?.id
        val sorted = MusicService.sortSongs(list, mode)
        val newIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        service.setSongList(sorted, newIndex)

        (activity as? PlayerActivity)?.onPlayQueueReordered()
    }

    /**
     * 显示歌单选择弹窗（从 PlayerActivity 迁移，与 MainActivity 中的实现一致）
     */
    private fun showSelectPlaylistDialog(song: Song) {
        lifecycleScope.launch {
            val repo = MusicRepository(requireContext())
            val playlists = withContext(Dispatchers.IO) {
                repo.getAllPlaylists().firstOrNull()
            } ?: emptyList()
            if (playlists.isEmpty()) {
                EmptyPlaylistActionDialog(requireActivity() as AppCompatActivity) {
                    showSelectPlaylistDialog(song)
                }.show()
                return@launch
            }
            // 查询每个歌单的歌曲数量，用于弹窗显示
            val songCounts = withContext(Dispatchers.IO) {
                val counts = mutableMapOf<Long, Int>()
                playlists.forEach { playlist ->
                    val songs = repo.getPlaylistSongs(playlist.id).firstOrNull()
                    counts[playlist.id] = songs?.size ?: 0
                }
                counts
            }
            // 以屏幕中心作为弹窗动画起点
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f
            SelectPlaylistDialog(
                context = requireContext(),
                triggerX = centerX,
                triggerY = centerY,
                allPlaylists = playlists,
                songCounts = songCounts,
                onConfirm = { chosenPlaylistIds ->
                    addSongToPlaylists(song, chosenPlaylistIds, playlists, repo)
                }
            ).show()
        }
    }

    /**
     * 将歌曲添加到选中的歌单（持久化到数据库）
     */
    private fun addSongToPlaylists(
        song: Song,
        playlistIds: List<Long>,
        playlists: List<Playlist>,
        repo: MusicRepository
    ) {
        if (playlistIds.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    repo.addSongsToPlaylist(playlistId, listOf(song))
                }
            }
            Toast.makeText(
                requireContext(),
                "已添加到 ${playlistIds.size} 个歌单",
                Toast.LENGTH_SHORT
            ).show()
            PlaylistRefresher.notifyPlaylistsChanged()
            // 若当前正在播放其中某个歌单，同步更新 MusicService 的歌曲列表
            val service = getService()
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    val playlist = playlists.find { it.id == playlistId }
                    if (playlist != null && service != null) {
                        val songs = repo.getPlaylistSongs(playlist.id).firstOrNull() ?: emptyList()
                        service.syncPlaylistSongList(playlist.id, songs)
                    }
                }
            }
        }
    }

    private fun updatePlayPauseState(isCurrentSongPlaying: Boolean) {
        if (isCurrentSongPlaying) {
            // 当前Fragment的歌曲正在播放，更新进度条
            updateTimeDisplay()
            updatePlayPauseButton(true)
        } else {
            updatePlayPauseButton(false)
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        _binding?.let { binding ->
            binding.playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    private fun updateSongInfo(song: Song?) {
        song?.let {
            currentSong = it
            binding.songTitle.text = it.title
            binding.artistName.text = it.artist
            binding.albumName.text = it.album

            // 音质标识：文字与颜色逻辑与 SongAdapter 一致
            binding.quality.setText(it.quality)
            val context = binding.root.context
            val qualityColor = when (it.quality) {
                "SQ" -> context.getColor(R.color.quality_sq)
                "HQ" -> context.getColor(R.color.quality_hq)
                "STD" -> context.getColor(R.color.quality_std)
                else -> context.getColor(R.color.quality_ord)
            }
            binding.quality.setColor(qualityColor)

            Log.d("PlayerFragment", "Updating song info: ${it.title} - ${it.artist}")

            Glide.with(this)
                .load(it.albumArt)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(36))
                .into(binding.albumArt)

            // 设置进度条最大值（使用歌曲实际时长）
            binding.seekBar.max = it.duration.toInt()
            updateTimeDisplay()
        }
    }

    fun updateCurrentSong(song: Song) {
        currentSong = song
        updateSongInfo(song)
    }

    private fun updateSeekBar(position: Int) {
        // 只在当前Fragment的歌曲正在播放时更新进度条
        val service = getService() ?: return
        val playingSong = service.currentSong.value
        val isCurrentSongPlaying = currentSong?.id == playingSong?.id

        // 当ViewPager不可见时（歌词全屏模式），停止刷新进度条
        if (!isViewPagerVisible()) return

        if (isCurrentSongPlaying) {
            binding.seekBar.progress = position
            updateTimeDisplay()
        }
    }

    private fun updateTimeDisplay() {
        val service = getService() ?: return
        val playingSong = service.currentSong.value
        val isCurrentSongPlaying = currentSong?.id == playingSong?.id

        // 当ViewPager不可见时（歌词全屏模式），停止刷新时间显示
        if (!isViewPagerVisible()) return

        if (isCurrentSongPlaying) {
            val currentPosition = service.getCurrentPosition()
            val duration = service.getDuration()

            binding.currentTime.text = formatTime(currentPosition)
            binding.totalTime.text = formatTime(duration)
        } else {
            // 当前Fragment的歌曲未播放，显示歌曲总时长
            currentSong?.let {
                binding.currentTime.text = "00:00"
                binding.totalTime.text = formatTime(it.duration.toInt())
            }
        }
    }

    /**
     * 检查ViewPager是否可见（用于判断是否处于歌词全屏模式）
     */
    private fun isViewPagerVisible(): Boolean {
        val activity = activity as? PlayerActivity ?: return true
        return activity.isLrcFullscreen.not()
    }

    private fun formatTime(milliseconds: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.toLong()) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    // 从Activity获取Service
    private fun getService(): MusicService? {
        val activity = requireActivity()
        return if (activity is PlayerActivity) {
            activity.getMusicService()
        } else {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}