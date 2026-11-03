package madoyomi.spike.animeharness

import java.net.URL
import java.net.URLClassLoader

/**
 * Same lookup order as Suwayomi's `ChildFirstURLClassLoader` (system -> jar -> parent), so the host's
 * copies of OkHttp/Jsoup/kotlinx/Injekt and our animesource API always win. Records every class name
 * that none of them could resolve: those are the missing-stub candidates for Phase 2.
 */
class LoggingClassLoader(
    urls: Array<URL>,
    private val onMissing: (String) -> Unit,
) : URLClassLoader(urls, null) {
    private val system: ClassLoader = getSystemClassLoader()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            var c = findLoadedClass(name)
            if (c == null) {
                c = try {
                    system.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    null
                }
            }
            if (c == null) {
                c = try {
                    findClass(name)
                } catch (e: ClassNotFoundException) {
                    onMissing(name)
                    throw e
                }
            }
            if (resolve) resolveClass(c)
            return c!!
        }
    }

    override fun getResource(name: String): URL? = system.getResource(name) ?: findResource(name)
}
