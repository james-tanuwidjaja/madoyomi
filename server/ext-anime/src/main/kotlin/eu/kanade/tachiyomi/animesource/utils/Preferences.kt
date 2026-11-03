package eu.kanade.tachiyomi.animesource.utils

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

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Gets preference key for source with [id].
 */
fun preferencesKey(id: Long) = "source_$id"

/**
 * Gets preference key for source.
 */
fun AnimeSource.preferencesKey(): String = preferencesKey(id)

/**
 * Gets instance of [SharedPreferences] scoped to the specific source key.
 */
fun sourcePreferences(key: String): SharedPreferences = Injekt.get<Application>().getSharedPreferences(key, Context.MODE_PRIVATE)

/**
 * Gets instance of [SharedPreferences] scoped to the specific source.
 *
 * @since extensions-lib 16
 */
fun AnimeSource.sourcePreferences(): SharedPreferences = sourcePreferences(preferencesKey())

/**
 * Gets instance of [SharedPreferences] scoped to the specific source id.
 *
 * @since extensions-lib 16
 *
 * @param id source id which the [SharedPreferences] is scoped to.
 */
fun sourcePreferences(id: Long): SharedPreferences = sourcePreferences(preferencesKey(id))
