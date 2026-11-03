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

sealed class AnimeFilter<T>(
    val name: String,
    var state: T,
) {
    open class Header(
        name: String,
    ) : AnimeFilter<Any>(name, 0)

    open class Separator(
        name: String = "",
    ) : AnimeFilter<Any>(name, 0)

    abstract class Select<V>(
        name: String,
        val values: Array<V>,
        state: Int = 0,
    ) : AnimeFilter<Int>(
            name,
            state,
        )

    abstract class Text(
        name: String,
        state: String = "",
    ) : AnimeFilter<String>(name, state)

    abstract class CheckBox(
        name: String,
        state: Boolean = false,
    ) : AnimeFilter<Boolean>(
            name,
            state,
        )

    abstract class TriState(
        name: String,
        state: Int = STATE_IGNORE,
    ) : AnimeFilter<Int>(name, state) {
        fun isIgnored() = state == STATE_IGNORE

        fun isIncluded() = state == STATE_INCLUDE

        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    abstract class Group<V>(
        name: String,
        state: List<V>,
    ) : AnimeFilter<List<V>>(name, state)

    abstract class Sort(
        name: String,
        val values: Array<String>,
        state: Selection? = null,
    ) : AnimeFilter<Sort.Selection?>(name, state) {
        data class Selection(
            val index: Int,
            val ascending: Boolean,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimeFilter<*>) return false

        return name == other.name && state == other.state
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (state?.hashCode() ?: 0)
        return result
    }
}
