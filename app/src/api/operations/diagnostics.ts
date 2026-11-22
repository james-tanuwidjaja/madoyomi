import { api } from "../client";

/*
 * What the backend adds to Export logs: a JSON document with its versions, the repos, the installed
 * extensions and the settings without secrets, and the library's titles only when asked. The shell writes it into the
 * zip as diagnostics.json.
 */

export const DIAGNOSTICS_QUERY = /* GraphQL */ `
  query Diagnostics($includeLibrary: Boolean) {
    diagnostics(includeLibrary: $includeLibrary)
  }
`;

/** The backend's report, as the JSON text it sends. */
export async function fetchDiagnostics(
  includeLibrary: boolean,
  signal?: AbortSignal,
): Promise<string> {
  return (await api.gql<{ diagnostics: string }>(DIAGNOSTICS_QUERY, { includeLibrary }, { signal }))
    .diagnostics;
}
