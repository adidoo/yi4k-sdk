# yi4k-sdk

Kotlin/JVM library that speaks the Yi 4K Action Camera's Wi-Fi control protocol: connect to
the camera's own hotspot, send commands (photo, record, settings) and get the RTSP URL for
its live preview. No Android dependency — usable from an Android app, a desktop app, or a
CLI tool.

This is the companion library for [`yi4k-remote-android`](https://github.com/adidoo/yi4k-remote-android),
but it doesn't depend on it in any way.

## Status: reverse-engineered, unofficial

Xiaoyi/YI never published a protocol spec for this exact model. This implementation is
based on:

- A community write-up of the older Yi Action Camera's JSON protocol (same IP/port).
- Cross-checking against YI's own official Android SDK
  ([`YITechnology/YIOpenAPI`](https://github.com/YITechnology/YIOpenAPI)), whose sample app
  connects to the same `tcp://192.168.42.1:7878` endpoint for the Yi 4K.
- A working Node.js client ([`mariomka/yi-action-camera`](https://github.com/mariomka/yi-action-camera))
  that confirmed the exact message IDs used below.

Some message IDs and event names may not match your exact firmware version. If a command
times out, capture traffic from the official YI app while it does the same action and adjust
`YiProtocol.kt` accordingly — PRs welcome.

## Protocol summary

- Connect a TCP socket to `192.168.42.1:7878` (the fixed IP of the camera's own access point).
- Every message is a JSON object, written back-to-back on the stream (no delimiter).
- First request a token (`msg_id: 257`), then include it as `"token"` in every later request.
- The camera also pushes unsolicited status events at any time as `msg_id: 7` objects, e.g.
  `{"msg_id":7,"type":"photo_taken","param":"/tmp/fuse_d/DCIM/.../YDXJ0001.jpg"}`.

| msg_id | Meaning                  |
|--------|---------------------------|
| 1      | Get one setting           |
| 2      | Set one setting            |
| 3      | Get all settings           |
| 5      | Get SD card free/total space |
| 7      | (push) status event        |
| 9      | Get available choices for a setting |
| 13     | Get battery level           |
| 257    | Request a session token     |
| 259    | Start the live stream (viewfinder) |
| 260    | Stop the live stream         |
| 513    | Start recording              |
| 514    | Stop recording                |
| 769    | Take a photo                   |
| 1281   | Delete a file                    |

## Usage

```kotlin
val camera = YiCameraController()

camera.connectionState.collect { state ->
    when (state) {
        is CameraConnectionState.Connected -> println("Connected: ${state.settings}")
        is CameraConnectionState.Failed -> println("Failed: ${state.message}")
        else -> {}
    }
}

camera.connect()               // suspend fun; connects + fetches settings + token handshake
val rtspUrl = camera.startLiveView()
camera.takePhoto()
camera.startRecording()
camera.stopRecording()
camera.disconnect()
```

## Building

```
./gradlew build
```

## License

MIT — see [LICENSE](LICENSE).
