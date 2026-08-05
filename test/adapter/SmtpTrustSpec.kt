package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.gateway.SmtpTrust
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The fixtures are two throwaway self-signed certificates generated with keytool
 * (CN=test-relay.invalid / CN=test-stranger.invalid) — the `.invalid` TLD can never be
 * registered, and neither key is used anywhere. They stand in for the internal relay's
 * cert and for whatever else might answer on its address.
 */
private fun fixture(name: String): File = File("testResources/smtp/$name.pem")

private fun certificate(name: String): X509Certificate =
    fixture(name).inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

class SmtpTrustSpec : BehaviorSpec({

    Given("a trust manager pinned to the relay's self-signed certificate") {
        val trust = SmtpTrust.trustManager(fixture("relay"))

        When("the relay presents that exact certificate") {
            Then("the handshake is allowed — this is the whole point of the pin") {
                shouldNotThrowAny {
                    trust.checkServerTrusted(arrayOf(certificate("relay")), "RSA")
                }
            }
        }

        When("something else presents a different self-signed certificate") {
            Then("it is rejected, with the reissue hint that makes it actionable") {
                val thrown = shouldThrow<CertificateException> {
                    trust.checkServerTrusted(arrayOf(certificate("stranger")), "RSA")
                }
                thrown.message!! shouldContain "does not match the pin"
            }
        }

        When("a certificate the pin signed nothing for arrives ahead of it in the chain") {
            Then("only the leaf is consulted, so an appended pin cannot smuggle it in") {
                // Guards the difference between a pin and a trust anchor: were the pinned
                // cert treated as a CA, presenting it alongside a foreign leaf would pass.
                shouldThrow<CertificateException> {
                    trust.checkServerTrusted(
                        arrayOf(certificate("stranger"), certificate("relay")),
                        "RSA",
                    )
                }
            }
        }

        When("the relay presents nothing at all") {
            Then("it fails closed rather than dereferencing an empty chain") {
                shouldThrow<CertificateException> {
                    trust.checkServerTrusted(emptyArray(), "RSA")
                }
            }
        }

        Then("client authentication is refused outright — this manager is server-side only") {
            shouldThrow<CertificateException> {
                trust.checkClientTrusted(arrayOf(certificate("relay")), "RSA")
            }
        }
    }

    Given("a PEM path that is not a certificate") {
        Then("building the trust manager fails loudly rather than trusting nothing") {
            shouldThrow<Exception> { SmtpTrust.trustManager(File("build.gradle.kts")) }
        }
    }
})
