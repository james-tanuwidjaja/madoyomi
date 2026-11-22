import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.KtlintPlugin

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.download)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.jte) apply false
}

allprojects {
    group = "suwayomi"

    version = "1.0"

    repositories {
        mavenCentral()
        google()
        // Madoyomi: the android-jar branch at a fixed commit (upstream: raw/android-jar/, the branch's head). Upstream
        // rewrites com.github.Suwayomi:android-jar:1.0.0 in place whenever its AndroidCompat implements another
        // class (2026-11-07: RenderProcessGoneDetail), which this fork still takes from the stubs. 6830765 is the jar
        // the fork was built and tested with; AndroidCompat/build.gradle.kts checks its SHA-256.
        maven("https://github.com/Suwayomi/Suwayomi-Server/raw/6830765fb39a28e31cd40111c853f5bf88be3f06/")
        maven("https://jitpack.io")
        maven("https://jogamp.org/deployment/maven")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            val javaVersion = JavaVersion.toVersion(libs.versions.jvmTarget.get())
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }

    plugins.withType<KtlintPlugin> {
        extensions.configure<KtlintExtension>("ktlint") {
            version.set(libs.versions.ktlint.get())
            filter {
                exclude("**/generated/**")
            }
        }
    }

    tasks {
        withType<KotlinJvmCompile> {
            if (plugins.hasPlugin(KtlintPlugin::class)) {
                dependsOn("ktlintFormat")
            }
            compilerOptions {
                jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
                freeCompilerArgs.add("-Xcontext-parameters")
            }
        }
    }
}
