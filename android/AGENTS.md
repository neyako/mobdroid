# Mobdroid (Android)

Native Android port of Moblin: Kotlin, Jetpack Compose with Material 3 Expressive, no cross-platform
layers. The iOS sources in the repository root are the reference implementation; port behavior from
them, not code style.

## Layout

- `engine/` — pure Kotlin/JVM, no Android imports. Everything that can run and be tested on the JVM:
  muxers, transports, adaptive bitrate.
  - `media/` — `VideoFormat`, `AudioFormat`, `EncodedFrame`, `MediaSink`.
  - `net/` — `Publisher` (one-shot ingest connection), `NetworkLink`/`NetworkLinkProvider` (sockets
    pinned to an interface, used by SRTLA bonding).
  - `flv/`, `rtmp/` — FLV tags and the RTMP/RTMPS publisher. HEVC and AV1 use Enhanced RTMP.
  - `ts/` — MPEG-TS muxer (H264, HEVC, AV1, AAC).
  - `srt/` — SRT caller, a port of Moblin's own `SrtSender.swift`. `srtla/` — SRTLA bonding client,
    a port of `Moblin/Media/Srtla/Client`. `abr/` — adaptive bitrate (Belabox port).
- `app/` — the Android app: camera sources, GL compositor, MediaCodec encoders, the foreground
  streaming service and the Compose UI. `src/main/cpp/` holds the native UVC driver
  (libusb + libuvc + libjpeg-turbo).

## Media contracts

- Video frames are what MediaCodec emits: Annex-B for H264/HEVC, low overhead OBUs for AV1. The
  codec config buffer (`BUFFER_FLAG_CODEC_CONFIG`) is `VideoFormat.config`: Annex-B parameter sets for
  H264/HEVC; for AV1 either an `av1C` record (first byte `0x81`) or a bare sequence header OBU.
- Audio is AAC-LC, raw frames without ADTS; `AudioFormat.config` is the AudioSpecificConfig.
- Encoders run without B-frames, so DTS equals PTS. Timestamps are microseconds on `CLOCK_MONOTONIC`
  (`System.nanoTime`), shared by audio and video.
- `setVideoFormat`/`setAudioFormat` can be called again with the same or a new config (AV1 encoders report
  it both as a config buffer and as `csd-0`); sinks treat a repeat as idempotent.
- `MediaSink` calls come from encoder threads and never block. A `Publisher` owns a bounded backlog;
  on overflow it drops video until the next key frame before it drops audio.
- A `Publisher` is one-shot: after `stop()` or `onDisconnected` it is dead. The app's stream session
  owns reconnection with backoff.

## Transports

- `RtmpPublisher(url, streamKey, listener)`: with a non-empty key, `url` is the server URL (`app` is its
  path); with an empty key, the last path segment of `url` is the key. Default ports 1935/443. It keeps
  `EncodedFrame.data` until sent, so never reuse a frame's array. It waits up to 2 s for
  `setVideoFormat` before `connect` so it can declare the Enhanced RTMP `fourCcList`, and always starts
  at a key frame. Backlog limit is 3 s of media or 16 MiB. The key frame that ends an overflow also
  drops audio queued for longer than the limit, so audio never trails video by seconds afterwards.
- `SrtPublisher(url, listener, links)`: `srt://host:port?streamid=&latency=` or `srtla://host:port?streamid=`
  (bonded over `links`). No encryption: a passphrase is rejected. Frames before `onConnected` are
  dropped, so the session requests a key frame on connect. `NetworkLink.createDatagramSocket()` must
  return an unconnected socket.
- Both: `stop()` never calls `onDisconnected`; any other end calls it exactly once, on an internal
  thread. `stop()` blocks up to about 2 s.
- AV1 is offered only over RTMP. FFmpeg 7.1 cannot decode AV1 in MPEG-TS, so `StreamProfile.allowedCodecs`
  drops it for `srt`/`srtla` URLs. Codec choice is `CodecCatalog.choose`: the profile's codec if the
  phone has a hardware encoder for it, else the first hardware codec in the platform's priority list
  (Twitch, Kick, Facebook: H.264 only; YouTube and Custom: AV1, HEVC, H.264). Software encoders are
  never used.
- ABR: `BelaboxAdaptiveBitrate` for SRT, `QueueAdaptiveBitrate` for RTMP, ticked every
  `ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS` (20 ms) with the transport bitrate measured once a second.
  Both cap the target near the measured transport bitrate, as Moblin does. `TransportBitrateMeter` is
  new for each session and pauses while reconnecting, so time offline does not pull the cap down.

## Cameras

- `camera/`: `CameraCatalog.list` probes visible and hidden numeric camera ids (vivo, OPPO and Xiaomi
  hide auxiliary cameras), filters depth, secure, monochrome and low resolution sensors, dedupes lenses
  and derives zoom stops from field of view. It blocks for tens of milliseconds; call it off the main
  thread. `CameraController` reopens with backoff on errors, stalls and availability changes and steps
  down session options on configuration failures and on faults before the first frame. Call
  `SurfaceTexture.setDefaultBufferSize` before `open`; `open` resets zoom, torch, exposure and locks.
- Catalog rules (`buildCatalog`): a hidden logical camera replaces a visible logical one of the same
  facing when it has all of its lenses and more zoom stops (the vivo X300 lists a two lens camera and
  hides the three lens one). When a lens is wider than the zoom range reaches, the range's start is a
  stop of its own (vivo: 0.65x is a crop of the ultra wide, the full lens is 0.4x). A lens the zoom
  range cannot reach becomes its own entry: opened by its own camera id when that id can be probed,
  through a physical stream of the logical camera only otherwise. A physical stream crashes the vivo's
  Qualcomm camera provider (`ChiMcxStaticPolicy::ActivateAuxCameraStateAndMask`) at every fallback level.
- A camera provider restart takes about 6 s, during which `getCameraCharacteristics` throws. That is a
  recoverable fault retried with backoff; an id `openCamera` rejects is not.
- Samsung does not advertise 60 fps in the Camera2 AE ranges on most models. Its vendor table
  `samsung.android.scaler.availableVideoConfigurations` (6 ints per entry: width, height, min fps, max fps
  and two unused) lists the real video frame rates, read by `samsungVideoFps` into
  `CameraDescriptor.vendorFpsBySize`. When the AE ranges cannot reach the profile's rate,
  `CameraController` sets `samsung.android.control.recordingMaxFps`/`recordingMinFps` with a fixed AE
  range, and drops them at the last fallback level. The ultra wide lens still delivers 30 fps, and the
  camera takes a few seconds to return to 60 after zooming back from it.
- Qualcomm phones (vivo, and likely OPPO and Xiaomi) list 60 fps in
  `org.quic.camera2.customhfrfps.info.CustomHFRFpsTable` (4 ints per entry: width, height, fps and one
  unused), read by `qualcommVideoFps` into the same map. There a fixed AE range alone delivers the rate;
  the Samsung keys are set only for a camera that has the Samsung table (the keys are not in
  `availableCaptureRequestKeys` on the Flip5, and unknown vendor keys throw). Elsewhere the encoder
  runs at `min(profile fps, camera max fps)`.
- The encoder's key frame interval counts frames at its configured rate, so `VideoEncoder` requests a key
  frame when one is 25% overdue. That keeps the interval near the profile's when the camera delivers
  fewer frames than the encoder's rate.
- The camera service puts the sensor rotation, and a mirror for front cameras, in the `SurfaceTexture`
  transform, which makes the picture upright for the display's natural orientation. `VideoCompositor`
  applies that transform, then rotates by the display rotation only, undoes the mirror for the stream
  (the preview keeps it) and center-crops to the output aspect. Never add the sensor orientation again.
  On the Flip cover screen the sensor's long side is vertical, so the stream is the middle third of it.
- Tap to focus: `VideoCompositor.previewToInput` maps a preview point through the matrices it draws
  with to camera buffer coordinates and ignores the letterbox bars. `meteringRect` maps those into the
  part of the active array the buffer shows (a center crop at the buffer's aspect), at zoom 1 when the
  camera uses `CONTROL_ZOOM_RATIO`, whose metering regions are relative to the zoomed view.
- `uvc/`: userspace UVC for phones without an external camera HAL. libusb, libuvc and libjpeg-turbo are
  fetched by CMake at configure time with pinned hashes. MJPEG or YUYV frames are decoded into the
  compositor's `SurfaceTexture` through their own `Surface`, released after `stop()`. USB permission
  needs runtime CAMERA permission first. Cameras the OS exposes through Camera2 `LENS_FACING_EXTERNAL`
  go through `camera/` instead. `UvcCamera.start` blocks on USB negotiation, so `StreamEngine` calls it
  outside its lock. A USB attach selects the attached camera only, never another one already plugged in.

## UI

- `ui/MainScreen.kt` lays the screen out the way Moblin does. Wide screens: the preview with rounded corners,
  the status in its top right (uptime, bitrate, bonding share, audio meter, in that order), the zoom stops
  and the camera row in its bottom right, and a 104 dp control bar on the right with the Moblin logo and the
  settings button on top, the quick buttons under them and the Go Live button at the bottom. Near-square
  screens (the Flip cover, `maxWidth / maxHeight < 1.5`): status, logo and settings button in a top band,
  the preview with zoom stops left and cameras right, quick buttons and Go Live in a bottom band.
- The preview `SurfaceView` has exactly the output aspect, so the corner mask drawn over it rounds the
  picture. Never animate its size: opening settings resizes it once and only the panel slides.
- Settings (`SettingsPanel.kt`) sit between the preview and the control bar on wide screens and fill the
  screen on near-square ones. The pages follow Moblin's categories and drill down with a back stack. Rows
  are `SegmentedListItem`s built with `Group { row { ... } }` from `SettingsWidgets.kt`.
- The microphone is open whenever the preview runs, so the audio meter works before going live: an
  `AudioEncoder` without a sink only reads and meters. It reports the peak level in dB about nine times a
  second to `StreamEngine.audioLevel`, which the meter reads in its draw phase only.
- `LiveStats.links` is each SRTLA link's share of the bytes sent in the last second (`linkShares`).
- `AppSettings` stores the status items and quick buttons the user hid, not the ones shown, so an item
  added later is shown by default.
- `streamUrl` adds the profile's SRT latency to an `srt`/`srtla` URL that does not name one.
  `StreamProfile.bondingNetworks` limits which networks SRTLA requests.
- `mirrorFrontCameraOnStream` makes the encoder draw the same mirrored picture as the preview.

## Commands

Run from `android/`. `local.properties` (gitignored) needs `sdk.dir=$HOME/Android/Sdk`.

```sh
./gradlew :engine:test          # JVM unit and loopback tests
./gradlew :app:assembleDebug    # builds the APK (arm64-v8a)
./gradlew :app:installDebug     # installs on the connected device
```

Engine tests that talk to a real server use the system `ffmpeg`/`ffprobe` as the receiver and are
skipped when they are missing. The SRTLA end-to-end tests also need `SRTLA_REC` pointing at a built
`srtla_rec` from https://github.com/BELABOX/srtla.

## Stream service

- `StreamEngine.goLive` needs CAMERA and RECORD_AUDIO and reports an error without them. It starts
  `StreamService` only after the encoders have started.
- `stopLive` sends `StreamService.ACTION_STOP` instead of calling `stopService`: a service started with
  `startForegroundService` that is stopped before it calls `startForeground` crashes the app. The
  service enters the foreground first when it has not yet, then stops itself.

## Conventions

- Landscape only (`sensorLandscape` in the manifest). Vertical streaming for TikTok or Shorts belongs in
  the platforms' own apps, so there is no portrait output.

- No comments or docstrings in code, same as the iOS project. Contracts live in this file.
- Performance matters more than abstraction: the phone's budget belongs to the encoder. No per-frame
  allocations in hot paths that can reuse a buffer, no polling loops, no continuously repainting UI.
- Long sessions must not degrade: bounded queues everywhere, every thread and socket closed on stop.
