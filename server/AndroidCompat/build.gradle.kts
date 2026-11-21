import java.security.MessageDigest

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
}

// Madoyomi: android-jar classes that AndroidCompat implements itself (src/main/java). They are left out of the
// android-jar the module uses, so there is exactly one copy of each on every classpath and in the server jar
// (upstream's android-jar is already stripped of the classes upstream implements). See server/UPSTREAM.md.
val madoyomiAndroidOverrides =
    listOf(
        "android/os/LocaleList.class",
    )

// Madoyomi: the android-jar's SHA-256 (Phase 7 Stage 5): the artifact upstream serves can change under the same
// version (see the repository pin in server/build.gradle.kts), so a different file fails the build instead of
// failing it later with unresolved classes.
val androidStubsSha256 = "b91eab7d6c1b0deae4635d351dbaef64266d3a600c81b749b71436e8bdb3aa48"

val androidStubsJar: Configuration =
    configurations.create("androidStubsJar") {
        isTransitive = false
    }

val strippedAndroidStubs =
    tasks.register<Jar>("strippedAndroidStubs") {
        description = "The android-jar without the classes AndroidCompat implements (Madoyomi)"
        archiveFileName.set("android-jar-madoyomi.jar")
        destinationDirectory.set(layout.buildDirectory.dir("android-stubs"))
        inputs.property("overrides", madoyomiAndroidOverrides)
        inputs.property("stubsSha256", androidStubsSha256)
        doFirst {
            val stubs = androidStubsJar.singleFile
            val sha =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(stubs.readBytes())
                    .joinToString("") { "%02x".format(it) }
            check(sha == androidStubsSha256) {
                "${stubs.name} has SHA-256 $sha, not the pinned $androidStubsSha256 (server/build.gradle.kts pins its repository)"
            }
        }
        from({ zipTree(androidStubsJar.singleFile) }) {
            exclude(madoyomiAndroidOverrides)
        }
    }

dependencies {
    // Shared
    implementation(libs.bundles.shared)
    testImplementation(libs.bundles.sharedTest)

    // Android stub library
    // Madoyomi: through strippedAndroidStubs (upstream: implementation(libs.android.stubs))
    androidStubsJar(libs.android.stubs)
    implementation(files(strippedAndroidStubs))

    // XML
    compileOnly(libs.xmlpull)

    // Config API
    implementation(projects.androidCompat.config)

    // APK sig verifier
    compileOnly(libs.apksig)

    // AndroidX annotations
    compileOnly(libs.android.annotations)

    // substitute for duktape-android/quickjs
    implementation(libs.bundles.polyglot)

    // Kotlin wrapper around Java Preferences, makes certain things easier
    implementation(libs.bundles.settings)

    // Android version of SimpleDateFormat
    implementation(libs.icu4j)

    // OpenJDK lacks native JPEG encoder and native WEBP decoder
    implementation(libs.bundles.twelvemonkeys)
    implementation(libs.imageio.webp)
}

tasks.test {
    useJUnitPlatform()
    // Madoyomi: a data root under build/ (as core's tests), so the SharedPreferences tests don't write to the user's data dir
    systemProperty(
        "madoyomi.config.server.rootDir",
        layout.buildDirectory
            .dir("tmp/TestDesk")
            .get()
            .asFile.absolutePath,
    )
}
