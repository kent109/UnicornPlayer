package com.unicorn.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.unicorn.player.MainActivity
import com.unicorn.player.R
import com.unicorn.player.model.Song
import java.io.IOException

class MusicService : Service() {

    private val binder = MusicBinder()
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaSession: MediaSessionCompat

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0)
    val currentPosition: LiveData<Int> = _currentPosition

    private var songList = mutableListOf<Song>()
    private var currentIndex = 0

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "music_channel"

        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_NEXT = "action_next"
        const val ACTION_PREVIOUS = "action_previous"
        const val ACTION_STOP = "action_stop"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                playNext()
            }
        }

        mediaSession = MediaSessionCompat(this, "MusicService")

        // Start position updates
        startPositionUpdates()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    fun setSongList(songs: List<Song>, startIndex: Int = 0) {
        songList.clear()
        songList.addAll(songs)
        currentIndex = startIndex
    }

    fun playSong(song: Song) {
        val index = songList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex = index
            playCurrentSong()
        }
    }

    fun playCurrentSong() {
        if (songList.isEmpty()) return

        val song = songList[currentIndex]
        _currentSong.postValue(song)

        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(song.path)
            mediaPlayer.prepare()
            mediaPlayer.start()
            _isPlaying.postValue(true)
            showNotification(song)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun play() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
            _isPlaying.postValue(true)
            showNotification(_currentSong.value)
        }
    }

    fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            _isPlaying.postValue(false)
            showNotification(_currentSong.value)
        }
    }

    fun playNext() {
        if (songList.isEmpty()) return

        currentIndex = if (currentIndex < songList.size - 1) {
            currentIndex + 1
        } else {
            0
        }
        playCurrentSong()
    }

    fun playPrevious() {
        if (songList.isEmpty()) return

        currentIndex = if (currentIndex > 0) {
            currentIndex - 1
        } else {
            songList.size - 1
        }
        playCurrentSong()
    }

    fun seekTo(position: Int) {
        mediaPlayer.seekTo(position)
        _currentPosition.postValue(position)
    }

    fun getCurrentPosition(): Int = mediaPlayer.currentPosition

    fun getDuration(): Int = mediaPlayer.duration

    private fun startPositionUpdates() {
        Thread {
            while (true) {
                try {
                    if (mediaPlayer.isPlaying) {
                        _currentPosition.postValue(mediaPlayer.currentPosition)
                    }
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun showNotification(song: Song?) {
        if (song == null) return

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (_isPlaying.value == true) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                createActionPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                createActionPendingIntent(ACTION_PLAY)
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText("${song.artist} - ${song.album}")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_previous,
                    "Previous",
                    createActionPendingIntent(ACTION_PREVIOUS)
                )
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_next,
                    "Next",
                    createActionPendingIntent(ACTION_NEXT)
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(_isPlaying.value == true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
        mediaSession.release()

        // Use modern API for stopping foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Cancel the notification
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }
}