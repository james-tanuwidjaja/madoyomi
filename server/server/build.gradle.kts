import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.time.Instant

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
    application
    alias(libs.plugins.shadowjar)
    id(
        libs.plugins.buildconfig
            .get()
            .pluginId,
    )
    id(
        libs.plugins.jte
            .get()
            .pluginId,
    )
}

dependencies {
    // Shared
    implementation(libs.bundles.shared)
    testImplementation(libs.bundles.sharedTest)

    // OkHttp
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)

    // Javalin api
    implementation(libs.bundles.javalin)
    implementation(libs.bundles.jackson)

    // GraphQL
    implementation(libs.graphql.kotlin.server)
    implementation(libs.graphql.kotlin.scheme)
    implementation(libs.graphql.java.scalars)

    // Exposed ORM
    implementation(libs.bundles.exposed)
    implementation(libs.postgres)
    implementation(libs.h2)
    implementation(libs.hikaricp)

    // Exposed Migrations
    implementation(libs.exposed.migrations)

    // tray icon
    implementation(libs.bundles.systemtray)

    // dependencies of Mihon (Tachiyomi) extensions, some are duplicate, keeping it here for reference
    implementation(libs.injekt)
    implementation(libs.okhttp.core)
    implementation(libs.rxjava)
    implementation(libs.jsoup)

    // ComicInfo
    implementation(libs.serialization.xml.core)
    implementation(libs.serialization.xml)

    // Sort
    implementation(libs.sort)

    // asm for ByteCodeEditor(fixing SimpleDateFormat) (must match Dex2Jar version)
    implementation(libs.asm)

    // Disk & File
    implementation(libs.cache4k)
    implementation(libs.zip4j)
    implementation(libs.commonscompress)
    implementation(libs.junrar)

    // AES/CBC/PKCS7Padding Cypher provider for zh.copymanga
    implementation(libs.bouncycastle)

    // AndroidCompat
    implementation(projects.androidCompat)
    implementation(projects.androidCompat.config)

    // i18n
    implementation(projects.server.i18n)

    // Settings module
    implementation(projects.server.serverConfig)

    // uncomment to test extensions directly
//    implementation(fileTree("lib/"))
    implementation(kotlin("script-runtime"))

    testImplementation(libs.mockk)

    implementation(libs.cron4j)

    implementation(libs.cronUtils)

    implementation(libs.bcrypt)
    implementation(libs.jwt)

    compileOnly(libs.kte)
}

jte {
    generate()
}

application {
    applicationDefaultJvmArgs =
        listOf(
            "-Djunrar.extractor.thread-keep-alive-seconds=30",
        )
    mainClass.set(MainClass)
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
            srcDir("build/generated/src/main/resources")
        }
        kotlin {
            srcDir("build/generated/src/main/kotlin")
        }
    }
    test {
        resources {
            srcDir("build/generated/src/test/resources")
        }
    }
}

buildConfig {
    className("BuildConfig")
    packageName("suwayomi.tachidesk.server.generated")

    useKotlinOutput()

    fun quoteWrap(obj: Any): String = """"$obj""""

    buildConfigField("String", "NAME", quoteWrap(rootProject.name))
    buildConfigField("String", "VERSION", quoteWrap(getTachideskVersion()))
    buildConfigField("String", "REVISION", quoteWrap(getTachideskRevision()))
    buildConfigField("String", "BUILD_TYPE", quoteWrap(if (System.getenv("ProductBuildType") == "Stable") "Stable" else "Preview"))
    buildConfigField("long", "BUILD_TIME", Instant.now().epochSecond.toString())

    buildConfigField("String", "WEBUI_TAG", quoteWrap(webUIRevisionTag))

    // Madoyomi: project links (upstream: Suwayomi GitHub and Discord)
    buildConfigField("String", "GITHUB", quoteWrap("https://github.com/james-tanuwidjaja/madoyomi"))
    buildConfigField("String", "DISCORD", quoteWrap(""))
    buildConfigField("String", "JCEF_VERSION", quoteWrap(libs.versions.jcef.get()))
    buildConfigField("String", "JCEF_JBR_RELEASE", quoteWrap(webviewJbrRelease))
}

tasks {
    shadowJar {
        isZip64 = true
        manifest {
            attributes(
                "Main-Class" to MainClass,
                "Implementation-Title" to rootProject.name,
                // Madoyomi: vendor (upstream: "The Suwayomi Project")
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
        destinationDirectory.set(File("$rootDir/server/build"))
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

    test {
        useJUnitPlatform {
            if (!project.hasProperty("masstest")) {
                exclude("**/masstest/*")
            }
        }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
        // Set before any test runs, a test may load the config before ApplicationTest sets it
        // Madoyomi: config property prefix madoyomi.config (upstream: suwayomi.tachidesk.config)
        systemProperty(
            "madoyomi.config.server.rootDir",
            layout.buildDirectory
                .dir("tmp/TestDesk")
                .get()
                .asFile.absolutePath,
        )
        listOf(
            "testDatabaseType" to "databaseType",
            "testDatabaseUrl" to "databaseUrl",
            "testDatabaseUsername" to "databaseUsername",
            "testDatabasePassword" to "databasePassword",
        ).forEach { (gradleProp, configProp) ->
            project.findProperty(gradleProp)?.let {
                systemProperty("madoyomi.config.server.$configProp", it)
            }
        }
    }

    withType<KotlinJvmCompile> {
        compilerOptions {
            freeCompilerArgs.add(
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            )
        }
    }

    named<Copy>("processResources") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Madoyomi: upstream's downloadWebUI and runElectron tasks are removed. Madoyomi ships no
    // bundled WebUI and no Electron launcher; the Tauri app is the only client.

    runKtlintCheckOverMainSourceSet {
        mustRunAfter(generateJte)
        mustRunAfter(":server:server-config-generate:generateSettings")
    }

    compileKotlin {
        dependsOn(":server:server-config-generate:generateSettings")
    }

    processResources {
        dependsOn(":server:server-config-generate:generateSettings")
    }

    processTestResources {
        dependsOn(":server:server-config-generate:generateSettings")
    }
}
