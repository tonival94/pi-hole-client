package io.github.tsutsu3.pi_hole_client.widget.common

import android.util.Log
import io.github.tsutsu3.pi_hole_client.widget.WidgetDebugConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.delay
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Classifies connection error types for appropriate UI feedback.
 */
enum class ConnectionErrorType {
    /** Network unreachable or DNS resolution failed */
    NETWORK,
    /** Connection or read timeout */
    TIMEOUT,
    /** SSL/TLS handshake or certificate validation failed */
    SSL,
    /** Other unexpected errors */
    UNKNOWN,
}

/**
 * Minimal HTTP response payload for widget usage.
 *
 * Widgets avoid heavier networking stacks to keep dependencies small.
 *
 * @param statusCode HTTP status code, or negative value for connection errors:
 *   - `-1`: Network error (DNS, unreachable)
 *   - `-2`: Timeout error
 *   - `-3`: SSL/TLS error
 *   - `-4`: Unknown error
 * @param body Response body or error message for connection failures
 */
data class ApiResponse(val statusCode: Int, val body: String) {
    /**
     * Returns the error type for connection failures (negative status codes).
     * Returns null for successful HTTP responses.
     */
    val errorType: ConnectionErrorType?
        get() = when (statusCode) {
            STATUS_NETWORK_ERROR -> ConnectionErrorType.NETWORK
            STATUS_TIMEOUT_ERROR -> ConnectionErrorType.TIMEOUT
            STATUS_SSL_ERROR -> ConnectionErrorType.SSL
            STATUS_UNKNOWN_ERROR -> ConnectionErrorType.UNKNOWN
            else -> null
        }

    val isConnectionError: Boolean
        get() = statusCode < 0

    companion object {
        const val STATUS_NETWORK_ERROR = -1
        const val STATUS_TIMEOUT_ERROR = -2
        const val STATUS_SSL_ERROR = -3
        const val STATUS_UNKNOWN_ERROR = -4
    }
}

/**
 * Lightweight HTTP client for widget polling and toggling.
 *
 * Uses [HttpURLConnection] intentionally instead of OkHttp or Retrofit to avoid
 * pulling heavy networking dependencies into the widget. Widgets run in a
 * restricted WorkManager context where APK size and class-loading overhead matter,
 * and the widget only makes two simple calls (GET stats, POST toggle).
 *
 * Authentication is handled by Flutter; this client only attaches a provided SID.
 *
 * ## TLS certificate validation modes
 *
 * | ignoreCertErrors | allowUntrustedCert | pinnedSha256 | Behaviour               |
 * |------------------|-----------------|--------------|-------------------------|
 * | true             | any             | any          | Skip all TLS validation |
 * | false            | true            | non-null     | Standard TLS, else pin  |
 * | false            | true            | null         | Reject (pin required)   |
 * | false            | false           | any          | Standard TLS            |
 */
class PiHoleApiClient(
    private val allowUntrustedCert: Boolean,
    private val ignoreCertificateErrors: Boolean,
    private val pinnedCertificateSha256: String?,
) {

    companion object {
        private const val TAG = "PiHoleApiClient"

        /** Connection timeout in milliseconds. Short enough to fail-fast on LAN. */
        private const val CONNECT_TIMEOUT_MS = 4_000

        /** Read timeout in milliseconds */
        private const val READ_TIMEOUT_MS = 10_000

        /**
         * Detects auth failures from HTTP status code.
         *
         * Pi-hole v6 returns 401/403 for expired or invalid SIDs.
         * The body is accepted for call-site convenience but only the
         * status code is used for detection.
         */
        fun isAuthFailure(statusCode: Int, body: String = ""): Boolean {
            return statusCode == 401 || statusCode == 403
        }
    }

    /**
     * Whether this client can actually connect given the current TLS settings.
     *
     * Returns `false` when untrusted signed certs are allowed but no pinned
     * fingerprint is configured - the widget refuses to trust arbitrary
     * certificates and requires the user to pin one via the app.
     *
     * For HTTP URLs, always returns `true` since certificate validation
     * is not applicable to cleartext traffic.
     */
    fun canConnect(serverAddress: String): Boolean {
        // HTTP doesn't use certificates, so always allow connection
        if (serverAddress.startsWith("http://", ignoreCase = true)) {
            return true
        }

        // HTTPS certificate validation logic
        if (ignoreCertificateErrors) return true
        if (allowUntrustedCert && pinnedCertificateSha256.isNullOrEmpty()) return false
        return true
    }

    /**
     * Executes a GET request to the Pi-hole API.
     */
    fun get(url: String, sid: String?): ApiResponse {
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Content-Type", "application/json")
        if (!sid.isNullOrEmpty()) {
            connection.setRequestProperty("X-FTL-SID", sid)
        }
        return readResponse(connection)
    }

    /**
     * Executes a GET request with up to two retries (300 ms then 600 ms) on connection errors.
     *
     * Extracted so all workers share the same retry logic without duplication.
     */
    suspend fun getWithRetry(url: String, sid: String?): ApiResponse {
        var resp = get(url, sid)
        if (resp.isConnectionError) {
            if (WidgetDebugConfig.DEBUG) Log.w(TAG, "Connection error, retrying (1): ${resp.body}")
            delay(300)
            resp = get(url, sid)
        }
        if (resp.isConnectionError) {
            if (WidgetDebugConfig.DEBUG) Log.w(TAG, "Connection error, retrying (2): ${resp.body}")
            delay(600)
            resp = get(url, sid)
        }
        return resp
    }

    /**
     * Executes a POST request to the Pi-hole API.
     */
    fun post(url: String, sid: String?, body: String): ApiResponse {
        val connection = openConnection(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        if (!sid.isNullOrEmpty()) {
            connection.setRequestProperty("X-FTL-SID", sid)
        }
        connection.outputStream.use { output ->
            output.write(body.toByteArray())
        }
        return readResponse(connection)
    }

    /**
     * Opens an HTTP connection and configures TLS based on the server's
     * certificate validation mode.
     */
    private fun openConnection(url: String): HttpURLConnection {
        if (WidgetDebugConfig.DEBUG) {
            // Only log in debug builds to avoid leaking server addresses in production
            Log.d(TAG, "Opening connection")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            configureTls(connection)
        }
        // Keep timeouts short to avoid blocking widget refresh.
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        return connection
    }

    /**
     * Applies the appropriate TLS configuration to an HTTPS connection.
     *
     * Priority order matches Flutter's `createHttpClient()`:
     * 1. `ignoreCertificateErrors` - trust everything (legacy / explicit opt-in)
     * 2. `allowUntrustedCert` with pin - standard TLS first, SHA-256 pin as fallback
     * 3. default - standard platform TLS validation
     *
     * Note: `allowUntrustedCert` without a pin is rejected before reaching this
     * method (see [canConnect]).
     */
    private fun configureTls(connection: HttpsURLConnection) {
        when {
            ignoreCertificateErrors -> {
                // Skip all validation. Used by legacy users (pre-v9 migration)
                // and users who explicitly enable "Don't check SSL certificate".
                val trustAll = arrayOf<TrustManager>(TrustAllManager())
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, java.security.SecureRandom())
                connection.sslSocketFactory = sslContext.socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }

            allowUntrustedCert && !pinnedCertificateSha256.isNullOrEmpty() -> {
                // Like Flutter's badCertificateCallback: a certificate that
                // passes standard validation is accepted; the pin is only
                // checked when it does not. A publicly trusted certificate
                // that was renewed after pinning keeps working.
                val pinningManager = CertificatePinningTrustManager(pinnedCertificateSha256)
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(pinningManager), java.security.SecureRandom())
                connection.sslSocketFactory = sslContext.socketFactory
                connection.hostnameVerifier = PinnedHostnameVerifier(pinnedCertificateSha256)
            }

            // else: standard platform TLS - no custom configuration needed.
        }
    }

    /**
     * Reads the response body and normalizes empty error streams.
     *
     * Classifies connection errors into specific types for appropriate UI feedback:
     * - Network errors (DNS failure, unreachable host)
     * - Timeout errors (connection or read timeout)
     * - SSL errors (certificate validation, handshake failure)
     * - Unknown errors (other exceptions)
     */
    private fun readResponse(connection: HttpURLConnection): ApiResponse {
        return try {
            val statusCode = connection.responseCode
            if (WidgetDebugConfig.DEBUG) {
                Log.d(TAG, "Response code: $statusCode")
            }
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = if (stream != null) {
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            } else {
                ""
            }
            connection.disconnect()
            ApiResponse(statusCode, body)
        } catch (e: UnknownHostException) {
            logConnectionError("DNS resolution failed", e)
            ApiResponse(ApiResponse.STATUS_NETWORK_ERROR, "Network error: Unable to resolve host")
        } catch (e: SocketTimeoutException) {
            logConnectionError("Connection timed out", e)
            ApiResponse(ApiResponse.STATUS_TIMEOUT_ERROR, "Timeout: Server did not respond in time")
        } catch (e: SSLException) {
            logConnectionError("SSL/TLS error", e)
            ApiResponse(ApiResponse.STATUS_SSL_ERROR, "SSL error: ${e.message ?: "Certificate validation failed"}")
        } catch (e: java.net.ConnectException) {
            logConnectionError("Connection refused", e)
            ApiResponse(ApiResponse.STATUS_NETWORK_ERROR, "Network error: Connection refused")
        } catch (e: Exception) {
            logConnectionError("Unexpected error", e)
            ApiResponse(ApiResponse.STATUS_UNKNOWN_ERROR, "Error: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Logs connection errors only in debug builds to avoid exposing
     * sensitive information in production logs.
     */
    private fun logConnectionError(message: String, e: Exception) {
        if (WidgetDebugConfig.DEBUG) {
            Log.w(TAG, "$message: ${e.javaClass.simpleName}", e)
        }
    }

    /**
     * TrustManager that accepts all certificates without validation.
     *
     * Only used when [ignoreCertificateErrors] is true.
     */
    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Hostname verifier for pinned servers.
     *
     * Standard hostname check first. A certificate matching the pin is
     * accepted whatever its CN/SAN, because untrusted signed certs often lack
     * a matching hostname entry.
     */
    internal class PinnedHostnameVerifier(
        private val pinnedSha256: String,
        private val standard: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    ) : HostnameVerifier {

        override fun verify(hostname: String, session: SSLSession): Boolean {
            if (standard.verify(hostname, session)) return true
            val leaf = try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (e: SSLException) {
                null
            }
            return leaf != null && matchesPin(leaf, pinnedSha256)
        }
    }

    /**
     * TrustManager for pinned servers.
     *
     * Accepts a certificate that passes the platform's standard validation.
     * Otherwise accepts it only if the leaf certificate's SHA-256 fingerprint
     * matches [pinnedSha256].
     */
    internal class CertificatePinningTrustManager(
        private val pinnedSha256: String,
        private val platform: X509TrustManager? = platformTrustManager(),
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) {
                throw CertificateException("Empty certificate chain")
            }
            try {
                if (platform != null) {
                    platform.checkServerTrusted(chain, authType)
                    return
                }
            } catch (e: CertificateException) {
                // Not trusted by the platform: fall back to the pin.
            }
            if (!matchesPin(chain[0], pinnedSha256)) {
                if (WidgetDebugConfig.DEBUG) {
                    // Only log fingerprints in debug builds to avoid exposing certificate details
                    Log.w(TAG, "Certificate pin mismatch")
                }
                throw CertificateException("Certificate fingerprint mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** The platform's default trust manager, or null if none is available. */
private fun platformTrustManager(): X509TrustManager? {
    return try {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (e: Exception) {
        null
    }
}

/** Whether the SHA-256 fingerprint of [cert] matches [pinnedSha256]. */
internal fun matchesPin(cert: X509Certificate, pinnedSha256: String): Boolean {
    val sha256Bytes = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    val certFingerprint = sha256Bytes.joinToString("") { "%02x".format(it) }
    val normalizedPin = pinnedSha256.replace(":", "").lowercase().trim()
    return certFingerprint == normalizedPin
}
