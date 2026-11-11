import { api } from "../client";
import type { MpvCheck, PlayerLanguage, PlayerSettings, PlayerSettingsPatch } from "../types";

/*
 * The player settings (Phase 4 Stage 5): kept by the backend in its data dir, because the backend applies them (the
 * watched mark, the video order, the subtitle and audio tracks, which mpv it starts). A change takes effect at the
 * next play; the watched threshold also in the session that plays.
 */

const PLAYER_SETTINGS_FIELDS = /* GraphQL */ `
  fragment PlayerSettingsFields on PlayerSettingsType {
    watchedThreshold
    preferredQuality
    preferredServer
    subtitleLanguage
    audioLanguage
    mpvPath
  }
`;

export const PLAYER_SETTINGS_QUERY = /* GraphQL */ `
  query PlayerSettings {
    playerSettings {
      ...PlayerSettingsFields
    }
  }
  ${PLAYER_SETTINGS_FIELDS}
`;

export const SET_PLAYER_SETTINGS_MUTATION = /* GraphQL */ `
  mutation SetPlayerSettings($patch: PlayerSettingsPatchInput!) {
    setPlayerSettings(input: { patch: $patch }) {
      settings {
        ...PlayerSettingsFields
      }
    }
  }
  ${PLAYER_SETTINGS_FIELDS}
`;

export const PLAYER_LANGUAGES_QUERY = /* GraphQL */ `
  query PlayerLanguages {
    playerLanguages {
      code
      name
    }
  }
`;

export const MPV_CHECK_QUERY = /* GraphQL */ `
  query MpvCheck($path: String) {
    mpvCheck(path: $path) {
      ok
      path
      origin
      version
      errorCode
      message
    }
  }
`;

export async function fetchPlayerSettings(signal?: AbortSignal): Promise<PlayerSettings> {
  return (
    await api.gql<{ playerSettings: PlayerSettings }>(PLAYER_SETTINGS_QUERY, undefined, { signal })
  ).playerSettings;
}

/** Changes the given fields and returns all of them as the backend kept them. */
export async function setPlayerSettings(patch: PlayerSettingsPatch): Promise<PlayerSettings> {
  return (
    await api.gql<{ setPlayerSettings: { settings: PlayerSettings } }>(
      SET_PLAYER_SETTINGS_MUTATION,
      { patch },
    )
  ).setPlayerSettings.settings;
}

export async function fetchPlayerLanguages(signal?: AbortSignal): Promise<PlayerLanguage[]> {
  return (
    await api.gql<{ playerLanguages: PlayerLanguage[] }>(PLAYER_LANGUAGES_QUERY, undefined, {
      signal,
    })
  ).playerLanguages;
}

/** Runs `mpv --version` on `path`, or on the mpv the backend would start now (`path` null). */
export async function checkMpv(path: string | null): Promise<MpvCheck> {
  return (await api.gql<{ mpvCheck: MpvCheck }>(MPV_CHECK_QUERY, { path })).mpvCheck;
}
