package com.puregoldbe.ibms.adapter.security

import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.PresignOp
import com.puregoldbe.ibms.domain.port.PresignPort
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local presign: mints short-lived HMAC-signed URLs pointing back at this backend's
 * public `/attachments/{id}/blob` route, backed by the local-disk StoragePort. The
 * token binds the attachment id, the operation, and an expiry, so an upload token
 * can't be replayed as a download (or after it expires). Swap for a real S3/GCS
 * presigner later behind [PresignPort].
 */
class LocalHmacPresign(
    private val secret: String,
    private val baseUrl: String,
    private val clock: Clock,
    private val ttlSeconds: Long = 900,
) : PresignPort {

    override fun presignedUrl(attachmentId: String, op: PresignOp): String {
        val exp = clock.now().epochSeconds + ttlSeconds
        val token = "$exp:${sign(attachmentId, op, exp)}"
        return "${baseUrl.trimEnd('/')}/attachments/$attachmentId/blob?token=$token"
    }

    override fun isValid(attachmentId: String, op: PresignOp, token: String): Boolean {
        val parts = token.split(":", limit = 2)
        if (parts.size != 2) return false
        val exp = parts[0].toLongOrNull() ?: return false
        if (exp < clock.now().epochSeconds) return false
        return MessageDigest.isEqual(sign(attachmentId, op, exp).toByteArray(), parts[1].toByteArray())
    }

    private fun sign(attachmentId: String, op: PresignOp, exp: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$attachmentId:${op.name}:$exp".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
