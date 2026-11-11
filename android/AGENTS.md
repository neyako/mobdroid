# opIRL (Android)

Native Android port of Moblin: Kotlin, Jetpack Compose with Material 3 Expressive, no cross-platform
layers. The iOS sources in the repository root are the reference implementation; port behavior from
them, not code style.

## Layout

- `engine/` — pure Kotlin/JVM, no Android imports. Everything that can run and be tested on the JVM:
  muxers, transports, adaptive bitrate, chat clients, the OBS remote.
  - `media/` — `VideoFormat`, `AudioFormat`, `EncodedFrame`, `MediaSink`.
  - `net/` — `Publisher` (one-shot ingest connection), `NetworkLink`/`NetworkLinkProvider` (sockets
    pinned to an interface, used by SRTLA bonding).
  - `flv/`, `rtmp/` — FLV tags and the RTMP/RTMPS publisher. HEVC and AV1 use Enhanced RTMP.
  - `ts/` — MPEG-TS muxer (H264, HEVC, AV1, AAC).
  - `mp4/` — the recorder: a crash-safe MP4 writer and the function that finishes or recovers a file.
  - `obs/` — the obs-websocket 5 client.
  - `audio/` — PCM helpers for the video player: the resampler and the timed ring buffer.
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
- The app side: `chat/ChatController` batches events into `lines` (the newest 200) at most every 150 ms, and
  `LoginStore` keeps the logins and the voice API keys in `logins.json`, outside the exported settings. The
  Twitch login is the implicit flow in a WebView with Moblin's client id and the scopes in `TWITCH_SCOPES`
  (`TwitchApi.kt`); `TwitchAccount.scopes` is what the token was granted, and an account without all of them
  gets a "Log in again" row. Add a scope there with every Helix call that needs a new one.
- Each platform lives in its own package (`chat/twitch`, `chat/kick`, `chat/youtube`) and ports the
  behavior of Moblin's `Moblin/StreamingPlatforms/`. Parsing is pure functions tested with captured
  payloads; the network goes through the `OkHttpClient` passed in; JSON is read as `JsonElement` trees
  (`kotlinx.serialization.json`, no compiler plugin in the engine).

## Platform APIs, emotes, voices and the chat bot

- `engine/chat/Api.kt` is the contract for managing a stream: `StreamApi` (title, category, marker, poll,
  delete a message, ban, unban, moderators, VIPs, chat modes) with one implementation per platform
  (`TwitchApi`, `KickApi`, `YouTubeApi`), and `streamFeatures(platform)`, which says what each one can do; the
  UI shows only those rows. Every call blocks and throws `ApiException` with a message for the user:
  "Cannot reach X", "X login expired" (HTTP 401), the service's own message, or "X error <code>".
  `ApiException.code` is the HTTP status. Tests run each client against a `MockWebServer` (`ApiServer`).
- Twitch uses Helix as the logged in user, who is both broadcaster and moderator, so it manages that user's
  own channel only. Kick uses the private API of kick.com with the web session token, as Moblin does, and
  looks the channel up once for its slug and chatroom id. YouTube needs an OAuth access token for the
  `youtube` scope and manages the active broadcast, else the first upcoming one; it can change the title and
  ban a chatter by channel id.
- The app side (`chat/Platforms`): `managedPlatforms` lists the platforms the selected stream can manage
  (Twitch when the stream's Twitch channel is empty or the logged in user's, Kick with a login and a channel,
  YouTube when connected), and `call(platform) { api -> ... }` runs one call on the IO dispatcher and returns
  a `Result`. `ui/StreamTools.kt` (the tune button in the chat panel's header) is the page for all of it;
  `PlatformRunner` runs a call from the UI and reports the outcome as an alert. Tapping a chat message of a
  managed platform shows Delete, Timeout and Ban for it.
- The YouTube login is Google Play services' authorization API (`chat/YouTubeAuth`): no client id is in the
  app, Google matches the package name and signing certificate against an Android OAuth client of a Google
  Cloud project. Until one is registered for `com.opirl.app` with the certificates in use (debug, release,
  Play signing) the login cannot complete. `Logins.youtube` only records that the user connected; a token
  is requested before each call.
- Emotes: `engine/chat/emotes` fetches the BTTV, FFZ and 7TV sets (global, and the channel's by its Twitch
  room id or Kick user id, which the chat clients report through `ChatListener.onChannelId`) and
  `applyEmotes` turns whole words of text segments into emote segments. Images are stills: 7TV's static file,
  the first frame of anything else. `chat/EmoteStore` keeps the tables, retries a failed fetch with backoff
  (30 s doubling to an hour) and is applied to every message in `ChatController`; a message that arrives
  before the tables keeps its text. `chat/EmoteImages` loads the bitmaps for the chat widget (at most 150,
  112 px), and the widget is drawn again when one arrives.
- Voices: `ChatSpeech` is one worker thread with a queue of 10 texts. It speaks with Android's text to
  speech, or with `ChatSpeech.voice` (an ElevenLabs or TTS Monster voice, `engine/chat/tts`) it downloads
  the audio and plays it with a `MediaPlayer`, one message at a time, on the phone and not in the stream. A
  failed download is reported once as an alert and the message is skipped. Messages that start with `!` and
  links are not read.
- Chat bot: `engine/chat/bot` parses `!opirl <command>` (`parseBotCommand`) and decides who may run it
  (`BotGate`): the streamer always, moderators when the command allows them, subscribers and everyone else
  when enabled and then subject to the cooldown. `chat/ChatBot` runs the action on the main thread and shows
  it as an alert; replies go to the chat the command came from and are not translated. Commands: `tts
  on|off`, `say`, `snapshot`, `mute`, `unmute`, `zoom`, `torch on|off`, `scene`, `stream
  start|stop|title|category`, `widget <name> enable|disable`, `send`, `help`. Scene and stream commands are
  for the streamer only until moderators are allowed.
- Predefined messages (`AppSettings.predefinedMessages`) are sent from the bolt button next to the chat
  input, to the platform the input is set to.

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
- The app side (`recording/RecordingStore`): a recording is a MediaStore video in `Movies/opIRL`,
  pending while it is written and published when finished, so the gallery never shows a half written file.
  `recover()` runs once at start, finishes every pending recording an earlier run of the app left behind
  (older than this process, so never the one being written) and deletes the ones with nothing in them.
  Snapshots go to `Pictures/opIRL`.
- `StreamEngine` runs the encoders while it is live or recording (`busy`). `MediaTee` takes the output of
  the stream video encoder and the audio encoder to the stream session and the recorder, and replays the
  formats to a sink attached later. `RecordingSettings.separate` adds a second video encoder
  (`VideoCompositor.setRecorder`) with its own codec and bitrate at the stream's resolution and frame rate;
  otherwise the recorder gets the stream's frames, at the profile's bitrate while not live. `syncEncoders`
  is the one place that creates and releases encoders for the current state. `fail` clears the recording
  and the session before it reconciles, so a teardown never tries to create an encoder. The stream profile cannot
  change while busy. Recording stops with an alert when free storage falls under 300 MiB.
- The foreground service runs while live or recording; its notification title says which.

## Scenes and widgets

- `AppSettings.scenes` are what the row under the zoom stops switches between. A `Scene` has a name, a
  camera (`cameraKey`) and its widget placements. Whenever there are none (the first start, a settings reset),
  one scene per built-in camera is created, named after it, so the row reads Back and Front as the camera
  row did. An external camera (USB) is not a
  scene: while one is attached it replaces the camera of every scene (`sceneCamera`), and with several
  attached the one in use stays in use. A scene that plays videos (see Video scenes) keeps playing them.
- `AppSettings.widgets` are global; a `SceneWidget` places one in a scene: on or off, a corner, X and Y
  offsets from that corner in percent of the picture, and a size in percent of its width. Text and Music
  widgets ignore the size, their text size sets it. `Widget.previewOnly` keeps a widget out of the stream, the
  recording and snapshots.
- `widgets/WidgetRenderer` turns the selected scene's widgets into `Overlay`s (a bitmap and a rectangle in
  picture coordinates, origin top left). Text fills in `{time}` and `{date}` and is redrawn each minute;
  images are copies in `files/widgets/`, decoded upright (EXIF) and no larger than the picture; chat is
  the last six lines of `ChatController.lines`. No bitmap is larger than the output picture: long text is
  cut off and a tall chat keeps its newest lines. A bitmap is
  reused while its inputs are the same, and the compositor uploads a texture only when the bitmap object
  changed.
- A browser widget is a web page drawn off screen (`widgets/BrowserWidgets`, main thread only): a
  `WebView` in a `Presentation` on a private `VirtualDisplay` whose surface is a `SurfaceTexture` of the
  compositor (`openSurfaceOverlay`). The page keeps its transparency and its animations, and costs no
  bitmap copies. Its `Overlay` has no bitmap and carries the `BrowserPage` (URL and size in CSS pixels);
  `StreamEngine.syncBrowsers` opens and closes pages to match the overlays and the current compositor, so
  a page lives only while its widget is in the selected scene and the preview runs. A URL change is loaded
  after 800 ms. The sync always runs on the main thread and reads the compositor then, never one captured
  earlier; a compositor call after release returns at once instead of waiting on its dead thread. A page
  is not drawn before its first frame, and a page whose renderer process dies is closed (it would
  otherwise take the app down) and reopens at the next change. The page's sound plays on the phone and is not in the stream. Cleartext HTTP is allowed
  (`usesCleartextTraffic`) because overlay pages are often served from a computer on the local network.
- The Music widget shows the track that is playing in any other app: the cover, the title in bold and the
  artist under it, sized by its text size. `widgets/NowPlaying` reads it from the system's media sessions
  (`MediaSessionManager.getActiveSessions`), which Android only allows for an app with notification access;
  `MediaListener` is an empty `NotificationListenerService` that exists to be granted that, and no
  notification is read. The widget's page opens the system screen for it until it is granted. `NowPlaying`
  listens only while the preview runs or the app is live and a Music widget exists, on the main thread, and
  registers again from scratch whenever that or the access setting changes (it observes the setting), so a
  revoked and returned access is picked up. It takes the session that is playing, else the first one, never
  the app's own (`currentSession`). Each session's metadata and play state are kept from its callbacks, so
  a state update costs no call to the other app. The cover is bounded to 256 px and kept when the new
  one has the same pixels, and a new `Track` is published only when something changed, so a player that
  resends its state does not make the widget redraw. The text size is clamped to the slider's range and the
  bitmap to the picture. "Hide when paused" (on by default) removes the
  widget while nothing plays; "Cover" off leaves the text.
- `VideoCompositor.setOverlays` draws them over the camera with premultiplied alpha blending: all of them
  on the preview, the ones that are not preview only on the encoder and recorder surfaces and in snapshots.
  They are never mirrored.

## Video scenes

- A scene with `video` on and at least one file in `videos` (`Scene.playsVideo`) shows those files in
  place of the camera: `StreamEngine.openCamera` closes the camera and starts a `media/VideoPlayer`, which
  feeds the compositor's input surface like a camera does. Leaving the scene or the preview releases it:
  `release()` interrupts the player thread and waits up to a second for it; the thread itself releases the
  decoders and then the surface. If it has not stopped by then (a stalled file read), the engine opens the
  new source anyway and opens it once more when the thread has finished, since the old decoder may still
  have held the surface.
  `openCamera` keeps a running player when called again for the same scene and file list, so a USB camera
  or a settings change does not restart the video; a changed list restarts it.
- `VideoPlayer` is one thread that demuxes and decodes with two `MediaExtractor`s and `MediaCodec`s, no
  library: video to the surface, audio to PCM. The files play in order and loop. The first decoder start
  is retried for up to 2 seconds because the camera lets go of the surface asynchronously. A file that
  cannot be played sets the status error and the next one is tried.
- The clock starts at the first decoded frame. A frame is released when the clock reaches its time, with
  that exact time as its timestamp (so the compositor's frame pacing sees even spacing), and dropped when
  it is more than 150 ms late. While nothing new is shown for 50 ms (paused, between files) the player
  calls `VideoCompositor.repeatFrame`, so widgets keep updating and the encoder keeps getting frames.
- Rotation metadata is applied by the decoder; the engine passes the size and rotation to
  `VideoCompositor.setInput`, which fills the picture and crops the rest, as for a camera.
- Sound: the audio track is decoded, resampled to 48 kHz stereo (`engine/audio/PcmConverter`, linear) and
  written with its time to a one second `PcmRing`, kept 300 ms ahead of the clock. The ring keeps one
  timestamp gap (audio written more than 20 ms away from where the buffered audio ends starts a new
  segment at its own time, and a late read never drops past that point), and a write larger than the ring
  keeps its end at the right time. `AudioEncoder` hands
  every microphone buffer to `AudioMix`, which mutes the mic if the mic button is on or the scene's "Mic
  while playing" is off, then adds the video's sound at the scene's volume. So the sound is in the stream
  and the recording and is not played on the phone. The phone's audio clock and its system clock differ
  (0.17% on the vivo), so each mix nudges the player's clock by a sixteenth of the difference between the
  ring's time and the clock; a difference over 80 ms (a seek, a stall) skips or waits instead.
- The strip over the preview (`VideoStrip`, in place of the zoom stops) has previous, play or pause, next
  and a seek bar. A seek goes to the previous key frame and decodes without showing up to the target.
- Videos are added with the system file picker and played from where they are; the app keeps a persisted
  read permission for each and releases it when no scene uses the file any more (a file removed from a
  list, a scene deleted).

## OBS remote

- `engine/obs/ObsClient` speaks obs-websocket 5 (RPC version 1) over OkHttp. One instance lives in
  `StreamEngine` for the app's life; `start(url, password)` replaces the connection (a call with the same
  values keeps it) and `stop()` closes it. It is connected while the preview runs or the app is live or
  recording, for the selected stream's `ObsSettings` (`enabled`, a URL `obsUrl` accepts, the password).
- All connection state lives on one single-thread executor per connection, shut down on stop. `state`
  (scenes in OBS's order top to bottom, the program scene, stream and record output states with the time
  they started on the client's clock, the audio inputs) and `screenshot` are `StateFlow`s that reset on
  stop. A lost connection sets `error` ("Cannot reach OBS", OBS's close reason, or "Wrong password" for close
  code 4009) and reconnects every 5 seconds.
- Commands (`setScene`, `startStream`, `stopStream`, `startRecord`, `stopRecord`, `setMuted`) are sent from
  the calling thread and the socket closes gracefully, so a command sent right before `stop()` still
  reaches OBS (ending a stream from the background stops the preview in the same call). A failed command
  reports OBS's comment through `onError`, which shows an alert. The state changes only from OBS's events,
  never optimistically. A request OBS does not answer within 10 seconds fails with "OBS did not answer", so
  the pending requests stay bounded.
- Between `CurrentSceneCollectionChanging` and `CurrentSceneCollectionChanged` nothing is sent (OBS can
  crash on requests then): requests fail at once, the screenshot timer stops, and everything is read again
  afterwards. The check and the send of a request share one lock with those two events, because commands
  are sent from other threads.
- A mute event that arrives during an input refresh restarts the refresh, so an older answer cannot
  overwrite it.
- The audio inputs are OBS's special inputs (desktop and mic devices) plus the enabled sources of the
  program scene, kept only when `GetInputMute` succeeds, which is how OBS tells a source has audio.
- `setScreenshots(true)` fetches a 480 px wide JPEG of the program scene, then again 2 seconds after each
  answer and at once on a scene change. Every fetch cancels the timer and the answer of the one before, so
  there is one timer chain however often the panel is shown. The OBS panel turns it on only while it is on screen and the
  activity is started; nothing is fetched in the background.
- `StreamProfile.obs` also holds four switches that start and stop OBS's stream and recording with Go Live
  and End. They act only when the client is connected to the selected stream's OBS (`isFor`) and the
  output is in the opposite state. End sends them before it stops the local recording, which can stop the
  preview and with it the client.

## UI

- `ui/MainScreen.kt` lays the screen out the way Moblin does, with Moblin's sizes. Wide screens: the preview
  with rounded corners against the start edge, under a display cutout there (only the end side keeps its
  cutout inset), the status in its top right (OBS, temperature and uptime, recording, bitrate, bonding
  share, audio meter, in that order), the zoom stops and the scene row in its bottom right, and a control bar at
  the far end with the opIRL logo and the settings button on top, the quick buttons under them and the Go
  Live button at the bottom. Quick buttons are 40 dp in 48 dp cells. With no panel the bar takes the width
  the preview leaves (100 to 250 dp) and shows as many button columns as fit (three on the vivo); with a
  panel open it is 100 dp with two columns, as in Moblin. Near-square screens (the Flip cover,
  `maxWidth / maxHeight < 1.5`): status, logo and settings button in a top band, the preview with zoom
  stops left and scenes right, quick buttons and Go Live in a bottom band.
- `StreamProfile.portrait` (Streams, a stream, Video, Portrait) swaps the output size (1080p becomes
  1080x1920) and makes the activity request `sensorPortrait` instead of `sensorLandscape`. The compositor
  already rotates by the display rotation and crops to the output aspect, so a portrait stream is upright
  in any window. Widget text scales with the picture's short side. Displays with a smallest width of
  600 dp and more ignore orientation requests since Android 16, so every layout has to work in any window
  shape: a portrait window gets the near-square layout.
- Large windows (smallest side 600 dp or more: tablets, unfolded foldables): the preview pinned to the top
  with the logo, the settings button, one row of 48 dp quick buttons and Go Live directly under it, and the
  panel docked on the right (up to 400 dp) while one is open, or filling what is left under the controls in
  a portrait window. The quick buttons start right after the settings button, and the logo, every button
  circle and Go Live share one centre line, with the labels below it. The preview takes its new size at
  once when a panel opens or closes.
- Half open with the hinge across the screen (`MainActivity` reads Jetpack WindowManager's `FoldingFeature`
  and hands its bounds to `MainScreen`): the preview with its status and camera controls above the hinge,
  the quick buttons, Go Live and any panel below it. A device whose hinge is horizontal in its natural
  orientation (the Flip) is turned to portrait while half open, so it works standing on a table.
- In the near-square layout the zoom stops and the scene row sit at the bottom of the preview band, across
  the whole width, so a narrow portrait picture does not squeeze them out.
- First run (`AppSettings.setupDone` is false): `ui/Wizard.kt`, three steps. Access lists camera, microphone
  and notifications with one line each on why, and its button asks for the ones missing; a permission
  denied for good turns the button into Open settings. Then the destination (opIRL relay, Twitch, YouTube,
  Kick, Facebook, Custom), then only what that destination needs (a stream key, or a URL) and one of four
  quality presets in one row (1080p30, 1080p60, 1440p30, 4K30), 1080p30 by default. Done writes them into
  the selected stream; Skip leaves it alone.
  Settings saved before the wizard existed have no `setupDone` key and count as set up. The wizard is a popup
  over the dimmed main screen on every window size, as tall as its step and scrolling when the window is
  shorter. The destination and stream steps draw no cards: choices are toggle buttons and the stream
  step's fields are plain filled text fields with a primary outline while focused, so nothing is nested
  inside another container. Nothing asks for a
  permission at launch any more: `ui/Access.kt` holds the permission state, and without camera and
  microphone the preview shows the same reason with an Allow button.
- `AppSettings.usbCameraRotation` (Settings, Camera) turns a USB camera's picture clockwise by 0, 90, 180
  or 270 degrees before it is cropped to the output, so a camera mounted upright fills a portrait stream.
- Read sizes from `constraints` with `LocalDensity`, not from `BoxWithConstraints`' `maxWidth`/`maxHeight`:
  those keep the density of the last measure, and a density change that leaves the pixel size alone (the
  display size setting) lays the screen out wrong.
- Colours come from `OpirlTheme` (`ui/Theme.kt`): the system's dynamic dark scheme on Android 12 and later
  (the user's wallpaper or chosen colour), the Material default below that, with background and surface
  forced to black. Accents use `MaterialTheme.colorScheme`, never a literal; only colours with a fixed
  meaning stay literal (live red, warning and level colours, platform colours, the settings badges).
- Motion uses the theme's expressive `motionScheme` only, spatial specs for movement and effects specs for
  fades, and runs on events, never continuously. `ui/Motion.kt` has the shared pieces: `fadeThrough` for
  content that swaps (panels, quick button icons, zoom stops and the video strip), `Appear` for status
  items, and `backProgress` with `Modifier.backGesture`, which shrinks a panel or a settings page with the
  system back gesture before it closes. A snapshot flashes the preview, settings groups resize with a
  spring when rows come and go, and the Go Live button rounds into a pill while live.
- The preview `SurfaceView` has exactly the output aspect, so the corner mask drawn over it rounds the
  picture. Never animate its size: it takes its final size the moment a panel opens or closes, and the
  rest moves over it. The control bar's width and the quick buttons' positions follow one spring
  (`QuickButtonGrid` places each button between its place in the wide and the two column layout), read in
  the layout phase only, while the panel slides out from behind the bar or fades away over the preview.
- The chat panel keeps its draft and whether the stream tools page is open in `MainScreen`, so hiding the
  panel loses neither.
- Panels (settings, chat, widgets, OBS, browser) sit between the preview and the control bar on wide screens (350 dp, Moblin's
  `settingsHalfWidth`) and fill the screen on near-square ones. Their header is 44 dp with `PanelButtons`:
  hide and close, extra small filled icon buttons with the expressive shape morph. They are 32 dp with
  16 dp between them, so their 48 dp touch targets do not overlap. Hide takes the panel off the screen and
  keeps the settings page (`SettingsPages`, reset once the panel has closed) and the chat draft, which
  live in `MainScreen`; the two buttons then float in the preview's top right and the status moves under
  them. Settings pages follow Moblin's categories and drill down with a back stack. Rows are
  `SegmentedListItem`s built with `Group { row { ... } }` from `SettingsWidgets.kt`, capped at 44 dp (56 dp
  with a supporting line) times the font scale, with 15 sp titles. The cap is a
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
- The Widgets quick button opens a panel that turns the selected scene's widgets on and off. Scenes and
  widgets are edited under Settings, Scenes: a scene page has the source (a camera or Video), the video list when the source is
  Video, and every widget with a switch; tapping a widget opens its placement in that scene.
- The OBS quick button (OBS's own logo, `ObsLogo`) opens the OBS panel: the program picture with the
  stream and record buttons beside it, the scenes as toggle buttons, the audio inputs with their mute
  state. Stopping an output takes a second tap within 3 seconds. A stream without OBS set up gets the URL
  and password fields in the panel; an unreachable OBS shows why and a link to Streams, the stream, OBS
  remote. The OBS status item (scene, live, rec, or offline) shows while OBS is enabled for the stream.
- The Browser quick button opens a web browser in the panel slot (`ui/Browser.kt`). `BrowserState` in
  `MainScreen` owns the one `WebView`, created at the first load: it lives while the browser is the
  selected panel, shown or hidden, so a hidden page keeps running, and is destroyed when the panel is closed
  or another panel takes its place, and with the activity. It is paused while the activity is stopped. A
  page whose renderer process dies is closed instead of taking the app down. Only `http`, `https` and
  `about` links are followed. `browserAddress` turns the address field's text into a URL, and nothing else is ever
  loaded: an `http` or `https` URL and `about:blank` are kept, an IPv4 address or `localhost` gets
  `http://`, other text with a dot, no space and no scheme gets `https://`, and the rest, any other scheme
  included, is a Google search. The field shows the URL without `https://`; Go on that unchanged text loads the page's
  own URL (`editedAddress`). The field selects all its text when tapped.
  The system back button goes back in the page's history before it closes the panel. Bookmarks
  (`AppSettings.bookmarks`) are listed where the page would be until one is loaded, and on their own page
  with "Add this page"; the page's sound plays on the phone and is not in the stream.
- The Record quick button asks for a second tap to stop, like the End button. The recording time and size
  are a status item. Recording settings are per stream (Streams, a stream, Recording); the recordings list
  is Settings, Recordings.

## Commands

Run from `android/`. `local.properties` (gitignored) needs `sdk.dir=$HOME/Android/Sdk`.

```sh
./gradlew :engine:test          # JVM unit and loopback tests
./gradlew :app:assembleDebug    # builds the APK (arm64-v8a)
./gradlew :app:installDebug     # installs on the connected device
./gradlew :app:assembleRelease  # minified APK, signed with the release key when it is present
./gradlew :app:bundleRelease    # the bundle for the Play Store, signed the same way
./gradlew :app:lintRelease      # Android lint, no errors allowed
tools/soak.sh <adb serial> <minutes> [srt port] [output directory]
```

`versionCode` in `app/build.gradle.kts` goes up by one with every build that is published.

`tools/soak.sh` is the long-session and heat test. It runs an `ffmpeg` SRT listener that decodes the stream,
and every 30 seconds samples the app's memory, threads, file descriptors and CPU, the battery temperature,
the thermal status, and the app's own `live ...` log line (`StreamEngine` logs its stats every 10 seconds
while live). Point a stream profile at `srt://<this computer>:<port>`, start the script, go live. It writes
`samples.csv` and a summary, and force-stops the app when the thermal status reaches 5 or the battery 50 °C
(`SOAK_MAX_THERMAL`, `SOAK_MAX_BATTERY`), so a heat test cannot cook the phone. Needs a debug build
(`run-as`). On the folded Flip5 a 1080p60 stream from the back camera reaches thermal status 4 in four
minutes and 5 in twenty.

The app is opIRL, application ID and package `com.opirl.app` (the engine is `com.opirl.app.engine`). The
application ID is fixed once the app is on the Play Store, so never change it.

The release build is signed with the keystore named in a properties file (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`): the Gradle property `opirl.signing`, else the environment variable
`OPIRL_SIGNING`, else `~/.config/opirl/signing.properties`. The keystore and that file are never in the
repository. Without them (CI) the release build is signed with the debug key and must not be published.

Backups (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`): `settings.json` and the widget images
go to the cloud backup only when it is end-to-end encrypted, since the settings hold stream keys;
`logins.json` only moves in a device-to-device transfer.

Settings, About has a Source code row (the LGPL and MIT texts require a way to the source), and Licenses
lists the bundled software with its licence text from `assets/licenses/`. Add a row and a text there with
every new bundled library or asset.

Engine tests that talk to a real server use the system `ffmpeg`/`ffprobe` as the receiver and are
skipped when they are missing. The SRTLA end-to-end tests also need `SRTLA_REC` pointing at a built
`srtla_rec` from https://github.com/BELABOX/srtla.

## Stream service

- `ThermalGuard` lowers the frame rate to 30 when the thermal status reaches critical (4): the compositor
  paces at the lower rate (a playing video is not restarted), the camera is reopened at it and the video
  encoders are restarted, with an alert. It stays lowered while
  live or recording, and goes back to the profile's rate once the status is moderate (2) or lower and
  nothing is running.
- `StreamEngine.goLive` needs CAMERA and RECORD_AUDIO and reports an error without them. It starts
  `StreamService` only after the encoders have started.
- `stopLive` sends `StreamService.ACTION_STOP` instead of calling `stopService`: a service started with
  `startForegroundService` that is stopped before it calls `startForeground` crashes the app. The
  service enters the foreground first when it has not yet, then stops itself.

## Conventions

- Every string the user reads is a resource (`res/values/strings.xml`, Vietnamese in `values-vi`), read with
  `stringResource` in Compose and `Context.getString` elsewhere. Brand names, units (`Mbps`, `kbps`, `s`)
  and technical fault details stay literal. Enum labels are `@StringRes` ids. Fixed English messages that
  come from the engine module or from `describeError` are mapped to resources by `ui/Messages.kt`
  (`messageRes`, `localized`) where they are shown; add a line there with every new fixed message.
  `res/xml/locales_config.xml` lists the languages for the per-app language setting of Android 13 and later.
  Text made for the stream itself (widget text, recording names) is not translated.
- No comments or docstrings in code, same as the iOS project. Contracts live in this file.
- Performance matters more than abstraction: the phone's budget belongs to the encoder. No per-frame
  allocations in hot paths that can reuse a buffer, no polling loops, no continuously repainting UI.
- Long sessions must not degrade: bounded queues everywhere, every thread and socket closed on stop.
