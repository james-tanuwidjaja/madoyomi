package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import madoyomi.core.diagnostics.Diagnostics
import suwayomi.tachidesk.graphql.directives.RequireAuth

class DiagnosticsQuery {
    @RequireAuth
    @GraphQLDescription(
        "What Settings → Export logs puts beside the logs (Phase 7 Stage 3), as a JSON document: the backend's and Java's " +
            "versions, the repos, the installed extensions, the settings without secrets (server.conf's privacy-safe " +
            "keys, Madoyomi's settings files) and the downloads folder; the library's titles only with includeLibrary",
    )
    fun diagnostics(includeLibrary: Boolean? = null): String = Diagnostics.report(includeLibrary == true)
}
