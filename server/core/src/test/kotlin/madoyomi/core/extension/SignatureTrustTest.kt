package madoyomi.core.extension

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import madoyomi.core.repo.RepoIndexFetcher
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.core.repo.RepoService
import madoyomi.core.repo.StubRepoHttp
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SignatureTrust] (USER trust next to repo STORE trust) on a scratch in-memory H2 database. */
class SignatureTrustTest {
    companion object {
        private const val KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    private var previousPrimary: Database? = null
    private lateinit var database: Database
    private val http = StubRepoHttp()
    private val repos = RepoService(RepoIndexFetcher(http), now = { 1L }, onTrusted = {})
    private val repo = TestRepo(http, ExtensionKind.ANIME, "https://example.org/trust", KEY)

    @BeforeEach
    fun setUp() {
        Assumptions.assumeTrue(serverConfig.databaseType.value == DatabaseType.H2)
        previousPrimary = TransactionManager.primaryDatabase
        database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-trust-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                    },
            )
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        repo.serve()
    }

    @AfterEach
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        previousPrimary?.let { TransactionManager.defaultDatabase = it }
    }

    private fun origin(hash: String) =
        transaction {
            TrustedSignatureTable
                .selectAll()
                .where { TrustedSignatureTable.id eq hash }
                .firstOrNull()
                ?.get(TrustedSignatureTable.origin)
        }

    @Test
    fun `a user trust is a USER row and can be withdrawn`() {
        assertFalse(SignatureTrust.isTrusted(KEY))
        assertFalse(SignatureTrust.isTrusted(null))

        SignatureTrust.trust(KEY.uppercase(), now = 5)
        assertTrue(SignatureTrust.isTrusted(KEY))
        assertEquals(SignatureTrustOrigin.USER, origin(KEY))
        assertEquals(setOf(KEY), SignatureTrust.trustedHashes())

        assertFalse(SignatureTrust.untrust(KEY, now = 6))
        assertNull(origin(KEY))
    }

    @Test
    fun `trusting a repo key detaches it from the repo, so removing the repo keeps it`() {
        runBlocking { repos.add(repo.listUrl, ExtensionKind.ANIME, RepoIndexFormat.JSON) }
        assertEquals(SignatureTrustOrigin.STORE, origin(KEY))

        SignatureTrust.trust(KEY, now = 5)
        assertEquals(SignatureTrustOrigin.USER, origin(KEY))
        runBlocking { repos.remove(repo.repoUrl) }

        assertTrue(SignatureTrust.isTrusted(KEY))
        assertEquals(SignatureTrustOrigin.USER, origin(KEY))
    }

    @Test
    fun `untrusting a key a repo vouches for leaves it trusted by the repo`() {
        runBlocking { repos.add(repo.listUrl, ExtensionKind.ANIME, RepoIndexFormat.JSON) }

        // a STORE row: the user can't withdraw what the repo vouches for
        assertTrue(SignatureTrust.untrust(KEY, now = 5))
        assertEquals(SignatureTrustOrigin.STORE, origin(KEY))

        // a USER row over a repo's key: withdrawing it hands the key back to the repo
        SignatureTrust.trust(KEY, now = 6)
        assertTrue(SignatureTrust.untrust(KEY, now = 7))
        assertEquals(SignatureTrustOrigin.STORE, origin(KEY))

        runBlocking { repos.remove(repo.repoUrl) }
        assertFalse(SignatureTrust.isTrusted(KEY))
    }

    @Test
    fun `only SHA-256 hashes can be trusted`() {
        assertFailsWith<IllegalArgumentException> { SignatureTrust.trust("not-a-hash", now = 1) }
        assertFailsWith<IllegalArgumentException> { SignatureTrust.trust("abcd", now = 1) }
    }
}
