// Throwaway Phase 0 spikes (see docs/phases/phase-0-spikes.md). Not part of the real backend.
rootProject.name = "madoyomi-spikes"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

include("index-pb")
include("sidecar")
include("anime-harness")
