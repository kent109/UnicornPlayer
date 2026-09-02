package com.unicorn.player

import android.content.DialogInterface
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.ui.SelectPlaylistDialog
import com.unicorn.player.ui.SongMultiChoiceFragment
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class SongMultiChoiceBaseActivity : AppCompatActivity(),
    SongMultiChoiceFragment.OnMultiChoiceActionListener {

    // 服务绑定
    protected var musicService: MusicService? = null
    private var songMultiChoiceFragment: SongMultiChoiceFragment? = null

    protected var playlistId: Long = -1L
    protected var multiChoiceView: View? = null
    private var lastTopTapTime = 0L
    private val clickViewRect = Rect()
    protected lateinit var viewModel: MusicViewModel
    protected lateinit var playlistViewModel: PlaylistViewModel
    protected lateinit var bottomPlayerController: BottomPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (songMultiChoiceFragment != null) {
            outState.putInt("SongMultiChoiceFragment", 1)
        }
    }

    protected fun handleRecreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null && savedInstanceState.getInt("SongMultiChoiceFragment") == 1) {
            bottomPlayerController.hide()
        }
    }

    override fun onDeleteSelected(playlistId: Long, selectedSongIds: Set<Long>) {
        showDeleteConfirmDialog(playlistId, selectedSongIds)
    }

    private fun updateServiceSongList() {
        // 正在播放歌单时，不覆盖歌单的歌曲列表
        if (musicService?.isPlayingPlaylist() == true) return

        val sortedSongs = viewModel.getSortedFullSongs()
        if (sortedSongs.isEmpty()) return

        val currentSong = musicService?.currentSong?.value
        val currentIndex = if (currentSong != null) {
            sortedSongs.indexOfFirst { it.id == currentSong.id }.takeIf { it != -1 } ?: 0
        } else {
            0
        }
        musicService?.setSongList(sortedSongs, currentIndex)
    }

    private fun showDeleteConfirmDialog(playlistId: Long, selectedSongIds: Set<Long>) {
        val title = "删除歌曲"
        val message = "确定要从列表中删除所选的歌曲吗？\n\n注意：这不会删除本地文件。"

        val errorColor = this.getColor(R.color.error)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                // 通过回调通知外部处理删除
                handleDeleteSelected(playlistId, selectedSongIds)
                Toast.makeText(this, "已从列表中删除", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
        // 设置删除按钮文字颜色为红色（error 色）
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(errorColor)
    }

    private fun handleDeleteSelected(playlistId: Long, selectedSongIds: Set<Long>) {
        lifecycleScope.launch {
            val clone = selectedSongIds.toMutableSet()
            for (songId in clone) {
                // 从当前歌单移除该歌曲；ViewModel reload 后列表自动刷新
                viewModel.removeSongFromPlaylist(playlistId, songId)
            }
            updateServiceSongList()
            setResult(RESULT_OK)
        }
        dismissMultiChoiceFragment()
    }

    private fun dismissMultiChoiceFragment() {
        if (songMultiChoiceFragment == null) {
            songMultiChoiceFragment =
                supportFragmentManager.findFragmentByTag("SMCF") as SongMultiChoiceFragment?
        }

        if (songMultiChoiceFragment == null) {
            return
        }
        supportFragmentManager.beginTransaction().remove(songMultiChoiceFragment!!).commitNow()
        bottomPlayerController.show()
        songMultiChoiceFragment = null
    }

    override fun onAddToPlaylist(selectedSongIds: Set<Long>) {
        handleAddToPlaylist(selectedSongIds)
    }

    override fun onCancel() {
        dismissSongMultiChoiceFragment()
    }

    private fun handleAddToPlaylist(selectedSongIds: Set<Long>) {
        lifecycleScope.launch {
            val repository = MusicRepository(this@SongMultiChoiceBaseActivity)
            val playlists = withContext(Dispatchers.IO) {
                repository.getAllPlaylists().firstOrNull()
            } ?: emptyList()
            if (playlists.isEmpty()) {
                Toast.makeText(this@SongMultiChoiceBaseActivity, "暂无歌单", Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }

            val songCounts = withContext(Dispatchers.IO) {
                val counts = mutableMapOf<Long, Int>()
                playlists.forEach { playlist ->
                    val songs = repository.getPlaylistSongs(playlist.id).firstOrNull()
                    counts[playlist.id] = songs?.size ?: 0
                }
                counts
            }

            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f

            SelectPlaylistDialog(
                context = this@SongMultiChoiceBaseActivity,
                triggerX = centerX,
                triggerY = centerY,
                allPlaylists = playlists,
                songCounts = songCounts,
                onConfirm = { chosenPlaylistIds ->
                    addSelectedSongsToPlaylists(
                        selectedSongIds, chosenPlaylistIds, playlists, repository
                    )
                }).show()
        }
    }

    private fun addSelectedSongsToPlaylists(
        selectedIds: Set<Long>,
        playlistIds: List<Long>,
        playlists: List<Playlist>,
        repository: MusicRepository
    ) {
        if (playlistIds.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    repository.addSongsToPlaylist(playlistId, selectedIds.mapNotNull { id ->
                        viewModel.fullSongs.value?.find { it.id == id }
                    })
                }
            }
            Toast.makeText(
                this@SongMultiChoiceBaseActivity,
                "已添加到 ${playlistIds.size} 个歌单",
                Toast.LENGTH_SHORT
            ).show()
            PlaylistRefresher.notifyPlaylistsChanged()
            // 若当前正在播放其中某个歌单，同步更新 MusicService 的歌曲列表
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    val playlist = playlists.find { it.id == playlistId }
                    if (playlist != null) {
                        val songs =
                            repository.getPlaylistSongs(playlist.id).firstOrNull() ?: emptyList()
                        musicService?.syncPlaylistSongList(playlist.name, songs)
                    }
                }
            }
            setResult(RESULT_OK)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_UP) {
            if (songMultiChoiceFragment != null) {
                scrollToTop(
                    songMultiChoiceFragment!!.getClickView(), ev
                ) { songMultiChoiceFragment!!.scrollToTop() }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scrollToTop(clickView: View, ev: MotionEvent, action: Runnable) {
        // 获取clickView在屏幕上的区域
        clickView.getHitRect(clickViewRect)
        // 将触摸事件坐标转换到clickView的父坐标系
        val location = IntArray(2)
        clickView.getLocationOnScreen(location)
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        if (clickViewRect.contains(
                x - location[0] + clickViewRect.left, y - location[1] + clickViewRect.top
            )
        ) {
            // 点击在clickView范围内
            val canTrigger = true
            if (canTrigger) {
                val currentTime = System.currentTimeMillis()
                val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
                if (currentTime - lastTopTapTime < doubleTapTimeout) {
                    // 双击检测：滚动当前标签页列表到顶部
                    action.run()
                    lastTopTapTime = 0L
                } else {
                    lastTopTapTime = currentTime
                }
            } else {
                lastTopTapTime = 0L
            }
        }
    }

    protected fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (songMultiChoiceFragment != null) {
                        dismissSongMultiChoiceFragment()
                    } else {
                        // 非多选模式，执行默认返回行为
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
    }

    protected fun showSongMultiChoiceFragment(fragmentType: Int, songList: List<Song>) {
        bottomPlayerController.hide()
        setupSongMultiChoiceFragment(fragmentType, songList)
        supportFragmentManager.beginTransaction()
            .replace(R.id.multiChoiceFragmentContainer, songMultiChoiceFragment!!, "SMCF")
            .commitNow()
    }

    private fun setupSongMultiChoiceFragment(fragmentType: Int, songList: List<Song>) {
        songMultiChoiceFragment = SongMultiChoiceFragment()
        val args = Bundle()
        args.putInt("type", fragmentType)
        args.putParcelableArrayList("list", ArrayList(songList))
        args.putLong("playlistId", playlistId)
        songMultiChoiceFragment?.arguments = args
    }

    private fun dismissSongMultiChoiceFragment() {
        if (songMultiChoiceFragment == null) {
            songMultiChoiceFragment =
                supportFragmentManager.findFragmentByTag("SMCF") as SongMultiChoiceFragment?
        }

        if (songMultiChoiceFragment == null) {
            return
        }
        supportFragmentManager.beginTransaction().remove(songMultiChoiceFragment!!).commitNow()
        bottomPlayerController.show()
        songMultiChoiceFragment = null
    }
}
