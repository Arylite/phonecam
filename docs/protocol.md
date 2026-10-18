# How PhoneCam works

```
Android app                         PhoneCam.exe                          PhoneCamSource.dll
camera -> GPU (rotate, crop)        TCP 127.0.0.1:<port>                  Media Foundation source
       -> H.264 encoder  --USB-->   H.264 decoder (low latency)  ----->   in the Camera Frame Server
          (adb reverse)             NV12 into shared memory               -> every app sees "PhoneCam"
```

## Phone to PC: TCP

The PC client runs `adb reverse tcp:<port> tcp:<port>`, so the app reaches it on `127.0.0.1:<port>` over USB. Nothing listens on the network. All integers are big-endian.

| Direction | Message | Layout |
|---|---|---|
| phone -> PC | hello | `"PCAM"`, u8 version (`1`) |
| PC -> phone | config | u16 width, u16 height, u8 fps, u32 bitrate (bit/s), u8 keyframe interval (s) |
| phone -> PC | message | u8 type, u32 length, payload |
| PC -> phone | ack | one byte per video message, sent once it is decoded |

Message types: `1` is one H.264 Annex-B access unit (SPS/PPS come with every keyframe), `2` is a UTF-8 error to show on the PC. Unknown types are skipped.

**Flow control.** The phone keeps at most two video messages without an ack. Past that it drops frames until the next keyframe, which it requests from the encoder. A slow or busy PC therefore costs a short stutter instead of delay that grows in buffers.

Settings changes on the PC close the connection; the app reconnects and receives the new config.

## PC client to camera: shared memory

The camera runs inside the Windows Camera Frame Server service, so it creates the objects (services may create `Global\` names) and the client opens them:

- `Global\PhoneCamFrame`: `SharedFrame` from [`windows/common/phonecam.h`](../windows/common/phonecam.h), a seqlock (`seq` is odd while the client writes), the format the camera was opened with, the camera's last read time, and one NV12 frame.
- `Global\PhoneCamEvent`: set by the client after each frame.

The camera sends the latest frame on every sample request, and repeats it at least every 100 ms so apps never stall.

## Settings

`%ProgramData%\PhoneCam\settings.ini`, written by the client window, read by both:

```ini
[camera]
Width=1920
Height=1080
Fps=30
[stream]
Bitrate=10000000
KeyframeInterval=1
Port=8765
[adb]
Path=
[client]
CloseToTray=0
```

The camera reads its format when an app opens it, so a new resolution applies once the app reopens the camera.
