package com.unicorn.player

import android.content.DialogInterface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.model.Song
import com.unicorn.player.service.MusicService
import com.unicorn.player.ui.SongMultiChoiceFragment
import com.unicorn.player.util.PlaylistHelper
import com.unicorn.player.util.ScrollToTopHelper
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.PlaylistViewModel
import kotlinx.coroutines.launch

open class SongMultiChoiceBaseActivity : AppCompatActivity(),
    SongMultiChoiceFragment.OnMultiChoiceActionListener {

    // 服务绑定
    protected var musicService: MusicService? = null
    private var songMultiChoiceFragment: SongMultiChoiceFragment? = null

    protected var playlistId: Long = -1L
    protected var multiChoiceView: View? = null

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
        PlaylistHelper.addToPlaylist(selectedSongIds, this, viewModel, musicService)
    }

    override fun onCancel() {
        dismissSongMultiChoiceFragment()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_UP) {
            if (songMultiChoiceFragment != null) {
                ScrollToTopHelper.scrollToTop(
                    songMultiChoiceFragment!!.getClickView(), ev
                ) { songMultiChoiceFragment!!.scrollToTop() }
            }
        }
        return super.dispatchTouchEvent(ev)
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
