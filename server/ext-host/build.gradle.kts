// Madoyomi: extension host. Upstream's OkHttp network stack and helpers (eu.kanade.tachiyomi.network/util),
// the dex2jar bytecode fixer and the extension classloader, moved out of the upstream server module
// with their packages unchanged (see UPSTREAM.md "Moved paths"). No DB, no GraphQL, no Javalin.
plugins {
    id(
        libs.plugins.kotlin.jvm
            .get()
            .pluginId,
    )
    id(
        libs.plugins.kotlin.serialization
            .get()
            .pluginId,
    )
    id(
        libs.plugins.ktlint
            .get()
            .pluginId,
    )
    `java-library`
}

dependencies {
    // Part of the API that extensions (and ext-manga/ext-anime) compile against
    api(projects.androidCompat)
    api(projects.androidCompat.config)
    api(libs.bundles.okhttp)
    api(libs.okio)
    api(libs.injekt)
    api(libs.rxjava)
    api(libs.jsoup)

    implementation(libs.bundles.shared)

    // serverConfig (Cloudflare/FlareSolverr and SOCKS proxy settings)
    implementation(projects.serverConfig)

    // natural sort (util.lang.StringExtensions), EPUB zip reading (util.storage.EpubFile)
    implementation(libs.sort)
    implementation(libs.commonscompress)

    // BytecodeEditor (must match the dex2jar version)
    implementation(libs.asm)
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}
