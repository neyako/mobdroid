package com.opirl.app.engine.whip

import java.io.IOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.Hashtable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.signers.DSADigestSigner
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsFatalAlertReceived
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.Certificate as TlsCertificateChain

private const val DTLS_SEND_LIMIT = 1200
private const val DTLS_RECEIVE_LIMIT = 1500
private const val SRTP_LABEL = "EXTRACTOR-dtls_srtp"
private const val CERTIFICATE_DAYS = 30L

private val DTLS_CIPHER_SUITES = intArrayOf(
    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
)

internal class DtlsFingerprint(val algorithm: String, val hash: ByteArray) {
    fun matches(certificate: ByteArray): Boolean {
        val digest = try {
            MessageDigest.getInstance(javaName(algorithm)).digest(certificate)
        } catch (_: Exception) {
            return false
        }
        return MessageDigest.isEqual(digest, hash)
    }

    companion object {
        fun isSupported(algorithm: String): Boolean = runCatching { MessageDigest.getInstance(javaName(algorithm)) }.isSuccess

        private fun javaName(algorithm: String): String = algorithm.uppercase()
    }
}

internal class DtlsIdentity(val privateKey: ECPrivateKeyParameters, val encoded: ByteArray) {
    val fingerprint: ByteArray
        get() = MessageDigest.getInstance("SHA-256").digest(encoded)

    companion object {
        fun generate(random: SecureRandom): DtlsIdentity {
            val curve = CustomNamedCurves.getByName("secp256r1")
            val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
            val generator = ECKeyPairGenerator()
            generator.init(ECKeyGenerationParameters(domain, random))
            val pair = generator.generateKeyPair()
            val publicKey = pair.public as ECPublicKeyParameters
            val privateKey = pair.private as ECPrivateKeyParameters
            val signatureAlgorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
            val name = X500Name("CN=opirl")
            val now = System.currentTimeMillis()
            val generatorTbs = V3TBSCertificateGenerator()
            generatorTbs.setSerialNumber(ASN1Integer(BigInteger(63, random).add(BigInteger.ONE)))
            generatorTbs.setSignature(signatureAlgorithm)
            generatorTbs.setIssuer(name)
            generatorTbs.setSubject(name)
            generatorTbs.setStartDate(Time(Date(now - TimeUnit.DAYS.toMillis(1))))
            generatorTbs.setEndDate(Time(Date(now + TimeUnit.DAYS.toMillis(CERTIFICATE_DAYS))))
            generatorTbs.setSubjectPublicKeyInfo(
                SubjectPublicKeyInfo(AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256r1), publicKey.q.getEncoded(false)),
            )
            val tbs = generatorTbs.generateTBSCertificate()
            val signer = DSADigestSigner(ECDSASigner(), SHA256Digest())
            signer.init(true, ParametersWithRandom(privateKey, random))
            val tbsBytes = tbs.getEncoded(ASN1Encoding.DER)
            signer.update(tbsBytes, 0, tbsBytes.size)
            val vector = ASN1EncodableVector()
            vector.add(tbs)
            vector.add(signatureAlgorithm)
            vector.add(DERBitString(signer.generateSignature()))
            return DtlsIdentity(privateKey, DERSequence(vector).getEncoded(ASN1Encoding.DER))
        }
    }
}

internal class DtlsDatagramTransport(private val sender: (ByteArray, Int, Int) -> Unit) : DatagramTransport {
    private val inbound = ArrayBlockingQueue<ByteArray>(128)
    private val wakeMarker = ByteArray(0)

    @Volatile private var closed = false

    @Volatile var deadlineNanos = Long.MAX_VALUE

    fun offer(data: ByteArray, length: Int) {
        if (!closed) {
            inbound.offer(data.copyOf(length))
        }
    }

    fun wake() {
        inbound.offer(wakeMarker)
    }

    override fun getReceiveLimit(): Int = DTLS_RECEIVE_LIMIT

    override fun getSendLimit(): Int = DTLS_SEND_LIMIT

    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        val infinite = waitMillis == 0
        val waitNanos = TimeUnit.MILLISECONDS.toNanos(waitMillis.toLong())
        val started = System.nanoTime()
        while (true) {
            if (closed) {
                throw IOException("DTLS transport closed")
            }
            val now = System.nanoTime()
            var allowed = if (infinite) Long.MAX_VALUE else waitNanos - (now - started)
            val limit = deadlineNanos
            if (limit != Long.MAX_VALUE) {
                val untilDeadline = limit - now
                if (untilDeadline <= 0) {
                    throw SocketTimeoutException("DTLS handshake timed out")
                }
                allowed = minOf(allowed, untilDeadline)
            }
            if (allowed <= 0) {
                return -1
            }
            val data = inbound.poll(allowed, TimeUnit.NANOSECONDS)
            if (data == null) {
                if (!infinite) {
                    return -1
                }
                continue
            }
            if (data === wakeMarker) {
                if (closed) {
                    throw IOException("DTLS transport closed")
                }
                return -1
            }
            val count = minOf(data.size, len)
            System.arraycopy(data, 0, buf, off, count)
            return count
        }
    }

    override fun send(buf: ByteArray, off: Int, len: Int) {
        if (closed) {
            throw IOException("DTLS transport closed")
        }
        sender(buf, off, len)
    }

    override fun close() {
        closed = true
        wake()
    }
}

internal class DtlsResult(
    val transport: DTLSTransport,
    val profile: SrtpProfile,
    val keys: SrtpSessionKeys,
    val peerAlert: () -> String?,
)

internal class DtlsEndpoint(
    private val identity: DtlsIdentity,
    private val expected: DtlsFingerprint,
    private val profiles: List<SrtpProfile>,
    private val resendMillis: Int = 500,
    private val random: SecureRandom = SecureRandom(),
) {
    private val crypto = BcTlsCrypto(random)
    private val chain = TlsCertificateChain(arrayOf(crypto.createCertificate(identity.encoded)))

    private fun signer(context: org.bouncycastle.tls.TlsContext, algorithm: SignatureAndHashAlgorithm?): TlsCredentialedSigner =
        BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context),
            crypto,
            identity.privateKey,
            chain,
            algorithm ?: SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa),
        )

    private fun verifyPeer(certificate: TlsCertificateChain?) {
        val der = certificate?.takeIf { !it.isEmpty }?.getCertificateAt(0)?.encoded
        if (der == null || !expected.matches(der)) {
            throw TlsFatalAlert(AlertDescription.bad_certificate)
        }
    }

    private fun profileIds(): IntArray = profiles.map { it.dtlsId }.toIntArray()

    fun connectAsClient(transport: DtlsDatagramTransport): DtlsResult {
        val client = object : DefaultTlsClient(crypto) {
            var selected: SrtpProfile? = null
            var alert: String? = null
            var exported: ByteArray? = null

            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                exported = context.exportKeyingMaterial(SRTP_LABEL, null, srtpExportLength(selected ?: throw TlsFatalAlert(AlertDescription.handshake_failure)))
            }

            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()

            override fun getSupportedCipherSuites(): IntArray = TlsUtils.getSupportedCipherSuites(crypto, DTLS_CIPHER_SUITES)

            override fun getHandshakeResendTimeMillis(): Int = resendMillis

            override fun requiresExtendedMasterSecret(): Boolean = true

            override fun getClientExtensions(): Hashtable<Any?, Any?> {
                @Suppress("UNCHECKED_CAST")
                val extensions = super.getClientExtensions() as Hashtable<Any?, Any?>
                TlsSRTPUtils.addUseSRTPExtension(extensions, UseSRTPData(profileIds(), TlsUtils.EMPTY_BYTES))
                return extensions
            }

            override fun processServerExtensions(serverExtensions: Hashtable<*, *>?) {
                super.processServerExtensions(serverExtensions)
                val data = TlsSRTPUtils.getUseSRTPExtension(serverExtensions)
                if (data == null || data.protectionProfiles.size != 1) {
                    throw TlsFatalAlert(AlertDescription.handshake_failure)
                }
                selected = profiles.firstOrNull { it.dtlsId == data.protectionProfiles[0] } ?: throw TlsFatalAlert(AlertDescription.illegal_parameter)
            }

            override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
                alert = AlertDescription.getText(alertDescription)
            }

            override fun getAuthentication(): TlsAuthentication =
                object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                        verifyPeer(serverCertificate.certificate)
                    }

                    override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials? {
                        if (!certificateRequest.certificateTypes.contains(ClientCertificateType.ecdsa_sign)) {
                            return null
                        }
                        val chosen = TlsUtils.chooseSignatureAndHashAlgorithm(context, certificateRequest.supportedSignatureAlgorithms, SignatureAlgorithm.ecdsa)
                        return signer(context, chosen)
                    }
                }
        }
        val dtls = DTLSClientProtocol().connect(client, transport)
        val profile = client.selected ?: throw TlsFatalAlert(AlertDescription.handshake_failure)
        val exported = client.exported ?: throw TlsFatalAlert(AlertDescription.internal_error)
        return DtlsResult(dtls, profile, srtpFromDtlsExport(profile, exported, isDtlsClient = true)) { client.alert }
    }

    fun acceptAsServer(transport: DtlsDatagramTransport): DtlsResult {
        val server = object : DefaultTlsServer(crypto) {
            var selected: SrtpProfile? = null
            var alert: String? = null
            var exported: ByteArray? = null

            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                exported = context.exportKeyingMaterial(SRTP_LABEL, null, srtpExportLength(selected ?: throw TlsFatalAlert(AlertDescription.handshake_failure)))
            }

            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()

            override fun getSupportedCipherSuites(): IntArray = TlsUtils.getSupportedCipherSuites(crypto, DTLS_CIPHER_SUITES)

            override fun getHandshakeResendTimeMillis(): Int = resendMillis

            override fun requiresExtendedMasterSecret(): Boolean = true

            override fun getECDSASignerCredentials(): TlsCredentialedSigner {
                val offered = context.securityParametersHandshake.clientSigAlgs
                val chosen = TlsUtils.chooseSignatureAndHashAlgorithm(context, offered, SignatureAlgorithm.ecdsa)
                return signer(context, chosen)
            }

            override fun getCertificateRequest(): CertificateRequest =
                CertificateRequest(shortArrayOf(ClientCertificateType.ecdsa_sign), TlsUtils.getDefaultSupportedSignatureAlgorithms(context), null)

            override fun notifyClientCertificate(clientCertificate: TlsCertificateChain) {
                verifyPeer(clientCertificate)
            }

            override fun processClientExtensions(clientExtensions: Hashtable<*, *>?) {
                super.processClientExtensions(clientExtensions)
                val data = TlsSRTPUtils.getUseSRTPExtension(clientExtensions) ?: throw TlsFatalAlert(AlertDescription.handshake_failure)
                selected = profiles.firstOrNull { wanted -> data.protectionProfiles.any { it == wanted.dtlsId } }
                    ?: throw TlsFatalAlert(AlertDescription.handshake_failure)
            }

            override fun getServerExtensions(): Hashtable<Any?, Any?> {
                @Suppress("UNCHECKED_CAST")
                val extensions = super.getServerExtensions() as Hashtable<Any?, Any?>
                TlsSRTPUtils.addUseSRTPExtension(extensions, UseSRTPData(intArrayOf(selected!!.dtlsId), TlsUtils.EMPTY_BYTES))
                return extensions
            }

            override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
                alert = AlertDescription.getText(alertDescription)
            }
        }
        val protocol = DTLSServerProtocol()
        protocol.verifyRequests = false
        val dtls = protocol.accept(server, transport)
        val profile = server.selected ?: throw TlsFatalAlert(AlertDescription.handshake_failure)
        val exported = server.exported ?: throw TlsFatalAlert(AlertDescription.internal_error)
        return DtlsResult(dtls, profile, srtpFromDtlsExport(profile, exported, isDtlsClient = false)) { server.alert }
    }
}

internal fun describeDtlsFailure(error: Throwable, peerAlert: String?): String =
    when {
        error is TlsFatalAlertReceived -> "DTLS alert from server: ${AlertDescription.getText(error.alertDescription)}"
        error is TlsFatalAlert -> "DTLS failed: ${AlertDescription.getText(error.alertDescription)}"
        peerAlert != null -> "DTLS alert from server: $peerAlert"
        error is SocketTimeoutException -> "DTLS handshake timed out"
        else -> "DTLS failed: ${error.message ?: error.javaClass.simpleName}"
    }
