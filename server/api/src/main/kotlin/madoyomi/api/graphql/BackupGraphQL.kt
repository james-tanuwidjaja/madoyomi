package madoyomi.api.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.Flow
import madoyomi.core.backup.BackupFileInfo
import madoyomi.core.backup.BackupFiles
import madoyomi.core.backup.BackupReport
import madoyomi.core.backup.BackupScheduler
import madoyomi.core.backup.BackupSettings
import madoyomi.core.backup.BackupSettingsStore
import madoyomi.core.backup.MadoyomiBackupExport
import madoyomi.core.backup.MadoyomiBackupRestore
import madoyomi.core.backup.RestoreMode
import madoyomi.core.backup.RestoreRequest
import madoyomi.core.backup.RestoreStatus
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

/*
 * Madoyomi's backups (Phase 6 Stage 6, madoyomi.core.backup): `.madoyomi-backup` files in `<data dir>/backups`, made
 * now or on a schedule, checked against this install before a restore, restored with merge or replace. Named apart
 * from upstream's manga-only `createBackup`/`restoreBackup`/`validateBackup`/`restoreStatus`, which stay.
 */

private fun userOf(env: DataFetchingEnvironment): Int = env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()

@GraphQLDescription("The scheduled backups (Settings → Backup)")
data class MadoyomiBackupSettingsType(
    @GraphQLDescription("Hours between scheduled backups: 0 (off), 24 or 168")
    val intervalHours: Int,
    @GraphQLDescription("How many scheduled backups are kept (manual ones are never deleted)")
    val keep: Int,
    @GraphQLDescription("Epoch milliseconds of the next scheduled backup; null while the schedule is off")
    val nextScheduledAt: Long?,
    @GraphQLDescription("The folder the backups are written to")
    val folder: String,
    @GraphQLDescription("Why the last scheduled backup failed, if it did")
    val lastError: String?,
) {
    constructor(
        s: BackupSettings,
    ) : this(s.intervalHours, s.keep, BackupScheduler.nextAt, BackupFiles.dir().absolutePath, BackupScheduler.lastError)
}

class MadoyomiBackupQuery {
    @RequireAuth
    @GraphQLDescription("The backup files in the backups folder (Madoyomi's, and any .tachibk), the newest first")
    fun madoyomiBackups(): List<BackupFileInfo> = BackupFiles.list()

    @RequireAuth
    fun madoyomiBackupSettings(): MadoyomiBackupSettingsType = MadoyomiBackupSettingsType(BackupSettingsStore.current())

    @RequireAuth
    @GraphQLDescription(
        "What the backup file holds, checked against this install: its repos, certificates and extensions (the trust " +
            "prompt), and the sources its entries need that aren't installed. BAD_REQUEST when it isn't a backup",
    )
    fun validateMadoyomiBackup(name: String): CompletableFuture<BackupReport> = future { MadoyomiBackupRestore.validate(name) }

    @RequireAuth
    @GraphQLDescription("The restore that runs, else the last one since the start")
    fun madoyomiRestoreStatus(): RestoreStatus = MadoyomiBackupRestore.status.value
}

class MadoyomiBackupMutation {
    data class CreateMadoyomiBackupInput(
        val clientMutationId: String? = null,
    )

    data class CreateMadoyomiBackupPayload(
        val clientMutationId: String?,
        val backup: BackupFileInfo,
    )

    data class DeleteMadoyomiBackupInput(
        val clientMutationId: String? = null,
        val name: String,
    )

    data class DeleteMadoyomiBackupPayload(
        val clientMutationId: String?,
        val backups: List<BackupFileInfo>,
    )

    data class SetMadoyomiBackupSettingsInput(
        val clientMutationId: String? = null,
        val intervalHours: Int? = null,
        val keep: Int? = null,
    )

    data class SetMadoyomiBackupSettingsPayload(
        val clientMutationId: String?,
        val settings: MadoyomiBackupSettingsType,
    )

    data class RestoreMadoyomiBackupInput(
        val clientMutationId: String? = null,
        val name: String,
        @GraphQLDescription("MERGE (the default) or REPLACE")
        val mode: RestoreMode? = null,
        @GraphQLDescription("The entries, categories, progress and history (default true)")
        val library: Boolean? = null,
        @GraphQLDescription("The settings files and the sources' preferences (default true)")
        val settings: Boolean? = null,
        @GraphQLDescription("The backup's repos to add, by index URL: adding one trusts its signing key")
        val repos: List<String>? = null,
        @GraphQLDescription("The backup's certificates to trust, by hash")
        val certificates: List<String>? = null,
        @GraphQLDescription("The backup's extensions to reinstall from their repos, by package name")
        val extensions: List<String>? = null,
    )

    data class RestoreMadoyomiBackupPayload(
        val clientMutationId: String?,
        val status: RestoreStatus,
    )

    @RequireAuth
    @GraphQLDescription("Writes a backup of everything now into the backups folder")
    fun createMadoyomiBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateMadoyomiBackupInput? = null,
    ): CompletableFuture<CreateMadoyomiBackupPayload> {
        val userId = userOf(dataFetchingEnvironment)
        return future {
            val file = BackupFiles.write(BackupFiles.encode(MadoyomiBackupExport.create(userId)), automatic = false)
            CreateMadoyomiBackupPayload(input?.clientMutationId, BackupFiles.info(file))
        }
    }

    @RequireAuth
    fun deleteMadoyomiBackup(input: DeleteMadoyomiBackupInput): DeleteMadoyomiBackupPayload {
        BackupFiles.delete(input.name)
        return DeleteMadoyomiBackupPayload(input.clientMutationId, BackupFiles.list())
    }

    @RequireAuth
    fun setMadoyomiBackupSettings(input: SetMadoyomiBackupSettingsInput): SetMadoyomiBackupSettingsPayload =
        SetMadoyomiBackupSettingsPayload(
            input.clientMutationId,
            MadoyomiBackupSettingsType(BackupSettingsStore.update(BackupSettings.Patch(input.intervalHours, input.keep))),
        )

    @RequireAuth
    @GraphQLDescription(
        "Starts restoring a backup file in the background (one at a time); madoyomiRestoreStatusChanged follows it. " +
            "Only the repos, certificates and extensions listed are added, trusted or installed",
    )
    fun restoreMadoyomiBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RestoreMadoyomiBackupInput,
    ): CompletableFuture<RestoreMadoyomiBackupPayload> {
        val userId = userOf(dataFetchingEnvironment)
        return future {
            val status =
                MadoyomiBackupRestore.start(
                    userId,
                    RestoreRequest(
                        name = input.name,
                        mode = input.mode ?: RestoreMode.MERGE,
                        library = input.library ?: true,
                        settings = input.settings ?: true,
                        repos = input.repos.orEmpty(),
                        certificates = input.certificates.orEmpty(),
                        extensions = input.extensions.orEmpty(),
                    ),
                )
            RestoreMadoyomiBackupPayload(input.clientMutationId, status)
        }
    }
}

class MadoyomiBackupSubscription {
    @RequireAuth
    @GraphQLDescription("The restore's status: at once on subscribing, then on every change; the latest one wins")
    fun madoyomiRestoreStatusChanged(): Flow<RestoreStatus> = MadoyomiBackupRestore.status
}
