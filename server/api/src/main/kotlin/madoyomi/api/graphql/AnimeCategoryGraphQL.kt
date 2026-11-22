package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import graphql.GraphQLContext
import madoyomi.api.graphql.AnimeNodeList.Companion.toNodeList
import madoyomi.core.anime.AnimeCategories
import madoyomi.core.anime.model.table.AnimeCategoryTable
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.ResultRow
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.server.JavalinSetup.future

/**
 * An anime category (`ANIME_CATEGORY`), the counterpart of upstream's `CategoryType`: the same
 * fields, and the library entries in it.
 */
class AnimeCategoryType(
    val id: Int,
    @GraphQLDescription("The position: 0 for Default, then 1, 2, …")
    val order: Int,
    val name: String,
    @GraphQLDescription("New library entries go into this category (upstream's CategoryType.default)")
    val default: Boolean,
    val includeInUpdate: IncludeOrExclude,
    val includeInDownload: IncludeOrExclude,
    @GraphQLDescription("The Default category: the library entries in no other category")
    val isDefaultCategory: Boolean,
    @GraphQLIgnore
    val metaMap: Map<String, String>,
) : Node {
    constructor(row: ResultRow) : this(
        id = row[AnimeCategoryTable.id].value,
        order = row[AnimeCategoryTable.order],
        name = row[AnimeCategoryTable.name],
        default = row[AnimeCategoryTable.isDefault],
        includeInUpdate = IncludeOrExclude.fromValue(row[AnimeCategoryTable.includeInUpdate]),
        includeInDownload = IncludeOrExclude.fromValue(row[AnimeCategoryTable.includeInDownload]),
        isDefaultCategory = row[AnimeCategoryTable.isDefaultCategory],
        metaMap = AnimeCategories.metaOf(row),
    )

    @GraphQLDescription("Key/value strings, by key (as upstream's CategoryType.meta)")
    fun meta(): List<AnimeCategoryMetaType> = metaMap.entries.sortedBy { it.key }.map { AnimeCategoryMetaType(id, it.key, it.value) }

    @GraphQLDescription("The library entries in this category (for Default: those in no other), by title")
    fun animes(): AnimeNodeList = AnimeCategories.libraryAnimeIn(id).map(::AnimeType).toNodeList()
}

data class AnimeCategoryMetaType(
    val categoryId: Int,
    val key: String,
    val value: String,
)

/** An anime's categories by position (Default never listed), for `AnimeType.categories`. */
class CategoriesForAnimeDataLoader : KotlinDataLoader<Int, List<AnimeCategoryType>> {
    override val dataLoaderName = "CategoriesForAnimeDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<AnimeCategoryType>> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val byAnime = AnimeCategories.categoriesOf(ids)
                ids.map { id -> byAnime[id].orEmpty().map(::AnimeCategoryType) }
            }
        }
}

class AnimeCategoryQuery {
    @RequireAuth
    @GraphQLDescription("The anime categories, Default first, then by position (manga's are upstream's categories)")
    fun animeCategories(): List<AnimeCategoryType> = AnimeCategories.list().map(::AnimeCategoryType)

    @RequireAuth
    fun animeCategory(id: Int): AnimeCategoryType = AnimeCategoryType(AnimeCategories.row(id))
}

/**
 * The anime counterparts of upstream's category mutations (`createCategory`, `updateCategory`, `updateCategoryOrder`,
 * `deleteCategory`, `updateMangaCategories`, `updateMangasCategories`, `setCategoryMeta`, `deleteCategoryMeta`), in
 * their shapes, over `AnimeCategories` (its rules are upstream's, with the differences it lists).
 */
class AnimeCategoryMutation {
    data class CreateAnimeCategoryInput(
        val clientMutationId: String? = null,
        val name: String,
        @GraphQLDescription("1-based position among the categories after Default; the end when left out")
        val order: Int? = null,
        val default: Boolean? = null,
        val includeInUpdate: IncludeOrExclude? = null,
        val includeInDownload: IncludeOrExclude? = null,
    )

    data class CreateAnimeCategoryPayload(
        val clientMutationId: String?,
        val category: AnimeCategoryType,
    )

    @RequireAuth
    @GraphQLDescription("Creates an anime category (a unique name, ignoring case, not Default)")
    fun createAnimeCategory(input: CreateAnimeCategoryInput): CreateAnimeCategoryPayload {
        val id = AnimeCategories.create(input.name, input.order, input.default, input.includeInUpdate, input.includeInDownload)
        return CreateAnimeCategoryPayload(input.clientMutationId, AnimeCategoryType(AnimeCategories.row(id)))
    }

    data class UpdateAnimeCategoryPatch(
        val name: String? = null,
        val default: Boolean? = null,
        val includeInUpdate: IncludeOrExclude? = null,
        val includeInDownload: IncludeOrExclude? = null,
    )

    data class UpdateAnimeCategoryInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateAnimeCategoryPatch,
    )

    data class UpdateAnimeCategoryPayload(
        val clientMutationId: String?,
        val category: AnimeCategoryType,
    )

    @RequireAuth
    @GraphQLDescription(
        "Changes an anime category (as upstream's updateCategory). Default takes only the update and download flags; " +
            "a name or the new-entry flag for it is BAD_REQUEST",
    )
    fun updateAnimeCategory(input: UpdateAnimeCategoryInput): UpdateAnimeCategoryPayload {
        val patch = input.patch
        AnimeCategories.update(input.id, patch.name, patch.default, patch.includeInUpdate, patch.includeInDownload)
        return UpdateAnimeCategoryPayload(input.clientMutationId, AnimeCategoryType(AnimeCategories.row(input.id)))
    }

    data class UpdateAnimeCategoryOrderInput(
        val clientMutationId: String? = null,
        val id: Int,
        @GraphQLDescription("1-based position among the categories after Default (clamped to the last)")
        val position: Int,
    )

    data class UpdateAnimeCategoryOrderPayload(
        val clientMutationId: String?,
        @GraphQLDescription("Every category, Default first, then by position")
        val categories: List<AnimeCategoryType>,
    )

    @RequireAuth
    @GraphQLDescription("Moves an anime category (as upstream's updateCategoryOrder)")
    fun updateAnimeCategoryOrder(input: UpdateAnimeCategoryOrderInput): UpdateAnimeCategoryOrderPayload {
        AnimeCategories.move(input.id, input.position)
        return UpdateAnimeCategoryOrderPayload(input.clientMutationId, AnimeCategories.list().map(::AnimeCategoryType))
    }

    data class DeleteAnimeCategoryInput(
        val clientMutationId: String? = null,
        val categoryId: Int,
    )

    data class DeleteAnimeCategoryPayload(
        val clientMutationId: String?,
        val category: AnimeCategoryType,
        @GraphQLDescription("The entries that were in it (now in Default unless they are in another)")
        val animes: List<AnimeType>,
    )

    @RequireAuth
    @GraphQLDescription("Deletes an anime category (as upstream's deleteCategory); Default is BAD_REQUEST")
    fun deleteAnimeCategory(input: DeleteAnimeCategoryInput): DeleteAnimeCategoryPayload {
        val category = AnimeCategoryType(AnimeCategories.row(input.categoryId))
        val animeIds = AnimeCategories.delete(input.categoryId)
        return DeleteAnimeCategoryPayload(input.clientMutationId, category, AnimeType.byIds(animeIds))
    }

    data class UpdateAnimeCategoriesPatch(
        @GraphQLDescription("Removes the entries from every category (back to Default) before addToCategories")
        val clearCategories: Boolean? = null,
        val addToCategories: List<Int>? = null,
        val removeFromCategories: List<Int>? = null,
    )

    data class UpdateAnimeCategoriesInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateAnimeCategoriesPatch,
    )

    data class UpdateAnimeCategoriesPayload(
        val clientMutationId: String?,
        val anime: AnimeType,
    )

    data class UpdateAnimesCategoriesInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
        val patch: UpdateAnimeCategoriesPatch,
    )

    data class UpdateAnimesCategoriesPayload(
        val clientMutationId: String?,
        @GraphQLDescription("In the given order, each id once")
        val animes: List<AnimeType>,
    )

    private fun assign(
        ids: List<Int>,
        patch: UpdateAnimeCategoriesPatch,
    ) = AnimeCategories.assign(
        ids,
        add = patch.addToCategories.orEmpty(),
        remove = patch.removeFromCategories.orEmpty(),
        clear = patch.clearCategories == true,
    )

    @RequireAuth
    @GraphQLDescription("Changes an anime's categories (as upstream's updateMangaCategories)")
    fun updateAnimeCategories(input: UpdateAnimeCategoriesInput): UpdateAnimeCategoriesPayload {
        assign(listOf(input.id), input.patch)
        return UpdateAnimeCategoriesPayload(input.clientMutationId, AnimeType.byIds(listOf(input.id)).single())
    }

    @RequireAuth
    @GraphQLDescription("Changes several anime's categories at once (as upstream's updateMangasCategories)")
    fun updateAnimesCategories(input: UpdateAnimesCategoriesInput): UpdateAnimesCategoriesPayload {
        assign(input.ids, input.patch)
        return UpdateAnimesCategoriesPayload(input.clientMutationId, AnimeType.byIds(input.ids.distinct()))
    }

    data class SetAnimeCategoryMetaInput(
        val clientMutationId: String? = null,
        val meta: AnimeCategoryMetaInput,
    )

    data class AnimeCategoryMetaInput(
        val categoryId: Int,
        val key: String,
        val value: String,
    )

    data class SetAnimeCategoryMetaPayload(
        val clientMutationId: String?,
        val meta: AnimeCategoryMetaType,
    )

    @RequireAuth
    @GraphQLDescription("Sets one meta key of an anime category (as upstream's setCategoryMeta)")
    fun setAnimeCategoryMeta(input: SetAnimeCategoryMetaInput): SetAnimeCategoryMetaPayload {
        val meta = input.meta
        AnimeCategories.setMeta(meta.categoryId, meta.key, meta.value)
        return SetAnimeCategoryMetaPayload(input.clientMutationId, AnimeCategoryMetaType(meta.categoryId, meta.key, meta.value))
    }

    data class DeleteAnimeCategoryMetaInput(
        val clientMutationId: String? = null,
        val categoryId: Int,
        val key: String,
    )

    data class DeleteAnimeCategoryMetaPayload(
        val clientMutationId: String?,
        @GraphQLDescription("The removed key, null if the category had none")
        val meta: AnimeCategoryMetaType?,
        val category: AnimeCategoryType,
    )

    @RequireAuth
    @GraphQLDescription("Removes one meta key of an anime category (as upstream's deleteCategoryMeta)")
    fun deleteAnimeCategoryMeta(input: DeleteAnimeCategoryMetaInput): DeleteAnimeCategoryMetaPayload {
        val old = AnimeCategories.deleteMeta(input.categoryId, input.key)
        return DeleteAnimeCategoryMetaPayload(
            input.clientMutationId,
            old?.let { AnimeCategoryMetaType(input.categoryId, input.key, it) },
            AnimeCategoryType(AnimeCategories.row(input.categoryId)),
        )
    }
}
