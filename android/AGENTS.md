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
- Encoders run without B-frames, so DTS equals PTS. Timestamps are microseconds on one clock.
- `MediaSink` calls come from encoder threads and never block. A `Publisher` owns a bounded backlog;
  on overflow it drops video until the next key frame before it drops audio.
- A `Publisher` is one-shot: after `stop()` or `onDisconnected` it is dead. The app's stream session
  owns reconnection with backoff.

## Commands

Run from `android/`. `local.properties` (gitignored) needs `sdk.dir=$HOME/Android/Sdk`.

```sh
./gradlew :engine:test          # JVM unit and loopback tests
./gradlew :app:assembleDebug    # builds the APK (arm64-v8a)
./gradlew :app:installDebug     # installs on the connected device
```

Engine tests that talk to a real server use the system `ffmpeg`/`ffprobe` as the receiver and are
skipped when they are missing.

## Conventions

- No comments or docstrings in code, same as the iOS project. Contracts live in this file.
- Performance matters more than abstraction: the phone's budget belongs to the encoder. No per-frame
  allocations in hot paths that can reuse a buffer, no polling loops, no continuously repainting UI.
- Long sessions must not degrade: bounded queues everywhere, every thread and socket closed on stop.
