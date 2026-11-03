package xyz.nulldev.ts.config

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import ca.gosyer.appdirs.AppDirs
import java.io.File

// Madoyomi: system property prefix (upstream: "suwayomi.tachidesk.config")
const val CONFIG_PREFIX = "madoyomi.config"

// Madoyomi: default data dir is %APPDATA%\Madoyomi on Windows (upstream: AppDirs "Tachidesk", which is LocalAppData).
// The Tauri shell normally passes --data-dir, which sets the rootDir property.
private fun defaultRootDir(): String {
    val appData = System.getenv("APPDATA")
    if (System.getProperty("os.name").startsWith("Windows") && !appData.isNullOrBlank()) {
        return File(appData, "Madoyomi").absolutePath
    }
    return AppDirs { appName = "Madoyomi" }.getUserDataDir()
}

val ApplicationRootDir: String
    get(): String {
        return System.getProperty(
            "$CONFIG_PREFIX.server.rootDir",
            defaultRootDir(),
        )
    }
