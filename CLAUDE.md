# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供本仓库的开发指南。

## 项目概述

**UnicornPlayer** 是一款基于 Kotlin 和 Material Design 3 开发的 Android 音乐播放器应用。提供音乐文件扫描、播放列表管理、后台播放及通知控制、音频焦点处理等功能。

## 构建命令

```bash
# 构建调试版 APK
gradle app:assembleDebug

# 清理构建
gradle clean

# 输出路径：UnicornPlayer_debug_1.0.apk（位于 app/build/outputs/apk/debug/）
```

**系统要求**：Windows 操作系统、Android SDK、Java 1.8、Kotlin 2.2.20、AGP 8.13.1

## 交互说明
开发者是中文环境，在发送和接收指令时请使用简体中文

## 架构设计

### MVVM + Repository 模式

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   UI 层         │────▶│  ViewModel      │────▶│   Repository    │
│  (Activities/   │     │ (MusicViewModel)│     │(MusicRepository)│
│   Fragments)    │◀────│                 │◀────│                 │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                        ┌─────────────────┐              │
                        │  Room 数据库    │◀─────────────┘
                        │ (MusicDatabase) │
                        └─────────────────┘
```

### 核心组件

| 组件 | 职责 |
|------|------|
| `MainActivity` | 歌曲列表界面、搜索功能、底部播放栏、服务绑定 |
| `PlayerActivity` | 全屏播放界面，支持 ViewPager2 滑动切换歌曲 |
| `PlayerFragment` | 单个歌曲页面，包含播放控制 |
| `MusicService` | 后台播放、通知栏控制、音频焦点、媒体会话 |
| `MusicViewModel` | UI 数据管理、歌曲加载、搜索功能 |
| `MusicRepository` | MediaStore 扫描、数据库操作 |
| `SongAdapter` | RecyclerView 适配器，实现专辑封面旋转动画 |
| `PlayerPagerAdapter` | ViewPager2 适配器，管理歌曲页面 |
| `PlayingAnimationView` | 自定义动画均衡器条 |
| `AdHeader` | 自定义 SmartRefreshLayout 广告头部 |

## 数据流

1. **音乐扫描**：`MusicRepository.scanMusicFiles()` 查询 `MediaStore.Audio.Media` → 保存到 Room `songs` 表
2. **UI 更新**：`MusicViewModel` 收集 Room `Flow<List<Song>>` → 暴露 `LiveData` → `MainActivity` 观察
3. **播放控制**：点击歌曲 → `MusicService.setSongList()` + `requestAudioFocusAndPlayCurrentSong()`
4. **状态持久化**：`DataStore<Preferences>` 保存当前歌曲 ID、播放位置、播放状态
5. **通知栏**：`MusicService.updateNotification()` 使用 `MediaStyle` 通知

## 关键实现细节

### 音乐扫描
- 使用 `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` 查询
- 过滤条件：`IS_MUSIC != 0`，排除 Recordings/allsaintsMusic/msc 目录
- 最小文件大小：1024 KB（1 MB）
- 专辑封面 URI：`content://media/external/audio/albumart/{albumId}`

### 服务绑定模式
```kotlin
// 先启动服务（前台），再绑定
startService(intent)
bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
```

### 音频焦点处理
- 使用 `AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)`
- 在 `AUDIOFOCUS_LOSS_TRANSIENT` / `AUDIOFOCUS_LOSS` 时暂停
- 在 `AUDIOFOCUS_GAIN` 时恢复（如果之前正在播放）

### 动画生命周期（专辑封面旋转）
- `SongAdapter.onViewAttachedToWindow()` → 启动旋转
- `SongAdapter.onViewDetachedFromWindow()` → 停止旋转
- `MainActivity.onPause()` → `songAdapter.stopAllAnimations()`
- 使用 `ObjectAnimator` + `LinearInterpolator`，8 秒周期，无限循环

### 通知栏动作
通过 `PendingIntent.getService()` 发送广播：
- `ACTION_PLAY`、`ACTION_PAUSE`、`ACTION_NEXT`、`ACTION_PREVIOUS`、`ACTION_STOP`
- 每个动作使用独立 requestCode（1001-1005）

## 数据库结构

| 表名 | 实体 | 关键字段 |
|------|------|----------|
| `songs` | `Song` | id, title, artist, album, duration, path, albumArt, lastModified |
| `playlists` | `Playlist` | id, name, createdAt |
| `playlist_songs` | `PlaylistSong` | playlistId, songId（多对多关联） |

## 权限说明

- `READ_MEDIA_AUDIO`（Android 13+）/ `READ_EXTERNAL_STORAGE`
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `POST_NOTIFICATIONS`（Android 13+）
- `WAKE_LOCK`
- `BLUETOOTH`、`BLUETOOTH_ADMIN`、`BLUETOOTH_CONNECT`

## 代码风格

- 语言：注释和 UI 文本使用**简体中文**
- 变量命名：有意义的驼峰命名法
- 注释：复杂逻辑添加中文注释
- 架构：严格遵循 MVVM，Activity/Fragment 中不编写业务逻辑
- 协程：ViewModel 使用 `viewModelScope`，UI 使用 `lifecycleScope`
- Room：响应式查询使用 `Flow`，写入操作使用 `suspend` 函数

## 常用模式

### LiveData 观察
```kotlin
// 使用前始终检查初始化状态
if (::songAdapter.isInitialized) {
    songAdapter.submitList(songs)
}
```

### 服务连接
```kotlin
override fun onDestroy() {
    if (isServiceBound) {
        unbindService(serviceConnection)
        isServiceBound = false
    }
}
```

### 协程数据库操作
```kotlin
viewModelScope.launch {
    repository.scanMusicFiles() // suspend 函数
}
```

## 依赖说明

- **Room**：支持 Flow 的数据库
- **Glide**：图片加载，使用 RoundedCorners 变换
- **SmartRefreshLayout**：下拉刷新，带 TwoLevelHeader
- **DataStore**：偏好设置，用于播放状态持久化
- **MediaSessionCompat**：通知栏播放控制
- **ViewPager2**：PlayerActivity 中的歌曲滑动

## 调试建议

- 数据库问题：使用 Android Studio Database Inspector
- 服务问题：检查通知状态和 `MusicService` 日志
- 内存泄漏：使用 Android Profiler 检测 MediaPlayer/Service 泄漏
- UI 问题：使用 Layout Inspector 检查约束/布局问题
