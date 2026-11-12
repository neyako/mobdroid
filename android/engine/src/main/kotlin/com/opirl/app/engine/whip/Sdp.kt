package com.opirl.app.engine.whip

import com.opirl.app.engine.media.VideoCodec
import java.net.InetAddress
import java.net.InetSocketAddress

internal const val OPUS_PAYLOAD_TYPE = 111
internal const val H264_PAYLOAD_TYPE = 96
internal const val HEVC_PAYLOAD_TYPE = 97
internal const val RTX_PAYLOAD_TYPE = 98
internal const val AUDIO_MID = "0"
internal const val VIDEO_MID = "1"

private const val H264_FMTP = "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"
private const val OPUS_FMTP = "minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1"

internal class SdpException(message: String) : Exception(message)

internal enum class DtlsSetup(val attribute: String) {
    ACTPASS("actpass"),
    ACTIVE("active"),
    PASSIVE("passive"),
    ;

    companion object {
        fun parse(value: String): DtlsSetup? = entries.firstOrNull { it.attribute == value.trim().lowercase() }
    }
}

internal class LocalCandidate(val foundation: Int, val priority: Long, val address: InetAddress, val port: Int)

internal class IceCandidate(val foundation: String, val priority: Long, val address: InetSocketAddress, val type: String)

internal class SdpOfferParams(
    val sessionId: Long,
    val iceUfrag: String,
    val icePassword: String,
    val fingerprint: String,
    val setup: DtlsSetup,
    val cname: String,
    val streamId: String,
    val audioTrackId: String,
    val videoTrackId: String,
    val audioSsrc: Int,
    val videoCodec: VideoCodec,
    val videoSsrc: Int,
    val rtxSsrc: Int,
    val offerRtx: Boolean,
    val candidates: List<LocalCandidate>,
)

internal fun videoPayloadType(codec: VideoCodec): Int =
    when (codec) {
        VideoCodec.H264 -> H264_PAYLOAD_TYPE
        VideoCodec.HEVC -> HEVC_PAYLOAD_TYPE
        VideoCodec.AV1 -> throw IllegalArgumentException("AV1 is not supported over WHIP")
    }

internal fun buildOffer(params: SdpOfferParams): String {
    val videoPayload = videoPayloadType(params.videoCodec)
    val sdp = StringBuilder()
    fun line(text: String) {
        sdp.append(text).append("\r\n")
    }
    fun transport() {
        line("a=ice-ufrag:${params.iceUfrag}")
        line("a=ice-pwd:${params.icePassword}")
        line("a=fingerprint:sha-256 ${params.fingerprint}")
        line("a=setup:${params.setup.attribute}")
    }
    line("v=0")
    line("o=- ${params.sessionId} 2 IN IP4 127.0.0.1")
    line("s=-")
    line("t=0 0")
    line("a=group:BUNDLE $AUDIO_MID $VIDEO_MID")
    line("a=msid-semantic: WMS ${params.streamId}")
    line("m=audio 9 UDP/TLS/RTP/SAVPF $OPUS_PAYLOAD_TYPE")
    line("c=IN IP4 0.0.0.0")
    line("a=rtcp:9 IN IP4 0.0.0.0")
    transport()
    line("a=mid:$AUDIO_MID")
    line("a=sendonly")
    line("a=msid:${params.streamId} ${params.audioTrackId}")
    line("a=rtcp-mux")
    line("a=rtpmap:$OPUS_PAYLOAD_TYPE opus/48000/2")
    line("a=rtcp-fb:$OPUS_PAYLOAD_TYPE nack")
    line("a=fmtp:$OPUS_PAYLOAD_TYPE $OPUS_FMTP")
    line("a=ssrc:${params.audioSsrc.toUInt()} cname:${params.cname}")
    line("a=ssrc:${params.audioSsrc.toUInt()} msid:${params.streamId} ${params.audioTrackId}")
    for (candidate in params.candidates) {
        line("a=candidate:${candidate.foundation} 1 udp ${candidate.priority} ${candidate.address.hostAddress} ${candidate.port} typ host")
    }
    line("a=end-of-candidates")
    val formats = if (params.offerRtx) "$videoPayload $RTX_PAYLOAD_TYPE" else "$videoPayload"
    line("m=video 9 UDP/TLS/RTP/SAVPF $formats")
    line("c=IN IP4 0.0.0.0")
    line("a=rtcp:9 IN IP4 0.0.0.0")
    transport()
    line("a=mid:$VIDEO_MID")
    line("a=sendonly")
    line("a=msid:${params.streamId} ${params.videoTrackId}")
    line("a=rtcp-mux")
    when (params.videoCodec) {
        VideoCodec.H264 -> line("a=rtpmap:$videoPayload H264/90000")
        VideoCodec.HEVC -> line("a=rtpmap:$videoPayload H265/90000")
        VideoCodec.AV1 -> throw IllegalArgumentException("AV1 is not supported over WHIP")
    }
    line("a=rtcp-fb:$videoPayload nack")
    line("a=rtcp-fb:$videoPayload nack pli")
    line("a=rtcp-fb:$videoPayload ccm fir")
    line("a=rtcp-fb:$videoPayload goog-remb")
    if (params.videoCodec == VideoCodec.H264) {
        line("a=fmtp:$videoPayload $H264_FMTP")
    }
    if (params.offerRtx) {
        line("a=rtpmap:$RTX_PAYLOAD_TYPE rtx/90000")
        line("a=fmtp:$RTX_PAYLOAD_TYPE apt=$videoPayload")
        line("a=ssrc-group:FID ${params.videoSsrc.toUInt()} ${params.rtxSsrc.toUInt()}")
    }
    line("a=ssrc:${params.videoSsrc.toUInt()} cname:${params.cname}")
    line("a=ssrc:${params.videoSsrc.toUInt()} msid:${params.streamId} ${params.videoTrackId}")
    if (params.offerRtx) {
        line("a=ssrc:${params.rtxSsrc.toUInt()} cname:${params.cname}")
        line("a=ssrc:${params.rtxSsrc.toUInt()} msid:${params.streamId} ${params.videoTrackId}")
    }
    return sdp.toString()
}

internal class AnswerMedia(
    val mid: String,
    val rejected: Boolean,
    val payloadType: Int,
    val rtxPayloadType: Int,
    val nack: Boolean,
    val pli: Boolean,
    val fir: Boolean,
    val remb: Boolean,
    val transportCc: Boolean,
)

internal class SdpAnswer(
    val iceUfrag: String,
    val icePassword: String,
    val iceLite: Boolean,
    val fingerprintAlgorithm: String,
    val fingerprint: ByteArray,
    val setup: DtlsSetup,
    val candidates: List<IceCandidate>,
    val audio: AnswerMedia?,
    val video: AnswerMedia?,
)

private class SdpSection(val kind: String, val port: Int, val formats: List<Int>) {
    val attributes = ArrayList<Pair<String, String>>()

    fun first(name: String): String? = attributes.firstOrNull { it.first == name }?.second

    fun all(name: String): List<String> = attributes.filter { it.first == name }.map { it.second }

    fun has(name: String): Boolean = attributes.any { it.first == name }
}

private fun splitSdp(text: String): Pair<SdpSection, List<SdpSection>> {
    val session = SdpSection("session", 0, emptyList())
    val sections = ArrayList<SdpSection>()
    var current = session
    for (raw in text.split('\n')) {
        val line = raw.trim()
        if (line.length < 2 || line[1] != '=') {
            continue
        }
        if (line.startsWith("m=")) {
            val parts = line.substring(2).split(' ').filter { it.isNotEmpty() }
            if (parts.size < 3) {
                throw SdpException("Malformed media line")
            }
            current = SdpSection(parts[0], parts[1].toIntOrNull() ?: throw SdpException("Malformed media port"), parts.drop(3).mapNotNull { it.toIntOrNull() })
            sections.add(current)
        } else if (line.startsWith("a=")) {
            val body = line.substring(2)
            val colon = body.indexOf(':')
            if (colon < 0) {
                current.attributes.add(body to "")
            } else {
                current.attributes.add(body.substring(0, colon) to body.substring(colon + 1))
            }
        }
    }
    return session to sections
}

internal fun parseCandidate(value: String): IceCandidate? {
    val parts = value.trim().split(' ').filter { it.isNotEmpty() }
    if (parts.size < 8 || parts[6] != "typ") {
        return null
    }
    if (parts[1] != "1" || !parts[2].equals("udp", ignoreCase = true) || parts.contains("tcptype")) {
        return null
    }
    val priority = parts[3].toLongOrNull() ?: return null
    val port = parts[5].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val host = parts[4]
    if (host.endsWith(".local")) {
        return null
    }
    val address = try {
        InetAddress.getByName(host)
    } catch (_: Exception) {
        return null
    }
    return IceCandidate(parts[0], priority, InetSocketAddress(address, port), parts[7])
}

private fun parseFingerprint(value: String): Pair<String, ByteArray> {
    val parts = value.trim().split(' ').filter { it.isNotEmpty() }
    if (parts.size != 2) {
        throw SdpException("Malformed fingerprint")
    }
    val bytes = parts[1].split(':').map { it.toIntOrNull(16)?.toByte() ?: throw SdpException("Malformed fingerprint") }.toByteArray()
    return parts[0].lowercase() to bytes
}

private class Rtpmap(val payloadType: Int, val name: String)

private fun interpretMedia(section: SdpSection, mid: String, wantedName: String, offeredPayload: Int): AnswerMedia {
    val rejected = section.port == 0 || section.has("inactive")
    val maps = section.all("rtpmap").mapNotNull { entry ->
        val space = entry.indexOf(' ')
        val payload = entry.substring(0, maxOf(space, 0)).toIntOrNull() ?: return@mapNotNull null
        Rtpmap(payload, entry.substring(space + 1).substringBefore('/').lowercase())
    }
    val fmtps = section.all("fmtp").mapNotNull { entry ->
        val space = entry.indexOf(' ')
        val payload = entry.substring(0, maxOf(space, 0)).toIntOrNull() ?: return@mapNotNull null
        payload to entry.substring(space + 1)
    }
    val candidates = maps.filter { it.name == wantedName && it.payloadType in section.formats }
    val payload = candidates.firstOrNull { it.payloadType == offeredPayload }?.payloadType ?: candidates.firstOrNull()?.payloadType ?: -1
    val rtx = maps.firstOrNull { rtpmap ->
        rtpmap.name == "rtx" && rtpmap.payloadType in section.formats &&
            fmtps.any { (type, value) -> type == rtpmap.payloadType && value.split(';').any { it.trim() == "apt=$payload" } }
    }?.payloadType ?: -1
    val feedback = section.all("rtcp-fb").filter { entry ->
        val target = entry.substringBefore(' ')
        target == "*" || target.toIntOrNull() == payload
    }.map { it.substringAfter(' ').trim() }
    return AnswerMedia(
        mid = mid,
        rejected = rejected || payload < 0,
        payloadType = payload,
        rtxPayloadType = rtx,
        nack = feedback.contains("nack"),
        pli = feedback.contains("nack pli"),
        fir = feedback.contains("ccm fir"),
        remb = feedback.contains("goog-remb"),
        transportCc = feedback.contains("transport-cc"),
    )
}

internal fun parseAnswer(text: String, videoCodec: VideoCodec, offeredVideoPayload: Int): SdpAnswer {
    val (session, sections) = splitSdp(text)
    if (sections.isEmpty()) {
        throw SdpException("Answer has no media sections")
    }
    fun transportValue(name: String): String? =
        sections.firstOrNull { it.port != 0 && it.has(name) }?.first(name) ?: session.first(name)
    val ufrag = transportValue("ice-ufrag") ?: throw SdpException("Answer has no ice-ufrag")
    val password = transportValue("ice-pwd") ?: throw SdpException("Answer has no ice-pwd")
    for (section in sections.filter { it.port != 0 }) {
        val other = section.first("ice-ufrag")
        if (other != null && other != ufrag) {
            throw SdpException("Answer does not bundle its media")
        }
    }
    val (algorithm, hash) = parseFingerprint(transportValue("fingerprint") ?: throw SdpException("Answer has no fingerprint"))
    val setup = DtlsSetup.parse(transportValue("setup") ?: throw SdpException("Answer has no setup"))
        ?: throw SdpException("Answer has an unknown setup")
    val candidates = ArrayList<IceCandidate>()
    val seen = HashSet<String>()
    for (owner in listOf(session) + sections.filter { it.port != 0 }) {
        for (value in owner.all("candidate")) {
            val parsed = parseCandidate(value) ?: continue
            if (seen.add("${parsed.address.address.hostAddress}:${parsed.address.port}")) {
                candidates.add(parsed)
            }
        }
    }
    var audio: AnswerMedia? = null
    var video: AnswerMedia? = null
    sections.forEachIndexed { index, section ->
        val mid = section.first("mid") ?: index.toString()
        when {
            section.kind == "audio" && mid == AUDIO_MID -> audio = interpretMedia(section, mid, "opus", OPUS_PAYLOAD_TYPE)
            section.kind == "video" && mid == VIDEO_MID ->
                video = interpretMedia(section, mid, if (videoCodec == VideoCodec.HEVC) "h265" else "h264", offeredVideoPayload)
        }
    }
    return SdpAnswer(
        iceUfrag = ufrag,
        icePassword = password,
        iceLite = session.has("ice-lite"),
        fingerprintAlgorithm = algorithm,
        fingerprint = hash,
        setup = setup,
        candidates = candidates,
        audio = audio,
        video = video,
    )
}

internal fun formatFingerprint(hash: ByteArray): String = hash.joinToString(":") { "%02X".format(it) }
