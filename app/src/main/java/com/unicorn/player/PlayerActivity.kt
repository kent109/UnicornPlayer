package com.unicorn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
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
                if (service.isPlaying.value == true) {
                    service.pause()
                } else {
                    service.play()
                }
            }
        }

        binding.nextButton.setOnClickListener {
            musicService?.playNext()
        }

        binding.previousButton.setOnClickListener {
            musicService?.playPrevious()
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
            binding.albumName.text = it.album

            // Set album art if available
            // Glide.with(this)
            //     .load(it.albumArt)
            //     .placeholder(R.drawable.ic_music_note)
            //     .into(binding.albumArt)

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

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}