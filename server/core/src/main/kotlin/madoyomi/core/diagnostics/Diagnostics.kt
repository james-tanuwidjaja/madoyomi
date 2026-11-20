package madoyomi.core.diagnostics

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.typesafe.config.ConfigRenderOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import madoyomi.core.MadoyomiBuildInfo
import madoyomi.core.anime.model.table.AnimeSourceTable
import madoyomi.core.anime.model.table.AnimeTable
import madoyomi.core.backup.BackupSettingsStore
import madoyomi.core.downloads.DownloadSettingsStore
import madoyomi.core.downloads.DownloadsFolder
import madoyomi.core.library.LibraryUpdateSettingsStore
import madoyomi.core.player.PlayerSettingsStore
import madoyomi.core.reader.ReaderSettingsStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.settings.SettingsRegistry
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.ts.config.GlobalConfigManager
import java.io.File
import java.time.Instant

/**
 * What the backend adds to Settings → Export logs (Phase 7 Stage 3): `diagnostics.json` in the zip the shell writes.
 *
 * Always: the backend's and Java's versions and the OS; the repos (name, index URL, kind); the installed extensions
 * (name, package, version, language, kind, repo, state); the settings without secrets (server.conf's keys upstream
 * marks privacy-safe, the others shown as `[REDACTED]`, and Madoyomi's own settings files); the downloads folder.
 * Only with [includeLibrary] (an opt-in, unticked by default): the library's titles with their sources.
 *
 * Paths in it (the data dir, the downloads folder, tool paths) keep the Windows user name here; the shell replaces the
 * user's profile folder in every file of the zip, and the API token never reaches this report (it isn't in any of it).
 */
object Diagnostics {
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val pretty =
        Json {
            prettyPrint = true
            explicitNulls = true
        }

    /** The report as pretty JSON. */
    fun report(includeLibrary: Boolean): String = pretty.encodeToString(JsonElement.serializer(), build(includeLibrary))

    fun build(includeLibrary: Boolean): JsonObject =
        buildJsonObject {
            put("generatedAt", Instant.now().toString())
            put(
                "backend",
                buildJsonObject {
                    put("name", MadoyomiBuildInfo.name)
                    put("version", MadoyomiBuildInfo.version)
                    put("java", "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
                    put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
                    put("processors", Runtime.getRuntime().availableProcessors())
                    put("maxHeapMiB", Runtime.getRuntime().maxMemory() / (1024 * 1024))
                    put("dataDir", File(applicationDirs.dataRoot).absolutePath)
                },
            )
            dbTransaction {
                put("repos", repos())
                put("extensions", extensions())
            }
            put("downloadsFolder", downloadsFolder())
            put("settings", settings())
            if (includeLibrary) {
                put("library", dbTransaction { library() })
            } else {
                put("library", JsonPrimitive("not included (the export's opt-in was off)"))
            }
        }

    private fun repos(): JsonArray =
        buildJsonArray {
            ExtensionStoreTable.selectAll().orderBy(ExtensionStoreTable.type).forEach { row ->
                add(
                    buildJsonObject {
                        put("name", row[ExtensionStoreTable.name])
                        put("indexUrl", row[ExtensionStoreTable.indexUrl])
                        put("kind", row[ExtensionStoreTable.type].name)
                        put("format", row[ExtensionStoreTable.format].name)
                    },
                )
            }
        }

    /** The installed extensions only (a repo lists thousands). */
    private fun extensions(): JsonArray =
        buildJsonArray {
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.isInstalled eq true }
                .orderBy(ExtensionTable.type)
                .orderBy(ExtensionTable.pkgName)
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("name", row[ExtensionTable.name])
                            put("pkgName", row[ExtensionTable.pkgName])
                            put("version", row[ExtensionTable.versionName])
                            put("versionCode", row[ExtensionTable.versionCode])
                            put("lang", row[ExtensionTable.lang])
                            put("kind", row[ExtensionTable.type].name)
                            put("lib", row[ExtensionTable.extensionLib])
                            put("repo", row[ExtensionTable.storeIndexUrl])
                            put("loaded", row[ExtensionTable.isLoaded])
                            put("hasUpdate", row[ExtensionTable.hasUpdate])
                            put("obsolete", row[ExtensionTable.isObsolete])
                        },
                    )
                }
        }

    private fun downloadsFolder(): JsonObject {
        val s = DownloadsFolder.status()
        return buildJsonObject {
            put("path", s.path)
            put("isDefault", s.isDefault)
            put("reachable", s.reachable)
        }
    }

    /** server.conf with only upstream's privacy-safe values, and Madoyomi's settings files (no secrets in them). */
    private fun settings(): JsonObject =
        buildJsonObject {
            val secret =
                SettingsRegistry
                    .getAll()
                    .filter { !it.value.privacySafe }
                    .keys
                    .toList()
            val conf =
                runCatching {
                    val rendered =
                        GlobalConfigManager
                            .getRedactedConfig(secret)
                            .getConfig("server")
                            .root()
                            .render(ConfigRenderOptions.concise())
                    pretty.parseToJsonElement(rendered)
                }.getOrElse { JsonPrimitive("could not be read: ${it.message}") }
            put("server.conf", conf)
            listOf(
                DownloadSettingsStore.FILE_NAME to DownloadSettingsStore.file(),
                PlayerSettingsStore.FILE_NAME to PlayerSettingsStore.file(),
                ReaderSettingsStore.FILE_NAME to ReaderSettingsStore.file(),
                LibraryUpdateSettingsStore.FILE_NAME to LibraryUpdateSettingsStore.file(),
                BackupSettingsStore.FILE_NAME to BackupSettingsStore.file(),
            ).forEach { (name, file) ->
                put(name, readJson(file))
            }
        }

    private fun readJson(file: File): JsonElement =
        when {
            !file.isFile -> {
                JsonPrimitive("(none: the defaults)")
            }

            else -> {
                runCatching {
                    pretty.parseToJsonElement(
                        file.readText(Charsets.UTF_8),
                    )
                }.getOrElse { JsonPrimitive("unreadable: ${it.message}") }
            }
        }

    private fun library(): JsonObject {
        val mangaSources = SourceTable.selectAll().associate { it[SourceTable.id].value to it[SourceTable.name] }
        val animeSources = AnimeSourceTable.selectAll().associate { it[AnimeSourceTable.id].value to it[AnimeSourceTable.name] }
        val manga =
            (MangaTable innerJoin MangaUserTable)
                .selectAll()
                .where { (MangaUserTable.inLibrary eq true) and (MangaUserTable.user eq 1) }
                .map { it[MangaTable.title] to (mangaSources[it[MangaTable.sourceReference]] ?: it[MangaTable.sourceReference].toString()) }
        val anime =
            AnimeTable
                .selectAll()
                .where { AnimeTable.inLibrary eq true }
                .map { it[AnimeTable.title] to (animeSources[it[AnimeTable.sourceReference]] ?: it[AnimeTable.sourceReference].toString()) }

        fun entries(list: List<Pair<String, String>>) =
            buildJsonArray {
                list.sortedBy { it.first.lowercase() }.forEach { (title, source) ->
                    add(
                        buildJsonObject {
                            put("title", title)
                            put("source", source)
                        },
                    )
                }
            }
        return buildJsonObject {
            put("manga", entries(manga))
            put("anime", entries(anime))
        }
    }
}
