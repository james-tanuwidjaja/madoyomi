package suwayomi.tachidesk.manga.impl.util

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.util.Enumeration

/**
 * A parent-last class loader that will try in order:
 * - the system class loader
 * - the child class loader
 * - the parent class loader.
 */
// Madoyomi: every loader has a unique name (upstream: unnamed), which stack frames carry
// (StackTraceElement.getClassLoaderName). R8's short class names (a, b2, v7) repeat across extensions, so a frame's
// class name alone can't tell whose code a thread runs (madoyomi.core.extension.ExtensionThreads)
class ChildFirstURLClassLoader(
    urls: Array<URL>,
    parent: ClassLoader? = null,
) : URLClassLoader("madoyomi-extension-${counter.incrementAndGet()}", urls, parent) {
    private companion object {
        val counter =
            java.util.concurrent.atomic
                .AtomicLong()
    }

    private val systemClassLoader: ClassLoader? = getSystemClassLoader()

    override fun loadClass(
        name: String?,
        resolve: Boolean,
    ): Class<*> {
        var c = findLoadedClass(name)

        if (c == null && systemClassLoader != null) {
            try {
                c = systemClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }

        if (c == null) {
            c =
                try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    // Madoyomi: a class found nowhere (system, jar, parent) is reported to the compat log
                    // (madoyomi.exthost.ExtensionClassLoadHook) before the exception propagates
                    try {
                        super.loadClass(name, resolve)
                    } catch (e: ClassNotFoundException) {
                        if (name != null) {
                            madoyomi.exthost.ExtensionClassLoadHook
                                .classNotFound(urLs.firstOrNull(), name)
                        }
                        throw e
                    }
                }
        }

        if (resolve) {
            resolveClass(c)
        }

        return c
    }

    override fun getResource(name: String?): URL? =
        systemClassLoader?.getResource(name)
            ?: findResource(name)
            ?: super.getResource(name)

    override fun getResources(name: String?): Enumeration<URL> {
        val systemUrls = systemClassLoader?.getResources(name)
        val localUrls = findResources(name)
        val parentUrls = parent?.getResources(name)
        val urls =
            buildList {
                while (systemUrls?.hasMoreElements() == true) {
                    add(systemUrls.nextElement())
                }

                while (localUrls?.hasMoreElements() == true) {
                    add(localUrls.nextElement())
                }

                while (parentUrls?.hasMoreElements() == true) {
                    add(parentUrls.nextElement())
                }
            }

        return object : Enumeration<URL> {
            val iterator = urls.iterator()

            override fun hasMoreElements() = iterator.hasNext()

            override fun nextElement() = iterator.next()
        }
    }

    override fun getResourceAsStream(name: String?): InputStream? {
        return try {
            getResource(name)?.openStream()
        } catch (_: IOException) {
            return null
        }
    }
}
