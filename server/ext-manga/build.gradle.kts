// Madoyomi: the Mihon/Tachiyomi manga source API (eu.kanade.tachiyomi.source, minus local/, which stays
// in core), moved out of the upstream server module with its packages unchanged.
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
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}
