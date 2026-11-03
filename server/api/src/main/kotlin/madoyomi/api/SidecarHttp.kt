package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.config.JavalinConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import io.javalin.http.UnauthorizedResponse
import suwayomi.tachidesk.server.generated.BuildConfig

/** Sidecar-only HTTP: `GET /health` and the token gate in front of every other route. */
object SidecarHttp {
    const val HEALTH_PATH = "/health"

    private val logger = KotlinLogging.logger {}

    private class TokenRejected : RuntimeException("Missing or invalid API token")

    fun install(
        config: JavalinConfig,
        token: SidecarToken,
        startedAt: Long,
    ) {
        val routes = config.routes

        routes.get(HEALTH_PATH) { ctx ->
            ctx.json(
                mapOf(
                    "status" to "ok",
                    "name" to BuildConfig.NAME,
                    "version" to BuildConfig.VERSION,
                    "pid" to ProcessHandle.current().pid(),
                    "uptimeMs" to System.currentTimeMillis() - startedAt,
                ),
            )
        }

        // Registered before upstream's handlers (see JavalinSetup.javalinSetup), so nothing else runs first.
        routes.beforeMatched { ctx ->
            val isPreflight = ctx.method() == HandlerType.OPTIONS
            val isHealth = ctx.method() == HandlerType.GET && ctx.path() == HEALTH_PATH
            if (isPreflight || isHealth) return@beforeMatched

            // ?token= only for GETs (<img>-style binary requests); anything that changes state needs the header.
            val allowQuery = ctx.method() == HandlerType.GET
            if (!token.matches(presentedToken(ctx, allowQuery))) {
                logger.debug { "rejected ${ctx.method()} ${ctx.path()}: missing or invalid token" }
                throw TokenRejected()
            }
        }

        // Browsers cannot set headers on a WebSocket, so the upgrade request may carry ?token=.
        routes.wsBeforeUpgrade { ctx ->
            if (!token.matches(presentedToken(ctx, allowQuery = true))) {
                logger.debug { "rejected WebSocket upgrade ${ctx.path()}: missing or invalid token" }
                throw UnauthorizedResponse()
            }
        }

        routes.exception(TokenRejected::class.java) { e, ctx ->
            ctx.status(HttpStatus.UNAUTHORIZED)
            ctx.json(mapOf("code" to "UNAUTHORIZED", "message" to e.message, "details" to null))
        }
    }

    private fun presentedToken(
        ctx: Context,
        allowQuery: Boolean,
    ): String? {
        val header = ctx.header("Authorization")
        if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            return header.substring("Bearer ".length).trim()
        }
        return if (allowQuery) ctx.queryParam("token") else null
    }
}
