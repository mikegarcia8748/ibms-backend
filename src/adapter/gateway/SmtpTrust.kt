package com.puregoldbe.ibms.adapter.gateway

import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for a relay whose certificate no public CA will ever vouch for.
 *
 * The internal Exchange relay presents a self-signed certificate, so the JVM's default
 * trust store rejects the STARTTLS upgrade — Jakarta Mail reports that as
 * `MessagingException: Could not convert socket to TLS`, with the real reason
 * (`PKIX path building failed`) only on the cause. SMTP_TRUSTED_CERT pins the exact
 * certificate the relay is expected to present.
 *
 * **This is an exact pin, not a trust anchor.** The presented certificate must be
 * byte-for-byte the pinned one; nothing is accepted on the strength of who signed it.
 * Two consequences follow, and both are deliberate:
 *
 *  - *The hostname stops mattering.* Ordinary TLS checks the name because a CA vouches
 *    for many hosts, so "signed by someone trusted" alone would let any of them
 *    impersonate this one. A pin admits exactly one certificate, and only whoever holds
 *    its private key can complete a handshake with it — so identity is already settled,
 *    by something stronger than a name. That is what lets SMTP_HOST be
 *    `mbox2.puregold.com.ph` while the certificate names only `MBOX2.puregold.local`.
 *    The gateway therefore turns Jakarta Mail's own hostname check off when a pin is
 *    configured, and leaves it on when there is none.
 *  - *Rotation is a breaking change.* When IT reissues the certificate, sends fail until
 *    the PEM is re-exported. That is the price of a pin, and it fails closed and loudly
 *    rather than quietly trusting the replacement. See certs/README.md.
 *
 * Also deliberately not `-Djavax.net.ssl.trustStore`: that switch is JVM-wide and
 * replaces the public CA set for *every* outbound call in the process. The factory here
 * is handed to one mail session and constrains nothing else.
 */
internal object SmtpTrust {

    /** Reads [pemFile] and returns a factory accepting only that exact certificate. */
    fun socketFactory(pemFile: File): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager(pemFile)), null) }
            .socketFactory

    /**
     * The trust decision on its own. Separated from [socketFactory] because what the pin
     * does and does not accept is the part worth asserting on, and an [SSLSocketFactory]
     * gives no way back to the manager inside it.
     */
    fun trustManager(pemFile: File): X509TrustManager = pinnedTo(readCertificate(pemFile))

    private fun readCertificate(pemFile: File): X509Certificate =
        pemFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun pinnedTo(pinned: X509Certificate) = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val presented = chain.firstOrNull()
                ?: throw CertificateException("relay presented no certificate")
            if (presented != pinned) {
                // Named rather than generic: the overwhelmingly likely cause is a
                // reissued certificate, and that is a one-line fix once you know it.
                throw CertificateException(
                    "relay certificate does not match the pin in SMTP_TRUSTED_CERT " +
                        "(presented ${presented.subjectX500Principal}, expected " +
                        "${pinned.subjectX500Principal}) — re-export it if it was reissued",
                )
            }
            // An expired pin still fails closed: pinning decides *which* certificate is
            // acceptable, not whether the usual validity rules apply.
            presented.checkValidity()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client authentication is not supported here")

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
    }
}
