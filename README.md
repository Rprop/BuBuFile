# BuBuTrim

A lightweight Android video trimming app. Pick videos from the local gallery or open them via the system share sheet, select a segment on a visual timeline, and export the clip.

| Property | Value |
|----------|-------|
| Application ID | `rprop.app.video` |
| Min SDK | Android 10 (API 29) |
| Target SDK | Android 15 (API 35) |
| Language | Java 21 |

## Features

- **Video browser**: Loads local videos from MediaStore and displays them in a grid with cover art and duration
- **Multiple entry points**: In-app picker, system file picker (`ACTION_OPEN_DOCUMENT`), and share intent from other apps (`ACTION_SEND`)
- **Visual trimming**: Video preview, thumbnail timeline, and dual-thumb RangeSeekBar for selecting start/end times
- **Duration limits**: Minimum 3 seconds, maximum 10 seconds per trim
- **Two trim modes**:
  - **Fast mode** (default): `-codec copy` — stream copy, faster processing
  - **Precise mode**: `-c:v libx264 -c:a aac` — re-encode for more accurate cut points, slower
- **Export**: Trimmed clips are saved to `Movies/VideoCut/` via MediaStore
- **Gallery actions**: Long-press a video to share or delete it

## Tech Stack

| Category | Technology |
|----------|------------|
| Build | Gradle 8.x, Android Gradle Plugin 8.13.2 |
| UI | AndroidX AppCompat, DataBinding, RecyclerView |
| Images | Glide 5.x |
| Video processing | [FFmpegKit](https://github.com/arthenica/ffmpeg-kit) |
| Thumbnails | `MediaMetadataRetriever` |

## Project Structure

```
VideoCut/
├── build.gradle              # Root build configuration
├── settings.gradle           # Module: cut
├── gradle.properties
├── gradlew / gradlew.bat
└── cut/                      # Main application module
    ├── build.gradle
    ├── libs/                 # Local FFmpegKit AAR/JAR (must be added manually)
    ├── sign.jks              # Debug signing keystore
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/rprop/app/video/
        │   ├── select/       # Video selection screen
        │   ├── trim/         # Trim screen and core views
        │   ├── utils/        # Video utilities (FFmpeg trim, thumbnail generation)
        │   └── widget/       # Custom widgets (RangeSeekBar, etc.)
        └── res/              # Layouts, strings (includes Chinese values-zh)
```

## Requirements

- JDK 21
- Android SDK (compileSdk 36, buildTools 36.1.0)
- Android NDK 29.0.14206865 (required by FFmpegKit native libraries)
- Android Studio (recommended) or command-line Gradle

## Dependency Setup

FFmpegKit is included as a local library from `cut/libs/`:

```gradle
implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])
```

Place the FFmpegKit AAR/JAR files in `cut/libs/` before building. Alternatively, uncomment the following line in `cut/build.gradle` to use Maven instead:

```gradle
implementation 'com.arthenica:ffmpeg-kit-min-gpl:6.0-2'
```

## Build & Install

```bash
# Windows
gradlew.bat :cut:assembleDebug

# macOS / Linux
./gradlew :cut:assembleDebug
```

Release build:

```bash
gradlew.bat :cut:assembleRelease
```

APK output is located at `cut/build/outputs/apk/`. The app ships only `arm64-v8a` and `x86_64` ABIs.

## Usage

1. Launch the app and grant video read permissions
2. Tap a video in the grid, or use the top button to pick a file from the system file manager
3. On the trim screen:
   - Drag the left/right thumbs to set the start and end range
   - Scroll the thumbnail timeline horizontally for longer videos
   - Tap the play button to preview the selected segment
4. Tap **Finish**, confirm start/end times in the dialog, and optionally enable **Precise mode**
5. Wait for FFmpeg to finish; the exported file is saved to `Movies/VideoCut/`

## Permissions

| Permission | Purpose |
|------------|---------|
| `READ_EXTERNAL_STORAGE` | Read local videos on Android 12 and below |
| `READ_MEDIA_VIDEO` | Read local videos on Android 13+ |

## Core Flow

```
VideoSelectActivity          VideoTrimActivity              VideoUtils
       │                            │                           │
       ├─ Load MediaStore videos     ├─ Preview & timeline UI   ├─ generateThumbnailsInBackground()
       ├─ File picker / share   ──►  ├─ RangeSeekBar selection ──► trim() → FFmpegKit
       └─ Open trim screen          └─ Save via MediaStore        └─ Write temp file to cache
```

## Trim Parameters

| Constant | Value | Description |
|----------|-------|-------------|
| `MIN_SHOOT_DURATION` | 3000 ms | Minimum trim duration |
| `MAX_SHOOT_DURATION` | 10000 ms | Maximum trim duration |
| `MAX_COUNT_RANGE` | 10 | Number of visible thumbnails on the timeline |

Output filename format: `{originalName}.{startTime}_{endTime}.mp4` (colons in timestamps are replaced with `-`).

## Notes

- Both debug and release builds are signed with `cut/sign.jks` — for development/testing only
- Release builds enable ProGuard obfuscation (`minifyEnabled true`)
- Thumbnail generation is paused during trimming to avoid competing with FFmpeg for resources
- Leaving the trim screen calls `FFmpegKit.cancel()` to abort any in-progress task

## License

No open-source license is specified. Confirm usage rights before distributing.
