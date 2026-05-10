# Android Music Player

A modern Android music player application built with Kotlin, following Material Design 3 guidelines and using modern Android architecture components.

## Features

- 🎵 Browse and play local music files
- 🔍 Search music by title, artist, or album
- 📱 Modern Material Design 3 UI
- 🎮 Background music playback with notification controls
- 💾 Room database for caching music metadata
- 🔄 MVVM architecture with LiveData and ViewModel
- 📻 Media session integration
- 🎨 Dark/Light theme support

## Architecture

The app follows the MVVM (Model-View-ViewModel) architecture pattern with the following components:

### Model Layer
- **Song**: Data class representing a music track
- **Playlist**: Data class for user-created playlists
- **MusicRepository**: Handles data operations and music scanning
- **Room Database**: Local storage for music metadata

### View Layer
- **MainActivity**: Main screen with music list and search
- **PlayerActivity**: Full-screen player with controls
- **SongAdapter**: RecyclerView adapter for music list
- **Material Design 3**: Modern UI components and theming

### ViewModel Layer
- **MusicViewModel**: Manages UI state and data operations
- **LiveData**: Reactive data observation
- **Coroutines**: Asynchronous operations

### Service Layer
- **MusicService**: Background music playback service
- **MediaPlayer**: Android's media playback engine
- **Notification**: Media controls in notification

## Key Components

### Database Schema
- **Songs table**: Stores music metadata (title, artist, album, duration, path)
- **Playlists table**: User-created playlists
- **PlaylistSongs table**: Many-to-many relationship between playlists and songs

### Permissions
- `READ_EXTERNAL_STORAGE`: Access music files
- `WAKE_LOCK`: Keep device awake during playback
- `FOREGROUND_SERVICE`: Background music service
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Media-specific foreground service

### Dependencies

```gradle
dependencies {
    // Core Android libraries
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    
    // Architecture components
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.7.0'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    
    // Media playback
    implementation 'androidx.media:media:1.7.0'
    
    // Room database
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'
}
```

## Usage

1. **First Launch**: The app will request storage permission to scan for music files
2. **Music Scanning**: Automatically scans device for music files on first launch
3. **Browse Music**: View all songs in a scrollable list with search functionality
4. **Play Music**: Tap any song to start playback
5. **Player Controls**: Access full player screen with seek bar and playback controls
6. **Background Play**: Music continues playing with notification controls

## Project Structure

```
app/src/main/java/com/unicorn/player/
├── MainActivity.kt           # Main screen with music list
├── PlayerActivity.kt         # Full-screen player
├── model/
│   ├── Song.kt              # Song data class
│   └── Playlist.kt          # Playlist data class
├── database/
│   ├── SongDao.kt           # Song database operations
│   ├── PlaylistDao.kt       # Playlist database operations
│   └── MusicDatabase.kt     # Room database configuration
├── repository/
│   └── MusicRepository.kt   # Data repository
├── viewmodel/
│   ├── MusicViewModel.kt    # UI state management
│   └── MusicViewModelFactory.kt
├── adapter/
│   └── SongAdapter.kt       # RecyclerView adapter
└── service/
    └── MusicService.kt      # Background music service
```

## Technical Highlights

- **Material Design 3**: Latest design system with dynamic colors
- **Room Database**: Efficient local storage with reactive queries
- **MediaPlayer API**: Robust audio playback with lifecycle management
- **Foreground Service**: Proper background playback with notifications
- **MVVM Pattern**: Clean architecture with separation of concerns
- **Kotlin Coroutines**: Modern asynchronous programming
- **LiveData**: Reactive UI updates
- **ViewBinding**: Type-safe view references

## Future Enhancements

- [ ] Playlist management (create, edit, delete)
- [ ] Shuffle and repeat modes
- [ ] Equalizer settings
- [ ] Lyrics display
- [ ] Album art fetching
- [ ] Sleep timer
- [ ] Audio effects
- [ ] Cloud sync integration
- [ ] Dark theme toggle
- [ ] Widget support

## Build Instructions

1. Open project in Android Studio
2. Sync Gradle dependencies
3. Build and run on device/emulator
4. Grant storage permissions when prompted

## Requirements

- Android API 24+ (Android 7.0+)
- Kotlin 1.9.10+
- Android Gradle Plugin 8.2.0+
- Material Design 3 components

## License

This project is created for educational purposes and demonstrates modern Android development practices.