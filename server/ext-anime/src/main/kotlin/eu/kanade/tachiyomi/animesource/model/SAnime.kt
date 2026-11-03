@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

/*
 * Copyright 2015 Javier Tomás and the Tachiyomi, Mihon and Aniyomi contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from aniyomiorg/aniyomi@9741444, source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/animesource/.
 * Modified for Madoyomi, see server/ext-anime/NOTICE.md.
 */

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SAnime : Serializable {
    var url: String

    var title: String

    var thumbnail_url: String?

    var background_url: String?

    var artist: String?

    var author: String?

    var status: Int

    var description: String?

    var genre: String?

    var update_strategy: AnimeUpdateStrategy

    var fetch_type: FetchType

    var season_number: Double

    var initialized: Boolean

    /**
     * Extra metadata associated with the anime.
     *
     * The JSON object is not visible to users and intended for internal or source-specific
     * purposes. Apps may define their own namespaced keys (e.g., `"aniyomi.*"`) for sources to populate.
     *
     * This allows apps to attach and ask for custom information without affecting the visible
     * anime data.
     *
     * @since extensions-lib 17
     */
    var memo: JsonObject

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre
            ?.split(", ")
            ?.map { it.trim() }
            ?.filterNot { it.isBlank() }
            ?.distinct()
    }

    fun copy() =
        create().also {
            it.url = url
            it.title = title
            it.artist = artist
            it.author = author
            it.description = description
            it.genre = genre
            it.status = status
            it.thumbnail_url = thumbnail_url
            it.background_url = background_url
            it.update_strategy = update_strategy
            it.fetch_type = fetch_type
            it.season_number = season_number
            it.initialized = initialized
            it.memo = memo
        }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6
        const val UPCOMING = 7

        fun create(): SAnime = SAnimeImpl()
    }
}
