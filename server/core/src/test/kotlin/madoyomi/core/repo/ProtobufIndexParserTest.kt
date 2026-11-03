package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.repo.RepoFixtures.KEIYOUSHI_KEY
import madoyomi.core.repo.RepoFixtures.KEIYOUSHI_PB
import madoyomi.core.repo.RepoFixtures.YUZONO_KEY
import madoyomi.core.repo.RepoFixtures.YUZONO_PB
import madoyomi.core.repo.RepoFixtures.YUZONO_PKGS
import madoyomi.exthost.ExtensionKind
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.toExtensionInfos
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProtobufIndexParserTest {
    private val parser = ProtobufIndexParser()

    private fun keiyoushi() = parser.parseIndex(RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64"), KEIYOUSHI_PB, ExtensionKind.MANGA)

    @Test
    fun `Keiyoushi index_pb (base64, gzip, protobuf) parses`() {
        val store = keiyoushi()
        assertEquals(
            RepoStoreMeta("Keiyoushi", "KEI", KEIYOUSHI_KEY, "https://keiyoushi.github.io", "https://discord.gg/3FbCpdKbdY"),
            store.meta,
        )
        assertEquals(null, store.extensionListUrl)
        val listings = assertNotNull(store.extensions)
        assertEquals(
            listOf(
                "eu.kanade.tachiyomi.extension.all.comicskingdom",
                "eu.kanade.tachiyomi.extension.all.ahottie",
                "eu.kanade.tachiyomi.extension.all.bookorbit",
            ),
            listings.map { it.pkg },
        )

        val kingdom = listings[0]
        assertEquals("ComicsKingdom", kingdom.name)
        assertEquals(106001L, kingdom.versionCode)
        assertEquals("1.6", kingdom.lib)
        assertEquals("all", kingdom.lang, "sources in en and es")
        assertEquals(listOf("en", "es"), kingdom.sources.map { it.lang })
        assertTrue(kingdom.jarUrl!!.endsWith("tachiyomi-all.comicskingdom-v1.6.1.jar"))
        assertTrue(kingdom.apkUrl.endsWith("tachiyomi-all.comicskingdom-v1.6.1.apk"))
        assertEquals(ContentWarning.NSFW, listings[1].contentWarning)
        assertEquals(ContentWarning.SAFE, listings[2].contentWarning)
        assertTrue(listings.none { it.isTorrent })
    }

    @Test
    fun `mapping matches upstream's toExtensionInfos`() {
        val raw = decompressIfGzipped(RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64"))
        val dto = ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), raw)
        val store = dto.toExtensionStore(KEIYOUSHI_PB)
        assertEquals(dto.extensionList!!.toExtensionInfos(store), keiyoushi().extensions!!.map { it.toExtensionInfo(KEIYOUSHI_PB) })
    }

    @Test
    fun `plain (not gzipped) protobuf parses too`() {
        val raw = decompressIfGzipped(RepoFixtures.gzipped("keiyoushi-index.pb.gz.b64"))
        assertFalse(raw[0] == 0x1f.toByte())
        assertEquals(keiyoushi(), parser.parseIndex(raw, KEIYOUSHI_PB, ExtensionKind.MANGA))
    }

    @Test
    fun `yuzono index_pb uses Aniyomi's schema, with the torrent flag`() {
        val store = parser.parseIndex(RepoFixtures.gzipped("yuzono-index.pb.gz.b64"), YUZONO_PB, ExtensionKind.ANIME)
        assertEquals(YUZONO_KEY, store.meta.signingKey)
        assertEquals("Yū", store.meta.badgeLabel)
        val listings = assertNotNull(store.extensions)
        assertEquals(YUZONO_PKGS, listings.map { it.pkg })
        assertEquals(listOf(false, false, true, false), listings.map { it.isTorrent })
        assertEquals("AnimeOnsen", listings[0].name, "no prefix in the v2 index")
        assertEquals("all", listings[1].lang)
        assertEquals(9, listings[1].sources.size)
        assertEquals(ContentWarning.NSFW, listings[2].contentWarning)
        assertTrue(listings[0].apkUrl.startsWith("https://cdn.jsdelivr.net/gh/yuzono/anime-repo@repo/apk/"))

        // the same entries as the legacy JSON, apart from the URLs and the content-warning granularity
        val json =
            (
                JsonIndexParser().parseIndex(
                    RepoFixtures.bytes("yuzono-index.min.json"),
                    RepoFixtures.YUZONO_LIST,
                    ExtensionKind.ANIME,
                ) as ParsedIndex.LegacyList
            ).extensions
        assertEquals(
            json.map { listOf(it.pkg, it.name, it.versionCode, it.versionName, it.lib, it.isTorrent, it.sources.map { s -> s.id }) },
            listings.map { listOf(it.pkg, it.name, it.versionCode, it.versionName, it.lib, it.isTorrent, it.sources.map { s -> s.id }) },
        )
    }

    @Test
    fun `an index in the other kind's schema still parses, so the repo type check can name the problem`() {
        // upstream's Mihon DTO can't read yuzono's index.pb (field 8 is a bool there, the sources list in Mihon)
        val raw = decompressIfGzipped(RepoFixtures.gzipped("yuzono-index.pb.gz.b64"))
        assertFailsWith<IllegalArgumentException> { ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), raw) }

        val asManga = parser.parseIndex(raw, YUZONO_PB, ExtensionKind.MANGA)
        assertEquals(YUZONO_PKGS, asManga.extensions!!.map { it.pkg })
        assertTrue(asManga.extensions!!.none { ExtensionKind.MANGA.matchesPackage(it.pkg) })
    }

    @Test
    fun `unknown fields are skipped`() {
        val future =
            FutureStore(
                name = "Future",
                badgeLabel = "FUT",
                signingKey = "ab:CD",
                contact = NetworkExtensionStore.Contact("https://future.example", null),
                extensionList =
                    FutureList(
                        listOf(
                            FutureExtension(
                                name = "One",
                                packageName = "eu.kanade.tachiyomi.extension.en.one",
                                resources = FutureResources("https://f/one.apk", "https://f/one.png", extra = "x"),
                                extensionLib = "1.7",
                                versionCode = 107001,
                                versionName = "1.7.1",
                                contentWarning = 2,
                                sources = listOf(FutureSource(42, "One", "en", "https://one", contentWarning = 3, note = "new")),
                                rating = 5,
                            ),
                        ),
                    ),
                futureTop = "added later",
            )
        val bytes = RepoFixtures.gzip(ProtoBuf.encodeToByteArray(FutureStore.serializer(), future))

        val store = parser.parseIndex(bytes, "https://f/index.pb", ExtensionKind.MANGA)
        assertEquals("ab:CD", store.meta.signingKey)
        assertEquals("abcd", store.meta.normalizedSigningKey())
        val one = store.extensions!!.single()
        assertEquals("eu.kanade.tachiyomi.extension.en.one", one.pkg)
        assertEquals(ContentWarning.MIXED, one.contentWarning)
        assertEquals(listOf(ListingSource(42, "One", "en", "https://one")), one.sources)
    }

    @Test
    fun `JSON and garbage are format mismatches`() {
        for (body in listOf(RepoFixtures.bytes("yuzono-repo.json"), byteArrayOf(0x0a, 0x7f, 0x01))) {
            val e = assertFailsWith<MadoyomiException> { parser.parseIndex(body, "https://x/index.pb", ExtensionKind.MANGA) }
            assertEquals(ErrorCode.REPO_FORMAT_MISMATCH, e.code)
        }
    }
}

// A newer index generator's output: Mihon's fields plus ones this parser doesn't know about.

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class FutureStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: NetworkExtensionStore.Contact,
    @ProtoNumber(101) val extensionList: FutureList,
    @ProtoNumber(999) val futureTop: String,
)

@Serializable
private data class FutureList(
    @ProtoNumber(1) val extensions: List<FutureExtension>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class FutureExtension(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val packageName: String,
    @ProtoNumber(3) val resources: FutureResources,
    @ProtoNumber(4) val extensionLib: String,
    @ProtoNumber(5) val versionCode: Long,
    @ProtoNumber(6) val versionName: String,
    @ProtoNumber(7) val contentWarning: Int,
    @ProtoNumber(8) val sources: List<FutureSource>,
    @ProtoNumber(42) val rating: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class FutureResources(
    @ProtoNumber(1) val apkUrl: String,
    @ProtoNumber(2) val iconUrl: String,
    @ProtoNumber(77) val extra: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class FutureSource(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val language: String,
    @ProtoNumber(4) val homeUrl: String,
    // Mihon reserves 6 for a per-source content warning
    @ProtoNumber(6) val contentWarning: Int,
    @ProtoNumber(999) val note: String,
)
