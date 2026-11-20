# Echo

A native Android (Kotlin + Jetpack Compose + Media3) music and video player. Plays what is on your phone and streams from YouTube, with background playback, lyrics and playlists.

## Install

Download [Echo.apk](https://github.com/rave10x/echo-android/releases/latest/download/Echo.apk) (about 5 MB, Android 8.0+) and open it. Allow "Install unknown apps" for your browser or file manager when asked.

## Features

- **Home** with recommendations: songs you left off on, "Because you played …", "More from <artist>", and shelves of well-known songs (rock anthems, pop, Bollywood, classics…)
- **Local library** of songs, albums and artists. Short clips and call recordings / voice notes / ringtones are hidden (configurable)
- **Background playback** with a real music notification (previous, play/pause, next, seek, like, shuffle), lock-screen and Bluetooth controls
- **Lyrics**: synced lyrics from [LRCLIB](https://lrclib.net) or a `.lrc` file next to the song, cached on the device
- **Playlists**: create, rename, reorder, liked songs, recently and most played
- **Videos**: local videos, plus YouTube with an audio-only / video switch
- **Search / paste a link** for YouTube videos and playlists. Share a link to Echo from YouTube or a browser
- Queue, sleep timer, playback speed, resumes where you left off

## Build

Needs the Android SDK (minSdk 26) and JDK 17+.

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # R8-shrunk APK, signed with the debug key for sideloading
```

YouTube data and streams come from [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).

## Disclaimer

Echo is an unofficial client and is not affiliated with YouTube or Google. Use it in line with the terms of the services you access.

## License

MIT
