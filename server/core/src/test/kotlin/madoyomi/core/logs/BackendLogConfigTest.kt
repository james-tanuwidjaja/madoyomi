package madoyomi.core.logs

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.LoggerFactory
import xyz.nulldev.ts.config.initLoggerConfig
import xyz.nulldev.ts.config.updateFileAppender
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The backend's `madoyomi.log`: upstream's logback setup is kept as it is. It writes
 * `<data dir>/logs/madoyomi.log`, rolls it daily and past `server.maxLogFileSize` into gzipped files, keeps
 * `server.maxLogFiles` days of them and caps them all at `server.maxLogFolderSize`.
 */
class BackendLogConfigTest {
    private val dir: File = Files.createTempDirectory("backend-log").toFile()
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val root: Logger = context.getLogger(Logger.ROOT_LOGGER_NAME)
    private val exposedLevel: Level? = context.getLogger("Exposed").level

    /** The server's own appender, if a test before set the server up in this JVM: set aside while these run. */
    private var serverAppender: Appender<ILoggingEvent>? = null

    @BeforeTest
    fun setAside() {
        serverAppender = root.getAppender(APPENDER)?.also { root.detachAppender(it) }
    }

    @AfterTest
    fun cleanUp() {
        root.getAppender(APPENDER)?.let {
            root.detachAppender(it)
            it.stop()
        }
        serverAppender?.let { root.addAppender(it) }
        context.getLogger("Exposed").level = exposedLevel
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun appender() = assertNotNull(root.getAppender(APPENDER) as RollingFileAppender<ILoggingEvent>?)

    @Test
    fun `the defaults roll madoyomi log at 10 MB, keep 31 days and cap the folder at 100 MB`() {
        // ServerConfig's defaults for server.maxLogFiles, maxLogFileSize and maxLogFolderSize
        initLoggerConfig(dir.path, 31, "10mb", "100mb")
        val appender = appender()
        assertEquals(File(dir, "logs/madoyomi.log").path, File(appender.file).path)
        val policy = appender.rollingPolicy as SizeAndTimeBasedRollingPolicy<*>
        assertEquals("${dir.path}/logs/madoyomi_%d{yyyy-MM-dd}_%i.log.gz", policy.fileNamePattern)
        assertEquals(31, policy.maxHistory)
        assertEquals(FileSize.valueOf("10mb").size, sizeOf(policy, "maxFileSize"))
        assertEquals(FileSize.valueOf("100mb").size, sizeOf(policy, "totalSizeCap"))

        root.info("written to madoyomi.log")
        assertTrue(File(dir, "logs/madoyomi.log").readText().contains("written to madoyomi.log"))
    }

    @Test
    fun `server conf's limits apply, and changing them applies at once`() {
        initLoggerConfig(dir.path, 3, "50kb", "1mb")
        val policy = appender().rollingPolicy as SizeAndTimeBasedRollingPolicy<*>
        assertEquals(3, policy.maxHistory)
        assertEquals(FileSize.valueOf("50kb").size, sizeOf(policy, "maxFileSize"))
        assertEquals(FileSize.valueOf("1mb").size, sizeOf(policy, "totalSizeCap"))

        updateFileAppender(5, "20kb", "200kb")
        assertEquals(5, policy.maxHistory)
        assertEquals(FileSize.valueOf("20kb").size, sizeOf(policy, "maxFileSize"))
        assertEquals(FileSize.valueOf("200kb").size, sizeOf(policy, "totalSizeCap"))
        assertTrue(appender().isStarted)
    }

    @Test
    fun `a size server conf can't parse falls back to 10 MB per file and 100 MB in all`() {
        initLoggerConfig(dir.path, 31, "lots", "plenty")
        val policy = appender().rollingPolicy as SizeAndTimeBasedRollingPolicy<*>
        assertEquals(FileSize.valueOf("10mb").size, sizeOf(policy, "maxFileSize"))
        assertEquals(FileSize.valueOf("100mb").size, sizeOf(policy, "totalSizeCap"))
    }

    /** A [FileSize] field of logback's policy (it has setters but no getters for these). */
    private fun sizeOf(
        policy: Any,
        field: String,
    ): Long {
        var type: Class<*>? = policy.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == field }?.let { f ->
                f.isAccessible = true
                return (f.get(policy) as FileSize).size
            }
            type = type.superclass
        }
        error("no field $field in ${policy.javaClass.name}")
    }

    private companion object {
        const val APPENDER = "MadoyomiDefaultAppender"
    }
}
