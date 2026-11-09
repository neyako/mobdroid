package app.mobdroid.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.mobdroid.engine.media.VideoCodec

val VideoCodec.mime: String
    get() = when (this) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        VideoCodec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
    }

val VideoCodec.label: String
    get() = when (this) {
        VideoCodec.H264 -> "H.264"
        VideoCodec.HEVC -> "HEVC"
        VideoCodec.AV1 -> "AV1"
    }

data class VideoEncoderInfo(
    val codec: VideoCodec,
    val name: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val supportsCbr: Boolean,
    private val capabilities: MediaCodecInfo.VideoCapabilities,
) {
    fun supports(width: Int, height: Int, fps: Int): Boolean =
        capabilities.areSizeAndRateSupported(width, height, fps.toDouble()) ||
            capabilities.areSizeAndRateSupported(height, width, fps.toDouble())
}

object CodecCatalog {
    val encoders: Map<VideoCodec, VideoEncoderInfo> by lazy { scan() }

    fun available(codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean =
        encoders[codec]?.supports(width, height, fps) == true

    fun choose(preferred: VideoCodec?, allowed: List<VideoCodec>, width: Int, height: Int, fps: Int): VideoCodec {
        if (preferred != null && preferred in allowed && available(preferred, width, height, fps)) return preferred
        return allowed.firstOrNull { available(it, width, height, fps) } ?: VideoCodec.H264
    }

    private val SPECIAL_SUFFIXES = listOf(".cq", ".hdr", ".low_latency", ".dv")

    private fun isSpecialPurpose(info: MediaCodecInfo, mime: String): Boolean =
        info.name.endsWith(".secure") ||
            info.getCapabilitiesForType(mime).isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)

    private fun scan(): Map<VideoCodec, VideoEncoderInfo> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && !it.isAlias
        }
        return VideoCodec.entries.mapNotNull { codec ->
            val info = infos.filter { codec.mime in it.supportedTypes && !isSpecialPurpose(it, codec.mime) }
                .minByOrNull { SPECIAL_SUFFIXES.count { suffix -> it.name.contains(suffix) } } ?: return@mapNotNull null
            val caps = info.getCapabilitiesForType(codec.mime)
            val video = caps.videoCapabilities ?: return@mapNotNull null
            val encoder = caps.encoderCapabilities ?: return@mapNotNull null
            codec to VideoEncoderInfo(
                codec = codec,
                name = info.name,
                maxWidth = video.supportedWidths.upper,
                maxHeight = video.supportedHeights.upper,
                supportsCbr = encoder.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                ),
                capabilities = video,
            )
        }.toMap()
    }
}
