# UnicornPlayer AGENTS.md

## 构建命令

**仅限 Windows**：使用 `gradle`（不是 `./gradlew`）

```bash
gradle app:assembleDebug          # 构建调试 APK
gradle app:assembleRelease        # 构建发布 APK
gradle app:compileDebugKotlin     # 快速类型检查
gradle clean                      # 清理构建
```

**发布 APK 命名**：`UnicornPlayer_1.0.0_YYMMDD.apk`（从 `app/build.gradle:29-37` 自动生成）

**签名**：`keystore.jks`，别名 `relkey`，密码 `Pro_365zm`（见 `app/build.gradle:39-46`）

## 架构

**包名**：`com.unicorn.player`
**最低 SDK**：29（Android 10），**目标 SDK**：35（Android 15）
**架构**：MVVM + Repository 模式
**ViewBinding**：在 `app/build.gradle:71-73` 中启用

### 模块结构

```
UnicornPlayer/
├── app/          # 主应用模块
└── equalizer/    # 音频均衡器库模块
```

### 关键组件

- **MusicRepository**：扫描 MediaStore，分类质量（SQ/HQ/STD/ORD），存储到 Room
- **MusicViewModel**：暴露 LiveData，使用协程 Job 进行搜索
- **MusicService**：MediaPlayer + 音频焦点 + MediaSession + 通知，将状态持久化到 DataStore
- **Activities**：MainActivity、PlayerActivity、SettingsActivity、LyricsOptionsActivity、ArtistSongsActivity、AlbumSongsActivity、LrcSearchActivity、EqualizerActivity、DirectoryPickerActivity、PlaylistSongsActivity、ScanFilterActivity、FeedbackActivity、ManualActivity、AboutActivity
- **Fragments**：MainPagerAdapter、SongsFragment、ArtistFragment、AlbumFragment、PlaylistFragment、PlaceholderFragment
- **Adapters**：SongAdapter、SelectableSongAdapter、PlaylistAdapter、SelectablePlaylistAdapter、ArtistAdapter、AlbumAdapter、LrcSearchResultAdapter

## 数据层

### Room 数据库（版本 3）

**实体**：Song、Playlist、PlaylistSong、Artist、Album
**DAOs**：SongDao、PlaylistDao（都返回 Flow）
**迁移**：`fallbackToDestructiveMigration()`

### DataStore（Preferences 1.0.0）

存储播放状态：当前歌曲、播放模式、随机模式、音量

## 依赖

**需要 KAPT**：Room 2.5.1、Glide 4.16.0
**Kotlin kapt 参数**：`javaParameters=true`（Room 的 `:param` 占位符支持）- `app/build.gradle:11-15`

**关键库**：
- Room 2.5.1（KAPT）
- Glide 4.16.0（KAPT）
- DataStore 1.0.0
- SmartRefreshLayout 3.0.0-alpha
- LrcView V1.4（JitPack）
- Markwon 4.6.2
- OkHttp 4.12.0、Gson 2.10.1
- opencc4j 1.14.0、pinyin4j 2.5.1
- Kotlinx Serialization 1.11.0
- DocumentFile 1.0.0（SAF）

## 代码生成与格式化

**每次代码修改后必须格式化**：
- 删除未使用的 import
- 保持 4 空格缩进
- 统一空格使用（运算符、逗号）
- 方法参数换行格式化
- 使用 Android Studio "Code" -> "Reformat Code"

**换行符规则（重要）**：
- **CRLF 换行符**：Windows 标准换行符，格式为 `\r\n`（回车+换行）
- **LF 换行符**：Unix/Linux 标准换行符，格式为 `\n`（换行）
- **CR 换行符**：旧式换行符，格式为 `\r`（回车）
- **禁止使用 CR**：绝对不能使用纯 CR（`\r`）
- **禁止使用 LF**：绝对不能使用纯 LF（`\n`）
- **必须使用 CRLF**：所有文本文件必须使用 CRLF（`\r\n`）

**新文件创建规则**：
- 新建文本文件（.kt、.java、.xml、.md、.gradle、.xml、.properties 等）必须使用 CRLF（`\r\n`）
- Write 工具默认输出 LF，需要立即转换为 CRLF
- 正确转换方法：`content.replace('\n', '\r\n')`

**检查文件换行符的方法**：
```bash
# Python 方法
python -c "content = open(r'文件路径', 'rb').read(); print('CRLF count:', content.count(b'\r\n'))"

# PowerShell 方法
powershell -Command "(Get-Content '文件路径' -Raw) -replace '`r`n', '`r`n'"
```

**常见错误**：
- ❌ 使用 Write 工具后直接保存（会得到 LF）
- ❌ 使用 PowerShell Set-Content（默认使用 LF）
- ❌ 使用 Python open() 写入（默认使用 LF）
- ✅ 使用 Python `content.replace('\n', '\r\n')` 转换
- ✅ 使用 PowerShell `Get-Content ... | Set-Content ... -NoNewline`

## 权限

- `READ_MEDIA_AUDIO` - 音频文件（Android 13+）
- `WAKE_LOCK` - 播放期间保持设备唤醒
- `FOREGROUND_SERVICE` - 后台服务
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - 媒体专用前台服务
- `POST_NOTIFICATIONS` - 显示播放通知（Android 13+）
- `BLUETOOTH` / `BLUETOOTH_CONNECT` - 蓝牙音频输出

## 发布配置

**ProGuard**：`proguard-android-optimize.txt`（在 `app/build.gradle:49-54` 中启用）
**资源压缩**：发布构建中启用
**签名**：`keystore.jks`（根目录），别名 `relkey`，密码 `Pro_365zm`
**混淆**：启用

## 常见陷阱

- **换行符**：所有新建的文本文件（.kt、.java、.xml、.md、.gradle）必须使用 CRLF，不能使用 LF
- **ViewBinding**：始终使用 binding 视图，不要用 `findViewById`
- **Room KAPT**：必须使用 `kapt` 插件并启用 `javaParameters` 以支持 `:param`
- **Gradle 命令**：Windows 使用 `gradle`，不是 `./gradlew`
- **包名前缀**：始终使用 `com.unicorn.player` 前缀
- **包结构**：实际结构比文档中描述的大得多；检查 `app/src/main/java/com/unicorn/player/` 获取完整列表
- **均衡器模块**：`equalizer` 模块作为库项目包含在 `app/build.gradle:141`

## 测试

未找到单元测试或仪器测试。
最快验证：`gradle app:compileDebugKotlin`（包含类型检查）
