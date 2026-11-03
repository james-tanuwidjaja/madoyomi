// Madoyomi: the application module. It holds the sidecar entry point (madoyomi.api.MainKt), the token
// filter and /health, and builds the fat jar Madoyomi-Server-<version>.jar into api/build/.
// The application and shadowJar settings below are upstream's (from the server module's
// build.gradle.kts), moved here when core became a library.
plugins {
    id(
        libs.plugins.kotlin.jvm
            .get()
            .pluginId,
    )
    id(
        libs.plugins.ktlint
            .get()
            .pluginId,
    )
    application
    alias(libs.plugins.shadowjar)
}

dependencies {
    implementation(projects.core)

    implementation(projects.serverConfig)
    implementation(projects.androidCompat.config)

    implementation(libs.bundles.shared)
    implementation(libs.bundles.javalin)
}

application {
    applicationDefaultJvmArgs =
        listOf(
            "-Djunrar.extractor.thread-keep-alive-seconds=30",
        )
    mainClass.set(MainClass)
}

tasks {
    shadowJar {
        isZip64 = true
        manifest {
            attributes(
                "Main-Class" to MainClass,
                "Implementation-Title" to rootProject.name,
                "Implementation-Vendor" to "Madoyomi",
                "Specification-Version" to getTachideskVersion(),
                "Implementation-Version" to getTachideskRevision(),
                "Multi-Release" to true, // needed for polyglot
                "X-JBR-Release" to webviewJbrRelease,
            )
        }
        archiveBaseName.set(rootProject.name)
        archiveVersion.set(getTachideskVersion())
        archiveClassifier.set("")
        // upstream: $rootDir/server/build, i.e. the app module's build dir
        destinationDirectory.set(layout.buildDirectory)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles("META-INF/services")
        dependencies {
            // The deprecated GraalVM js-community POM chain declares `<type>pom</type>`
            // dependencies, which puts literal .pom artifacts on the resolved classpath.
            // Shadow 9 expands every classpath file with zipTree() and fails with
            // "Cannot expand ZIP" on them (GradleUp/shadow#1716). Excluding the
            // POM-only metapackages drops just those .pom files; the actual jars
            // (js-language, truffle-runtime) are still shadowed and their
            // META-INF/services files are still merged by mergeServiceFiles.
            exclude(dependency("org.graalvm.js:js-community:.*"))
            exclude(dependency("org.graalvm.js:js:.*"))
        }
    }
}
