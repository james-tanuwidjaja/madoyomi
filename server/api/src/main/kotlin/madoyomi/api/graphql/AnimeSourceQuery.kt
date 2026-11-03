package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import madoyomi.api.graphql.AnimeSourceNodeList.Companion.toNodeList
import madoyomi.core.anime.model.table.AnimeSourceTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.database.dbTransaction

class AnimeSourceQuery {
    data class AnimeSourceCondition(
        val id: Long? = null,
        val name: String? = null,
        val lang: String? = null,
        val extensionPkgName: String? = null,
    )

    /** The sources of the installed anime extensions (manga sources are upstream's `sources`). */
    @RequireAuth
    fun animeSources(condition: AnimeSourceCondition? = null): AnimeSourceNodeList =
        dbTransaction {
            val query = AnimeSourceTable.innerJoin(ExtensionTable).selectAll()
            condition?.id?.let { id -> query.andWhere { AnimeSourceTable.id eq id } }
            condition?.name?.let { name -> query.andWhere { AnimeSourceTable.name eq name } }
            condition?.lang?.let { lang -> query.andWhere { AnimeSourceTable.lang eq lang } }
            condition?.extensionPkgName?.let { pkg -> query.andWhere { ExtensionTable.pkgName eq pkg } }
            query.orderBy(AnimeSourceTable.name to SortOrder.ASC).map(::AnimeSourceType)
        }.toNodeList()
}
