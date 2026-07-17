package com.puregoldbe.ibms.adapter.gateway

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.puregoldbe.ibms.domain.port.TokenVerifierPort
import com.puregoldbe.ibms.domain.port.VerifiedGoogleIdentity

/**
 * Verifies a Google OIDC ID token against the configured Workspace web client id.
 * If no client id is configured (pure local dev), verification returns null and the
 * dev-login path is used instead.
 */
class GoogleTokenVerifierAdapter(clientId: String?) : TokenVerifierPort {
    private val verifier: GoogleIdTokenVerifier? = clientId?.let {
        GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(listOf(it))
            .build()
    }

    override fun verify(idToken: String): VerifiedGoogleIdentity? {
        val v = verifier ?: return null
        val token = runCatching { v.verify(idToken) }.getOrNull() ?: return null
        val payload = token.payload
        return VerifiedGoogleIdentity(
            sub = payload.subject,
            email = payload.email,
            name = payload["name"] as? String,
        )
    }
}
