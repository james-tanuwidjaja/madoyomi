// Madoyomi: the Aniyomi anime source API (eu.kanade.tachiyomi.animesource), ported from Aniyomi's
// source-api (Apache-2.0, see NOTICE.md). Phase 1 only compiles it; loading anime sources is Phase 2.
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
    api(projects.extHost)

    implementation(libs.bundles.shared)

    // animesource.model.HttpServer (extension lib 17). Not in upstream's version catalog, which stays untouched.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}
