package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.security.SecureRandom

/** The API token shared with the Tauri shell. Never logged. */
class SidecarToken private constructor(
    private val bytes: ByteArray,
) {
    /** Constant-time comparison. */
    fun matches(candidate: String?): Boolean = candidate != null && MessageDigest.isEqual(bytes, candidate.toByteArray(Charsets.UTF_8))

    companion object {
        const val ENV = "MADOYOMI_TOKEN"

        private val logger = KotlinLogging.logger {}

        /** Reads [ENV]. Without one, a random token nobody knows is used, so the API is locked rather than open. */
        fun fromEnvironment(): SidecarToken {
            val fromEnv = System.getenv(ENV)?.trim()
            if (!fromEnv.isNullOrEmpty()) {
                return SidecarToken(fromEnv.toByteArray(Charsets.UTF_8))
            }
            logger.warn { "$ENV is not set: using a random token, so every API request except GET /health will be rejected" }
            val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return SidecarToken(random.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8))
        }
    }
}
