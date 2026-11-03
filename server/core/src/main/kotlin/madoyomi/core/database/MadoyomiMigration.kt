package madoyomi.core.database

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Marks a migration as Madoyomi's (as opposed to upstream Suwayomi's).
 *
 * Madoyomi migrations live in upstream's migration package (the runner only scans that one), always
 * have the highest numbers, and must be idempotent: when an upstream sync renumbers them they may run
 * a second time on an existing database. See `server/UPSTREAM.md`, "Database migrations".
 * `MigrationsFromEmptyDbTest` checks both rules.
 */
interface MadoyomiMigration
