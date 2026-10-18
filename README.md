# PhoneCam

Use your Android phone as a USB webcam on Windows. No driver: the camera is a Windows 11 virtual camera, so Zoom, Teams, OBS, Discord and browsers see it like any webcam.

- **Low latency**: hardware H.264 on the phone, low-latency decoding on the PC, at most two frames in flight.
- **USB only**: the stream goes through `adb reverse`, nothing is exposed on the network.
- **Configurable**: resolution up to 4K, frame rate, bitrate, keyframe interval, port, lens, portrait or landscape, autofocus.

## Requirements

- Windows 11 (the virtual camera API is not on Windows 10)
- Android 11 or later, with [USB debugging](https://developer.android.com/studio/debug/dev-options) on
- [adb](https://developer.android.com/tools/releases/platform-tools) in `PATH` or in the Android SDK

## Install

1. Download `PhoneCam-windows-x64.zip` and `PhoneCam-android.apk` from the [latest release](https://github.com/Arylite/phonecam/releases/latest).
2. Unzip, run `PhoneCam.exe` and click **Install camera** (asks for admin once).
3. Install the APK on the phone, open it and plug the phone in.

The stream starts by itself. Pick **PhoneCam** as the camera in any app.

## Settings

The PC window sets what the phone streams: resolution, frame rate, bitrate, keyframe interval and port. A new resolution applies once the app using the camera reopens it. Ticking *Keep running in the notification area* makes the close button hide the window instead of quitting.

The phone sets the lens, portrait or landscape, autofocus and preview. In portrait the image is cropped to fill the PC's format.

Everything on the PC is also in `%ProgramData%\PhoneCam\settings.ini`.

## Build

Windows, with Visual Studio 2022 (C++ workload, Windows 11 SDK) and CMake:

```sh
cmake -S windows -B windows/build -A x64
cmake --build windows/build --config Release   # windows/build/bin
```

Android, with JDK 17+ and the Android SDK:

```sh
cd android
./gradlew assembleRelease ktlintCheck lintRelease
```

How the pieces talk to each other: [docs/protocol.md](docs/protocol.md).

## Release

Push a tag like `v1.2.3`: CI builds both sides and publishes them on a GitHub release. The APK is signed with the key in the repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); forks and local builds fall back to the debug key.

## License

[MIT](LICENSE)
