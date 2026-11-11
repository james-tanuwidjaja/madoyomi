import { computed } from "vue";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { fetchPlayerLanguages, fetchPlayerSettings, setPlayerSettings } from "../api/operations";
import type { PlayerSettings, PlayerSettingsPatch } from "../api/types";
import { useBackendStore } from "../stores/backend";
import { WATCHED_THRESHOLD } from "../utils/progress";
import { queryKeys } from "./queryKeys";

/*
 * The backend's player settings (Phase 4 Stage 5, Settings → Player). The backend keeps and applies them; the app
 * reads them for the Settings section and for what it decides itself: the resume prompt's upper bound (the watched
 * threshold). The rest (the video order, the tracks, which mpv) the backend applies at the next play.
 */

/** The settings the backend uses when its file has none (as `PlayerSettings()` in Kotlin). */
export const DEFAULT_PLAYER_SETTINGS: PlayerSettings = {
  watchedThreshold: WATCHED_THRESHOLD,
  preferredQuality: "SOURCE",
  preferredServer: "",
  subtitleLanguage: "",
  audioLanguage: "",
  mpvPath: "",
};

export function usePlayerSettings() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.playerSettings(),
    queryFn: ({ signal }) => fetchPlayerSettings(signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
}

/** The watched threshold in use: the backend's setting once known, else the default (85 %). */
export function useWatchedThreshold() {
  const settings = usePlayerSettings();
  return computed(() => settings.data.value?.watchedThreshold ?? WATCHED_THRESHOLD);
}

/** The languages the subtitle and audio preferences can match (the backend's table). They don't change. */
export function usePlayerLanguages() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.playerLanguages(),
    queryFn: ({ signal }) => fetchPlayerLanguages(signal),
    enabled: computed(() => backend.ready),
    staleTime: Infinity,
  });
}

/**
 * Changes some player settings. The new values show at once (written into the cached settings); the backend's
 * answer replaces them, and a failure puts the previous ones back. Changes run one at a time, in order.
 */
export function useSetPlayerSettings() {
  const queryClient = useQueryClient();
  const key = queryKeys.playerSettings();
  return useMutation({
    mutationFn: (patch: PlayerSettingsPatch) => setPlayerSettings(patch),
    scope: { id: "playerSettings" },
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<PlayerSettings>(key);
      queryClient.setQueryData<PlayerSettings>(key, {
        ...DEFAULT_PLAYER_SETTINGS,
        ...previous,
        ...patch,
      });
      return { previous };
    },
    onError: (_e, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onSuccess: (settings) => {
      queryClient.setQueryData(key, settings);
    },
  });
}
