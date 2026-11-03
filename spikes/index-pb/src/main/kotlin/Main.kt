// Phase 0 / S2 spike: parse a Mihon extension-store index (index.pb) and print every extension.
// Schema: docs/research/index-pb.proto. Throwaway code.
@file:OptIn(ExperimentalSerializationApi::class)

package madoyomi.spike.indexpb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File
import java.net.URI
import java.util.zip.GZIPInputStream

@Serializable
data class ExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@Serializable
data class Contact(
    @ProtoNumber(1) val website: String = "",
    @ProtoNumber(2) val discord: String? = null,
)

@Serializable
data class ExtensionList(
    @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
)

@Serializable
data class Extension(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: Resources? = null,
    @ProtoNumber(4) val extensionLib: String = "",
    @ProtoNumber(5) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: Int = 0, // kept as Int so unknown enum values don't throw
    @ProtoNumber(8) val sources: List<Source> = emptyList(),
)

@Serializable
data class Resources(
    @ProtoNumber(1) val apkUrl: String = "",
    @ProtoNumber(2) val iconUrl: String = "",
    @ProtoNumber(501) val jarUrl: String? = null,
)

@Serializable
data class Source(
    @ProtoNumber(1) val id: Long = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val language: String = "",
    @ProtoNumber(4) val homeUrl: String = "",
    @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
    @ProtoNumber(7) val message: String? = null,
)

private val contentWarningNames = mapOf(0 to "unspecified", 1 to "safe", 2 to "mixed", 3 to "nsfw")

const val DEFAULT_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"

fun readIndex(arg: String): ByteArray {
    val raw = if (arg.startsWith("http://") || arg.startsWith("https://")) {
        URI(arg).toURL().openStream().use { it.readBytes() }
    } else {
        File(arg).readBytes()
    }
    // Served gzip-compressed without a Content-Encoding header: sniff the magic bytes.
    val isGzip = raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
    return if (isGzip) GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
}

fun main(args: Array<String>) {
    val input = args.firstOrNull() ?: DEFAULT_URL
    val bytes = readIndex(input)
    // ProtoBuf ignores unknown fields by default, which keeps us tolerant to upstream changes.
    val store = ProtoBuf.decodeFromByteArray<ExtensionStore>(bytes)
    val extensions = store.extensionList?.extensions.orEmpty()

    println("Store: ${store.name} [${store.badgeLabel}] signingKey=${store.signingKey}")
    println("Contact: ${store.contact?.website} ${store.contact?.discord.orEmpty()}")
    store.extensionListUrl?.let { println("extensionListUrl: $it (list is external)") }
    println()
    println("pkg | name | version (code) | lib | lang | warning | #sources | apk")
    for (e in extensions) {
        val langs = e.sources.map { it.language }.toSet()
        val lang = if (langs.size == 1) langs.first() else "all"
        println(
            listOf(
                e.packageName,
                e.name,
                "${e.versionName} (${e.versionCode})",
                e.extensionLib,
                lang,
                contentWarningNames[e.contentWarning] ?: "?${e.contentWarning}",
                e.sources.size,
                e.resources?.apkUrl?.substringAfterLast('/'),
            ).joinToString(" | "),
        )
    }

    val sources = extensions.flatMap { it.sources }
    println()
    println("Totals: ${extensions.size} extensions, ${sources.size} sources, ${sources.map { it.id }.toSet().size} unique source ids")
    println("By lib: " + extensions.groupingBy { it.extensionLib }.eachCount().toSortedMap())
    println("By warning: " + extensions.groupingBy { contentWarningNames[it.contentWarning] }.eachCount())
    println("With jarUrl (501): ${extensions.count { it.resources?.jarUrl != null }}")
    println("Sources with mirrors: ${sources.count { it.mirrorUrls.isNotEmpty() }}, with message: ${sources.count { it.message != null }}")
}
