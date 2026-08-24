# 轻籁播放器

一款功能丰富的 Android 音乐播放器，采用现代 Material Design 3 设计规范和 MVVM 架构模式构建。支持本地音乐浏览、智能搜索、歌词同步、音频均衡器等专业音乐播放功能。

## 主要功能

### 🎵 核心播放功能
- **本地音乐扫描**：自动扫描设备中的音乐文件（MP3、FLAC、M4A、OGG、WAV、AAC、WMA 等）
- **高质量分类**：自动识别音频质量（SQ/高音质、HQ/音质、STD/标准、ORD/普通）
- **全屏播放器**：支持专辑封面展示、进度条拖动、播放控制
- **后台播放**：通过通知栏控制音乐播放，支持锁屏界面
- **媒体会话**：集成 Android 媒体会话 API，支持蓝牙和车载系统控制

### 🔍 智能搜索功能
- **多维度搜索**：支持按歌名、歌手、专辑名搜索
- **拼音搜索**：中文歌名和歌手名支持拼音首字母搜索（如：啊→A）
- **简繁体转换**：内置简繁体转换功能，方便不同地区用户
- **歌词搜索**：内置歌词搜索功能，支持预览和保存到本地

### 🎨 界面设计
- **Material Design 3**：采用最新的设计系统，支持动态配色
- **深色/浅色主题**：支持主题切换，适应不同使用场景
- **侧边栏导航**：支持侧边栏快速切换音乐分类
- **自定义控件**：包含播放动画、标题栏、进度条等自定义组件
- **下拉刷新**：集成 SmartRefreshLayout，支持多种刷新动画

### 📚 音乐库管理
- **歌曲列表**：支持按质量排序、搜索筛选
- **播放列表**：创建、编辑、删除播放列表，支持多选操作
- **歌手浏览**：按歌手分组展示，点击查看歌手歌曲
- **专辑浏览**：按专辑分组展示，点击查看专辑歌曲
- **收藏功能**：支持收藏喜欢的歌曲

### 🎵 专业音频功能
- **音频均衡器**：内置 10 段均衡器，支持预设和自定义调节
- **播放模式**：支持随机播放、顺序播放、单曲循环
- **音量控制**：独立的音量调节功能
- **音频质量标签**：直观显示音频文件质量等级

### 📝 歌词功能
- **歌词同步**：支持 LRC 格式歌词同步显示
- **歌词保存**：支持将歌词保存到 Documents/Unicorn/Lyrics 目录
- **歌词预览**：搜索结果支持预览功能
- **歌词搜索**：内置歌词搜索引擎

### ⚙️ 系统功能
- **扫描过滤**：自定义扫描过滤规则，选择性扫描特定目录
- **目录选择**：支持选择特定目录进行音乐扫描
- **手动扫描**：支持手动触发音乐扫描
- **设置管理**：提供详细的设置选项
- **反馈功能**：内置反馈提交功能
- **关于页面**：展示应用信息、版本号、更新日志

## 技术架构

应用采用 MVVM 架构模式，包含以下层次：

### 数据层
- **Room 数据库**：版本 3，本地存储音乐元数据
  - Songs 表：存储歌曲信息（标题、歌手、专辑、时长、路径等）
  - Playlists 表：存储播放列表
  - PlaylistSongs 表：播放列表与歌曲的多对多关系
  - Artists 表：存储歌手信息
  - Albums 表：存储专辑信息
- **DataStore**：版本 1.0.0，存储播放状态和用户偏好
- **Repository 模式**：统一数据访问接口

### 视图层
- **Activities**：
  - MainActivity：主界面，包含音乐列表和搜索功能
  - PlayerActivity：全屏播放器界面
  - SettingsActivity：设置页面
  - EqualizerActivity：均衡器设置
  - LyricsOptionsActivity：歌词选项
  - ArtistSongsActivity：歌手歌曲列表
  - AlbumSongsActivity：专辑歌曲列表
  - LrcSearchActivity：歌词搜索
  - DirectoryPickerActivity：目录选择
  - PlaylistSongsActivity：播放列表歌曲
  - ScanFilterActivity：扫描过滤设置
  - FeedbackActivity：反馈提交
  - ManualActivity：手动扫描
  - AboutActivity：关于页面
- **Fragments**：
  - SongsFragment：歌曲列表
  - ArtistFragment：歌手列表
  - AlbumFragment：专辑列表
  - PlaylistFragment：播放列表
  - PlaceholderFragment：占位符
  - PlayerFragment：播放器
- **Adapters**：
  - SongAdapter：歌曲列表适配器
  - SelectableSongAdapter：可多选歌曲适配器
  - PlaylistAdapter：播放列表适配器
  - SelectablePlaylistAdapter：可多选播放列表适配器
  - ArtistAdapter：歌手适配器
  - AlbumAdapter：专辑适配器
  - LrcSearchResultAdapter：歌词搜索结果适配器

### ViewModel 层
- **MusicViewModel**：管理歌曲数据和搜索状态
- **PlaylistViewModel**：管理播放列表数据
- **LiveData**：响应式数据观察
- **Coroutines**：异步操作支持

### 服务层
- **MusicService**：后台音乐播放服务
  - 集成 MediaPlayer API
  - 管理音频焦点
  - 处理媒体会话
  - 显示播放通知
  - 持久化播放状态

### 工具层
- **LrcHelper**：歌词处理工具
- **LrcFetcher**：歌词获取工具
- **LyricsSaveManager**：歌词保存管理器
- **PinyinUtil**：拼音转换工具
- **ZhConverterExt**：简繁体转换工具
- **UpdateHelper**：更新检查工具
- **FeedbackHelper**：反馈提交工具
- **DisplayUtil**：显示工具
- **SongInfoHelper**：歌曲信息处理

### 环境要求
- Android Studio Arctic Fox 或更高版本
- JDK 8 或更高版本
- Android SDK 35
- Gradle 8.2.0 或更高版本

## 权限说明

- `READ_MEDIA_AUDIO` - 读取音频文件（Android 13+）
- `READ_EXTERNAL_STORAGE` - 读取外部存储（Android 12 及以下）
- `WAKE_LOCK` - 播放期间保持设备唤醒
- `FOREGROUND_SERVICE` - 后台服务权限
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - 媒体专用前台服务
- `POST_NOTIFICATIONS` - 显示播放通知（Android 13+）
- `BLUETOOTH` / `BLUETOOTH_CONNECT` - 蓝牙音频输出

## 使用说明

### 首次使用
1. 启动应用，授予存储权限以扫描音乐文件
2. 应用将自动扫描设备中的音乐文件
3. 扫描完成后，可以在主界面浏览所有歌曲

### 播放音乐
1. 在主界面点击任意歌曲即可开始播放
2. 点击底部播放控制栏可进入全屏播放器
3. 支持后台播放，通过通知栏控制

### 搜索功能
1. 在主界面顶部搜索框输入关键词
2. 支持歌名、歌手、专辑名搜索
3. 中文内容支持拼音首字母搜索

### 歌词功能
1. 在播放器界面点击歌词按钮
2. 可以搜索歌词或查看预览
3. 支持保存歌词到本地

### 播放列表
1. 在主界面切换到播放列表标签
2. 点击"+"创建新播放列表
3. 在歌曲列表中长按选择歌曲，添加到播放列表

### 音频均衡器
1. 在播放器界面点击均衡器按钮
2. 调节 10 段均衡器参数
3. 选择预设或自定义调节

## 项目结构

```
app/src/main/java/com/unicorn/player/
├── MainActivity.kt                      # 主界面
├── PlayerActivity.kt                    # 全屏播放器
├── PlayerFragment.kt                    # 播放器组件
├── ui/                                  # 界面组件
│   ├── MainPagerAdapter.kt              # 主界面页适配器
│   ├── SongsFragment.kt                 # 歌曲列表
│   ├── ArtistFragment.kt                # 歌手列表
│   ├── AlbumFragment.kt                 # 专辑列表
│   ├── PlaylistFragment.kt              # 播放列表
│   ├── PlaceholderFragment.kt           # 占位符
│   ├── NewPlaylistDialog.kt             # 新建播放列表对话框
│   ├── SelectSongsDialog.kt             # 选择歌曲对话框
│   └── SelectPlaylistDialog.kt          # 选择播放列表对话框
├── model/                               # 数据模型
│   ├── Song.kt                          # 歌曲实体
│   ├── Playlist.kt                      # 播放列表实体
│   ├── Artist.kt                        # 歌手实体
│   ├── Album.kt                         # 专辑实体
│   └── LrcSearchResult.kt               # 歌词搜索结果
├── database/                            # 数据库层
│   ├── SongDao.kt                       # 歌曲数据访问
│   ├── PlaylistDao.kt                   # 播放列表数据访问
│   └── MusicDatabase.kt                 # 数据库配置
├── repository/                           # 数据仓库
│   └── MusicRepository.kt               # 音乐数据仓库
├── viewmodel/                            # 视图模型
│   ├── MusicViewModel.kt                # 音乐视图模型
│   ├── PlaylistViewModel.kt             # 播放列表视图模型
│   └── MusicViewModelFactory.kt         # 视图模型工厂
├── adapter/                              # 适配器
│   ├── SongAdapter.kt                   # 歌曲适配器
│   ├── SelectableSongAdapter.kt         # 可多选歌曲适配器
│   ├── PlaylistAdapter.kt               # 播放列表适配器
│   ├── SelectablePlaylistAdapter.kt     # 可多选播放列表适配器
│   ├── ArtistAdapter.kt                 # 歌手适配器
│   ├── AlbumAdapter.kt                  # 专辑适配器
│   └── LrcSearchResultAdapter.kt        # 歌词搜索结果适配器
├── service/                              # 服务层
│   └── MusicService.kt                  # 音乐播放服务
├── equalizer/                            # 均衡器模块
│   ├── EqualizerManager.kt              # 均衡器管理
│   └── EqualizerPreset.kt               # 均衡器预设
├── util/                                 # 工具类
│   ├── LrcHelper.kt                     # 歌词工具
│   ├── LrcFetcher.kt                    # 歌词获取
│   ├── LyricsSaveManager.kt             # 歌词保存
│   ├── PinyinUtil.kt                    # 拼音转换
│   ├── ZhConverterExt.kt                # 简繁体转换
│   ├── UpdateHelper.kt                  # 更新检查
│   ├── FeedbackHelper.kt                # 反馈提交
│   └── DisplayUtil.kt                   # 显示工具
├── widget/                               # 自定义控件
│   ├── TitleBar.kt                      # 标题栏
│   ├── PlayingAnimationView.kt          # 播放动画
│   ├── LrcViewContainer.kt              # 歌词容器
│   ├── IndeterminateProgressBar.kt      # 进度条
│   └── AudioQualityLabel.kt             # 音频质量标签
├── Activitys/                            # Activity
│   ├── SettingsActivity.kt              # 设置
│   ├── EqualizerActivity.kt             # 均衡器
│   ├── LyricsOptionsActivity.kt         # 歌词选项
│   ├── LrcSearchActivity.kt             # 歌词搜索
│   ├── ArtistSongsActivity.kt           # 歌手歌曲
│   ├── AlbumSongsActivity.kt            # 专辑歌曲
│   ├── PlaylistSongsActivity.kt         # 播放列表歌曲
│   ├── DirectoryPickerActivity.kt       # 目录选择
│   ├── ScanFilterActivity.kt            # 扫描过滤
│   ├── FeedbackActivity.kt              # 反馈
│   ├── ManualActivity.kt                # 手动扫描
│   └── AboutActivity.kt                 # 关于
└── diagnostics/                          # 诊断工具
    └── AdvancedPerformanceDiagnostics.kt # 性能诊断
```

## 技术亮点

- **Material Design 3**：最新的设计系统，支持动态配色和深色模式
- **Room 数据库**：高效本地存储，支持响应式查询
- **MediaPlayer API**：强大的音频播放引擎，完善的生命周期管理
- **前台服务**：正确的后台播放实现，带有通知控制
- **MVVM 架构**：清晰的架构模式，关注点分离
- **Kotlin 协程**：现代异步编程
- **LiveData**：响应式 UI 更新
- **ViewBinding**：类型安全的视图引用
- **SmartRefreshLayout**：流畅的下拉刷新体验
- **Glide**：高效的图片加载和缓存
- **LrcView**：专业的歌词显示组件
- **文档文件 API**：支持 SAF 目录树访问
- **OkHttp + Gson**：强大的网络请求和 JSON 解析
- **OpenCC + Pinyin4j**：智能的简繁体和拼音转换

## 版本信息

- **最低 SDK**：29（Android 10）
- **目标 SDK**：35（Android 15）
- **当前版本**：1.0.0
- **包名**：com.unicorn.player

## 许可证

本项目用于教育目的，展示了现代 Android 开发实践。

## 致谢

感谢所有开源项目和开发者，为本项目提供了强大的技术支持。
