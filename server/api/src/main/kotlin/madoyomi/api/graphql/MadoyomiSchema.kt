package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import madoyomi.core.graphql.GraphQLSchemaContribution

/**
 * Madoyomi's additions to upstream's GraphQL schema, found by `core`'s schema provider through
 * `META-INF/services/madoyomi.core.graphql.GraphQLSchemaContribution`.
 */
class MadoyomiSchema : GraphQLSchemaContribution {
    override val queries: List<Any> =
        listOf(
            TrustQuery(),
            AnimeSourceQuery(),
            AnimeQuery(),
            AnimeCategoryQuery(),
            EpisodeQuery(),
            PlaybackQuery(),
            PlayerSettingsQuery(),
            ReaderQuery(),
            HistoryQuery(),
            LibraryUpdateQuery(),
            DownloadQueueQuery(),
            MadoyomiBackupQuery(),
        )
    override val mutations: List<Any> =
        listOf(
            TrustMutation(),
            AnimeMutation(),
            AnimeCategoryMutation(),
            PlaybackMutation(),
            PlayerSettingsMutation(),
            ReaderMutation(),
            HistoryMutation(),
            LibraryUpdateMutation(),
            DownloadQueueMutation(),
            MadoyomiBackupMutation(),
        )
    override val subscriptions: List<Any> =
        listOf(
            ExtensionSubscription(),
            PlaybackSubscription(),
            LibraryUpdateSubscription(),
            DownloadQueueSubscription(),
            MadoyomiBackupSubscription(),
        )
    override val dataLoaders: List<KotlinDataLoader<*, *>> = animeDataLoaders
}
