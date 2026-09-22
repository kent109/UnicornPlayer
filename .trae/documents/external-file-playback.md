# 外部文件播放功能方案

## Context（背景）

当前 UnicornPlayer 只能从应用内的歌曲/歌手/专辑/歌单列表点击播放入口。当用户在系统文件管理器点击一个音频文件时，没有任何 intent-filter 接收，应用无法响应。

目标：让 PlayerActivity 接收外部 `ACTION_VIEW`（audio/\*），临时播放该文件，区分两种模式：

* **永久模式**（文件不在排除目录内）：写入 DB，歌曲/歌手/专辑列表立即刷新，加入全库队列，next/prev 正常，保存进度，PlayerActivity 销毁后歌曲留在库里。

* **临时模式**（文件在排除目录内）：不入库，单曲队列，next/prev 置灰（UI+通知栏+耳机），不保存进度；PlayerActivity 销毁时继续播放，自然结束或用户切歌时回到「上次保存进度」的歌曲从头播放。

## 已确认的关键决策

1. **URI 范围**：只支持可解析为真实文件路径的 URI（`content://media/...` 经 MediaStore DATA 列解析；`file://` 直接取路径）。SAF/document URI（如 `content://com.android.providers...`）无法解析时弹 Toast 放弃，不做文件拷贝。
2. **临时歌曲不入 DB**：service.songList=\[tempSong]，仅内存标记。不污染 Songs/Artist/Album 列表。
3. **永久歌曲入 DB**：按路径去重，加入全库队列。
4. **不升数据库版本、Song 不加持久化字段**：文件路径已知 → 解析目录 → 与用户设置的排除目录对比，是否临时播放完全动态计算。Service 持有排除目录内存缓存（onCreate 同步加载，IO 路径上顺手刷新）。
5. **PlayerActivity = singleTask + onNewIntent**：外部再次打开时复用实例，重新解析 URI 并从头播放（即便同一首歌也 seek 到 0）。
6. **临时歌曲销毁后生命周期**：mediaPlayer 不停；通知栏/MediaSession/耳机 的 next/prev 全部禁用；自然结束或用户切歌时 → 读 DataStore 中上次保存的 `CURRENT_SONG_ID`，从 DB 查这首歌，恢复全库队列，从头播放（position=0）。无保存歌曲则停止。

## 实现步骤

### 1. SongDao 增加按路径/ID 的同步查询

* 文件：[SongDao.kt](file:///d:/workspace/android_studio/UnicornPlayer/app/src/main/java/com/unicorn/player/database/SongDao.kt)

* 新增 `@Query("SELECT * FROM songs WHERE path = :path") fun getSongByPathSync(path: String): Song?`（外部入库去重）。

* 新增 `@Query("SELECT * FROM songs WHERE id = :songId") fun getSongByIdSync(songId: Long): Song?`（恢复上次保存歌曲，避开 Flow）。

### 2. MusicRepository 增加外部 URI 解析与入库

* 文件：[MusicRepository.kt](file:///d:/workspace/android_studio/UnicornPlayer/app/src/main/java/com/unicorn/player/repository/MusicRepository.kt)

* `suspend fun isPathExcluded(path: String): Boolean`：读 `scanFiltersDataStore` 的 `EXCLUDED_DIRS`，做前缀匹配。

* `suspend fun buildSongFromExternalUri(uri: Uri): Song?`：

  * 优先从 `content://media/...` URI 末段取 MediaStore \_ID；用 `_ID` 查 MediaStore（TITLE/ARTIST/ALBUM/DURATION/DATA/ALBUM\_ID/MIME\_TYPE），再用 TagLib 覆盖标签（复用 scanMusicFiles 内逻辑）。

  * 若不是 media URI，解析 `file://` → path；path 无 \_ID 时用 `-(path.hashCode().toLong().absoluteValue)` 作合成 ID（负数避免与 MediaStore 正 ID 冲突）。

  * 用 `MediaMetadataRetriever` 取 duration，`classifyQuality` 算品质，构造 `albumArt` URI。

  * 路径解析失败（非 media/file）返回 null，由调用方 Toast 提示。不设置任何持久化标记。

* `suspend fun ensureSongInDb(song: Song): Song`（永久模式）：先 `getSongByPathSync`，命中返回库内对象（保证 ID 一致、避免重复入库）；未命中 `insertSong` 后返回。临时模式不调用。

### 3. MusicService 临时播放与恢复逻辑

* 文件：[MusicService.kt](file:///d:/workspace/android_studio/UnicornPlayer/app/src/main/java/com/unicorn/player/service/MusicService.kt)

* 新增字段：

  * `@Volatile private var isTempPlayback = false`；`private var tempSong: Song? = null`；对外暴露 `tempPlayback: LiveData<Boolean>`。

  * `@Volatile private var excludedDirsCache: Set<String>`：onCreate 与播放模式一起 runBlocking 加载；`suspend fun refreshExcludedDirsCache()` 在 IO 路径刷新；`private fun isPathExcludedSync(path)` 供主线程同步判断。

* **playExternalSong(song, isTemp)**：IO 协程开头 `refreshExcludedDirsCache()`；临时 → `setSongList(listOf(song), 0)`；永久 → 查全库 `setSongList(allSongs, indexOf(song))`；主线程 `setCurrentSong` + `requestAudioFocusAndPlayCurrentSong()`（`playSongDirectly` 走 reset+prepare+start，天然从 0 开始）+ `updateNotification(song)`。

* **setCurrentSong(song)**：`if (!isPathExcludedSync(song.path)) clearTempPlayback()` —— 用户从任何入口切到普通歌曲（路径不在排除目录）即自动结束临时态，无需逐个 UI 入口处理。

* **savePlaybackState()**：开头守卫 `if (isTempPlayback) return`，不写 DataStore，保留上次保存的歌曲状态。

* **onCompletion**：`if (isTempPlayback) { resumeLastSavedSong(); return }`，其余原逻辑。

* **resumeLastSavedSong()**：刷新缓存 → 读 DataStore `CURRENT_SONG_ID` → `getSongByIdSync`；命中且文件存在 → `setSongList(allSongs, idx)`、`setCurrentSong`、`requestAudioFocusAndPlayCurrentSong()`（从 0）；未命中/文件不存在 → 清 currentSong、停止。统一经 `clearTempPlayback()` 复位。

* **通知栏构建**：`isTempPlayback` 时只加 playPause Action，去掉 Previous/Next；`setShowActionsInCompactView(0)`。

* **MediaSession actions**：临时态 `setActions(ACTION_PLAY or ACTION_PAUSE or ACTION_SEEK_TO)`，去掉 SKIP\_TO\_NEXT/PREVIOUS，耳机按键随之失效。

* **MediaSession Callback**：`onSkipToNext/onSkipToPrevious` 加 `if (isTempPlayback) return`，双保险。

* **onTaskRemoved / onDestroy**：内联写入处用 `if (!isTempPlayback)` 区分——临时歌曲不覆盖上次保存的歌曲字段，仅写 `IS_PLAYING=0`；`isMediaPlayerReleased` 等现有流程保留。

### 4. PlayerActivity singleTask + intent-filter + onNewIntent

* 文件：[AndroidManifest.xml](file:///d:/workspace/android_studio/UnicornPlayer/app/src/main/AndroidManifest.xml)

  * PlayerActivity 节点加 `android:launchMode="singleTask"`、`android:exported="true"`，新增 intent-filter：

    ```xml
    <intent-filter>
      <action android:name="android.intent.action.VIEW" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />
      <data android:mimeType="audio/*" />
    </intent-filter>
    ```

* 文件：[PlayerActivity.kt](file:///d:/workspace/android_studio/UnicornPlayer/app/src/main/java/com/unicorn/player/PlayerActivity.kt)

  * `onCreate` 末尾调用 `handleExternalIntent(intent)`；新增 `onNewIntent` → `setIntent` + `handleExternalIntent`。

  * **handleExternalIntent**：仅处理 ACTION_VIEW；IO 协程中 `buildSongFromExternalUri`（null → Toast + finish）→ `isTemp = repository.isPathExcluded(song.path)` → 永久走 `ensureSongInDb`，临时原样使用 → 主线程调 `playExternalSong`；service 未就绪时暂存 `pendingExternal`，在 `onServiceConnected` 消费。

  * **observeCurrentSong**：补充 pagerAdapter 为空或不含当前歌曲时，从 `service.getSongList()` 刷新（覆盖临时播放、临时结束切回全库两个场景）。

### 5. PlayerActivity 既有启动方式不破坏

* [BottomPlayerController.kt#L42](file:///d:/workspace/android_studio/UnicornPlayer/app/src/main/java/com/unicorn/player/BottomPlayerController.kt#L42) 仍用无 ACTION_VIEW 的 Intent 启动；singleTask 下走 `onNewIntent`，`handleExternalIntent` 提前返回，保持原「显示当前播放」行为。

## 复用现有代码

* `MusicRepository.classifyQuality` / `computeBitrate` / `TagLib.getMetadata`：复用做外部文件元数据。

* `SongDao.insertSong`（REPLACE）：仅用于永久新歌（path 不存在时，无关联可 cascade）。

* `ScanFilterConfig.shouldSkip` 的前缀匹配逻辑：`isPathExcluded` 为只查 excludedDirs 的轻量版。

* `MusicService.playSongDirectly`：reset+prepare+start 从 0 播放，直接复用。

* `MusicService.loadPlaybackState` 的 DataStore 读取模式：`resumeLastSavedSong` 参考其键名与文件存在性检查。

## 验证

1. **编译**：`gradle app:compileDebugKotlin`（类型检查、Room KAPT）。数据库版本保持 4，无迁移。
2. **场景 A（永久）**：把一首不在排除目录的歌用文件管理器打开 → PlayerActivity 启动、从 0 播放、Songs 列表立刻出现该歌、next/prev 可切、销毁后进度保存、重开应用底部播放条显示该歌并保留进度。
3. **场景 B（临时）**：把排除目录内的歌用文件管理器打开 → PlayerActivity 启动、从 0 播放、Songs 列表无该歌、UI/通知栏/耳机 next/prev 全部失效、销毁 PlayerActivity 后音乐继续、自然结束 → 自动从 0 播放 DataStore 中上次保存的那首永久歌并恢复全库队列。
4. **场景 C（onNewIntent）**：播放中再用文件管理器打开另一首 → 复用同一 PlayerActivity 实例，新歌从 0 播放。
5. **场景 D（同歌重启）**：正在播放歌 X，文件管理器再次打开歌 X → 仍然 seek 到 0 重播。
6. **场景 E（无保存歌曲）**：清空应用数据后直接用文件管理器打开一首排除目录的歌 → 销毁后自然结束时不崩溃，静默停止（DataStore 无 CURRENT\_SONG\_ID）。
7. **场景 F（排除目录设置热更新）**：播放中在 ScanFilterActivity 增删排除目录，再从文件管理器打开文件 → isTemp 按最新设置判定。
