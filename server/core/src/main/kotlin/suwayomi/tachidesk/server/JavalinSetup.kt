package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.config.JavalinConfig
import io.javalin.config.RoutesConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import io.javalin.http.RedirectResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.json.JavalinJackson3
import io.javalin.rendering.template.JavalinJte
import io.javalin.websocket.WsContext
import jakarta.servlet.ServletException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import madoyomi.core.MadoyomiFeatures
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import madoyomi.core.error.MadoyomiException
import madoyomi.core.error.RestErrors
import org.eclipse.jetty.ee10.servlet.SessionHandler
import org.eclipse.jetty.http.HttpCookie
import org.eclipse.jetty.server.ServerConnector
import suwayomi.tachidesk.global.GlobalAPI
import suwayomi.tachidesk.graphql.GraphQL
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.i18n.MR
import suwayomi.tachidesk.manga.MangaAPI
import suwayomi.tachidesk.manga.impl.util.network.isHostUnreachable
import suwayomi.tachidesk.opds.OpdsAPI
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.getUserFromContext
import suwayomi.tachidesk.server.user.getUserFromWsContext
import suwayomi.tachidesk.server.util.Browser
import suwayomi.tachidesk.server.util.ServerSubpath
import suwayomi.tachidesk.server.util.WebInterfaceManager
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import org.eclipse.jetty.http.HttpException as JettyHttpException

object JavalinSetup {
    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun <T> future(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = scope.future(block = block)

    /** Madoyomi: the HTTP connector, so the sidecar can report the port actually bound (config port 0 = random). */
    @Volatile
    var connector: ServerConnector? = null
        private set

    /**
     * Madoyomi: [configure] lets the sidecar entry point (madoyomi.api) add its routes and filters. It runs before
     * upstream's [defineCore], so its `beforeMatched`/`wsBeforeUpgrade` handlers run first. Returns the started app.
     */
    fun javalinSetup(configure: (JavalinConfig) -> Unit = {}): Javalin {
        val app =
            Javalin.start { config ->
                val templateEngine = TemplateEngine.createPrecompiled(ContentType.Html)
                config.fileRenderer(JavalinJte(templateEngine))

                config.jsonMapper(JavalinJackson3())

                WebInterfaceManager.setup(config)

                // config.registerPlugin(OpenApiPlugin(getOpenApiOptions()))

                var connectorAdded = false
                config.jetty.modifyServer { server ->
                    if (!connectorAdded) {
                        // Madoyomi: always bind loopback, whatever server.ip says (upstream: host = serverConfig.ip.value)
                        warnIfNotLoopback(serverConfig.ip.value)
                        val connector =
                            ServerConnector(server).apply {
                                host = MadoyomiFeatures.LOOPBACK
                                port = serverConfig.port.value
                            }
                        server.addConnector(connector)
                        this.connector = connector

                        serverConfig.subscribeTo(
                            combine(
                                serverConfig.ip,
                                serverConfig.port,
                            ) { ip, port -> Pair(ip, port) },
                            { (newIp, newPort) ->
                                val oldIp = connector.host
                                val oldPort = connector.port

                                // Madoyomi: loopback only (upstream: connector.host = newIp)
                                warnIfNotLoopback(newIp)
                                connector.host = MadoyomiFeatures.LOOPBACK
                                connector.port = newPort
                                connector.stop()
                                connector.start()

                                logger.info {
                                    "Server ip and/or port changed from $oldIp:$oldPort to ${connector.host}:${connector.localPort} "
                                }
                            },
                        )
                        connectorAdded = true
                    }
                }

                config.jetty.modifyServletContextHandler {
                    it.sessionHandler =
                        SessionHandler().apply {
                            setHttpOnly(true)
                            sameSite = HttpCookie.SameSite.STRICT
                        }
                }

                // Madoyomi: only the Tauri origins, on every path including /health
                // (upstream: path "api/*" with reflectClientOrigin = true, i.e. any origin)
                config.bundledPlugins.enableCors { cors ->
                    cors.addRule {
                        it.allowCredentials = false
                        it.allowHost(MadoyomiFeatures.ALLOWED_ORIGINS.first(), *MadoyomiFeatures.ALLOWED_ORIGINS.drop(1).toTypedArray())
                    }
                }

                // Madoyomi: sidecar routes and filters, registered before upstream's
                configure(config)

                config.routes.defineCore()
                config.routes.apiBuilder {
                    path(ServerSubpath.maybeAddAsPrefix("api/")) {
                        path("v1/") {
                            GlobalAPI.defineEndpoints()
                            MangaAPI.defineEndpoints()
                        }

                        // Madoyomi: OPDS is disabled (MadoyomiFeatures.opdsEnabled)
                        if (MadoyomiFeatures.opdsEnabled) {
                            OpdsAPI.defineEndpoints()
                        }
                        GraphQL.defineEndpoints()

                        after { ctx ->
                            // Madoyomi: a request the token filter rejected never reached its endpoint; keep its 401
                            if (ctx.status() == HttpStatus.UNAUTHORIZED) return@after

                            // If not matched, the request was for an invalid endpoint
                            // Return a 404 instead of redirecting to the UI for usability
                            if (ctx.endpoints().lastHttpEndpoint()?.path == null || ctx.endpoints().lastHttpEndpoint()?.path == "*") {
                                throw NotFoundResponse()
                            }
                        }
                    }
                }

                config.events.serverStarted {
                    // Madoyomi: the connector is loopback-only (upstream listed the configured ip / all IPv4 addresses)
                    val addresses = listOf(MadoyomiFeatures.LOOPBACK)

                    logger.info {
                        // Madoyomi: the bound port, since the configured one may be 0 (upstream: serverConfig.port.value)
                        val port = connector?.localPort ?: serverConfig.port.value
                        buildString {
                            appendLine("Server is available at:")

                            addresses
                                .distinct()
                                .forEach { address ->
                                    appendLine("  http://$address:$port")
                                }
                        }.trimEnd()
                    }
                    if (serverConfig.initialOpenInBrowserEnabled.value) {
                        scope.launch {
                            withTimeoutOrNull(10.seconds) {
                                WebInterfaceManager.isSetupComplete.first { it }
                            }
                            Browser.openInBrowser()
                        }
                    }
                }
            }

        // when JVM is prompted to shutdown, stop javalin gracefully
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                app.stop()
            },
        )

        return app
    }

    // Madoyomi: see the connector setup in javalinSetup
    private fun warnIfNotLoopback(ip: String) {
        if (ip != MadoyomiFeatures.LOOPBACK) {
            logger.warn { "server.ip is $ip, but Madoyomi-Server only binds ${MadoyomiFeatures.LOOPBACK}" }
        }
    }

    fun RoutesConfig.defineCore() {
        val loginPath = ServerSubpath.maybeAddAsPrefix("/login.html")

        get(loginPath) { ctx ->
            val randomToken = UUID.randomUUID().toString()
            ctx.sessionAttribute("form-csrf-token", randomToken)
            val locale: Locale = LocalizationHelper.ctxToLocale(ctx)
            ctx.header("content-type", "text/html")
            val httpCacheSeconds = 1.days.inWholeSeconds
            ctx.header("cache-control", "max-age=$httpCacheSeconds")
            ctx.render(
                "Login.jte",
                mapOf(
                    "locale" to locale,
                    "error" to "",
                    "csrf_token" to randomToken,
                ),
            )
        }

        post(loginPath) { ctx ->
            val locale: Locale = LocalizationHelper.ctxToLocale(ctx)
            val sessionToken = ctx.sessionAttribute<String>("form-csrf-token")
            val username = ctx.formParam("user")
            val password = ctx.formParam("pass")
            val isValid =
                username == serverConfig.authUsername.value &&
                    password == serverConfig.authPassword.value

            val error =
                if (sessionToken == null || ctx.formParam("csrf-token") != sessionToken) {
                    MR.strings.login_csrf_expired.localized(locale)
                } else if (!isValid) {
                    MR.strings.login_failed.localized(locale)
                } else {
                    ctx.req().changeSessionId()
                    val redirect = ctx.queryParam("redirect") ?: ServerSubpath.maybeAddAsPrefix("/")
                    val uri = URI(redirect)
                    if (uri.host != null || uri.scheme != null) {
                        throw IllegalArgumentException("Given redirect is not relative, refusing")
                    }
                    // NOTE: We currently have no session handler attached.
                    // Thus, all sessions are stored in memory and not persisted.
                    // Furthermore, default session timeout appears to be 30m
                    ctx.header("Location", redirect)
                    ctx.sessionAttribute("logged-in", username)
                    throw RedirectResponse(HttpStatus.SEE_OTHER)
                }

            ctx.req().session.invalidate()
            val randomToken = UUID.randomUUID().toString()
            ctx.sessionAttribute("form-csrf-token", randomToken)
            ctx.header("content-type", "text/html")
            ctx.render(
                "Login.jte",
                mapOf(
                    "locale" to locale,
                    "error" to error,
                    "csrf_token" to randomToken,
                ),
            )
        }

        beforeMatched { ctx ->
            val isWebManifest =
                listOf("site.webmanifest", "manifest.json", "login.html").any {
                    ctx.path().endsWith(it)
                }
            val isPageIcon =
                ctx.path().startsWith('/') &&
                    !ctx.path().substring(1).contains('/') &&
                    listOf(".png", ".jpg", ".ico").any { ctx.path().endsWith(it) }
            val isPreFlight = ctx.method() == HandlerType.OPTIONS
            val isApi = ctx.path().startsWith(ServerSubpath.maybeAddAsPrefix("/api/"))

            val requiresAuthentication = !isPreFlight && !isPageIcon && !isWebManifest
            if (!requiresAuthentication) {
                return@beforeMatched
            }

            val authMode = serverConfig.authMode.value

            fun credentialsValid(): Boolean {
                val basicAuthCredentials = ctx.basicAuthCredentials() ?: return false
                val (username, password) = basicAuthCredentials
                return username == serverConfig.authUsername.value &&
                    password == serverConfig.authPassword.value
            }

            fun cookieValid(): Boolean {
                val username = ctx.sessionAttribute<String>("logged-in") ?: return false
                return username == serverConfig.authUsername.value
            }

            if (authMode == AuthMode.SIMPLE_LOGIN && !cookieValid() && !isApi) {
                val url =
                    "$loginPath?redirect=" +
                        URLEncoder.encode(ctx.path() + (ctx.queryString()?.let { "?" + it } ?: ""), Charsets.UTF_8)
                ctx.header("Location", url)
                throw RedirectResponse(HttpStatus.SEE_OTHER)
            }

            if (authMode == AuthMode.BASIC_AUTH && !credentialsValid()) {
                ctx.header("WWW-Authenticate", "Basic")
                throw UnauthorizedResponse()
            }

            if (authMode == AuthMode.BASIC_AUTH && ctx.sessionAttribute<String>("csrf-token") == null) {
                val randomToken = UUID.randomUUID().toString()
                ctx.sessionAttribute("csrf-token", randomToken)
                ctx.res().addHeader(
                    "Set-Cookie",
                    // Madoyomi: cookie name (upstream: suwayomi-basic-csrf)
                    "madoyomi-basic-csrf=$randomToken; Path=${ServerSubpath.maybeAddAsPrefix("/")}; HttpOnly; SameSite=Strict",
                )
            }

            ctx.setAttribute(Attribute.TachideskUser, runBlocking { getUserFromContext(ctx) })
            ctx.setAttribute(Attribute.TachideskBasic, credentialsValid())
        }

        wsBeforeUpgrade { ctx ->
            val authMode = serverConfig.authMode.value

            fun credentialsValid(): Boolean {
                val basicAuthCredentials = ctx.basicAuthCredentials() ?: return false
                val (username, password) = basicAuthCredentials
                return username == serverConfig.authUsername.value &&
                    password == serverConfig.authPassword.value
            }

            if (authMode == AuthMode.BASIC_AUTH && !credentialsValid()) {
                ctx.header("WWW-Authenticate", "Basic")
                throw UnauthorizedResponse()
            }
        }

        wsBefore {
            it.onConnect { ctx ->
                ctx.setAttribute(Attribute.TachideskUser, runBlocking { getUserFromWsContext(ctx) })
            }
        }

        defineExceptionHandlers()
    }

    fun RoutesConfig.defineExceptionHandlers() {
        exception(NullPointerException::class.java) { e, ctx ->
            logger.error(e) { "NullPointerException while handling the request" }
            ctx.status(404)
            // Madoyomi: JSON error body {code, message, details} (upstream: status only), here and in the handlers below
            RestErrors.body(ctx, ErrorCode.NOT_FOUND, e.message)
        }
        exception(NoSuchElementException::class.java) { e, ctx ->
            logger.error(e) { "NoSuchElementException while handling the request" }
            ctx.status(404)
            RestErrors.body(ctx, ErrorCode.NOT_FOUND, e.message) // Madoyomi
        }
        exception(IOException::class.java) { e, ctx ->
            if (e.isHostUnreachable()) {
                // A source host being down isn't a server fault
                logger.warn { "Source host unreachable while handling ${ctx.path()}: ${e.message}" }
                ctx.sourceFailed(e.message)
                return@exception
            }

            logger.error(e) { "IOException while handling the request" }
            ctx.status(500)
            RestErrors.body(ctx, ErrorCode.INTERNAL, e.message ?: "Internal Server Error") // Madoyomi (upstream: ctx.result)
        }

        exception(HttpException::class.java) { e, ctx ->
            // A source error isn't a server fault, and its stack trace only shows obfuscated extension code
            logger.warn { "Source answered HTTP ${e.code} while handling ${ctx.path()}" }
            ctx.sourceFailed(e.message)
        }

        exception(IllegalArgumentException::class.java) { e, ctx ->
            logger.error(e) { "IllegalArgumentException while handling the request" }
            ctx.status(400)
            RestErrors.body(ctx, ErrorCode.BAD_REQUEST, e.message ?: "Bad Request") // Madoyomi (upstream: ctx.result)
        }

        exception(UnauthorizedException::class.java) { e, ctx ->
            logger.error(e) { "UnauthorizedException while handling the request" }
            ctx.status(HttpStatus.UNAUTHORIZED)
            RestErrors.body(ctx, ErrorCode.UNAUTHORIZED, e.message ?: "Unauthorized") // Madoyomi (upstream: ctx.result)
        }

        exception(ForbiddenException::class.java) { e, ctx ->
            logger.error(e) { "ForbiddenException while handling the request" }
            ctx.status(HttpStatus.FORBIDDEN)
            RestErrors.body(ctx, ErrorCode.FORBIDDEN, e.message ?: "Forbidden") // Madoyomi (upstream: ctx.result)
        }

        exception(ServletException::class.java) { e, ctx ->
            logger.error(e) { "Jakarta Exception while handling the request" }
            val rootCause = e.rootCause
            if (rootCause is JettyHttpException) {
                ctx.status(rootCause.code)
                RestErrors.body(ctx, RestErrors.codeFor(rootCause.code), rootCause.reason ?: "Forbidden") // Madoyomi (upstream: ctx.result)
            } else {
                ctx.status(500)
                RestErrors.body(ctx, ErrorCode.INTERNAL, e.message) // Madoyomi
            }
        }

        // Madoyomi: Madoyomi's own errors keep their code; anything else no handler above matches is INTERNAL, with
        // the stack trace in the log only (upstream: Javalin's default 500 page)
        exception(MadoyomiException::class.java) { e, ctx ->
            logger.debug { "${e.code} while handling ${ctx.path()}: ${e.message}" }
            RestErrors.respond(ctx, ErrorClassifier.classify(e))
        }
        exception(Exception::class.java) { e, ctx ->
            val error = ErrorClassifier.classify(e)
            if (error.expected) {
                logger.debug { "${error.code} while handling ${ctx.path()}: ${error.message}" }
            } else {
                logger.error(e) { "Error while handling the request" }
            }
            RestErrors.respond(ctx, error)
        }
    }

    // private fun getOpenApiOptions(): OpenApiOptions {
    //     val applicationInfo =
    //         Info().apply {
    //             version("1.0")
    //             description("Suwayomi-Server Api")
    //         }
    //     return OpenApiOptions(applicationInfo).apply {
    //         path("/api/openapi.json")
    //         swagger(
    //             SwaggerOptions("/api/swagger-ui").apply {
    //                 title("Suwayomi-Server Swagger Documentation")
    //             },
    //         )
    //     }
    // }

    sealed class Attribute<T : Any>(
        val name: String,
    ) {
        data object TachideskUser : Attribute<UserType>("user")

        data object TachideskBasic : Attribute<Boolean>("basicAuthValid")
    }

    // 424 rather than 502: clients and proxies read 502 to 504 as the server being down
    private fun Context.sourceFailed(message: String?) {
        status(HttpStatus.FAILED_DEPENDENCY)
        RestErrors.body(this, ErrorCode.SOURCE_FAILED, message ?: "Source request failed") // Madoyomi (upstream: result)
    }

    private fun <T : Any> Context.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    private fun <T : Any> WsContext.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    fun <T : Any> Context.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttributeOrSet(
        attribute: Attribute<T>,
        replaceIf: (T) -> Boolean = { false },
        set: () -> T,
    ): T {
        var item: T? = attribute(attribute.name)

        if (item != null && replaceIf(item)) {
            item = null
        }

        return item ?: set().also { setAttribute(attribute, it) }
    }

    fun getIPv4Addresses(): List<String> =
        NetworkInterface
            .getNetworkInterfaces()
            .asSequence()
            .flatMap { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@flatMap emptySequence()
                }

                networkInterface.inetAddresses
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { address -> address.hostAddress }
            }.toList()
}
