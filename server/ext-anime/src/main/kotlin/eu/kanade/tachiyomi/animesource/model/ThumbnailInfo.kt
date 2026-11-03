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
 * The class containing info for displaying thumbnails.
 *
 * @param tileInfo List of tiles to be used as thumbnails
 * @param imageTileUrls List of urls to image tiles
 */
open class ThumbnailInfo(
    val tileInfo: List<TileInfo>,
    val imageTileUrls: List<String>,
)

/**
 * The class containing info for an image tile to be used as a thumbnail
 *
 * @param imageIndex The index from `imageTileUrls` to use image tile from
 * @param timeMs The position, in milliseconds, where the preview starts
 * @param x X coordinate of tile start, in pixels
 * @param y Y coordinate of tile start, in pixels
 * @param width Tile width, in pixels
 * @param height Tile height, in pixels
 */
data class TileInfo(
    val imageIndex: Int,
    val timeMs: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
