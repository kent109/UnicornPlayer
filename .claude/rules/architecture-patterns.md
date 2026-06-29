# 架构模式与约束

## 服务绑定模式

**关键**: 必须遵循此确切模式以避免内存泄漏：

```kotlin
// 1. 先启动服务（使其独立运行）
startService(Intent(this, MusicService::class.java))

// 2. 然后绑定以进行交互
bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

// 3. 存储观察者引用以便清理
private val observer = Observer<Song> { song -> /* 更新 UI */ }

// 4. 在 onDestroy/onStop 中移除观察者
override fun onDestroy() {
    if (isServiceBound) {
        unbindService(serviceConnection)
        isServiceBound = false
    }
    // 移除 LiveData 观察者
    musicService?.currentSong?.removeObserver(observer)
    super.onDestroy()
}
```

## 使用歌曲 ID，而非位置

```kotlin
// 错误：位置会随搜索/过滤变化
val song = songList[position]

// 正确：ID 是稳定的
val song = songList.find { it.id == targetId }
```

## 搜索 Job 取消

MusicViewModel 在启动新搜索前取消之前的 Job 以避免竞态条件：

```kotlin
fun searchSongs(query: String) {
    searchJob?.cancel()  // 取消之前的
    searchJob = viewModelScope.launch { /* ... */ }
}
```

## 动画生命周期

SongAdapter 使用 `rotationAngleMap` 管理旋转动画，以在暂停/恢复时保持角度。暂停前始终保存角度，恢复时还原。

## Event 包装模式

MusicService 使用 `Event<T>` 包装一次性事件（如 `requestSongList`），避免 LiveData 在配置变更时重复触发。

## 文件过滤

MusicRepository 过滤掉：
- 小于 1MB 的文件（< 1024KB）
- 录音文件（`/music/Recordings/`、`/allsaintsMusic/`、`/msc/`）

## 服务生命周期

MusicService 使用 `START_STICKY` 在被杀死后重启。带媒体播放通知的前台服务。必须请求 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限。

## MediaSession 集成

MusicService 创建 MediaSessionCompat 用于系统媒体控件。使用 AudioFocusRequest 和 AudioManager 处理音频焦点。

## 蓝牙断开检测

MusicService 注册广播接收器监听有线耳机插拔、蓝牙 ACL 断开、A2DP 连接状态变化、蓝牙配对状态变化，断开时自动暂停播放。

## MusicService LiveData 暴露

MusicService 暴露以下 LiveData 供 UI 观察：
- `currentSong: LiveData<Song?>` - 当前播放歌曲
- `isPlaying: LiveData<Boolean>` - 播放/暂停状态
- `currentPosition: LiveData<Int>` - 当前播放位置（毫秒）
- `playModeLiveData: LiveData<PlayMode>` - 当前播放模式
- `fileChanged: LiveData<Unit>` - 文件变化通知
- `requestSongList: LiveData<Event<Boolean>>` - 请求重新设置歌曲列表
