import { computed } from "vue";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  createBackup,
  deleteBackup,
  fetchBackups,
  fetchBackupSettings,
  fetchRestoreStatus,
  restoreBackup,
  setBackupSettings,
  uploadBackup,
} from "../api/operations";
import type { BackupFile, BackupSettings, RestoreRequest, RestoreStatus } from "../api/types";
import { useBackendStore } from "../stores/backend";
import { queryKeys } from "./queryKeys";

/*
 * Settings → Backup (Phase 6 Stage 6): the backup files, the schedule, Back up now, delete, a picked file kept in the
 * folder, and the restore with its status (kept current by the `madoyomiRestoreStatusChanged` subscription in
 * api/realtime, which refetches everything once a restore ends).
 */

/** How often Settings → Backup fetches the files and the schedule again while it is open. */
export const BACKUP_LIST_REFRESH_MS = 15_000;

export function useBackupFiles() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.backupFiles(),
    queryFn: ({ signal }) => fetchBackups(signal),
    enabled: computed(() => backend.ready),
    // the schedule writes and prunes files while Settings is open (found in the live check)
    refetchInterval: BACKUP_LIST_REFRESH_MS,
  });
}

export function useBackupSettings() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.backupSettings(),
    queryFn: ({ signal }) => fetchBackupSettings(signal),
    enabled: computed(() => backend.ready),
    staleTime: 10_000,
    // "Next at" moves on after each scheduled backup
    refetchInterval: BACKUP_LIST_REFRESH_MS,
  });
}

/** Changes the schedule: shown at once, rolled back on an error; a smaller "keep" prunes, so the list is refetched. */
export function useSetBackupSettings() {
  const queryClient = useQueryClient();
  const key = queryKeys.backupSettings();
  return useMutation({
    mutationFn: (patch: Partial<Pick<BackupSettings, "intervalHours" | "keep">>) =>
      setBackupSettings(patch),
    scope: { id: "backupSettings" },
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<BackupSettings>(key);
      if (previous) queryClient.setQueryData<BackupSettings>(key, { ...previous, ...patch });
      return { previous };
    },
    onError: (_e, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onSuccess: (settings) => {
      queryClient.setQueryData(key, settings);
      void queryClient.invalidateQueries({ queryKey: queryKeys.backupFiles() });
    },
  });
}

export function useCreateBackup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => createBackup(),
    onSuccess: (file) => {
      queryClient.setQueryData<BackupFile[]>(queryKeys.backupFiles(), (list) =>
        list ? [file, ...list.filter((f) => f.name !== file.name)] : [file],
      );
    },
  });
}

export function useDeleteBackup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => deleteBackup(name),
    onSuccess: (list) => queryClient.setQueryData(queryKeys.backupFiles(), list),
    // a file the schedule pruned in between is NOT_FOUND: the list is fetched again either way
    onError: () => void queryClient.invalidateQueries({ queryKey: queryKeys.backupFiles() }),
  });
}

/** A file the user picked, kept in the backups folder; resolves to its name there. */
export function useUploadBackup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => uploadBackup(file, file.name),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: queryKeys.backupFiles() }),
  });
}

export function useRestoreStatus() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.restoreStatus(),
    queryFn: ({ signal }) => fetchRestoreStatus(signal),
    enabled: computed(() => backend.ready),
  });
}

export function useRestoreBackup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: RestoreRequest) => restoreBackup(request),
    onSuccess: (status) =>
      queryClient.setQueryData<RestoreStatus>(queryKeys.restoreStatus(), status),
  });
}
