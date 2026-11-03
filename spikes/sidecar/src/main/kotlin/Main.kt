// Phase 0 / S5 spike: dummy JVM backend that the Tauri shell spawns.
// Contract (also used by the real server in Phase 1):
//   - binds 127.0.0.1 on a random free port (port 0)
//   - prints exactly one line `MADOYOMI_PORT=<port>` to stdout once it is listening
//   - serves GET /health -> {"status":"ok",...}
//   - exits when stdin is closed (parent died) as a backup to the Windows job object
package madoyomi.spike.sidecar

import io.javalin.Javalin
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val startedAt = System.currentTimeMillis()

fun main() {
    val app =
        Javalin.start { config ->
            config.jetty.host = "127.0.0.1"
            config.jetty.port = 0
            config.startup.showJavalinBanner = false
            config.bundledPlugins.enableCors { cors ->
                cors.addRule {
                    // Tauri dev server and the production WebView2 origin.
                    it.allowHost("http://localhost:1420", "http://tauri.localhost", "https://tauri.localhost")
                }
            }
            // One stderr line per request so the Tauri log shows the UI's calls.
            config.requestLogger.http { ctx, ms ->
                System.err.println(
                    "request ${ctx.method()} ${ctx.path()} -> ${ctx.status().code} " +
                        "(${"%.1f".format(ms)} ms, origin=${ctx.header("Origin")})",
                )
            }
            config.routes.get("/health") { ctx ->
                // Hand-written JSON keeps Jackson out of the spike.
                val pid = ProcessHandle.current().pid()
                val uptime = System.currentTimeMillis() - startedAt
                val java = System.getProperty("java.version")
                ctx.contentType("application/json")
                    .result("""{"status":"ok","pid":$pid,"uptimeMs":$uptime,"java":"$java"}""")
            }
        }

    // The handshake line. Flush explicitly: stdout is a pipe, not a console.
    println("MADOYOMI_PORT=${app.port()}")
    System.out.flush()

    // When the parent goes away its end of our stdin closes and read() returns -1.
    thread(isDaemon = true, name = "stdin-watchdog") {
        try {
            while (System.`in`.read() != -1) {
                // ignore input
            }
        } catch (_: Exception) {
        }
        System.err.println("stdin closed, shutting down")
        app.stop()
        exitProcess(0)
    }
}
