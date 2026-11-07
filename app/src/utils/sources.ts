import { LOCAL_SOURCE_ID, type SourceBase } from "../api/types";

/**
 * Whether a source can be browsed:
 * - `builtIn`: upstream's Local source (manga, id 0). Its fake extension has no signature, so it reads as untrusted,
 *   but it is part of the backend;
 * - `ok`: its extension is trusted and loaded;
 * - `untrusted`: its extension's certificate isn't trusted, so the backend doesn't load it (the trust prompt, in
 *   Extensions, fixes that);
 * - `notLoaded`: trusted, but the backend couldn't load it (its install error is shown in Extensions).
 */
export type SourceAccess = "builtIn" | "ok" | "untrusted" | "notLoaded";

export function sourceAccess(s: Pick<SourceBase, "id" | "extension">): SourceAccess {
  if (s.id === LOCAL_SOURCE_ID) return "builtIn";
  if (!s.extension.isTrusted) return "untrusted";
  if (!s.extension.isLoaded) return "notLoaded";
  return "ok";
}

export const canBrowse = (access: SourceAccess) => access === "builtIn" || access === "ok";
