# 常见问题与陷阱

1. **IndexOutOfBoundsException**: 始终使用歌曲 ID 进行查找，而非位置
2. **暂停时动画重置**: SongAdapter 将旋转角度保存在 `rotationAngleMap` 中
3. **搜索竞态条件**: 必须取消之前的搜索 Job
4. **服务未启动**: 先调用 `startService()`，再调用 `bindService()`
5. **内存泄漏**: 在 `onDestroy()` 中移除所有 LiveData 观察者
6. **排序模式不同步**: MusicService 的歌曲列表必须使用 `viewModel.getSortedFullSongs()` 获取排序后的列表，确保播放顺序与 UI 一致
7. **LrcView 全屏状态**: `isLrcFullscreen` 标记在 PlayerActivity 中管理，切换歌曲时需保持状态
8. **DataStore 与 SharedPreferences 选择**: 播放状态用 DataStore（异步），排序模式用 SharedPreferences（同步 commit），注意不要混淆
