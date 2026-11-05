package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.config.JavalinConfig
import madoyomi.core.anime.AnimeThumbnails
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import kotlin.time.Duration.Companion.days

/**
 * Anime binaries over REST (everything else is GraphQL): `GET /api/v1/anime/{animeId}/thumbnail`, the counterpart of
 * upstream's manga thumbnail route. Errors are the `{code, message, details}` body of upstream's exception handlers
 * (404 `NOT_FOUND` for an unknown anime or one without a thumbnail).
 */
object AnimeHttp {
    const val THUMBNAIL_PATH = "/api/v1/anime/{animeId}/thumbnail"

    fun install(config: JavalinConfig) {
        config.routes.get(THUMBNAIL_PATH) { ctx ->
            ctx.getAttribute(Attribute.TachideskUser).requireUser()
            val animeId =
                ctx.pathParam("animeId").toIntOrNull()
                    ?: throw MadoyomiException(
                        ErrorCode.BAD_REQUEST,
                        "animeId must be a number",
                        mapOf("animeId" to ctx.pathParam("animeId")),
                    )
            ctx.future {
                future { AnimeThumbnails.get(animeId) }
                    .thenApply { (stream, contentType) ->
                        ctx.header("content-type", contentType)
                        // upstream's: a day
                        ctx.header("cache-control", "max-age=${1.days.inWholeSeconds}")
                        ctx.result(stream)
                    }
            }
        }
    }
}
