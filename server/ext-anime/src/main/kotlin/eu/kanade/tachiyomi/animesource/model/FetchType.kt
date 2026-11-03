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

/**
 * Define what type of content the anime should fetch.
 * The fetch type for a [SAnime] will not update after it's been initialized
 * to either Seasons or Episodes
 *
 * @since extensions-lib 16
 */
enum class FetchType {
    /**
     * [SAnime] will only call `getSeasonList`.
     */
    Seasons,

    /**
     * [SAnime] will only call `getEpisodeList`.
     */
    Episodes,
}
