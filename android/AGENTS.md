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
  at a key frame. Backlog limit is 3 s of media or 16 MiB.
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
  Both cap the target near the measured transport bitrate, as Moblin does.

## Cameras

- `camera/`: `CameraCatalog.list` probes visible and hidden numeric camera ids (vivo, OPPO and Xiaomi
  hide auxiliary cameras), filters depth, secure, monochrome and low resolution sensors, dedupes lenses
  and derives zoom stops from field of view. It blocks for tens of milliseconds; call it off the main
  thread. `CameraController` reopens with backoff on errors, stalls and availability changes and steps
  down session options on configuration failures. Call `SurfaceTexture.setDefaultBufferSize` before
  `open`; `open` resets zoom, torch, exposure and locks.
- Samsung does not advertise 60 fps to Camera2 on most models, so the encoder runs at
  `min(profile fps, camera max fps)`.
- `uvc/`: userspace UVC for phones without an external camera HAL. libusb, libuvc and libjpeg-turbo are
  fetched by CMake at configure time with pinned hashes. MJPEG or YUYV frames are decoded into the
  compositor's `SurfaceTexture` through their own `Surface`, released after `stop()`. USB permission
  needs runtime CAMERA permission first. Cameras the OS exposes through Camera2 `LENS_FACING_EXTERNAL`
  go through `camera/` instead.

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

## Conventions

- No comments or docstrings in code, same as the iOS project. Contracts live in this file.
- Performance matters more than abstraction: the phone's budget belongs to the encoder. No per-frame
  allocations in hot paths that can reuse a buffer, no polling loops, no continuously repainting UI.
- Long sessions must not degrade: bounded queues everywhere, every thread and socket closed on stop.
