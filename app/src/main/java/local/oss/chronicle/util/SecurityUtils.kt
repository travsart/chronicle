package local.oss.chronicle.util

import timber.log.Timber
import java.security.MessageDigest

/**
 * Security utilities for safe handling of sensitive data in logging and debugging.
 */
object SecurityUtils {
    /**
     * Securely hash a token for logging purposes.
     * Returns first 16 chars of SHA-256 hash to identify tokens without exposing them.
     *
     * @param token The authentication token to hash
     * @return Hashed token prefix (e.g., "a1b2c3d4e5f6g7h8") or "<empty>" if blank
     */
    fun hashToken(token: String?): String {
        if (token.isNullOrBlank()) {
            return "<empty>"
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(token.toByteArray())
            hashBytes.take(8) // First 8 bytes = 16 hex chars
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to hash token")
            "<hash_error>"
        }
    }
}
