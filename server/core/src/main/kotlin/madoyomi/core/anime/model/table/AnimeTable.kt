package madoyomi.core.anime.model.table

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.columns.jsonObject
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar
import suwayomi.tachidesk.manga.model.table.columns.unlimitedVarchar

/** Anime entries, the counterpart of upstream's `MANGA` table. Unique by `(source, url)` (M0071). */
object AnimeTable : IntIdTable("anime") {
    val url = varchar("url", 2048)
    val title = truncatingVarchar("title", 512)
    val artist = unlimitedVarchar("artist").nullable()
    val author = unlimitedVarchar("author").nullable()
    val description = unlimitedVarchar("description").nullable()
    val genre = unlimitedVarchar("genre").nullable()

    /** `SAnime` status constant. */
    val status = integer("status").default(0)
    val thumbnailUrl = varchar("thumbnail_url", 2048).nullable()
    val inLibrary = bool("in_library").default(false)
    val initialized = bool("initialized").default(false)

    /**
     * The anime source id. Not a foreign key (the same as `MANGA.source`), so library entries outlive
     * an uninstalled extension. Named like upstream's `MangaTable.sourceReference` because `source` clashes
     * with a member of the Exposed table classes.
     */
    val sourceReference = long("source").index()

    /** Epoch seconds when the anime was added to the library. */
    val addedAt = long("added_at").default(0)

    /** Epoch seconds of the last details fetch. */
    val lastFetchedAt = long("last_fetched_at").default(0)

    // M0071: the rest of SAnime.
    val backgroundUrl = varchar("background_url", 2048).nullable()
    val updateStrategy = enumerationByName("update_strategy", 32, AnimeUpdateStrategy::class).default(AnimeUpdateStrategy.ALWAYS_UPDATE)

    /** Whether the entry has episodes or seasons (`SAnime.fetch_type`). */
    val fetchType = enumerationByName("fetch_type", 16, FetchType::class).default(FetchType.Episodes)
    val seasonNumber = double("season_number").default(-1.0)

    /** The extension's own data (`SAnime.memo`), handed back to it with the entry. */
    val memo = jsonObject("memo").default(JsonObject(emptyMap()))

    /** For a season: the entry whose season list it came from. Cleared when that entry goes or drops the season. */
    val parentId = optReference("parent_id", id, onDelete = ReferenceOption.SET_NULL)

    /** For a season: its position in the parent's season list (as `EPISODE.source_order`). */
    val seasonSourceOrder = integer("season_source_order").default(0)

    init {
        uniqueIndex("UX_ANIME_SOURCE_URL", sourceReference, url)
    }
}
