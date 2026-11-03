import java.io.BufferedReader

/*
 * Copyright (C) Contributors to the Suwayomi project
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Madoyomi: sidecar entry point (upstream: suwayomi.tachidesk.MainKt)
const val MainClass = "madoyomi.api.MainKt"

// should be bumped with each stable release
// Madoyomi: fixed version; it must stay numeric vX.Y.Z because AppInfo derives the version code from it
val getTachideskVersion = { "v0.1.0" }

val webUIRevisionTag = "r3518"

val webviewJbrRelease = "jbr-release-25.0.4b508.27"

private val getCommitCount = {
    runCatching {
        ProcessBuilder()
            .command("git", "rev-list", "HEAD", "--count")
            .start()
            .let { process ->
                process.waitFor()
                val output = process.inputStream.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                process.destroy()
                output.trim()
            }
    }.getOrDefault("0")
}

// counts commits on the current checked out branch
val getTachideskRevision = { "r${getCommitCount()}" }

