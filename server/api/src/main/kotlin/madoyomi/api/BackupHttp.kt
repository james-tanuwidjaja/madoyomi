package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.config.JavalinConfig
import madoyomi.core.backup.BackupFiles
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import java.net.URLEncoder

/**
 * Backup files over REST (Phase 6 Stage 6; everything else is GraphQL):
 * - `GET /api/v1/madoyomi/backups/{name}`: the file's bytes, as a download;
 * - `POST /api/v1/madoyomi/backups?name=<file name>` with the file as the body: a backup the user picked, checked to be
 *   one (`BAD_REQUEST` if not) and kept in the backups folder under its name; answers `{"name": …}`.
 */
object BackupHttp {
    const val FILE_PATH = "/api/v1/madoyomi/backups/{name}"
    const val UPLOAD_PATH = "/api/v1/madoyomi/backups"

    fun install(config: JavalinConfig) {
        config.routes.get(FILE_PATH) { ctx ->
            ctx.getAttribute(Attribute.TachideskUser).requireUser()
            val name = ctx.pathParam("name")
            val bytes = BackupFiles.read(name)
            ctx.header("content-type", "application/octet-stream")
            ctx.header("content-disposition", "attachment; filename*=UTF-8''${URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}")
            ctx.result(bytes)
        }
        config.routes.post(UPLOAD_PATH) { ctx ->
            ctx.getAttribute(Attribute.TachideskUser).requireUser()
            val name =
                ctx.queryParam("name")?.takeIf { it.isNotBlank() }
                    ?: throw MadoyomiException(ErrorCode.BAD_REQUEST, "name is missing", mapOf("field" to "name"))
            val file = ctx.bodyInputStream().use { BackupFiles.import(name, it) }
            ctx.json(mapOf("name" to file.name))
        }
    }
}
