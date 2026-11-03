// Madoyomi: fixed app/jar name (upstream: System.getenv("ProductName") ?: "Suwayomi-Server")
rootProject.name = "Madoyomi-Server"

include("server")
include("server:i18n")
include("server:server-config")
include("server:server-config-generate")

include("AndroidCompat")
include("AndroidCompat:Config")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
