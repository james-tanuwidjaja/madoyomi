package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import madoyomi.core.extension.ExtensionLifecycle
import madoyomi.core.repo.RepoService
import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.graphql.directives.RequireAuth

/** Events, over `graphql-transport-ws` at `/api/graphql`. Nothing is replayed: subscribe before starting the operation. */
class ExtensionSubscription {
    @RequireAuth
    @GraphQLDescription("The steps of every install and update, or only of pkgName (DOWNLOADING … INSTALLED or FAILED)")
    fun extensionInstallProgress(pkgName: String? = null): Flow<ExtensionInstallProgressType> =
        ExtensionLifecycle.default.progress
            .filter { pkgName == null || it.pkgName == pkgName }
            .map(::ExtensionInstallProgressType)

    @RequireAuth
    @GraphQLDescription("One event per repo refresh (fetchExtensions, startup), of every kind or only of type")
    fun extensionStoreRefreshed(type: ExtensionKind? = null): Flow<ExtensionStoreRefreshedType> =
        RepoService.default.refreshed
            .filter { type == null || it.kind == type }
            .map(::ExtensionStoreRefreshedType)
}
