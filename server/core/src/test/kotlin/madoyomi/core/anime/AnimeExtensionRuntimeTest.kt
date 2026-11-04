package madoyomi.core.anime

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.webkit.CookieManager
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import madoyomi.core.extension.TestApks
import madoyomi.exthost.Dex2jarRepair
import madoyomi.exthost.ExtensionKind
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.ChildFirstURLClassLoader
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real anime extensions (`tools/apks/`) in the host runtime, without the install pipeline: their network stack comes
 * from Injekt (the app's `NetworkHelper`, whose cookie jar `android.webkit.CookieManager` shares), and their
 * preference screens set up on AndroidCompat's `androidx.preference` without hitting a `"Stub!"`.
 */
class AnimeExtensionRuntimeTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @TempDir
    lateinit var tmp: Path

    private val loaders = mutableListOf<URLClassLoader>()

    private val network: NetworkHelper get() = Injekt.get()

    @AfterEach
    fun tearDown() {
        // the loaders hold the jars open, which would keep @TempDir from deleting them on Windows
        loaders.forEach { it.close() }
    }

    /** Converts [glob]'s APK as an install does and instantiates its sources (no trust gate: nothing is installed). */
    private fun sources(glob: String): List<AnimeSource> {
        val apk = TestApks.apk(glob)
        val jar = (tmp / apk.pkg).createDirectories() / "${apk.pkg}-v${apk.inspection.versionName}.jar"
        PackageTools.dex2jar(apk.path, jar)
        Dex2jarRepair.repair(apk.path, jar)
        val className =
            apk.inspection.metaData
                .getValue(ExtensionKind.ANIME.classKey)
                .let { if (it.startsWith(".")) apk.pkg + it else it }
        val loader = ChildFirstURLClassLoader(arrayOf(jar.toUri().toURL())).also { loaders += it }
        return AnimeExtensionLoader.instantiate(apk.pkg, className) {
            Class.forName(it, false, loader).getDeclaredConstructor().newInstance()
        }
    }

    @Test
    fun `an anime source gets the app's shared client, whose cookie jar CookieManager shares`() {
        val source = assertIs<AnimeHttpSource>(sources(TestApks.ANIMEGG).single())
        // the extension's code asked Injekt for NetworkHelper (AnimeHttpSource.network) and got the app's
        assertSame(network.client.cookieJar, source.client.cookieJar)
        assertTrue(network.client.interceptors.all { it in source.client.interceptors }, "the shared interceptors are kept")

        val url = source.baseUrl.toHttpUrl()
        // a cookie from a response (the OkHttp jar) is visible to android.webkit.CookieManager ...
        source.client.cookieJar.saveFromResponse(url, listOf(Cookie.parse(url, "madoyomi_a=1; Path=/")!!))
        val cookies = CookieManager.getInstance().getCookie(source.baseUrl).orEmpty()
        assertTrue("madoyomi_a=1" in cookies, cookies)
        // ... and one set through CookieManager (what a WebView does) goes out with the source's requests
        CookieManager.getInstance().setCookie(source.baseUrl, "madoyomi_b=2; Path=/")
        assertTrue(
            source.client.cookieJar
                .loadForRequest(url)
                .any { it.name == "madoyomi_b" && it.value == "2" },
        )

        // a rate-limited client built from it, as extensions do, keeps the jar and adds the limiter
        val limited =
            source.client
                .newBuilder()
                .rateLimit(2)
                .build()
        assertSame(network.client.cookieJar, limited.cookieJar)
        assertEquals(source.client.interceptors.size + 1, limited.interceptors.size)
    }

    @Test
    fun `AnimePahe's preference screen sets up without a stub and its values round-trip through SharedPreferences`() {
        val source = assertIs<ConfigurableAnimeSource>(sources(TestApks.ANIMEPAHE).single())
        val prefs = source.getSourcePreferences()
        prefs.edit().clear().apply()
        try {
            val screen = PreferenceScreen(Injekt.get<CustomContext>())
            screen.sharedPreferences = prefs

            source.setupPreferenceScreen(screen)

            val preferences = screen.preferences
            println(preferences.joinToString("\n") { "${it.javaClass.simpleName} ${it.key}: ${it.title}" })
            val lists = preferences.filterIsInstance<ListPreference>().filter { !it.entryValues.isNullOrEmpty() }
            assertTrue(lists.isNotEmpty(), "AnimePahe has list preferences")
            lists.forEach { pref ->
                val last = pref.entryValues.last().toString()
                pref.value = last
                assertEquals(last, pref.value)
                assertEquals(last, prefs.getString(pref.key, null), "ListPreference.setValue persists")
                assertEquals(pref.entries.last(), pref.entry)
                pref.setValueIndex(0)
                assertEquals(pref.entryValues.first().toString(), prefs.getString(pref.key, null))
            }
            preferences.filterIsInstance<TwoStatePreference>().forEach { pref ->
                val flipped = !pref.isChecked
                pref.isChecked = flipped
                assertEquals(flipped, pref.isChecked)
                assertEquals(flipped, prefs.getBoolean(pref.key, !flipped))
            }
            preferences.filterIsInstance<MultiSelectListPreference>().filter { !it.entryValues.isNullOrEmpty() }.forEach { pref ->
                val first = setOf(pref.entryValues.first().toString())
                pref.values = first
                assertEquals(first, pref.values)
                assertEquals(first, prefs.getStringSet(pref.key, null))
            }
        } finally {
            prefs.edit().clear().apply()
        }
    }
}
