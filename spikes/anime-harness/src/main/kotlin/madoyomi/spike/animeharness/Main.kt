// Phase 0 / S3 harness: load Aniyomi extension APKs on the JVM, call popular -> details -> episodes ->
// hosters -> videos, and write out/<ext>.result.json, out/video.json (for spikes/mpv-play/play.ps1) and
// out/missing.log. Throwaway spike code: see docs/phases/phase-0-spikes.md.
package madoyomi.spike.animeharness

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.server.applicationSetup
import java.io.File
import java.time.LocalDateTime
import kotlin.system.exitProcess

private val outDir = File("out").apply { mkdirs() }
private val missingFile = File(outDir, "missing.log")
private val prettyJson = Json { prettyPrint = true }

private fun log(msg: String) = println("[harness] $msg")

private fun msSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000

private fun missing(line: String) {
    synchronized(missingFile) { missingFile.appendText(line + "\n") }
}

/** One extension run: info fields, a step log, and the video that S4 should play. */
private class Report(val ext: String) {
    val info = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    val steps = mutableListOf<JsonObject>()
    var playable: JsonObject? = null
    val unresolved = linkedSetOf<String>()

    fun put(key: String, block: JsonObjectBuilder.() -> Unit) {
        info[key] = buildJsonObject(block)
    }

    suspend fun <T> step(name: String, timeoutMs: Long = 120_000, block: suspend () -> T): T? {
        val t0 = System.nanoTime()
        return try {
            val r = withTimeout(timeoutMs) { block() }
            steps += buildJsonObject { put("step", name); put("ok", true); put("ms", msSince(t0)) }
            log("[$ext] $name: ok (${msSince(t0)} ms)")
            r
        } catch (e: Throwable) {
            steps += buildJsonObject {
                put("step", name); put("ok", false); put("ms", msSince(t0)); put("error", e.toString())
                put("rootCause", rootCause(e).toString())
            }
            log("[$ext] $name: FAILED ${e::class.java.name}: ${e.message}")
            recordIfMissing(name, e)
            null
        }
    }

    private fun recordIfMissing(step: String, e: Throwable) {
        val linkage = generateSequence(e) { it.cause }.firstOrNull {
            it is LinkageError || it is ClassNotFoundException || it.message == "Stub!"
        } ?: return
        val frames = linkage.stackTrace.take(8).joinToString("\n") { "      at $it" }
        missing("[$ext] call $step -> ${linkage::class.java.name}: ${linkage.message}\n$frames")
    }

    fun toJson() = buildJsonObject {
        put("extension", ext)
        info.forEach { (k, v) -> put(k, v) }
        putJsonArray("steps") { steps.forEach { add(it) } }
        playable?.let { put("playable", it) }
        putJsonArray("unresolvedClasses") { unresolved.forEach { add(it) } }
    }
}

private fun rootCause(e: Throwable): Throwable = generateSequence(e) { it.cause }.last()

private fun SAnime.toJson() = buildJsonObject {
    put("title", title); put("url", url); put("thumbnail", thumbnail_url); put("status", status)
    put("genre", genre); put("fetchType", fetch_type.name); put("description", description?.take(200))
}

private fun SEpisode.toJson() = buildJsonObject {
    put("name", name); put("url", url); put("episode", episode_number); put("dateUpload", date_upload)
}

private fun Video.toJson() = buildJsonObject {
    put("title", videoTitle)
    put("url", videoUrl)
    put("resolution", resolution)
    put("bitrate", bitrate)
    put("preferred", preferred)
    put("initialized", initialized)
    putJsonObject("headers") { headers?.forEach { (k, v) -> put(k, v) } }
    putJsonArray("subtitles") { subtitleTracks.forEach { addJsonObject { put("url", it.url); put("lang", it.lang) } } }
    putJsonArray("audio") { audioTracks.forEach { addJsonObject { put("url", it.url); put("lang", it.lang) } } }
    putJsonArray("timestamps") {
        timestamps.forEach { addJsonObject { put("start", it.start); put("end", it.end); put("name", it.name); put("type", it.type.name) } }
    }
    putJsonArray("mpvArgs") { mpvArgs.forEach { (k, v) -> addJsonArray { add(k); add(v) } } }
    putJsonArray("ffmpegStreamArgs") { ffmpegStreamArgs.forEach { (k, v) -> addJsonArray { add(k); add(v) } } }
    putJsonArray("ffmpegVideoArgs") { ffmpegVideoArgs.forEach { (k, v) -> addJsonArray { add(k); add(v) } } }
    put("internalDataLength", internalData.length)
}

/** The shape spikes/mpv-play/play.ps1 reads. */
private fun playJson(title: String, v: Video) = buildJsonObject {
    put("title", title)
    put("url", v.videoUrl)
    putJsonObject("headers") { v.headers?.forEach { (k, value) -> put(k, value) } }
    putJsonArray("subtitles") { v.subtitleTracks.forEach { addJsonObject { put("url", it.url); put("lang", it.lang) } } }
    putJsonArray("audio") { v.audioTracks.forEach { addJsonObject { put("url", it.url); put("lang", it.lang) } } }
    putJsonArray("mpvArgs") { v.mpvArgs.forEach { (k, value) -> addJsonArray { add(k); add(value) } } }
}

private fun Video.hasUrl() = videoUrl.startsWith("http", ignoreCase = true)

fun main(args: Array<String>) {
    missingFile.writeText("# Phase 0 / S3 missing class / method log (${LocalDateTime.now()})\n")
    require(args.isNotEmpty()) { "usage: anime-harness <apk>..." }

    val t0 = System.nanoTime()
    applicationSetup()
    log("Suwayomi runtime (Koin, AndroidCompat, NetworkHelper, H2) ready in ${msSince(t0)} ms")

    val reports = args.map { File(it) }.map { apk ->
        val rep = Report(apk.nameWithoutExtension)
        try {
            runBlocking { runExtension(apk, rep) }
        } catch (e: Throwable) {
            log("[${rep.ext}] aborted: $e")
            rep.steps += buildJsonObject { put("step", "aborted"); put("ok", false); put("error", e.toString()) }
        }
        File(outDir, "${rep.ext}.result.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), rep.toJson()))
        rep
    }

    // Pick the video for S4: first extension with a playable URL, preferring one with subtitles.
    val playable = reports.mapNotNull { it.playable }
    val best = playable.firstOrNull { (it["subtitles"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true }
        ?: playable.firstOrNull()
    best?.let { File(outDir, "video.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), it)) }

    val unresolvedTotal = reports.sumOf { it.unresolved.size }
    if (missingFile.readLines().size <= 1) missing("(none: no unresolved classes and no linkage/Stub! errors in any call)")
    log("summary:")
    reports.forEach { r ->
        val failed = r.steps.filter { it["ok"].toString() == "false" }.map { it["step"].toString() }
        log("  ${r.ext}: steps=${r.steps.size} failed=$failed playable=${r.playable != null} unresolvedClasses=${r.unresolved.size}")
    }
    log("video.json: ${if (best != null) "written" else "NOT written (no playable video)"}; missing.log: $unresolvedTotal unresolved class name(s)")
    exitProcess(if (best != null) 0 else 1)
}

private suspend fun runExtension(apk: File, rep: Report) {
    val pkg = PackageTools.getPackageInfo(apk.toPath())
    val meta = pkg.applicationInfo.metaData
    fun metaStr(key: String) = meta.get(key)?.toString()
    val rawClass = metaStr("tachiyomi.animeextension.class") ?: error("no tachiyomi.animeextension.class in manifest")
    val className = if (rawClass.startsWith(".")) pkg.packageName + rawClass else rawClass
    rep.put("package") {
        put("name", pkg.packageName); put("versionName", pkg.versionName); put("class", className)
        put("extensionLib", metaStr("tachiyomix.extensionLib")); put("factory", metaStr("tachiyomi.animeextension.factory"))
        put("nsfw", metaStr("tachiyomi.animeextension.nsfw")); put("torrent", metaStr("tachiyomi.animeextension.torrent"))
    }

    // In-process dex2jar + BytecodeEditor.fixAndroidClasses, exactly as Suwayomi installs an APK.
    val jar = File(outDir, "jars/${apk.nameWithoutExtension}.jar").apply { parentFile.mkdirs() }
    rep.step("dex2jar") { PackageTools.dex2jar(apk.toPath(), jar.toPath()) } ?: return

    val loader = LoggingClassLoader(arrayOf(jar.toURI().toURL())) { name ->
        if (rep.unresolved.add(name)) missing("[${rep.ext}] unresolved class: $name")
    }
    val instance = rep.step("instantiate") { Class.forName(className, false, loader).getDeclaredConstructor().newInstance() } ?: return
    val sources = when (instance) {
        is AnimeSourceFactory -> instance.createSources()
        is AnimeSource -> listOf(instance)
        else -> error("${instance::class.java.name} is neither AnimeSource nor AnimeSourceFactory")
    }
    rep.put("sources") { put("count", sources.size) }
    testSource(rep, sources.first())
}

@Suppress("DEPRECATION")
private suspend fun testSource(rep: Report, src: AnimeSource) {
    rep.put("source") {
        put("id", src.id); put("name", src.name); put("lang", src.lang); put("supportsLatest", src.supportsLatest)
        put("baseUrl", (src as? AnimeHttpSource)?.baseUrl)
        put("superclass", src::class.java.superclass.name)
        put("filters", src.getFilterList().size)
    }

    val popular = rep.step("getPopularAnime(1)") { src.getPopularAnime(1) } ?: return
    rep.info["popular"] = buildJsonObject {
        put("count", popular.animes.size); put("hasNextPage", popular.hasNextPage)
        putJsonArray("first") { popular.animes.take(3).forEach { add(it.toJson()) } }
    }

    for ((i, anime) in popular.animes.take(3).withIndex()) {
        val details = rep.step("getAnimeDetails[#$i]") { src.getAnimeDetails(anime) }
        details?.let { d -> d.url = anime.url; if (runCatching { d.title }.isFailure) d.title = anime.title }
        val target = details ?: anime
        val episodes = rep.step("getEpisodeList[#$i]") { src.getEpisodeList(target) }
        if (episodes.isNullOrEmpty()) continue
        rep.info["anime"] = buildJsonObject {
            put("details", target.toJson()); put("episodeCount", episodes.size)
            putJsonArray("firstEpisodes") { episodes.take(3).forEach { add(it.toJson()) } }
        }
        // Lists are usually newest-first; the oldest episode is the least likely to be still processing.
        val episode = episodes.last()
        val videos = collectVideos(rep, src, episode)
        rep.info["videos"] = buildJsonArray { videos.take(10).forEach { add(it.toJson()) } }
        val pick = videos.firstOrNull { it.hasUrl() } ?: continue
        rep.playable = playJson("${target.title} - ${episode.name} - ${pick.videoTitle}", pick)
        return
    }
}

@Suppress("DEPRECATION")
private suspend fun collectVideos(rep: Report, src: AnimeSource, episode: SEpisode): List<Video> {
    val videos = mutableListOf<Video>()
    val hosters = rep.step("getHosterList") { src.getHosterList(episode) }
    if (hosters != null) {
        rep.info["hosters"] = buildJsonArray {
            hosters.forEach { h ->
                addJsonObject {
                    put("name", h.hosterName); put("url", h.hosterUrl); put("lazy", h.lazy)
                    put("prefetchedVideos", h.videoList?.size); put("internalDataLength", h.internalData.length)
                }
            }
        }
        for (h in hosters.take(4)) {
            val list = h.videoList ?: rep.step("getVideoList(hoster '${h.hosterName}')") { src.getVideoList(h) } ?: continue
            videos += list
        }
    }
    if (videos.isEmpty()) {
        rep.step("getVideoList(episode) [legacy]") { src.getVideoList(episode) }?.let { videos += it }
    }

    val http = src as? AnimeHttpSource ?: return videos
    // Lazy / unresolved videos: lib 16 resolveVideo, then the legacy videoUrl request.
    val resolved = videos.map { v ->
        if (v.hasUrl()) return@map v
        val r = rep.step("resolveVideo('${v.videoTitle}')") { http.resolveVideo(v) }
        if (r != null && r.hasUrl()) return@map r
        val url = rep.step("getVideoUrl('${v.videoTitle}')") { http.getVideoUrl(v) }
        if (url != null && url.startsWith("http")) v.copy(videoUrl = url) else v
    }
    // Apply the extension's own quality preference, as the app does.
    return rep.step("sortVideos") { with(http) { resolved.sortVideos() } } ?: resolved
}
