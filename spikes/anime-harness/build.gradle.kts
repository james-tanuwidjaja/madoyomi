// Phase 0 / S3: load yuzono (Aniyomi) extensions on the JVM and call popular -> episodes -> videos.
// The runtime (AndroidCompat, eu.kanade.tachiyomi.network, Injekt-on-Koin, OkHttp, Jsoup, kotlinx.*) comes
// from the Suwayomi-Server fat jar, i.e. exactly what the Madoyomi fork will provide. Only the
// eu.kanade.tachiyomi.animesource API is ours (ported from the Aniyomi app's source-api, see NOTICE.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val upstream = rootDir.resolve("../tools/upstream/Suwayomi-Server/server/build")
val suwayomiJar: File =
    (findProperty("suwayomiJar") as String?)?.let(::file)
        ?: upstream.listFiles { f -> f.name.startsWith("Suwayomi-Server-") && f.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
        ?: error("Suwayomi-Server jar not found in $upstream: build it with ./gradlew :server:shadowJar or pass -PsuwayomiJar=<path>")

// The serialization compiler plugin reads the runtime version from the manifest of the jar holding
// kotlinx.serialization, and the fat jar's says "r1" (-> PROVIDED_RUNTIME_TOO_LOW). So compile against a copy without
// kotlinx/serialization plus the real artifact (same version as Suwayomi's), and run against the untouched fat jar.
val suwayomiCompileJar =
    tasks.register<Jar>("suwayomiCompileJar") {
        from(zipTree(suwayomiJar)) { exclude("kotlinx/serialization/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA") }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveFileName.set("suwayomi-compile.jar")
        destinationDirectory.set(layout.buildDirectory.dir("suwayomi"))
        isZip64 = true
    }

dependencies {
    compileOnly(files(suwayomiCompileJar))
    compileOnly(libs.serialization.json)
    runtimeOnly(files(suwayomiJar))
    // Used by the ported animesource.model.HttpServer (lib 17); not in Suwayomi's jar.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        // The ported API has a few self-references to deprecated members.
        suppressWarnings.set(true)
    }
}

application {
    mainClass.set("madoyomi.spike.animeharness.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    val apkDir = rootDir.resolve("../tools/apks")
    args =
        (findProperty("apks") as String?)?.split(',')
            ?: listOf("aniyomi-en.animegg-v14.2.apk", "aniyomi-en.animepahe-v16.56.apk").map { apkDir.resolve(it).absolutePath }
    val cfg = "suwayomi.tachidesk.config.server"
    systemProperty("$cfg.rootDir", projectDir.resolve("out/data").absolutePath)
    // Port only matters for Suwayomi's single-instance mutex check; keep it off S1's 4567.
    systemProperty("$cfg.port", "4568")
    systemProperty("$cfg.systemTrayEnabled", "false")
    systemProperty("$cfg.initialOpenInBrowserEnabled", "false")
    systemProperty("$cfg.webUIEnabled", "false")
    // KCEF (Chromium for android.webkit.WebView) downloads ~100+ MB on first start: opt in with -Pkcef=true.
    systemProperty("$cfg.kcefEnabled", (findProperty("kcef") as String?) ?: "false")
    standardInput = System.`in`
}
