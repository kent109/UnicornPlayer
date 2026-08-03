# UnicornPlayer AGENTS.md

## 构建命令

- **使用 `gradle`（不是 `./gradlew`）**：Windows 环境需要 `gradle app:assembleDebug`、`gradle app:compileDebugKotlin`、`gradle clean`
- **构建类型**：Debug APK 命名为 `UnicornPlayer_debug_1.0.0.apk`，Release APK 命名为 `UnicornPlayer_1.0.0_YYMMDD.apk`（自动生成）
- **Release 签名**：项目根目录的 `keystore.jks`，别名 `relkey`，密码在 `app/build.gradle` 中

## 文件编码

- **所有文本文件必须使用 CRLF 换行符**（`\r\n`），不能使用 LF（`\n`）
- 适用于 `.kt`、`.java`、`.xml`、`.md`、`.gradle`、`.pro` 等
- 写工具默认输出 LF，写入后立即转换

## 架构

- **包名**：`com.unicorn.player`
- **最低 SDK**：29（Android 10），**目标 SDK**：35（Android 15）
- **架构**：MVVM + Repository 模式
- **ViewBinding**：在 `app/build.gradle:71-73` 中启用

## 主要依赖

- **Room 2.5.1**：数据库，使用 KAPT（`@Entity`、`@Dao`、`@Database`）
- **Glide 4.16.0**：图片加载，使用 KAPT
- **DataStore 1.0.0**：偏好设置存储（音乐播放状态）
- **SmartRefreshLayout 3.0.0-alpha**：下拉刷新 UI
- **LrcView V1.4**：歌词显示（JitPack）
- **Markwon 4.6.2**：Markdown 渲染（用于使用指南/关于页面）

## 代码生成

- **需要 KAPT**：`kapt 'androidx.room:room-compiler:2.5.1'`、`kapt 'com.github.bumptech.glide:compiler:4.16.0'`
- **Kotlin kapt 参数**：`app/build.gradle:11-15` 中设置 `javaParameters=true`（用于 Room 的 `:param` 占位符）

## 包结构

```
com.unicorn.player/
├── MainActivity.kt              # 入口：歌曲列表、搜索、服务绑定、底部播放器
├── PlayerActivity.kt            # 全屏播放器（ViewPager2 + LrcView）
├── PlayerFragment.kt            # 播放器 UI Fragment（ViewPager2 子页）
├── PlayerPagerAdapter.kt        # ViewPager2 适配器（stableId = song.id）
├── SettingsActivity.kt          # 设置界面（CardView 布局）
├── LyricsOptionsActivity.kt     # 歌词显示设置
├── ManualActivity.kt            # 使用指南（Markdown 来自 `R.raw.manual`）
├── AboutActivity.kt             # 关于页面（Markdown 来自 `R.raw.about`）
├── SongInfoHelper.kt            # 歌曲信息 BottomSheetDialog
├── model/                       # Room 实体（Song、Playlist）- Parcelable
├── database/                    # Room 数据库配置 + DAO（Flow 返回类型）
├── repository/                  # MediaStore 扫描 + 音频质量分类
├── viewmodel/                   # UI 状态管理（排序/搜索/加载）+ 工厂类
├── adapter/                     # RecyclerView（ListAdapter + DiffUtil + 旋转动画）
├── service/                     # 后台音乐服务（MediaPlayer + 通知 + 蓝牙）
├── util/                        # LrcHelper、LogWriter、DisplayUtil
└── widget/                      # TitleBar、AudioQualityLabel、PlayingAnimationView
```

## UI 组件

- **TitleBar**：自定义标题栏，带返回按钮，支持通过 XML 属性配置左/中/右区域
- **AudioQualityLabel**：SQ/HQ/STD/ORD 的彩色边框标签（1.6:1 宽高比）
- **PlayingAnimationView**：当前播放歌曲的旋转光盘动画
- **SmartRefreshLayout**：下拉刷新包装器（大多数页面已禁用）

## 数据流

1. **MusicRepository** 扫描 MediaStore 音频文件，分类质量（SQ/HQ/STD/ORD），将元数据存储到 Room
2. **MusicViewModel** 向 UI 暴露 LiveData，使用 coroutine Job 进行搜索
3. **MusicService** 处理 MediaPlayer、音频焦点、MediaSession、通知，将播放状态持久化到 DataStore
4. **Activities** 绑定到 MusicService，观察 LiveData，更新 UI

## Room 数据库

- **版本**：3（使用 `fallbackToDestructiveMigration()`）
- **实体**：Song（id、title、artist、album、duration、path、albumArt、lastModified、quality）、Playlist（id、name、createdAt）、PlaylistSong（playlistId、songId、position）
- **DAO**：SongDao.getAllSongs()、SongDao.searchSongs()、insertSongs() 使用 REPLACE 策略

## 自定义视图（widget/）

- **TitleBar**：返回按钮 + 标题，三区域布局（左/中/右）
- **AudioQualityLabel**：SQ/HQ/STD/ORD 彩色边框标签（1.6:1 宽高比）
- **PlayingAnimationView**：当前播放歌曲的旋转光盘动画

## 工具类（util/）

- **LrcHelper**：从音频目录加载 `.lrc` 文件，支持 `LrcDataBuilder` 和手动解析
- **LogWriter**：将错误日志写入外部存储 `error_logs` 目录，文件轮转（最大 5MB，保留 5 个备份）
- **DisplayUtil**：显示单位转换（sp2px、dp2px）

## 权限

- `READ_MEDIA_AUDIO` - 访问音频文件（Android 13+）
- `WAKE_LOCK` - 播放期间保持设备唤醒
- `FOREGROUND_SERVICE` - 后台服务
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - 媒体专用前台服务
- `POST_NOTIFICATIONS` - 显示播放通知（Android 13+）
- `BLUETOOTH` / `BLUETOOTH_CONNECT` - 蓝牙音频输出

## Release 配置

- **ProGuard**：`proguard-android-optimize.txt`（Release 时启用）
- **资源压缩**：Release 时启用
- **签名**：项目根目录的 `keystore.jks`，别名 `relkey`，密码在 `app/build.gradle:40-45`
- **输出**：`UnicornPlayer_1.0.0_YYMMDD.apk`

## 测试

- **编译检查**：`gradle app:compileDebugKotlin`（最快验证方式）
- **Lint**：通过 Android Studio 或 Gradle 任务运行
- **类型检查**：包含在 `gradle app:compileDebugKotlin` 中

## 常见陷阱

- **换行符**：文本文件始终使用 CRLF（CLAUDE.md:2）
- **ViewBinding**：始终使用 binding 视图，不要用 `findViewById`
- **Room KAPT**：必须使用 `kapt` 插件并启用 `javaParameters` 以支持 `:param` 占位符
- **Gradle 命令**：Windows 环境使用 `gradle` 而不是 `./gradlew`
- **包名**：始终使用 `com.unicorn.player` 前缀
