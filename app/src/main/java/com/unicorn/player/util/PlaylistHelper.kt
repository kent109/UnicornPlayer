package com.unicorn.player.util

import android.app.Activity.RESULT_OK
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.unicorn.player.model.Playlist
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.ui.SelectPlaylistDialog
import com.unicorn.player.viewmodel.MusicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistHelper {
    companion object {

        fun addToPlaylist(
            selectedSongIds: Set<Long>,
            activity: AppCompatActivity,
            viewModel: MusicViewModel,
            musicService: MusicService?
        ) {
            (activity as LifecycleOwner).lifecycleScope.launch {
                val repository = MusicRepository(activity)
                val playlists = withContext(Dispatchers.IO) {
                    repository.getAllPlaylists().firstOrNull()
                } ?: emptyList()
                if (playlists.isEmpty()) {
                    Toast.makeText(activity, "暂无歌单", Toast.LENGTH_SHORT).show()
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

                val metrics = activity.resources.displayMetrics
                val centerX = metrics.widthPixels / 2f
                val centerY = metrics.heightPixels / 2f

                SelectPlaylistDialog(
                    context = activity,
                    triggerX = centerX,
                    triggerY = centerY,
                    allPlaylists = playlists,
                    songCounts = songCounts,
                    onConfirm = { chosenPlaylistIds ->
                        addSelectedSongsToPlaylists(
                            selectedSongIds,
                            chosenPlaylistIds,
                            playlists,
                            repository,
                            activity,
                            viewModel,
                            musicService
                        )
                    }).show()
            }
        }

        private fun addSelectedSongsToPlaylists(
            selectedIds: Set<Long>,
            playlistIds: List<Long>,
            playlists: List<Playlist>,
            repository: MusicRepository,
            activity: AppCompatActivity,
            viewModel: MusicViewModel,
            musicService: MusicService?
        ) {
            if (playlistIds.isEmpty()) return
            (activity as LifecycleOwner).lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    playlistIds.forEach { playlistId ->
                        repository.addSongsToPlaylist(playlistId, selectedIds.mapNotNull { id ->
                            viewModel.fullSongs.value?.find { it.id == id }
                        })
                    }
                }
                Toast.makeText(
                    activity, "已添加到 ${playlistIds.size} 个歌单", Toast.LENGTH_SHORT
                ).show()
                PlaylistRefresher.notifyPlaylistsChanged()
                // 若当前正在播放其中某个歌单，同步更新 MusicService 的歌曲列表
                withContext(Dispatchers.IO) {
                    playlistIds.forEach { playlistId ->
                        val playlist = playlists.find { it.id == playlistId }
                        if (playlist != null) {
                            val songs = repository.getPlaylistSongs(playlist.id).firstOrNull()
                                ?: emptyList()
                            musicService?.syncPlaylistSongList(playlist.id, songs)
                        }
                    }
                }
                activity.setResult(RESULT_OK)
            }
        }
    }
}