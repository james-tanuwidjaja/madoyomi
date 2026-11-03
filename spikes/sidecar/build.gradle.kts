plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(libs.javalin)
    implementation(libs.slf4j.simple)
}

application {
    mainClass.set("madoyomi.spike.sidecar.MainKt")
}

tasks.shadowJar {
    // Stable name so the Tauri shell can find it: build/libs/sidecar-all.jar
    archiveBaseName.set("sidecar")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
