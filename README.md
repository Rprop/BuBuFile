# BuBuFile

An Android app for viewing files and working with videos. Material Design 3 UI with light/dark theme support.

## Features

### Video

- Browse and play local videos
- Trim video clips
- Merge multiple videos (including fast merge)
- Multi-select delete and share

### Image

- View high-resolution images with pinch/double-tap zoom
- Tile decoding for large photos without loading the full bitmap into memory
- Share / copy to other apps or folders

### Text

- View text files (large files loaded in chunks)
- Word wrap and in-document search
- Share / copy to other apps or folders

### Zip

- Browse archive folders without full extract (ZIP64 supported)
- Extract a single entry to cache and open with another app
- Password support for ZipCrypto and WinZip AES

### Open files from other apps

Receive files via Android “Open with” **BuBuFile**, then pick how to open (text / image / video / zip), share, share to LAN (QR), or copy to a folder.

### LAN HTTP (port 10021)

Starts with the app. Open `http://<phone-ip>:10021/` on the same network to:

- browse / remove shared files (image size, video size·duration, APK package info)
- download with Range resume; preview text / image / video in the browser
- upload to the phone: Share To Apps / Upload Files To Download / Install Apk
- resumable chunked upload (2MB chunks, auto-retry, client-persisted batch)
- paste or drop files; hover image inputs for preview
- sync clipboard text with the phone (keep the app in foreground)
- Chinese / English and light / dark theme
