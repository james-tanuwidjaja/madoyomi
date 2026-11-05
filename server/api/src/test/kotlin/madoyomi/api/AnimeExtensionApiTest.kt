package madoyomi.api

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import madoyomi.core.anime.AnimeGetSource
import madoyomi.core.anime.RealAnimeSources
import madoyomi.core.extension.ExtensionCompatLog
import madoyomi.core.extension.TestApks
import madoyomi.core.extension.TestRepo
import madoyomi.exthost.ExtensionKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real extensions over GraphQL: AnimePahe installed from a loopback repo (trusted by the repo's key, so loaded),
 * with its preferences and its compat issues; kickassanime's filters. Skipped without the APKs.
 */
class AnimeExtensionApiTest : AnimeApiTest() {
    @TempDir
    lateinit var tmp: Path

    private val real by lazy { RealAnimeSources(tmp) }

    @AfterTest
    fun closeLoaders() {
        real.close()
    }

    /** Installs AnimePahe from a loopback repo signed with its own key; returns its pkg and live source id. */
    private fun installAnimePahe(): Pair<String, Long> {
        val apk = TestApks.apk(TestApks.ANIMEPAHE)
        val repo = TestRepo(repoServer.routes, ExtensionKind.ANIME, "${repoServer.base}/anime-${UUID.randomUUID()}", apk.hash)
        repo.serve(TestRepo.Entry(apk))
        graphql(
            """mutation { addExtensionStore(input: {indexUrl: "${repo.listUrl}", type: ANIME, format: JSON}) {
                extensionStore { indexUrl } } }""",
        ).assertNoErrors()
        val installed =
            graphql("""mutation { updateExtension(input: {id: "${apk.pkg}", patch: {install: true}}) { extension { isLoaded } } }""")
                .assertNoErrors()
        assertEquals(true, installed.dataPath("updateExtension", "extension", "isLoaded"))
        val id =
            graphql("""{ animeSources(condition: {extensionPkgName: "${apk.pkg}"}) { nodes { id } } }""")
                .assertNoErrors()
                .dataPath("animeSources", "nodes", "0", "id") as String
        return apk.pkg to id.toLong()
    }

    private fun clearPreferences(sourceId: Long) {
        (AnimeGetSource.cached(sourceId) as? ConfigurableAnimeSource)
            ?.getSourcePreferences()
            ?.edit()
            ?.clear()
            ?.apply()
    }

    private val preferenceFields =
        """__typename
            ... on SwitchPreference { key switchValue: currentValue }
            ... on ListPreference { key listValue: currentValue entryValues }
            ... on EditTextPreference { key textValue: currentValue }"""

    private fun preferences(sourceId: Long): List<*> =
        graphql("""{ animeSources(condition: {id: "$sourceId"}) { nodes { isConfigurable preferences { $preferenceFields } } } }""")
            .assertNoErrors()
            .dataPath("animeSources", "nodes", "0", "preferences")
            .asList

    private fun update(
        sourceId: Long,
        change: String,
    ) = graphql(
        """mutation { updateAnimeSourcePreference(input: {source: "$sourceId", change: {$change}}) {
            preferences { $preferenceFields } source { id name } } }""",
    )

    @Test
    fun `AnimePahe's preferences list, change and read back through GraphQL`() {
        val (_, sourceId) = installAnimePahe()
        clearPreferences(sourceId)
        try {
            val before = preferences(sourceId)
            assertEquals(7, before.size)
            assertEquals(
                mapOf("SwitchPreference" to 3, "ListPreference" to 3, "EditTextPreference" to 1),
                before.groupingBy { it.path("__typename") as String }.eachCount(),
            )

            val switchIndex = before.indexOfFirst { it.path("key") == "preferred_av1" }
            val wasOn = before[switchIndex].path("switchValue") as Boolean
            val toggled = update(sourceId, "position: $switchIndex, switchState: ${!wasOn}").assertNoErrors()
            assertEquals(!wasOn, toggled.dataPath("updateAnimeSourcePreference", "preferences", "$switchIndex", "switchValue"))
            assertEquals(sourceId.toString(), toggled.dataPath("updateAnimeSourcePreference", "source", "id"))

            val listIndex = before.indexOfFirst { it.path("key") == "preferred_quality" }
            val newValue = (before[listIndex].path("entryValues") as List<*>).first { it != before[listIndex].path("listValue") }
            update(sourceId, """position: $listIndex, listState: "$newValue"""").assertNoErrors()

            val after = preferences(sourceId)
            assertEquals(!wasOn, after[switchIndex].path("switchValue"), "persisted, read by the new instance")
            assertEquals(newValue, after[listIndex].path("listValue"))

            assertEquals("BAD_REQUEST", update(sourceId, "position: 99, switchState: true").errorCode)
            assertEquals("BAD_REQUEST", update(sourceId, """position: $switchIndex, listState: "x"""").errorCode)
        } finally {
            clearPreferences(sourceId)
        }
    }

    @Test
    fun `an installed extension's compat issues are on its ExtensionType`() {
        val (pkg, _) = installAnimePahe()

        val issues =
            graphql("""{ extension(pkgName: "$pkg") { compatIssues { kind target origin context count firstSeen lastSeen } } }""")
                .assertNoErrors()
                .dataPath("extension", "compatIssues")
                .asList
        // the link check's 7 Android stubs, all in its EditTextPreference dialog (Phase 2 §10)
        assertEquals(7, issues.size, "$issues")
        assertTrue(issues.all { it.path("kind") == "STUB" && it.path("origin") == "STATIC" })
        assertTrue(issues.any { (it.path("target") as String).startsWith("android.widget.TextView.") })
        assertTrue(issues.all { it.path("count") is String && it.path("firstSeen") is String }, "LongString scalars")

        ExtensionCompatLog.clearAll()
        val none = graphql("""{ extension(pkgName: "$pkg") { compatIssues { target } } }""").assertNoErrors()
        assertTrue(none.dataPath("extension", "compatIssues").asList.isEmpty())
    }

    @Test
    fun `a real extension's filters come through GraphQL`() {
        val source = real.compatApk("aniyomi-en.kickassanime-v*.apk").single()
        register(source)

        val filters =
            graphql("""{ animeSources(condition: {id: "${source.id}"}) { nodes { filters { __typename } } } }""")
                .assertNoErrors()
                .dataPath("animeSources", "nodes", "0", "filters")
                .asList
        // header, separator, 4 selects, a group of 77 checkboxes (Phase 2 §10)
        assertEquals(7, filters.size)
        assertEquals(4, filters.count { it.path("__typename") == "SelectFilter" })
        assertEquals(1, filters.count { it.path("__typename") == "GroupFilter" })
    }
}
