package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.extension.SignatureTrust
import madoyomi.core.extension.SourceAccess
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every GraphQL error carries `extensions.code`; messages never carry stack traces. */
class ErrorModelTest : ApiGraphQLTest() {
    private val hash = "2222222222222222222222222222222222222222222222222222222222222222"

    private fun insertMangaSource(
        sourceId: Long,
        installed: Boolean,
        signatureHash: String?,
    ) = transaction {
        val extension =
            ExtensionTable.insertAndGetId {
                it[apkName] = "test.apk"
                it[name] = "Test"
                it[pkgName] = "eu.kanade.tachiyomi.extension.en.test$sourceId"
                it[versionName] = "1.0"
                it[versionCode] = 1
                it[lang] = "en"
                it[extensionLib] = "1.5"
                it[isInstalled] = installed
                it[this.signatureHash] = signatureHash
            }
        SourceTable.insert {
            it[id] = EntityID(sourceId, SourceTable)
            it[name] = "Test"
            it[lang] = "en"
            it[this.extension] = extension
        }
    }

    @Test
    fun `graphql-java's own errors get BAD_REQUEST`() {
        val response = graphql("{ noSuchField }")
        assertEquals("BAD_REQUEST", response.errorCode, "errors: ${response.errors}")

        val syntax = graphql("{ extensions { ")
        assertEquals("BAD_REQUEST", syntax.errorCode, "errors: ${syntax.errors}")
    }

    @Test
    fun `a resolver's MadoyomiException keeps its code and details, without a stack trace`() {
        val response =
            graphql(
                """mutation { updateExtension(input: {id: "com.example.missing", patch: {install: true}}) { extension { pkgName } } }""",
            )
        val error = response.errors!!.single()
        assertEquals("NOT_FOUND", error.extensions!!["code"])
        assertEquals(mapOf("pkgName" to "com.example.missing"), error.extensions!!["details"])
        assertEquals("No extension com.example.missing", error.message)
        assertFalse(error.message.contains("\tat "), error.message)
    }

    @Test
    fun `browsing a missing, an anime or an untrusted source says why`() {
        fun browse(id: Long) = graphql("""mutation { fetchSourceManga(input: {source: "$id", type: POPULAR, page: 1}) { hasNextPage } }""")

        assertEquals("NOT_FOUND", browse(42).errorCode)

        val animeExtension =
            transaction {
                ExtensionTable.insertAndGetId {
                    it[apkName] = "anime.apk"
                    it[name] = "Anime"
                    it[pkgName] = "eu.kanade.tachiyomi.animeextension.en.test"
                    it[versionName] = "1.0"
                    it[versionCode] = 1
                    it[lang] = "en"
                    it[extensionLib] = "14"
                    it[type] = ExtensionKind.ANIME
                }
            }
        transaction {
            AnimeSourceTable.insert {
                it[id] = EntityID(43L, AnimeSourceTable)
                it[name] = "Anime"
                it[lang] = "en"
                it[extension] = animeExtension
            }
        }
        val anime = browse(43)
        assertEquals("NOT_FOUND", anime.errorCode)
        assertTrue(
            anime.errors!!
                .first()
                .message
                .contains("anime source"),
            anime.errors!!.first().message,
        )

        insertMangaSource(44, installed = true, signatureHash = null)
        assertEquals("UNTRUSTED", browse(44).errorCode, "an install without a verified signature")

        insertMangaSource(45, installed = true, signatureHash = hash)
        val untrusted = browse(45)
        assertEquals("UNTRUSTED", untrusted.errorCode)
        assertEquals(hash, (untrusted.errors!!.first().extensions!!["details"] as Map<*, *>)["signatureHash"])

        insertMangaSource(46, installed = false, signatureHash = null)
        assertEquals("NOT_FOUND", browse(46).errorCode, "a listed, uninstalled extension")
    }

    @Test
    fun `the classifier maps known exceptions and calls the rest INTERNAL`() {
        val wrapped = CompletionException(MadoyomiException(ErrorCode.SIGNATURE_CHANGED, "changed", mapOf("a" to 1)))
        assertEquals(ErrorCode.SIGNATURE_CHANGED, ErrorClassifier.classify(wrapped).code)
        assertEquals(mapOf("a" to 1), ErrorClassifier.classify(wrapped).details)

        val internal = ErrorClassifier.classify(IllegalStateException("boom"))
        assertEquals(ErrorCode.INTERNAL, internal.code)
        assertEquals("boom", internal.message)
        assertFalse(internal.expected)
        assertEquals("NullPointerException", ErrorClassifier.classify(NullPointerException()).message)

        assertEquals(ErrorCode.SOURCE_FAILED, ErrorClassifier.classify(HttpException(503)).code)
        assertEquals(ErrorCode.SOURCE_FAILED, ErrorClassifier.classify(IOException("down", UnknownHostException("x"))).code)
        assertEquals(ErrorCode.INTERNAL, ErrorClassifier.classify(IOException("disk full")).code)
        assertEquals(ErrorCode.BAD_REQUEST, ErrorClassifier.classify(IllegalArgumentException("bad")).code)
        assertEquals(
            ErrorCode.FORBIDDEN,
            ErrorClassifier
                .classify(
                    suwayomi.tachidesk.server.user
                        .ForbiddenException(),
                ).code,
        )
        assertEquals(
            ErrorCode.UNAUTHORIZED,
            ErrorClassifier
                .classify(
                    suwayomi.tachidesk.server.user
                        .UnauthorizedException(),
                ).code,
        )

        insertMangaSource(47, installed = true, signatureHash = hash)
        val stub = StubSource(47).let { it.SourceNotInstalledException(47) }
        assertEquals(ErrorCode.UNTRUSTED, ErrorClassifier.classify(stub).code, "StubSource's exception is diagnosed")
        transaction { SignatureTrust.trust(hash, 0) }
        assertEquals(ErrorCode.INTERNAL, SourceAccess.diagnose(47).code, "trusted but not loadable")
    }
}
