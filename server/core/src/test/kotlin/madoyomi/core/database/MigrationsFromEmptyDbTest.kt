package madoyomi.core.database

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.Migration
import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import madoyomi.core.anime.model.table.AnimeCategoryTable
import madoyomi.core.anime.model.table.AnimeHistoryTable
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.anime.model.table.EpisodeTable
import madoyomi.core.extension.SignatureTrustOrigin
import madoyomi.core.extension.TrustedSignatureTable
import madoyomi.core.repo.RepoIndexFormat
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
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

/**
 * Runs the full migration chain (upstream's and Madoyomi's) on scratch in-memory H2 databases.
 * See server/UPSTREAM.md "Database migrations" for the rules checked here.
 */
class MigrationsFromEmptyDbTest {
    companion object {
        private const val FIRST_MADOYOMI_VERSION = 69
        private const val MIGRATION_PACKAGE = "suwayomi.tachidesk.server.database.migration"

        /** Tables and columns added by Madoyomi's migrations (H2 upper-cases unquoted names). */
        private val NEW_COLUMNS =
            mapOf(
                "EXTENSIONSTORE" to setOf("TYPE", "FORMAT", "LAST_REFRESHED_AT", "LAST_ERROR", "SIGNING_KEY_TRUSTED"),
                "EXTENSION" to setOf("TYPE", "SIGNATURE_HASH", "IS_TORRENT", "IS_LOADED"),
                "TRUSTED_SIGNATURE" to setOf("HASH", "ORIGIN", "STORE_ID", "ADDED_AT"),
                "ANIME_SOURCE" to setOf("ID", "NAME", "LANG", "EXTENSION", "CONTENT_WARNING", "BASE_URL"),
                "ANIME" to
                    setOf(
                        "ID",
                        "URL",
                        "TITLE",
                        "ARTIST",
                        "AUTHOR",
                        "DESCRIPTION",
                        "GENRE",
                        "STATUS",
                        "THUMBNAIL_URL",
                        "IN_LIBRARY",
                        "INITIALIZED",
                        "SOURCE",
                        "ADDED_AT",
                        "LAST_FETCHED_AT",
                        "BACKGROUND_URL",
                        "UPDATE_STRATEGY",
                        "FETCH_TYPE",
                        "SEASON_NUMBER",
                        "MEMO",
                        "PARENT_ID",
                        "SEASON_SOURCE_ORDER",
                    ),
                "EPISODE" to
                    setOf(
                        "ID",
                        "URL",
                        "NAME",
                        "EPISODE_NUMBER",
                        "DATE_UPLOAD",
                        "SCANLATOR",
                        "WATCHED",
                        "BOOKMARK",
                        "LAST_SECOND_SEEN",
                        "TOTAL_SECONDS",
                        "SOURCE_ORDER",
                        "FETCHED_AT",
                        "ANIME",
                        "FILLERMARK",
                        "SUMMARY",
                        "PREVIEW_URL",
                        "MEMO",
                    ),
                "ANIME_HISTORY" to setOf("ID", "EPISODE", "LAST_SEEN"),
                "ANIME_CATEGORY" to setOf("ID", "CATEGORY", "ANIME"),
            )

        /** Foreign keys of the new tables: table -> column -> (referenced table, ON DELETE rule or null if unspecified). */
        private val NEW_FOREIGN_KEYS =
            mapOf(
                "TRUSTED_SIGNATURE" to mapOf("STORE_ID" to ("EXTENSIONSTORE" to "CASCADE")),
                "ANIME_SOURCE" to mapOf("EXTENSION" to ("EXTENSION" to null)),
                "ANIME" to mapOf("PARENT_ID" to ("ANIME" to "SET NULL")),
                "EPISODE" to mapOf("ANIME" to ("ANIME" to "CASCADE")),
                "ANIME_HISTORY" to mapOf("EPISODE" to ("EPISODE" to "CASCADE")),
                "ANIME_CATEGORY" to mapOf("CATEGORY" to ("CATEGORY" to "CASCADE"), "ANIME" to ("ANIME" to "CASCADE")),
            )

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    // Same DatabaseConfig as the app (DBManager) and ApplicationTest, so names match the raw SQL in migrations.
    private val dbConfig =
        DatabaseConfig {
            useNestedTransactions = true
            @OptIn(ExperimentalKeywordApi::class)
            preserveKeywordCasing = false
        }

    @BeforeEach
    fun onlyOnH2() {
        // The migrations branch on the global database type; these checks use H2's INFORMATION_SCHEMA.
        Assumptions.assumeTrue(serverConfig.databaseType.value == DatabaseType.H2)
    }

    private fun loadMigrations(): List<Migration> = loadMigrationsFrom(MIGRATION_PACKAGE, ServerConfig::class.java)

    @Test
    fun `all migrations apply to an empty database, Madoyomi's last`() {
        val migrations = loadMigrations()
        val versions = migrations.map { it.version }.sorted()
        assertEquals((1..migrations.size).toList(), versions, "migration versions must be consecutive from 1")
        assertTrue(migrations.size >= 71, "expected at least M0069 to M0071, found ${migrations.size} migrations")

        val madoyomiVersions = migrations.filter { it is MadoyomiMigration }.map { it.version }.sorted()
        assertEquals(
            (FIRST_MADOYOMI_VERSION..migrations.size).toList(),
            madoyomiVersions,
            "Madoyomi migrations must be exactly the highest-numbered ones, starting at M00$FIRST_MADOYOMI_VERSION",
        )

        withScratchDatabase { db ->
            runMigrations(migrations, db)
            transaction(db) {
                assertEquals(migrations.map { it.version to it.name }.sortedBy { it.first }, recordedMigrations())
            }
        }
    }

    @Test
    fun `new tables, columns and foreign keys exist`() {
        withScratchDatabase { db ->
            runMigrations(loadMigrations(), db)
            transaction(db) {
                NEW_COLUMNS.forEach { (table, expected) ->
                    val actual = columns(table)
                    assertTrue(actual.containsAll(expected), "$table is missing ${expected - actual}")
                }
                NEW_FOREIGN_KEYS.forEach { (table, expected) ->
                    val actual = foreignKeys(table)
                    expected.forEach { (column, reference) ->
                        val (referencedTable, deleteRule) = reference
                        val found = actual[column]
                        assertEquals(referencedTable, found?.first, "$table.$column should reference $referencedTable")
                        if (deleteRule != null) assertEquals(deleteRule, found?.second, "ON DELETE of $table.$column")
                    }
                }
            }
        }
    }

    @Test
    fun `a second run is a no-op`() {
        val migrations = loadMigrations()
        withScratchDatabase { db ->
            runMigrations(migrations, db)
            val (migrationsBefore, schemaBefore) = transaction(db) { recordedMigrations() to schema() }

            runMigrations(migrations, db)

            transaction(db) {
                assertEquals(migrationsBefore, recordedMigrations())
                assertEquals(schemaBefore, schema())
            }
        }
    }

    @Test
    fun `upgrading a pre-Madoyomi database keeps its rows as manga`() {
        val migrations = loadMigrations()
        withScratchDatabase { db ->
            // The state of a database before Stage 4: upstream's migrations only.
            runMigrations(migrations.filter { it.version < FIRST_MADOYOMI_VERSION }, db)
            transaction(db) {
                exec(
                    "INSERT INTO EXTENSIONSTORE (INDEX_URL, NAME, BADGE_LABEL, SIGNING_KEY, CONTACT_WEBSITE) " +
                        "VALUES ('https://example.org/index.min.json', 'Store', 'S', 'abc', 'https://example.org')",
                )
                exec(
                    "INSERT INTO EXTENSION (APK_NAME, EXTENSION_LIB, NAME, PKG_NAME, VERSION_NAME, VERSION_CODE, LANG, " +
                        "CONTENT_WARNING, IS_INSTALLED) " +
                        "VALUES ('example.apk', '1.4', 'Ext', 'eu.kanade.tachiyomi.extension.en.example', '1.4.1', 1, 'en', 0, TRUE)",
                )
            }

            runMigrations(migrations, db)

            transaction(db) {
                assertEquals(migrations.size, recordedMigrations().size)
                val store = ExtensionStoreTable.selectAll().single()
                assertEquals(ExtensionKind.MANGA, store[ExtensionStoreTable.type])
                assertEquals(RepoIndexFormat.AUTO, store[ExtensionStoreTable.format])
                assertFalse(store[ExtensionStoreTable.signingKeyTrusted])
                assertEquals(0L, store[ExtensionStoreTable.lastRefreshedAt])
                assertNull(store[ExtensionStoreTable.lastError])

                val extension =
                    ExtensionTable
                        .selectAll()
                        .where { ExtensionTable.pkgName eq "eu.kanade.tachiyomi.extension.en.example" }
                        .single()
                assertEquals(ExtensionKind.MANGA, extension[ExtensionTable.type])
                assertNull(extension[ExtensionTable.signatureHash])
                assertFalse(extension[ExtensionTable.isTorrent])
                assertFalse(extension[ExtensionTable.isLoaded])
                assertTrue(extension[ExtensionTable.isInstalled])
            }
        }
    }

    @Test
    fun `Madoyomi migrations can run again on a migrated database`() {
        // Backs the renumbering rule: after an upstream sync, Madoyomi migrations may run a second time.
        val migrations = loadMigrations()
        withScratchDatabase { db ->
            runMigrations(migrations, db)
            val schemaBefore = transaction(db) { schema() }

            migrations.filter { it is MadoyomiMigration }.sortedBy { it.version }.forEach { migration ->
                transaction(db) { migration.run() }
            }

            transaction(db) { assertEquals(schemaBefore, schema()) }
        }
    }

    @Test
    fun `the Exposed tables match the migrated schema`() {
        withScratchDatabase { db ->
            runMigrations(loadMigrations(), db)
            transaction(db) {
                val storeId =
                    ExtensionStoreTable.insertAndGetId {
                        it[indexUrl] = "https://example.org/anime/index.min.json"
                        it[name] = "Anime store"
                        it[badgeLabel] = "A"
                        it[signingKey] = "cbec"
                        it[contactWebsite] = "https://example.org"
                        it[type] = ExtensionKind.ANIME
                        it[format] = RepoIndexFormat.JSON
                        it[lastRefreshedAt] = 1_700_000_000
                        it[lastError] = "timeout"
                        it[signingKeyTrusted] = true
                    }
                val extensionId =
                    ExtensionTable.insertAndGetId {
                        it[name] = "AnimeGG"
                        it[pkgName] = "eu.kanade.tachiyomi.animeextension.en.animegg"
                        it[versionName] = "14.1"
                        it[extensionLib] = "14"
                        it[versionCode] = 1
                        it[lang] = "en"
                        it[contentWarning] = 0
                        it[storeIndexUrl] = "https://example.org/anime/index.min.json"
                        it[type] = ExtensionKind.ANIME
                        it[signatureHash] = "cbec"
                        it[isTorrent] = true
                    }
                TrustedSignatureTable.insert {
                    it[id] = "cbec"
                    it[origin] = SignatureTrustOrigin.STORE
                    it[store] = storeId
                    it[addedAt] = 1_700_000_000
                }
                AnimeSourceTable.insert {
                    it[id] = 4_242L
                    it[name] = "AnimeGG"
                    it[lang] = "en"
                    it[extension] = extensionId
                    it[baseUrl] = "https://example.org"
                }
                val animeId =
                    AnimeTable.insertAndGetId {
                        it[url] = "/anime/1"
                        it[title] = "Anime"
                        it[genre] = "Action, Comedy"
                        it[inLibrary] = true
                        it[sourceReference] = 4_242L
                        it[backgroundUrl] = "https://example.org/bg.jpg"
                        it[updateStrategy] = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                        it[fetchType] = FetchType.Seasons
                        it[seasonNumber] = 2.5
                        it[memo] = buildJsonObject { put("key", "value") }
                    }
                val seasonId =
                    AnimeTable.insertAndGetId {
                        it[url] = "/anime/1/season/1"
                        it[title] = "Season 1"
                        it[sourceReference] = 4_242L
                        it[parentId] = animeId
                        it[seasonSourceOrder] = 1
                    }
                val episodeId =
                    EpisodeTable.insertAndGetId {
                        it[url] = "/episode/1"
                        it[name] = "Episode 1"
                        it[episodeNumber] = 1f
                        it[sourceOrder] = 0
                        it[lastSecondSeen] = 90
                        it[anime] = animeId
                        it[fillermark] = true
                        it[summary] = "A summary"
                        it[previewUrl] = "https://example.org/preview.jpg"
                        it[memo] = buildJsonObject { put("n", 1) }
                    }
                AnimeHistoryTable.insert {
                    it[episode] = episodeId
                    it[lastSeen] = 1_700_000_000
                }
                val categoryId = CategoryTable.selectAll().first()[CategoryTable.id]
                AnimeCategoryTable.insert {
                    it[category] = categoryId
                    it[anime] = animeId
                }

                val store = ExtensionStoreTable.selectAll().where { ExtensionStoreTable.id eq storeId }.single()
                assertEquals(ExtensionKind.ANIME, store[ExtensionStoreTable.type])
                assertEquals(RepoIndexFormat.JSON, store[ExtensionStoreTable.format])
                assertTrue(store[ExtensionStoreTable.signingKeyTrusted])
                val extension = ExtensionTable.selectAll().where { ExtensionTable.id eq extensionId }.single()
                assertEquals(ExtensionKind.ANIME, extension[ExtensionTable.type])
                assertTrue(extension[ExtensionTable.isTorrent])
                assertFalse(extension[ExtensionTable.isLoaded])
                val signature = TrustedSignatureTable.selectAll().single()
                assertEquals(SignatureTrustOrigin.STORE, signature[TrustedSignatureTable.origin])
                assertEquals(storeId.value, signature[TrustedSignatureTable.store]?.value)
                assertEquals(extensionId.value, AnimeSourceTable.selectAll().single()[AnimeSourceTable.extension].value)
                val episode = EpisodeTable.selectAll().single()
                assertEquals(animeId.value, episode[EpisodeTable.anime].value)
                assertEquals(90L, episode[EpisodeTable.lastSecondSeen])
                assertTrue(episode[EpisodeTable.fillermark])
                assertEquals("A summary", episode[EpisodeTable.summary])
                assertEquals(buildJsonObject { put("n", 1) }, episode[EpisodeTable.memo])
                assertEquals(1L, AnimeCategoryTable.selectAll().count())
                val anime = AnimeTable.selectAll().where { AnimeTable.id eq animeId }.single()
                assertEquals(AnimeUpdateStrategy.ONLY_FETCH_ONCE, anime[AnimeTable.updateStrategy])
                assertEquals(FetchType.Seasons, anime[AnimeTable.fetchType])
                assertEquals(2.5, anime[AnimeTable.seasonNumber])
                assertEquals(buildJsonObject { put("key", "value") }, anime[AnimeTable.memo])
                val season = AnimeTable.selectAll().where { AnimeTable.id eq seasonId }.single()
                assertEquals(animeId.value, season[AnimeTable.parentId]?.value)
                assertEquals(FetchType.Episodes, season[AnimeTable.fetchType])
                assertEquals("{}", season[AnimeTable.memo].toString())

                // ON DELETE CASCADE: removing the anime removes its episodes, their history and its category links,
                // and removing a store revokes its signing key. A season outlives its parent (SET NULL).
                AnimeTable.deleteWhere { AnimeTable.id eq animeId }
                assertNull(AnimeTable.selectAll().where { AnimeTable.id eq seasonId }.single()[AnimeTable.parentId])
                assertEquals(0L, EpisodeTable.selectAll().count())
                assertEquals(0L, AnimeHistoryTable.selectAll().count())
                assertEquals(0L, AnimeCategoryTable.selectAll().count())
                ExtensionStoreTable.deleteWhere { ExtensionStoreTable.id eq storeId }
                assertEquals(0L, TrustedSignatureTable.selectAll().count())
            }
        }
    }

    @Test
    fun `M0071 drops duplicate anime and episodes and keeps them unique`() {
        val migrations = loadMigrations()
        withScratchDatabase { db ->
            runMigrations(migrations.filter { it.version < 71 }, db)
            val first =
                transaction(db) {
                    // Before M0071 nothing kept them apart.
                    repeat(2) { exec("INSERT INTO ANIME (URL, TITLE, SOURCE) VALUES ('/a', 'A', 1)") }
                    exec("INSERT INTO ANIME (URL, TITLE, SOURCE) VALUES ('/a', 'Same url, other source', 2)")
                    val min =
                        exec("SELECT MIN(ID) FROM ANIME WHERE SOURCE = 1") { rs ->
                            rs.next()
                            rs.getInt(1)
                        }!!
                    repeat(2) { exec("INSERT INTO EPISODE (URL, NAME, SOURCE_ORDER, ANIME) VALUES ('/e', 'E', 1, $min)") }
                    min
                }

            runMigrations(migrations, db)

            transaction(db) {
                val anime = AnimeTable.selectAll().map { it[AnimeTable.sourceReference] to it[AnimeTable.id].value }
                assertEquals(listOf(1L, 2L), anime.map { it.first }.sorted(), "one row per (source, url)")
                assertEquals(first, anime.single { it.first == 1L }.second, "the lowest id is kept")
                assertEquals(1L, EpisodeTable.selectAll().count())
            }
            transaction(db) {
                assertFailsWith<ExposedSQLException> {
                    AnimeTable.insert {
                        it[url] = "/a"
                        it[title] = "Duplicate"
                        it[sourceReference] = 1L
                    }
                }
            }
            transaction(db) {
                val animeId = AnimeTable.selectAll().where { AnimeTable.sourceReference eq 1L }.single()[AnimeTable.id]
                assertFailsWith<ExposedSQLException> {
                    EpisodeTable.insert {
                        it[url] = "/e"
                        it[name] = "Duplicate"
                        it[sourceOrder] = 2
                        it[anime] = animeId
                    }
                }
            }
        }
    }

    /** Runs [block] against a fresh in-memory H2 database and restores the shared test database afterwards. */
    private fun withScratchDatabase(block: (Database) -> Unit) {
        // The scratch database is the explicit default while it runs, then the previous explicit default (or none) is
        // restored. Not `primaryDatabase`: restoring an implicit primary would make it explicit, and an explicit default
        // wins over every later `Database.connect` (other tests rely on that making their scratch database the primary).
        val previousDefault = TransactionManager.defaultDatabase
        val database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-migrations-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig = dbConfig,
            )
        try {
            TransactionManager.defaultDatabase = database
            block(database)
        } finally {
            TransactionManager.closeAndUnregister(database)
            TransactionManager.defaultDatabase = previousDefault
        }
    }

    private fun JdbcTransaction.recordedMigrations(): List<Pair<Int, String>> =
        exec("SELECT VERSION, NAME FROM MIGRATIONS ORDER BY VERSION") { rs ->
            buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) }
        }.orEmpty()

    private fun JdbcTransaction.columns(table: String): Set<String> =
        exec(
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '$table'",
        ) { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }.orEmpty()

    /** column -> (referenced table, ON DELETE rule) for the foreign keys of [table]. */
    private fun JdbcTransaction.foreignKeys(table: String): Map<String, Pair<String, String>> =
        exec(
            """
            SELECT KCU.COLUMN_NAME, UQ.TABLE_NAME, RC.DELETE_RULE
            FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU
              ON KCU.CONSTRAINT_SCHEMA = RC.CONSTRAINT_SCHEMA AND KCU.CONSTRAINT_NAME = RC.CONSTRAINT_NAME
            JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS UQ
              ON UQ.CONSTRAINT_SCHEMA = RC.UNIQUE_CONSTRAINT_SCHEMA AND UQ.CONSTRAINT_NAME = RC.UNIQUE_CONSTRAINT_NAME
            WHERE KCU.TABLE_SCHEMA = 'PUBLIC' AND KCU.TABLE_NAME = '$table'
            """.trimIndent(),
        ) { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2) to rs.getString(3)) }
        }.orEmpty()

    /** Every column (with type, nullability and default), index and constraint, as comparable text. */
    private fun JdbcTransaction.schema(): List<String> {
        fun query(sql: String): List<String> =
            exec(sql) { rs ->
                val width = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..width).joinToString("|") { rs.getString(it).orEmpty() }) }
            }.orEmpty()

        return query(
            "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT " +
                "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, COLUMN_NAME",
        ) +
            query(
                "SELECT TABLE_NAME, INDEX_NAME, INDEX_TYPE_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                    "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, INDEX_NAME",
            ) +
            query(
                "SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, CONSTRAINT_NAME",
            )
    }
}
