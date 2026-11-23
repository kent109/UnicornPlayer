**UnicornPlayer** is a lightweight, smooth, and thoughtful music player. This guide will walk you through every feature.

### 🎵 Playing Music

**Scan music:** On first launch, the app automatically scans your device for music files. You can also pull-to-refresh or tap **Scan now** to trigger a manual scan.

**Play a song:** Tap any song in the list to start playback. A mini player bar at the bottom shows the current track and playback controls.

**Playback controls:**
- Tap the Play/Pause button to toggle playback
- Tap Previous/Next to switch songs
- Tap the mini player bar to enter the full-screen player
- Swipe left or right on the full-screen player to switch songs

**Player page features:**
- **Play mode:** Tap the loop button to cycle through playback modes (synced with the home screen player bar)
- **Playback queue:** Tap the list button to open the queue window. The header shows the song count for the current source. Tap any song to switch. Enable custom sorting to drag-and-drop reorder on long press.
- **Sort:** Tap the sort button to choose how the queue is ordered. The current queue reorders instantly without interrupting playback. The setting is synced with the home screen sort. When **Custom sort** is selected, the queue follows the drag order for this source.
- **More:** Tap the more button to open the song detail dialog — edit tags or add to playlist
- **Share:** Tap the share button in the top bar to share the current song file directly

**Locate song:** After the list settles, tap the locate icon in the bottom-right to scroll the currently playing song to the top.

**Delete song:** Hides the song globally. It will **not** reappear on scan or refresh by default. To restore, enable **Scan deleted songs** in **Settings → Scan filters**, then rescan.

**Bluetooth:** Control playback and audio focus via Bluetooth headphones (requires Bluetooth permission).

**Album art:** If the music file has embedded album art, the mini player bar and full-screen player will display it.

**Playback engine:** Built on AndroidX Media3 ExoPlayer, with system media notifications, Bluetooth headset controls, audio focus management, and queue management.

**Supported formats:** MP3, M4A/AAC, FLAC, WAV, OGG, OPUS, AIFF, ALAC and more. Includes a software decoder for **lossless APE** format.

### 📁 Playing External Files

**How to open:** Select an audio file in your file manager and choose this app — playback starts from the beginning.

**Behavior:**
- **Song already in library:** Auto-locates the song and plays the full song list
- **Song not in library:** Plays only this file; no progress is saved

**Play source:** External playback does not change the current play source. When you exit, if the last song is not in the current source, the source resets to **All songs**.

**Default app:** Set UnicornPlayer as your default music player in your phone's app settings, so audio files in the system file manager open directly in the app.

### 🔄 Play Modes

Tap the play mode button to cycle through:
1. **Loop all:** Restarts from the beginning after the list finishes
2. **Loop one:** Repeats the current song
3. **Shuffle:** Randomly picks the next song
4. **Sequential:** Plays in list order, stops after the last song

### 🔔 Notification Controls

A media notification appears while playing, supporting:
- Play / Pause
- Previous / Next
- Stop playback

### 🎨 Quality Tags

The app auto-detects audio quality and displays a colored tag:
- **SQ (Super Quality):** Lossless formats (FLAC, APE, WAV, ALAC, DSD)
- **HQ (High Quality):** MP3 ≥320kbps or AAC/OGG ≥256kbps
- **STD (Standard):** 128kbps ≤ bitrate < 320kbps
- **ORD (Normal):** Bitrate < 128kbps

Tags appear both in song lists and on the full-screen player.

### 🔍 Search Music

Tap the search icon on the home screen and enter any keyword (local songs only). Matches:
- Song title
- Artist name
- Album name

### 🗂️ Song Categories

Browse music by different dimensions:
- **All songs:** Shows every scanned music file
- **Artists:** Grouped by artist. Long-press an item to open the artist info dialog
- **Albums:** Grouped by album. Long-press an item to open the album info dialog
- **Playlists:** Shows all custom playlists

Double-tap the blank area of the top toolbar to scroll the list to the top.

### 🖼️ Custom Images

Artists, albums, and playlists support custom images to replace the default icon.

**Artist avatar / Album cover:** Long-press the artist or album, tap **Change avatar** / **Change cover** in the dialog, then pick an image. The item shows the new image instantly. Long-press again to re-set, or tap **Default** to restore the original icon.

**Playlist cover:** In the new/edit playlist dialog, tap **Change** to select an image. The dialog preview refreshes; tap **OK** to save. If the playlist already has a custom image, a **Default** button appears — tap it to clear the image and restore the original (also requires **OK** to confirm).

**Notes:**
- Artists and albums are matched by name. Merged items with the same name share one image, independent from the player page cover setting.
- If an artist/album no longer has songs after deletion, its image is not cleared — it can be reused if another artist/album with the same name appears later.
- Images are stored locally only. Exported playlists do **not** include images. Images are lost on data clear or reinstall.
- If you see *"Failed to read image, please try again"*, the image cannot be decoded — try another one.

### ✅ Multi-Select

Tap the multi-select button in the toolbar on the Songs / Artists / Albums / Playlists page to batch-select items:

**Multi-select actions:**
- **Select all / Deselect all:** Tap **Select all** to select every item in the current list
- **Cancel:** Return to the current page
- **Delete:** Delete selected items (songs: hide; playlists: remove)
- **Add to playlist:** Add selected items to a target playlist

**Supported batch operations:**
- **Songs page:** Selected songs can be deleted or added to a playlist
- **Artists page:** Selected artists and all their songs can be added to a playlist
- **Albums page:** Selected albums and all their songs can be added to a playlist
- **Playlists page:** Selected playlists can be batch-deleted or batch-exported

**Sub-page multi-select:**
- **Single artist page:** Selected songs can be added to a playlist
- **Single album page:** Selected songs can be added to a playlist
- **Single playlist page:** Selected songs can be batch-deleted

**Tips:**
- **Exit multi-select:** Tap **Cancel** or use system back
- **Scroll to top:** Double-tap the title bar in multi-select mode
- **Auto-hide:** The multi-select button hides automatically when the page is empty, and reappears when content is present

### ⚗️ Scan Filters

Customize scan rules in **Settings → Scan filters** to control which files enter the library:

- **Skip audio shorter than 30 seconds:** When enabled, skips files shorter than 30 seconds
- **Skip audio smaller than 100KB:** When enabled, skips files smaller than 100KB
- **Skip unsupported formats:** When enabled, skips formats that cannot be decoded or fail playback. The subtitle shows unsupported extensions. Requires a rescan after toggling
- **Scan deleted songs:** Off by default. Songs hidden from the list are filtered out on scan. Turn on to restore them on the next scan
- **Excluded directories:** Choose directories to ignore. Audio files in these directories are skipped
- **Included directories:** Choose directories to scan. When set, only these directories are scanned

**Directory rules:**
- Excluded directories take priority over included directories
- If both rules match, **Excluded** wins
- When included directories are empty, all directories are scanned (excluded rules still apply)

Changes take effect on the next scan or pull-to-refresh.

### 🚫 Unsupported Format Tags

The app auto-detects audio formats your device cannot decode (device-specific) and marks the corresponding songs as **Unsupported** in the song list.

**Tag sources:**
- Auto-detected after the first scan based on device decoding capability
- Auto-added after a playback failure

**Behavior:** Tapping an unsupported format prompts *"This format is not yet supported"*, and the play button returns to the paused state.

**Remove completely:** Enable **Skip unsupported formats** in **Settings → Scan filters**, then rescan. These songs will no longer appear. Turn off and rescan to restore.

### 🔢 Song Sort

Tap the sort button in the toolbar to reorder songs:

- **By time:** Most recently modified first
- **By title:** Alphabetical by song title
- **By artist:** Alphabetical by artist name
- **Custom:** Adds manual drag order on top of the current base sort. The initial order does not change until you drag.

The selected mode shows a red checkmark. Sort settings are saved automatically and persist across app launches.

Sort applies to the full song list and within each artist/album group. The full-screen player also has a sort button — changes reorder the queue instantly without interrupting playback. Sort settings stay synced.

**Custom sort:** Long-press a song in the list, drag up/down, and release to apply. Each list (All songs, each artist, each album, each playlist) has its own independent order.

- When the playing song's list is dragged, the queue follows the new order — playback is not interrupted
- The queue window on the player page supports long-press drag; after release, the order is saved to the current source (songs, artist, album, or playlist)
- Dragging is disabled in search or multi-select mode
- New songs after a rescan are appended to the end (sorted by the current base mode among themselves)
- After switching back to one of the three standard modes, re-selecting **Custom sort** restores the previous drag order
- When importing a playlist, the order from the import file is used

### 📝 Playlist Management

**Create playlist:** Tap the new button on the Playlists page and enter a name.

**Add songs:** In the song detail dialog or artist/album info dialog, choose **Add to playlist** and pick the target playlist. If no playlist exists yet, you'll be prompted to create one first.

**Edit playlist:** Long-press or swipe left on a playlist to reveal actions. Enter the playlist to delete songs or rename it.

**Export playlist:** For backup. Swipe left on a playlist and tap **Export**, or batch-export in multi-select. Empty playlists can also be exported. Up to 10 playlists can be exported; when the limit is reached, a cleanup dialog appears. If a file with the same name already exists, you'll be prompted to **Merge** or **Overwrite** — both options save from the current playlist.

**Import playlist:** Restore playlists after data clear or reinstall. Tap **Import** on the Playlists page and check the playlists to import. Songs that are no longer in the library are skipped automatically; if the target playlist already exists, songs are merged — otherwise a new playlist is created.

**Refresh playlist:** Pull-to-refresh on the Playlists list to reload playlist info.

**Delete playlist:** Use the playlist actions or the playlist multi-select interface. Note: deleting a playlist also removes its exported file.

**Play history:** When playing songs from a playlist, the app will continue playing that playlist after restart.

### ✏️ Edit Song Info

**Edit tags:** Open the song detail dialog, tap the edit button in the top-right to modify the song title, artist, album, and genre. Title, artist, and album can be edited inline. Tap genre to enter the picker and choose from built-in genres. Tap **OK** to save — the app modifies the audio file tags directly.

**Change cover:** On the full-screen player, tap **Change cover** at the top. Tap **Select cover** to pick an image, then tap **Apply** to confirm. The image is compressed and stored in app data — the audio file itself is not modified. After a rescan, the cover is preserved. It is lost only when app data is cleared. A **Default cover** button appears when a custom cover is set — tap it to restore the cover embedded in the audio file.

**Supported formats:** MP3, FLAC, APE, WAV, M4A/AAC/ALAC, OGG, OPUS, AIFF, WMA and more.

**Permission note:** Due to Android scoped storage restrictions, write permission is required the first time you edit tags. After granting, modifications complete automatically — no re-authorization is needed for subsequent edits to files in the same directory.

**Notes:** Tags are written directly to the audio file (if a field is cleared, the tag displays "\<unknown\>"). After editing, the system media library refreshes automatically. If you edit from inside a category directory, go back up one level to see the updated category (new category name, song count, etc.).

### 🎤 Lyrics

**Auto-download:** When playing a song, the app first looks for a matching .lrc file in the lyrics directory. If none exists, it tries to download from the internet. Once found, lyrics load into the lyrics view automatically.

**Manual search:** Long-press the lyrics area at the bottom of the player page, or tap the lyrics icon in the top bar to enter the search interface. Fuzzy search is supported by artist and song title (at least one field is required). Long-press a search result to edit the content; tap to open the apply dialog — if you confirm, local lyrics are overwritten.

**Simplified Chinese conversion:** Auto-downloaded or edited-and-saved lyrics are automatically converted to simplified Chinese. The lyrics filename stays the same as the song filename (no traditional-to-simplified conversion on filenames).

**Full-screen lyrics:** Tap the lyrics area at the bottom of the player page to enter full-screen lyrics view.

**Edit lyrics:** Long-press the full-screen lyrics view to open the edit dialog. You can modify lyrics content and timing. The `[offset:+/-ms]` tag adjusts the global time offset. Saving with empty content deletes the local lyrics file.

**New lyrics:** If lyrics fail to load, tap the **New lyrics** button in the lyrics area to open a blank dialog. Paste the LRC content (including timestamps) and save. Saving blank content does not create a lyrics file.

**Enable lyrics:** Lyrics are off by default. Enable **Show lyrics** in Settings, or tap the **Enable lyrics** button in the lyrics area. You'll be guided to grant lyrics directory permission if needed.

**Lyrics settings:** In Settings you can adjust:
- Show lyrics (requires lyrics directory permission)
- Timestamps visibility
- Font size (Small / Medium / Large)

### 🎛️ Equalizer

Built-in custom 10-band equalizer (fixed frequencies: 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz), each band with ±15dB range.

**Equalizer controls:**
- **10 bands:** Independent sliders for each frequency. The top grid draws the frequency response curve in real time as you drag.
- **Presets:** **Custom**, **Flat**, and 24 built-in presets
- **Bass boost:** Adjustable bass intensity
- **Virtualizer:** Surround strength interpolates across 6 reverb spaces (Small room / Medium room / Large room / Medium hall / Large hall / Plate). The later the preset, the larger the space and the longer the tail.

**Usage:**
- Equalizer effects only apply when the switch is on. The switch state, preset, band gains, and both knob positions persist automatically and are restored on app launch.
- Entering the page before playback starts: All settings are shown in a disabled state (switch can still be toggled early). The overlay is removed and effects applied once playback starts.
- Tap the preset dropdown to choose a built-in mode, or choose **Custom** to freely adjust. The dropdown shows up to 11 items with scroll to browse the rest.
- Dragging a band slider while any built-in preset is active immediately switches to **Custom** and restores the previous custom band values.
- If you switch to another preset or leave the page mid-edit, a dialog appears asking whether to save your custom changes. Tap **Save** to overwrite, **Discard** to abandon.
- The bottom gauge-style dials control Bass Boost and Virtualizer — slide along the arc to adjust. Virtualizer is a software reverb independent of the vendor's audio chain; the space and tail length change smoothly as you turn.
- Turning off the master switch immediately stops the 10-band coloring (configuration values are retained). Turn it back on to restore.
- **Quick toggle:** Long-press the blank area of the home screen top bar, or long-press the app icon on your launcher and tap the shortcut.

**Preset tips:**
- **Pop:** Slightly boost mid-highs, moderate bass
- **Classical:** Emphasize mids, overall balanced
- **Rock / Metal:** Strong bass and mid-bass boost
- **Vocal / Podcast:** Reduce lows, boost vocal fundamental and clarity
- **Electronic / Dance / Punk:** Boost lows and highs for rhythm impact

**Export / Import:** The equalizer page has import and export buttons at the top. Save your current custom configuration (10-band gains + Bass Boost + Virtualizer) as a file for backup or restoration after data clear or reinstall.

- **Export:** Tap export → enter a filename (auto-trimmed) → tap **OK** to check for duplicates → save to external storage
- **Export limit:** Up to 10 files in the config directory. When the limit is reached, a cleanup dialog appears — check old files to delete, then export again
- **Import:** Tap import → all config files in the directory are listed → select one → tap **OK** to load
- **Import errors:** If the file has a wrong format, incompatible version, or is not 10 bands, an **Import error** dialog appears. You can choose to delete the file directly (old 5-band configs are incompatible and need to be re-adjusted)
- **Import behavior:**
    - Equalizer switch is off: data is written to persistence only — applied automatically when the switch is turned on next
    - Equalizer switch is on: data is written + automatically switches to **Custom** + refreshes all band, Bass Boost, and Virtualizer controls
- **Directory permission:** Export and import require permission to access `Primary storage/Download/Unicorn/Equalizer` on first use

### 🌓 Theme

**Dark mode:** Follows the system setting by default. You can also manually choose Light or Dark in **Settings → Theme**.

**Accent color:** In **Settings → Theme → Accent color**, three modes are available:
- **System:** Uses the system accent color
- **Random:** Auto-switches to a new accent color every day; no repeats across consecutive days
- **Fixed:** Pick one of 7 preset colors for long-term use

**Highlight color:** In **Settings → Theme → Highlight color**, used to mark the currently playing song / artist / album / playlist / lyrics / dropdown options. Two modes:
- **System:** Uses the system highlight color (red by default)
- **Fixed:** Pick one of 7 preset colors (same palette as accent color)

7 preset colors: Red, Orange, Yellow, Green, Cyan, Blue, Purple. Changes take effect immediately after selection.

**Icon appearance:** In **Settings → Theme → Icon appearance**, switch the song list icon style:
- **Cassette:** Cassette shape with spool rotation animation during playback
- **Disc:** Disc shape with rotation animation during playback

Changes take effect immediately; icons in both the song list and queue switch together.

**Bottom panel:** In **Settings → Theme → Bottom panel**, switch the bottom panel style (for the player bar and multi-select action panel):
- **Card:** Rounded card style with side margins
- **Banner:** No rounding, fills the full screen width; button positions unchanged

Changes take effect immediately.

**Dialog background:** In **Settings → Theme → Dialog background**, switch the background of dialogs (queue, song info, add to playlist, lyrics preview, etc.):
- **System:** Dialogs use a unified light/dark background, not following accent color (default)
- **Follow theme:** Dialog background follows the current accent color. Each of the 8 accent colors has a corresponding sampled color.

### 📤 Share Music

In the song detail dialog, tap **Share local file** to share the music file with other apps. The full-screen player top bar also has a share button that shares the current song directly.

### 👥 Share with Friends

Tap **Share with friends** in the Settings page to share the app with others. Includes:
- App QR code
- Default recommendation text
- Supports WeChat, QQ, Moments, and other third-party apps

### ⬆️ Check for Updates

Tap the version number in Settings to manually check for new versions:
- **Update available:** A dialog shows the version number and changelog; once download finishes, install starts automatically
- **Already up to date:** Prompt that you are on the latest version
- **Install permission:** First-time installation requires **Allow unknown apps to be installed** permission; authorization continues the install
- **Cached installers:** Downloaded installers that are not yet installed are kept locally — they can be installed directly on the next check
- **Auto-check:** An **Auto-check for updates** toggle in Settings — when enabled, checks for new versions on every fresh app launch

### 🧹 Clear Storage

Tap **Clear storage** in Settings to clean app cache and downloaded update files:
- **Cleared content:** App cache directory, external cache directory, and downloaded update installers
- **Confirmation dialog:** A confirmation dialog appears before clearing to prevent accidents
- **Safe:** Clearing cache does **not** delete local music files or lyrics, and does not affect playlist data

### 💬 Feedback

Tap **Feedback** in Settings to send us a question or suggestion:
- **Feedback type:** Choose a category (Feature suggestion, Bug report, Usage suggestion, etc.)
- **Description:** Briefly describe the problem
- **Details:** Enter a detailed description or suggestion
- **Contact:** Enter your contact (email, QQ, etc.) or leave it anonymous so we can get back to you

Feedback automatically includes device model and app version. We do not and have no permission to collect phone number, IP address, location, or other private information.

### ❓ FAQ

**Q: Why are some songs missing?**
A: The app filters audio files based on your scan settings. Make sure the file format is valid and file size meets the requirements.

**Q: Album art is not showing?**
A: Several possibilities: 1. The music file has no embedded image and no cover was set manually on the player page. 2. Some format files may have parsing issues.

**Q: Lyrics not showing?**
A: Make sure the lyrics file is in the `Download/Unicorn/Lyrics` directory with a matching filename, and that the lyrics display switch is enabled.

**Q: Will lyrics be removed when I uninstall the app?**
A: Clearing app data or uninstalling does **not** delete local lyrics. Use a file manager to delete them from the lyrics directory.

**Q: Playback pauses suddenly?**
A: This is usually caused by another app taking audio focus (incoming call, Bluetooth disconnection, navigation announcements, etc.) — this is normal behavior.

---

### 📌 App Info

**Platform:** Android phones (64-bit)  
**System requirement:** Android 10.0 and above  
**Developer:** Kent007  
**Contact email:** [ldw5109@gmail.com](ldw5109@gmail.com)
