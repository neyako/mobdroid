# opIRL (Android)

Native Android port of Moblin: Kotlin, Jetpack Compose with Material 3 Expressive, no cross-platform
layers. The iOS sources in the repository root are the reference implementation; port behavior from
them, not code style.

## Layout

- `engine/` — pure Kotlin/JVM, no Android imports. Everything that can run and be tested on the JVM:
  muxers, transports, adaptive bitrate, chat clients, the OBS remote.
  - `media/` — `VideoFormat`, `AudioFormat`, `EncodedFrame`, `MediaSink`.
  - `net/` — `Publisher` (one-shot ingest connection), `NetworkLink`/`NetworkLinkProvider` (sockets
    pinned to an interface, used by SRTLA bonding), `WebServer` (HTTP and WebSocket server).
  - `moblink/` — Moblink streamer and relay. `remote/` — remote control protocol, streamer, assistant,
    web remote and relay bridge. `json/` — helpers for JSON trees.
  - `flv/`, `rtmp/` — FLV tags and the RTMP/RTMPS publisher. HEVC and AV1 use Enhanced RTMP.
  - `ts/` — MPEG-TS muxer (H264, HEVC, AV1, AAC).
  - `mp4/` — the recorder: a crash-safe MP4 writer and the function that finishes or recovers a file.
  - `obs/` — the obs-websocket 5 client.
  - `audio/` — PCM helpers for the video player: the resampler and the timed ring buffer.
  - `srt/` — SRT caller, a port of Moblin's own `SrtSender.swift`. `srtla/` — SRTLA bonding client,
    a port of `Moblin/Media/Srtla/Client`. `abr/` — adaptive bitrate (Belabox port).
  - `rist/` — RIST sender (Simple and Main profile, bonding). Moblin uses librist; this is the protocol
    written in Kotlin.
  - `whip/` — WHIP publisher: a send-only WebRTC stack (ICE, DTLS-SRTP, RTP and RTCP). Moblin uses
    libdatachannel. DTLS comes from BouncyCastle's lightweight API, the only dependency it adds.
  - `replay/` — the replay buffer.
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
- `RistPublisher(url, listener, links)`: `rist://host:port` with librist's `profile` (`main`, the default
  and what ffmpeg and OBS listen with, or `simple`, which needs an even port and uses port+1 for RTCP),
  `buffer` (ms, 1000), `cname`, `virt-dst-port` or `stream-id` (1968), `session-timeout` (ms, 2000) and
  `bandwidth` (kbps). Encryption, authentication and compression are refused. MPEG-TS in RTP payload type
  33, seven TS packets per datagram. Main profile is GRE over UDP with keepalives; the sender starts with
  GRE version 1 and moves to 2 when the receiver uses it. Retransmission answers both NACK forms out of a buffer of
  `buffer` plus a margin, at most 20 times a packet and never above `bandwidth`, with the SSRC's low bit
  set as librist does. `onConnected` at the first RTCP from a peer; `onDisconnected` when no peer has
  answered within 5 s ("RIST handshake timed out") or every peer has been silent for the session timeout
  ("RIST receiver stopped answering"). With `links` there is one peer per `NetworkLink`, following the
  flow; media is shared out by weight (librist's weighted load balancing) and each weight follows the
  peer's round trip time through `RistAdaptiveBitrate`, Moblin's `updateConnectionsWeights`. A peer that
  has been silent for 300 ms gets no media while another one answers. `stats()`: `rttMs` is the best
  peer's, `lostPercent` the larger of the receiver's reports and retransmit requests per packet sent.
- `WhipPublisher(url, headers, listener, http, requestKeyFrame)`: `whip://` is HTTP and `whips://` HTTPS
  (Moblin's rule), plain `http(s)://` works too. The app sends the stream's token as
  `Authorization: Bearer`. It waits up to 2 s for `setVideoFormat` so the offer names H.264 or HEVC (AV1
  fails), gathers host candidates on one UDP socket, POSTs the offer (non-trickle, `actpass`) and takes
  the answer and the `Location` header as the session, which `stop()` deletes. Then ICE as the
  controlling agent (works with ICE-lite and with full agents, no TURN and no STUN server), DTLS 1.2 in
  whichever role the answer picks with the answer's fingerprint checked, and SRTP (AES-CM-HMAC-SHA1-80
  or AES-GCM). Video is packetized per RFC 6184 or 7798 with the parameter sets on every key frame; audio
  must be Opus (`AudioFormat.codec`), anything else is not sent. One clock gives both tracks their RTP
  times. It answers NACKs out of a history (8192 video packets, 512 audio; RTX when the answer has it),
  turns PLI and FIR into `requestKeyFrame` at most every 500 ms, and reads the round trip time and loss
  from the receiver reports into `stats()`. `onConnected` once media can flow (it asks for a key frame);
  everything else ends in one `onDisconnected`: an HTTP status, "WHIP connection timed out" after 10 s,
  "Consent lost" when the server stops answering ICE checks for 10 s, a DTLS alert, an RTCP BYE. The send
  queue holds one second or 4 MiB and drops video up to the next key frame.
- All of them: `stop()` never calls `onDisconnected`; any other end calls it exactly once, on an internal
  thread. `stop()` blocks up to about 2 s.
- AV1 is offered only over RTMP. FFmpeg 7.1 cannot decode AV1 in MPEG-TS, so `StreamProfile.allowedCodecs`
  drops it for `srt`/`srtla`/`rist` URLs, and for `whip` because the publisher does not packetize it.
  `transportOf(url)` is the one place that reads the scheme. Codec choice is `CodecCatalog.choose`: the profile's codec if the
  phone has a hardware encoder for it, else the first hardware codec in the platform's priority list
  (Twitch, Kick, Facebook: H.264 only; YouTube and Custom: AV1, HEVC, H.264). Software encoders are
  never used.
- ABR: `BelaboxAdaptiveBitrate` for SRT, `QueueAdaptiveBitrate` for RTMP, `RistAdaptiveBitrate` for RIST
  (Moblin's RIST experiment: the best peer's round trip time with a constant ten packets in flight) and
  `LossAdaptiveBitrate` for WHIP (Moblin has none there: down by half the reported loss once a second
  above 10% loss, up 5% a second under 2% once it has been quiet for 4 s), ticked every
  `ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS` (20 ms) with the transport bitrate measured once a second.
  All of them cap the target near the measured transport bitrate, as Moblin does. `TransportBitrateMeter` is
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
  The login also asks for `TWITCH_ALERT_SCOPES` (followers, redemptions, bits); the Alerts page asks for a new
  login while one of those is missing (`missingTwitchScopes`).
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

## Alerts

- `engine/alerts/` holds what the Alerts widget reacts to. `AlertEvent` is the one event type: Twitch follows,
  subscriptions (subscribe, resubscribe, gifts, upgrades), raids, cheers and redemptions, Kick subscriptions,
  gifted subscriptions, hosts, rewards and kicks, and chat bot commands.
- `TwitchEventSubClient` is a long lived EventSub WebSocket client written like the chat clients (one scheduler
  thread, a lock, no blocking in listener calls, every socket and thread closed by `stop()`). After the welcome it
  creates the subscriptions the token's scopes allow (`subscriptions()` lists type, version, condition and scope: follows
  v2, `channel.chat.notification` for every subscription kind as Moblin does, redemptions, raids to the channel, cheers); a 403
  skips that one, a 401 or a revocation stops the client and calls `onUnauthorized`, anything else reconnects with
  backoff. Keepalives are watched (`keepalive_timeout_seconds` plus 5 s), `session_reconnect` moves to the new URL
  without subscribing again, and a notification id seen before is dropped. Notices from another channel of a shared
  chat session are ignored, which is Moblin's default. Parsing is `parseEventSub` over `JsonElement`.
- Kick events come over the chat client's Pusher connection: it also subscribes to `channel_<channel id>` (the id is
  `chatroom.channel_id` of the channel lookup) and `parseKickFrame` turns `SubscriptionEvent`, `GiftedSubscriptionsEvent`,
  `RewardRedeemedEvent`, `StreamHostEvent` and `KicksGifted` into `ChatListener.onAlert`. `ChatController.alerts`
  is a `SharedFlow` of them.
- `widgets/alerts/AlertsSource` (created by `StreamEngine`) runs the Twitch client while `WidgetRuntime.active` is true, an
  Alerts widget exists and a Twitch login is stored, and passes Kick events on while active. It also reads new chat lines
  (`WidgetRuntime.chat`) for `!opirl alert <name>` (the chat bot's prefix, but read here and not by `chat/ChatBot`), with
  Moblin's permissions, which are the widget's own: the channel owner always, moderators (on by default), subscribers and
  everyone (off), and a cooldown that only holds back the last two. A command works once it exists and is enabled,
  and only the word `alert` is read (no fuzzy matching). An event goes to the Alerts widgets the selected scene shows (nested scenes included), never to a widget that
  is not on the stream. Moblin never plays its channel point redemption alert (it is behind a debug switch and an
  `if false`); here one alert plays for every redemption.
- Each widget has an `AlertSequence` in `runtime.state(widget.id)`: a bounded queue (100), one alert at a time, 3 s
  after one ends before the next, the settings read when the alert is dequeued, an alert that is disabled or has no
  matching item skipped. `AlertsWidget.draw` drives it: it dequeues, prepares the alert on the IO dispatcher (GIF frames
  decoded once with `GifReader`, scaled no larger than they are drawn, at most 48 MiB of frames; sound and speech start
  when the alert starts), then returns the frame that is due and asks to be drawn at the next frame change, at most every
  33 ms. A still image shows for 1 s per repeat, a GIF frame for its delay (10 ms or less counts as 100 ms).
- The look is Moblin's. The image is drawn at its own pixel size, the text under it centered on the image: `user ` in
  the accent colour, the message in the text colour, a 2 px black outline, wrapped at 1000 px and left aligned inside its
  block (a long line can reach past the picture, as in Moblin). All sizes scale with `min(width, height) / 1080`, so a
  1080 px short side gives Moblin's pixels. The box is as wide as the image and as high as image plus text, placed like
  any widget (default 20% and 5% from the top left, no size). Texts are English and not translated (`resolveAlert`).
- Settings: `AlertsSettings` holds one `AlertSettings` for each alert, thresholds for cheers and kicks (the first enabled
  match plays), chat bot commands with their permissions and the widget's own images and sounds. Bundled images and sounds
  are in `assets/alerts/` and named by id (`BUNDLED_IMAGES`, `BUNDLED_SOUNDS`); a custom one is a copy in `files/widgets/`.
  Left out compared with Moblin: placing an alert on a face, speech to text alerts, Moblin Cloud tips, AI answers, video
  alerts, cloud voices, per-language voices (the phone's voice reads), the quick button alert and the remote control.

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
- `StreamEngine` runs the encoders while it is live, recording or sending the preview stream (`busy`).
  `MediaTee` takes the output of the stream video encoder and the audio encoder to its stream sinks (the
  main session, every destination and the replay buffer, `setStreams`) and to the recorder, and replays
  the formats to a sink attached later. `RecordingSettings.separate` adds a second video encoder
  (`VideoCompositor.setRecorder`) with its own codec and bitrate at the stream's resolution and frame rate;
  otherwise the recorder gets the stream's frames, at the profile's bitrate while not live. `syncEncoders`
  is the one place that creates and releases encoders for the current state. `fail` clears the recording
  and the session before it reconciles, so a teardown never tries to create an encoder. The stream profile cannot
  change while busy. Recording stops with an alert when free storage falls under 300 MiB.
- The foreground service runs while live, recording or sending the preview stream; its notification
  title says which.

## Outputs

- `StreamEngine.syncSinks` is the one place that decides which sink gets what, and `syncEncoders` calls it
  first. A `LiveOutput` is a `StreamSession` with its sinks: a WHIP output takes its video through a
  `VideoOnlySink` and its sound through an `AudioOnlySink` on `opusTee`, since WebRTC carries Opus and
  everything else AAC. `AudioEncoder.opusSink` is set while any output wants Opus; the encoder thread then
  runs an Opus `MediaCodec` beside the AAC one on the same PCM.
- Multi-streaming: `StreamProfile.destinations` (name, URL, key or token, on or off). Going live starts one
  `StreamSession` per enabled destination next to the main one. They all get the stream encoder's frames, so
  a destination must accept the stream's codec; adaptive bitrate follows the main session only, and a slow
  destination drops frames in its own publisher. Each destination reconnects by itself, reports "Live at"
  and "failed" alerts, and its state is in `StreamEngine.destinations` (shown on the Destinations page
  while live). The profile, its destinations included, cannot change while busy.
- Replay (`StreamProfile.replay`): while live with replay on, `engine/replay/ReplayBuffer` keeps the last
  60 seconds plus the delay of what the stream encoder made (whole groups of pictures, at most 96 MiB, the
  oldest dropped first; a new codec config starts over). It holds the frames the encoders already
  allocated, nothing is copied. `saveReplay` waits the delay, takes a `ReplayClip` of the wanted length
  starting at a key frame, writes it with `Mp4Recorder` into `Movies/opIRL` as "opIRL Replay ..." and
  publishes it. `instantReplay` does the same and then plays the file over the stream: `replayPlaying`
  takes the place of the scene's source in `openCamera`, a `VideoPlayer` that plays once (`loop = false`)
  at the replay speed (`AppSettings.replaySpeedPercent`, global so it can change on air) and reopens the
  scene's source when it ends. Sound plays only at normal speed. `playReplay(uri, startMs)` plays a saved
  replay the same way from a start point, live or not; `VideoPlayer.startMs` seeks the file before the
  first frame is decoded (a decoder flushed right after it starts gives no picture). The Instant quick
  button stops a running replay. The buffer is fed only while the stream encoder runs, so not while
  recording with a separate encoder off air. Saved replays are the recordings whose name starts with
  "Replay" (`Recording.isReplay`).
- Preview stream (`StreamProfile.previewStream`, Moblin's name; `monitor` in the code because preview is
  the on-screen picture here): a WHIP session of its own with a small H.264 encoder on the compositor's
  third surface (`VideoCompositor.setMonitor`, drawn at the surface's size) and Opus sound. The Preview
  quick button turns it on and off, on air or not, and it keeps the foreground service running.
- Microphones: `media/MicCatalog` lists the connected inputs (phone, USB, wired headset, Bluetooth) from
  `AudioManager` and follows `AudioDeviceCallback`. `AppSettings.micKey` is null for the system's choice,
  a `Mic.key`, or a bare kind (`USB`) from settings saved before there was a list. `MicCatalog.open`
  turns it into a `MicInput`; a Bluetooth one makes the headset's output the communication device (SCO;
  `setCommunicationDevice` only takes an output from `availableCommunicationDevices`) and records
  with `VOICE_COMMUNICATION`, everything else with `CAMCORDER`. A mic that is not connected falls back to
  the system's choice and is taken again when it connects. `AudioEncoder.input` can change at any time: the
  encoder thread reopens its `AudioRecord` and keeps the codecs, so the mic switches on air.

## Scenes and widgets

- `AppSettings.scenes` are what the row under the zoom stops switches between. A `Scene` has a name, a
  camera (`cameraKey`) and its widget placements. Whenever there are none (the first start, a settings reset),
  one scene per built-in camera is created, named after it, so the row reads Back and Front as the camera
  row did. An external camera (USB) is not a
  scene: while one is attached it replaces the camera of every scene (`sceneCamera`), and with several
  attached the one in use stays in use. A scene that plays videos (see Video scenes) keeps playing them.
- An image or browser widget can remove its background (see Filters).
- `AppSettings.widgets` are global; a `SceneWidget` places one in a scene: on or off, a corner, X and Y
  offsets from that corner in percent of the picture, and a size in percent of its width. Widgets whose drawer
  is not `sized` (text, music) ignore the size, their text size sets it. `Widget.previewOnly` keeps a widget out of the stream, the
  recording and snapshots.
- Every widget type is a `WidgetDrawer` (`widgets/WidgetKit.kt`) in its own package `widgets/<type>/`, next to
  its `@Serializable` settings class, which is a field of `Widget` with `@EncodeDefault(NEVER)` so a widget
  only stores the settings of its own type. `WidgetRegistry.kt` maps `WidgetType` to the drawer. Its settings
  page is `ui/widgets/<Type>Page.kt`, a composable that takes a `WidgetScope` (the widget, `update { }`, the
  runtime, `push(page, title)` for sub-pages, which come back as `scope.page`), and its strings are in
  `res/values/strings_<type>.xml`. Types in `CONTROLLED` also have a `<Type>Controls` composable, shown in
  the Widgets panel under the switches while the widget is on in the selected scene.
- `WidgetRenderer` draws the selected scene's widgets on one coroutine: it asks each drawer for a
  `WidgetFrame` (its size as shares of the picture, and `Layer`s inside that box: a bitmap or a surface
  source, with a rotation, an alpha and a crop), places it with `overlayRect` and hands the compositor
  `Overlay`s in picture coordinates, origin top left. It draws again when the scenes, the widgets or the
  picture size change, when `WidgetRuntime.invalidate()` is called, on a new chat line, track or location
  (only while a widget that uses them is placed), and at the earliest time a drawer asked for with
  `WidgetContext.redrawAt`/`redrawIn`. Nothing is drawn on a timer otherwise, and nothing at all while
  neither the preview nor a stream or recording runs. An unchanged overlay list is not sent again.
- A drawer keeps its bitmaps with `WidgetContext.cached(slot, key)` (slots start with the widget's id; a
  slot that a draw did not ask for is dropped), so a redraw with the same inputs allocates nothing and
  the compositor uploads a texture only when the bitmap object changed. An animation moves layers
  (rotation, alpha, position) of cached bitmaps instead of drawing new ones. No bitmap is larger than
  the picture. Text scales with `WidgetContext.textScale` (the picture's short side / 400).
- `WidgetRuntime` (`StreamEngine.widgets`) is what drawers, pages and controls share: the settings store
  (`update(id) { }` changes one widget), the app context, a coroutine scope, the `OkHttpClient`, the live
  inputs (`chat`, `track`, `location`, `faces`, `stats`, `ui`), `active` (true while the preview or a stream or
  recording runs: a source of its own, a connection or a sensor, runs only then) and `state(key) { }`, objects that live as long as
  the app (a spinning wheel, a snapshot queue), keyed by the widget's id. Whatever must survive a restart
  is in the widget's settings instead. `Need.Location` from `WidgetDrawer.needs` turns the location
  updates on (`widgets/sources/LocationSource`, the platform's fused provider, else GPS and network) while
  such a widget is in the selected scene and the preview or a stream runs; the stream service then also
  runs with the location type. `ui/widgets/LocationAccess` asks for the permission on the widget's page.
  `Need.FaceOf(camera)` asks for the face seen by a camera (null is the scene's own), reported in
  `WidgetRuntime.faces`.
- Files a widget owns (images, models, sounds) are copies in `files/widgets/` whose names start with the
  widget's id (`rememberWidgetFilePicker`); deleting the widget deletes them.
- A Scene widget shows the widgets of another scene at that scene's placements, a Slideshow shows text
  and image widgets one after another at its own placement, a Crop widget shows a part of a browser
  widget's page (the same page, opened once), a Snapshot widget shows each snapshot for its showtime.
  A widget is drawn once per scene, however often scenes nest it.
- Images are copies in `files/widgets/`, decoded upright (EXIF) and no larger than the picture; chat is
  the last six lines of `ChatController.lines`; a tall chat keeps its newest lines.
- The Text widget (`widgets/text/`) is Moblin's text widget. `Widget.text` is the format string, parsed by
  `parseTextFormat` (`{time}`, `{speed:km/h}`, `{checkbox}`, `\n` and every other field of
  `TextFormatStringLoader.swift`; a field with no Android source, such as Apple workout, Tesla, cycling and
  running sensors, subtitles, the debug overlay, the browser title and the latest follower, still parses and
  shows what Moblin shows without data). `Widget.textSize` is the font size in `textScale` units and
  `Widget.textOptions` has the rest: colours, font, alignment, minimum width, corner radius (also in `textScale`
  units), the delay, and the timers, stopwatches, checkboxes, ratings and lap times, one entry per such field
  in the text (`synced`). A timer's end, a stopwatch's start and a lap's start are wall clock times in the
  settings. Every line has its own rounded background, like Moblin. The Rounded design is Nunito
  (`assets/fonts`), the others are system families; the checkbox, stars and mute symbol are drawn as paths.
  The widget is drawn again only when a shown field changes (`nextRedraw`: second, minute and midnight
  boundaries, running timers, stopwatches and laps, g-force each second). Stream stats and mute wake it
  through `TextSources`, a location fix through the renderer. `runtime.textSources()` (one per runtime) runs
  while `runtime.active` for the text widgets placed in the selected scene (`textDemand`): the weather from
  Open-Meteo every 10 minutes (5 seconds until the first answer), the place from `Geocoder` (every 5 minutes,
  and only after 1000 m), the accelerometer for g-force and the trip (distance, ascent, descent, the splits,
  slope and average speed, derived from the fixes as `ModelLocation.swift` does and kept in memory, reset by
  the controls or when going live if the widget says so). With a delay the fields come from the sample taken
  that many seconds ago.
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
- The PNGTuber widget (`widgets/pngtuber`) shows a PNGTuber Plus `.save` model that follows a face. The file is one
  JSON object of numbered images: `imageData` (base64 PNG), `costumeLayers` (a string holding ten 0/1 flags),
  `showBlink` and `showTalk` (0 always, 1 eyes open or mouth closed, 2 eyes closed or mouth open) and `zindex`.
  Positions, offsets and parents are ignored, as on iOS: the layers of the costume that fit the pose are drawn in
  z order, bottom left aligned, into one picture as large as the largest of them, which is fitted into the
  placement size in percent of both the width and the height of the output (the iOS rule, not the width only).
  Mirror flips that picture. The face is `WidgetRuntime.faces[cameraKey]` (`Need.FaceOf`, null is the scene's
  camera): the mouth is open when `mouthOpen * sensitivity > 0.2`, the eyes (the left one, as on iOS) are open
  when `leftEyeOpen * sensitivity > 0.4`, the sensitivities being 5 to 300 and 5 to 500 percent. Without a face
  the last pose stays; before the first face it is eyes open, mouth closed. `PngTuberState.ensure`, called by the
  drawer and by the page, reads the file once on the IO dispatcher (`Json.decodeFromStream` into a tree) and
  composes one bitmap per distinct set of visible layers (at most four: mouth and eyes), layer by layer, so no
  decoded layer is kept. Layers are decoded at a half octave scale (`decodeLevel`) that covers the placement and
  the composites never exceed the output; a changed file, costume, output size or scale level loads again while
  the old pictures stay on screen, and nothing is drawn before the first load ends. A file whose layers cannot
  be read is `failed` and the page says so.
- The Map widget (`widgets/map/`) draws an OpenStreetMap raster map (256 px Web Mercator tiles from
  `tile.openstreetmap.org`) centred on `WidgetRuntime.location`, with Moblin's marker (a light ring round a blue dot,
  30 px at 1080p). Its size is a camera distance in metres (500 to 10000) as in Moblin: the map shows `2 tan 15°`
  times that, plus 150 m for every m/s over 4 m/s. With north up off the map turns to the course and, over 4 m/s,
  its centre moves ahead so that the marker sits below the middle (at most a quarter of the side). The delay shows
  the newest fix that is old enough (`FixQueue`, 12 s of history) and asks for a redraw when the next one comes due.
  The placement size is a percentage of the picture's short side, as in Moblin. Corner radius and border are
  Moblin's shape effect. `MapGeometry` is the pure maths (tested). `TileStore` loads tiles on two threads into a
  48 tile LRU, asks only for the tiles the map covers at that moment (never a margin: the tile policy counts
  pre-emptive fetching as bulk downloading), backs off per failing tile and calls `runtime.invalidate()` per
  arrival. `OsmTiles` fetches through a client derived from `runtime.http` with a 50 MiB `okhttp3.Cache` in
  `cacheDir/map-tiles` (server cache headers are honoured, so a tile is fetched once) and the `User-Agent`
  `opIRL/<version> (Android)`. The drawer never waits for the network: the map is drawn once every tile it covers is
  cached, until then the previous bitmap stays up (nothing before the first one), like Moblin's snapshotter.
  "© OpenStreetMap" is drawn in the map's bottom right corner and has to stay; the widget's page tells the streamer
  to link openstreetmap.org/copyright in the stream description. The tile policy allows blocking apps whose use
  degrades the service, so an app with many users needs a tile provider it has an agreement with (one URL, in
  `osmTileStore`). Not ported: privacy regions (the app has none) and the chat bot's temporary zoom out.
- `VideoCompositor.setOverlays` draws them over the camera with premultiplied alpha blending: all of them
  on the preview, the ones that are not preview only on the encoder and recorder surfaces and in snapshots.
  They are never mirrored.
- The Wheel of luck follows Moblin's `WheelOfLuckEffect`. The wheel is one bitmap (400 px per 1920 px of picture
  width, so it has no size setting and is smaller on a portrait picture) turned as a layer; the pointer is a layer
  of its own, 70% of it over the wheel. A spin lasts 12 s and the angular speed falls linearly from a random 8 to
  15 rad/s, so `WheelSpin` computes the angle from the time and the widget is drawn every 33 ms only while it
  turns. Spins queue: the next starts 3 s after the previous one ended. With "Only show when spinning" the wheel
  appears 1.5 s before a first spin, stays 5 s after it and is then removed. The result is the sector under the
  pointer when the spin ends; the results (newest first, at most 100) and the queue are in `WheelState`, which
  lives in `WidgetRuntime.state`, so nothing of a spin is stored in the settings. The simple options editor is one
  option per line and resets the weights, the advanced one lists options with a weight of 1 to 100, as in Moblin.
  The caption of Moblin Cloud tips and the actions of options are not ported.
- The Bingo card follows `BingoCardEffect`: a square whose side is the placement size in percent of the picture's
  shorter side, with 2, 3, 4 or 5 squares per side for up to 4, 9, 16 or more squares (`bingoSide`; squares past 25
  are not drawn). Text is 35% of a square, shrunk down to 40% to fit three lines, then cut. The squares and their
  marks are in the settings, and a mark belongs to its line, not to its text (`squaresFromText`).
- A Video source widget is picture in picture: a second camera drawn as a layer. Its `Layer` carries a
  `CameraFeed(camera)` and `StreamEngine.syncFeed` runs one second `CameraController` (or the UVC driver for
  `USB_CAMERA`) into a surface overlay of the compositor named `camera:<key>`, only while such a widget, or a face
  widget that names another camera, is in the selected scene and the preview runs. One feed at a time: the first
  one the overlays ask for. Two built-in cameras run together only when `CameraManager.concurrentCameraIds` lists
  the pair (the Flip5 lists none, the emulator does); otherwise nothing is drawn. A USB camera that a Video source
  widget of the selected scene uses (`usbIsFeed`) is no longer the camera of the scenes. `VideoCompositor.setFeedInput`
  takes the feed's size, sensor orientation, display rotation and mirror, and the feed fills its layer and crops
  the rest (`fillMatrix`). `feedBox` (pure, tested) turns the settings (crop in percent, rotation in quarter
  turns, mirror) into the widget's box and the layer inside it. Corner radius and border are percent of the
  layer's shorter side, drawn by the surface shader as a rounded rectangle, so they cost no bitmap.
- Layers are drawn by one vertex shader (`PLACE`: rectangle, crop, rotation about the centre) with an alpha
  and the widget's chroma key (`KEYED`); surface layers also take the corner radius and border. `linkProgram` checks the link status, because a
  uniform declared with different precisions in the two stages only fails there.
- Face tracking: `VideoCompositor.setFrameTap` reads the scene's picture, or a feed's, into a 320 px wide
  buffer at most ten times a second and only when the last frame was consumed (`FrameTap.busy`), and
  `widgets/sources/FaceTracker` runs ML Kit's bundled face detector (contours and classification) on it off
  the render thread. `Face` is what widgets get: `mouthOpen` (the inner lip gap over the face height, times 8,
  0 to 1), eye openness, yaw, pitch and roll in degrees, and the box in shares of the picture. The detector
  runs only while a drawer in the selected scene returns `Need.FaceOf` and the preview runs. One camera is
  tracked at a time: the first `FaceOf` (`StreamEngine.faceCamera`).
- The VTuber widget (`widgets/vtuber`) draws a VRM model (0.x or 1.0) with Filament and gltfio into a surface
  overlay, a square whose side is the placement size in percent of the picture's shorter side. `VTubers` owns
  one `HandlerThread`; every Filament call is made on it, each model has its own `Engine`, and a model lives
  only while its widget is in the selected scene and the preview runs. It draws every 33 ms. The file is read
  into a direct `ByteBuffer` that the renderer keeps for its lifetime (gltfio reads from it after loading),
  and `plainSlashes` first rewrites `\/` to `/` in the GLB's JSON chunk, padded with spaces, because UniVRM
  escapes slashes and gltfio then finds no decoder for `image\/png`. `parseVrm` (pure, tested) reads the
  humanoid bones (neck, spine, upper arms) and the mouth (`aa`/`a`) and blink morph binds of both versions; a
  1.0 model faces +Z and is turned around. Bones are set from the rest pose: the wanted rotation in world axes
  is conjugated by the parent's world rotation, so models whose bones are not axis aligned (1.0) pose the same.
  `TransformManager.getParent` returns an entity, not an instance. The animator is fetched after
  `loadResources`. The head follows the face's yaw and roll (0.8 on the neck, a third of that on the spine),
  the arms hang at `armsAngle` and sway (`armSway`), mouth and blink weights follow the face times the
  sensitivities. Lighting is one directional light and a constant ambient with a linear tone mapper, over a
  transparent swap chain. Spring bones, MToon shading, look-at and VRM 1.0 node constraints are not applied
  (Moblin applies no constraints either), so a model that moves its sleeves with constraints keeps them in
  the rest pose. Filament's ARM library crashes under the x86 emulator's ARM translation (a SIGSEGV in
  `FEngine::loop`), so the VTuber widget can only be run on a real phone.
- The Scoreboard (`widgets/scoreboard/`) is one widget with eleven sports. Generic, Padel, Golf and the Golf full
  scorecard have their own drawers. Basketball, Generic 2, Generic sets, Hockey, Football, Tennis and
  Volleyball share the modular drawer, whose templates and actions port Moblin's `ModelScoreboard.swift` and the
  web remote. All state is in `ScoreboardSettings`; a running clock is `startedAt` (wall clock) plus `seconds`,
  derived from `context.now` and redrawn once a second only while it changes. The scale is
  `textScale * size / 50`, so the placement size is a scale, not a share of the width. The full scorecard
  shows the first Golf widget's round. Moblin's Watch and web remote actions are rows of `ScoreboardControls`.
- The Pomodoro widget keeps its timer in its settings as wall clock values (`phase`, `endsAt` while running,
  `secondsLeft` while stopped, null meaning a whole phase). `PomodoroSettings.reading(now)` derives the phase and
  the seconds, flipping phases by arithmetic, so a phase that ends while the app is closed is right at the next
  draw and the drawer never writes settings; every change from the controls first moves the stored phase up to
  now. Like Moblin's one second tick, each value, `00:00` included, shows for a whole second. It redraws only at
  those seconds and only while running. Its size is a percent of the picture's short side, and the width setting
  stretches the box. Moblin's sound and chat message on a phase change are not ported.
- The QR code widget encodes with ZXing core (level M, UTF-8 bytes, one module of quiet zone, black on white like
  Core Image) and draws whole pixel modules as close as it can to its size (percent of the picture's short side),
  never larger than the picture. An empty or too long message draws nothing.

## Filters

- `AppSettings.effects` holds the filters that are on (`Filter`), their strengths and the LUTs. They are global,
  not per scene, as Moblin's filter quick buttons are. The Filters quick button opens `ui/Panels.kt`'s
  `FiltersPanel`: one switch per filter, with the sliders of a filter under it while it is on. The button is
  lit while any filter or LUT is on.
- `effects/EffectLoader` turns the settings into `EffectSettings` off the main thread (it decodes the LUTs,
  the face image and the logo) and `StreamEngine` hands them to `VideoCompositor.setEffects`.
- `media/gl/EffectRenderer` runs on the compositor thread. With no filter on, the compositor draws the camera
  straight to each surface as before and holds no framebuffer. With one on, the camera is drawn once into a
  texture of the output size (in stream orientation), the passes run between two textures, and the result is
  copied to the encoder, the recorder, snapshots and the preview (flipped for a mirrored front camera).
  Widgets and the drawing are drawn after the filters, so they are never filtered.
- Pass order: LUTs, glasses and sparkle, blur faces, text and background, logo in mouth, beauty, whirlpool,
  pinch, 4:3, black and white, sepia, triple, twin, pixellate, CRT. Everything that needs a face runs before
  the passes that move the picture, so detections line up (Moblin runs beauty later). Shaders are GLSL ES
  3.00; programs compile at first use.
- LUTs are 3D textures of at most 64 entries a side. `effects/LutCube.kt` reads a `.cube` file or a square
  image of tiles (one blue slice per tile, as Moblin's PNG LUTs). The bundled LUT is `assets/luts/<id>.png`;
  imported ones are copies in `files/effects/`, like the face image. A LUT that cannot be read raises an
  alert once and is skipped.
- Faces and text come from ML Kit (`effects/Detector`, its own thread; bundled models, nothing is downloaded
  and no picture leaves the phone). While a filter needs them, the renderer copies the unfiltered picture
  into a small texture (640 px on the long side, 1280 px when text is due), reads it through a pixel buffer
  object one frame later and hands it to the detector, one detection at a time: faces at most every 80 ms,
  text every 400 ms. Results stay until the next detection replaces them; faces are kept for 400 ms after a
  detection that finds none, so a blur does not flicker off. Contours (the lips, for the logo in the mouth)
  are only requested while that filter is on, and ML Kit then gives them for the most prominent face only.
  The detector is released when no filter needs it.
- Blur faces, blur text and blur background share one pass and one "hide with" mode (`PrivacyMode`): a
  Gaussian blur made at a quarter of the size, pixels, an image scaled to fill, or the logo over each face.
  Blur background hides everything but the faces, and then faces are never hidden.
- Glasses and sparkle (`Reaction`) are the GIFs in `assets/props/`, decoded with `android.graphics.Movie`
  and drawn on every face for one run (glasses loop three times). The clock starts at the first detection,
  and a prop gives up after 10 seconds without a face.
- Remove background is per widget (image and browser): a chroma key between two hues (`chromaKey`) in the
  overlay shaders, as Moblin's MetalPetal path.
- Fixed horizon (Settings, Camera) is not a pass: `StreamEngine` listens to the gravity sensor ten times a
  second while the preview runs and sets `VideoCompositor.horizonTilt`, the roll beyond the display rotation
  (`horizonTilt`, null while the phone lies flat, which keeps the last tilt). The compositor eases towards it
  each frame (`horizonStep`) and folds the rotation and the zoom that keeps the frame covered into the
  camera's texture matrix, with the opposite sign on the stream of a mirrored preview. Built-in cameras only.
- `VideoCompositor.repeatFrame` posts at most one pending repeat, so frames that take longer than 50 ms
  cannot pile up behind a playing video.
- The release build needs `-keep class * implements ...ComponentRegistrar { <init>(); }`
  (`app/proguard-rules.pro`): R8 otherwise removes the constructors ML Kit creates by reflection and every
  detection fails. Check a filter that needs detection on a release build after changing ML Kit or R8.

## Auto scene switcher, macros and drawing

- `AppSettings.autoSwitchers` are lists of scenes with a time each; `autoSwitcherId` is the one running, kept
  across restarts. `scenes/AutoSceneSwitcher` gives the next entry: in order, or shuffled a round at a time
  without showing the same scene twice in a row, skipping entries whose scene is gone. `StreamEngine` runs it
  while the preview is active. The Switcher quick button opens the list to pick one or Off.
- `AppSettings.macros` are lists of actions (`MacroFunction`): scene, zoom, filters (the set replaces the
  filters that are on), reaction, record, snapshot, mute, torch, auto switcher, chat message, notification
  (an alert titled with the macro's name), delay, wait for event, if, run macro. `macros/MacroRunner` runs
  each started macro as a coroutine. If compares two texts after `{time}` and `{date}` are filled in, as
  numbers when both start with one, and skips the next `runCount` actions when false. Wait for event takes
  events from a queue of 100 that fills while the macro runs: stream started and stopped, recording started
  and stopped, scene switched (optionally to one scene). A macro never runs a macro that is already on its
  stack. A repeated macro waits a second between runs that had no delay. "Run when the app starts" starts it
  at the first preview. Moblin's Twitch and Kick events, scene enabling, DJI and gimbal actions are not ported.
- The Macros quick button lists the macros with run and stop; it is lit while one runs.
- Draw (`widgets/DrawBoard`): while the quick button is on, drags on the preview draw lines, and a strip over
  the preview has wipe, undo, six colours and the width. The lines are one overlay over the whole picture, on
  the stream as well, drawn into a bitmap no wider than 1280 px that is uploaded at most 30 times a second.
  They stay after leaving draw mode until wiped, and are not saved.
- The Overlays quick button hides the status items and the chat over the preview until it is pressed again.

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
  share, audio meter, in that order, on one rounded scrim), the zoom stops and the scene row in its bottom
  right, and a control bar at the far end with the opIRL logo and the settings button on top, the quick buttons
  under them and the 52 dp Go Live button at the bottom. Quick buttons are 40 dp in 48 dp cells. With no panel
  the bar takes the width the preview leaves (100 to 250 dp) and shows as many button columns as fit (three on
  the vivo); with a panel open it is 100 dp with two columns, as in Moblin. `QuickButtonHeader` places the
  logo over the grid's first column and the settings button over its last, with the same spring as the grid.
  The logo is drawn by `AppLogo` with its dot in the theme's primary colour (the `ic_logo` drawable, used by
  the logo in mouth filter, keeps the red dot). A quick button list that scrolls fades out at its end
  (`fadeEnd`), so no button is cut off by an edge.
- Quick buttons belong to one of three categories (`QuickButtonPage`: Stream, Scene, Connect), by default
  `QuickButtonId.page`; `AppSettings.quickButtonPages` stores only the buttons the user moved. The wide control
  bar swipes between the categories that have a visible button (`QuickButtonPages`: a `HorizontalPager` of
  `QuickButtonGrid`s, each scrolling on its own, with page dots above Go Live), while the logo, the settings
  button and Go Live stay put. The other layouts show one scrolling row in category order. Settings, Display,
  Quick buttons lists the buttons by category with their switch; tapping a row picks its category. Near-square screens (the Flip cover,
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
- Without a chat account that can send, the chat panel's input is a "Log in to send messages" field that opens
  Settings, Chat, where the accounts are.
- The chat panel keeps its draft and whether the stream tools page is open in `MainScreen`, so hiding the
  panel loses neither.
- Panels (settings, chat, widgets, OBS, browser, filters, auto switcher, macros, remote, bitrate) sit between the preview and the control bar on wide screens (350 dp, Moblin's
  `settingsHalfWidth`) and fill the screen on near-square ones. Every panel is a `PanelSurface`
  (`surfaceContainerLow`, 24 dp corners, inset 6 dp) with a `PanelHeader` (`Panels.kt`): 52 dp, a back button
  when there is something to go back to, the title on the left (or a title slot, such as the browser's address
  field and the chat status on the cover screen), the panel's own actions, then `PanelButtons`: hide and close,
  extra small filled icon buttons with the expressive shape morph. They are 32 dp with 16 dp between them, so
  their 48 dp touch targets do not overlap. An empty panel shows `PanelNote` with an icon. Hide takes the panel off the screen and
  keeps the settings page (`SettingsPages`, reset once the panel has closed) and the chat draft, which
  live in `MainScreen`; the two buttons then float in the preview's top right and the status moves under
  them. Settings pages follow Moblin's categories and drill down with a back stack. Rows are
  `SegmentedListItem`s built with `Group { row { ... } }` from `SettingsWidgets.kt`, in `surfaceContainerHigh`,
  capped at 48 dp (60 dp with a supporting line) times the font scale, with 15 sp titles. The cap is a
  `heightIn(max)`, because the list item's own minimum is 56 dp. A `ChoiceRow`'s connected toggle buttons
  share the row's width equally, or in proportion to their labels when the widest label would not fit an
  equal share; a choice that cannot be picked keeps a readable label. A `TextRow` is `FilledField`, the wizard's text field drawn in the row's shape with a primary
  outline while focused, so a field is the row and never a box inside one.
- The microphone is open whenever the preview runs, so the audio meter works before going live: an
  `AudioEncoder` without a sink only reads and meters. It reports the peak level in dB about nine times a
  second to `StreamEngine.audioLevel`, which the meter reads in its draw phase only.
- The status temperature is the battery temperature from `ACTION_BATTERY_CHANGED`, coloured by the thermal
  status.
- `LiveStats.links` is each bonded link's share of the bytes sent in the last second (`linkShares`), from
  `BondedPublisher.linkUsage` (SRTLA and RIST).
- `AppSettings` stores the status items and quick buttons the user hid, not the ones shown, so an item
  added later is shown by default.
- `streamUrl` adds the profile's SRT latency to an `srt`/`srtla` URL that does not name one.
  `StreamProfile.bondingNetworks` limits which networks bonding requests: for `srtla://` always, for
  `rist://` when the stream's Bonding switch (`ristBonding`) is on.
- `mirrorFrontCameraOnStream` makes the encoder draw the same mirrored picture as the preview.
- The Widgets quick button opens a panel that turns the selected scene's widgets on and off. Scenes,
  widgets, auto scene switchers and macros are edited under Settings, Scenes (`ui/Automation.kt` has the
  last two): a scene page has the source (a camera or Video), the video list when the source is
  Video, and every widget with a switch; tapping a widget opens its placement in that scene.
- The OBS quick button (OBS's own logo, `ObsLogo`) opens the OBS panel: the program picture with the
  stream and record buttons beside it, the scenes as toggle buttons, the audio inputs with their mute
  state. Stopping an output takes a second tap within 3 seconds. A stream without OBS set up gets the URL
  and password fields in the panel; an unreachable OBS shows why and a link to Streams, the stream, OBS
  remote. The OBS status item (scene, live, rec, or offline) shows while OBS is enabled for the stream.
- The Remote quick button shows while the remote control assistant is on and opens the Remote panel
  (`ui/Remote.kt`): the streamer's picture with its uptime, bitrate, bonding shares and battery (coloured
  by the streamer's flame) over it and its zoom presets in the bottom right, then Go Live or End, record,
  mute, torch and stealth, then the scenes and the bitrate presets. End and stopping a recording take a
  second tap within 3 seconds. The status texts are the streamer's own (`uptime`, `srtla`), so they read
  the same for a Moblin streamer. Until a streamer connects the panel shows why, the `ws://` addresses to
  enter on the streamer, and a link to Remote control, Assistant.
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
- The Replay quick button (shown while replay is on for the stream) opens `ui/Replay.kt`'s overlay in
  the lower right of the picture, Moblin's layout, in place of the zoom stops and the scene row: the
  selected replay's picture with its delete button, a row with the start slider, the speed, play or stop
  and save, and the saved replays as thumbnails, newest first. The slider is the start point counted back
  from the end of the clip (`ReplayPanel.fromEndMs`, kept in `MainScreen` while the app runs, at least a
  second plays), and the picture and the selected thumbnail show the frame there. The newest replay is
  selected when the overlay opens and whenever one is saved. Delete takes a second tap within 3 seconds.
  A picture lower than 260 dp (the cover screen, a preview beside an open panel) gets the compact form:
  the thumbnails with the delete button, then the row, no large picture. In the near-square layout the
  overlay is as wide as the picture, or 300 dp for a narrow portrait picture.
- Settings, Remote control, Moblink, Game controller, Keyboard and Stream Deck follow Moblin's pages. A
  function is chosen on its own page (`Route.ActionPage`) because there are too many for a choice row.
  The Moblink and Remote control status items show while the feature is on: an icon, and its text on wide
  layouts.
- The Bitrate quick button opens the Bitrate panel, the bitrate presets as a pick list; picking one sets it and
  closes the panel.
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

`app/proguard-rules.pro` keeps the no-argument constructors of ML Kit's `ComponentRegistrar`s: R8 removes them
otherwise (they are only called by reflection) and the face detector then fails at runtime in the release build
only, which no build step or unit test shows. Run the release APK after adding a library that uses reflection.

Engine tests that talk to a real server use the system `ffmpeg`/`ffprobe` as the receiver and are
skipped when they are missing. The SRTLA end-to-end tests also need `SRTLA_REC` pointing at a built
`srtla_rec` from https://github.com/BELABOX/srtla. The WHIP end-to-end tests need `MEDIAMTX` pointing at
a MediaMTX binary (https://github.com/bluenviron/mediamtx/releases): they publish to it and read the
stream back over RTSP. The RIST tests use ffmpeg's `rist` protocol (librist) as the receiver, with
`-rw_timeout` so it ends by itself: once media stops, ffmpeg's librist input ignores a single SIGTERM,
and a second one makes it exit without flushing its output.

## Connections

Moblink, remote control and the web remote speak Moblin's protocols, so opIRL and Moblin work with each
other. Messages are Swift's Codable JSON: an enum case is an object with one key (`{"setLive":{"on":true}}`,
`{"ok":{}}`), and an optional that is nil is left out. The password proof is `hashPassword` (the same
formula as OBS). Codecs read and write `JsonElement` trees; a test pins each side against literal Moblin
JSON.

- `net/WebServer(port, handler)`: blocking sockets, one reader and one writer thread per connection, at most
  32 connections. A request with `Upgrade: websocket` becomes a `WebSocketConnection`, anything else goes to
  `onHttp` and the connection closes. `send` queues (128 frames) and never blocks; a full queue ends the
  connection. `close()` sends what is queued first; a second `close()` or `stop()` cuts it. Handler calls
  come from the connection's reader thread. A stopped server never binds again: `start()` after `stop()`
  does nothing, so a bind that is still running when its owner stops cannot leave a listener behind.
  The Moblink streamer, the Moblink relay (its streamers come from mDNS, so they are not chosen by the
  user) and the remote control assistant handle each message on their executor before the connection
  reads the next one (`deliver`), which bounds what a connection can queue.
- Use `String(bytes, charset)` in the engine, never `ByteArrayOutputStream.toString(Charset)`: that one is
  API 33 and the engine is not covered by Android lint.
- Everything in `StreamEngine.syncConnections` (Moblink streamer, remote control, web remote, Stream Deck)
  runs while the preview runs or the app is live or recording, like the OBS client, and is rebuilt when its
  settings change (500 ms debounce). The Moblink relay is the exception: once started it runs until it is
  turned off, with the foreground service holding it (`connectedDevice` type only, title "Moblink relay").
- On Android 17 an app that targets API 37 needs the runtime permission `ACCESS_LOCAL_NETWORK` for any
  traffic on the local network. The Remote control, Moblink and OBS pages show an Allow row while it is
  missing (`LocalNetworkAccess`); granting it calls `StreamEngine.restartConnections`.

### Moblink

- Streamer (`MoblinkStreamer`, `moblink/MoblinkController`): a WebSocket server on the Moblink port,
  advertised with `NsdManager` as `_moblink._tcp`, service name the device's id, TXT `name` the device name.
  Each relay gets `hello`, answers `identify`, and is asked for its status every 5 s; a relay that does not
  answer a ping is dropped. While live over SRTLA, `SrtPublisher`'s `onServer` hands the resolved server
  address to `startTunnels`; each relay answers with a UDP port, and that tunnel becomes a `NetworkLink`
  with `destination` set to the relay's address. `SrtlaLink` connects to `destination` when a link has one,
  to the server otherwise. `StreamEngine.streamLinks` is the phone's own links plus the tunnels. The tunnel
  socket is bound to the network the relay's connection came in on, and left unbound when that is no
  Android network (the phone's own hotspot).
- Relay (`MoblinkRelay`): one per streamer URL and per cellular or Ethernet network of this phone. The
  WebSocket and the socket facing the streamer go over Wi-Fi, the socket facing the server over the mobile
  network, and the server name is resolved on that network too. Streamers come from mDNS discovery, or from
  the one URL in manual mode. Two threads forward packets with one reused buffer each. The relay's id and
  name are generated once (`AppSettings.withIdentity`) and kept.
- The status item shows each relay's name, battery and a flame when it is hot, then the relay state.

### Remote control

- `RemoteControlTarget` is the phone as seen by a controller: `status()`, `settings()`, `state()` and
  `handle(request)`. `StreamEngine.remoteTarget` implements it with the pure functions in
  `remote/RemoteModel.kt`. A request the remote control does not handle (filters and macros, which are not
  wired to it yet, gimbal, scoreboard, SRT priorities) is `RemoteRequest.Unknown` and answered
  `unknownRequest`; a known one that cannot be done now
  (another stream while live, an unknown scene) is answered `error`.
- Ids on the wire are UUIDs. Scenes and streams have one; a bitrate preset's and a zoom stop's are derived
  from its value (`bitratePresetId`, `zoomPresetId`), a mic's is its `Mic.key`, or `AUTO` for the system's choice.
- `RemoteControlStreamer` connects out to the assistant's URL (direct, or a relay's `/streamer/<bridge id>`),
  retries every 5 s, pings every 30 s and reconnects without a pong. `RemoteControlWeb` serves the pages
  built from `WebRemoteControlFrontend/` (copied into the APK's assets at build time by `copyWebRemote`, so
  never committed twice) on its port and WebSockets on that port and the next one, which is the one
  `/js/config.mjs` names, as in Moblin. The web remote has no password, as in Moblin. Ports under 1024
  cannot be bound on Android, so the default is 8080.
- State goes out as changes only (`RemoteState.changesFrom`). The preview is a 640 px JPEG once a second
  from `VideoCompositor.takeThumbnail`, rendered at that size, and only while a controller asked for it.
- `RemoteControlAssistant` is the other side: a server the streamer connects to, with `state` (the
  streamer's state, settings and status) and `preview` as `StateFlow`s and `request()` for commands.
  `RemoteControlBridge` connects it to a relay server so a streamer can reach it from anywhere. Its state
  changes only from the streamer's events, never optimistically. `setActive(true)` asks for the preview
  and polls the status every second; the Remote panel turns it on only while it is on screen and the
  activity is started.
- Stealth mode is `UiState.stealth`, so a controller can set it.

### Deep links

- `settings/DeepLink.kt`: `moblin://?<URL encoded JSON>`, Moblin's `MoblinSettingsUrl` format.
  `parseDeepLink` throws `DeepLinkException` with a fixed English message (mapped in `ui/Messages.kt`).
  Streams, the quick buttons opIRL has, and remote control are applied; the rest is ignored. A resolution or
  frame rate opIRL does not have is refused. A plus sign is kept as it is, stream keys contain them.
- The activity takes `moblin://` VIEW intents; `StreamEngine.pendingLink` holds the link until
  `DeepLinkDialog` has asked. A stream whose name exists is replaced or added beside the old one, the user
  chooses. Nothing is imported while live or recording.
- `createDeepLink` writes the same format (Share link on a stream, Share streams under Import and export).
  An RTMP stream key goes into the URL's last path segment, where Moblin keeps it. AV1 is left out because
  Moblin refuses a codec it does not know.

### Controllers

- `ControllerAction` (a `ControllerFunction` plus the scene, widget or layout it names) is what a keyboard
  key, a game controller button and a Stream Deck key are bound to; `StreamEngine.perform(action, pressed)`
  runs it. Everything acts on release, except zoom in and out, which ramp while held.
- `control/Controls`: game controller keys are taken in `dispatchKeyEvent`, before Compose can use the D-pad
  for focus, and only when the button is bound. Triggers and the D-pad also arrive as axes
  (`dispatchGenericMotionEvent`); a button is down while its key or its axis says so. Keyboard keys are
  taken in `onKeyDown`/`onKeyUp`, so a focused text field gets them first; a key is its typed character,
  and Ctrl or Meta combinations are left alone. One mapping serves every controller.
- `control/StreamDecks`: a userspace HID driver over `UsbManager` for Elgato Stream Decks (vendor 0x0fd9):
  Mini (6 keys, 80 px BMP), the 15 key models from the second hardware revision on, and XL (72 and 96 px
  JPEG). The first 15 key model (product 0x0060) speaks an older protocol and is not supported.
  `StreamDeckProtocol.kt` builds the reports and is unit tested; it follows python-elgato-streamdeck. No
  Stream Deck has been plugged into it yet, so treat the driver as untested on hardware. Key pictures are
  the key's text on its colour, sent only when they change. The read loop wakes every 2 s on a timeout;
  unplugging is noticed through the USB detach broadcast.

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
