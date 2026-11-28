package io.github.tsutsu3.pi_hole_client.widget.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePinningTest {

    private val pinnedBytes = "pinned certificate".toByteArray()
    private val otherBytes = "renewed certificate".toByteArray()
    private val pin = sha256(pinnedBytes)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }

    private fun cert(bytes: ByteArray): X509Certificate =
        mockk { every { encoded } returns bytes }

    private fun platform(trusted: Boolean): X509TrustManager = mockk {
        if (trusted) {
            every { checkServerTrusted(any(), any()) } returns Unit
        } else {
            every { checkServerTrusted(any(), any()) } throws CertificateException("untrusted")
        }
    }

    @Test
    fun matchesPin_ignoresColonsAndCase() {
        assertTrue(matchesPin(cert(pinnedBytes), pin))
        assertTrue(matchesPin(cert(pinnedBytes), pin.replace(":", "").lowercase()))
        assertFalse(matchesPin(cert(otherBytes), pin))
    }

    @Test
    fun trustManager_acceptsPlatformTrustedCertEvenIfPinDiffers() {
        // A publicly trusted certificate that was renewed after pinning.
        val platform = platform(trusted = true)
        val manager = PiHoleApiClient.CertificatePinningTrustManager(pin, platform)

        manager.checkServerTrusted(arrayOf(cert(otherBytes)), "RSA")

        verify { platform.checkServerTrusted(any(), "RSA") }
    }

    @Test
    fun trustManager_acceptsUntrustedCertMatchingPin() {
        val manager = PiHoleApiClient.CertificatePinningTrustManager(pin, platform(trusted = false))

        manager.checkServerTrusted(arrayOf(cert(pinnedBytes)), "RSA")
    }

    @Test(expected = CertificateException::class)
    fun trustManager_rejectsUntrustedCertNotMatchingPin() {
        val manager = PiHoleApiClient.CertificatePinningTrustManager(pin, platform(trusted = false))

        manager.checkServerTrusted(arrayOf(cert(otherBytes)), "RSA")
    }

    @Test
    fun trustManager_withoutPlatformUsesPinOnly() {
        val manager = PiHoleApiClient.CertificatePinningTrustManager(pin, null)

        manager.checkServerTrusted(arrayOf(cert(pinnedBytes)), "RSA")
        try {
            manager.checkServerTrusted(arrayOf(cert(otherBytes)), "RSA")
            throw AssertionError("expected CertificateException")
        } catch (e: CertificateException) {
            // expected
        }
    }

    @Test(expected = CertificateException::class)
    fun trustManager_rejectsEmptyChain() {
        val manager = PiHoleApiClient.CertificatePinningTrustManager(pin, platform(trusted = true))

        manager.checkServerTrusted(emptyArray(), "RSA")
    }

    @Test
    fun hostnameVerifier_acceptsStandardMatch() {
        val standard = HostnameVerifier { _, _ -> true }
        val session = mockk<SSLSession> { every { peerCertificates } returns arrayOf(cert(otherBytes)) }

        assertTrue(PiHoleApiClient.PinnedHostnameVerifier(pin, standard).verify("pihole.example", session))
    }

    @Test
    fun hostnameVerifier_acceptsPinnedCertWithoutHostnameMatch() {
        val standard = HostnameVerifier { _, _ -> false }
        val pinned = mockk<SSLSession> { every { peerCertificates } returns arrayOf(cert(pinnedBytes)) }
        val other = mockk<SSLSession> { every { peerCertificates } returns arrayOf(cert(otherBytes)) }
        val unverified = mockk<SSLSession> {
            every { peerCertificates } throws SSLPeerUnverifiedException("none")
        }
        val verifier = PiHoleApiClient.PinnedHostnameVerifier(pin, standard)

        assertTrue(verifier.verify("pihole.local", pinned))
        assertFalse(verifier.verify("pihole.local", other))
        assertFalse(verifier.verify("pihole.local", unverified))
    }
}
