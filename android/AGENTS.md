# Mobdroid (Android)

Native Android port of Moblin: Kotlin, Jetpack Compose with Material 3 Expressive, no cross-platform
layers. The iOS sources in the repository root are the reference implementation; port behavior from
them, not code style.

## Layout

- `engine/` — pure Kotlin/JVM, no Android imports. Everything that can run and be tested on the JVM:
  muxers, transports, adaptive bitrate, chat clients.
  - `media/` — `VideoFormat`, `AudioFormat`, `EncodedFrame`, `MediaSink`.
  - `net/` — `Publisher` (one-shot ingest connection), `NetworkLink`/`NetworkLinkProvider` (sockets
    pinned to an interface, used by SRTLA bonding).
  - `flv/`, `rtmp/` — FLV tags and the RTMP/RTMPS publisher. HEVC and AV1 use Enhanced RTMP.
  - `ts/` — MPEG-TS muxer (H264, HEVC, AV1, AAC).
  - `mp4/` — the recorder: a crash-safe MP4 writer and the function that finishes or recovers a file.
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
  hides the three lens one). A lens the zoom range cannot reach is a zoom stop of that camera
  (`CameraDescriptor.lenses`), not an entry of its own: the vivo's zoom range starts at 0.65x, a crop of
  the ultra wide, so its stops are 0.4x, 1x and 2.5x and 0.4x opens the ultra wide. Such a lens is opened
  by its own camera id when that id can be probed, through a physical stream of the logical camera only
  otherwise. A physical stream crashes the vivo's Qualcomm camera provider
  (`ChiMcxStaticPolicy::ActivateAuxCameraStateAndMask`) at every fallback level.
- The vivo's logical camera never leaves the ultra wide for a third party app: 1x and 2.5x are crops of
  it, and `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` reports the ultra wide at every zoom ratio, with or
  without stabilization. `CameraDescriptor.fallbackLenses` lists the lenses the zoom range reaches that
  also have a camera id of their own. `CameraController` reports the active physical id once, 30 frames
  after the stream starts; when it is a lens the zoom range cannot reach, `StreamEngine` adds the camera
  to `AppSettings.fixedLensCameras` and from then on opens every stop by its own camera id (vivo: 0.4x
  is camera 4, 1x camera 3, 2.5x the 85 mm camera 5). If such a lens camera cannot be opened, the camera
  goes back to its logical id for the rest of the run.
- `StreamEngine.setZoom` switches lens when the ratio is such a stop and reopens the camera;
  `UiState.lens` is the open lens and `UiState.zoom` the ratio relative to the main lens. Pinch zoom
  never switches lens.
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

## Chat

- `engine/chat/Chat.kt` is the contract. A `ChatClient` is long lived: `start()` connects and keeps
  reconnecting with backoff until `stop()`. It reports every transition with `onConnected` and
  `onDisconnected`. Listener calls come from the client's own threads and must not block. `stop()` closes
  every socket and thread, and no listener call happens after it returns.
- `ChatMessage.segments` are the text and the emotes in message order; `userColor` is `0xRRGGBB` or null.
  `onMessageDeleted` removes one message, `onUserMessagesDeleted` every message of a banned or timed out
  user.
- `send` reports through its callback, null when the message was sent. `canSend` is false without a
  login.
- `onDisconnected` is reported once per outage: repeated failed attempts with the same reason stay silent
  until the next `onConnected`. A Twitch client's `start()` is one shot, so the app builds new clients for
  every start (`ChatController.start`).
- Twitch reads over the anonymous IRC WebSocket and sends through Helix `chat/messages`; its own message
  comes back over the read connection, so nothing is echoed locally. A 401 is reported as "Twitch login
  expired". YouTube scrapes the live chat page for the continuation and polls `get_live_chat` every 200 to
  3000 ms as Moblin does; it cannot send. A YouTube source starting with `@`, or anything that is not an
  11 character video id, is a handle. Kick resolves the channel slug to `chatroom.id`, listens on Pusher
  and sends with the `session_token` cookie of a kick.com web login as a Bearer token.
- The app side: `chat/ChatController` batches events into `lines` (the newest 200) at most every 150 ms,
  `ChatSpeech` reads messages aloud through Android's text to speech on the media stream, and `LoginStore`
  keeps the Twitch and Kick logins in `logins.json`, outside the exported settings. The Twitch login is the
  implicit flow in a WebView with Moblin's client id and the scopes `user:read:chat user:write:chat`.
- Each platform lives in its own package (`chat/twitch`, `chat/kick`, `chat/youtube`) and ports the
  behavior of Moblin's `Moblin/StreamingPlatforms/`. Parsing is pure functions tested with captured
  payloads; the network goes through the `OkHttpClient` passed in; JSON is read as `JsonElement` trees
  (`kotlinx.serialization.json`, no compiler plugin in the engine).

## Recording

- `Mp4Recorder(input, output, onError)` is a `MediaSink` with its own writer thread and a bounded backlog
  (32 MiB). `input` and `output` are channels of the same file: Android hands out a file descriptor, whose
  read and write channels are separate, a JVM test passes one `RandomAccessFile` channel twice. On overflow
  or an I/O error it stops taking frames, finishes what it wrote and calls `onError` once, from the thread
  that hit it, so the handler must not call `stop()` from inside it. `stop()` blocks until the file is
  finished, returns null when nothing was recorded and throws when the file could not be finished. Such a
  file still holds its blocks: the app leaves it pending and `recover()` finishes it at a later start.
- File layout: `ftyp`, one `mdat`, and `moov` at the end. Inside `mdat` the media is written in blocks of
  about a second: a 16 byte header (magic `mdrb`, records length, data length, CRC32 of the records), the
  records (the formats, then one 13 byte record per video sample, then per audio sample: tag, size, pts),
  then the video samples and the audio samples. A block is one chunk per track. The blocks are the only
  index while recording, so nothing but the encoded frames is kept in memory.
- `finishMp4(input, output)` walks the blocks from the start, stops at the first one that is incomplete or
  fails its CRC, truncates the file there, appends `moov` and patches the `mdat` size. Stopping a recording
  and recovering one after a crash are this same call, and running it on a finished file rewrites the same
  `moov`. It returns null when the file has no video sample.
- A recording starts at the first key frame. Audio that arrives before that key frame is held (48 frames)
  and kept when it is not more than 25 ms older than the key frame. The track that starts later gets an
  empty edit, so sync holds. Video deltas come from the timestamps in a 90 kHz timescale; audio uses 1024
  samples per frame and follows the timestamps only when they drift more than half a frame.
- H264 and HEVC samples are length prefixed and keep their in-band parameter sets (`avc1`, `hvc1`); AV1
  samples drop temporal delimiters and get the sequence header on key frames (`av01`).
- The app side (`recording/RecordingStore`): a recording is a MediaStore video in `Movies/Mobdroid`,
  pending while it is written and published when finished, so the gallery never shows a half written file.
  `recover()` runs once at start, finishes every pending recording an earlier run of the app left behind
  (older than this process, so never the one being written) and deletes the ones with nothing in them.
  Snapshots go to `Pictures/Mobdroid`.
- `StreamEngine` runs the encoders while it is live or recording (`busy`). `MediaTee` takes the output of
  the stream video encoder and the audio encoder to the stream session and the recorder, and replays the
  formats to a sink attached later. `RecordingSettings.separate` adds a second video encoder
  (`VideoCompositor.setRecorder`) with its own codec and bitrate at the stream's resolution and frame rate;
  otherwise the recorder gets the stream's frames, at the profile's bitrate while not live. `syncEncoders`
  is the one place that creates and releases encoders for the current state. `fail` clears the recording
  and the session before it reconciles, so a teardown never tries to create an encoder. The stream profile cannot
  change while busy. Recording stops with an alert when free storage falls under 300 MiB.
- The foreground service runs while live or recording; its notification title says which.

## UI

- `ui/MainScreen.kt` lays the screen out the way Moblin does, with Moblin's sizes. Wide screens: the preview
  with rounded corners against the start edge, under a display cutout there (only the end side keeps its
  cutout inset), the status in its top right (temperature and uptime, recording, bitrate, bonding share,
  audio meter, in that order), the zoom stops and the camera row in its bottom right, and a control bar at
  the far end with the Moblin logo and the settings button on top, the quick buttons under them and the Go
  Live button at the bottom. Quick buttons are 40 dp in 48 dp cells. With no panel the bar takes the width
  the preview leaves (100 to 250 dp) and shows as many button columns as fit (three on the vivo); with a
  panel open it is 100 dp with two columns, as in Moblin. Near-square screens (the Flip cover,
  `maxWidth / maxHeight < 1.5`): status, logo and settings button in a top band, the preview with zoom
  stops left and cameras right, quick buttons and Go Live in a bottom band.
- The preview `SurfaceView` has exactly the output aspect, so the corner mask drawn over it rounds the
  picture. Never animate its size: opening a panel resizes it and the bar once and only the panel slides.
- Panels (settings, chat) sit between the preview and the control bar on wide screens (350 dp, Moblin's
  `settingsHalfWidth`) and fill the screen on near-square ones. Their header is 44 dp with `PanelButtons`:
  hide and close. Hide keeps the panel's state but takes it off the screen; the two buttons then float in
  the preview's top right and the status moves under them. Settings pages follow Moblin's categories and
  drill down with a back stack. Rows are `SegmentedListItem`s built with `Group { row { ... } }` from
  `SettingsWidgets.kt`, capped at 44 dp (56 dp with a supporting line) with 15 sp titles. The cap is a
  `heightIn(max)`, because the list item's own minimum is 56 dp.
- The microphone is open whenever the preview runs, so the audio meter works before going live: an
  `AudioEncoder` without a sink only reads and meters. It reports the peak level in dB about nine times a
  second to `StreamEngine.audioLevel`, which the meter reads in its draw phase only.
- The status temperature is the battery temperature from `ACTION_BATTERY_CHANGED`, coloured by the thermal
  status.
- `LiveStats.links` is each SRTLA link's share of the bytes sent in the last second (`linkShares`).
- `AppSettings` stores the status items and quick buttons the user hid, not the ones shown, so an item
  added later is shown by default.
- `streamUrl` adds the profile's SRT latency to an `srt`/`srtla` URL that does not name one.
  `StreamProfile.bondingNetworks` limits which networks SRTLA requests.
- `mirrorFrontCameraOnStream` makes the encoder draw the same mirrored picture as the preview.
- The Record quick button asks for a second tap to stop, like the End button. The recording time and size
  are a status item. Recording settings are per stream (Streams, a stream, Recording); the recordings list
  is Settings, Recordings.

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
