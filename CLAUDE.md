# Unicorn Player - 开发指南

## 项目简介

Android音乐播放器，使用Material Design风格，提供音乐文件浏览、搜索、播放等功能。

## 当前功能状态

✅ **已完成的组件:**
- Room数据库管理音乐文件(SongDao, PlaylistDao)
- 扫描设备音乐文件
- 主界面展示音乐列表和搜索功能
- 播放界面控件操作
- 背景音乐服务及通知栏操作
- Material Design 3 主题
- LiveData及MVVM架构
- 使用RecyclerView展示列表

## 关键实现细节

### 音乐扫描
```kotlin
// 使用MediaStore API扫描音乐文件
context.contentResolver.query(
    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
    projection,
    selection,
    null,
    MediaStore.Audio.Media.TITLE + " ASC"
)
```

### 服务绑定
```kotlin
// MainActivity绑定MusicService
bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
```

### 交互式UI
```kotlin
// ViewModel为UI暴露LiveData
val allSongs: LiveData<List<Song>> = _allSongs
```

### 数据库关联
```kotlin
// 播放列表和歌曲多对多关联
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
```

## 代码结构

```
src/main/java/com/unicorn/player/
├── MainActivity.kt           # Entry point with music list
├── PlayerActivity.kt         # Full-screen player UI
├── model/
│   ├── Song.kt              # Music track data
│   └── Playlist.kt          # Playlist data
├── database/
│   ├── SongDao.kt           # Song CRUD operations
│   ├── PlaylistDao.kt       # Playlist operations
│   └── MusicDatabase.kt     # Room configuration
├── repository/
│   └── MusicRepository.kt   # Data access layer
├── viewmodel/
│   ├── MusicViewModel.kt    # UI state management
│   └── MusicViewModelFactory.kt
├── adapter/
│   └── SongAdapter.kt       # RecyclerView adapter
└── service/
    └── MusicService.kt      # Background playback
```

## 开发指导

### 添加新特性
1. **修改数据库**: 更新entities → 创建数据迁移 → 更新DAOs
2. **修改UI**: 更新layouts → 修改ViewModel → 修改Activities
3. **修改服务**: 测试后台行为 → 更新通知栏

### 代码风格
- 遵从Kotlin编码规范
- 使用有意义的变量名
- 复杂逻辑添加注释
- 遵从Material Design规范

## 通用问题&解决方案

### 权限处理
```kotlin
// 总是检查和申请权限
if (ContextCompat.checkSelfPermission(...) != PERMISSION_GRANTED) {
    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
}
```

### 服务生命周期
```kotlin
// 正确bind/unbind服务
override fun onDestroy() {
    if (isServiceBound) {
        unbindService(serviceConnection)
        isServiceBound = false
    }
}
```

### 数据库操作
```kotlin
// 数据库操作使用协程
viewModelScope.launch {
    repository.scanMusicFiles()
}
```

## 性能考量

- **Lazy Loading**: RecyclerView能有效加载大量文件
- **Database Caching**: Room自动缓存查询结果
- **Background Operations**: 使用协程扫描数据库
- **Memory Management**: 应用退出时释放MediaPlayer资源

## 扩展功能点

### 容易增加:
1. **Equalizer**: 添加均衡器API
2. **Themes**: 提供暗色模式的资源
3. **Widgets**: 创建app widget能够快速操作
4. **Lyrics**: 在PlayerActivity中增加歌词显示

### 适当的复杂度:
1. **Playlists**: 实现播放列表管理功能
2. **Cloud Sync**: 提供云端存储功能
3. **Audio Effects**: 实现音频处理功能
4. **Social Features**: 歌曲分享，创建协同的播放列表

### 高级特性:
1. **Smart Playlists**: 根据播放习惯自动生成播放列表
2. **Audio Analysis**: BPM检测，关键分析
3. **Streaming**: 集成在线流媒体播放
4. **Cross-device Sync**: 多设备播放同步

## 依赖管理

### 核心依赖:
- **Material Design 3**: UI组件和主题
- **Room**: 数据库操作
- **Lifecycle**: MVVM架构
- **Media**: 音乐播放能力

### 可选增强:
- **Glide/Picasso**: 加载专辑图片
- **ExoPlayer**: 提供媒体播放高级特性
- **WorkManager**: 后台任务调度
- **DataStore**: 最新的preferences存储

## 调试建议

1. **数据库问题**: 使用Android Studio的数据库Inspector
2. **服务问题**: 检查通知和后台服务状态
3. **内存泄漏**: 使用Android Profiler监测
4. **性能问题**: 使用Layout Inspector优化UI

## 下个开发周期

### 优先级1 - 用户体验:
- [ ] 实现播放列表的创建和管理
- [ ] 添加随机和重复播放模式
- [ ] 提升专辑艺术展示
- [ ] 添加播放速度控制

### 优先级2 - 特性:
- [ ] 定时睡眠功能
- [ ] 音频均衡器
- [ ] 歌词显示
- [ ] 淡入淡出切换

### 优先级3 - 润色:
- [ ] 暗色模式支持
- [ ] 创建桌面小部件
- [ ] 设置界面
- [ ] 导入/导出播放列表

## 测试Checklist

- [ ] 音乐扫描要考虑不同的Android版本
- [ ] 应用界面关闭时要保持后台播放
- [ ] 通知栏控件正常工作
- [ ] 搜索功能能正确过滤
- [ ] 数据库操作能处理大数据量的乐库
- [ ] 不同的手机屏幕UI展示正常
- [ ] 后台服务要考虑系统资源约束
- [ ] 优雅地处理权限拒绝

本指南应在保持既定架构和代码质量标准的同时，有效推进开发工作。