# 数据持久化约束

## DataStore 与 SharedPreferences 选择

- **播放状态** 用 DataStore（异步）— `music_player_state`
- **排序模式** 用 SharedPreferences（同步 commit）— `sort_mode_prefs`
- **歌词设置** 用 DataStore（异步）— `lyrics_settings`

排序模式使用 `commit()` 同步写入，确保杀进程时不会丢失。这是有意设计的，因为排序模式需要在 `restoreSortMode()` 中同步恢复（`runBlocking`）。

## 排序模式同步

MusicService 的歌曲列表必须使用 `viewModel.getSortedFullSongs()` 获取排序后的列表，确保播放顺序与 UI 一致。

## DataStore 键（播放状态）

MusicService 在 `music_player_state` DataStore（`applicationDataStore`）中持久化：

- `CURRENT_SONG_ID` (Long) - 当前播放歌曲 ID
- `CURRENT_POSITION` (Int) - 播放位置（毫秒）
- `IS_PLAYING` (Int) - 0=暂停, 1=播放
- `SONG_TITLE` (String) - 当前歌曲标题
- `SONG_ARTIST` (String) - 当前歌曲艺术家
- `SONG_PATH` (String) - 当前歌曲文件路径
- `PLAY_MODE` (Int) - 播放模式序号（ALL_LOOP=0, SINGLE_LOOP=1, RANDOM=2, SEQUENCE=3）
- `TASK_REMOVED_FLAG` (Int) - 标记用户从最近任务移除应用，防止服务重启后恢复通知

## DataStore 键（歌词设置）

LyricsOptionsActivity 在 `lyrics_settings` DataStore（`lyricsDataStore`）中持久化：

- `LYRICS_ENABLED` (Boolean) - 是否启用歌词显示
- `TIME_LABEL_VISIBLE` (Boolean) - 时间标签是否可见
- `FONT_SIZE` (Int) - 字体大小（0=小, 1=中, 2=大）
- `COLOR_THEME` (Int) - 颜色主题（0=跟随系统, 1=浅色, 2=深色）

## SharedPreferences（排序模式）

- `sort_mode` (Int) - 排序模式序号（BY_TIME=0, BY_TITLE=1, BY_ARTIST=2），默认 BY_TITLE
