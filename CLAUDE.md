# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 项目概述

UnicornPlayer 是一个使用 Kotlin 构建的现代 Android 音乐播放器应用，遵循 Material Design 3 设计规范，采用 MVVM 架构和 Repository 模式。

**包名**: `com.unicorn.player`
**最低 SDK**: 29 (Android 10)
**目标/编译 SDK**: 35 (Android 15)
**AGP**: 8.13.1
**Kotlin**: 2.2.20

## 构建命令
**注意:** 不要使用“./gradlew”命令，要使用gradle命令
### 开发构建
```bash
# 构建调试 APK
gradle app:assembleDebug

# 清理构建
gradle clean

# 构建发布 APK（需要密钥库）
gradle app:assembleRelease

# 仅编译 Kotlin（快速检查）
gradle app:compileDebugKotlin
```

### 测试
```bash
# 运行单元测试
gradle test

# 运行仪器化测试
gradle connectedAndroidTest

# 运行单个测试类
gradle testDebugUnitTest --tests="com.unicorn.player.SongAdapterTest"
```

### 安装
```bash
# 在连接的设备上安装调试 APK
gradle app:installDebug
```

## 交互说明
开发者是中文环境，在发送和接收指令时请使用简体中文

## 注意事项
创建新生文件时(不管是什么类型)，换行符要使用CRLF，不能使用LF换行符！
创建新类时，要根据类的用途和分类，考虑是否新建子包(比如component、util等)，不能总是放在com.unicorn.player包下
对于高度重复的代码，要提取为公共方法


## 架构

### MVVM + Repository 模式

```
视图层 (Activities/Fragments)
    ↓ 观察
视图模型层 (MusicViewModel)
    ↓ 调用
仓库层 (MusicRepository)
    ↓ 查询
数据源 (Room DB, MediaStore, DataStore)
```

### 数据流

1. **MusicRepository** 扫描 MediaStore 中的音频文件，分类音质（SQ/HQ/STD/ORD），将元数据存储到 Room
2. **MusicViewModel** 暴露 LiveData 给 UI，使用协程 Job 管理搜索
3. **MusicService** 处理 MediaPlayer、音频焦点、MediaSession、通知，通过 DataStore 持久化播放状态
4. **Activities** 绑定到 MusicService，观察 LiveData，更新 UI

### 核心组件

| 组件 | 文件 | 行数 | 职责 |
|------|------|------|------|
| MusicService | service/MusicService.kt | 1111 | MediaPlayer、音频焦点、通知、播放模式 |
| MusicRepository | repository/MusicRepository.kt | 171 | MediaStore 扫描、音质分类 |
| MusicViewModel | viewmodel/MusicViewModel.kt | 60 | UI 状态、搜索及 Job 管理 |
| MainActivity | MainActivity.kt | ~500 | 歌曲列表、搜索、服务绑定 |
| PlayerActivity | PlayerActivity.kt | ~400 | 全屏播放控制器 |
| SongAdapter | adapter/SongAdapter.kt | ~300 | RecyclerView 及旋转动画 |
| MusicDatabase | database/MusicDatabase.kt | 38 | Room DB 配置，启用 fallbackToDestructiveMigration |

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

## DataStore 键（播放状态）

MusicService 在 `music_player_state` DataStore 中持久化播放状态：

- `CURRENT_SONG_ID` (Long) - 当前播放歌曲 ID
- `CURRENT_POSITION` (Int) - 播放位置（毫秒）
- `IS_PLAYING` (Int) - 0=暂停, 1=播放
- `SONG_TITLE` (String) - 当前歌曲标题
- `SONG_ARTIST` (String) - 当前歌曲艺术家
- `SONG_PATH` (String) - 当前歌曲文件路径

## 音质分类

MusicRepository 根据比特率分类音质：

- **SQ** (超品质): 无损格式（FLAC、WAV、ALAC、APE、DSD）
- **HQ** (高品质): MP3 ≥320kbps 或 AAC/OGG ≥256kbps
- **STD** (标准): 128kbps ≤ 比特率 < 320kbps
- **ORD** (普通): 比特率 < 128kbps
- **UNK** (未知): 无法确定比特率

## Room 数据库架构

**版本**: 3（使用 `fallbackToDestructiveMigration()`）

### 实体
- **Song**: id (主键), title, artist, album, duration, path, albumArt, lastModified, quality
- **Playlist**: id (主键), name, createdAt
- **PlaylistSong**: playlistId (外键), songId (外键), position

### DAO
- **SongDao**: getAllSongs(), searchSongs(), 使用 REPLACE 策略的 insertSongs()
- **PlaylistDao**: 播放列表的增删改查操作

## 自定义视图

位于 `widget/` 包：

- **TitleBar**: 带返回按钮和标题的自定义应用栏
- **AudioQualityLabel**: 显示 SQ/HQ/STD/ORD 的彩色边框标签（1.6:1 宽高比）
- **PlayingAnimationView**: 当前播放歌曲的旋转光盘动画

## 动画资源

- `slide_top_out.xml`: 退出动画（从上到下）
- `slide_up_in.xml`: 进入动画（从下到上）
- `fade_in.xml` / `fade_out.xml`: 淡入淡出过渡
- **旋转动画**: 在 SongAdapter 中使用 `rotationAngleMap`（以 `song.id` 为键）管理

## 已知模式和注意事项

### 1. 使用歌曲 ID，而非位置
```kotlin
// 错误：位置会随搜索/过滤变化
val song = songList[position]

// 正确：ID 是稳定的
val song = songList.find { it.id == targetId }
```

### 2. 搜索 Job 取消
MusicViewModel 在启动新搜索前取消之前的 Job 以避免竞态条件：
```kotlin
fun searchSongs(query: String) {
    searchJob?.cancel()  // 取消之前的
    searchJob = viewModelScope.launch { /* ... */ }
}
```

### 3. 动画生命周期
SongAdapter 使用 `rotationAngleMap` 管理旋转动画，以在暂停/恢复时保持角度。暂停前始终保存角度，恢复时还原。

### 4. 文件过滤
MusicRepository 过滤掉：
- 小于 1MB 的文件（< 1024KB）
- 录音文件（`/music/Recordings/`、`/allsaintsMusic/`、`/msc/`）

### 5. 服务生命周期
MusicService 使用 `START_STICKY` 在被杀死后重启。带媒体播放通知的前台服务。必须请求 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限。

### 6. MediaSession 集成
MusicService 创建 MediaSessionCompat 用于系统媒体控件。使用 AudioFocusRequest 和 AudioManager 处理音频焦点。

## 所需权限

- `READ_MEDIA_AUDIO` - 访问音乐文件（Android 13+）
- `WAKE_LOCK` - 播放期间保持设备唤醒
- `FOREGROUND_SERVICE` - 后台服务
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - 媒体专用前台服务
- `POST_NOTIFICATIONS` - 显示播放通知（Android 13+）
- `BLUETOOTH` / `BLUETOOTH_CONNECT` - 蓝牙音频输出

## 依赖库

### 核心
- AndroidX Core KTX 1.10.1
- AppCompat 1.6.1
- Material 1.10.0
- ConstraintLayout 2.1.4

### 架构组件
- Lifecycle ViewModel/LiveData 2.6.1
- Room 2.5.1（使用 KAPT）
- RecyclerView 1.3.1

### 媒体
- Media 1.6.0
- MediaSessionCompat

### 图片加载
- Glide 4.16.0（使用 KAPT）

### UI 增强
- SmartRefreshLayout 3.0.0-alpha（下拉刷新）
- DataStore Preferences 1.0.0（播放状态持久化）

## 文件结构

```
app/src/main/java/com/unicorn/player/
├── MainActivity.kt              # 歌曲列表主界面
├── PlayerActivity.kt            # 全屏播放器
├── PlayerFragment.kt            # 播放器 UI 片段
├── PlayerPagerAdapter.kt        # ViewPager 适配器
├── SettingsActivity.kt          # 设置界面
├── UnicornPlayerApplication.kt  # 全局崩溃处理器
├── AdHeader.kt                  # 广告头部视图
├── model/
│   ├── Song.kt                  # Song 实体
│   └── Playlist.kt              # Playlist 实体
├── database/
│   ├── MusicDatabase.kt         # Room DB 配置
│   ├── SongDao.kt              # Song DAO
│   └── PlaylistDao.kt          # Playlist DAO
├── repository/
│   └── MusicRepository.kt      # 数据仓库
├── viewmodel/
│   ├── MusicViewModel.kt       # UI 状态管理
│   └── MusicViewModelFactory.kt # VM 工厂
├── adapter/
│   └── SongAdapter.kt          # RecyclerView 适配器
├── service/
│   └── MusicService.kt         # 后台音乐服务
└── widget/
    ├── TitleBar.kt             # 自定义标题栏
    ├── AudioQualityLabel.kt    # 音质标签
    └── PlayingAnimationView.kt # 播放动画
```

## 开发提示

### 运行测试
目前不存在测试目录。添加测试时：
- 单元测试: `src/test/java/com/unicorn/player/`
- 仪器化测试: `src/androidTest/java/com/unicorn/player/`

### 调试 MusicService
MusicService 使用标签 "MusicService" 记录日志。检查 logcat 查看：
- 音频焦点变化
- 播放状态变化
- 通知操作
- 服务生命周期事件

### 常见问题

1. **IndexOutOfBoundsException**: 始终使用歌曲 ID 进行查找，而非位置
2. **暂停时动画重置**: SongAdapter 将旋转角度保存在 `rotationAngleMap` 中
3. **搜索竞态条件**: 必须取消之前的搜索 Job
4. **服务未启动**: 先调用 `startService()`，再调用 `bindService()`
5. **内存泄漏**: 在 `onDestroy()` 中移除所有 LiveData 观察者

## 发布配置

发布构建使用：
- ProGuard 优化（`proguard-android-optimize.txt`）
- 启用资源压缩
- 密钥库: `keystore.jks`，别名 `relkey`
- 输出: `UnicornPlayer_1.0_YYMMDD.apk`
