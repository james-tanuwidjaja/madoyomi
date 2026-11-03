// Madoyomi: fixed app/jar name (upstream: System.getenv("ProductName") ?: "Suwayomi-Server")
rootProject.name = "Madoyomi-Server"

// Madoyomi: module split (upstream: "server" with server:i18n, server:server-config and
// server:server-config-generate nested inside it). See UPSTREAM.md "Moved paths".
// Dependencies: api -> core -> {ext-manga, ext-anime} -> ext-host -> {AndroidCompat, server-config}
include("api")
include("core")
include("ext-host")
include("ext-manga")
include("ext-anime")
include("i18n")
include("server-config")
include("server-config-generate")

include("AndroidCompat")
include("AndroidCompat:Config")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
