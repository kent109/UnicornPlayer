# Unicorn Player - Development Guide

## Project Overview

This is a complete Android music player application built with modern Android development practices. The app allows users to browse, search, and play local music files with a beautiful Material Design 3 interface.

## Current Implementation Status

✅ **Completed Components:**
- Song data model with Room database
- Music scanning from device storage
- Main activity with music list and search
- Player activity with full controls
- Background music service with notifications
- Material Design 3 theming
- MVVM architecture with LiveData
- RecyclerView for music list
- Database operations (SongDao, PlaylistDao)
- Repository pattern for data access

## Architecture Decisions

### Why MVVM?
- Clean separation of concerns
- Testable ViewModels
- Reactive UI updates with LiveData
- Lifecycle-aware components

### Why Room Database?
- Type-safe database queries
- Compile-time verification
- Reactive queries with Flow/LiveData
- Easy migration support

### Why Foreground Service?
- Proper background playback
- System notification controls
- Media session integration
- Battery optimization compliance

## Key Implementation Details

### Music Scanning
```kotlin
// Uses MediaStore API to scan for music files
context.contentResolver.query(
    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
    projection,
    selection,
    null,
    MediaStore.Audio.Media.TITLE + " ASC"
)
```

### Service Binding
```kotlin
// MainActivity binds to MusicService
bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
```

### Reactive UI
```kotlin
// ViewModel exposes LiveData for UI observation
val allSongs: LiveData<List<Song>> = _allSongs
```

### Database Relations
```kotlin
// Many-to-many relationship between playlists and songs
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
```

## File Structure

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

## Development Guidelines

### Adding New Features
1. **Database Changes**: Update entities → create migration → update DAOs
2. **UI Changes**: Update layouts → modify ViewModel → update Activities
3. **Service Changes**: Test background behavior → update notification

### Code Style
- Follow Kotlin coding conventions
- Use meaningful variable names
- Add comments for complex logic
- Follow Material Design guidelines

### Testing Strategy
- Unit tests for ViewModels
- Integration tests for database
- UI tests for Activities
- Service tests for MusicService

## Common Issues & Solutions

### Permission Handling
```kotlin
// Always check and request permissions
if (ContextCompat.checkSelfPermission(...) != PERMISSION_GRANTED) {
    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
}
```

### Service Lifecycle
```kotlin
// Properly bind/unbind service
override fun onDestroy() {
    if (isServiceBound) {
        unbindService(serviceConnection)
        isServiceBound = false
    }
}
```

### Database Operations
```kotlin
// Use coroutines for database operations
viewModelScope.launch {
    repository.scanMusicFiles()
}
```

## Performance Considerations

- **Lazy Loading**: RecyclerView efficiently handles large music libraries
- **Database Caching**: Room caches query results automatically
- **Background Operations**: All file scanning and database operations use coroutines
- **Memory Management**: MediaPlayer properly released in onDestroy

## Extension Points

### Easy to Add:
1. **Equalizer**: Add Equalizer API integration
2. **Themes**: Extend colors.xml with dark theme variants
3. **Widgets**: Create app widget for quick controls
4. **Lyrics**: Add lyrics display in PlayerActivity

### Moderate Complexity:
1. **Playlists**: Implement playlist management UI
2. **Cloud Sync**: Add cloud storage integration
3. **Audio Effects**: Implement audio processing
4. **Social Features**: Share songs, create collaborative playlists

### Advanced Features:
1. **Smart Playlists**: Auto-generated playlists based on listening habits
2. **Audio Analysis**: BPM detection, key analysis
3. **Streaming**: Online music streaming integration
4. **Cross-device Sync**: Multi-device playback synchronization

## Dependencies Management

### Core Dependencies:
- **Material Design 3**: UI components and theming
- **Room**: Database operations
- **Lifecycle**: MVVM architecture support
- **Media**: Audio playback capabilities

### Optional Enhancements:
- **Glide/Picasso**: Image loading for album art
- **ExoPlayer**: Advanced media playback features
- **WorkManager**: Background task scheduling
- **DataStore**: Modern preference storage

## Debugging Tips

1. **Database Issues**: Use Android Studio's Database Inspector
2. **Service Problems**: Check notification and foreground service status
3. **Memory Leaks**: Monitor with Android Profiler
4. **Performance**: Use Layout Inspector for UI optimization

## Next Development Phase

### Priority 1 - User Experience:
- [ ] Implement playlist creation and management
- [ ] Add shuffle and repeat modes
- [ ] Improve album art display
- [ ] Add playback speed control

### Priority 2 - Features:
- [ ] Sleep timer functionality
- [ ] Audio equalizer
- [ ] Lyrics display
- [ ] Crossfade between songs

### Priority 3 - Polish:
- [ ] Dark theme implementation
- [ ] App widget creation
- [ ] Settings screen
- [ ] Import/export playlists

## Testing Checklist

- [ ] Music scanning works on different Android versions
- [ ] Background playback continues when app is closed
- [ ] Notification controls work properly
- [ ] Search functionality filters results correctly
- [ ] Database operations handle large music libraries
- [ ] UI responds properly to different screen sizes
- [ ] Service properly handles system resource constraints
- [ ] App handles permission denial gracefully

This guide should help continue development effectively while maintaining the established architecture and code quality standards.