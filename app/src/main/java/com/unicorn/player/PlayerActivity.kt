package com.unicorn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.unicorn.player.databinding.ActivityPlayerBinding
import com.unicorn.player.model.Song
import com.unicorn.player.service.MusicService
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var musicService: MusicService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            setupMusicObservers()
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        bindMusicService()
    }

    private fun setupClickListeners() {
        binding.playPauseButton.setOnClickListener {
            musicService?.let { service ->
                val isPlaying = service.isPlaying.value ?: false
                if (isPlaying) {
                    service.pause()
                } else {
                    // 请求音频焦点，如果获得焦点就播放
                    service.requestAudioFocusAndPlay()
                }
                // 立即更新通知栏
                service.updateNotification()
            }
        }

        binding.nextButton.setOnClickListener {
            musicService?.let { service ->
                // 确保有歌曲列表或当前歌曲
                service.requestAudioFocusAndPlayNext()
                // 立即更新通知栏
                service.updateNotification()
            }
        }

        binding.previousButton.setOnClickListener {
            musicService?.let { service ->
                // 确保有歌曲列表或当前歌曲
                service.requestAudioFocusAndPlayPrevious()
                // 立即更新通知栏
                service.updateNotification()
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    musicService?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }

    private fun setupMusicObservers() {
        musicService?.currentSong?.observe(this) { song ->
            song?.let { updateSongInfo(it) }
        }

        musicService?.isPlaying?.observe(this) { isPlaying ->
            updatePlayPauseButton(isPlaying)
        }

        musicService?.currentPosition?.observe(this) { position ->
            updateSeekBar(position)
        }
    }

    private fun updateUI() {
        musicService?.let { service ->
            updateSongInfo(service.currentSong.value)
            updatePlayPauseButton(service.isPlaying.value ?: false)
            updateSeekBar(service.currentPosition.value ?: 0)
        }
    }

    private fun updateSongInfo(song: Song?) {
        song?.let {
            binding.songTitle.text = it.title
            binding.artistName.text = it.artist
            binding.albumName.text =
                if (TextUtils.equals(it.album, "Music")) "<unknown>" else it.album

            // 使用Glide加载专辑封面并添加圆角
            Glide.with(this)
                .load(it.albumArt)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(36))
                .into(binding.albumArt)

            // Update seek bar max duration
            binding.seekBar.max = musicService?.getDuration() ?: 0

            // Update time displays
            updateTimeDisplay()
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateSeekBar(position: Int) {
        binding.seekBar.progress = position
        updateTimeDisplay()
    }

    private fun updateTimeDisplay() {
        val currentPosition = musicService?.getCurrentPosition() ?: 0
        val duration = musicService?.getDuration() ?: 0

        binding.currentTime.text = formatTime(currentPosition)
        binding.totalTime.text = formatTime(duration)
    }

    private fun formatTime(milliseconds: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.toLong()) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}