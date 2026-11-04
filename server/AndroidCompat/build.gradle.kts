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
}
