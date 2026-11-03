plugins {
    id(
        libs.plugins.kotlin.jvm
            .get()
            .pluginId,
    )
}

dependencies {
    // Core Kotlin
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    
    // Config handling
    implementation(libs.config)
    implementation(libs.config4k)
    
    // Logging
    implementation(libs.slf4japi)
    implementation(libs.kotlinlogging)
    
    // Serialization
    implementation(libs.serialization.json)
    implementation(libs.serialization.protobuf)
    
    // Depend on server-config module for access to ServerConfig and SettingsRegistry
    // Madoyomi: server-config is a top-level module now (upstream: projects.server.serverConfig)
    implementation(projects.serverConfig)
}

// Madoyomi: :core is a sibling now (upstream: the parent, which Gradle always configured first), and
// generateSettings reads its source sets below
evaluationDependsOn(":core")

tasks {
    register<JavaExec>("generateSettings") {
        group = "build setup"
        description = "Generates settings from ServerConfig"

        dependsOn(compileKotlin)

        // Use this module's classpath which includes server-config as dependency
        classpath = sourceSets.main.get().runtimeClasspath
        
        mainClass.set("suwayomi.tachidesk.server.settings.generation.SettingsGeneratorKt")

        // Get reference to server project for file paths
        // Madoyomi: the upstream server module is :core (upstream: project(":server")); see evaluationDependsOn above
        val serverProject = project(":core")
        
        // Set working directory to the server module directory
        workingDir = serverProject.projectDir
        
        inputs.files(
            serverProject.sourceSets.main.get().allSource.filter {
                it.name.contains("ServerConfig") || it.name.contains("Settings")
            },
        )
        
        outputs.files(
            serverProject.file("build/generated/src/main/resources/server-reference.conf"),
            serverProject.file("build/generated/src/test/resources/server-reference.conf"),
            serverProject.file("build/generated/src/main/kotlin/suwayomi/tachidesk/graphql/types/SettingsType.kt"),
            serverProject.file("build/generated/src/main/kotlin/suwayomi/tachidesk/graphql/types/UserSettingsType.kt"),
            serverProject.file("build/generated/src/main/kotlin/suwayomi/tachidesk/manga/impl/backup/proto/models/BackupServerSettings.kt"),
            serverProject.file("build/generated/src/main/kotlin/suwayomi/tachidesk/manga/impl/backup/proto/models/BackupUserSettings.kt"),
            serverProject.file("build/generated/src/main/kotlin/suwayomi/tachidesk/manga/impl/backup/proto/handlers/BackupSettingsHandler.kt"),
            serverProject.file("build/generated/src/main/kotlin/suwayomi/tachidesk/manga/impl/backup/proto/handlers/BackupUserSettingsHandler.kt"),
        )
    }
}