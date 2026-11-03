package eu.kanade.tachiyomi.animesource

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
 * A factory for creating sources at runtime.
 */
interface AnimeSourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    fun createSources(): List<AnimeSource>
}
