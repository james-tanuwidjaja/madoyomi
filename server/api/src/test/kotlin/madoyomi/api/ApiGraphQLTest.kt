package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.server.execution.GraphQLRequestHandler
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLResponse
import com.sun.net.httpserver.HttpServer
import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.extension.ExtensionFiles
import madoyomi.core.extension.ExtensionLoadGate
import madoyomi.core.player.PlayerSettingsStore
import madoyomi.core.repo.StubRepoHttp
import madoyomi.exthost.ExtensionKind
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.server.TachideskDataLoaderRegistryFactory
import suwayomi.tachidesk.graphql.server.TachideskGraphQLServer
import suwayomi.tachidesk.graphql.server.subscriptions.GraphQLSubscriptionHandler
import suwayomi.tachidesk.graphql.server.toGraphQLContext
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div

/**
 * A loopback HTTP server that serves a [StubRepoHttp]'s routes, so the production network path
 * (`RepoService.default`, `ExtensionLifecycle.default`, `NetworkRepoHttp` on upstream's OkHttp client) fetches the
 * fixture repos and the real APKs without the internet.
 */
class LoopbackRepoServer : AutoCloseable {
    val routes = StubRepoHttp()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use {
                val body = routes.routes[base + it.requestURI.toString()]
                if (body == null) {
                    it.sendResponseHeaders(404, -1)
                } else {
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

/**
 * GraphQL end to end against the real schema (upstream's plus Madoyomi's, found through ServiceLoader), the production
 * `GraphQL` configuration (`TachideskGraphQLServer.getGraphQLObject()`: Madoyomi's error handler and
 * instrumentation) and the production lifecycle singletons, on a fresh in-memory H2 database per test (all migrations
 * applied) and a loopback repo server.
 */
@OptIn(ExperimentalPathApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApiGraphQLTest : ApplicationTest() {
    protected val admin: UserType = UserType.Admin(1)
    protected val repoServer = LoopbackRepoServer()

    private val graphQL by lazy { TachideskGraphQLServer.getGraphQLObject() }
    private val requestHandler by lazy { GraphQLRequestHandler(graphQL, TachideskDataLoaderRegistryFactory.create()) }
    private val subscriptionHandler by lazy { GraphQLSubscriptionHandler(graphQL, TachideskDataLoaderRegistryFactory.create()) }

    private val dbConfig =
        DatabaseConfig {
            useNestedTransactions = true
            @OptIn(ExperimentalKeywordApi::class)
            preserveKeywordCasing = false
        }
    private var previousDefault: Database? = null
    private lateinit var database: Database

    @BeforeEach
    fun setUpDatabase() {
        Assumptions.assumeTrue(serverConfig.databaseType.value == DatabaseType.H2)
        previousDefault = TransactionManager.defaultDatabase
        database =
            Database.connect(
                "jdbc:h2:mem:madoyomi-api-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig = dbConfig,
            )
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java), database)
        // ApplicationTest pins its own database as the default, so a newly connected one would not take over
        TransactionManager.defaultDatabase = database
        serverConfig.extensionStores.value = emptyList()
        resetRuntime()
        // each test starts from the default player settings, in a file of its own (Phase 4 Stage 5)
        PlayerSettingsStore.fileOverride =
            kotlin.io.path
                .createTempFile("player-settings", ".json")
                .toFile()
                .also { it.delete() }
        PlayerSettingsStore.reload()
    }

    @AfterEach
    fun tearDownDatabase() {
        PlayerSettingsStore.fileOverride?.delete()
        PlayerSettingsStore.fileOverride = null
        PlayerSettingsStore.reload()
        resetRuntime()
        serverConfig.extensionStores.value = emptyList()
        TransactionManager.defaultDatabase = previousDefault
        TransactionManager.closeAndUnregister(database)
    }

    @AfterAll
    fun stopRepoServer() = repoServer.close()

    /** Nothing loaded, no permits, no installed files. */
    private fun resetRuntime() {
        GetSource.unregisterAllSources()
        AnimeGetSource.unregisterAllSources()
        PackageTools.jarLoaderMap.values.forEach { it.close() }
        PackageTools.jarLoaderMap.clear()
        ExtensionLoadGate.clear()
        ExtensionKind.entries.forEach { (ExtensionFiles.extensionsRoot / it.dirName).deleteRecursively() }
    }

    private fun context() = mapOf(JavalinSetup.Attribute.TachideskUser to admin).toGraphQLContext()

    protected fun graphql(
        query: String,
        variables: Map<String, Any?>? = null,
    ): GraphQLResponse<*> =
        runBlocking {
            requestHandler.executeRequest(GraphQLRequest(query = query, variables = variables), context()) as GraphQLResponse<*>
        }

    protected fun subscribe(query: String): Flow<GraphQLResponse<*>> =
        subscriptionHandler.executeSubscription(GraphQLRequest(query = query), context())

    protected fun GraphQLResponse<*>.assertNoErrors(): GraphQLResponse<*> {
        kotlin.test.assertTrue(errors.isNullOrEmpty(), "Expected no GraphQL errors but got: $errors")
        return this
    }

    /** `errors[0].extensions.code`, or null. */
    protected val GraphQLResponse<*>.errorCode: String?
        get() = errors?.firstOrNull()?.extensions?.get("code") as String?

    protected fun Any?.path(vararg keys: String): Any? =
        keys.fold(this) { acc, key ->
            when (acc) {
                is Map<*, *> -> acc[key]
                is List<*> -> key.toIntOrNull()?.let { acc.getOrNull(it) }
                else -> null
            }
        }

    protected fun GraphQLResponse<*>.dataPath(vararg keys: String): Any? = data.path(*keys)
}
