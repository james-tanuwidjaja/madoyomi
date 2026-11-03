package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.repo.RepoFixtures.KEIYOUSHI_PB
import madoyomi.core.repo.RepoFixtures.MANGA_LIST
import madoyomi.core.repo.RepoFixtures.MANGA_REPO
import madoyomi.core.repo.RepoFixtures.YUZONO_KEY
import madoyomi.core.repo.RepoFixtures.YUZONO_LIST
import madoyomi.core.repo.RepoFixtures.YUZONO_PB
import madoyomi.core.repo.RepoFixtures.YUZONO_PKGS
import madoyomi.core.repo.RepoFixtures.YUZONO_REPO
import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoIndexFetcherTest {
    private fun fetch(
        http: StubRepoHttp,
        url: String,
        format: RepoIndexFormat,
        kind: ExtensionKind = ExtensionKind.ANIME,
    ) = runBlocking { RepoIndexFetcher(http).fetch(url, format, kind) }

    private fun failure(block: () -> Unit): MadoyomiException = assertFailsWith<MadoyomiException> { block() }

    @Test
    fun `AUTO on yuzono index_min_json follows repo_json to index_v2 (upstream behaviour)`() {
        val http = StubRepoHttp(RepoFixtures.yuzonoRoutes())
        val repo = fetch(http, YUZONO_LIST, RepoIndexFormat.AUTO)
        assertEquals(listOf(YUZONO_LIST, YUZONO_REPO, YUZONO_PB), http.requested)
        assertEquals(YUZONO_PB, repo.indexUrl)
        assertFalse(repo.isLegacy)
        assertEquals(YUZONO_KEY, repo.meta.signingKey)
        assertEquals(YUZONO_PKGS, repo.extensions.map { it.pkg })
        assertEquals(listOf(false, false, true, false), repo.extensions.map { it.isTorrent })
    }

    @Test
    fun `JSON on yuzono reads index_min_json and never requests index_pb`() {
        val http = StubRepoHttp(RepoFixtures.yuzonoRoutes())
        val repo = fetch(http, YUZONO_LIST, RepoIndexFormat.JSON)
        assertEquals(listOf(YUZONO_LIST, YUZONO_REPO), http.requested)
        assertEquals(YUZONO_REPO, repo.indexUrl, "a legacy repo is stored under its repo.json, as upstream does")
        assertTrue(repo.isLegacy)
        assertEquals("Yūzōnō", repo.meta.name)
        assertEquals(YUZONO_PKGS, repo.extensions.map { it.pkg })
        assertEquals(listOf(false, false, true, false), repo.extensions.map { it.isTorrent })

        // refreshing a JSON store from its stored repo.json reads the list again, still without index_v2
        http.requested.clear()
        val again = fetch(http, YUZONO_REPO, RepoIndexFormat.JSON)
        assertEquals(listOf(YUZONO_REPO, YUZONO_LIST), http.requested)
        assertEquals(repo, again)
    }

    @Test
    fun `AUTO on a repo_json without index_v2 is a legacy store`() {
        val http = StubRepoHttp(RepoFixtures.yuzonoRoutes().apply { put(YUZONO_REPO, RepoFixtures.bytes("yuzono-repo-no-v2.json")) })
        val fromList = fetch(http, YUZONO_LIST, RepoIndexFormat.AUTO)
        assertTrue(fromList.isLegacy)
        assertEquals(YUZONO_REPO, fromList.indexUrl)
        assertFalse(YUZONO_PB in http.requested)

        val fromRepo = fetch(http, YUZONO_REPO, RepoIndexFormat.AUTO)
        assertEquals(fromList, fromRepo)
    }

    @Test
    fun `AUTO and PROTOBUF read a gzipped index_pb directly`() {
        val http = StubRepoHttp(mutableMapOf(KEIYOUSHI_PB to RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64")))
        val auto = fetch(http, KEIYOUSHI_PB, RepoIndexFormat.AUTO, ExtensionKind.MANGA)
        val forced = fetch(http, KEIYOUSHI_PB, RepoIndexFormat.PROTOBUF, ExtensionKind.MANGA)
        assertEquals(auto, forced)
        assertEquals(KEIYOUSHI_PB, auto.indexUrl)
        assertEquals(3, auto.extensions.size)
        assertEquals(listOf(KEIYOUSHI_PB, KEIYOUSHI_PB), http.requested)
    }

    @Test
    fun `PROTOBUF on a JSON URL and JSON on a protobuf URL are format mismatches`() {
        val http = StubRepoHttp(RepoFixtures.yuzonoRoutes())
        assertEquals(ErrorCode.REPO_FORMAT_MISMATCH, failure { fetch(http, YUZONO_LIST, RepoIndexFormat.PROTOBUF) }.code)
        assertEquals(ErrorCode.REPO_FORMAT_MISMATCH, failure { fetch(http, YUZONO_PB, RepoIndexFormat.JSON) }.code)
    }

    @Test
    fun `a legacy list must be at an index_min_json URL`() {
        val http = StubRepoHttp(mutableMapOf("https://x/extensions.json" to RepoFixtures.bytes("manga-index.min.json")))
        val e = failure { fetch(http, "https://x/extensions.json", RepoIndexFormat.AUTO, ExtensionKind.MANGA) }
        assertEquals(ErrorCode.REPO_FORMAT_MISMATCH, e.code)
        assertTrue(e.message!!.contains("index.min.json"))
    }

    @Test
    fun `legacy manga repo`() {
        val http =
            StubRepoHttp(
                mutableMapOf(
                    MANGA_LIST to RepoFixtures.bytes("manga-index.min.json"),
                    MANGA_REPO to RepoFixtures.mangaRepoJson(),
                ),
            )
        val repo = fetch(http, MANGA_LIST, RepoIndexFormat.AUTO, ExtensionKind.MANGA)
        assertEquals(MANGA_REPO, repo.indexUrl)
        assertEquals("Example manga", repo.meta.badgeLabel)
        assertEquals(listOf("MangaPill", "No Sources"), repo.extensions.map { it.name })
        assertEquals("https://example.org/manga-repo/apk/tachiyomi-en.mangapill-v1.4.12.apk", repo.extensions[0].apkUrl)
    }

    @Test
    fun `v2 index with a separate extension list`() {
        val raw = decompressIfGzipped(RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64"))
        val dto = ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), raw)
        val listUrl = "https://x/list.pb"
        val http =
            StubRepoHttp(
                mutableMapOf(
                    "https://x/index.pb" to
                        ProtoBuf.encodeToByteArray(
                            NetworkExtensionStore.serializer(),
                            dto.copy(extensionList = null, extensionListUrl = listUrl),
                        ),
                    listUrl to
                        RepoFixtures.gzip(
                            ProtoBuf.encodeToByteArray(NetworkExtensionStore.ExtensionList.serializer(), dto.extensionList!!),
                        ),
                ),
            )
        val repo = fetch(http, "https://x/index.pb", RepoIndexFormat.AUTO, ExtensionKind.MANGA)
        assertEquals(listUrl, repo.extensionListUrl)
        assertEquals(3, repo.extensions.size)
        assertEquals(listOf("https://x/index.pb", listUrl), http.requested)
    }

    @Test
    fun `index_v2 redirects are capped`() {
        val loop = "https://loop/repo.json"
        val http =
            StubRepoHttp(
                mutableMapOf(
                    loop to """{"index_v2": "$loop", "meta": {"name": "L", "website": "w", "signingKeyFingerprint": "k"}}""".toByteArray(),
                ),
            )
        val e = failure { fetch(http, loop, RepoIndexFormat.AUTO) }
        assertEquals(ErrorCode.REPO_FORMAT_MISMATCH, e.code)
        assertEquals(RepoIndexFetcher.MAX_REDIRECTS + 1, http.requested.size)
    }

    @Test
    fun `download failures are REPO_FETCH_FAILED with the URL`() {
        val http = StubRepoHttp(RepoFixtures.yuzonoRoutes()).apply { failing += YUZONO_PB }
        val e = failure { fetch(http, YUZONO_LIST, RepoIndexFormat.AUTO) }
        assertEquals(ErrorCode.REPO_FETCH_FAILED, e.code)
        assertEquals(YUZONO_PB, e.details["url"])

        val missing = failure { fetch(StubRepoHttp(), "https://nowhere/index.pb", RepoIndexFormat.AUTO) }
        assertEquals(ErrorCode.REPO_FETCH_FAILED, missing.code)
        assertNull(missing.details["type"])
    }
}
