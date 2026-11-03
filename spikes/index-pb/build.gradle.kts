plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(libs.serialization.protobuf)
}

application {
    mainClass.set("madoyomi.spike.indexpb.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
}
