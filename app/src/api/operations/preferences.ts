import { api } from "../client";
import type { MediaKind, Preference, Source, SourceBase, SourcePreferenceChange } from "../types";
import { notFound, SOURCE_FIELDS } from "./sources";

/*
 * A source's own settings (Phase 3 step 19), in upstream's `Preference` union for both kinds: anime through
 * Madoyomi's `animeSources(condition: {id}).preferences` and `updateAnimeSourcePreference`, manga through upstream's
 * `source(id).preferences` and `updateSourcePreference` (server/UPSTREAM.md "Anime sources (Phase 2)").
 */

/**
 * The `Preference` union. Its members have fields of the same name and different types (`currentValue`, `default`),
 * which one selection can't hold, so each is aliased by its kind (`switchCurrentValue`, …), as in types.ts.
 */
export const PREFERENCE_FIELDS = /* GraphQL */ `
  fragment PreferenceFields on Preference {
    __typename
    ... on SwitchPreference {
      key
      title
      summary
      visible
      enabled
      switchCurrentValue: currentValue
      switchDefault: default
    }
    ... on CheckBoxPreference {
      key
      title
      summary
      visible
      enabled
      checkBoxCurrentValue: currentValue
      checkBoxDefault: default
    }
    ... on EditTextPreference {
      key
      title
      summary
      visible
      enabled
      editTextCurrentValue: currentValue
      editTextDefault: default
      dialogTitle
      dialogMessage
      text
    }
    ... on ListPreference {
      key
      title
      summary
      visible
      enabled
      listCurrentValue: currentValue
      listDefault: default
      entries
      entryValues
    }
    ... on MultiSelectListPreference {
      key
      title
      summary
      visible
      enabled
      multiSelectCurrentValue: currentValue
      multiSelectDefault: default
      dialogTitle
      dialogMessage
      entries
      entryValues
    }
  }
`;

export const ANIME_SOURCE_PREFERENCES_QUERY = /* GraphQL */ `
  query AnimeSourcePreferences($id: LongString!) {
    animeSources(condition: { id: $id }) {
      nodes {
        id
        preferences {
          ...PreferenceFields
        }
      }
    }
  }
  ${PREFERENCE_FIELDS}
`;

export const MANGA_SOURCE_PREFERENCES_QUERY = /* GraphQL */ `
  query MangaSourcePreferences($id: LongString!) {
    source(id: $id) {
      id
      preferences {
        ...PreferenceFields
      }
    }
  }
  ${PREFERENCE_FIELDS}
`;

export const UPDATE_ANIME_SOURCE_PREFERENCE_MUTATION = /* GraphQL */ `
  mutation UpdateAnimeSourcePreference($input: UpdateAnimeSourcePreferenceInput!) {
    updateAnimeSourcePreference(input: $input) {
      preferences {
        ...PreferenceFields
      }
      source {
        ...SourceFields
      }
    }
  }
  ${PREFERENCE_FIELDS}
  ${SOURCE_FIELDS("AnimeSourceType")}
`;

export const UPDATE_MANGA_SOURCE_PREFERENCE_MUTATION = /* GraphQL */ `
  mutation UpdateMangaSourcePreference($input: UpdateSourcePreferenceInput!) {
    updateSourcePreference(input: $input) {
      preferences {
        ...PreferenceFields
      }
      source {
        ...SourceFields
      }
    }
  }
  ${PREFERENCE_FIELDS}
  ${SOURCE_FIELDS("SourceType")}
`;

/** A source's preferences, in the source's order: a change addresses one by its index here. */
export async function fetchSourcePreferences(
  kind: MediaKind,
  sourceId: string,
  signal?: AbortSignal,
): Promise<Preference[]> {
  if (kind === "anime") {
    const data = await api.gql<{ animeSources: { nodes: { preferences: Preference[] }[] } }>(
      ANIME_SOURCE_PREFERENCES_QUERY,
      { id: sourceId },
      { signal },
    );
    const source = data.animeSources.nodes[0];
    if (!source) throw notFound(sourceId);
    return source.preferences;
  }
  const data = await api.gql<{ source: { preferences: Preference[] } }>(
    MANGA_SOURCE_PREFERENCES_QUERY,
    { id: sourceId },
    { signal },
  );
  return data.source.preferences;
}

/** What a change answers: the preferences as stored afterwards, and the source (null only for an anime source without a row). */
export interface PreferenceUpdate {
  preferences: Preference[];
  source: Source | null;
}

/**
 * Changes one preference, by its position. The server answers with every preference as stored afterwards: an anime
 * extension's change listener may refuse the value, which isn't an error (the answer then still has the old value).
 *
 * Manga takes two calls: upstream's `updateSourcePreference` uses the screen its last read cached, and has none
 * (a null pointer, `INTERNAL`) before any read since the server started, e.g. right after a backend restart. So the
 * preferences are read first (Phase 3 §9 "Stage 6").
 */
export async function updateSourcePreference(
  kind: MediaKind,
  sourceId: string,
  change: SourcePreferenceChange,
): Promise<PreferenceUpdate> {
  const input = { source: sourceId, change };
  if (kind === "anime") {
    const data = await api.gql<{
      updateAnimeSourcePreference: { preferences: Preference[]; source: SourceBase | null };
    }>(UPDATE_ANIME_SOURCE_PREFERENCE_MUTATION, { input });
    const r = data.updateAnimeSourcePreference;
    return { preferences: r.preferences, source: r.source && { ...r.source, kind } };
  }
  await fetchSourcePreferences(kind, sourceId);
  const data = await api.gql<{
    updateSourcePreference: { preferences: Preference[]; source: SourceBase };
  }>(UPDATE_MANGA_SOURCE_PREFERENCE_MUTATION, { input });
  const r = data.updateSourcePreference;
  return { preferences: r.preferences, source: { ...r.source, kind } };
}
