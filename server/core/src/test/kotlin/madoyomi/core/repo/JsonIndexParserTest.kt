package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.repo.RepoFixtures.YUZONO_KEY
import madoyomi.core.repo.RepoFixtures.YUZONO_LIST
import madoyomi.core.repo.RepoFixtures.YUZONO_PB
import madoyomi.core.repo.RepoFixtures.YUZONO_PKGS
import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.NetworkLegacyExtension
import suwayomi.tachidesk.manga.impl.extension.github.toExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonIndexParserTest {
    private val parser = JsonIndexParser()

    @Test
    fun `yuzono index_min_json maps every field, including the torrent flag`() {
        val parsed = parser.parseIndex(RepoFixtures.bytes("yuzono-index.min.json"), YUZONO_LIST, ExtensionKind.ANIME)
        val listings = assertIs<ParsedIndex.LegacyList>(parsed).extensions
        assertEquals(YUZONO_PKGS, listings.map { it.pkg })

        val onsen = listings[0]
        assertEquals("AnimeOnsen", onsen.name, "the \"Aniyomi: \" prefix is stripped")
        assertEquals(10L, onsen.versionCode)
        assertEquals("14.10", onsen.versionName)
        assertEquals("14", onsen.lib)
        assertEquals("all", onsen.lang)
        assertEquals("https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/aniyomi-all.animeonsen-v14.10.apk", onsen.apkUrl)
        assertEquals(
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/icon/eu.kanade.tachiyomi.animeextension.all.animeonsen.png",
            onsen.iconUrl,
        )
        assertNull(onsen.jarUrl)
        assertFalse(onsen.isTorrent)
        assertEquals(ContentWarning.SAFE, onsen.contentWarning)
        assertEquals(listOf(ListingSource(8542735178285060053, "AnimeOnsen", "all", "https://www.animeonsen.xyz")), onsen.sources)

        assertEquals(9, listings[1].sources.size, "multi-language extension keeps every source")
        assertEquals(listOf("all", "bn", "en", "hi", "ja", "ml", "mr", "ta", "te"), listings[1].sources.map { it.lang })

        val torrent = listings[2]
        assertTrue(torrent.isTorrent, "\"torrent\": 1")
        assertEquals(ContentWarning.MIXED, torrent.contentWarning, "\"nsfw\": 1 maps to MIXED, as upstream does")
        assertEquals("Hentai Torrent (Torrent)", torrent.name)
        assertFalse(listings[3].isTorrent)
    }

    @Test
    fun `legacy manga list matches upstream's mapping`() {
        val text = RepoFixtures.text("manga-index.min.json")
        val base = RepoFixtures.MANGA_LIST.removeSuffix("/index.min.json")
        val listings = parser.parseLegacyList(text, base)
        val store = ExtensionStore(RepoFixtures.MANGA_REPO, "x", "x", "k", ExtensionStore.Contact("w", null), true, null)
        val upstream =
            JsonIndexParser.defaultJson
                .decodeFromString<List<NetworkLegacyExtension>>(text)
                .map { it.toExtensionInfo(store, base) }
        assertEquals(upstream, listings.map { it.toExtensionInfo(store.indexUrl) })

        // no "sources": upstream's single fallback source
        val noSources = listings[1]
        assertEquals("No Sources", noSources.name)
        assertEquals(listOf(ListingSource(0, "Tachiyomi: No Sources", "all", "")), noSources.sources)
    }

    @Test
    fun `repo_json is a legacy repo with its index_v2`() {
        val parsed = parser.parseIndex(RepoFixtures.bytes("yuzono-repo.json"), RepoFixtures.YUZONO_REPO, ExtensionKind.ANIME)
        val repo = assertIs<ParsedIndex.LegacyRepo>(parsed)
        assertEquals(YUZONO_PB, repo.indexV2)
        assertEquals(RepoStoreMeta("Yūzōnō", "Yūzōnō", YUZONO_KEY, "https://yuzono.github.io"), repo.meta)

        val noV2 = parser.parseIndex(RepoFixtures.bytes("yuzono-repo-no-v2.json"), RepoFixtures.YUZONO_REPO, ExtensionKind.ANIME)
        assertNull(assertIs<ParsedIndex.LegacyRepo>(noV2).indexV2)
    }

    @Test
    fun `v2 store as JSON parses to the same listings as the protobuf`() {
        val pb =
            ProtobufIndexParser().parseIndex(
                RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64"),
                RepoFixtures.KEIYOUSHI_PB,
                ExtensionKind.MANGA,
            )
        // re-encode the fixture's DTO as JSON, the way Mihon accepts a v2 index
        val dto =
            kotlinx.serialization.protobuf.ProtoBuf.decodeFromByteArray(
                NetworkExtensionStore.serializer(),
                decompressIfGzipped(RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64")),
            )
        val jsonBody = Json.encodeToString(NetworkExtensionStore.serializer(), dto).toByteArray()

        val parsed = assertIs<ParsedIndex.Store>(parser.parseIndex(jsonBody, "https://example.org/index.json", ExtensionKind.MANGA))
        assertEquals(pb, parsed)
    }

    @Test
    fun `unknown keys are ignored`() {
        // the yuzono fixture carries a "futureField" object on its first entry
        assertTrue(RepoFixtures.text("yuzono-index.min.json").contains("futureField"))
        val repoJson =
            """{"index_v2": null, "meta": {"name": "N", "website": "w", "signingKeyFingerprint": "k", "new": [1, 2]}, "extra": {}}"""
        val repo = assertIs<ParsedIndex.LegacyRepo>(parser.parseIndex(repoJson.toByteArray(), "https://x/repo.json", ExtensionKind.MANGA))
        assertEquals("N", repo.meta.badgeLabel, "badge falls back to the name without shortName")
    }

    @Test
    fun `gzipped JSON with a byte-order mark parses`() {
        val body = RepoFixtures.gzip(("" + RepoFixtures.text("yuzono-index.min.json")).toByteArray())
        val parsed = assertIs<ParsedIndex.LegacyList>(parser.parseIndex(body, YUZONO_LIST, ExtensionKind.ANIME))
        assertEquals(4, parsed.extensions.size)
    }

    @Test
    fun `non-JSON and malformed JSON are format mismatches`() {
        for (body in listOf(
            RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64"),
            "[{\"name\": 1}]".toByteArray(),
            "{\"no\": \"store\"}".toByteArray(),
        )) {
            val e = assertFailsWith<MadoyomiException> { parser.parseIndex(body, YUZONO_LIST, ExtensionKind.ANIME) }
            assertEquals(ErrorCode.REPO_FORMAT_MISMATCH, e.code)
        }
    }
}
