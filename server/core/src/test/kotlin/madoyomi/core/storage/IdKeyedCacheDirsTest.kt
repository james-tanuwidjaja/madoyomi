package madoyomi.core.storage

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.server.ApplicationDirs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The thumbnail and chapter-page caches name their files by database id (`<mangaId>.jpg`, `anime-<id>.jpg`, chapter
 * dirs by manga and chapter id), so each data dir needs its own (Phase 3 Stage 7: a fresh data dir showed another data
 * dir's cached covers, because both caches were in the shared temp dir).
 */
class IdKeyedCacheDirsTest {
    private val tempRoot = "${System.getProperty("java.io.tmpdir")}/Madoyomi"

    @Test
    fun `the id-keyed caches live in the data dir`() {
        val dirs = ApplicationDirs(dataRoot = "/data/a", tempRoot = tempRoot)

        assertEquals("/data/a/cache/thumbnails", dirs.tempThumbnailCacheRoot)
        assertEquals("/data/a/cache/manga", dirs.tempMangaCacheRoot)
        assertTrue(dirs.tempThumbnailCacheRoot.startsWith(dirs.cacheDir))
        assertTrue(dirs.tempMangaCacheRoot.startsWith(dirs.cacheDir))
    }

    @Test
    fun `two data dirs share no id-keyed cache`() {
        val a = ApplicationDirs(dataRoot = "/data/a", tempRoot = tempRoot)
        val b = ApplicationDirs(dataRoot = "/data/b", tempRoot = tempRoot)

        assertNotEquals(a.tempThumbnailCacheRoot, b.tempThumbnailCacheRoot)
        assertNotEquals(a.tempMangaCacheRoot, b.tempMangaCacheRoot)
        // What isn't keyed by a database id may stay shared.
        assertEquals(a.webUIServe, b.webUIServe)
    }
}
