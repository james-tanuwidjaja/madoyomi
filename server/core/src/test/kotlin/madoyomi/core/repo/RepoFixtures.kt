package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * Repo index fixtures from `core/src/test/resources/repo/` (text only; the protobuf indexes are stored as base64 of
 * the gzipped bytes, exactly as the repos serve them), plus the real URLs they stand for.
 */
object RepoFixtures {
    const val YUZONO_LIST = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
    const val YUZONO_REPO = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/repo.json"
    const val YUZONO_PB = "https://github.com/yuzono/anime-repo/raw/repo/index.pb"
    const val YUZONO_KEY = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"

    const val KEIYOUSHI_PB = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
    const val KEIYOUSHI_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    const val MANGA_LIST = "https://example.org/manga-repo/index.min.json"
    const val MANGA_REPO = "https://example.org/manga-repo/repo.json"

    /** The pkgs in both yuzono fixtures (the JSON list and the index.pb), in index order. */
    val YUZONO_PKGS =
        listOf(
            "eu.kanade.tachiyomi.animeextension.all.animeonsen",
            "eu.kanade.tachiyomi.animeextension.all.animeworldindia",
            "eu.kanade.tachiyomi.animeextension.all.hentaitorrent",
            "eu.kanade.tachiyomi.animeextension.all.missav",
        )

    fun text(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/repo/$name")) { "missing fixture $name" }.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    fun bytes(name: String): ByteArray = text(name).toByteArray()

    /** A `*.gz.b64` fixture decoded to the gzipped bytes a repo serves. */
    fun gzipped(name: String): ByteArray = Base64.getMimeDecoder().decode(text(name))

    fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** A manga `repo.json` for [MANGA_LIST] with the given key and optional `index_v2`. */
    fun mangaRepoJson(
        signingKey: String = KEIYOUSHI_KEY,
        indexV2: String? = null,
    ): ByteArray =
        """{${indexV2?.let { "\"index_v2\": \"$it\"," } ?: ""}"meta": {"name": "Example manga", "website": "https://example.org",
            |"signingKeyFingerprint": "$signingKey"}}
        """.trimMargin().toByteArray()

    /** The yuzono routes: list → repo.json → index.pb. */
    fun yuzonoRoutes(): MutableMap<String, ByteArray> =
        mutableMapOf(
            YUZONO_LIST to bytes("yuzono-index.min.json"),
            YUZONO_REPO to bytes("yuzono-repo.json"),
            YUZONO_PB to gzipped("yuzono-index.pb.gz.b64"),
        )
}

/** A [RepoHttp] that serves fixed bodies, records what was asked for and 404s everything else. */
class StubRepoHttp(
    val routes: MutableMap<String, ByteArray> = mutableMapOf(),
) : RepoHttp {
    val requested = mutableListOf<String>()

    /** URLs that fail as if the network were down. */
    val failing = mutableSetOf<String>()

    override suspend fun get(url: String): ByteArray {
        requested += url
        if (url in failing) throw IOException("connection reset ($url)")
        return routes[url] ?: throw IOException("HTTP 404 ($url)")
    }
}
