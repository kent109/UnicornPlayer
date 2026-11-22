# UnicornPlayer AGENTS.md

## 构建命令（仅限 Windows）

```bash
gradle app:compileDebugKotlin     # 快速类型检查（最常用）
gradle app:assembleDebug          # 构建调试 APK
gradle app:assembleRelease        # 构建发布 APK
gradle clean                      # 清理构建
```

- **签名**：`keystore.jks`
- **发布 APK 命名**：`UnicornPlayer_1.0.0_YYMMDD.apk`（`app/build.gradle:29-37` 自动生成）
- **ProGuard**：已启用 `proguard-android-optimize.txt` + 资源压缩 + 混淆

## 项目参数

| 项 | 值 |
|----|----|
| 包名 | `com.unicorn.player` |
| minSdk / targetSdk | 29 / 35 |
| 架构 | MVVM + Repository |
| ViewBinding | 已启用（`app/build.gradle`） |
| 模块 | `app/` + `equalizer/`（库项目） |
| ExoPlayer | media3 1.9.x（`androidx.media3.exoplayer.ExoPlayer`） |
| Room | 2.5.1（KAPT，参数名需 `javaParameters = true`） |

## 目录结构（重构后）

```
com.unicorn.player/
├── UnicornPlayerApplication.kt     # 根包唯一入口
│
├── activity/                        # 所有 Activity（24 个）
│   ├── PlayerLayout.kt              #   PlayerActivity 布局函数
│   ├── SearchBarGestureController.kt #  MainActivity 搜索栏手势
│   └── ...Activity.kt               #   BaseActivity、MainActivity、PlayerActivity 等
│
├── service/                         # MusicService + 8 个职责分离类
│   ├── MusicService.kt              #   核心（~2020 行，累计减 1107 行）
│   ├── PlayerListener.kt            #   Player.Listener 3 回调
│   ├── MusicServiceMediaSessionCallback.kt # MediaSession.Callback 5 回调
│   ├── ServiceBroadcastReceivers.kt  #  通知栏按钮 + 蓝牙状态工厂
│   ├── AudioFocusManager.kt          #   手动音频焦点管理
│   ├── NotificationController.kt    #   通知 + MediaSession 元数据
│   ├── AlbumArtDecoder.kt            #   封面 4 来源解码链
│   ├── MusicServiceBluetooth.kt      #   蓝牙 A2DP + 断开处理
│   ├── PositionUpdater.kt            #   1 秒进度轮询
│   └── PlaySource.kt                 #   顶层 object（DataStoreKeys 等）
│
├── util/
│   ├── compat/IntentCompat.kt       #   getParcelableExtra API 兼容
│   ├── cover/                       #   封面处理（5）
│   ├── datastore/UnicornDataStores.kt #  顶层 DataStore 扩展
│   ├── diag/CallStack.kt            #   callerTrace 栈帧过滤
│   ├── event/Event.kt               #   一次性事件包装
│   ├── lyrics/                      #   歌词（3）
│   └── sort/                        #   排序/拼音/繁简（3）
│
├── ui/                              # Fragment + Dialog
│   └── player/                      #   PlayerFragment + BottomPlayerController
│
├── equalizer/                       # TenBandEqualizerProcessor + BiquadModels
├── model/                           # Room 实体（Song/Playlist/Artist/Album/Cover）
├── database/                        # MusicDatabase + DAOs
├── adapter/                         # 12 个 Adapter
├── viewmodel/                       # MusicViewModel + PlaylistViewModel
├── repository/                      # MusicRepository
├── playback/                        # SongPlayableRegistry
├── manager/                         # MusicManager
├── diagnostics/                     # 性能诊断
└── widget/                          # 自定义 View
```

## Service 职责拆分模式（通用范式）

MusicService 从 3127 → ~2020 行，采用**闭包注入**替代直接字段访问：

| 安全级别 | 模式 | 例子 |
|---------|------|------|
| 🟢 | 零闭包静态 object | `PlayerLayout`、`BiquadModels`、`Event` |
| 🟡 | 构造注入 3-10 闭包 | `PositionUpdater`、`NotificationController`、`AlbumArtDecoder` |
| 🟠 | 构造注入 10-37 闭包 | `PlayerListener`、`MusicServiceMediaSessionCallback` |
| 🔴 | 保留在 Service 内 | `playNext/playPrevious/play/pause/seekTo`（核心业务互相调用） |

**原则**：每个闭包参数要有明确语义（`getIsPlaying` 而非 `() -> Boolean`），避免 `Any`/`Pair` 这类模糊类型。

## 数据层

- **Room 数据库（版本 3）**：Song、Playlist、PlaylistSong、Artist、Cover 实体；SongDao、PlaylistDao、CoverDao；`fallbackToDestructiveMigration()`
- **DataStore（Preferences 1.0.0）**：`UnicornDataStores.kt` 里 3 个顶层扩展（themeDataStore/scanFiltersDataStore/lyricsDataStore）
- **SAF**：DocumentFile 1.0.0（直接权限不可用时走 SAF）

## 依赖

**需要 KAPT**：Room 2.5.1、Glide 4.16.0

**关键库**：
- Room 2.5.1、Glide 4.16.0、DataStore 1.0.0
- media3 1.9.x（Player/MediaSession）
- SmartRefreshLayout 3.0.0-alpha
- OkHttp 4.12.0、Gson 2.10.1
- Kotlinx Serialization 1.11.0
- opencc4j 1.14.0（繁简转换）、pinyin4j 2.5.1（拼音排序）
- DocumentFile 1.0.0（SAF）

## 代码规范

**换行符（重要）**：所有新建文本文件必须 **CRLF**（`\r\n`）。Write 工具默认 LF，需 `content.replace('\n', '\r\n')`。

**Room KAPT 陷阱**：必须在 `app/build.gradle` 的 `android { kotlin { compilerOptions { javaParameters = true } } }` 启用参数名。放 `kapt { arguments { arg(...) } }` 无效。症状：`kaptDebugKotlin` 报 "Each bind variable in the query must have a matching method parameter"。

**ViewBinding**：始终用 `binding` 视图，不用 `findViewById`。

**Gradle**：Windows 用 `gradle`，不是 `./gradlew`。

## 权限

`READ_MEDIA_AUDIO`（Android 13+）、`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`（Android 13+）、`BLUETOOTH` / `BLUETOOTH_CONNECT`

## 快速验证

项目没有单元测试。最可靠的验证：

```bash
gradle app:compileDebugKotlin          # 类型检查 + KAPT + KSP
gradle app:assembleDebug               # 完整构建 + ProGuard + 签名
```
