package com.unicorn.player.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.exoplayer.ExoPlayer
import com.unicorn.player.R
import com.unicorn.player.activity.MainActivity
import com.unicorn.player.model.Song

/**
 * 播放通知 + MediaSession 元数据的纯构建工具。
 *
 * 只做纯构建：不持有 MusicService 引用，不读 Service 字段，不写 Service 状态。
 * Service 的 loadAlbumArt / updateNotification / invalidateAlbumArt 保持在 Service 内部。
 *
 * 构造时传入 Service context；每次调用传入当前 player/mediaSession/song/状态值。
 */
internal class NotificationController(
    private val context: Context,
    private val channelId: String,
    private val notificationId: Int,
    private val actionPlay: String,
    private val actionPause: String,
    private val actionNext: String,
    private val actionPrevious: String,
    private val actionStop: String,
) {
    /** 创建通知渠道（Android 8.0+） */
    fun createNotificationChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val existing = manager.getNotificationChannel(channelId)
        if (existing != null) return   // 已存在，保持用户设置的优先级/重要性/是否允许声音
        val channel = android.app.NotificationChannel(
            channelId,
            context.getString(R.string.notification_channel_name),
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setVibrationPattern(null)
            enableLights(false)
            setSound(null, null)
            setBypassDnd(true)  // 绕过勿扰模式
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建当前歌曲的前台通知。
     * @param isPlaying 当前播放意图（用 playWhenReady 语义）
     * @param albumArtBitmap 已解码封面，可 null
     * @param mediaSession MediaSessionCompat（Service 持有的实例）
     */
    fun createNotification(
        song: Song,
        isPlaying: Boolean,
        albumArtBitmap: android.graphics.Bitmap?,
        mediaSession: MediaSessionCompat,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause, "Pause", createActionPendingIntent(actionPause)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play, "Play", createActionPendingIntent(actionPlay)
            )
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(song.title)
            .setContentText("${song.artist} - ${song.album}")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
        albumArtBitmap?.let { builder.setLargeIcon(it) }

        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_previous, "Previous", createActionPendingIntent(actionPrevious)
            )
        ).addAction(playPauseAction).addAction(
            NotificationCompat.Action(
                R.drawable.ic_next, "Next", createActionPendingIntent(actionNext)
            )
        )
        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )

        return builder
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** 构建指定播放动作的 Service PendingIntent（通知栏按钮点击 → 广播 → MusicService.onReceive） */
    fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply {
            component = ComponentName(context, MusicService::class.java)
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        }
        val requestCode = when (action) {
            actionPlay -> 1001
            actionPause -> 1002
            actionNext -> 1003
            actionPrevious -> 1004
            actionStop -> 1005
            else -> action.hashCode()
        }
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 更新 MediaSession 的播放状态（由 Service 外部调用） */
    fun updatePlaybackState(player: ExoPlayer, mediaSession: MediaSessionCompat) {
        val state = if (player.playWhenReady) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val stateCompat = PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
            ).build()
        mediaSession.setPlaybackState(stateCompat)
    }

    /** 更新 MediaSession 的元数据（由 Service 外部调用） */
    fun updateMetadata(
        song: Song,
        player: ExoPlayer,
        mediaSession: MediaSessionCompat,
        albumArtBitmap: android.graphics.Bitmap?,
    ) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
        albumArtBitmap?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession.setMetadata(builder.build())
    }
}
