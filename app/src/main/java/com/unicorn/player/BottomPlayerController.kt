package com.unicorn.player

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.unicorn.player.databinding.ViewBottomPlayerBinding
import com.unicorn.player.model.Song
import com.unicorn.player.service.MusicService

/**
 * 底部播放栏控制器
 *
 * 封装底部播放栏（view_bottom_player.xml）的按钮事件、LiveData 观察者绑定与 UI 更新，
 * 供 MainActivity、ArtistSongsActivity 等宿主复用，避免在多个 Activity 中重复一份播放栏逻辑。
 *
 * 使用方式：
 * 1. 在布局中通过 <include android:id="@+id/bottomPlayer" layout="@layout/view_bottom_player" /> 引入
 * 2. 构造时传入 binding.bottomPlayer（ViewBottomPlayerBinding）
 * 3. 服务绑定成功后调用 observe(service)；服务断开/销毁时调用 removeObservers()
 * 4. onResume 调用 updateUI(service) 同步状态
 */
class BottomPlayerController(
    private val activity: AppCompatActivity,
    private val binding: ViewBottomPlayerBinding,
    private val serviceProvider: () -> MusicService?
) {

    private var isPlayingObserver: Observer<Boolean>? = null
    private var currentSongObserver: Observer<Song?>? = null

    /**
     * 设置底部播放栏按钮点击事件
     *
     * - 整体点击 → 打开全屏播放界面
     * - 播放/暂停、上一首、下一首 → 通过 serviceProvider 操作 MusicService
     */
    fun setupClickListeners() {
        binding.root.setOnClickListener {
            val intent = Intent(activity, PlayerActivity::class.java)
            activity.startActivity(intent)
            activity.overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out)
        }

        binding.playButton.setOnClickListener {
            serviceProvider()?.let { service ->
                if (service.isPlaying.value == true) {
                    service.pause()
                } else {
                    service.requestAudioFocusAndPlay()
                }
            }
        }

        binding.previousButton.setOnClickListener {
            serviceProvider()?.requestAudioFocusAndPlayPrevious()
        }

        binding.nextButton.setOnClickListener {
            serviceProvider()?.requestAudioFocusAndPlayNext()
        }
    }

    /**
     * 绑定 MusicService 的 LiveData 观察者，驱动播放按钮图标与歌曲信息更新
     */
    fun observe(service: MusicService) {
        removeObservers()

        isPlayingObserver = Observer { isPlaying ->
            binding.playButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
        service.isPlaying.observe(activity, isPlayingObserver!!)

        currentSongObserver = Observer { song ->
            if (song == null) {
                clearSongInfo()
            } else {
                updateSongInfo(song)
            }
        }
        service.currentSong.observe(activity, currentSongObserver!!)
    }

    /**
     * 移除 LiveData 观察者，在服务断开或 Activity 销毁时调用
     */
    fun removeObservers() {
        val service = serviceProvider()
        isPlayingObserver?.let { service?.isPlaying?.removeObserver(it) }
        currentSongObserver?.let { service?.currentSong?.removeObserver(it) }
        isPlayingObserver = null
        currentSongObserver = null
    }

    /**
     * 立即根据当前服务状态刷新 UI（onResume / 服务重连后调用）
     */
    fun updateUI(service: MusicService) {
        binding.playButton.setImageResource(
            if (service.isPlaying.value == true) R.drawable.ic_pause else R.drawable.ic_play
        )
        val song = service.currentSong.value
        if (song == null) {
            clearSongInfo()
        } else {
            updateSongInfo(song)
        }
    }

    /**
     * 清空底部播放栏的歌曲信息（标题、艺术家、专辑封面恢复默认），
     * 当当前播放歌曲被移除时调用。
     */
    fun clearSongInfo() {
        if (activity.isDestroyed || activity.isFinishing) return

        binding.songTitle.text = ""
        binding.artistName.text = ""
        // 关闭跑马灯
        binding.songTitle.post {
            binding.songTitle.isSelected = false
        }
        // 专辑封面恢复默认图标
        binding.albumArt.setImageResource(R.drawable.ic_music_note)
    }

    /**
     * 更新底部播放栏的歌曲标题、艺术家与专辑封面
     */
    fun updateSongInfo(song: Song) {
        if (activity.isDestroyed || activity.isFinishing) return

        binding.songTitle.text = song.title
        binding.artistName.text = song.artist

        // 激活跑马灯效果
        binding.songTitle.post {
            binding.songTitle.isSelected = true
            binding.songTitle.requestFocus()
        }

        // 使用 Glide 加载专辑封面并添加圆角
        Glide.with(activity).load(song.albumArt).placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .transform(RoundedCorners(20))
            .into(binding.albumArt)
    }
}
