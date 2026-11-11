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

// The integration tests reuse core's test classes (ApplicationTest, TestApks, TestRepo, StubRepoHttp), so core
// must be configured first.
evaluationDependsOn(":core")

val coreTest: SourceSet = project(":core").the<SourceSetContainer>()["test"]

dependencies {
    implementation(projects.core)

    implementation(projects.serverConfig)
    implementation(projects.androidCompat.config)

    implementation(libs.bundles.shared)
    implementation(libs.bundles.javalin)

    // Madoyomi's GraphQL additions (madoyomi.api.graphql): schema types, the DB tables they read, ExtensionKind,
    // and (Phase 2) the anime source API the anime types and tests use
    implementation(projects.extHost)
    implementation(projects.extAnime)
    implementation(libs.graphql.kotlin.server)
    implementation(libs.graphql.kotlin.scheme)
    implementation(libs.bundles.exposed)

    testImplementation(coreTest.output)
    testImplementation(libs.bundles.sharedTest)
    testImplementation(libs.exposed.migrations)
    testImplementation(libs.injekt)
    testImplementation(projects.extManga)
    // the fake mpv named-pipe server (core's tests) runs as mpv in PlaybackApiTest
    testImplementation(libs.jna.platform)
}

application {
    applicationDefaultJvmArgs =
        listOf(
            "-Djunrar.extractor.thread-keep-alive-seconds=30",
        )
    mainClass.set(MainClass)
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            // off (core: on): the integration tests log every Jetty and Exposed debug line; the reports keep them
            showStandardStreams = false
            events("passed", "skipped", "failed")
        }
        // as the server jar's manifest (Add-Opens), for madoyomi.core.extension.ExtensionThreads
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED")
        // the same data root setup as core's tests, under api/build
        systemProperty(
            "madoyomi.config.server.rootDir",
            layout.buildDirectory
                .dir("tmp/TestDesk")
                .get()
                .asFile.absolutePath,
        )
    }

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
                // Madoyomi: unload reads the Runnable of a thread an extension left running (ExtensionThreads)
                "Add-Opens" to "java.base/java.lang java.base/java.util.concurrent",
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
