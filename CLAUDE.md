# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

> ⚠️ **铁律：创建任何新文件时，换行符必须使用 CRLF（`\r\n`），禁止使用 LF（`\n`）。**
> 包括 `.kt`、`.java`、`.xml`、`.md`、`.gradle`、`.pro` 等所有文本文件。Write 工具默认输出 LF，生成后必须立即转换。

详细规则约束请参阅 `.claude/rules/` 目录：
- `coding-standards.md` — 编码规范（CRLF、分包、ViewBinding、交互语言）
- `architecture-patterns.md` — 架构模式（服务绑定、ID 查找、Job 取消、动画生命周期等）
- `data-persistence.md` — 数据持久化（DataStore/SharedPreferences 键、排序同步）
- `domain-constants.md` — 领域常量（音质分类、PlayMode 枚举、通知动作常量）
- `common-pitfalls.md` — 常见问题与陷阱

## 项目概述

UnicornPlayer 是一个使用 Kotlin 构建的现代 Android 音乐播放器应用，遵循 Material Design 3 设计规范，采用 MVVM 架构和 Repository 模式。

**包名**: `com.unicorn.player`
**最低 SDK**: 29 (Android 10)
**目标/编译 SDK**: 35 (Android 15)
**AGP**: 8.13.1
**Kotlin**: 2.2.20

## 构建命令

**注意:** 不要使用 `./gradlew` 命令，要使用 `gradle` 命令。

```bash
# 构建调试 APK
gradle app:assembleDebug

# 清理构建
gradle clean

# 构建发布 APK（需要密钥库）
gradle app:assembleRelease

# 仅编译 Kotlin（快速检查）
gradle app:compileDebugKotlin

# 在连接的设备上安装调试 APK
gradle app:installDebug
```

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


## Room 数据库架构

**版本**: 3（使用 `fallbackToDestructiveMigration()`）

### 实体
- **Song**: id (主键), title, artist, album, duration, path, albumArt, lastModified, quality
- **Playlist**: id (主键), name, createdAt
- **PlaylistSong**: playlistId (外键), songId (外键), position

### DAO
- **SongDao**: getAllSongs(), searchSongs(), 使用 REPLACE 策略的 insertSongs()
- **PlaylistDao**: 播放列表的增删改查操作

## 自定义视图（widget/ 包）

- **TitleBar**: 带返回按钮和标题的自定义应用栏，支持左中右三区域布局，通过 XML 属性配置
- **AudioQualityLabel**: 显示 SQ/HQ/STD/ORD 的彩色边框标签（1.6:1 宽高比），自定义 View 绘制
- **PlayingAnimationView**: 当前播放歌曲的旋转光盘动画

## 工具类（util/ 包）

- **LrcHelper**: 歌词加载工具，从音频文件同目录加载 `.lrc` 文件，支持 `LrcDataBuilder` 和手动解析两种方式
- **LogWriter**: 错误日志写入器，将错误日志写入外部存储 `error_logs` 目录，支持文件轮转（最大 5MB，保留 5 个备份）
- **DisplayUtil**: 显示单位转换工具（sp2px, dp2px）

## 动画资源

- `slide_top_out.xml`: 退出动画（从上到下）
- `slide_up_in.xml`: 进入动画（从下到上）
- `fade_in.xml` / `fade_out.xml`: 淡入淡出过渡
- **旋转动画**: 在 SongAdapter 中使用 `rotationAngleMap`（以 `song.id` 为键）管理

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

### 歌词
- LrcView V1.4（JitPack: `com.github.bifan-wei:LrcView:V1.4`）

### 其他
- kotlin-parcelize（Song/Playlist 实体实现 Parcelable）

## 文件结构

```
app/src/main/java/com/unicorn/player/
├── MainActivity.kt              # 歌曲列表、搜索、服务绑定、底部播放栏
├── PlayerActivity.kt            # 全屏播放器（ViewPager2 + LrcView 全屏）
├── PlayerFragment.kt            # 播放器 UI 片段（ViewPager2 子页）
├── PlayerPagerAdapter.kt        # ViewPager2 适配器（以 song ID 为 stableId）
├── SettingsActivity.kt          # 设置界面
├── LyricsOptionsActivity.kt     # 歌词显示设置
├── SongInfoHelper.kt            # 歌曲信息 BottomSheetDialog
├── UnicornPlayerApplication.kt  # 全局崩溃处理器
├── AdHeader.kt                  # 广告头部视图
├── model/                       # Song、Playlist 实体（Parcelable, Room Entity）
├── database/                    # Room DB 配置 + DAO（Flow 返回类型）
├── repository/                  # MediaStore 扫描 + 音质分类
├── viewmodel/                   # UI 状态管理（排序/搜索/加载）+ 工厂
├── adapter/                     # RecyclerView（ListAdapter + DiffUtil + 旋转动画）
├── service/                     # 后台音乐服务（MediaPlayer + 通知 + 蓝牙）
├── util/                        # LrcHelper、LogWriter、DisplayUtil
└── widget/                      # TitleBar、AudioQualityLabel、PlayingAnimationView
```

## 开发提示

MusicService 使用标签 "MusicService" 记录日志。检查 logcat 查看：
- 音频焦点变化
- 播放状态变化
- 通知操作
- 服务生命周期事件

## 发布配置

发布构建使用：
- ProGuard 优化（`proguard-android-optimize.txt`）
- 启用资源压缩
- 密钥库: `keystore.jks`，别名 `relkey`
- 输出: `UnicornPlayer_1.0_YYMMDD.apk`
